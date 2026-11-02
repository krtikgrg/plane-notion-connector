package dev.planetonotion.services.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.integrations.models.PlaneApiModels.WorkItem;
import dev.planetonotion.integrations.models.NotionRequestModels.WorkItemProperties;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.repositories.SyncMappingRepository;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.TestData;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

@ExtendWith(MockitoExtension.class)
class WorkItemSyncServiceTest {

    @Mock
    private PlaneClient planeClient;
    @Mock
    private NotionClient notionClient;
    @Mock
    private SyncMappingRepository mappingRepository;

    @Test
    void syncProject_countsCreatedChangedAndUnchangedSourceProperties() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        AtomicReference<WorkItem> workItem = new AtomicReference<>(TestData.workItem("""
                {"id":"item-1","project_id":"plane-project","name":"One task","identifier":"DEMO-1",
                 "state":{"name":"Todo"},"priority":"high","target_date":"","updated_at":"2026-10-31T10:00:00Z",
                 "archived_at":null,"description_html":"<p>Details</p>","assignees":[],"labels":[]}
                """));
        doAnswer(invocation -> {
            Consumer<List<WorkItem>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(workItem.get()));
            return null;
        }).when(planeClient).forEachProjectWorkItemPage(anyString(), anyString(), isNull(), any(), any());
        when(planeClient.listWorkItemStates("workspace", "plane-project")).thenReturn(List.of());
        when(planeClient.listWorkItemLabels("workspace", "plane-project")).thenReturn(List.of());
        AtomicReference<SyncMappingDocument> mapping = new AtomicReference<>();
        when(mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn(anyString(), any()))
                .thenAnswer(invocation -> mapping.get() == null ? List.of() : List.of(mapping.get()));
        when(notionClient.createWorkItem(anyString(), any()))
                .thenReturn(TestData.notionPage("{\"id\":\"notion-page-1\"}"));
        doAnswer(invocation -> {
            List<SyncMappingDocument> writes = invocation.getArgument(0);
            mapping.set(writes.getFirst());
            return null;
        }).when(mappingRepository).bulkUpsert(any());

        WorkItemSyncService service = new WorkItemSyncService(planeClient, notionClient, mappingRepository);
        WorkItemSyncService.SyncResult first = service.syncProject(project, null,
                Instant.now(), ignored -> {
                });
        WorkItemSyncService.SyncResult second = service.syncProject(project, null,
                Instant.now(), ignored -> {
                });
        workItem.set(TestData.workItem("""
                {"id":"item-1","project_id":"plane-project","name":"Renamed task","identifier":"DEMO-1",
                 "state":{"name":"Todo"},"priority":"high","target_date":"","updated_at":"2026-10-31T11:00:00Z",
                 "archived_at":null,"description_html":"<p>Details</p>","assignees":[],"labels":[]}
                """));
        WorkItemSyncService.SyncResult third = service.syncProject(project, null,
                Instant.now(), ignored -> {
                });

        assertThat(first.eligibleCount()).isEqualTo(1);
        assertThat(first.createdCount()).isEqualTo(1);
        assertThat(first.updatedCount()).isZero();
        assertThat(second.createdCount()).isZero();
        assertThat(second.updatedCount()).isZero();
        assertThat(second.unchangedCount()).isEqualTo(1);
        assertThat(third.createdCount()).isZero();
        assertThat(third.updatedCount()).isEqualTo(1);
        assertThat(third.unchangedCount()).isZero();
        assertThat(mapping.get().planeSourceHash()).isNotBlank();
        verify(notionClient).createWorkItem(anyString(), any());
        verify(notionClient, times(1)).updateWorkItem(anyString(), any());
    }

    @Test
    void syncProject_reportsEligibleAndCompletedCountsWhenAnItemFails() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        doAnswer(invocation -> {
            Consumer<List<WorkItem>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(
                    TestData.workItem("{\"id\":\"item-1\",\"name\":\"One\",\"archived_at\":null}"),
                    TestData.workItem("{\"id\":\"item-2\",\"name\":\"Two\",\"archived_at\":null}")));
            return null;
        }).when(planeClient).forEachProjectWorkItemPage(anyString(), anyString(), isNull(), any(), any());
        when(mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn(anyString(), any()))
                .thenReturn(List.of());
        when(notionClient.createWorkItem(anyString(), any()))
                .thenReturn(TestData.notionPage("{\"id\":\"notion-page-1\"}"))
                .thenThrow(new IntegrationException("NOTION_ERROR", 500, "Notion failed."));
        AtomicReference<WorkItemSyncService.SyncResult> progress = new AtomicReference<>();
        WorkItemSyncService service = new WorkItemSyncService(planeClient, notionClient, mappingRepository);

        assertThatThrownBy(() -> service.syncProject(project, null, Instant.now(), progress::set))
                .isInstanceOf(IntegrationException.class);

        assertThat(progress.get().eligibleCount()).isEqualTo(2);
        assertThat(progress.get().createdCount()).isEqualTo(1);
        verify(mappingRepository, times(2)).bulkUpsert(any());
    }

    @Test
    void syncOne_persistsPendingMappingBeforeCreatingNotionPage() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        var config = TestData.config(new ObjectId(), project.id(), ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        when(planeClient.getWorkItem("workspace", "plane-project", "item-1")).thenReturn(TestData.workItem("""
                {"id":"item-1","name":"One","archived_at":null,"state":{},"labels":[]}
                """));
        when(mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn(
                "notion-data-source", List.of("item-1"))).thenReturn(List.of());
        when(notionClient.createWorkItem(anyString(), any()))
                .thenReturn(TestData.notionPage("{\"id\":\"notion-page-1\"}"));

        WorkItemSyncService service = new WorkItemSyncService(planeClient, notionClient, mappingRepository);
        service.syncOne(project, "item-1", ignored -> {
        });

        InOrder order = inOrder(mappingRepository, notionClient);
        order.verify(mappingRepository)
                .bulkUpsert(argThat(mappings -> mappings.size() == 1 && mappings.getFirst().notionPageId() == null));
        order.verify(notionClient).createWorkItem(anyString(), any());
        order.verify(mappingRepository).bulkUpsert(argThat(
                mappings -> mappings.size() == 1 && "notion-page-1".equals(mappings.getFirst().notionPageId())));

        ArgumentCaptor<WorkItemProperties> properties = ArgumentCaptor.forClass(WorkItemProperties.class);
        verify(notionClient).createWorkItem(eq("notion-data-source"), properties.capture());
        assertThat(properties.getValue().state()).isNull();
        assertThat(properties.getValue().priority()).isNull();
        assertThat(properties.getValue().dueDate()).isNull();
        assertThat(properties.getValue().planeUpdatedAt()).isNull();
    }

    @Test
    void syncOne_recoversPageForPendingMappingAfterAmbiguousCreate() {
        var project = TestData.project(new ObjectId(), "workspace", "plane-project");
        var config = TestData.config(new ObjectId(), project.id(), ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        when(planeClient.getWorkItem("workspace", "plane-project", "item-1")).thenReturn(TestData.workItem("""
                {"id":"item-1","name":"One","archived_at":null,"state":{},"labels":[]}
                """));
        SyncMappingDocument pending = new SyncMappingDocument(new ObjectId(), "item-1", null, "notion-data-source", "");
        when(mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn(
                "notion-data-source", List.of("item-1"))).thenReturn(List.of(pending));
        when(notionClient.findWorkItemBySourceId("notion-data-source", "item-1"))
                .thenReturn(TestData.notionPageQuery("{\"results\":[{\"id\":\"recovered-page-id\"}]}"));

        WorkItemSyncService service = new WorkItemSyncService(planeClient, notionClient, mappingRepository);
        WorkItemSyncService.SyncResult result = service.syncOne(project, "item-1", ignored -> {
        });

        assertThat(result.updatedCount()).isEqualTo(1);
        verify(notionClient).updateWorkItem(eq("recovered-page-id"), any());
        verify(notionClient, never()).createWorkItem(anyString(), any());
        verify(mappingRepository).bulkUpsert(argThat(
                mappings -> mappings.size() == 1 && "recovered-page-id".equals(mappings.getFirst().notionPageId())));
    }
}
