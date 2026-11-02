package dev.planetonotion.services;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.CalendarFrequency;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.integrations.NotionClient.ProjectDestination;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.services.ScheduleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {

    private final SyncConfigRepository configRepository;
    private final PlaneProjectRepository projectRepository;
    private final PlaneService planeService;
    private final NotionClient notionClient;
    private final NotionHierarchyService notionHierarchyService;
    private final ScheduleService scheduleService;

    public ConfigService(SyncConfigRepository configRepository, PlaneProjectRepository projectRepository,
            PlaneService planeService, NotionClient notionClient, NotionHierarchyService notionHierarchyService,
            ScheduleService scheduleService) {
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.planeService = planeService;
        this.notionClient = notionClient;
        this.notionHierarchyService = notionHierarchyService;
        this.scheduleService = scheduleService;
    }

    public List<SyncConfigDocument> list() {
        return configRepository.findAllByOrderByCreatedAtDesc();
    }

    public SyncConfigDocument get(ObjectId configId) {
        return findConfig(configId);
    }

    public SyncConfigDocument create(ConfigInput input) {
        validate(input, true);
        PlaneProjectDocument project = projectRepository.findById(input.planeProjectDocumentId())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The selected Plane project was not found."));
        if (!project.isAvailable() || project.isArchivedInPlane()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "PROJECT_NOT_SELECTABLE", "Choose an available, non-archived Plane project.");
        }
        if (configRepository.existsByPlaneProjectDocumentId(project.id())) {
            throw new ServiceException(HttpStatus.CONFLICT, "CONFIG_ALREADY_EXISTS", "This Plane project already has a sync config.");
        }
        if (hasAnyNotionDestination(project)) {
            throw new ServiceException(HttpStatus.CONFLICT, "NOTION_DESTINATION_ALREADY_EXISTS",
                    "This Plane project already has Notion destination details but no sync config. Repair the destination before retrying.");
        }

        String workspacePageId = notionHierarchyService.ensureWorkspacePage(project.planeWorkspaceSlug()).notionPageId();
        ProjectDestination destination = notionClient.createProjectDestination(workspacePageId,
                project.planeProjectName(), names(planeService.listWorkItemStates(project.planeWorkspaceSlug(), project.planeProjectId())),
                names(planeService.listWorkItemLabels(project.planeWorkspaceSlug(), project.planeProjectId())));
        Instant now = Instant.now();
        ConfigStatus status = input.status() == null ? ConfigStatus.ACTIVE : input.status();
        ObjectId configId = new ObjectId();
        try {
            project = projectRepository.save(withNotionDestination(project, destination, now));
        } catch (RuntimeException exception) {
            try {
                notionClient.archivePage(destination.pageId());
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        SyncConfigDocument draft = new SyncConfigDocument(configId, status, project.id(), input.syncOptions(),
                normalizeSchedule(input.schedule()), now, now);

        SyncConfigDocument saved;
        try {
            saved = configRepository.save(draft);
        } catch (DuplicateKeyException exception) {
            compensateCreate(configId, project, destination.pageId(), exception);
            throw new ServiceException(HttpStatus.CONFLICT, "CONFIG_ALREADY_EXISTS", "This Plane project already has a sync config.");
        } catch (RuntimeException exception) {
            compensateCreate(configId, project, destination.pageId(), exception);
            throw exception;
        }
        try {
            scheduleService.reset(saved);
        } catch (RuntimeException exception) {
            compensateCreate(configId, project, destination.pageId(), exception);
            throw exception;
        }
        notionHierarchyService.refreshSummaries();
        return saved;
    }

    private void compensateCreate(ObjectId configId, PlaneProjectDocument project, String projectPageId,
            RuntimeException createFailure) {
        List<RuntimeException> cleanupFailures = new ArrayList<>();
        try {
            scheduleService.cancelPending(configId);
        } catch (RuntimeException exception) {
            cleanupFailures.add(exception);
        }
        try {
            configRepository.deleteById(configId);
        } catch (RuntimeException exception) {
            cleanupFailures.add(exception);
        }
        try {
            projectRepository.save(withoutNotionDestination(project, Instant.now()));
        } catch (RuntimeException exception) {
            cleanupFailures.add(exception);
        }
        try {
            notionClient.archivePage(projectPageId);
        } catch (RuntimeException exception) {
            cleanupFailures.add(exception);
        }
        if (cleanupFailures.isEmpty()) return;

        IntegrationException cleanupFailure = new IntegrationException("CONFIG_CREATE_CLEANUP_FAILED", 502,
                "Configuration creation failed, and cleanup was incomplete. Check the config and Notion destination before retrying.");
        cleanupFailure.addSuppressed(createFailure);
        cleanupFailures.forEach(cleanupFailure::addSuppressed);
        throw cleanupFailure;
    }

    public SyncConfigDocument update(ObjectId configId, ConfigInput input) {
        validate(input, false);
        SyncConfigDocument old = findConfig(configId);
        SyncConfigDocument updated = new SyncConfigDocument(
                old.id(), input.status(), old.planeProjectDocumentId(), input.syncOptions(), normalizeSchedule(input.schedule()),
                old.createdAt(), Instant.now());
        SyncConfigDocument saved = configRepository.save(updated);
        try {
            scheduleService.reset(saved);
        } catch (RuntimeException updateFailure) {
            try {
                configRepository.save(old);
                scheduleService.reset(old);
            } catch (RuntimeException rollbackFailure) {
                IntegrationException failure = new IntegrationException("CONFIG_UPDATE_ROLLBACK_FAILED", 502,
                        "Configuration update failed, and its previous schedule could not be restored.");
                failure.addSuppressed(updateFailure);
                failure.addSuppressed(rollbackFailure);
                throw failure;
            }
            throw updateFailure;
        }
        notionHierarchyService.refreshSummaries();
        return saved;
    }

    private boolean hasAnyNotionDestination(PlaneProjectDocument project) {
        return project.notionProjectPageId() != null || project.notionDatabaseId() != null
                || project.notionDataSourceId() != null;
    }

    private PlaneProjectDocument withNotionDestination(PlaneProjectDocument project, ProjectDestination destination,
            Instant updatedAt) {
        return new PlaneProjectDocument(project.id(), project.planeWorkspaceSlug(), project.planeProjectId(), project.planeProjectName(),
                project.planeProjectIdentifier(), project.isArchivedInPlane(), project.isAvailable(), destination.pageId(),
                destination.databaseId(), destination.dataSourceId(), project.createdAt(), updatedAt);
    }

    private PlaneProjectDocument withoutNotionDestination(PlaneProjectDocument project, Instant updatedAt) {
        return new PlaneProjectDocument(project.id(), project.planeWorkspaceSlug(), project.planeProjectId(), project.planeProjectName(),
                project.planeProjectIdentifier(), project.isArchivedInPlane(), project.isAvailable(), null, null, null,
                project.createdAt(), updatedAt);
    }

    private SyncConfigDocument findConfig(ObjectId configId) {
        return configRepository.findById(configId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", "The sync config was not found."));
    }

    private void validate(ConfigInput input, boolean creating) {
        if (input == null || input.schedule() == null || input.schedule().type() == null || input.syncOptions() == null
                || (creating && input.planeProjectDocumentId() == null)) {
            throw invalid("A Plane project, sync options, and schedule type are required.");
        }
        validateOptions(input.syncOptions());
        if (!creating && input.status() == null) {
            throw invalid("A config status is required.");
        }
        SyncSchedule schedule = input.schedule();
        if (schedule.type() == ScheduleType.INTERVAL) {
            if (schedule.interval() == null || schedule.calendar() != null
                    || schedule.interval().value() == null || schedule.interval().value() < 1
                    || schedule.interval().unit() == null) {
                throw invalid("Interval schedules need a positive interval and MINUTES or HOURS unit.");
            }
        } else if (schedule.type() == ScheduleType.CALENDAR) {
            SyncSchedule.Calendar calendar = schedule.calendar();
            if (calendar == null || calendar.frequency() == null
                    || schedule.interval() != null || calendar.interval() == null || calendar.interval() < 1
                    || calendar.timeOfDay() == null) {
                throw invalid("Calendar schedules need DAILY or WEEKLY frequency, a positive interval, and a UTC time.");
            }
            if (calendar.frequency() == CalendarFrequency.WEEKLY) {
                if (calendar.daysOfWeek() == null || calendar.daysOfWeek().isEmpty()
                        || calendar.daysOfWeek().contains(null)) {
                    throw invalid("Weekly schedules need at least one day of the week.");
                }
            }
        }
        if (schedule.startAt() != null && schedule.endAt() != null && !schedule.endAt().isAfter(schedule.startAt())) {
            throw invalid("end_at must be after start_at.");
        }
    }

    private void validateOptions(SyncOptions options) {
        if (!options.workItems()) {
            throw invalid("Enable work_items for this prototype.");
        }
        if (options.pages()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "FEATURE_NOT_AVAILABLE", "Page sync is not implemented in the first cut.");
        }
    }

    private List<String> names(List<JsonNode> values) {
        List<String> names = new ArrayList<>();
        values.forEach(value -> {
            String name = value.path("name").asString("");
            if (!name.isBlank()) names.add(name);
        });
        return names;
    }

    private List<java.time.DayOfWeek> normalizedDays(List<java.time.DayOfWeek> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }

    private SyncSchedule normalizeSchedule(SyncSchedule schedule) {
        SyncSchedule.Interval interval = schedule.interval() == null ? null : new SyncSchedule.Interval(
                schedule.interval().value(), schedule.interval().unit());
        SyncSchedule.Calendar calendar = schedule.calendar() == null ? null : new SyncSchedule.Calendar(
                schedule.calendar().frequency(), schedule.calendar().interval(),
                normalizedDays(schedule.calendar().daysOfWeek()), schedule.calendar().timeOfDay());
        return new SyncSchedule(schedule.type(), schedule.startAt(), schedule.endAt(), interval, calendar);
    }

    private ServiceException invalid(String message) {
        return new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", message);
    }

    public record ConfigInput(ObjectId planeProjectDocumentId, ConfigStatus status, SyncOptions syncOptions,
            SyncSchedule schedule) {
    }
}
