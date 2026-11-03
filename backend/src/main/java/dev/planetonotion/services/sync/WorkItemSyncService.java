package dev.planetonotion.services.sync;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.PlaneToNotionEnums.WorkItemLifecycle;
import dev.planetonotion.integrations.models.NotionRequestModels.DateProperty;
import dev.planetonotion.integrations.models.NotionRequestModels.DateValue;
import dev.planetonotion.integrations.models.NotionRequestModels.MultiSelectProperty;
import dev.planetonotion.integrations.models.NotionRequestModels.NamedOption;
import dev.planetonotion.integrations.models.NotionRequestModels.RichText;
import dev.planetonotion.integrations.models.NotionRequestModels.RichTextProperty;
import dev.planetonotion.integrations.models.NotionRequestModels.SelectProperty;
import dev.planetonotion.integrations.models.NotionRequestModels.TitleProperty;
import dev.planetonotion.integrations.models.NotionRequestModels.UrlProperty;
import dev.planetonotion.integrations.models.NotionRequestModels.WorkItemProperties;
import dev.planetonotion.integrations.models.PlaneApiModels.Label;
import dev.planetonotion.integrations.models.PlaneApiModels.Person;
import dev.planetonotion.integrations.models.PlaneApiModels.State;
import dev.planetonotion.integrations.models.PlaneApiModels.WorkItem;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.repositories.SyncMappingRepository;
import dev.planetonotion.integrations.PlaneClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static dev.planetonotion.integrations.PlaneApiConstants.Priority.NONE;

/**
 * Synchronizes eligible Plane work items into configured or ad hoc Notion data
 * sources and persists the mapping state for subsequent runs.
 */
@Service
public class WorkItemSyncService {

    private static final JsonMapper HASH_MAPPER = JsonMapper.builder().build();
    private static final int MONGO_BATCH_SIZE = 1000;

    private final PlaneClient planeClient;
    private final NotionClient notionClient;
    private final SyncMappingRepository mappingRepository;

    /** Creates the sync service with provider clients and mapping storage. */
    public WorkItemSyncService(PlaneClient planeClient, NotionClient notionClient,
            SyncMappingRepository mappingRepository) {
        this.planeClient = planeClient;
        this.notionClient = notionClient;
        this.mappingRepository = mappingRepository;
    }

    /**
     * Streams a project's work items, optionally within an updated-time window,
     * and creates or updates their Notion rows in bounded batches.
     *
     * @param project          Plane project and configured Notion destination
     * @param updatedAtFrom    optional inclusive update-time lower bound
     * @param updatedAtTo      optional exclusive update-time upper bound
     * @param progressConsumer receives current counts as items are processed
     * @return final eligible, created, updated, and unchanged counts
     */
    public SyncResult syncProject(PlaneProjectDocument project, Instant updatedAtFrom,
            Instant updatedAtTo, Consumer<SyncResult> progressConsumer) {
        // Validate destination and synchronize options.
        String notionDataSourceId = project.notionDataSourceId();
        if (notionDataSourceId == null || notionDataSourceId.isBlank()) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.SYNC_DESTINATION_MISSING,
                    "The project's Notion destination is not configured.");
        }
        List<String> states = planeClient.listWorkItemStates(project.planeWorkspaceSlug(), project.planeProjectId())
                .stream().map(State::name).filter(name -> name != null && !name.isBlank()).distinct().toList();
        List<String> labels = planeClient.listWorkItemLabels(project.planeWorkspaceSlug(), project.planeProjectId())
                .stream().map(Label::name).filter(name -> name != null && !name.isBlank()).distinct().toList();
        notionClient.ensureWorkItemOptions(notionDataSourceId, states, labels);

        // Stream Plane pages into bounded batches.
        SyncAccumulator accumulator = new SyncAccumulator();
        List<WorkItem> batch = new ArrayList<>(MONGO_BATCH_SIZE);
        planeClient.forEachProjectWorkItemPage(project.planeWorkspaceSlug(), project.planeProjectId(), updatedAtFrom,
                updatedAtTo, page -> {
                    for (WorkItem workItem : page) {
                        batch.add(workItem);
                        if (batch.size() == MONGO_BATCH_SIZE)
                            processBatch(notionDataSourceId, project, batch, accumulator, progressConsumer);
                    }
                });
        // Process final batch before returning.
        if (!batch.isEmpty())
            processBatch(notionDataSourceId, project, batch, accumulator, progressConsumer);
        return accumulator.result();
    }

    /**
     * Synchronizes one work item into the project's configured data source.
     *
     * @param project          project whose configured Notion destination receives
     *                         the row
     * @param workItemId       Plane work-item UUID
     * @param progressConsumer receives current counts as processing completes
     * @return result counts for the selected item
     */
    public SyncResult syncOne(PlaneProjectDocument project, String workItemId,
            Consumer<SyncResult> progressConsumer) {
        return syncOne(project.notionDataSourceId(), project, workItemId, false, progressConsumer);
    }

    /**
     * Synchronizes one work item into the shared ad hoc data source.
     *
     * @param project            Plane project used to add source identity to the
     *                           row
     * @param workItemId         Plane work-item UUID
     * @param notionDataSourceId shared ad hoc Notion data-source ID
     * @param progressConsumer   receives current counts as processing completes
     * @return result counts for the selected item
     */
    public SyncResult syncOneAdHoc(PlaneProjectDocument project, String workItemId, String notionDataSourceId,
            Consumer<SyncResult> progressConsumer) {
        return syncOne(notionDataSourceId, project, workItemId, true, progressConsumer);
    }

    /** Reads one Plane item and upserts its Notion row and mapping. */
    private SyncResult syncOne(String notionDataSourceId, PlaneProjectDocument project, String workItemId,
            boolean adHoc,
            Consumer<SyncResult> progressConsumer) {
        // Read and validate requested work item.
        WorkItem workItem = planeClient.getWorkItem(project.planeWorkspaceSlug(), project.planeProjectId(),
                workItemId);
        assertEligible(workItem);
        progressConsumer.accept(new SyncResult(1, 0, 0, 0));
        // Ensure options and find existing mapping.
        List<String> stateNames = workItem.state() == null || workItem.state().name() == null
                ? List.of()
                : List.of(workItem.state().name());
        List<String> labelNames = namesLabels(workItem.labels());
        notionClient.ensureWorkItemOptions(notionDataSourceId, stateNames, labelNames);
        List<SyncMappingDocument> mappings = mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn(
                notionDataSourceId, List.of(workItem.id()));
        SyncMappingDocument mapping = mappings.stream().findFirst().orElse(null);
        // Save pending mapping before Notion writes.
        boolean newPendingMapping = mapping == null;
        if (newPendingMapping) {
            mapping = pendingMapping(workItem.id(), notionDataSourceId);
            mappingRepository.bulkUpsert(List.of(mapping));
        }
        // Upsert page and persist mapping changes.
        UpsertResult result = upsert(notionDataSourceId, project, workItem, adHoc, mapping, newPendingMapping);
        if (result.mapping() != null)
            mappingRepository.bulkUpsert(List.of(result.mapping()));
        SyncResult syncResult = new SyncResult(1, result.created() ? 1 : 0, result.updated() ? 1 : 0,
                result.unchanged() ? 1 : 0);
        progressConsumer.accept(syncResult);
        return syncResult;
    }

    /** Processes a bounded batch and flushes mappings around failures. */
    private void processBatch(String notionDataSourceId, PlaneProjectDocument project, List<WorkItem> batch,
            SyncAccumulator accumulator, Consumer<SyncResult> progressConsumer) {
        // Load mappings and prepare missing placeholders.
        List<String> planeWorkItemIds = batch.stream().map(WorkItem::id)
                .filter(planeWorkItemId -> !planeWorkItemId.isBlank()).toList();
        Map<String, SyncMappingDocument> mappingsByPlaneWorkItemId = new LinkedHashMap<>();
        mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn(notionDataSourceId, planeWorkItemIds)
                .forEach(mapping -> mappingsByPlaneWorkItemId.put(mapping.planeWorkItemId(), mapping));
        List<SyncMappingDocument> pendingMappings = new ArrayList<>();
        Set<String> pendingCreatedSourceIds = new HashSet<>();
        for (String planeWorkItemId : planeWorkItemIds) {
            if (!mappingsByPlaneWorkItemId.containsKey(planeWorkItemId)) {
                SyncMappingDocument pending = pendingMapping(planeWorkItemId, notionDataSourceId);
                mappingsByPlaneWorkItemId.put(planeWorkItemId, pending);
                pendingMappings.add(pending);
                pendingCreatedSourceIds.add(planeWorkItemId);
            }
        }
        // Persist placeholders before processing items.
        if (!pendingMappings.isEmpty())
            mappingRepository.bulkUpsert(pendingMappings);
        List<SyncMappingDocument> mappingsToWrite = new ArrayList<>();
        // Upsert each item while tracking progress.
        try {
            for (WorkItem workItem : batch) {
                accumulator.eligibleCount++;
                progressConsumer.accept(accumulator.result());
                String planeWorkItemId = workItem.id();
                if (planeWorkItemId.isBlank()) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, ApiErrorCodes.Plane.RESPONSE_INVALID,
                            "Plane returned a work item without an ID.");
                }
                boolean pendingCreatedNow = pendingCreatedSourceIds.contains(planeWorkItemId);
                UpsertResult result = upsert(notionDataSourceId, project, workItem, false,
                        mappingsByPlaneWorkItemId.get(planeWorkItemId), pendingCreatedNow);
                if (result.mapping() != null)
                    mappingsToWrite.add(result.mapping());
                if (result.created())
                    accumulator.createdCount++;
                else if (result.updated())
                    accumulator.updatedCount++;
                else if (result.unchanged())
                    accumulator.unchangedCount++;
                progressConsumer.accept(accumulator.result());
            }
        } catch (RuntimeException exception) {
            // Save completed mappings before propagating failure.
            if (!mappingsToWrite.isEmpty())
                mappingRepository.bulkUpsert(mappingsToWrite);
            throw exception;
        }
        // Persist mappings and release the batch.
        if (!mappingsToWrite.isEmpty())
            mappingRepository.bulkUpsert(mappingsToWrite);
        batch.clear();
    }

    /** Creates, updates, recovers, or skips one Notion work-item row. */
    private UpsertResult upsert(String notionDataSourceId, PlaneProjectDocument project, WorkItem workItem,
            boolean adHoc, SyncMappingDocument mapping, boolean pendingCreatedNow) {
        // Build mapped properties and source hash.
        String planeWorkItemId = workItem.id();
        WorkItemProperties properties = notionProperties(project, workItem, adHoc);
        String planeSourceHash = planeSourceHash(properties);
        // Reuse or repair an existing mapping.
        if (mapping != null) {
            if (mapping.notionPageId() == null || mapping.notionPageId().isBlank()) {
                if (!pendingCreatedNow) {
                    // Recover pages for pending mappings.
                    var matches = notionClient.findWorkItemBySourceId(notionDataSourceId, planeWorkItemId);
                    var foundPage = matches.results() == null || matches.results().isEmpty()
                            ? null
                            : matches.results().get(0);
                    if (foundPage != null) {
                        String recoveredTargetId = foundPage.id();
                        if (recoveredTargetId.isBlank()) {
                            throw new IntegrationException(ApiErrorCodes.Notion.RESPONSE_INVALID, 502,
                                    "Notion returned a matching work item without a page ID.");
                        }
                        notionClient.updateWorkItem(recoveredTargetId, properties);
                        return new UpsertResult(false, true, false,
                                new SyncMappingDocument(mapping.id(), planeWorkItemId, recoveredTargetId,
                                        notionDataSourceId, planeSourceHash));
                    }
                }
                return createWithMapping(notionDataSourceId, planeWorkItemId, planeSourceHash, properties,
                        mapping.id());
            }
            // Skip writes when the source matches.
            boolean unchanged = planeSourceHash.equals(mapping.planeSourceHash());
            if (unchanged)
                return new UpsertResult(false, false, true, null);
            // Update the existing Notion page.
            try {
                notionClient.updateWorkItem(mapping.notionPageId(), properties);
                return new UpsertResult(false, true, false,
                        new SyncMappingDocument(mapping.id(), planeWorkItemId, mapping.notionPageId(),
                                notionDataSourceId, planeSourceHash));
            } catch (IntegrationException exception) {
                if (exception.upstreamStatus() != 404)
                    throw exception;
                // Mark mappings pending after Notion 404.
                mappingRepository
                        .bulkUpsert(List.of(pendingMapping(planeWorkItemId, notionDataSourceId, mapping.id())));
            }
        }
        return createWithMapping(notionDataSourceId, planeWorkItemId, planeSourceHash, properties,
                mapping == null ? null : mapping.id());
    }

    /** Creates a Notion row and returns its new mapping. */
    private UpsertResult createWithMapping(String notionDataSourceId, String planeWorkItemId, String planeSourceHash,
            WorkItemProperties properties, ObjectId mappingId) {
        // Create page and validate returned ID.
        var page = notionClient.createWorkItem(notionDataSourceId, properties);
        String targetId = page.id();
        if (targetId.isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.RESPONSE_INVALID, 502,
                    "Notion did not return the created work item page ID.");
        }
        return new UpsertResult(true, false, false,
                new SyncMappingDocument(mappingId, planeWorkItemId, targetId, notionDataSourceId, planeSourceHash));
    }

    /** Creates a placeholder mapping before Notion creation. */
    private SyncMappingDocument pendingMapping(String planeWorkItemId, String notionDataSourceId) {
        return pendingMapping(planeWorkItemId, notionDataSourceId, null);
    }

    /** Creates a placeholder while preserving an existing mapping ID. */
    private SyncMappingDocument pendingMapping(String planeWorkItemId, String notionDataSourceId,
            ObjectId id) {
        return new SyncMappingDocument(id, planeWorkItemId, null, notionDataSourceId, "");
    }

    /** Hashes canonical mapped properties to detect unchanged items. */
    private String planeSourceHash(Object properties) {
        try {
            // Serialize stable properties before hashing.
            Object serializedProperties = HASH_MAPPER.readValue(
                    HASH_MAPPER.writeValueAsBytes(properties), Object.class);
            byte[] payload = HASH_MAPPER.writeValueAsBytes(sorted(serializedProperties));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    /** Sorts nested maps while preserving list order. */
    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            // Sort object keys for deterministic hashes.
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), sorted(child)));
            return sorted;
        }
        // Preserve list order while sorting values.
        if (value instanceof List<?> list)
            return list.stream().map(this::sorted).toList();
        return value;
    }

    /** Maps Plane work-item fields to the Notion row schema. */
    private WorkItemProperties notionProperties(PlaneProjectDocument project, WorkItem workItem, boolean adHoc) {
        // Normalize all Plane fields before mapping.
        String workItemId = workItem.id();
        String state = workItem.state() == null || workItem.state().name() == null ? "" : workItem.state().name();
        String priority = workItem.priority();
        String targetDate = workItem.targetDate() == null ? "" : workItem.targetDate();
        String updatedAt = workItem.updatedAt() == null ? "" : workItem.updatedAt();
        return new WorkItemProperties(
                TitleProperty.of(richText(defaultValue(workItem.name(), "Untitled work item"))),
                RichTextProperty.of(richText(defaultValue(workItem.identifier(), ""))),
                RichTextProperty.of(richText(workItemId)),
                UrlProperty.of(planeClient.workItemUrl(
                        project.planeWorkspaceSlug(), project.planeProjectId(), workItemId)),
                RichTextProperty.of(richText(plainText(defaultValue(workItem.descriptionHtml(), "")))),
                state.isBlank() ? null : SelectProperty.of(new NamedOption(state)),
                priority == null || priority.isBlank() || NONE.equalsIgnoreCase(priority)
                        ? null
                        : SelectProperty.of(new NamedOption(priority)),
                RichTextProperty.of(richText(String.join(", ", namesPeople(workItem.assignees())))),
                MultiSelectProperty.of(namesLabels(workItem.labels()).stream().map(NamedOption::new).toList()),
                targetDate.isBlank() ? null : DateProperty.of(new DateValue(targetDate)),
                updatedAt.isBlank() ? null : DateProperty.of(new DateValue(updatedAt)),
                SelectProperty.of(new NamedOption(WorkItemLifecycle.ACTIVE.name())),
                adHoc ? RichTextProperty.of(richText(project.planeWorkspaceSlug())) : null,
                adHoc ? RichTextProperty.of(richText(project.planeProjectId())) : null,
                adHoc ? RichTextProperty.of(richText(project.planeProjectIdentifier())) : null,
                adHoc ? RichTextProperty.of(richText(project.planeProjectName())) : null);
    }

    /** Rejects drafts and archived work items from synchronization. */
    private void assertEligible(WorkItem workItem) {
        // Exclude drafts and archived source items.
        if (Boolean.TRUE.equals(workItem.isDraft())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.DRAFT_WORK_ITEM,
                    ApiErrorMessages.Application.DRAFT_WORK_ITEM);
        }
        if (workItem.archivedAt() != null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.ARCHIVED_WORK_ITEM,
                    ApiErrorMessages.Application.ARCHIVED_WORK_ITEM);
        }
    }

    /** Splits text into Notion-sized rich-text fragments. */
    private List<RichText> richText(String value) {
        if (value == null || value.isBlank())
            return List.of();
        List<RichText> result = new ArrayList<>();
        // Split long text into API-sized pieces.
        for (int start = 0; start < value.length(); start += 2000) {
            String chunk = value.substring(start, Math.min(value.length(), start + 2000));
            result.add(RichText.plain(chunk));
        }
        return result;
    }

    /** Returns distinct nonblank Plane assignee names. */
    private List<String> namesPeople(List<Person> values) {
        if (values == null)
            return List.of();
        // Prefer display names, then full names.
        return values.stream().map(person -> {
            String name = defaultValue(person.displayName(), "");
            if (name.isBlank())
                name = (defaultValue(person.firstName(), "") + " " + defaultValue(person.lastName(), "")).trim();
            return name;
        }).filter(name -> !name.isBlank()).distinct().toList();
    }

    /** Returns distinct nonblank Plane label names. */
    private List<String> namesLabels(List<Label> values) {
        if (values == null)
            return List.of();
        // Ignore blank labels and remove duplicates.
        return values.stream().map(Label::name).filter(name -> name != null && !name.isBlank()).distinct().toList();
    }

    /** Uses the fallback only when the value is null. */
    private String defaultValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** Converts simple Plane HTML descriptions into plain text. */
    private String plainText(String html) {
        // Convert common HTML tags and entities.
        return html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])\\s*>", "\n")
                .replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    /** Per-run counts for selected and synchronized work items. */
    public record SyncResult(int eligibleCount, int createdCount, int updatedCount, int unchangedCount) {
    }

    /** Outcome and optional mapping produced by one row upsert. */
    private record UpsertResult(boolean created, boolean updated, boolean unchanged, SyncMappingDocument mapping) {
    }

    private static final class SyncAccumulator {
        private int eligibleCount;
        private int createdCount;
        private int updatedCount;
        private int unchangedCount;

        /** Copies the accumulator's current counts into a result. */
        private SyncResult result() {
            return new SyncResult(eligibleCount, createdCount, updatedCount, unchangedCount);
        }
    }
}
