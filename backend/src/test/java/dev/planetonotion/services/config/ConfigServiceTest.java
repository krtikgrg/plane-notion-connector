package dev.planetonotion.services.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

import dev.planetonotion.TestData;
import dev.planetonotion.common.enums.ConfigStatus;
import dev.planetonotion.common.enums.ScheduleType;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.notion.NotionClient;
import dev.planetonotion.integrations.plane.PlaneClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.repositories.config.SyncConfigRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.services.projects.NotionHierarchyService;
import dev.planetonotion.services.scheduling.ScheduleService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    @Mock private PlaneClient planeClient;
    @Mock private NotionClient notionClient;
    @Mock private NotionHierarchyService notionHierarchyService;
    @Mock private ScheduleService scheduleService;

    private ConfigService service;

    @BeforeEach
    void setUp() {
        service = new ConfigService(configRepository, projectRepository, planeClient, notionClient,
                notionHierarchyService, scheduleService);
    }

    @Test
    void create_provisionsDestinationPersistsConfigAndSchedulesIt() {
        ObjectId projectRef = new ObjectId();
        ObjectId configId = new ObjectId();
        PlaneProjectDocument project = TestData.project(projectRef, "demo-workspace", "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(configRepository.existsByPlaneProjectRef(projectRef)).thenReturn(false);
        when(notionHierarchyService.ensureWorkspacePage("demo-workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "demo-workspace", "workspace-page-id", Instant.now(), Instant.now()));
        when(planeClient.listWorkItemStates("demo-workspace", "plane-project"))
                .thenReturn(List.of(TestData.json("{\"name\":\"Todo\"}")));
        when(planeClient.listWorkItemLabels("demo-workspace", "plane-project")).thenReturn(List.of());
        when(notionClient.createProjectDestination("workspace-page-id", "Demo Project", List.of("Todo"), List.of()))
                .thenReturn(new NotionClient.ProjectDestination("page-id", "db-id", "source-id"));
        when(configRepository.save(any())).thenAnswer(invocation -> TestData.withId(
                invocation.getArgument(0), configId));

        SyncConfigDocument saved = service.create(intervalInput(projectRef, ConfigStatus.ACTIVE));

        assertThat(saved.id()).isEqualTo(configId);
        assertThat(saved.notionProjectPageId()).isEqualTo("page-id");
        assertThat(saved.notionDatabaseId()).isEqualTo("db-id");
        assertThat(saved.notionDataSourceId()).isEqualTo("source-id");
        verify(scheduleService).reset(saved);
    }

    @Test
    void create_rejectsPageSyncBeforeProvisioningDestination() {
        ConfigService.ConfigInput input = new ConfigService.ConfigInput(new ObjectId(), ConfigStatus.ACTIVE,
                Map.of("work_items", true, "pages", true), ScheduleType.INTERVAL, 30, "MINUTES",
                null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.create(input))
                .isInstanceOf(ServiceException.class).hasMessageContaining("Page sync is not implemented");
        verify(notionClient, never()).createProjectDestination(any(), any(), any(), any());
    }

    @Test
    void create_rejectsDuplicateProjectConfig() {
        ObjectId projectRef = new ObjectId();
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(
                TestData.project(projectRef, "demo-workspace", "plane-project")));
        when(configRepository.existsByPlaneProjectRef(projectRef)).thenReturn(true);

        assertThatThrownBy(() -> service.create(intervalInput(projectRef, ConfigStatus.ACTIVE)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("already has a sync config");
        verify(notionClient, never()).createProjectDestination(any(), any(), any(), any());
    }

    @Test
    void create_removesConfigAndDestinationWhenScheduleSeedingFails() {
        ObjectId projectRef = new ObjectId();
        PlaneProjectDocument project = TestData.project(projectRef, "demo-workspace", "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(configRepository.existsByPlaneProjectRef(projectRef)).thenReturn(false);
        when(notionHierarchyService.ensureWorkspacePage("demo-workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "demo-workspace", "workspace-page-id", Instant.now(), Instant.now()));
        when(planeClient.listWorkItemStates("demo-workspace", "plane-project")).thenReturn(List.of());
        when(planeClient.listWorkItemLabels("demo-workspace", "plane-project")).thenReturn(List.of());
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
        PlaneProjectDocument project = TestData.project(projectRef, "demo-workspace", "plane-project");
        when(projectRepository.findById(projectRef)).thenReturn(Optional.of(project));
        when(configRepository.existsByPlaneProjectRef(projectRef)).thenReturn(false);
        when(notionHierarchyService.ensureWorkspacePage("demo-workspace"))
                .thenReturn(new PlaneWorkspaceDocument(new ObjectId(), "demo-workspace", "workspace-page-id", Instant.now(), Instant.now()));
        when(planeClient.listWorkItemStates("demo-workspace", "plane-project")).thenReturn(List.of());
        when(planeClient.listWorkItemLabels("demo-workspace", "plane-project")).thenReturn(List.of());
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
                ScheduleType.INTERVAL, 30, "MINUTES", null, null);
        when(configRepository.findById(configId)).thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConfigService.ConfigInput update = intervalInput(null, ConfigStatus.DISABLED);

        SyncConfigDocument saved = service.update(configId, update);

        assertThat(saved.status()).isEqualTo(ConfigStatus.DISABLED);
        assertThat(saved.planeProjectRef()).isEqualTo(projectRef);
        verify(scheduleService).reset(saved);
    }

    @Test
    void update_restoresPreviousConfigAndScheduleWhenReseedingFails() {
        ObjectId configId = new ObjectId();
        ObjectId projectRef = new ObjectId();
        SyncConfigDocument existing = TestData.config(configId, projectRef, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, "MINUTES", null, null);
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
        return new ConfigService.ConfigInput(projectRef, status, Map.of("work_items", true, "pages", false),
                ScheduleType.INTERVAL, 30, "minutes", null, null, List.of(), null,
                Instant.now().plusSeconds(3600), null);
    }
}
