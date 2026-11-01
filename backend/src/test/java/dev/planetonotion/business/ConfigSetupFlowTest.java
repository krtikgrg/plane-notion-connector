package dev.planetonotion.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.TestData;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import dev.planetonotion.services.ConfigService;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.services.ScheduleService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigSetupFlowTest {

    @Mock private SyncConfigRepository configRepository;
    @Mock private PlaneProjectRepository projectRepository;
    @Mock private SyncJobRepository jobRepository;
    @Mock private PlaneService planeService;
    @Mock private NotionClient notionClient;
    @Mock private NotionHierarchyService notionHierarchyService;

    @Test
    void creatingActiveConfig_provisionsNotionDestinationAndSeedsFirstScheduledJob() {
        ObjectId projectRef = new ObjectId();
        ObjectId configId = new ObjectId();
        Instant startAt = Instant.now().plusSeconds(3600);
        PlaneProjectDocument project = TestData.project(projectRef, "workspace", "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(configRepository.existsByPlaneProjectRef(projectRef)).thenReturn(false);
        when(notionHierarchyService.ensureWorkspacePage("workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "workspace", "workspace-page", Instant.now(), Instant.now()));
        when(planeService.listWorkItemStates("workspace", "plane-project")).thenReturn(List.of());
        when(planeService.listWorkItemLabels("workspace", "plane-project")).thenReturn(List.of());
        when(notionClient.createProjectDestination("workspace-page", "Demo Project", List.of(), List.of()))
                .thenReturn(new NotionClient.ProjectDestination("notion-page", "notion-db", "notion-source"));
        when(configRepository.save(any())).thenAnswer(invocation ->
                TestData.withId(invocation.getArgument(0), configId));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ScheduleService scheduleService = new ScheduleService(jobRepository);
        ConfigService configService = new ConfigService(configRepository, projectRepository, planeService,
                notionClient, notionHierarchyService, scheduleService);
        ConfigService.ConfigInput input = new ConfigService.ConfigInput(projectRef, ConfigStatus.ACTIVE,
                Map.of("work_items", true, "pages", false), ScheduleType.INTERVAL, 30, "MINUTES",
                null, null, List.of(), null, startAt, null);

        SyncConfigDocument created = configService.create(input);

        assertThat(created.notionDataSourceId()).isEqualTo("notion-source");
        verify(jobRepository).deleteByConfigIdAndTriggerTypeAndStatus(
                configId, SyncJobType.SCHEDULED, SyncJobStatus.PENDING);
        ArgumentCaptor<SyncJobDocument> scheduled = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).save(scheduled.capture());
        assertThat(scheduled.getValue().configId()).isEqualTo(configId);
        assertThat(scheduled.getValue().planeProjectRef()).isEqualTo(projectRef);
        assertThat(scheduled.getValue().status()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(scheduled.getValue().scheduledFor()).isEqualTo(startAt);
    }
}
