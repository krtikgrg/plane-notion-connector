package dev.planetonotion.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.integrations.models.PlaneApiModels.WorkItem;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.repositories.JobRepository;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.SyncMappingRepository;
import dev.planetonotion.services.AdHocNotionDestinationService;
import dev.planetonotion.services.ConfigService;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.services.ProjectCatalogService;
import dev.planetonotion.services.ScheduleService;
import dev.planetonotion.services.sync.SyncJobService;
import dev.planetonotion.services.sync.SyncJobWorker;
import dev.planetonotion.services.sync.WorkItemSyncService;
import dev.planetonotion.TestData;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

@ExtendWith(MockitoExtension.class)
class SyncProcessingFlowTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private SyncConfigRepository configRepository;
    @Mock
    private PlaneProjectRepository projectRepository;
    @Mock
    private SyncMappingRepository mappingRepository;
    @Mock
    private PlaneClient planeClient;
    @Mock
    private NotionClient notionClient;
    @Mock
    private ProjectCatalogService projectCatalogService;
    @Mock
    private NotionHierarchyService notionHierarchyService;
    @Mock
    private AdHocNotionDestinationService adHocDestinationService;
    @Mock
    private ConfigService configService;

    @Test
    void queuedProjectSync_fetchesPlaneItemsCreatesNotionPageAndMarksRunSuccessful() {
        ObjectId configId = new ObjectId();
        ObjectId projectRef = new ObjectId();
        ObjectId jobId = new ObjectId();
        Instant previousProjectRunStart = Instant.parse("2026-10-30T10:00:00Z");
        SyncConfigDocument config = TestData.config(configId, projectRef, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        PlaneProjectDocument project = TestData.project(projectRef, "workspace", "plane-project");
        when(configRepository.findById(configId)).thenReturn(Optional.of(config));
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(projectCatalogService.refreshProject(projectRef)).thenReturn(project);

        AtomicReference<SyncJobDocument> queued = new AtomicReference<>();
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            SyncJobDocument job = invocation.getArgument(0);
            SyncJobDocument saved = job.id() == null ? withId(job, jobId) : job;
            if (saved.jobStatus() == JobStatus.PENDING && saved.jobType() == JobType.MANUAL)
                queued.set(saved);
            return saved;
        });
        when(jobRepository.claimNextDueJob(any())).thenAnswer(invocation -> Optional.of(withStatus(
                queued.get(), JobStatus.RUNNING)));
        when(jobRepository.findLatestSuccessfulProjectSyncStart(configId))
                .thenReturn(Optional.of(previousProjectRunStart));
        when(jobRepository.findPendingScheduledJobsByConfigId(configId)).thenReturn(List.of());

        when(planeClient.listWorkItemStates("workspace", "plane-project"))
                .thenReturn(List.of(TestData.planeState("{\"id\":\"state-1\",\"name\":\"Todo\"}")));
        doAnswer(invocation -> {
            Consumer<List<WorkItem>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(TestData.workItem("""
                    {"id":"item-1","project_id":"plane-project","name":"Add dashboard","identifier":"DEMO-1",
                     "state":{"name":"Todo"},"priority":"high","target_date":"","updated_at":"2026-10-31T10:00:00Z",
                     "archived_at":null,"description_html":"<p>Useful work</p>","assignees":[],"labels":[]}
                    """)));
            return null;
        }).when(planeClient).forEachProjectWorkItemPage(eq("workspace"),
                eq("plane-project"), eq(previousProjectRunStart.minusMillis(100)), any(), any());
        when(planeClient.listWorkItemLabels("workspace", "plane-project")).thenReturn(List.of());
        when(planeClient.workItemUrl("workspace", "plane-project", "item-1"))
                .thenReturn("https://app.plane.so/workspace/projects/plane-project/issues/item-1");
        when(mappingRepository.findByNotionDataSourceIdAndPlaneWorkItemIdIn("notion-data-source", List.of("item-1")))
                .thenReturn(List.of());
        when(notionClient.createWorkItem(anyString(), any()))
                .thenReturn(TestData.notionPage("{\"id\":\"notion-page-1\"}"));
        doNothing().when(notionHierarchyService).synchronizeProjectPage(project);

        ScheduleService scheduleService = new ScheduleService(jobRepository);
        WorkItemSyncService workItemSyncService = new WorkItemSyncService(planeClient, notionClient,
                mappingRepository);
        SyncJobWorker worker = new SyncJobWorker(jobRepository, configRepository, projectRepository,
                workItemSyncService, projectCatalogService, notionHierarchyService, scheduleService,
                adHocDestinationService, configService);
        SyncJobService jobService = new SyncJobService(jobRepository, configRepository, projectRepository,
                planeClient, TestData.integrationProperties());

        SyncJobDocument queuedJob = jobService.enqueueProject(configId, false);
        assertThat(queuedJob.jobStatus()).isEqualTo(JobStatus.PENDING);
        worker.claimAndRunOne();

        ArgumentCaptor<List<SyncMappingDocument>> mapping = ArgumentCaptor.forClass(List.class);
        verify(mappingRepository, times(2)).bulkUpsert(mapping.capture());
        List<SyncMappingDocument> completedMappings = mapping.getAllValues().getLast();
        assertThat(completedMappings.getFirst().planeWorkItemId()).isEqualTo("item-1");
        assertThat(completedMappings.getFirst().notionPageId()).isEqualTo("notion-page-1");
        ArgumentCaptor<SyncJobDocument> savedJobs = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository, times(2)).save(savedJobs.capture());
        SyncJobDocument complete = savedJobs.getAllValues().get(1);
        assertThat(complete.jobStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(complete.result().eligiblePlaneWorkItemsCount()).isEqualTo(1);
        assertThat(complete.result().createdNotionPagesCount()).isEqualTo(1);
        verify(notionClient).ensureWorkItemOptions("notion-data-source", List.of("Todo"), List.of());
        ArgumentCaptor<Instant> lowerBound = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> upperBound = ArgumentCaptor.forClass(Instant.class);
        verify(planeClient).forEachProjectWorkItemPage(eq("workspace"), eq("plane-project"),
                lowerBound.capture(), upperBound.capture(), any());
        assertThat(lowerBound.getValue()).isEqualTo(previousProjectRunStart.minusMillis(100));
        assertThat(upperBound.getValue()).isAfter(previousProjectRunStart);
    }

    private SyncJobDocument withId(SyncJobDocument job, ObjectId id) {
        return TestData.withJobId(job, id);
    }

    private SyncJobDocument withStatus(SyncJobDocument job, JobStatus status) {
        return TestData.withStatus(job, status);
    }
}
