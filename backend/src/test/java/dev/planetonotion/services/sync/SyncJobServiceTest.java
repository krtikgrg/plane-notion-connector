package dev.planetonotion.services.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.TestData;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import java.net.URI;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncJobServiceTest {

    @Mock private SyncJobRepository jobRepository;
    @Mock private SyncConfigRepository configRepository;
    @Mock private PlaneProjectRepository projectRepository;
    @Mock private PlaneService planeService;

    private final ObjectId projectRef = new ObjectId();
    private final ObjectId configId = new ObjectId();
    private PlaneProjectDocument project;
    private SyncConfigDocument config;
    private SyncJobService service;

    @BeforeEach
    void setUp() {
        project = TestData.project(projectRef, "demo-workspace", "plane-project");
        config = TestData.config(configId, projectRef, ConfigStatus.ACTIVE, ScheduleType.INTERVAL,
                30, "MINUTES", null, null);
        service = new SyncJobService(jobRepository, configRepository, projectRepository, planeService,
                TestData.integrationProperties());
    }

    @Test
    void enqueueTask_resolvesIdentifierAndCreatesManualWorkItemJob() {
        stubActiveTask();
        stubProject();
        when(configRepository.findByPlaneProjectRef(projectRef)).thenReturn(Optional.of(config));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncJobDocument job = service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false);

        assertThat(job.triggerType()).isEqualTo(SyncJobType.MANUAL);
        assertThat(job.syncScope()).isEqualTo(SyncScope.WORK_ITEM);
        assertThat(job.status()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(job.configId()).isEqualTo(configId);
        assertThat(job.planeProjectRef()).isEqualTo(projectRef);
        assertThat(job.workItemId()).isEqualTo("item-7");
    }

    @Test
    void enqueueTask_requiresForceFlagForArchivedConfig() {
        stubActiveTask();
        stubProject();
        SyncConfigDocument archived = TestData.config(configId, projectRef, ConfigStatus.ARCHIVED,
                ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        when(configRepository.findByPlaneProjectRef(projectRef)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).errorCode()).isEqualTo("CONFIG_ARCHIVED"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    void enqueueTask_allowsOneTimeRunWhenArchivedConfigIsForced() {
        stubActiveTask();
        stubProject();
        SyncConfigDocument archived = TestData.config(configId, projectRef, ConfigStatus.ARCHIVED,
                ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        when(configRepository.findByPlaneProjectRef(projectRef)).thenReturn(Optional.of(archived));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncJobDocument job = service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, true);

        assertThat(job.forceArchivedConfig()).isTrue();
        assertThat(job.status()).isEqualTo(SyncJobStatus.PENDING);
    }

    @Test
    void enqueueTask_queuesActiveWorkItemToAdHocDestinationWhenProjectHasNoConfig() {
        stubActiveTask();
        stubProject();
        when(configRepository.findByPlaneProjectRef(projectRef)).thenReturn(Optional.empty());
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncJobDocument job = service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false);

        assertThat(job.configId()).isNull();
        assertThat(job.syncScope()).isEqualTo(SyncScope.WORK_ITEM);
        assertThat(job.workItemId()).isEqualTo("item-7");
    }

    @Test
    void enqueueTask_rejectsDraftWorkItem() {
        when(planeService.getWorkItemByIdentifier("demo-workspace", "DEMO-7"))
                .thenReturn(TestData.json("{\"id\":\"item-7\",\"project_id\":\"plane-project\",\"is_draft\":true}"));

        assertThatThrownBy(() -> service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).errorCode()).isEqualTo("DRAFT_WORK_ITEM"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    void enqueueTask_rejectsArchivedWorkItem() {
        when(planeService.getWorkItemByIdentifier("demo-workspace", "DEMO-7"))
                .thenReturn(TestData.json("{\"id\":\"item-7\",\"project_id\":\"plane-project\",\"is_draft\":false,\"archived_at\":\"2026-10-31T10:00:00Z\"}"));

        assertThatThrownBy(() -> service.enqueueTask("https://app.plane.so/demo-workspace/browse/DEMO-7", null, false))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).errorCode()).isEqualTo("ARCHIVED_WORK_ITEM"));
        verify(jobRepository, never()).save(any());
    }

    private void stubActiveTask() {
        when(planeService.getWorkItemByIdentifier("demo-workspace", "DEMO-7"))
                .thenReturn(TestData.json("{\"id\":\"item-7\",\"project_id\":\"plane-project\",\"is_draft\":false,\"archived_at\":null}"));
    }

    private void stubProject() {
        when(projectRepository.findByWorkspaceSlugAndProjectId("demo-workspace", "plane-project"))
                .thenReturn(Optional.of(project));
    }
}
