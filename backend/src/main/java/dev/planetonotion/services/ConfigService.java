package dev.planetonotion.services;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.PlaneToNotionEnums.CalendarFrequency;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationType;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.integrations.models.PlaneApiModels.Label;
import dev.planetonotion.integrations.models.PlaneApiModels.State;
import dev.planetonotion.integrations.NotionBlockClient;
import dev.planetonotion.integrations.NotionClient.ProjectDestination;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.models.documents.jobs.ConfigOperationJobDocument;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.JobRepository;
import dev.planetonotion.repositories.MongoQueryOptions;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);

    private final SyncConfigRepository configRepository;
    private final PlaneProjectRepository projectRepository;
    private final PlaneClient planeClient;
    private final NotionClient notionClient;
    private final NotionBlockClient notionBlockClient;
    private final NotionHierarchyService notionHierarchyService;
    private final ScheduleService scheduleService;
    private final JobRepository jobRepository;

    public ConfigService(SyncConfigRepository configRepository, PlaneProjectRepository projectRepository,
            PlaneClient planeClient, NotionClient notionClient, NotionBlockClient notionBlockClient,
            NotionHierarchyService notionHierarchyService, ScheduleService scheduleService,
            JobRepository jobRepository) {
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.planeClient = planeClient;
        this.notionClient = notionClient;
        this.notionBlockClient = notionBlockClient;
        this.notionHierarchyService = notionHierarchyService;
        this.scheduleService = scheduleService;
        this.jobRepository = jobRepository;
    }

    public List<SyncConfigDocument> list() {
        MongoQueryOptions options = new MongoQueryOptions(List.of(),
                List.of(MongoQueryOptions.Sort.descending(SyncConfigDocument.FIELD_CREATED_AT)));
        return configRepository.findAll(options);
    }

    public SyncConfigDocument get(ObjectId configId) {
        return findConfig(configId);
    }

    public SyncConfigDocument create(ConfigInput input) {
        validate(input, true);
        PlaneProjectDocument project = projectRepository.findById(input.planeProjectDocumentId())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.PROJECT_NOT_FOUND,
                        "The selected Plane project was not found."));
        if (!project.isAvailable() || project.isArchivedInPlane()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.PROJECT_NOT_SELECTABLE,
                    "Choose an available, non-archived Plane project.");
        }
        if (configRepository.existsByPlaneProjectDocumentId(project.id())) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_ALREADY_EXISTS,
                    ApiErrorMessages.Application.CONFIG_ALREADY_EXISTS);
        }
        if (hasAnyNotionDestination(project)) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.NOTION_DESTINATION_ALREADY_EXISTS,
                    "This Plane project already has Notion destination details but no sync config. Repair the destination before retrying.");
        }
        Instant now = Instant.now();
        ConfigStatus status = input.status() == null ? ConfigStatus.ACTIVE : input.status();
        ObjectId configId = new ObjectId();
        SyncConfigDocument draft = new SyncConfigDocument(configId, status, ConfigOperationStatus.PROVISIONING, null,
                project.id(), input.syncOptions(), normalizeSchedule(input.schedule()), now, now);
        SyncConfigDocument saved;
        try {
            saved = configRepository.save(draft);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_ALREADY_EXISTS,
                    ApiErrorMessages.Application.CONFIG_ALREADY_EXISTS);
        }
        try {
            jobRepository.save(newOperationJob(ConfigOperationType.CREATE, saved, null));
        } catch (RuntimeException exception) {
            configRepository.deleteById(configId);
            throw exception;
        }
        return saved;
    }

    public SyncConfigDocument update(ObjectId configId, ConfigInput input) {
        validate(input, false);
        SyncConfigDocument old = findConfig(configId);
        if (old.operationStatus() == ConfigOperationStatus.PROVISIONING
                || old.operationStatus() == ConfigOperationStatus.UPDATING) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_OPERATION_IN_PROGRESS,
                    "Wait for the current configuration operation to finish before editing it again.");
        }
        Instant now = Instant.now();
        SyncConfigDocument queued = new SyncConfigDocument(old.id(), old.status(), ConfigOperationStatus.UPDATING,
                null, old.planeProjectDocumentId(), old.syncOptions(), old.schedule(), old.createdAt(), now);
        try {
            configRepository.save(queued);
            jobRepository.holdPendingByConfigId(configId, now);
            jobRepository.save(newOperationJob(ConfigOperationType.UPDATE, queued, input));
        } catch (RuntimeException exception) {
            configRepository.save(old);
            jobRepository.releaseHeldByConfigId(configId, Instant.now());
            throw exception;
        }
        return queued;
    }

    public SyncConfigDocument processOperation(ConfigOperationJobDocument job) {
        SyncConfigDocument config = findConfig(job.configId());
        PlaneProjectDocument project = projectRepository.findById(config.planeProjectDocumentId())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.PROJECT_NOT_FOUND,
                        ApiErrorMessages.Application.PLANE_PROJECT_NOT_FOUND));
        project = ensureNotionDestination(project);

        SyncConfigDocument ready = config;
        if (job.operationType() == ConfigOperationType.UPDATE) {
            ready = new SyncConfigDocument(config.id(), job.targetStatus(), ConfigOperationStatus.READY, null,
                    config.planeProjectDocumentId(), job.targetSyncOptions(), normalizeSchedule(job.targetSchedule()),
                    config.createdAt(), Instant.now());
        } else {
            ready = new SyncConfigDocument(config.id(), config.status(), ConfigOperationStatus.READY, null,
                    config.planeProjectDocumentId(), config.syncOptions(), config.schedule(), config.createdAt(),
                    Instant.now());
        }
        try {
            scheduleService.reset(ready);
            jobRepository.releaseHeldByConfigId(config.id(), Instant.now());
            ready = configRepository.save(ready);
        } catch (RuntimeException exception) {
            if (job.operationType() == ConfigOperationType.UPDATE) {
                scheduleService.reset(config);
            } else {
                scheduleService.cancelPending(config.id());
            }
            throw exception;
        }
        try {
            notionHierarchyService.refreshSummaries();
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not refresh Notion hierarchy summary after config operation {}", job.id());
        }
        return ready;
    }

    public void failOperation(ConfigOperationJobDocument job, String errorCode, String errorMessage) {
        SyncConfigDocument config = configRepository.findById(job.configId()).orElse(null);
        if (config == null)
            return;
        SyncConfigDocument failed = new SyncConfigDocument(config.id(), config.status(), ConfigOperationStatus.FAILED,
                errorMessage, config.planeProjectDocumentId(), config.syncOptions(), config.schedule(),
                config.createdAt(), Instant.now());
        configRepository.save(failed);
        if (job.operationType() == ConfigOperationType.UPDATE) {
            scheduleService.reset(failed);
            jobRepository.releaseHeldByConfigId(config.id(), Instant.now());
        }
    }

    private ConfigOperationJobDocument newOperationJob(ConfigOperationType type, SyncConfigDocument config,
            ConfigInput target) {
        Instant now = Instant.now();
        return new ConfigOperationJobDocument(new ObjectId(), JobType.CONFIGURATION, JobStatus.PENDING, config.id(),
                type,
                target == null ? null : target.status(),
                target == null ? null : target.syncOptions(),
                target == null ? null : normalizeSchedule(target.schedule()),
                null, null, 0, null, null, now, now);
    }

    private PlaneProjectDocument ensureNotionDestination(PlaneProjectDocument project) {
        if (project.notionProjectPageId() != null && project.notionDatabaseId() != null
                && project.notionDataSourceId() != null) {
            return project;
        }
        if (hasAnyNotionDestination(project)) {
            throw new IntegrationException(ApiErrorCodes.Notion.DESTINATION_INCOMPLETE, 409,
                    "The project's Notion destination is incomplete. Repair it before retrying setup.");
        }
        String workspacePageId = notionHierarchyService.ensureWorkspacePage(project.planeWorkspaceSlug())
                .notionPageId();
        List<String> states = planeClient.listWorkItemStates(project.planeWorkspaceSlug(), project.planeProjectId())
                .stream().map(State::name).filter(name -> name != null && !name.isBlank()).distinct().toList();
        List<String> labels = planeClient.listWorkItemLabels(project.planeWorkspaceSlug(), project.planeProjectId())
                .stream().map(Label::name).filter(name -> name != null && !name.isBlank()).distinct().toList();
        ProjectDestination destination = notionClient.createProjectDestination(workspacePageId,
                project.planeProjectName(), states, labels);
        try {
            return projectRepository.save(withNotionDestination(project, destination, Instant.now()));
        } catch (RuntimeException exception) {
            try {
                notionBlockClient.archivePage(destination.pageId());
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private boolean hasAnyNotionDestination(PlaneProjectDocument project) {
        return project.notionProjectPageId() != null || project.notionDatabaseId() != null
                || project.notionDataSourceId() != null;
    }

    private PlaneProjectDocument withNotionDestination(PlaneProjectDocument project, ProjectDestination destination,
            Instant updatedAt) {
        return new PlaneProjectDocument(project.id(), project.planeWorkspaceSlug(), project.planeProjectId(),
                project.planeProjectName(),
                project.planeProjectIdentifier(), project.isArchivedInPlane(), project.isAvailable(),
                destination.pageId(),
                destination.databaseId(), destination.dataSourceId(), project.createdAt(), updatedAt);
    }

    private SyncConfigDocument findConfig(ObjectId configId) {
        return configRepository.findById(configId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.CONFIG_NOT_FOUND,
                        ApiErrorMessages.Application.CONFIG_NOT_FOUND));
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
                throw invalid(
                        "Calendar schedules need DAILY or WEEKLY frequency, a positive interval, and a UTC time.");
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
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.FEATURE_NOT_AVAILABLE,
                    "Page sync is not implemented in the first cut.");
        }
    }

    private List<java.time.DayOfWeek> normalizedDays(List<java.time.DayOfWeek> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }

    private SyncSchedule normalizeSchedule(SyncSchedule schedule) {
        SyncSchedule.Interval interval = schedule.interval() == null ? null
                : new SyncSchedule.Interval(
                        schedule.interval().value(), schedule.interval().unit());
        SyncSchedule.Calendar calendar = schedule.calendar() == null ? null
                : new SyncSchedule.Calendar(
                        schedule.calendar().frequency(), schedule.calendar().interval(),
                        normalizedDays(schedule.calendar().daysOfWeek()), schedule.calendar().timeOfDay());
        return new SyncSchedule(schedule.type(), schedule.startAt(), schedule.endAt(), interval, calendar);
    }

    private ServiceException invalid(String message) {
        return new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.INVALID_CONFIG, message);
    }

    public record ConfigInput(ObjectId planeProjectDocumentId, ConfigStatus status, SyncOptions syncOptions,
            SyncSchedule schedule) {
    }
}
