package dev.planetonotion.integrations;

import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.common.PlaneToNotionEnums.IntegrationProvider;
import dev.planetonotion.integrations.models.PlaneApiModels.ConnectionUser;
import dev.planetonotion.integrations.models.PlaneApiModels.Label;
import dev.planetonotion.integrations.models.PlaneApiModels.Page;
import dev.planetonotion.integrations.models.PlaneApiModels.Project;
import dev.planetonotion.integrations.models.PlaneApiModels.State;
import dev.planetonotion.integrations.models.PlaneApiModels.WorkItem;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Typed HTTP boundary for Plane project, work-item, state, and label APIs. */
@Component
public class PlaneClient {

    private static final int PLANE_API_PAGE_SIZE = 200;

    private static final ParameterizedTypeReference<Page<Project>> PROJECT_PAGE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Page<WorkItem>> WORK_ITEM_PAGE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Page<State>> STATE_PAGE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Page<Label>> LABEL_PAGE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;
    private final HttpService httpService;
    private final IntegrationProperties properties;

    @Autowired
    public PlaneClient(IntegrationProperties properties, HttpService httpService,
            IntegrationRestClientFactory clientFactory) {
        this(properties, clientFactory.builder(properties.planeApiBaseUrl().toString()), httpService);
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
        get("/api/v2/users/me/", ConnectionUser.class);
    }

    public void forEachProjectPage(String workspaceSlug, Consumer<List<Project>> pageConsumer) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("include_archived", "true");
        query.put("fields", "id,name,identifier,archived_at");
        forEachPage("/api/v2/workspaces/{workspace}/projects/", query, PROJECT_PAGE_TYPE,
                pageConsumer, workspaceSlug);
    }

    public Project getProject(String workspaceSlug, String projectId) {
        return get("/api/v2/workspaces/" + segment(workspaceSlug) + "/projects/" + segment(projectId) + "/",
                Project.class);
    }

    public void forEachProjectWorkItemPage(String workspaceSlug, String projectId, Instant updatedAtFrom,
            Instant updatedAtTo, Consumer<List<WorkItem>> pageConsumer) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("archived", "false");
        query.put("is_draft", "false");
        query.put("expand", "state,assignees,labels");
        query.put("fields", String.join(",", List.of("id", "identifier", "project_id", "state_id", "name",
                "description_html", "priority", "assignee_ids", "label_ids", "target_date", "archived_at",
                "updated_at", "is_draft")));
        query.put("paginate", "cursor");
        query.put("order_by", "updated_at");
        if (updatedAtFrom != null)
            query.put("updated_at__gte", updatedAtFrom.toString());
        if (updatedAtTo != null)
            query.put("updated_at__lte", updatedAtTo.toString());
        forEachPage("/api/v2/workspaces/{workspace}/projects/{project}/work-items/", query, WORK_ITEM_PAGE_TYPE,
                pageConsumer, workspaceSlug, projectId);
    }

    public WorkItem getWorkItemByIdentifier(String workspaceSlug, String identifier) {
        return get("/api/v2/workspaces/" + segment(workspaceSlug) + "/work-items/" + segment(identifier)
                + "/?expand=state,assignees,labels", WorkItem.class);
    }

    public WorkItem getWorkItem(String workspaceSlug, String projectId, String workItemId) {
        return get("/api/v2/workspaces/" + segment(workspaceSlug) + "/projects/" + segment(projectId)
                + "/work-items/" + segment(workItemId) + "/?expand=state,assignees,labels", WorkItem.class);
    }

    public String projectUrl(String workspaceSlug, String projectId) {
        return appBaseUrl() + "/" + segment(workspaceSlug) + "/projects/" + segment(projectId);
    }

    public String workItemUrl(String workspaceSlug, String projectId, String workItemId) {
        return projectUrl(workspaceSlug, projectId) + "/issues/" + segment(workItemId);
    }

    public List<State> listWorkItemStates(String workspaceSlug, String projectId) {
        return getAllPages("/api/v2/workspaces/{workspace}/projects/{project}/states/", Map.of(), STATE_PAGE_TYPE,
                workspaceSlug, projectId);
    }

    public List<Label> listWorkItemLabels(String workspaceSlug, String projectId) {
        return getAllPages("/api/v2/workspaces/{workspace}/projects/{project}/labels/", Map.of(), LABEL_PAGE_TYPE,
                workspaceSlug, projectId);
    }

    private <T> List<T> getAllPages(String pathTemplate, Map<String, String> filters,
            ParameterizedTypeReference<Page<T>> responseType, Object... pathVariables) {
        java.util.ArrayList<T> results = new java.util.ArrayList<>();
        forEachPage(pathTemplate, filters, responseType, results::addAll, pathVariables);
        return results;
    }

    private <T> void forEachPage(String pathTemplate, Map<String, String> filters,
            ParameterizedTypeReference<Page<T>> responseType, Consumer<List<T>> pageConsumer,
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
            filters.forEach(builder::queryParam);
            Page<T> page = get(builder.buildAndExpand(pathVariables).encode().toUri(), responseType);
            List<T> data = page.data();
            if (data == null) {
                throw new IntegrationException("PLANE_RESPONSE_INVALID", 502,
                        "Plane returned an invalid list response.");
            }
            pageConsumer.accept(data);
            if (data.isEmpty())
                break;

            if (cursorPagination) {
                if (!Boolean.TRUE.equals(page.hasMore()))
                    break;
                String nextCursor = page.nextCursor();
                if (nextCursor == null || nextCursor.isBlank() || nextCursor.equals(cursor)) {
                    throw new IntegrationException("PLANE_RESPONSE_INVALID", 502,
                            "Plane returned invalid pagination data.");
                }
                cursor = nextCursor;
            } else if (page.next() != null && !page.next().isBlank()) {
                int nextOffset = parseOffset(page.next(), offset);
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

    private int parseOffset(String next, int currentOffset) {
        try {
            String value = URI.create(next).getQuery();
            if (value != null) {
                for (String pair : value.split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2 && "offset".equals(parts[0]))
                        return Integer.parseInt(parts[1]);
                }
            }
            return currentOffset + PLANE_API_PAGE_SIZE;
        } catch (RuntimeException exception) {
            throw new IntegrationException("PLANE_RESPONSE_INVALID", 502,
                    "Plane returned invalid pagination data.");
        }
    }

    private <T> T get(String path, Class<T> responseType) {
        if (properties.planePersonalAccessToken() == null || properties.planePersonalAccessToken().isBlank()) {
            throw new IntegrationException("PLANE_TOKEN_MISSING", 0, "PLANE_PERSONAL_ACCESS_TOKEN is not configured.");
        }
        return httpService.get(client, IntegrationProvider.PLANE,
                URI.create(properties.planeApiBaseUrl().toString().replaceAll("/$", "") + path), responseType);
    }

    private <T> T get(URI uri, ParameterizedTypeReference<T> responseType) {
        if (properties.planePersonalAccessToken() == null || properties.planePersonalAccessToken().isBlank()) {
            throw new IntegrationException("PLANE_TOKEN_MISSING", 0, "PLANE_PERSONAL_ACCESS_TOKEN is not configured.");
        }
        return httpService.get(client, IntegrationProvider.PLANE, uri, responseType);
    }

    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String appBaseUrl() {
        return properties.planeAppBaseUrl().toString().replaceAll("/$", "");
    }
}
