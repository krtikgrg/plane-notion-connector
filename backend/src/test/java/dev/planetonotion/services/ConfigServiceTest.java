package dev.planetonotion.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationType;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.integrations.NotionBlockClient;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.models.documents.jobs.ConfigOperationJobDocument;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import dev.planetonotion.repositories.JobRepository;
import dev.planetonotion.repositories.MongoQueryOptions;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock
    private SyncConfigRepository configRepository;
    @Mock
    private PlaneProjectRepository projectRepository;
    @Mock
    private JobRepository jobRepository;
    @Mock
    private PlaneClient planeClient;
    @Mock
    private NotionClient notionClient;
    @Mock
    private NotionBlockClient notionBlockClient;
    @Mock
    private NotionHierarchyService notionHierarchyService;
    @Mock
    private ScheduleService scheduleService;

    private ConfigService service;

    @BeforeEach
    void setUp() {
        service = new ConfigService(configRepository, projectRepository, planeClient, notionClient, notionBlockClient,
                notionHierarchyService, scheduleService, jobRepository);
    }

    @Test
    void list_ordersConfigsByMostRecentCreationTime() {
        when(configRepository.findAll(any())).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
        ArgumentCaptor<MongoQueryOptions> options = ArgumentCaptor.forClass(MongoQueryOptions.class);
        verify(configRepository).findAll(options.capture());
        assertThat(options.getValue().filters()).isEmpty();
        assertThat(options.getValue().sorts()).containsExactly(
                MongoQueryOptions.Sort.descending(SyncConfigDocument.FIELD_CREATED_AT));
    }

    @Test
    void createReturnsProvisioningConfigAndQueuesWorkWithoutCallingIntegrations() {
        ObjectId projectId = new ObjectId();
        ObjectId configId = new ObjectId();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                TestData.projectWithoutNotionDestination(projectId, "demo-workspace", "plane-project")));
        when(configRepository.existsByPlaneProjectDocumentId(projectId)).thenReturn(false);
        when(configRepository.save(any()))
                .thenAnswer(invocation -> TestData.withId(invocation.getArgument(0), configId));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncConfigDocument created = service.create(intervalInput(projectId, ConfigStatus.ACTIVE));

        assertThat(created.id()).isEqualTo(configId);
        assertThat(created.operationStatus()).isEqualTo(ConfigOperationStatus.PROVISIONING);
        ArgumentCaptor<ConfigOperationJobDocument> job = ArgumentCaptor.forClass(ConfigOperationJobDocument.class);
        verify(jobRepository).save(job.capture());
        assertThat(job.getValue().jobType()).isEqualTo(JobType.CONFIGURATION);
        assertThat(job.getValue().operationType()).isEqualTo(ConfigOperationType.CREATE);
        assertThat(job.getValue().jobStatus()).isEqualTo(JobStatus.PENDING);
        verify(notionClient, never()).createProjectDestination(any(), any(), any(), any());
        verify(scheduleService, never()).reset(any());
    }

    @Test
    void createRejectsPageSyncBeforeQueueing() {
        ConfigService.ConfigInput input = new ConfigService.ConfigInput(new ObjectId(), ConfigStatus.ACTIVE,
                new SyncOptions(true, true), intervalInput(null, ConfigStatus.ACTIVE).schedule());
        assertThatThrownBy(() -> service.create(input)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("Page sync is not implemented");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateProjectConfig() {
        ObjectId projectId = new ObjectId();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                TestData.project(projectId, "demo-workspace", "plane-project")));
        when(configRepository.existsByPlaneProjectDocumentId(projectId)).thenReturn(true);
        assertThatThrownBy(() -> service.create(intervalInput(projectId, ConfigStatus.ACTIVE)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("already has a sync config");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void updateKeepsCurrentSettingsAndQueuesDesiredSettingsWhileHoldingPendingSyncJobs() {
        ObjectId configId = new ObjectId();
        ObjectId projectId = new ObjectId();
        SyncConfigDocument existing = TestData.config(configId, projectId, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        when(configRepository.findById(configId)).thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncConfigDocument queued = service.update(configId, intervalInput(null, ConfigStatus.DISABLED));

        assertThat(queued.status()).isEqualTo(ConfigStatus.ACTIVE);
        assertThat(queued.operationStatus()).isEqualTo(ConfigOperationStatus.UPDATING);
        assertThat(queued.schedule()).isEqualTo(existing.schedule());
        verify(jobRepository).holdPendingByConfigId(org.mockito.ArgumentMatchers.eq(configId), any());
        ArgumentCaptor<ConfigOperationJobDocument> job = ArgumentCaptor.forClass(ConfigOperationJobDocument.class);
        verify(jobRepository).save(job.capture());
        assertThat(job.getValue().operationType()).isEqualTo(ConfigOperationType.UPDATE);
        assertThat(job.getValue().targetStatus()).isEqualTo(ConfigStatus.DISABLED);
    }

    @Test
    void processingCreateOperationProvisionsDestinationAndScheduleThenMarksConfigReady() {
        ObjectId configId = new ObjectId();
        ObjectId projectId = new ObjectId();
        SyncConfigDocument config = TestData.config(configId, projectId, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        PlaneProjectDocument project = TestData.projectWithoutNotionDestination(projectId, "workspace",
                "plane-project");
        when(configRepository.findById(configId)).thenReturn(Optional.of(config));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(notionHierarchyService.ensureWorkspacePage("workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "workspace", "workspace-page", Instant.now(),
                        Instant.now()));
        when(planeClient.listWorkItemStates("workspace", "plane-project")).thenReturn(List.of());
        when(planeClient.listWorkItemLabels("workspace", "plane-project")).thenReturn(List.of());
        when(notionClient.createProjectDestination("workspace-page", "Demo Project", List.of(), List.of()))
                .thenReturn(new NotionClient.ProjectDestination("notion-page", "notion-db", "notion-source"));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConfigOperationJobDocument job = new ConfigOperationJobDocument(new ObjectId(), JobType.CONFIGURATION,
                JobStatus.RUNNING, configId, ConfigOperationType.CREATE, null, null, null,
                Instant.now(), null, 1, null, null, Instant.now(), Instant.now());

        SyncConfigDocument ready = service.processOperation(job);

        assertThat(ready.operationStatus()).isEqualTo(ConfigOperationStatus.READY);
        verify(projectRepository).save(org.mockito.ArgumentMatchers.argThat(
                saved -> "notion-source".equals(saved.notionDataSourceId())));
        verify(scheduleService).reset(ready);
        verify(jobRepository).releaseHeldByConfigId(org.mockito.ArgumentMatchers.eq(configId), any());
        verify(notionHierarchyService).refreshSummaries();
    }

    private ConfigService.ConfigInput intervalInput(ObjectId projectRef, ConfigStatus status) {
        return new ConfigService.ConfigInput(projectRef, status, new SyncOptions(true, false),
                new SyncSchedule(ScheduleType.INTERVAL, Instant.now().plusSeconds(3600), null,
                        new SyncSchedule.Interval(30, IntervalUnit.MINUTES), null));
    }
}
