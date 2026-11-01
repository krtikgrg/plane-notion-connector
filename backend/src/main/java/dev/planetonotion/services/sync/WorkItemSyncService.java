package dev.planetonotion.services.sync;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.repositories.mappings.SyncMappingRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkItemSyncService {

    private static final JsonMapper HASH_MAPPER = JsonMapper.builder().build();
    private static final int MONGO_BATCH_SIZE = 1000;

    private final PlaneService planeService;
    private final NotionClient notionClient;
    private final SyncMappingRepository mappingRepository;

    public WorkItemSyncService(PlaneService planeService, NotionClient notionClient, SyncMappingRepository mappingRepository) {
        this.planeService = planeService;
        this.notionClient = notionClient;
        this.mappingRepository = mappingRepository;
    }

    public SyncResult syncProject(PlaneProjectDocument project, SyncConfigDocument config, Instant updatedAtFrom,
            Instant updatedAtTo, Consumer<SyncResult> progressConsumer) {
        String dataSourceId = config.notionDataSourceId();
        List<String> states = names(planeService.listWorkItemStates(project.workspaceSlug(), project.projectId()));
        List<String> labels = names(planeService.listWorkItemLabels(project.workspaceSlug(), project.projectId()));
        notionClient.ensureWorkItemOptions(dataSourceId, states, labels);

        SyncAccumulator accumulator = new SyncAccumulator();
        List<JsonNode> batch = new ArrayList<>(MONGO_BATCH_SIZE);
        planeService.forEachProjectWorkItemPage(project.workspaceSlug(), project.projectId(), updatedAtFrom,
                updatedAtTo, page -> {
                    for (JsonNode workItem : page) {
                        batch.add(workItem);
                        if (batch.size() == MONGO_BATCH_SIZE) processBatch(dataSourceId, project, batch, accumulator, progressConsumer);
                    }
                });
        if (!batch.isEmpty()) processBatch(dataSourceId, project, batch, accumulator, progressConsumer);
        return accumulator.result();
    }

    public SyncResult syncOne(SyncConfigDocument config, PlaneProjectDocument project, String workItemId,
            Consumer<SyncResult> progressConsumer) {
        return syncOne(config.notionDataSourceId(), project, workItemId, false, progressConsumer);
    }

    public SyncResult syncOneAdHoc(PlaneProjectDocument project, String workItemId, String dataSourceId,
            Consumer<SyncResult> progressConsumer) {
        return syncOne(dataSourceId, project, workItemId, true, progressConsumer);
    }

    private SyncResult syncOne(String dataSourceId, PlaneProjectDocument project, String workItemId, boolean adHoc,
            Consumer<SyncResult> progressConsumer) {
        JsonNode workItem = planeService.getWorkItem(project.workspaceSlug(), project.projectId(), workItemId);
        assertEligible(workItem);
        progressConsumer.accept(new SyncResult(1, 0, 0, 0));
        List<String> stateNames = namesFromExpanded(workItem.path("state"));
        List<String> labelNames = names(workItem.path("labels"));
        notionClient.ensureWorkItemOptions(dataSourceId, stateNames, labelNames);
        SyncMappingDocument mapping = mappingRepository.findByTargetDataSourceIdAndSourceId(dataSourceId,
                workItem.path("id").asString("")).orElse(null);
        UpsertResult result = upsert(dataSourceId, project, workItem, adHoc, mapping);
        if (result.mapping() != null) mappingRepository.bulkUpsert(List.of(result.mapping()));
        SyncResult syncResult = new SyncResult(1, result.created() ? 1 : 0, result.updated() ? 1 : 0,
                result.unchanged() ? 1 : 0);
        progressConsumer.accept(syncResult);
        return syncResult;
    }

    private void processBatch(String dataSourceId, PlaneProjectDocument project, List<JsonNode> batch,
            SyncAccumulator accumulator, Consumer<SyncResult> progressConsumer) {
        List<String> sourceIds = batch.stream().map(item -> item.path("id").asString(""))
                .filter(sourceId -> !sourceId.isBlank()).toList();
        Map<String, SyncMappingDocument> mappingsBySourceId = new LinkedHashMap<>();
        mappingRepository.findByTargetDataSourceIdAndSourceIdIn(dataSourceId, sourceIds)
                .forEach(mapping -> mappingsBySourceId.put(mapping.sourceId(), mapping));
        List<SyncMappingDocument> mappingsToWrite = new ArrayList<>();
        try {
            for (JsonNode workItem : batch) {
                accumulator.eligibleCount++;
                progressConsumer.accept(accumulator.result());
                String sourceId = workItem.path("id").asString("");
                if (sourceId.isBlank()) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "PLANE_RESPONSE_INVALID", "Plane returned a work item without an ID.");
                }
                UpsertResult result = upsert(dataSourceId, project, workItem, false, mappingsBySourceId.get(sourceId));
                if (result.mapping() != null) mappingsToWrite.add(result.mapping());
                if (result.created()) accumulator.createdCount++;
                else if (result.updated()) accumulator.updatedCount++;
                else if (result.unchanged()) accumulator.unchangedCount++;
                progressConsumer.accept(accumulator.result());
            }
        } catch (RuntimeException exception) {
            if (!mappingsToWrite.isEmpty()) mappingRepository.bulkUpsert(mappingsToWrite);
            throw exception;
        }
        if (!mappingsToWrite.isEmpty()) mappingRepository.bulkUpsert(mappingsToWrite);
        batch.clear();
    }

    private UpsertResult upsert(String dataSourceId, PlaneProjectDocument project, JsonNode workItem,
            boolean adHoc, SyncMappingDocument mapping) {
        String sourceId = workItem.path("id").asString("");
        Map<String, Object> properties = notionProperties(project, workItem);
        if (adHoc) {
            properties.put("workspace_slug", typed("rich_text", richText(project.workspaceSlug())));
            properties.put("project_id", typed("rich_text", richText(project.projectId())));
            properties.put("project_identifier", typed("rich_text", richText(project.identifier())));
            properties.put("project_name", typed("rich_text", richText(project.name())));
        }
        String sourceHash = sourceHash(properties);
        if (mapping != null) {
            boolean unchanged = sourceHash.equals(mapping.sourceHash());
            if (unchanged) return new UpsertResult(false, false, true, null);
            try {
                notionClient.updateWorkItem(mapping.targetId(), properties);
                return new UpsertResult(false, true, false,
                        new SyncMappingDocument(mapping.id(), sourceId, mapping.targetId(), dataSourceId, sourceHash));
            } catch (IntegrationException exception) {
                if (exception.upstreamStatus() != 404) throw exception;
            }
        }

        JsonNode page = notionClient.createWorkItem(dataSourceId, properties);
        String targetId = page.path("id").asString("");
        if (targetId.isBlank()) {
            throw new IntegrationException("NOTION_RESPONSE_INVALID", 502, "Notion did not return the created work item page ID.");
        }
        return new UpsertResult(true, false, false,
                new SyncMappingDocument(mapping == null ? null : mapping.id(), sourceId, targetId, dataSourceId, sourceHash));
    }

    private String sourceHash(Map<String, Object> properties) {
        try {
            byte[] payload = HASH_MAPPER.writeValueAsBytes(sorted(properties));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return java.util.HexFormat.of().formatHex(digest);
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
        if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
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
        properties.put("work_item_url", typed("url", planeService.workItemUrl(project.workspaceSlug(), project.projectId(), workItemId)));
        properties.put("description", typed("rich_text", richText(plainText(workItem.path("description_html").asString("")))));
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
            throw new ServiceException(HttpStatus.BAD_REQUEST, "DRAFT_WORK_ITEM", "A draft work item cannot be synced.");
        }
        if (!workItem.path("archived_at").isMissingNode() && !workItem.path("archived_at").isNull()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "ARCHIVED_WORK_ITEM", "An archived work item cannot be synced.");
        }
    }

    private Map<String, Object> typed(String key, Object value) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put(key, value);
        return property;
    }

    private List<Map<String, Object>> richText(String value) {
        if (value == null || value.isBlank()) return List.of();
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
                if (!name.isBlank()) result.add(name);
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
