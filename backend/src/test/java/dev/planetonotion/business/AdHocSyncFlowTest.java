package dev.planetonotion.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.TestData;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.mappings.SyncMappingRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.services.AdHocNotionDestinationService;
import dev.planetonotion.services.ProjectCatalogService;
import dev.planetonotion.services.ScheduleService;
import dev.planetonotion.services.sync.SyncJobService;
import dev.planetonotion.services.sync.SyncJobWorker;
import dev.planetonotion.services.sync.WorkItemSyncService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdHocSyncFlowTest {

    private static final String AD_HOC_DATA_SOURCE_ID = "adhoc-data-source";

    @Mock private SyncJobRepository jobRepository;
    @Mock private SyncConfigRepository configRepository;
    @Mock private PlaneProjectRepository projectRepository;
    @Mock private SyncMappingRepository mappingRepository;
    @Mock private PlaneService planeService;
    @Mock private NotionClient notionClient;
    @Mock private ProjectCatalogService projectCatalogService;
    @Mock private NotionHierarchyService notionHierarchyService;
    @Mock private AdHocNotionDestinationService adHocDestinationService;

    @Test
    void activeTaskUrlWithoutConfig_syncsIntoSharedAdHocDataSource() {
        ObjectId projectRef = new ObjectId();
        ObjectId jobId = new ObjectId();
        PlaneProjectDocument project = TestData.project(projectRef, "workspace", "plane-project");
        when(planeService.getWorkItemByIdentifier("workspace", "DEMO-9")).thenReturn(TestData.json("""
                {"id":"item-9","project_id":"plane-project","identifier":"DEMO-9",
                 "is_draft":false,"archived_at":null}
                """));
        when(planeService.getWorkItem("workspace", "plane-project", "item-9")).thenReturn(TestData.json("""
                {"id":"item-9","project_id":"plane-project","identifier":"DEMO-9","name":"Active task",
                 "is_draft":false,"archived_at":null,"state":{"name":"Done"},
                 "priority":"low","target_date":"","updated_at":"2026-10-31T10:00:00Z",
                 "description_html":"","assignees":[],"labels":[]}
                """));
        when(projectRepository.findByPlaneWorkspaceSlugAndPlaneProjectId("workspace", "plane-project"))
                .thenReturn(Optional.of(project));
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(configRepository.findByPlaneProjectDocumentId(projectRef)).thenReturn(Optional.empty());
        when(adHocDestinationService.getOrCreate()).thenReturn(new AdHocNotionDestinationDocument(
                AdHocNotionDestinationDocument.SHARED_DESTINATION_ID, "adhoc-page", "adhoc-database",
                AD_HOC_DATA_SOURCE_ID, Instant.now(), Instant.now()));
        when(mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemId(AD_HOC_DATA_SOURCE_ID, "item-9"))
                .thenReturn(Optional.empty());
        when(notionClient.createWorkItem(anyString(), any())).thenReturn(TestData.json("{\"id\":\"notion-page-9\"}"));
        when(planeService.workItemUrl("workspace", "plane-project", "item-9"))
                .thenReturn("https://app.plane.so/workspace/projects/plane-project/issues/item-9");
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            SyncJobDocument job = invocation.getArgument(0);
            return job.id() == null ? withId(job, jobId) : job;
        });
        AtomicReference<SyncJobDocument> queued = new AtomicReference<>();
        when(jobRepository.claimNextDueJob(any())).thenAnswer(invocation -> {
            queued.set(withStatus(queued.get(), SyncJobStatus.RUNNING));
            return Optional.of(queued.get());
        });

        SyncJobService jobService = new SyncJobService(jobRepository, configRepository, projectRepository, planeService,
                TestData.integrationProperties());
        WorkItemSyncService syncService = new WorkItemSyncService(planeService, notionClient, mappingRepository);
        SyncJobWorker worker = new SyncJobWorker(jobRepository, configRepository, projectRepository, syncService,
                projectCatalogService, notionHierarchyService, new ScheduleService(jobRepository),
                adHocDestinationService);

        SyncJobDocument manualJob = jobService.enqueueTask(
                "https://app.plane.so/workspace/browse/DEMO-9", null, false);
        queued.set(manualJob);
        worker.claimAndRunOne();

        ArgumentCaptor<SyncJobDocument> saved = ArgumentCaptor.forClass(SyncJobDocument.class);
        org.mockito.Mockito.verify(jobRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        SyncJobDocument finished = saved.getAllValues().get(1);
        assertThat(finished.jobStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(finished.configId()).isNull();
        assertThat(finished.result().createdNotionPagesCount()).isEqualTo(1);
        verify(adHocDestinationService).getOrCreate();
        verify(notionClient).ensureWorkItemOptions(AD_HOC_DATA_SOURCE_ID, List.of("Done"), List.of());
        ArgumentCaptor<List<SyncMappingDocument>> mappings = ArgumentCaptor.forClass(List.class);
        verify(mappingRepository, org.mockito.Mockito.times(2)).bulkUpsert(mappings.capture());
        List<SyncMappingDocument> completedMappings = mappings.getAllValues().getLast();
        assertThat(completedMappings.getFirst().notionDataSourceId()).isEqualTo(AD_HOC_DATA_SOURCE_ID);
        assertThat(completedMappings.getFirst().planeSourceHash()).isNotBlank();
    }

    private SyncJobDocument withId(SyncJobDocument job, ObjectId id) {
        return TestData.withJobId(job, id);
    }

    private SyncJobDocument withStatus(SyncJobDocument job, SyncJobStatus status) {
        return TestData.withStatus(job, status);
    }
}
