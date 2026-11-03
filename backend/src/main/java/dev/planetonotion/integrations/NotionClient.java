package dev.planetonotion.integrations;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.common.PlaneToNotionEnums.IntegrationProvider;
import dev.planetonotion.common.PlaneToNotionEnums.WorkItemLifecycle;
import dev.planetonotion.integrations.models.NotionRequestModels.CreateDatabaseRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.CreateTitlePageRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.CreateWorkItemPageRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.Filter;
import dev.planetonotion.integrations.models.NotionRequestModels.InitialDataSource;
import dev.planetonotion.integrations.models.NotionRequestModels.MovePageRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.MultiSelectSchema;
import dev.planetonotion.integrations.models.NotionRequestModels.NamedOption;
import dev.planetonotion.integrations.models.NotionRequestModels.ParentReference;
import dev.planetonotion.integrations.models.NotionRequestModels.PropertyDefinition;
import dev.planetonotion.integrations.models.NotionRequestModels.PropertyUpdate;
import dev.planetonotion.integrations.models.NotionRequestModels.QueryDataSourceRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.RichText;
import dev.planetonotion.integrations.models.NotionRequestModels.RichTextFilter;
import dev.planetonotion.integrations.models.NotionRequestModels.SelectSchema;
import dev.planetonotion.integrations.models.NotionRequestModels.TitlePageProperties;
import dev.planetonotion.integrations.models.NotionRequestModels.TitleProperty;
import dev.planetonotion.integrations.models.NotionRequestModels.UpdateDataSourceRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.UpdateTitlePageRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.UpdateWorkItemPageRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.WorkItemProperties;
import dev.planetonotion.integrations.models.NotionRequestModels.WorkItemSchema;
import dev.planetonotion.integrations.models.NotionRequestModels.WorkItemSchemaUpdates;
import dev.planetonotion.integrations.models.NotionResponseModels.Database;
import dev.planetonotion.integrations.models.NotionResponseModels.DataSource;
import dev.planetonotion.integrations.models.NotionResponseModels.Page;
import dev.planetonotion.integrations.models.NotionResponseModels.PageQuery;
import dev.planetonotion.integrations.models.NotionResponseModels.SelectOptions;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import static dev.planetonotion.integrations.NotionApiConstants.Headers.*;
import static dev.planetonotion.integrations.NotionApiConstants.Paths.*;
import static dev.planetonotion.integrations.NotionApiConstants.PropertyNames.WORK_ITEM_ID;
import static dev.planetonotion.integrations.NotionApiConstants.VERSION;
import static dev.planetonotion.integrations.PlaneApiConstants.Priority.NOTION_OPTIONS;

/**
 * Creates and updates Notion pages, databases, data sources, and work-item rows.
 * Summary block content is handled by {@link NotionBlockClient}.
 */
@Component
public class NotionClient {

    private static final String WORK_ITEM_DATABASE_TITLE = "Plane Work Items";

    private final RestClient client;
    private final HttpService httpService;
    private final IntegrationProperties properties;
    private final NotionBlockClient notionBlockClient;

    /** Creates the Notion client with provider credentials and shared HTTP settings. */
    @Autowired
    public NotionClient(IntegrationProperties properties, HttpService httpService,
            IntegrationRestClientFactory clientFactory, NotionBlockClient notionBlockClient) {
        this(properties, clientFactory.builder(properties.notionApiBaseUrl().toString()), httpService,
                notionBlockClient);
    }

    /** Creates a client with a supplied builder for integration tests. */
    NotionClient(IntegrationProperties properties, RestClient.Builder builder) {
        this(properties, builder, new HttpService(), new NotionBlockClient(properties, builder));
    }

    /** Configures the API base URL, authorization header, and API version. */
    private NotionClient(IntegrationProperties properties, RestClient.Builder builder, HttpService httpService,
            NotionBlockClient notionBlockClient) {
        this.properties = properties;
        this.httpService = httpService;
        this.notionBlockClient = notionBlockClient;
        this.client = builder
                .baseUrl(properties.notionApiBaseUrl().toString())
                .defaultHeader(AUTHORIZATION, BEARER_PREFIX + properties.notionConnectionToken())
                .defaultHeader(NOTION_VERSION, VERSION)
                .build();
    }

    /**
     * Creates a workspace page beneath the configured Notion parent page.
     *
     * @param workspaceSlug Plane workspace slug used in the page title
     * @return ID of the newly created Notion page
     */
    public String createWorkspacePage(String workspaceSlug) {
        if (properties.notionParentPageId() == null || properties.notionParentPageId().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.CONFIGURATION_MISSING, 0,
                    "NOTION_PARENT_PAGE_ID is required to create workspace pages.");
        }
        Page page = post(PAGES, new CreateTitlePageRequest(ParentReference.page(properties.notionParentPageId()),
                new TitlePageProperties(TitleProperty.of(richText("Plane Workspace — " + workspaceSlug)))),
                Page.class);
        return requiredId(page, "Notion did not return the workspace page ID.");
    }

    /**
     * Creates a project page and its work-item database and data source.
     *
     * @param workspacePageId Notion page that will contain the project page
     * @param projectName Plane project name used as the page title
     * @param states current Plane state names used as select options
     * @param labels current Plane label names used as multi-select options
     * @return IDs for the created project page, database, and data source
     */
    public ProjectDestination createProjectDestination(String workspacePageId, String projectName,
            List<String> states, List<String> labels) {
        return createDestination(workspacePageId, projectName, WORK_ITEM_DATABASE_TITLE,
                taskProperties(states, labels, false), "Notion did not return the project page ID.");
    }

    /**
     * Creates the shared page, database, and data source for unconfigured work items.
     *
     * @return IDs for the shared page, database, and data source
     */
    public ProjectDestination createAdHocDestination() {
        if (properties.notionParentPageId() == null || properties.notionParentPageId().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.CONFIGURATION_MISSING, 0,
                    "NOTION_PARENT_PAGE_ID is required to create the shared manual-sync destination.");
        }
        return createDestination(properties.notionParentPageId(), "Plane Manual Syncs", WORK_ITEM_DATABASE_TITLE,
                taskProperties(List.of(), List.of(), true), "Notion did not return the shared manual-sync page ID.");
    }

    /**
     * Updates a project page's title and moves it beneath its current workspace page.
     *
     * @param pageId existing Notion project-page ID
     * @param projectName current Plane project name
     * @param workspacePageId current Notion workspace-page ID
     */
    public void synchronizeProjectPage(String pageId, String projectName, String workspacePageId) {
        Page page = get(PAGES + "/" + segment(pageId), Page.class);
        var title = page.properties() == null ? null : page.properties().title();
        String currentTitle = title == null || title.title() == null || title.title().isEmpty()
                ? ""
                : title.title().get(0).plainText();
        if (!projectName.equals(currentTitle)) {
            patch(PAGES + "/" + segment(pageId), new UpdateTitlePageRequest(
                    new TitlePageProperties(TitleProperty.of(richText(projectName)))));
        }
        if (page.parent() == null || !workspacePageId.equals(page.parent().pageId())) {
            post(PAGES + "/" + segment(pageId) + MOVE,
                    new MovePageRequest(ParentReference.page(workspacePageId)),
                    Page.class);
        }
    }

    /**
     * Creates a titled destination page, its inline database, and its initial
     * data-source schema. If provisioning fails after page creation, archives
     * that temporary page before rethrowing the failure.
     */
    private ProjectDestination createDestination(String parentPageId, String pageTitle, String databaseTitle,
            WorkItemSchema schema, String pageIdError) {
        Page page = post(PAGES, new CreateTitlePageRequest(ParentReference.page(parentPageId),
                new TitlePageProperties(TitleProperty.of(richText(pageTitle)))), Page.class);
        String pageId = requiredId(page, pageIdError);
        try {
            Database database = post(DATABASES, new CreateDatabaseRequest(ParentReference.page(pageId),
                    richText(databaseTitle), true, new InitialDataSource(schema)), Database.class);
            String databaseId = requiredId(database, "Notion did not return the database ID.");
            if (database.dataSources() == null || database.dataSources().isEmpty()) {
                throw new IntegrationException(ApiErrorCodes.Notion.DATA_SOURCE_MISSING, 502,
                        "Notion created the database without returning a data source.");
            }
            String dataSourceId = requiredId(database.dataSources().get(0), "Notion did not return a data source ID.");
            return new ProjectDestination(pageId, databaseId, dataSourceId);
        } catch (RuntimeException exception) {
            archivePageAfterFailure(pageId, exception);
            throw exception;
        }
    }

    /**
     * Creates one work-item page as a row in the specified data source.
     *
     * @param dataSourceId target Notion data-source ID
     * @param properties mapped Plane values for the work-item row
     * @return created Notion page
     */
    public Page createWorkItem(String dataSourceId, WorkItemProperties properties) {
        return post(PAGES, new CreateWorkItemPageRequest(ParentReference.dataSource(dataSourceId), properties),
                Page.class);
    }

    /**
     * Finds a row whose visible Plane work-item ID matches the source ID.
     *
     * @param dataSourceId Notion data source to search
     * @param sourceId Plane work-item ID to match
     * @return query response containing matching page rows
     */
    public PageQuery findWorkItemBySourceId(String dataSourceId, String sourceId) {
        return post(DATA_SOURCES + segment(dataSourceId) + QUERY,
                new QueryDataSourceRequest(1,
                        new Filter(WORK_ITEM_ID, new RichTextFilter(sourceId))),
                PageQuery.class);
    }

    /**
     * Replaces the mapped Plane fields on an existing Notion work-item page.
     *
     * @param pageId existing Notion page ID
     * @param properties current mapped Plane values
     */
    public void updateWorkItem(String pageId, WorkItemProperties properties) {
        patch(PAGES + "/" + segment(pageId), new UpdateWorkItemPageRequest(properties));
    }

    /**
     * Adds newly observed state and label choices to the data-source schema while
     * retaining existing choices used by older rows.
     *
     * @param dataSourceId Notion data-source ID whose schema is synchronized
     * @param states current Plane state names
     * @param labels current Plane label names
     */
    public void ensureWorkItemOptions(String dataSourceId, List<String> states, List<String> labels) {
        DataSource dataSource = get(DATA_SOURCES + segment(dataSourceId), DataSource.class);
        var schema = dataSource.properties();
        List<String> currentStates = getOptions(schema == null || schema.state() == null
                ? null
                : schema.state().select());
        List<String> currentLabels = getOptions(schema == null || schema.labels() == null
                ? null
                : schema.labels().multiSelect());
        List<String> stateOptions = mergedOptions(currentStates, states);
        List<String> labelOptions = mergedOptions(currentLabels, labels);
        boolean stateChanged = !currentStates.equals(stateOptions);
        boolean labelsChanged = !currentLabels.equals(labelOptions);
        if (!stateChanged && !labelsChanged)
            return;

        WorkItemSchemaUpdates updates = new WorkItemSchemaUpdates(
                stateChanged ? new PropertyUpdate(new SelectSchema(optionObjects(stateOptions)), null) : null,
                labelsChanged ? new PropertyUpdate(null,
                        new MultiSelectSchema(optionObjects(labelOptions))) : null);
        patch(DATA_SOURCES + segment(dataSourceId), new UpdateDataSourceRequest(updates));
    }

    /** Archives a page created during incomplete destination setup. */
    private void archivePageAfterFailure(String pageId, RuntimeException provisioningFailure) {
        try {
            notionBlockClient.archivePage(pageId);
        } catch (RuntimeException cleanupFailure) {
            IntegrationException failure = new IntegrationException(ApiErrorCodes.Notion.SETUP_CLEANUP_FAILED, 502,
                    "Notion setup failed, and the temporary project page could not be archived.");
            failure.addSuppressed(provisioningFailure);
            failure.addSuppressed(cleanupFailure);
            throw failure;
        }
    }

    /**
     * Builds the fixed work-item data-source schema, optionally adding columns
     * that identify workspace and project for shared ad hoc rows.
     */
    private WorkItemSchema taskProperties(List<String> states, List<String> labels,
            boolean includeProjectIdentity) {
        return new WorkItemSchema(
                PropertyDefinition.titleDefinition(),
                PropertyDefinition.richTextDefinition(),
                PropertyDefinition.richTextDefinition(),
                PropertyDefinition.urlDefinition(),
                PropertyDefinition.richTextDefinition(),
                PropertyDefinition.select(optionObjects(states)),
                PropertyDefinition.select(optionObjects(NOTION_OPTIONS)),
                PropertyDefinition.richTextDefinition(),
                PropertyDefinition.multiSelect(optionObjects(labels)),
                PropertyDefinition.dateDefinition(),
                PropertyDefinition.dateDefinition(),
                PropertyDefinition.select(optionObjects(List.of(WorkItemLifecycle.values()).stream()
                        .map(WorkItemLifecycle::name).toList())),
                includeProjectIdentity ? PropertyDefinition.richTextDefinition() : null,
                includeProjectIdentity ? PropertyDefinition.richTextDefinition() : null,
                includeProjectIdentity ? PropertyDefinition.richTextDefinition() : null,
                includeProjectIdentity ? PropertyDefinition.richTextDefinition() : null);
    }

    /** Converts nonblank option names to distinct Notion option objects. */
    private List<NamedOption> optionObjects(List<String> names) {
        return names.stream().filter(name -> name != null && !name.isBlank()).distinct()
                .map(NamedOption::new).toList();
    }

    /** Appends new nonblank options without removing existing choices or duplicates. */
    private List<String> mergedOptions(List<String> current, List<String> incoming) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        current.stream().filter(name -> name != null && !name.isBlank()).forEach(result::add);
        incoming.stream().filter(name -> name != null && !name.isBlank()).forEach(result::add);
        return List.copyOf(result);
    }

    /** Extracts option names from a Notion select or multi-select schema response. */
    private List<String> getOptions(SelectOptions schema) {
        if (schema == null || schema.options() == null)
            return List.of();
        return schema.options().stream().map(NamedOption::name).filter(name -> name != null).toList();
    }

    /** Splits text into Notion-sized rich-text fragments. */
    private List<RichText> richText(String value) {
        List<RichText> result = new java.util.ArrayList<>();
        for (int start = 0; start < value.length(); start += 2000) {
            result.add(RichText.plain(value.substring(start, Math.min(value.length(), start + 2000))));
        }
        return result;
    }

    /** Sends a GET request after checking that the Notion connection token is set. */
    private <T> T get(String path, Class<T> responseType) {
        requireToken();
        return httpService.get(client, IntegrationProvider.NOTION, path, responseType);
    }

    /** Sends a POST request after checking that the Notion connection token is set. */
    private <T> T post(String path, Object body, Class<T> responseType) {
        requireToken();
        return httpService.post(client, IntegrationProvider.NOTION, path, body, responseType);
    }

    /** Sends a PATCH request after checking that the Notion connection token is set. */
    private void patch(String path, Object body) {
        requireToken();
        httpService.patchWithoutResponse(client, IntegrationProvider.NOTION, path, body);
    }

    /** Fails early when no Notion connection token is configured. */
    private void requireToken() {
        if (properties.notionConnectionToken() == null || properties.notionConnectionToken().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.TOKEN_MISSING, 0,
                    ApiErrorMessages.Notion.TOKEN_MISSING);
        }
    }

    /** Requires a nonblank ID on the returned page. */
    private String requiredId(Page object, String message) {
        return requiredId(object.id(), message);
    }

    /** Requires a nonblank ID on the returned database. */
    private String requiredId(Database object, String message) {
        return requiredId(object.id(), message);
    }

    /** Requires a nonblank ID on the returned data-source summary. */
    private String requiredId(dev.planetonotion.integrations.models.NotionResponseModels.DataSourceSummary object,
            String message) {
        return requiredId(object.id(), message);
    }

    /** Rejects a missing ID in a successful Notion response. */
    private String requiredId(String id, String message) {
        if (id == null || id.isBlank())
            throw new IntegrationException(ApiErrorCodes.Notion.RESPONSE_INVALID, 502, message);
        return id;
    }

    /** Encodes a value so it can safely occupy one URL path segment. */
    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * IDs of the Notion resources created for a configured or ad hoc destination.
     *
     * @param pageId destination page ID
     * @param databaseId database container ID
     * @param dataSourceId data-source schema and row collection ID
     */
    public record ProjectDestination(String pageId, String databaseId, String dataSourceId) {
    }
}
