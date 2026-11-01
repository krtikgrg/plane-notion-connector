package dev.planetonotion.integrations.notion;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.config.NotionProperties;
import dev.planetonotion.common.exceptions.IntegrationException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class NotionClient {

    private static final String NOTION_VERSION = "2026-03-11";

    private final RestClient client;
    private final NotionProperties properties;

    @Autowired
    public NotionClient(NotionProperties properties) {
        this(properties, RestClient.builder());
    }

    NotionClient(NotionProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.client = builder
                .baseUrl(properties.apiBaseUrl().toString())
                .defaultHeader("Authorization", "Bearer " + properties.connectionToken())
                .defaultHeader("Notion-Version", NOTION_VERSION)
                .build();
    }

    public String createWorkspacePage(String workspaceSlug) {
        if (properties.parentPageId() == null || properties.parentPageId().isBlank()) {
            throw new IntegrationException("NOTION_CONFIGURATION_MISSING", 0, "NOTION_PARENT_PAGE_ID is required to create workspace pages.");
        }
        JsonNode page = post("/v1/pages", Map.of(
                "parent", Map.of("type", "page_id", "page_id", properties.parentPageId()),
                "properties", Map.of("title", Map.of("title", richText("Plane Workspace — " + workspaceSlug)))));
        return requiredId(page, "Notion did not return the workspace page ID.");
    }

    public ProjectDestination createProjectDestination(String workspacePageId, String projectName,
            List<String> states, List<String> labels) {
        return createDestination(workspacePageId, projectName, "Plane Work Items",
                taskProperties(states, labels), "Notion did not return the project page ID.");
    }

    public void synchronizeProjectPage(String pageId, String projectName, String workspacePageId) {
        JsonNode page = get("/v1/pages/" + segment(pageId));
        JsonNode title = page.path("properties").path("title").path("title");
        String currentTitle = title.isArray() && !title.isEmpty()
                ? title.get(0).path("plain_text").asString("") : "";
        if (!projectName.equals(currentTitle)) {
            patch("/v1/pages/" + segment(pageId), Map.of("properties",
                    Map.of("title", Map.of("title", richText(projectName)))));
        }
        if (!workspacePageId.equals(page.path("parent").path("page_id").asString(""))) {
            post("/v1/pages/" + segment(pageId) + "/move",
                    Map.of("parent", Map.of("type", "page_id", "page_id", workspacePageId)));
        }
    }

    public void upsertPageTableSummary(String pageId, String heading, List<String> legacyHeadings,
            String summaryPrefix, String summary, List<String> columns, List<List<String>> rows) {
        List<JsonNode> pageBlocks = readChildren(pageId);
        JsonNode sectionHeading = pageBlocks.stream()
                .filter(block -> isHeading(block) && (heading.equals(blockText(block))
                        || legacyHeadings.contains(blockText(block))))
                .findFirst().orElse(null);

        if (sectionHeading == null) {
            JsonNode response = appendBlocks(pageId, List.of(headingBlock(heading)));
            sectionHeading = response.path("results").get(0);
            pageBlocks = readChildren(pageId);
        } else if (!heading.equals(blockText(sectionHeading))) {
            patch(blockPath(sectionHeading), Map.of("heading_2", Map.of("rich_text", richText(heading))));
        }

        String headingId = sectionHeading.path("id").asString("");
        List<JsonNode> section = sectionBlocks(pageBlocks, headingId);
        JsonNode callout = section.stream().filter(block -> "callout".equals(block.path("type").asString("")))
                .findFirst().orElse(null);
        JsonNode table = section.stream().filter(block -> "table".equals(block.path("type").asString("")))
                .findFirst().orElse(null);

        for (JsonNode block : section) {
            if ("paragraph".equals(block.path("type").asString("") )
                    && blockText(block).startsWith(summaryPrefix)) {
                trashBlock(block);
            }
        }

        if (callout == null) {
            JsonNode response = appendBlocks(pageId, List.of(calloutBlock(summary)), headingId);
            callout = response.path("results").get(0);
        } else {
            patch(blockPath(callout), Map.of("callout", Map.of("rich_text", richText(summary))));
        }

        boolean tableWidthChanged = table != null
                && table.path("table").path("table_width").asInt(-1) != columns.size();
        if (tableWidthChanged) trashBlock(table);
        if (table == null || tableWidthChanged) {
            JsonNode response = appendBlocks(pageId, List.of(tableBlock(columns)), callout.path("id").asString(""));
            JsonNode createdTable = response.path("results").get(0);
            tableRows(createdTable.path("id").asString(""), withHeader(columns, rows));
        } else {
            tableRows(table.path("id").asString(""), withHeader(columns, rows));
        }
    }

    private List<JsonNode> readChildren(String blockId) {
        List<JsonNode> blocks = new ArrayList<>();
        String cursor = null;
        do {
            String path = "/v1/blocks/" + segment(blockId) + "/children?page_size=100";
            if (cursor != null) path += "&start_cursor=" + segment(cursor);
            JsonNode response = get(path);
            response.path("results").forEach(blocks::add);
            cursor = response.path("next_cursor").asString("");
            if (cursor.isBlank() || !response.path("has_more").asBoolean(false)) cursor = null;
        } while (cursor != null);
        return blocks;
    }

    private List<JsonNode> sectionBlocks(List<JsonNode> blocks, String headingId) {
        int start = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (headingId.equals(blocks.get(i).path("id").asString(""))) { start = i; break; }
        }
        if (start < 0) return List.of();
        List<JsonNode> result = new ArrayList<>();
        for (int i = start + 1; i < blocks.size(); i++) {
            JsonNode block = blocks.get(i);
            if (isHeading(block)) break;
            result.add(block);
        }
        return result;
    }

    private boolean isHeading(JsonNode block) {
        return block.path("type").asString("").startsWith("heading_");
    }

    private String blockText(JsonNode block) {
        String type = block.path("type").asString("");
        JsonNode text = block.path(type).path("rich_text");
        StringBuilder result = new StringBuilder();
        if (text.isArray()) text.forEach(part -> result.append(part.path("plain_text").asString("")));
        return result.toString();
    }

    private String blockPath(JsonNode block) {
        return "/v1/blocks/" + segment(block.path("id").asString(""));
    }

    private Map<String, Object> headingBlock(String heading) {
        return Map.of("object", "block", "type", "heading_2", "heading_2", Map.of("rich_text", richText(heading)));
    }

    private Map<String, Object> calloutBlock(String summary) {
        return Map.of("object", "block", "type", "callout", "callout", Map.of(
                "rich_text", richText(summary), "icon", Map.of("type", "emoji", "emoji", "📊")));
    }

    private Map<String, Object> tableBlock(List<String> columns) {
        return Map.of("object", "block", "type", "table", "table", Map.of(
                "table_width", columns.size(), "has_column_header", true, "has_row_header", false,
                "children", List.of(tableRow(columns, true))));
    }

    private Map<String, Object> tableRow(List<String> cells, boolean header) {
        List<List<Map<String, Object>>> richCells = cells.stream()
                .map(cell -> header ? boldRichText(cell) : richText(cell))
                .toList();
        return Map.of("object", "block", "type", "table_row", "table_row", Map.of("cells", richCells));
    }

    private List<Map<String, Object>> boldRichText(String value) {
        return List.of(Map.of("type", "text", "text", Map.of("content", value),
                "annotations", Map.of("bold", true)));
    }

    private List<List<String>> withHeader(List<String> columns, List<List<String>> rows) {
        List<List<String>> result = new ArrayList<>();
        result.add(columns);
        result.addAll(rows);
        return result;
    }

    private void tableRows(String tableId, List<List<String>> rows) {
        List<JsonNode> existing = readChildren(tableId);
        int available = existing.size();
        int updateCount = Math.min(available, rows.size());
        for (int i = 0; i < updateCount; i++) {
            boolean headerRow = i == 0;
            patch(blockPath(existing.get(i)), Map.of("table_row", Map.of("cells",
                    rows.get(i).stream().map(cell -> headerRow ? boldRichText(cell) : richText(cell)).toList())));
        }
        if (rows.size() > available) {
            List<Map<String, Object>> additions = new ArrayList<>();
            for (int i = available; i < rows.size(); i++) additions.add(tableRow(rows.get(i), false));
            appendBlocks(tableId, additions);
        }
        for (int i = rows.size(); i < existing.size(); i++) trashBlock(existing.get(i));
    }

    private void trashBlock(JsonNode block) {
        patch(blockPath(block), Map.of("in_trash", true));
    }

    private JsonNode appendBlocks(String parentId, List<Map<String, Object>> blocks) {
        return appendBlocks(parentId, blocks, null);
    }

    private JsonNode appendBlocks(String parentId, List<Map<String, Object>> blocks, String afterBlockId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("children", blocks);
        if (afterBlockId != null) body.put("position", Map.of("type", "after_block", "after_block", Map.of("id", afterBlockId)));
        return patchForResponse("/v1/blocks/" + segment(parentId) + "/children", body);
    }

    private ProjectDestination createDestination(String parentPageId, String pageTitle, String databaseTitle,
            Map<String, Object> schema, String pageIdError) {
        JsonNode page = post("/v1/pages", Map.of(
                "parent", Map.of("type", "page_id", "page_id", parentPageId),
                "properties", Map.of("title", Map.of("title", richText(pageTitle)))));
        String pageId = requiredId(page, pageIdError);
        try {
            JsonNode database = post("/v1/databases", Map.of(
                    "parent", Map.of("type", "page_id", "page_id", pageId),
                    "title", richText(databaseTitle),
                    "is_inline", true,
                    "initial_data_source", Map.of("properties", schema)));
            String databaseId = requiredId(database, "Notion did not return the database ID.");
            JsonNode sources = database.path("data_sources");
            if (!sources.isArray() || sources.isEmpty()) {
                throw new IntegrationException("NOTION_DATA_SOURCE_MISSING", 502, "Notion created the database without returning a data source.");
            }
            String dataSourceId = requiredId(sources.get(0), "Notion did not return a data source ID.");
            return new ProjectDestination(pageId, databaseId, dataSourceId);
        } catch (RuntimeException exception) {
            archivePageAfterFailure(pageId, exception);
            throw exception;
        }
    }

    public JsonNode createWorkItem(String dataSourceId, Map<String, Object> properties) {
        return post("/v1/pages", Map.of(
                "parent", Map.of("type", "data_source_id", "data_source_id", dataSourceId),
                "properties", properties));
    }

    public void updateWorkItem(String pageId, Map<String, Object> properties) {
        patch("/v1/pages/" + segment(pageId), Map.of("properties", properties));
    }

    public void ensureWorkItemOptions(String dataSourceId, List<String> states, List<String> labels) {
        JsonNode dataSource = get("/v1/data_sources/" + segment(dataSourceId));
        JsonNode schema = dataSource.path("properties");
        List<String> stateOptions = mergedOptions(schema.path("state").path("select").path("options"), states);
        List<String> labelOptions = mergedOptions(schema.path("labels").path("multi_select").path("options"), labels);
        boolean stateChanged = !sameOptions(schema.path("state").path("select").path("options"), stateOptions);
        boolean labelsChanged = !sameOptions(schema.path("labels").path("multi_select").path("options"), labelOptions);
        if (!stateChanged && !labelsChanged) return;

        Map<String, Object> updates = new java.util.LinkedHashMap<>();
        if (stateChanged) updates.put("state", Map.of("select", Map.of("options", optionObjects(stateOptions))));
        if (labelsChanged) updates.put("labels", Map.of("multi_select", Map.of("options", optionObjects(labelOptions))));
        patch("/v1/data_sources/" + segment(dataSourceId), Map.of("properties", updates));
    }

    public void archivePage(String pageId) {
        requireToken();
        try {
            client.delete().uri("/v1/blocks/{block_id}", pageId).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new IntegrationException("NOTION_ARCHIVE_FAILED", exception.getStatusCode().value(),
                    "Notion could not archive the temporary project page.");
        } catch (RestClientException exception) {
            throw new IntegrationException("NOTION_ARCHIVE_FAILED", 0,
                    "Notion could not archive the temporary project page.");
        }
    }

    private void archivePageAfterFailure(String pageId, RuntimeException provisioningFailure) {
        try {
            archivePage(pageId);
        } catch (RuntimeException cleanupFailure) {
            IntegrationException failure = new IntegrationException("NOTION_SETUP_CLEANUP_FAILED", 502,
                    "Notion setup failed, and the temporary project page could not be archived.");
            failure.addSuppressed(provisioningFailure);
            failure.addSuppressed(cleanupFailure);
            throw failure;
        }
    }

    private Map<String, Object> taskProperties(List<String> states, List<String> labels) {
        Map<String, Object> lifecycle = Map.of("select", Map.of("options", optionObjects(List.of(
                "ACTIVE", "ARCHIVED_IN_PLANE", "MISSING_FROM_PROJECT"))));
        return Map.ofEntries(
                Map.entry("name", Map.of("title", Map.of())),
                Map.entry("identifier", Map.of("rich_text", Map.of())),
                Map.entry("work_item_id", Map.of("rich_text", Map.of())),
                Map.entry("work_item_url", Map.of("url", Map.of())),
                Map.entry("description", Map.of("rich_text", Map.of())),
                Map.entry("state", Map.of("select", Map.of("options", optionObjects(states)))),
                Map.entry("priority", Map.of("select", Map.of("options", optionObjects(List.of("urgent", "high", "medium", "low"))))),
                Map.entry("assignees", Map.of("rich_text", Map.of())),
                Map.entry("labels", Map.of("multi_select", Map.of("options", optionObjects(labels)))),
                Map.entry("due_date", Map.of("date", Map.of())),
                Map.entry("plane_updated_at", Map.of("date", Map.of())),
                Map.entry("plane_lifecycle", lifecycle));
    }

    private List<Map<String, String>> optionObjects(List<String> names) {
        return names.stream().filter(name -> name != null && !name.isBlank()).distinct()
                .map(name -> Map.of("name", name)).toList();
    }

    private List<String> mergedOptions(JsonNode current, List<String> incoming) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (current.isArray()) {
            current.forEach(option -> {
                String name = option.path("name").asString("");
                if (!name.isBlank()) result.add(name);
            });
        }
        incoming.stream().filter(name -> name != null && !name.isBlank()).forEach(result::add);
        return List.copyOf(result);
    }

    private boolean sameOptions(JsonNode existing, List<String> values) {
        List<String> current = new ArrayList<>();
        if (existing.isArray()) existing.forEach(option -> current.add(option.path("name").asString("")));
        return current.equals(values);
    }

    private List<Map<String, Object>> richText(String value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int start = 0; start < value.length(); start += 2000) {
            result.add(Map.of("type", "text", "text", Map.of("content", value.substring(start, Math.min(value.length(), start + 2000)))));
        }
        return result;
    }

    private JsonNode get(String path) {
        requireToken();
        try {
            JsonNode body = client.get().uri(path).retrieve().body(JsonNode.class);
            if (body == null) throw new IntegrationException("NOTION_RESPONSE_INVALID", 502, "Notion returned an empty response.");
            return body;
        } catch (RestClientResponseException exception) {
            throw apiFailure(exception);
        } catch (RestClientException exception) {
            throw new IntegrationException("NOTION_UNAVAILABLE", 0, "Could not connect to the configured Notion API.");
        }
    }

    private JsonNode post(String path, Object body) {
        requireToken();
        try {
            JsonNode response = client.post().uri(path).body(body).retrieve().body(JsonNode.class);
            if (response == null) throw new IntegrationException("NOTION_RESPONSE_INVALID", 502, "Notion returned an empty response.");
            return response;
        } catch (RestClientResponseException exception) {
            throw apiFailure(exception);
        } catch (RestClientException exception) {
            throw new IntegrationException("NOTION_UNAVAILABLE", 0, "Could not connect to the configured Notion API.");
        }
    }

    private void patch(String path, Object body) {
        requireToken();
        try {
            client.patch().uri(path).body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw apiFailure(exception);
        } catch (RestClientException exception) {
            throw new IntegrationException("NOTION_UNAVAILABLE", 0, "Could not connect to the configured Notion API.");
        }
    }

    private JsonNode patchForResponse(String path, Object body) {
        requireToken();
        try {
            JsonNode response = client.patch().uri(path).body(body).retrieve().body(JsonNode.class);
            if (response == null) throw new IntegrationException("NOTION_RESPONSE_INVALID", 502, "Notion returned an empty response.");
            return response;
        } catch (RestClientResponseException exception) {
            throw apiFailure(exception);
        } catch (RestClientException exception) {
            throw new IntegrationException("NOTION_UNAVAILABLE", 0, "Could not connect to the configured Notion API.");
        }
    }

    private IntegrationException apiFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return new IntegrationException("NOTION_API_ERROR", status, "Notion API request failed (HTTP " + status + ").");
    }

    private void requireToken() {
        if (properties.connectionToken() == null || properties.connectionToken().isBlank()) {
            throw new IntegrationException("NOTION_TOKEN_MISSING", 0, "NOTION_CONNECTION_API_TOKEN is not configured.");
        }
    }

    private String requiredId(JsonNode object, String message) {
        String id = object.path("id").asString("");
        if (id.isBlank()) throw new IntegrationException("NOTION_RESPONSE_INVALID", 502, message);
        return id;
    }

    private String segment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record ProjectDestination(String pageId, String databaseId, String dataSourceId) {
    }
}
