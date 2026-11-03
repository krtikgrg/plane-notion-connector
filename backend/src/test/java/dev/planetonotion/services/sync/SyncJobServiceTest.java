package dev.planetonotion.services.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.jobs.ManualSyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobResult;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.JobRepository;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

@ExtendWith(MockitoExtension.class)
class SyncJobServiceTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private SyncConfigRepository configRepository;
    @Mock
    private PlaneProjectRepository projectRepository;
    @Mock
    private PlaneClient planeClient;

    private final ObjectId projectRef = new ObjectId();
    private final ObjectId configId = new ObjectId();
    private PlaneProjectDocument project;
    private SyncConfigDocument config;
    private SyncJobService service;

    @BeforeEach
    void setUp() {
        project = TestData.project(projectRef, "demo-workspace", "plane-project");
        config = TestData.config(configId, projectRef, ConfigStatus.ACTIVE, ScheduleType.INTERVAL,
                30, IntervalUnit.MINUTES, null, null);
        service = new SyncJobService(jobRepository, configRepository, projectRepository, planeClient,
                TestData.integrationProperties());
    }

    @Test
    void nextScheduledRuns_selectsEarliestRunPerConfigRegardlessOfRepositoryOrder() {
        ObjectId otherConfigId = new ObjectId();
        Instant later = Instant.parse("2026-11-02T12:00:00Z");
        Instant earlier = Instant.parse("2026-11-02T11:00:00Z");
        when(jobRepository.findAllPendingScheduledJobs()).thenReturn(List.of(
                pendingScheduled(configId, later),
                pendingScheduled(otherConfigId, earlier),
                pendingScheduled(configId, earlier)));

        var nextRuns = service.nextScheduledRuns();

        assertThat(nextRuns).containsEntry(configId.toHexString(), earlier)
                .containsEntry(otherConfigId.toHexString(), earlier);
    }

    @Test
    void enqueueTask_resolvesIdentifierAndCreatesManualWorkItemJob() {
        stubActiveTask();
        stubProject();
        when(configRepository.findByPlaneProjectDocumentId(projectRef)).thenReturn(Optional.of(config));
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncJobDocument job = service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false);

        assertThat(job.jobType()).isEqualTo(JobType.MANUAL);
        assertThat(job.scope()).isEqualTo(SyncScope.WORK_ITEM);
        assertThat(job.jobStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.configId()).isEqualTo(configId);
        assertThat(job.planeProjectDocumentId()).isEqualTo(projectRef);
        assertThat(job).isInstanceOfSatisfying(ManualSyncJobDocument.class, manual -> {
            assertThat(manual.planeWorkItem().workItemId()).isEqualTo("item-7");
            assertThat(manual.planeWorkItem().taskUrl()).isEqualTo("https://app.plane.so/demo-workspace/browse/DEMO-7");
        });
    }

    @Test
    void enqueueTask_requiresForceFlagForArchivedConfig() {
        stubActiveTask();
        stubProject();
        SyncConfigDocument archived = TestData.config(configId, projectRef, ConfigStatus.ARCHIVED,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        when(configRepository.findByPlaneProjectDocumentId(projectRef)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).errorCode())
                        .isEqualTo("CONFIG_ARCHIVED"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    void enqueueTask_allowsOneTimeRunWhenArchivedConfigIsForced() {
        stubActiveTask();
        stubProject();
        SyncConfigDocument archived = TestData.config(configId, projectRef, ConfigStatus.ARCHIVED,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        when(configRepository.findByPlaneProjectDocumentId(projectRef)).thenReturn(Optional.of(archived));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));

        SyncJobDocument job = service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, true);

        assertThat(job).isInstanceOfSatisfying(ManualSyncJobDocument.class,
                manual -> assertThat(manual.forceArchivedConfig()).isTrue());
        assertThat(job.jobStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void enqueueTask_queuesActiveWorkItemToAdHocDestinationWhenProjectHasNoConfig() {
        stubActiveTask();
        stubProject();
        when(configRepository.findByPlaneProjectDocumentId(projectRef)).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncJobDocument job = service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false);

        assertThat(job.configId()).isNull();
        assertThat(job.scope()).isEqualTo(SyncScope.WORK_ITEM);
        assertThat(job).isInstanceOfSatisfying(ManualSyncJobDocument.class,
                manual -> assertThat(manual.planeWorkItem().workItemId()).isEqualTo("item-7"));
    }

    @Test
    void enqueueTask_rejectsDraftWorkItem() {
        when(planeClient.getWorkItemByIdentifier("demo-workspace", "DEMO-7"))
                .thenReturn(
                        TestData.workItem("{\"id\":\"item-7\",\"project_id\":\"plane-project\",\"is_draft\":true}"));

        assertThatThrownBy(() -> service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).errorCode())
                        .isEqualTo("DRAFT_WORK_ITEM"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    void enqueueTask_rejectsArchivedWorkItem() {
        when(planeClient.getWorkItemByIdentifier("demo-workspace", "DEMO-7"))
                .thenReturn(TestData.workItem(
                        "{\"id\":\"item-7\",\"project_id\":\"plane-project\",\"is_draft\":false,\"archived_at\":\"2026-10-31T10:00:00Z\"}"));

        assertThatThrownBy(() -> service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).errorCode())
                        .isEqualTo("ARCHIVED_WORK_ITEM"));
        verify(jobRepository, never()).save(any());
    }

    private void stubActiveTask() {
        when(planeClient.getWorkItemByIdentifier("demo-workspace", "DEMO-7"))
                .thenReturn(TestData.workItem(
                        "{\"id\":\"item-7\",\"project_id\":\"plane-project\",\"is_draft\":false,\"archived_at\":null}"));
    }

    private void stubProject() {
        when(projectRepository.findByPlaneWorkspaceSlugAndPlaneProjectId("demo-workspace", "plane-project"))
                .thenReturn(Optional.of(project));
    }

    private SyncJobDocument pendingScheduled(ObjectId forConfig, Instant scheduledFor) {
        Instant now = Instant.now();
        return new ScheduledSyncJobDocument(new ObjectId(), JobType.SCHEDULED, SyncScope.PROJECT,
                JobStatus.PENDING,
                forConfig, projectRef, scheduledFor, null, null, 0,
                SyncJobResult.empty(), null, null, now, now);
    }
}
