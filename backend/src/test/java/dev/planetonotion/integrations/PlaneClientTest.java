package dev.planetonotion.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.planetonotion.TestData;
import dev.planetonotion.common.exceptions.IntegrationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PlaneClientTest {

    private MockRestServiceServer server;

    @AfterEach
    void verifyRequests() {
        if (server != null)
            server.verify();
    }

    @Test
    void getWorkItemByIdentifier_doesNotScanProjectAfterNotFound() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PlaneClient client = new PlaneClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "test-token", "https://notion.test", "unused", "parent"), builder);

        server.expect(request -> {
            assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
            assertThat(request.getURI().getPath()).isEqualTo("/api/v2/workspaces/workspace/work-items/DEMO-7/");
        }).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.getWorkItemByIdentifier("workspace", "DEMO-7"))
                .isInstanceOf(IntegrationException.class);
    }

    @Test
    void getWorkItem_doesNotScanProjectWhenDetailReadHidesItem() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PlaneClient client = new PlaneClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "test-token", "https://notion.test", "unused", "parent"), builder);

        server.expect(request -> assertThat(request.getURI().getPath())
                .isEqualTo("/api/v2/workspaces/workspace/projects/project-id/work-items/item-id/"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.getWorkItem("workspace", "project-id", "item-id"))
                .isInstanceOf(IntegrationException.class);
    }

    @Test
    void projectWorkItems_usesActiveNonDraftIncrementalCursorWindow() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        PlaneClient client = new PlaneClient(TestData.integrationProperties("https://plane.test",
                "https://app.plane.test", "test-token", "https://notion.test", "unused", "parent"), builder);
        server.expect(request -> {
            assertThat(request.getURI().getQuery()).contains("archived=false", "is_draft=false", "order_by=updated_at",
                    "paginate=cursor", "per_page=200", "updated_at__gte=2026-10-31T09:59:59.900Z",
                    "updated_at__lte=2026-10-31T10:00:00Z");
        }).andRespond(withSuccess("""
                {"data":[{"id":"item-1"}],"next_cursor":"cursor-2","has_more":true}
                """, MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("cursor=cursor-2"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"item-2"}],"has_more":false}
                        """, MediaType.APPLICATION_JSON));

        List<String> itemIds = new ArrayList<>();
        client.forEachProjectWorkItemPage("workspace", "project-id",
                Instant.parse("2026-10-31T09:59:59.900Z"), Instant.parse("2026-10-31T10:00:00Z"),
                page -> page.forEach(item -> itemIds.add(item.path("id").asString())));

        assertThat(itemIds).containsExactly("item-1", "item-2");
    }
}
