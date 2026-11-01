package dev.planetonotion.services.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import dev.planetonotion.TestData;
import dev.planetonotion.integrations.notion.NotionClient;
import dev.planetonotion.integrations.plane.PlaneClient;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.repositories.mappings.SyncMappingRepository;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkItemSyncServiceTest {

    @Mock private PlaneClient planeClient;
    @Mock private NotionClient notionClient;
    @Mock private SyncMappingRepository mappingRepository;

    @Test
    void syncProject_countsCreatedChangedAndUnchangedSourceProperties() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        var config = TestData.config(new ObjectId(), project.id(), dev.planetonotion.common.enums.ConfigStatus.ACTIVE,
                dev.planetonotion.common.enums.ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        AtomicReference<tools.jackson.databind.JsonNode> workItem = new AtomicReference<>(TestData.json("""
                {"id":"item-1","project_id":"plane-project","name":"One task","identifier":"DEMO-1",
                 "state":{"name":"Todo"},"priority":"high","target_date":"","updated_at":"2026-10-31T10:00:00Z",
                 "archived_at":null,"description_html":"<p>Details</p>","assignees":[],"labels":[]}
                """));
        doAnswer(invocation -> {
            Consumer<List<tools.jackson.databind.JsonNode>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(workItem.get()));
            return null;
        }).when(planeClient).forEachProjectWorkItemPage(anyString(), anyString(), isNull(), any(), any());
        when(planeClient.listWorkItemStates("workspace", "plane-project")).thenReturn(List.of());
        when(planeClient.listWorkItemLabels("workspace", "plane-project")).thenReturn(List.of());
        AtomicReference<SyncMappingDocument> mapping = new AtomicReference<>();
        when(mappingRepository.findByTargetDataSourceIdAndSourceIdIn(anyString(), any()))
                .thenAnswer(invocation -> mapping.get() == null ? List.of() : List.of(mapping.get()));
        when(notionClient.createWorkItem(anyString(), any())).thenReturn(TestData.json("{\"id\":\"notion-page-1\"}"));
        doAnswer(invocation -> {
            List<SyncMappingDocument> writes = invocation.getArgument(0);
            mapping.set(writes.getFirst());
            return null;
        }).when(mappingRepository).bulkUpsert(any());

        WorkItemSyncService service = new WorkItemSyncService(planeClient, notionClient, mappingRepository);
        WorkItemSyncService.SyncResult first = service.syncProject(project, config, null,
                java.time.Instant.now(), ignored -> {});
        WorkItemSyncService.SyncResult second = service.syncProject(project, config, null,
                java.time.Instant.now(), ignored -> {});
        workItem.set(TestData.json("""
                {"id":"item-1","project_id":"plane-project","name":"Renamed task","identifier":"DEMO-1",
                 "state":{"name":"Todo"},"priority":"high","target_date":"","updated_at":"2026-10-31T11:00:00Z",
                 "archived_at":null,"description_html":"<p>Details</p>","assignees":[],"labels":[]}
                """));
        WorkItemSyncService.SyncResult third = service.syncProject(project, config, null,
                java.time.Instant.now(), ignored -> {});

        assertThat(first.eligibleCount()).isEqualTo(1);
        assertThat(first.createdCount()).isEqualTo(1);
        assertThat(first.updatedCount()).isZero();
        assertThat(second.createdCount()).isZero();
        assertThat(second.updatedCount()).isZero();
        assertThat(second.unchangedCount()).isEqualTo(1);
        assertThat(third.createdCount()).isZero();
        assertThat(third.updatedCount()).isEqualTo(1);
        assertThat(third.unchangedCount()).isZero();
        assertThat(mapping.get().sourceHash()).isNotBlank();
        verify(notionClient).createWorkItem(anyString(), any());
        verify(notionClient, times(1)).updateWorkItem(anyString(), any());
    }

    @Test
    void syncProject_reportsEligibleAndCompletedCountsWhenAnItemFails() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        var config = TestData.config(new ObjectId(), project.id(), dev.planetonotion.common.enums.ConfigStatus.ACTIVE,
                dev.planetonotion.common.enums.ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        doAnswer(invocation -> {
            java.util.function.Consumer<List<tools.jackson.databind.JsonNode>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(
                    TestData.json("{\"id\":\"item-1\",\"name\":\"One\",\"archived_at\":null}"),
                    TestData.json("{\"id\":\"item-2\",\"name\":\"Two\",\"archived_at\":null}")));
            return null;
        }).when(planeClient).forEachProjectWorkItemPage(anyString(), anyString(), isNull(), any(), any());
        when(mappingRepository.findByTargetDataSourceIdAndSourceIdIn(anyString(), any()))
                .thenReturn(List.of());
        when(notionClient.createWorkItem(anyString(), any()))
                .thenReturn(TestData.json("{\"id\":\"notion-page-1\"}"))
                .thenThrow(new IntegrationException("NOTION_ERROR", 500, "Notion failed."));
        AtomicReference<WorkItemSyncService.SyncResult> progress = new AtomicReference<>();
        WorkItemSyncService service = new WorkItemSyncService(planeClient, notionClient, mappingRepository);

        assertThatThrownBy(() -> service.syncProject(project, config, null, java.time.Instant.now(), progress::set))
                .isInstanceOf(IntegrationException.class);

        assertThat(progress.get().eligibleCount()).isEqualTo(2);
        assertThat(progress.get().createdCount()).isEqualTo(1);
        verify(mappingRepository).bulkUpsert(any());
    }
}
