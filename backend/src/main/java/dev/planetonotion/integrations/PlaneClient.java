package dev.planetonotion.integrations;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ApiErrorCodes;
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
import static dev.planetonotion.integrations.PlaneApiConstants.Paths.*;
import static dev.planetonotion.integrations.PlaneApiConstants.QueryParameters.*;
import static dev.planetonotion.integrations.PlaneApiConstants.QueryValues.*;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.PROJECTS_SEGMENT;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.ISSUES_SEGMENT;

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
        get(USERS_ME, ConnectionUser.class);
    }

    public void forEachProjectPage(String workspaceSlug, Consumer<List<Project>> pageConsumer) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(INCLUDE_ARCHIVED, TRUE);
        query.put(FIELDS, PROJECT_FIELDS);
        forEachPage(WORKSPACES + "{workspace}" + PROJECTS, query, PROJECT_PAGE_TYPE,
                pageConsumer, workspaceSlug);
    }

    public Project getProject(String workspaceSlug, String projectId) {
        return get(WORKSPACES + segment(workspaceSlug) + PROJECTS + segment(projectId) + "/",
                Project.class);
    }

    public void forEachProjectWorkItemPage(String workspaceSlug, String projectId, Instant updatedAtFrom,
            Instant updatedAtTo, Consumer<List<WorkItem>> pageConsumer) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(ARCHIVED, FALSE);
        query.put(IS_DRAFT, FALSE);
        query.put(EXPAND, WORK_ITEM_EXPANSIONS);
        query.put(FIELDS, WORK_ITEM_FIELDS);
        query.put(PAGINATE, CURSOR_PAGINATION);
        query.put(ORDER_BY, UPDATED_AT);
        if (updatedAtFrom != null)
            query.put(UPDATED_AT_GTE, updatedAtFrom.toString());
        if (updatedAtTo != null)
            query.put(UPDATED_AT_LTE, updatedAtTo.toString());
        forEachPage(WORKSPACES + "{workspace}" + PROJECTS + "{project}" + WORK_ITEMS, query,
                WORK_ITEM_PAGE_TYPE,
                pageConsumer, workspaceSlug, projectId);
    }

    public WorkItem getWorkItemByIdentifier(String workspaceSlug, String identifier) {
        return get(WORKSPACES + segment(workspaceSlug) + WORK_ITEMS + segment(identifier)
                + "/?" + EXPAND + "=" + WORK_ITEM_EXPANSIONS, WorkItem.class);
    }

    public WorkItem getWorkItem(String workspaceSlug, String projectId, String workItemId) {
        return get(WORKSPACES + segment(workspaceSlug) + PROJECTS + segment(projectId) + WORK_ITEMS
                + segment(workItemId) + "/?" + EXPAND + "=" + WORK_ITEM_EXPANSIONS, WorkItem.class);
    }

    public String projectUrl(String workspaceSlug, String projectId) {
        return appBaseUrl() + "/" + segment(workspaceSlug) + "/" + PROJECTS_SEGMENT + "/" + segment(projectId);
    }

    public String workItemUrl(String workspaceSlug, String projectId, String workItemId) {
        return projectUrl(workspaceSlug, projectId) + "/" + ISSUES_SEGMENT + "/" + segment(workItemId);
    }

    public List<State> listWorkItemStates(String workspaceSlug, String projectId) {
        return getAllPages(WORKSPACES + "{workspace}" + PROJECTS + "{project}" + STATES, Map.of(), STATE_PAGE_TYPE,
                workspaceSlug, projectId);
    }

    public List<Label> listWorkItemLabels(String workspaceSlug, String projectId) {
        return getAllPages(WORKSPACES + "{workspace}" + PROJECTS + "{project}" + LABELS, Map.of(), LABEL_PAGE_TYPE,
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
        boolean cursorPagination = CURSOR_PAGINATION.equals(filters.get(PAGINATE));
        while (true) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUri(properties.planeApiBaseUrl())
                    .path(pathTemplate)
                    .queryParam(PER_PAGE, PLANE_API_PAGE_SIZE);
            if (!cursorPagination)
                builder.queryParam(COUNT, FALSE);
            if (cursorPagination) {
                if (cursor != null)
                    builder.queryParam(CURSOR, cursor);
            } else {
                builder.queryParam(OFFSET, offset);
            }
            filters.forEach(builder::queryParam);
            Page<T> page = get(builder.buildAndExpand(pathVariables).encode().toUri(), responseType);
            List<T> data = page.data();
            if (data == null) {
                throw new IntegrationException(ApiErrorCodes.Plane.RESPONSE_INVALID, 502,
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
                    throw new IntegrationException(ApiErrorCodes.Plane.RESPONSE_INVALID, 502,
                            ApiErrorMessages.Plane.INVALID_PAGINATION);
                }
                cursor = nextCursor;
            } else if (page.next() != null && !page.next().isBlank()) {
                int nextOffset = parseOffset(page.next(), offset);
                if (nextOffset <= offset) {
                    throw new IntegrationException(ApiErrorCodes.Plane.RESPONSE_INVALID, 502,
                            ApiErrorMessages.Plane.INVALID_PAGINATION);
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
                    if (parts.length == 2 && OFFSET.equals(parts[0]))
                        return Integer.parseInt(parts[1]);
                }
            }
            return currentOffset + PLANE_API_PAGE_SIZE;
        } catch (RuntimeException exception) {
            throw new IntegrationException(ApiErrorCodes.Plane.RESPONSE_INVALID, 502,
                    ApiErrorMessages.Plane.INVALID_PAGINATION);
        }
    }

    private <T> T get(String path, Class<T> responseType) {
        if (properties.planePersonalAccessToken() == null || properties.planePersonalAccessToken().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Plane.TOKEN_MISSING, 0,
                    ApiErrorMessages.Plane.TOKEN_MISSING);
        }
        return httpService.get(client, IntegrationProvider.PLANE,
                URI.create(properties.planeApiBaseUrl().toString().replaceAll("/$", "") + path), responseType);
    }

    private <T> T get(URI uri, ParameterizedTypeReference<T> responseType) {
        if (properties.planePersonalAccessToken() == null || properties.planePersonalAccessToken().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Plane.TOKEN_MISSING, 0,
                    ApiErrorMessages.Plane.TOKEN_MISSING);
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
