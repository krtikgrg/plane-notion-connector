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
import static org.mockito.Mockito.inOrder;

import dev.planetonotion.TestData;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.services.PlaneService;
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
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkItemSyncServiceTest {

    @Mock private PlaneService planeService;
    @Mock private NotionClient notionClient;
    @Mock private SyncMappingRepository mappingRepository;

    @Test
    void syncProject_countsCreatedChangedAndUnchangedSourceProperties() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        var config = TestData.config(new ObjectId(), project.id(), dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus.ACTIVE,
                dev.planetonotion.common.PlaneToNotionEnums.ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        AtomicReference<tools.jackson.databind.JsonNode> workItem = new AtomicReference<>(TestData.json("""
                {"id":"item-1","project_id":"plane-project","name":"One task","identifier":"DEMO-1",
                 "state":{"name":"Todo"},"priority":"high","target_date":"","updated_at":"2026-10-31T10:00:00Z",
                 "archived_at":null,"description_html":"<p>Details</p>","assignees":[],"labels":[]}
                """));
        doAnswer(invocation -> {
            Consumer<List<tools.jackson.databind.JsonNode>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(workItem.get()));
            return null;
        }).when(planeService).forEachProjectWorkItemPage(anyString(), anyString(), isNull(), any(), any());
        when(planeService.listWorkItemStates("workspace", "plane-project")).thenReturn(List.of());
        when(planeService.listWorkItemLabels("workspace", "plane-project")).thenReturn(List.of());
        AtomicReference<SyncMappingDocument> mapping = new AtomicReference<>();
        when(mappingRepository.findByTargetDataSourceIdAndSourceIdIn(anyString(), any()))
                .thenAnswer(invocation -> mapping.get() == null ? List.of() : List.of(mapping.get()));
        when(notionClient.createWorkItem(anyString(), any())).thenReturn(TestData.json("{\"id\":\"notion-page-1\"}"));
        doAnswer(invocation -> {
            List<SyncMappingDocument> writes = invocation.getArgument(0);
            mapping.set(writes.getFirst());
            return null;
        }).when(mappingRepository).bulkUpsert(any());

        WorkItemSyncService service = new WorkItemSyncService(planeService, notionClient, mappingRepository);
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
        var config = TestData.config(new ObjectId(), project.id(), dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus.ACTIVE,
                dev.planetonotion.common.PlaneToNotionEnums.ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        doAnswer(invocation -> {
            java.util.function.Consumer<List<tools.jackson.databind.JsonNode>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(
                    TestData.json("{\"id\":\"item-1\",\"name\":\"One\",\"archived_at\":null}"),
                    TestData.json("{\"id\":\"item-2\",\"name\":\"Two\",\"archived_at\":null}")));
            return null;
        }).when(planeService).forEachProjectWorkItemPage(anyString(), anyString(), isNull(), any(), any());
        when(mappingRepository.findByTargetDataSourceIdAndSourceIdIn(anyString(), any()))
                .thenReturn(List.of());
        when(notionClient.createWorkItem(anyString(), any()))
                .thenReturn(TestData.json("{\"id\":\"notion-page-1\"}"))
                .thenThrow(new IntegrationException("NOTION_ERROR", 500, "Notion failed."));
        AtomicReference<WorkItemSyncService.SyncResult> progress = new AtomicReference<>();
        WorkItemSyncService service = new WorkItemSyncService(planeService, notionClient, mappingRepository);

        assertThatThrownBy(() -> service.syncProject(project, config, null, java.time.Instant.now(), progress::set))
                .isInstanceOf(IntegrationException.class);

        assertThat(progress.get().eligibleCount()).isEqualTo(2);
        assertThat(progress.get().createdCount()).isEqualTo(1);
        verify(mappingRepository, times(2)).bulkUpsert(any());
    }

    @Test
    void syncOne_persistsPendingMappingBeforeCreatingNotionPage() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        var config = TestData.config(new ObjectId(), project.id(), dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus.ACTIVE,
                dev.planetonotion.common.PlaneToNotionEnums.ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        when(planeService.getWorkItem("workspace", "plane-project", "item-1")).thenReturn(TestData.json("""
                {"id":"item-1","name":"One","archived_at":null,"state":{},"labels":[]}
                """));
        when(mappingRepository.findByTargetDataSourceIdAndSourceId(anyString(), anyString())).thenReturn(java.util.Optional.empty());
        when(notionClient.createWorkItem(anyString(), any())).thenReturn(TestData.json("{\"id\":\"notion-page-1\"}"));

        WorkItemSyncService service = new WorkItemSyncService(planeService, notionClient, mappingRepository);
        service.syncOne(config, project, "item-1", ignored -> {});

        InOrder order = inOrder(mappingRepository, notionClient);
        order.verify(mappingRepository).bulkUpsert(org.mockito.ArgumentMatchers.argThat(mappings ->
                mappings.size() == 1 && mappings.getFirst().targetId() == null));
        order.verify(notionClient).createWorkItem(anyString(), any());
        order.verify(mappingRepository).bulkUpsert(org.mockito.ArgumentMatchers.argThat(mappings ->
                mappings.size() == 1 && "notion-page-1".equals(mappings.getFirst().targetId())));
    }

    @Test
    void syncOne_recoversPageForPendingMappingAfterAmbiguousCreate() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        var config = TestData.config(new ObjectId(), project.id(), dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus.ACTIVE,
                dev.planetonotion.common.PlaneToNotionEnums.ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        when(planeService.getWorkItem("workspace", "plane-project", "item-1")).thenReturn(TestData.json("""
                {"id":"item-1","name":"One","archived_at":null,"state":{},"labels":[]}
                """));
        SyncMappingDocument pending = new SyncMappingDocument(new ObjectId(), "item-1", null, "notion-data-source", "");
        when(mappingRepository.findByTargetDataSourceIdAndSourceId(anyString(), anyString())).thenReturn(java.util.Optional.of(pending));
        when(notionClient.findWorkItemBySourceId("notion-data-source", "item-1"))
                .thenReturn(TestData.json("{\"results\":[{\"id\":\"recovered-page-id\"}]}"));

        WorkItemSyncService service = new WorkItemSyncService(planeService, notionClient, mappingRepository);
        WorkItemSyncService.SyncResult result = service.syncOne(config, project, "item-1", ignored -> {});

        assertThat(result.updatedCount()).isEqualTo(1);
        verify(notionClient).updateWorkItem(org.mockito.ArgumentMatchers.eq("recovered-page-id"), any());
        verify(notionClient, org.mockito.Mockito.never()).createWorkItem(anyString(), any());
        verify(mappingRepository).bulkUpsert(org.mockito.ArgumentMatchers.argThat(mappings ->
                mappings.size() == 1 && "recovered-page-id".equals(mappings.getFirst().targetId())));
    }
}
