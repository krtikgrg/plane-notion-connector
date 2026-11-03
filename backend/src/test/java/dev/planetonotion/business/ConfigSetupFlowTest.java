package dev.planetonotion.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationType;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.integrations.NotionBlockClient;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.models.documents.jobs.ConfigOperationJobDocument;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.JobRepository;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.services.AdHocNotionDestinationService;
import dev.planetonotion.services.ConfigService;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.services.ProjectCatalogService;
import dev.planetonotion.services.ScheduleService;
import dev.planetonotion.services.sync.SyncJobWorker;
import dev.planetonotion.services.sync.WorkItemSyncService;
import dev.planetonotion.TestData;
import java.time.Instant;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

@ExtendWith(MockitoExtension.class)
class ConfigSetupFlowTest {

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
    private ProjectCatalogService projectCatalogService;
    @Mock
    private AdHocNotionDestinationService adHocDestinationService;
    @Mock
    private WorkItemSyncService workItemSyncService;
    @Mock
    private ConfigService configService;

    @Test
    void creatingConfigPersistsProvisioningStateAndQueuesConfigurationJob() {
        ObjectId projectRef = new ObjectId();
        ObjectId configId = new ObjectId();
        PlaneProjectDocument project = TestData.projectWithoutNotionDestination(projectRef, "workspace",
                "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(configRepository.existsByPlaneProjectDocumentId(projectRef)).thenReturn(false);
        when(configRepository.save(any()))
                .thenAnswer(invocation -> TestData.withId(invocation.getArgument(0), configId));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConfigService configService = new ConfigService(configRepository, projectRepository, planeClient,
                notionClient, notionBlockClient, notionHierarchyService, new ScheduleService(jobRepository),
                jobRepository);
        ConfigService.ConfigInput input = new ConfigService.ConfigInput(projectRef, ConfigStatus.ACTIVE,
                new SyncOptions(true, false), new SyncSchedule(ScheduleType.INTERVAL, Instant.now(), null,
                        new SyncSchedule.Interval(30, IntervalUnit.MINUTES), null));

        SyncConfigDocument created = configService.create(input);

        assertThat(created.id()).isEqualTo(configId);
        assertThat(created.operationStatus()).isEqualTo(ConfigOperationStatus.PROVISIONING);
        ArgumentCaptor<ConfigOperationJobDocument> job = ArgumentCaptor.forClass(ConfigOperationJobDocument.class);
        verify(jobRepository).save(job.capture());
        assertThat(job.getValue().jobType()).isEqualTo(JobType.CONFIGURATION);
        assertThat(job.getValue().jobStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getValue().operationType()).isEqualTo(ConfigOperationType.CREATE);
        assertThat(job.getValue().configId()).isEqualTo(configId);
        verify(notionClient, never()).createProjectDestination(any(), any(), any(), any());
        verify(notionHierarchyService, never()).refreshSummaries();
    }

    @Test
    void workerDispatchesConfigurationJobAndStoresItsSuccessfulOutcome() {
        ObjectId configId = new ObjectId();
        Instant now = Instant.now();
        ConfigOperationJobDocument queued = new ConfigOperationJobDocument(new ObjectId(), JobType.CONFIGURATION,
                JobStatus.RUNNING, configId, ConfigOperationType.CREATE, null, null, null,
                now, null, 1, null, null, now, now);
        when(jobRepository.claimNextDueJob(any())).thenReturn(Optional.of(queued));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncJobWorker worker = new SyncJobWorker(jobRepository, configRepository, projectRepository,
                workItemSyncService, projectCatalogService, notionHierarchyService, new ScheduleService(jobRepository),
                adHocDestinationService, configService);
        worker.claimAndRunOne();

        verify(configService).processOperation(queued);
        ArgumentCaptor<ConfigOperationJobDocument> saved = ArgumentCaptor.forClass(ConfigOperationJobDocument.class);
        verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().jobStatus()).isEqualTo(JobStatus.SUCCESS);
    }
}
