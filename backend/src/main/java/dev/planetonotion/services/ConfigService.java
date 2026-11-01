package dev.planetonotion.services;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.integrations.NotionClient.ProjectDestination;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.services.ScheduleService;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {

    private static final List<String> SYNC_OPTION_KEYS = List.of("work_items", "pages");

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
        PlaneProjectDocument project = projectRepository.findById(input.planeProjectRef())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The selected Plane project was not found."));
        if (!project.isAvailable() || project.isArchived()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "PROJECT_NOT_SELECTABLE", "Choose an available, non-archived Plane project.");
        }
        if (configRepository.existsByPlaneProjectRef(project.id())) {
            throw new ServiceException(HttpStatus.CONFLICT, "CONFIG_ALREADY_EXISTS", "This Plane project already has a sync config.");
        }

        String workspacePageId = notionHierarchyService.ensureWorkspacePage(project.workspaceSlug()).notionPageId();
        ProjectDestination destination = notionClient.createProjectDestination(workspacePageId,
                project.name(), names(planeService.listWorkItemStates(project.workspaceSlug(), project.projectId())),
                names(planeService.listWorkItemLabels(project.workspaceSlug(), project.projectId())));
        Instant now = Instant.now();
        ConfigStatus status = input.status() == null ? ConfigStatus.ACTIVE : input.status();
        ObjectId configId = new ObjectId();
        SyncConfigDocument draft = new SyncConfigDocument(configId, status, project.id(), destination.pageId(),
                destination.databaseId(), destination.dataSourceId(), input.syncOptions(), input.scheduleType(),
                input.intervalValue(), normalizeIntervalUnit(input.intervalUnit()), normalize(input.calendarFrequency()),
                input.calendarInterval(), normalizedDays(input.calendarDaysOfWeek()), input.calendarTimeOfDay(),
                input.startAt(), input.endAt(), now, now);

        SyncConfigDocument saved;
        try {
            saved = configRepository.save(draft);
        } catch (DuplicateKeyException exception) {
            compensateCreate(configId, destination.pageId(), exception);
            throw new ServiceException(HttpStatus.CONFLICT, "CONFIG_ALREADY_EXISTS", "This Plane project already has a sync config.");
        } catch (RuntimeException exception) {
            compensateCreate(configId, destination.pageId(), exception);
            throw exception;
        }
        try {
            scheduleService.reset(saved);
        } catch (RuntimeException exception) {
            compensateCreate(configId, destination.pageId(), exception);
            throw exception;
        }
        notionHierarchyService.refreshSummaries();
        return saved;
    }

    private void compensateCreate(ObjectId configId, String projectPageId, RuntimeException createFailure) {
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
                old.id(), input.status(), old.planeProjectRef(), old.notionProjectPageId(), old.notionDatabaseId(),
                old.notionDataSourceId(), input.syncOptions(), input.scheduleType(), input.intervalValue(),
                normalizeIntervalUnit(input.intervalUnit()), normalize(input.calendarFrequency()), input.calendarInterval(),
                normalizedDays(input.calendarDaysOfWeek()), input.calendarTimeOfDay(), input.startAt(), input.endAt(),
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

    private SyncConfigDocument findConfig(ObjectId configId) {
        return configRepository.findById(configId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", "The sync config was not found."));
    }

    private void validate(ConfigInput input, boolean creating) {
        if (input == null || input.scheduleType() == null || input.syncOptions() == null
                || (creating && input.planeProjectRef() == null)) {
            throw invalid("A Plane project, sync options, and schedule type are required.");
        }
        validateOptions(input.syncOptions());
        if (!creating && input.status() == null) {
            throw invalid("A config status is required.");
        }
        if (input.scheduleType() == ScheduleType.INTERVAL) {
            if (input.intervalValue() == null || input.intervalValue() < 1 || normalizeIntervalUnit(input.intervalUnit()) == null) {
                throw invalid("Interval schedules need a positive interval and MINUTES or HOURS unit.");
            }
        } else if (input.scheduleType() == ScheduleType.CALENDAR) {
            String frequency = normalize(input.calendarFrequency());
            if (frequency == null || !List.of("DAILY", "WEEKLY").contains(frequency)
                    || input.calendarInterval() == null || input.calendarInterval() < 1 || input.calendarTimeOfDay() == null) {
                throw invalid("Calendar schedules need DAILY or WEEKLY frequency, a positive interval, and a UTC time.");
            }
            if ("WEEKLY".equals(frequency)) {
                if (input.calendarDaysOfWeek() == null || input.calendarDaysOfWeek().isEmpty()) {
                    throw invalid("Weekly schedules need at least one day of the week.");
                }
                try {
                    input.calendarDaysOfWeek().forEach(day -> java.time.DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)));
                } catch (RuntimeException exception) {
                    throw invalid("Weekly schedule days must be MONDAY through SUNDAY.");
                }
            }
        }
        if (input.startAt() != null && input.endAt() != null && !input.endAt().isAfter(input.startAt())) {
            throw invalid("end_at must be after start_at.");
        }
    }

    private void validateOptions(Map<String, Boolean> options) {
        if (!Boolean.TRUE.equals(options.get("work_items"))) {
            throw invalid("Enable work_items for this prototype.");
        }
        if (Boolean.TRUE.equals(options.get("pages"))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "FEATURE_NOT_AVAILABLE", "Page sync is not implemented in the first cut.");
        }
        if (options.keySet().stream().anyMatch(key -> !SYNC_OPTION_KEYS.contains(key))) {
            throw invalid("sync_options contains an unsupported key.");
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

    private List<String> normalizedDays(List<String> values) {
        return values == null ? List.of() : values.stream().map(value -> value.toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private String normalizeIntervalUnit(String value) {
        String normalized = normalize(value);
        return normalized != null && List.of("MINUTES", "HOURS").contains(normalized) ? normalized : null;
    }

    private String normalize(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private ServiceException invalid(String message) {
        return new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", message);
    }

    public record ConfigInput(ObjectId planeProjectRef, ConfigStatus status, Map<String, Boolean> syncOptions,
            ScheduleType scheduleType, Integer intervalValue, String intervalUnit, String calendarFrequency,
            Integer calendarInterval, List<String> calendarDaysOfWeek, LocalTime calendarTimeOfDay,
            Instant startAt, Instant endAt) {
    }
}
