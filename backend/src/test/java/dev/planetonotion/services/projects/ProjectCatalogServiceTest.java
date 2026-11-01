package dev.planetonotion.services.projects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import dev.planetonotion.TestData;
import dev.planetonotion.common.config.PlaneProperties;
import dev.planetonotion.integrations.plane.PlaneClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectCatalogServiceTest {

    @Mock private PlaneClient planeClient;
    @Mock private PlaneProjectRepository projectRepository;

    @Test
    void refresh_upsertsPlaneProjectsAndMarksUnseenProjectsUnavailable() {
        ObjectId staleId = new ObjectId();
        PlaneProjectDocument stale = TestData.project(staleId, "demo-workspace", "stale-project");
        AtomicReference<List<PlaneProjectDocument>> saved = new AtomicReference<>(List.of(stale));
        when(projectRepository.findByWorkspaceSlug("demo-workspace")).thenReturn(List.of(stale)).thenAnswer(invocation -> saved.get());
        doAnswer(invocation -> {
            Consumer<List<tools.jackson.databind.JsonNode>> pageConsumer = invocation.getArgument(1);
            pageConsumer.accept(List.of(
                TestData.json("{\"id\":\"fresh-project\",\"name\":\"Fresh\",\"identifier\":\"NEW\",\"archived_at\":null}"),
                TestData.json("{\"id\":\"archived-project\",\"name\":\"Archived\",\"identifier\":\"OLD\",\"archived_at\":\"2026-10-30T10:00:00Z\"}")));
            return null;
        }).when(planeClient).forEachProjectPage(org.mockito.ArgumentMatchers.eq("demo-workspace"), any());
        doAnswer(invocation -> {
            List<PlaneProjectDocument> batch = invocation.getArgument(0);
            Map<String, PlaneProjectDocument> allSaved = new LinkedHashMap<>();
            saved.get().forEach(project -> allSaved.put(project.projectId(), project));
            batch.forEach(project -> allSaved.put(project.projectId(), project));
            saved.set(new ArrayList<>(allSaved.values()));
            return null;
        }).when(projectRepository).bulkUpsert(anyList());
        ProjectCatalogService service = new ProjectCatalogService(planeClient, projectRepository,
                new PlaneProperties(URI.create("https://api.plane.so"), URI.create("https://app.plane.so"), "unused"));

        List<PlaneProjectDocument> refreshed = service.refresh("demo-workspace");

        assertThat(refreshed).hasSize(3);
        assertThat(refreshed).anySatisfy(project -> {
            assertThat(project.projectId()).isEqualTo("fresh-project");
            assertThat(project.isAvailable()).isTrue();
            assertThat(project.name()).isEqualTo("Fresh");
        });
        assertThat(refreshed).anySatisfy(project -> {
            assertThat(project.projectId()).isEqualTo("stale-project");
            assertThat(project.isAvailable()).isFalse();
            assertThat(project.id()).isEqualTo(staleId);
        });
        assertThat(refreshed).anySatisfy(project -> {
            assertThat(project.projectId()).isEqualTo("archived-project");
            assertThat(project.isArchived()).isTrue();
            assertThat(project.isAvailable()).isTrue();
        });
    }
}
