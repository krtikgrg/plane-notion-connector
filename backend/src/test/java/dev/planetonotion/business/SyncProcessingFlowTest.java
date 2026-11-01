package dev.planetonotion.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import dev.planetonotion.TestData;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
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
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncProcessingFlowTest {

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
    void queuedProjectSync_fetchesPlaneItemsCreatesNotionPageAndMarksRunSuccessful() {
        ObjectId configId = new ObjectId();
        ObjectId projectRef = new ObjectId();
        ObjectId jobId = new ObjectId();
        Instant previousProjectRunStart = Instant.parse("2026-10-30T10:00:00Z");
        SyncConfigDocument config = TestData.config(configId, projectRef, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        PlaneProjectDocument project = TestData.project(projectRef, "workspace", "plane-project");
        when(configRepository.findById(configId)).thenReturn(Optional.of(config));
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(projectCatalogService.refreshProject(projectRef)).thenReturn(project);

        AtomicReference<SyncJobDocument> queued = new AtomicReference<>();
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            SyncJobDocument job = invocation.getArgument(0);
            SyncJobDocument saved = job.id() == null ? withId(job, jobId) : job;
            if (saved.status() == SyncJobStatus.PENDING && saved.triggerType() == SyncJobType.MANUAL) queued.set(saved);
            return saved;
        });
        when(jobRepository.claimNextDueJob(any())).thenAnswer(invocation -> Optional.of(withStatus(
                queued.get(), SyncJobStatus.RUNNING)));
        when(jobRepository.findLatestSuccessfulProjectSyncStart(configId))
                .thenReturn(Optional.of(previousProjectRunStart));
        when(jobRepository.findByConfigIdAndTriggerTypeAndStatusOrderByScheduledForAsc(
                configId, SyncJobType.SCHEDULED, SyncJobStatus.PENDING)).thenReturn(List.of());

        when(planeService.listWorkItemStates("workspace", "plane-project"))
                .thenReturn(List.of(TestData.json("{\"id\":\"state-1\",\"name\":\"Todo\"}")));
        doAnswer(invocation -> {
            Consumer<List<tools.jackson.databind.JsonNode>> pageConsumer = invocation.getArgument(4);
            pageConsumer.accept(List.of(TestData.json("""
                {"id":"item-1","project_id":"plane-project","name":"Add dashboard","identifier":"DEMO-1",
                 "state":{"name":"Todo"},"priority":"high","target_date":"","updated_at":"2026-10-31T10:00:00Z",
                 "archived_at":null,"description_html":"<p>Useful work</p>","assignees":[],"labels":[]}
                """)));
            return null;
        }).when(planeService).forEachProjectWorkItemPage(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.eq("plane-project"), eq(previousProjectRunStart.minusMillis(100)), any(), any());
        when(planeService.listWorkItemLabels("workspace", "plane-project")).thenReturn(List.of());
        when(planeService.workItemUrl("workspace", "plane-project", "item-1"))
                .thenReturn("https://app.plane.so/workspace/projects/plane-project/issues/item-1");
        when(mappingRepository.findByTargetDataSourceIdAndSourceIdIn("notion-data-source", List.of("item-1")))
                .thenReturn(List.of());
        when(notionClient.createWorkItem(anyString(), any())).thenReturn(TestData.json("{\"id\":\"notion-page-1\"}"));
        org.mockito.Mockito.doNothing().when(notionHierarchyService).synchronizeProjectPage(project, config);

        ScheduleService scheduleService = new ScheduleService(jobRepository);
        WorkItemSyncService workItemSyncService = new WorkItemSyncService(planeService, notionClient, mappingRepository);
        SyncJobWorker worker = new SyncJobWorker(jobRepository, configRepository, projectRepository,
                workItemSyncService, projectCatalogService, notionHierarchyService, scheduleService,
                adHocDestinationService);
        SyncJobService jobService = new SyncJobService(jobRepository, configRepository, projectRepository,
                planeService, TestData.integrationProperties());

        SyncJobDocument queuedJob = jobService.enqueueProject(configId, false);
        assertThat(queuedJob.status()).isEqualTo(SyncJobStatus.PENDING);
        worker.claimAndRunOne();

        ArgumentCaptor<List<SyncMappingDocument>> mapping = ArgumentCaptor.forClass(List.class);
        verify(mappingRepository, times(2)).bulkUpsert(mapping.capture());
        List<dev.planetonotion.models.documents.SyncMappingDocument> completedMappings = mapping.getAllValues().getLast();
        assertThat(completedMappings.getFirst().sourceId()).isEqualTo("item-1");
        assertThat(completedMappings.getFirst().targetId()).isEqualTo("notion-page-1");
        ArgumentCaptor<SyncJobDocument> savedJobs = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository, times(2)).save(savedJobs.capture());
        SyncJobDocument complete = savedJobs.getAllValues().get(1);
        assertThat(complete.status()).isEqualTo(SyncJobStatus.SUCCESS);
        assertThat(complete.fetchedCount()).isEqualTo(1);
        assertThat(complete.eligibleCount()).isEqualTo(1);
        assertThat(complete.createdCount()).isEqualTo(1);
        assertThat(complete.failedCount()).isZero();
        verify(notionClient).ensureWorkItemOptions("notion-data-source", List.of("Todo"), List.of());
        ArgumentCaptor<Instant> lowerBound = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> upperBound = ArgumentCaptor.forClass(Instant.class);
        verify(planeService).forEachProjectWorkItemPage(eq("workspace"), eq("plane-project"),
                lowerBound.capture(), upperBound.capture(), any());
        assertThat(lowerBound.getValue()).isEqualTo(previousProjectRunStart.minusMillis(100));
        assertThat(upperBound.getValue()).isAfter(previousProjectRunStart);
    }

    private SyncJobDocument withId(SyncJobDocument job, ObjectId id) {
        return new SyncJobDocument(id, job.triggerType(), job.syncScope(), job.status(), job.configId(),
                job.planeProjectRef(), job.requestedTaskUrl(), job.workItemId(), job.forceArchivedConfig(),
                job.scheduledFor(), job.startedAt(), job.finishedAt(), job.attemptCount(), job.fetchedCount(), job.eligibleCount(),
                job.createdCount(), job.updatedCount(), job.unchangedCount(), job.failedCount(), job.errorCode(),
                job.errorMessage(), job.createdAt(), job.updatedAt());
    }

    private SyncJobDocument withStatus(SyncJobDocument job, SyncJobStatus status) {
        return new SyncJobDocument(job.id(), job.triggerType(), job.syncScope(), status, job.configId(),
                job.planeProjectRef(), job.requestedTaskUrl(), job.workItemId(), job.forceArchivedConfig(),
                job.scheduledFor(), Instant.now(), null, 1, 0, 0, 0, 0, 0, 0, null, null,
                job.createdAt(), Instant.now());
    }
}
