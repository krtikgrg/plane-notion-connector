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

@Component
public class NotionClient {

    private static final String WORK_ITEM_DATABASE_TITLE = "Plane Work Items";

    private final RestClient client;
    private final HttpService httpService;
    private final IntegrationProperties properties;
    private final NotionBlockClient notionBlockClient;

    @Autowired
    public NotionClient(IntegrationProperties properties, HttpService httpService,
            IntegrationRestClientFactory clientFactory, NotionBlockClient notionBlockClient) {
        this(properties, clientFactory.builder(properties.notionApiBaseUrl().toString()), httpService,
                notionBlockClient);
    }

    NotionClient(IntegrationProperties properties, RestClient.Builder builder) {
        this(properties, builder, new HttpService(), new NotionBlockClient(properties, builder));
    }

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

    public ProjectDestination createProjectDestination(String workspacePageId, String projectName,
            List<String> states, List<String> labels) {
        return createDestination(workspacePageId, projectName, WORK_ITEM_DATABASE_TITLE,
                taskProperties(states, labels, false), "Notion did not return the project page ID.");
    }

    public ProjectDestination createAdHocDestination() {
        if (properties.notionParentPageId() == null || properties.notionParentPageId().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.CONFIGURATION_MISSING, 0,
                    "NOTION_PARENT_PAGE_ID is required to create the shared manual-sync destination.");
        }
        return createDestination(properties.notionParentPageId(), "Plane Manual Syncs", WORK_ITEM_DATABASE_TITLE,
                taskProperties(List.of(), List.of(), true), "Notion did not return the shared manual-sync page ID.");
    }

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

    public Page createWorkItem(String dataSourceId, WorkItemProperties properties) {
        return post(PAGES, new CreateWorkItemPageRequest(ParentReference.dataSource(dataSourceId), properties),
                Page.class);
    }

    public PageQuery findWorkItemBySourceId(String dataSourceId, String sourceId) {
        return post(DATA_SOURCES + segment(dataSourceId) + QUERY,
                new QueryDataSourceRequest(1,
                        new Filter(WORK_ITEM_ID, new RichTextFilter(sourceId))),
                PageQuery.class);
    }

    public void updateWorkItem(String pageId, WorkItemProperties properties) {
        patch(PAGES + "/" + segment(pageId), new UpdateWorkItemPageRequest(properties));
    }

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

    private List<NamedOption> optionObjects(List<String> names) {
        return names.stream().filter(name -> name != null && !name.isBlank()).distinct()
                .map(NamedOption::new).toList();
    }

    private List<String> mergedOptions(List<String> current, List<String> incoming) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        current.stream().filter(name -> name != null && !name.isBlank()).forEach(result::add);
        incoming.stream().filter(name -> name != null && !name.isBlank()).forEach(result::add);
        return List.copyOf(result);
    }

    private List<String> getOptions(SelectOptions schema) {
        if (schema == null || schema.options() == null)
            return List.of();
        return schema.options().stream().map(NamedOption::name).filter(name -> name != null).toList();
    }

    private List<RichText> richText(String value) {
        List<RichText> result = new java.util.ArrayList<>();
        for (int start = 0; start < value.length(); start += 2000) {
            result.add(RichText.plain(value.substring(start, Math.min(value.length(), start + 2000))));
        }
        return result;
    }

    private <T> T get(String path, Class<T> responseType) {
        requireToken();
        return httpService.get(client, IntegrationProvider.NOTION, path, responseType);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        requireToken();
        return httpService.post(client, IntegrationProvider.NOTION, path, body, responseType);
    }

    private void patch(String path, Object body) {
        requireToken();
        httpService.patchWithoutResponse(client, IntegrationProvider.NOTION, path, body);
    }

    private void requireToken() {
        if (properties.notionConnectionToken() == null || properties.notionConnectionToken().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.TOKEN_MISSING, 0,
                    ApiErrorMessages.Notion.TOKEN_MISSING);
        }
    }

    private String requiredId(Page object, String message) {
        return requiredId(object.id(), message);
    }

    private String requiredId(Database object, String message) {
        return requiredId(object.id(), message);
    }

    private String requiredId(dev.planetonotion.integrations.models.NotionResponseModels.DataSourceSummary object,
            String message) {
        return requiredId(object.id(), message);
    }

    private String requiredId(String id, String message) {
        if (id == null || id.isBlank())
            throw new IntegrationException(ApiErrorCodes.Notion.RESPONSE_INVALID, 502, message);
        return id;
    }

    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record ProjectDestination(String pageId, String databaseId, String dataSourceId) {
    }
}
