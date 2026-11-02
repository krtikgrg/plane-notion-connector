package dev.planetonotion.services.sync;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.bson.types.ObjectId;

import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.repositories.SyncMappingRepository;
import dev.planetonotion.services.PlaneService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkItemSyncService {

    private static final JsonMapper HASH_MAPPER = JsonMapper.builder().build();
    private static final int MONGO_BATCH_SIZE = 1000;

    private final PlaneService planeService;
    private final NotionClient notionClient;
    private final SyncMappingRepository mappingRepository;

    public WorkItemSyncService(PlaneService planeService, NotionClient notionClient,
            SyncMappingRepository mappingRepository) {
        this.planeService = planeService;
        this.notionClient = notionClient;
        this.mappingRepository = mappingRepository;
    }

    public SyncResult syncProject(PlaneProjectDocument project, Instant updatedAtFrom,
            Instant updatedAtTo, Consumer<SyncResult> progressConsumer) {
        String notionDataSourceId = project.notionDataSourceId();
        if (notionDataSourceId == null || notionDataSourceId.isBlank()) {
            throw new ServiceException(HttpStatus.CONFLICT, "SYNC_DESTINATION_MISSING",
                    "The project's Notion destination is not configured.");
        }
        List<String> states = names(
                planeService.listWorkItemStates(project.planeWorkspaceSlug(), project.planeProjectId()));
        List<String> labels = names(
                planeService.listWorkItemLabels(project.planeWorkspaceSlug(), project.planeProjectId()));
        notionClient.ensureWorkItemOptions(notionDataSourceId, states, labels);

        SyncAccumulator accumulator = new SyncAccumulator();
        List<JsonNode> batch = new ArrayList<>(MONGO_BATCH_SIZE);
        planeService.forEachProjectWorkItemPage(project.planeWorkspaceSlug(), project.planeProjectId(), updatedAtFrom,
                updatedAtTo, page -> {
                    for (JsonNode workItem : page) {
                        batch.add(workItem);
                        if (batch.size() == MONGO_BATCH_SIZE)
                            processBatch(notionDataSourceId, project, batch, accumulator, progressConsumer);
                    }
                });
        if (!batch.isEmpty())
            processBatch(notionDataSourceId, project, batch, accumulator, progressConsumer);
        return accumulator.result();
    }

    public SyncResult syncOne(PlaneProjectDocument project, String workItemId,
            Consumer<SyncResult> progressConsumer) {
        return syncOne(project.notionDataSourceId(), project, workItemId, false, progressConsumer);
    }

    public SyncResult syncOneAdHoc(PlaneProjectDocument project, String workItemId, String notionDataSourceId,
            Consumer<SyncResult> progressConsumer) {
        return syncOne(notionDataSourceId, project, workItemId, true, progressConsumer);
    }

    private SyncResult syncOne(String notionDataSourceId, PlaneProjectDocument project, String workItemId,
            boolean adHoc,
            Consumer<SyncResult> progressConsumer) {
        JsonNode workItem = planeService.getWorkItem(project.planeWorkspaceSlug(), project.planeProjectId(),
                workItemId);
        assertEligible(workItem);
        progressConsumer.accept(new SyncResult(1, 0, 0, 0));
        List<String> stateNames = namesFromExpanded(workItem.path("state"));
        List<String> labelNames = names(workItem.path("labels"));
        notionClient.ensureWorkItemOptions(notionDataSourceId, stateNames, labelNames);
        List<SyncMappingDocument> mappings = mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn(
                notionDataSourceId, List.of(workItem.path("id").asString("")));
        SyncMappingDocument mapping = mappings.stream().findFirst().orElse(null);
        boolean newPendingMapping = mapping == null;
        if (newPendingMapping) {
            mapping = pendingMapping(workItem.path("id").asString(""), notionDataSourceId);
            mappingRepository.bulkUpsert(List.of(mapping));
        }
        UpsertResult result = upsert(notionDataSourceId, project, workItem, adHoc, mapping, newPendingMapping);
        if (result.mapping() != null)
            mappingRepository.bulkUpsert(List.of(result.mapping()));
        SyncResult syncResult = new SyncResult(1, result.created() ? 1 : 0, result.updated() ? 1 : 0,
                result.unchanged() ? 1 : 0);
        progressConsumer.accept(syncResult);
        return syncResult;
    }

    private void processBatch(String notionDataSourceId, PlaneProjectDocument project, List<JsonNode> batch,
            SyncAccumulator accumulator, Consumer<SyncResult> progressConsumer) {
        List<String> planeWorkItemIds = batch.stream().map(item -> item.path("id").asString(""))
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
        if (!pendingMappings.isEmpty())
            mappingRepository.bulkUpsert(pendingMappings);
        List<SyncMappingDocument> mappingsToWrite = new ArrayList<>();
        try {
            for (JsonNode workItem : batch) {
                accumulator.eligibleCount++;
                progressConsumer.accept(accumulator.result());
                String planeWorkItemId = workItem.path("id").asString("");
                if (planeWorkItemId.isBlank()) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "PLANE_RESPONSE_INVALID",
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
            if (!mappingsToWrite.isEmpty())
                mappingRepository.bulkUpsert(mappingsToWrite);
            throw exception;
        }
        if (!mappingsToWrite.isEmpty())
            mappingRepository.bulkUpsert(mappingsToWrite);
        batch.clear();
    }

    private UpsertResult upsert(String notionDataSourceId, PlaneProjectDocument project, JsonNode workItem,
            boolean adHoc, SyncMappingDocument mapping, boolean pendingCreatedNow) {
        String planeWorkItemId = workItem.path("id").asString("");
        Map<String, Object> properties = notionProperties(project, workItem);
        if (adHoc) {
            properties.put("workspace_slug", typed("rich_text", richText(project.planeWorkspaceSlug())));
            properties.put("project_id", typed("rich_text", richText(project.planeProjectId())));
            properties.put("project_identifier", typed("rich_text", richText(project.planeProjectIdentifier())));
            properties.put("project_name", typed("rich_text", richText(project.planeProjectName())));
        }
        String planeSourceHash = planeSourceHash(properties);
        if (mapping != null) {
            if (mapping.notionPageId() == null || mapping.notionPageId().isBlank()) {
                if (!pendingCreatedNow) {
                    JsonNode matches = notionClient.findWorkItemBySourceId(notionDataSourceId, planeWorkItemId);
                    JsonNode foundPage = matches.path("results").isArray() && !matches.path("results").isEmpty()
                            ? matches.path("results").get(0)
                            : null;
                    if (foundPage != null) {
                        String recoveredTargetId = foundPage.path("id").asString("");
                        if (recoveredTargetId.isBlank()) {
                            throw new IntegrationException("NOTION_RESPONSE_INVALID", 502,
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
            boolean unchanged = planeSourceHash.equals(mapping.planeSourceHash());
            if (unchanged)
                return new UpsertResult(false, false, true, null);
            try {
                notionClient.updateWorkItem(mapping.notionPageId(), properties);
                return new UpsertResult(false, true, false,
                        new SyncMappingDocument(mapping.id(), planeWorkItemId, mapping.notionPageId(),
                                notionDataSourceId, planeSourceHash));
            } catch (IntegrationException exception) {
                if (exception.upstreamStatus() != 404)
                    throw exception;
                mappingRepository
                        .bulkUpsert(List.of(pendingMapping(planeWorkItemId, notionDataSourceId, mapping.id())));
            }
        }
        return createWithMapping(notionDataSourceId, planeWorkItemId, planeSourceHash, properties,
                mapping == null ? null : mapping.id());
    }

    private UpsertResult createWithMapping(String notionDataSourceId, String planeWorkItemId, String planeSourceHash,
            Map<String, Object> properties, ObjectId mappingId) {
        JsonNode page = notionClient.createWorkItem(notionDataSourceId, properties);
        String targetId = page.path("id").asString("");
        if (targetId.isBlank()) {
            throw new IntegrationException("NOTION_RESPONSE_INVALID", 502,
                    "Notion did not return the created work item page ID.");
        }
        return new UpsertResult(true, false, false,
                new SyncMappingDocument(mappingId, planeWorkItemId, targetId, notionDataSourceId, planeSourceHash));
    }

    private SyncMappingDocument pendingMapping(String planeWorkItemId, String notionDataSourceId) {
        return pendingMapping(planeWorkItemId, notionDataSourceId, null);
    }

    private SyncMappingDocument pendingMapping(String planeWorkItemId, String notionDataSourceId,
            ObjectId id) {
        return new SyncMappingDocument(id, planeWorkItemId, null, notionDataSourceId, "");
    }

    private String planeSourceHash(Map<String, Object> properties) {
        try {
            byte[] payload = HASH_MAPPER.writeValueAsBytes(sorted(properties));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), sorted(child)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(this::sorted).toList();
        return value;
    }

    private Map<String, Object> notionProperties(PlaneProjectDocument project, JsonNode workItem) {
        String workItemId = workItem.path("id").asString("");
        String state = workItem.path("state").path("name").asString("");
        String priority = workItem.path("priority").asString("none");
        String targetDate = workItem.path("target_date").asString("");
        String updatedAt = workItem.path("updated_at").asString("");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", typed("title", richText(workItem.path("name").asString("Untitled work item"))));
        properties.put("identifier", typed("rich_text", richText(workItem.path("identifier").asString(""))));
        properties.put("work_item_id", typed("rich_text", richText(workItemId)));
        properties.put("work_item_url", typed("url",
                planeService.workItemUrl(project.planeWorkspaceSlug(), project.planeProjectId(), workItemId)));
        properties.put("description",
                typed("rich_text", richText(plainText(workItem.path("description_html").asString("")))));
        properties.put("state", typed("select", state.isBlank() ? null : Map.of("name", state)));
        properties.put("priority", typed("select", "none".equals(priority) ? null : Map.of("name", priority)));
        properties.put("assignees", typed("rich_text", richText(String.join(", ", names(workItem.path("assignees"))))));
        properties.put("labels", typed("multi_select", names(workItem.path("labels")).stream()
                .map(name -> Map.of("name", name)).toList()));
        properties.put("due_date", typed("date", targetDate.isBlank() ? null : Map.of("start", targetDate)));
        properties.put("plane_updated_at", typed("date", updatedAt.isBlank() ? null : Map.of("start", updatedAt)));
        properties.put("plane_lifecycle", typed("select", Map.of("name", "ACTIVE")));
        return properties;
    }

    private void assertEligible(JsonNode workItem) {
        if (workItem.path("is_draft").asBoolean(false)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "DRAFT_WORK_ITEM",
                    "A draft work item cannot be synced.");
        }
        if (!workItem.path("archived_at").isMissingNode() && !workItem.path("archived_at").isNull()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "ARCHIVED_WORK_ITEM",
                    "An archived work item cannot be synced.");
        }
    }

    private Map<String, Object> typed(String key, Object value) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put(key, value);
        return property;
    }

    private List<Map<String, Object>> richText(String value) {
        if (value == null || value.isBlank())
            return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int start = 0; start < value.length(); start += 2000) {
            String chunk = value.substring(start, Math.min(value.length(), start + 2000));
            result.add(Map.of("type", "text", "text", Map.of("content", chunk)));
        }
        return result;
    }

    private List<String> names(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) {
            values.forEach(value -> {
                String name = value.path("display_name").asString(value.path("name").asString(""));
                if (name.isBlank()) {
                    String first = value.path("first_name").asString("");
                    String last = value.path("last_name").asString("");
                    name = (first + " " + last).trim();
                }
                if (!name.isBlank())
                    result.add(name);
            });
        }
        return result.stream().distinct().toList();
    }

    private List<String> names(List<JsonNode> values) {
        return values.stream().map(value -> value.path("name").asString(""))
                .filter(name -> !name.isBlank()).distinct().toList();
    }

    private List<String> namesFromExpanded(JsonNode expandedState) {
        String name = expandedState.path("name").asString("");
        return name.isBlank() ? List.of() : List.of(name);
    }

    private String plainText(String html) {
        return html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])\\s*>", "\n")
                .replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    public record SyncResult(int eligibleCount, int createdCount, int updatedCount, int unchangedCount) {
    }

    private record UpsertResult(boolean created, boolean updated, boolean unchanged, SyncMappingDocument mapping) {
    }

    private static final class SyncAccumulator {
        private int eligibleCount;
        private int createdCount;
        private int updatedCount;
        private int unchangedCount;

        private SyncResult result() {
            return new SyncResult(eligibleCount, createdCount, updatedCount, unchangedCount);
        }
    }
}
