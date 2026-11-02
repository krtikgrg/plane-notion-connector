package dev.planetonotion.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

import dev.planetonotion.TestData;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.services.ScheduleService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock private SyncConfigRepository configRepository;
    @Mock private PlaneProjectRepository projectRepository;
    @Mock private PlaneService planeService;
    @Mock private NotionClient notionClient;
    @Mock private NotionHierarchyService notionHierarchyService;
    @Mock private ScheduleService scheduleService;

    private ConfigService service;

    @BeforeEach
    void setUp() {
        service = new ConfigService(configRepository, projectRepository, planeService, notionClient,
                notionHierarchyService, scheduleService);
    }

    @Test
    void create_provisionsDestinationPersistsConfigAndSchedulesIt() {
        ObjectId projectRef = new ObjectId();
        ObjectId configId = new ObjectId();
        PlaneProjectDocument project = TestData.projectWithoutNotionDestination(projectRef, "demo-workspace", "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.existsByPlaneProjectDocumentId(projectRef)).thenReturn(false);
        when(notionHierarchyService.ensureWorkspacePage("demo-workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "demo-workspace", "workspace-page-id", Instant.now(), Instant.now()));
        when(planeService.listWorkItemStates("demo-workspace", "plane-project"))
                .thenReturn(List.of(TestData.json("{\"name\":\"Todo\"}")));
        when(planeService.listWorkItemLabels("demo-workspace", "plane-project")).thenReturn(List.of());
        when(notionClient.createProjectDestination("workspace-page-id", "Demo Project", List.of("Todo"), List.of()))
                .thenReturn(new NotionClient.ProjectDestination("page-id", "db-id", "source-id"));
        when(configRepository.save(any())).thenAnswer(invocation -> TestData.withId(
                invocation.getArgument(0), configId));

        SyncConfigDocument saved = service.create(intervalInput(projectRef, ConfigStatus.ACTIVE));

        assertThat(saved.id()).isEqualTo(configId);
        ArgumentCaptor<PlaneProjectDocument> projectCaptor = ArgumentCaptor.forClass(PlaneProjectDocument.class);
        verify(projectRepository, atLeastOnce()).save(projectCaptor.capture());
        PlaneProjectDocument savedProject = projectCaptor.getValue();
        assertThat(savedProject.notionProjectPageId()).isEqualTo("page-id");
        assertThat(savedProject.notionDatabaseId()).isEqualTo("db-id");
        assertThat(savedProject.notionDataSourceId()).isEqualTo("source-id");
        verify(scheduleService).reset(saved);
    }

    @Test
    void create_rejectsPageSyncBeforeProvisioningDestination() {
        ConfigService.ConfigInput input = new ConfigService.ConfigInput(new ObjectId(), ConfigStatus.ACTIVE,
                new SyncOptions(true, true), new SyncSchedule(ScheduleType.INTERVAL, null, null,
                        new SyncSchedule.Interval(30, IntervalUnit.MINUTES), null));

        assertThatThrownBy(() -> service.create(input))
                .isInstanceOf(ServiceException.class).hasMessageContaining("Page sync is not implemented");
        verify(notionClient, never()).createProjectDestination(any(), any(), any(), any());
    }

    @Test
    void intervalUnitEnumExposesOnlySupportedCadences() {
        assertThat(IntervalUnit.values())
                .containsExactly(IntervalUnit.MINUTES, IntervalUnit.HOURS);
    }

    @Test
    void create_rejectsDuplicateProjectConfig() {
        ObjectId projectRef = new ObjectId();
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(
                TestData.project(projectRef, "demo-workspace", "plane-project")));
        when(configRepository.existsByPlaneProjectDocumentId(projectRef)).thenReturn(true);

        assertThatThrownBy(() -> service.create(intervalInput(projectRef, ConfigStatus.ACTIVE)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("already has a sync config");
        verify(notionClient, never()).createProjectDestination(any(), any(), any(), any());
    }

    @Test
    void create_removesConfigAndDestinationWhenScheduleSeedingFails() {
        ObjectId projectRef = new ObjectId();
        PlaneProjectDocument project = TestData.projectWithoutNotionDestination(projectRef, "demo-workspace", "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.existsByPlaneProjectDocumentId(projectRef)).thenReturn(false);
        when(notionHierarchyService.ensureWorkspacePage("demo-workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "demo-workspace", "workspace-page-id", Instant.now(), Instant.now()));
        when(planeService.listWorkItemStates("demo-workspace", "plane-project")).thenReturn(List.of());
        when(planeService.listWorkItemLabels("demo-workspace", "plane-project")).thenReturn(List.of());
        when(notionClient.createProjectDestination("workspace-page-id", "Demo Project", List.of(), List.of()))
                .thenReturn(new NotionClient.ProjectDestination("page-id", "db-id", "source-id"));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimeException scheduleFailure = new IllegalStateException("schedule write failed");
        doThrow(scheduleFailure).when(scheduleService).reset(any());

        assertThatThrownBy(() -> service.create(intervalInput(projectRef, ConfigStatus.ACTIVE)))
                .isSameAs(scheduleFailure);

        verify(configRepository).deleteById(any(ObjectId.class));
        verify(scheduleService).cancelPending(any(ObjectId.class));
        verify(notionClient).archivePage("page-id");
    }

    @Test
    void create_reportsWhenCompensationCannotArchiveNotionDestination() {
        ObjectId projectRef = new ObjectId();
        PlaneProjectDocument project = TestData.projectWithoutNotionDestination(projectRef, "demo-workspace", "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.existsByPlaneProjectDocumentId(projectRef)).thenReturn(false);
        when(notionHierarchyService.ensureWorkspacePage("demo-workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "demo-workspace", "workspace-page-id", Instant.now(), Instant.now()));
        when(planeService.listWorkItemStates("demo-workspace", "plane-project")).thenReturn(List.of());
        when(planeService.listWorkItemLabels("demo-workspace", "plane-project")).thenReturn(List.of());
        when(notionClient.createProjectDestination("workspace-page-id", "Demo Project", List.of(), List.of()))
                .thenReturn(new NotionClient.ProjectDestination("page-id", "db-id", "source-id"));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("schedule write failed")).when(scheduleService).reset(any());
        doThrow(new IllegalStateException("archive failed")).when(notionClient).archivePage("page-id");

        assertThatThrownBy(() -> service.create(intervalInput(projectRef, ConfigStatus.ACTIVE)))
                .isInstanceOf(dev.planetonotion.common.exceptions.IntegrationException.class)
                .hasFieldOrPropertyWithValue("errorCode", "CONFIG_CREATE_CLEANUP_FAILED");

        verify(configRepository).deleteById(any(ObjectId.class));
        verify(scheduleService).cancelPending(any(ObjectId.class));
    }

    @Test
    void update_persistsNewStatusAndResetsSchedule() {
        ObjectId configId = new ObjectId();
        ObjectId projectRef = new ObjectId();
        SyncConfigDocument existing = TestData.config(configId, projectRef, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        when(configRepository.findById(configId)).thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConfigService.ConfigInput update = intervalInput(null, ConfigStatus.DISABLED);

        SyncConfigDocument saved = service.update(configId, update);

        assertThat(saved.status()).isEqualTo(ConfigStatus.DISABLED);
        assertThat(saved.planeProjectDocumentId()).isEqualTo(projectRef);
        verify(scheduleService).reset(saved);
    }

    @Test
    void update_restoresPreviousConfigAndScheduleWhenReseedingFails() {
        ObjectId configId = new ObjectId();
        ObjectId projectRef = new ObjectId();
        SyncConfigDocument existing = TestData.config(configId, projectRef, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);
        when(configRepository.findById(configId)).thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            SyncConfigDocument config = invocation.getArgument(0);
            if (config.status() == ConfigStatus.DISABLED) throw new IllegalStateException("schedule write failed");
            return null;
        }).when(scheduleService).reset(any());

        assertThatThrownBy(() -> service.update(configId, intervalInput(null, ConfigStatus.DISABLED)))
                .isInstanceOf(IllegalStateException.class).hasMessage("schedule write failed");

        verify(configRepository).save(existing);
        verify(scheduleService).reset(existing);
    }

    private ConfigService.ConfigInput intervalInput(ObjectId projectRef, ConfigStatus status) {
        return new ConfigService.ConfigInput(projectRef, status, new SyncOptions(true, false),
                new SyncSchedule(ScheduleType.INTERVAL, Instant.now().plusSeconds(3600), null,
                        new SyncSchedule.Interval(30, IntervalUnit.MINUTES), null));
    }
}
