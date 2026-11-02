package dev.planetonotion.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.TestData;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.MongoQueryOptions;
import dev.planetonotion.repositories.MongoQueryOptions.Filter;
import dev.planetonotion.repositories.MongoQueryOptions.Sort;
import dev.planetonotion.repositories.PlaneProjectRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;

@ExtendWith(MockitoExtension.class)
class ProjectCatalogServiceTest {

    @Mock
    private PlaneService planeService;
    @Mock
    private PlaneProjectRepository projectRepository;

    @Test
    void list_requestsAvailableNonArchivedProjectsOrderedByName() {
        ProjectCatalogService service = new ProjectCatalogService(planeService, projectRepository,
                TestData.integrationProperties());
        when(projectRepository.findByPlaneWorkspaceSlug(eq("demo-workspace"), any()))
                .thenReturn(List.of());

        service.list("demo-workspace");

        ArgumentCaptor<MongoQueryOptions> options = ArgumentCaptor.forClass(MongoQueryOptions.class);
        verify(projectRepository).findByPlaneWorkspaceSlug(eq("demo-workspace"), options.capture());
        assertThat(options.getValue().filters()).containsExactly(
                Filter.equalTo(PlaneProjectDocument.FIELD_IS_AVAILABLE, true),
                Filter.equalTo(PlaneProjectDocument.FIELD_IS_ARCHIVED_IN_PLANE, false));
        assertThat(options.getValue().sorts()).containsExactly(
                Sort.ascending(PlaneProjectDocument.FIELD_PLANE_PROJECT_NAME));
    }

    @Test
    void refresh_upsertsPlaneProjectsAndMarksUnseenProjectsUnavailable() {
        ObjectId staleId = new ObjectId();
        PlaneProjectDocument stale = TestData.project(staleId, "demo-workspace", "stale-project");
        AtomicReference<List<PlaneProjectDocument>> saved = new AtomicReference<>(List.of(stale));
        when(projectRepository.findByPlaneWorkspaceSlug("demo-workspace", MongoQueryOptions.empty()))
                .thenReturn(List.of(stale)).thenAnswer(invocation -> saved.get());
        doAnswer(invocation -> {
            Consumer<List<JsonNode>> pageConsumer = invocation.getArgument(1);
            pageConsumer.accept(List.of(
                    TestData.json(
                            "{\"id\":\"fresh-project\",\"name\":\"Fresh\",\"identifier\":\"NEW\",\"archived_at\":null}"),
                    TestData.json(
                            "{\"id\":\"archived-project\",\"name\":\"Archived\",\"identifier\":\"OLD\",\"archived_at\":\"2026-10-30T10:00:00Z\"}")));
            return null;
        }).when(planeService).forEachProjectPage(eq("demo-workspace"), any());
        doAnswer(invocation -> {
            List<PlaneProjectDocument> batch = invocation.getArgument(0);
            Map<String, PlaneProjectDocument> allSaved = new LinkedHashMap<>();
            saved.get().forEach(project -> allSaved.put(project.planeProjectId(), project));
            batch.forEach(project -> allSaved.put(project.planeProjectId(), project));
            saved.set(new ArrayList<>(allSaved.values()));
            return null;
        }).when(projectRepository).bulkUpsert(anyList());
        ProjectCatalogService service = new ProjectCatalogService(planeService, projectRepository,
                TestData.integrationProperties());

        List<PlaneProjectDocument> refreshed = service.refresh("demo-workspace");

        assertThat(refreshed).hasSize(3);
        assertThat(refreshed).anySatisfy(project -> {
            assertThat(project.planeProjectId()).isEqualTo("fresh-project");
            assertThat(project.isAvailable()).isTrue();
            assertThat(project.planeProjectName()).isEqualTo("Fresh");
        });
        assertThat(refreshed).anySatisfy(project -> {
            assertThat(project.planeProjectId()).isEqualTo("stale-project");
            assertThat(project.isAvailable()).isFalse();
            assertThat(project.id()).isEqualTo(staleId);
        });
        assertThat(refreshed).anySatisfy(project -> {
            assertThat(project.planeProjectId()).isEqualTo("archived-project");
            assertThat(project.isArchivedInPlane()).isTrue();
            assertThat(project.isAvailable()).isTrue();
        });
    }
}
