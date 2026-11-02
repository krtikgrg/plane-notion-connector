package dev.planetonotion.integrations;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.common.exceptions.IntegrationException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PlaneClient {

    private static final int PLANE_API_PAGE_SIZE = 200;

    private final RestClient client;
    private final HttpService httpService;
    private final IntegrationProperties properties;

    @Autowired
    public PlaneClient(IntegrationProperties properties, HttpService httpService) {
        this(properties, RestClient.builder(), httpService);
    }

    PlaneClient(IntegrationProperties properties, RestClient.Builder builder) {
        this(properties, builder, new HttpService());
    }

    private PlaneClient(IntegrationProperties properties, RestClient.Builder builder, HttpService httpService) {
        this.properties = properties;
        this.httpService = httpService;
        this.client = builder
                .baseUrl(properties.planeApiBaseUrl().toString())
                .defaultHeader("X-Api-Key", properties.planePersonalAccessToken())
                .build();
    }

    public void testConnection() {
        get("/api/v2/users/me/");
    }

    public void forEachProjectPage(String workspaceSlug, Consumer<List<JsonNode>> pageConsumer) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("include_archived", "true");
        query.put("fields", "id,name,identifier,archived_at");
        forEachPage("/api/v2/workspaces/{workspace}/projects/", query, page -> {
            List<JsonNode> projects = new ArrayList<>();
            page.path("data").forEach(projects::add);
            pageConsumer.accept(projects);
        }, workspaceSlug);
    }

    public JsonNode getProject(String workspaceSlug, String projectId) {
        return get("/api/v2/workspaces/" + segment(workspaceSlug) + "/projects/" + segment(projectId) + "/");
    }

    public void forEachProjectWorkItemPage(String workspaceSlug, String projectId, Instant updatedAtFrom,
            Instant updatedAtTo, Consumer<List<JsonNode>> pageConsumer) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("archived", "false");
        query.put("is_draft", "false");
        query.put("expand", "state,assignees,labels");
        query.put("fields",
                "id,identifier,project_id,state_id,name,description_html,priority,assignee_ids,label_ids,target_date,archived_at,updated_at,is_draft");
        query.put("paginate", "cursor");
        query.put("order_by", "updated_at");
        if (updatedAtFrom != null)
            query.put("updated_at__gte", updatedAtFrom.toString());
        if (updatedAtTo != null)
            query.put("updated_at__lte", updatedAtTo.toString());
        forEachPage("/api/v2/workspaces/{workspace}/projects/{project}/work-items/", query,
                page -> {
                    List<JsonNode> items = new ArrayList<>();
                    page.path("data").forEach(items::add);
                    pageConsumer.accept(items);
                }, workspaceSlug, projectId);
    }

    public JsonNode getWorkItemByIdentifier(String workspaceSlug, String identifier) {
        return get("/api/v2/workspaces/" + segment(workspaceSlug) + "/work-items/" + segment(identifier)
                + "/?expand=state,assignees,labels");
    }

    public JsonNode getWorkItem(String workspaceSlug, String projectId, String workItemId) {
        return get("/api/v2/workspaces/" + segment(workspaceSlug) + "/projects/" + segment(projectId)
                + "/work-items/" + segment(workItemId) + "/?expand=state,assignees,labels");
    }

    public String projectUrl(String workspaceSlug, String projectId) {
        return appBaseUrl() + "/" + segment(workspaceSlug) + "/projects/" + segment(projectId);
    }

    public String workItemUrl(String workspaceSlug, String projectId, String workItemId) {
        return projectUrl(workspaceSlug, projectId) + "/issues/" + segment(workItemId);
    }

    public List<JsonNode> listWorkItemStates(String workspaceSlug, String projectId) {
        return getAllPages("/api/v2/workspaces/{workspace}/projects/{project}/states/", Map.of(), workspaceSlug,
                projectId);
    }

    public List<JsonNode> listWorkItemLabels(String workspaceSlug, String projectId) {
        return getAllPages("/api/v2/workspaces/{workspace}/projects/{project}/labels/", Map.of(), workspaceSlug,
                projectId);
    }

    private List<JsonNode> getAllPages(String pathTemplate, Map<String, String> filters, Object... pathVariables) {
        List<JsonNode> results = new ArrayList<>();
        forEachPage(pathTemplate, filters, page -> page.path("data").forEach(results::add), pathVariables);
        return results;
    }

    private void forEachPage(String pathTemplate, Map<String, String> filters, Consumer<JsonNode> pageConsumer,
            Object... pathVariables) {
        int offset = 0;
        String cursor = null;
        boolean cursorPagination = "cursor".equals(filters.get("paginate"));
        while (true) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUri(properties.planeApiBaseUrl())
                    .path(pathTemplate)
                    .queryParam("per_page", PLANE_API_PAGE_SIZE);
            if (!cursorPagination)
                builder.queryParam("count", "false");
            if (cursorPagination) {
                if (cursor != null)
                    builder.queryParam("cursor", cursor);
            } else {
                builder.queryParam("offset", offset);
            }
            filters.forEach((key, value) -> builder.queryParam(key, value));
            JsonNode page = get(builder.buildAndExpand(pathVariables).encode().toUri());
            JsonNode data = page.path("data");
            if (!data.isArray()) {
                throw new IntegrationException("PLANE_RESPONSE_INVALID", 502,
                        "Plane returned an invalid list response.");
            }
            pageConsumer.accept(page);
            if (data.isEmpty())
                break;

            if (cursorPagination) {
                if (!page.path("has_more").asBoolean(false))
                    break;
                String nextCursor = page.path("next_cursor").asString("");
                if (nextCursor.isBlank() || nextCursor.equals(cursor)) {
                    throw new IntegrationException("PLANE_RESPONSE_INVALID", 502,
                            "Plane returned invalid pagination data.");
                }
                cursor = nextCursor;
            } else {
                JsonNode next = page.path("next");
                if (next.isNumber()) {
                    int nextOffset = next.asInt();
                    if (nextOffset <= offset) {
                        throw new IntegrationException("PLANE_RESPONSE_INVALID", 502,
                                "Plane returned invalid pagination data.");
                    }
                    offset = nextOffset;
                } else if (data.size() == PLANE_API_PAGE_SIZE) {
                    offset += PLANE_API_PAGE_SIZE;
                } else {
                    break;
                }
            }
        }
    }

    private JsonNode get(String path) {
        return get(URI.create(properties.planeApiBaseUrl().toString().replaceAll("/$", "") + path));
    }

    private JsonNode get(URI uri) {
        if (properties.planePersonalAccessToken() == null || properties.planePersonalAccessToken().isBlank()) {
            throw new IntegrationException("PLANE_TOKEN_MISSING", 0, "PLANE_PERSONAL_ACCESS_TOKEN is not configured.");
        }
        return httpService.get(client, "PLANE", uri);
    }

    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String appBaseUrl() {
        return properties.planeAppBaseUrl().toString().replaceAll("/$", "");
    }
}
