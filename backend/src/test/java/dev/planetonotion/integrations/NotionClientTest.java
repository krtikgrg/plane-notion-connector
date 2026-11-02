package dev.planetonotion.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.planetonotion.common.exceptions.IntegrationException;
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
import dev.planetonotion.TestData;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NotionClientTest {

    private MockRestServiceServer server;

    @AfterEach
    void verifyRequests() {
        if (server != null)
            server.verify();
    }

    @Test
    void createProjectDestination_createsParentPageAndDatasourceSchema() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionClient client = new NotionClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);

        server.expect(requestTo("https://notion.test/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer unit-test-token"))
                .andExpect(content().string(containsString("workspace-page-id")))
                .andRespond(withSuccess("{\"id\":\"project-page-id\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/databases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Todo")))
                .andExpect(content().string(containsString("bug")))
                .andExpect(content().string(not(containsString("DELETED_IN_PLANE"))))
                .andRespond(withSuccess("""
                        {"id":"project-database-id","data_sources":[{"id":"project-data-source-id"}]}
                        """, MediaType.APPLICATION_JSON));

        NotionClient.ProjectDestination destination = client.createProjectDestination(
                "workspace-page-id", "Demo Project", List.of("Todo"), List.of("bug"));

        assertThat(destination.pageId()).isEqualTo("project-page-id");
        assertThat(destination.databaseId()).isEqualTo("project-database-id");
        assertThat(destination.dataSourceId()).isEqualTo("project-data-source-id");
    }

    @Test
    void createAdHocDestination_createsSharedPageAndProjectIdentitySchemaUnderParent() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionClient client = new NotionClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "root-page-id"), builder);

        server.expect(requestTo("https://notion.test/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("root-page-id")))
                .andExpect(content().string(containsString("Plane Manual Syncs")))
                .andRespond(withSuccess("{\"id\":\"ad-hoc-page-id\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/databases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("workspace_slug")))
                .andExpect(content().string(containsString("project_identifier")))
                .andExpect(content().string(containsString("project_name")))
                .andRespond(withSuccess("""
                        {"id":"ad-hoc-database-id","data_sources":[{"id":"ad-hoc-data-source-id"}]}
                        """, MediaType.APPLICATION_JSON));

        NotionClient.ProjectDestination destination = client.createAdHocDestination();

        assertThat(destination.pageId()).isEqualTo("ad-hoc-page-id");
        assertThat(destination.databaseId()).isEqualTo("ad-hoc-database-id");
        assertThat(destination.dataSourceId()).isEqualTo("ad-hoc-data-source-id");
    }

    @Test
    void findWorkItemBySourceId_queriesDataSourceByPlaneWorkItemId() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionClient client = new NotionClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);

        server.expect(requestTo("https://notion.test/v1/data_sources/data-source-id/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("work_item_id")))
                .andExpect(content().string(containsString("plane-item-id")))
                .andRespond(withSuccess("{\"results\":[{\"id\":\"notion-page-id\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.findWorkItemBySourceId("data-source-id", "plane-item-id")
                .results().get(0).id()).isEqualTo("notion-page-id");
    }

    @Test
    void createWorkItem_serializesKnownPropertyNamesAndValueTypes() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionClient client = new NotionClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);
        WorkItemProperties properties = new WorkItemProperties(
                TitleProperty.of(List.of(RichText.plain("Example task"))),
                RichTextProperty.of(List.of(RichText.plain("DEMO-1"))),
                RichTextProperty.of(List.of(RichText.plain("plane-work-item-id"))),
                UrlProperty.of("https://app.plane.test/work-item"),
                RichTextProperty.of(List.of(RichText.plain("Description"))),
                SelectProperty.of(new NamedOption("Todo")),
                SelectProperty.of(new NamedOption("high")),
                RichTextProperty.of(List.of()),
                MultiSelectProperty.of(List.of(new NamedOption("bug"))),
                DateProperty.of(new DateValue("2026-11-02")),
                DateProperty.of(new DateValue("2026-11-02T10:00:00Z")),
                SelectProperty.of(new NamedOption("ACTIVE")), null, null, null, null);

        server.expect(requestTo("https://notion.test/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"name\":{\"title\"")))
                .andExpect(content().string(containsString("\"work_item_id\":{\"rich_text\"")))
                .andExpect(content().string(containsString("\"work_item_url\":{\"url\"")))
                .andExpect(content().string(containsString("\"labels\":{\"multi_select\"")))
                .andExpect(content().string(containsString("\"due_date\":{\"date\"")))
                .andRespond(withSuccess("{\"id\":\"notion-page-id\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createWorkItem("data-source-id", properties).id()).isEqualTo("notion-page-id");
    }

    @Test
    void createProjectDestination_archivesPageWhenDatabaseCreationFails() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionClient client = new NotionClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);

        server.expect(requestTo("https://notion.test/v1/pages"))
                .andRespond(withSuccess("{\"id\":\"project-page-id\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/databases"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo("https://notion.test/v1/blocks/project-page-id"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        assertThatThrownBy(
                () -> client.createProjectDestination("workspace-page-id", "Demo Project", List.of(), List.of()))
                .isInstanceOf(IntegrationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOTION_API_ERROR");
    }

    @Test
    void archivePage_reportsCleanupFailure() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionBlockClient client = new NotionBlockClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);

        server.expect(requestTo("https://notion.test/v1/blocks/project-page-id"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.archivePage("project-page-id"))
                .isInstanceOf(IntegrationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOTION_ARCHIVE_FAILED");
    }

    @Test
    void upsertPageTableSummary_createsReadableSummaryTable() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionBlockClient client = new NotionBlockClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);

        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children?page_size=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(containsString("Workspace overview")))
                .andRespond(withSuccess("""
                        {"results":[{"id":"heading-id","type":"heading_2","heading_2":{"rich_text":[]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children?page_size=100"))
                .andRespond(withSuccess(
                        """
                                {"results":[{"id":"heading-id","type":"heading_2","heading_2":{"rich_text":[{"plain_text":"Workspace overview"}]}}],"has_more":false}
                                """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(containsString("1 workspace")))
                .andRespond(withSuccess("""
                        {"results":[{"id":"callout-id"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(containsString("Synced work items")))
                .andExpect(content().string(containsString("Workspace")))
                .andRespond(withSuccess("""
                        {"results":[{"id":"table-id","type":"table"}]}
                        """, MediaType.APPLICATION_JSON));

        client.upsertPageTableSummary("parent-page-id", "Workspace overview", List.of("Plane workspace rollup"),
                "Plane sync summary:", "1 workspace · 1 configured project · 3 synced work items",
                List.of("Workspace", "Projects", "Active", "Disabled", "Archived", "Synced work items"), List.of());
    }

    @Test
    void upsertPageTableSummary_rebuildsWhenSeveralRowsChanged() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionBlockClient client = new NotionBlockClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);

        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children?page_size=100"))
                .andRespond(withSuccess(
                        """
                                {"results":[
                                  {"id":"heading-id","type":"heading_2","heading_2":{"rich_text":[{"plain_text":"Workspace overview"}]}},
                                  {"id":"callout-id","type":"callout","callout":{"rich_text":[{"plain_text":"2 configured projects"}]}},
                                  {"id":"old-table-id","type":"table","table":{"table_width":2}}
                                ],"has_more":false}
                                """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/old-table-id/children?page_size=100"))
                .andRespond(withSuccess(
                        """
                                {"results":[
                                  {"id":"old-header","type":"table_row","table_row":{"cells":[[{"plain_text":"Old A"}],[{"plain_text":"Old B"}]]}},
                                  {"id":"old-row-1","type":"table_row","table_row":{"cells":[[{"plain_text":"Old 1"}],[{"plain_text":"Old 2"}]]}},
                                  {"id":"old-row-2","type":"table_row","table_row":{"cells":[[{"plain_text":"Old 3"}],[{"plain_text":"Old 4"}]]}}
                                ],"has_more":false}
                                """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(containsString("New Project")))
                .andExpect(content().string(containsString("\"type\":\"table_row\",\"table_row\":{")))
                .andRespond(withSuccess("""
                        {"results":[{"id":"new-table-id","type":"table"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/old-table-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(containsString("in_trash")))
                .andRespond(withSuccess());

        client.upsertPageTableSummary("parent-page-id", "Workspace overview", List.of(),
                "Workspace summary:", "2 configured projects", List.of("Project", "Status"),
                List.of(List.of("New Project", "Active"), List.of("Other Project", "Disabled")));
    }

    @Test
    void upsertPageTableSummary_skipsUnchangedCalloutAndTableRows() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionBlockClient client = new NotionBlockClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "unused", "https://notion.test", "unit-test-token", "parent-page-id"),
                builder);
        String summary = "1 configured project";
        server.expect(requestTo("https://notion.test/v1/blocks/parent-page-id/children?page_size=100"))
                .andRespond(withSuccess(
                        """
                                {"results":[
                                  {"id":"heading-id","type":"heading_2","heading_2":{"rich_text":[{"plain_text":"Workspace overview"}]}},
                                  {"id":"callout-id","type":"callout","callout":{"rich_text":[{"plain_text":"1 configured project"}]}},
                                  {"id":"table-id","type":"table","table":{"table_width":2}}
                                ],"has_more":false}
                                """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://notion.test/v1/blocks/table-id/children?page_size=100"))
                .andRespond(withSuccess(
                        """
                                {"results":[
                                  {"id":"header-id","type":"table_row","table_row":{"cells":[[{"plain_text":"Project"}],[{"plain_text":"Status"}]]}},
                                  {"id":"row-id","type":"table_row","table_row":{"cells":[[{"plain_text":"Demo"}],[{"plain_text":"ACTIVE"}]]}}
                                ],"has_more":false}
                                """,
                        MediaType.APPLICATION_JSON));

        client.upsertPageTableSummary("parent-page-id", "Workspace overview", List.of(),
                "Workspace summary:", summary, List.of("Project", "Status"),
                List.of(List.of("Demo", "ACTIVE")));
    }
}
