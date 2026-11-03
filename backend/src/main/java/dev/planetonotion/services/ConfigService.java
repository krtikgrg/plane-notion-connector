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

/**
 * Validates configuration requests and coordinates their queued provisioning,
 * updates, and schedule changes.
 */
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

    /** Creates the service with repositories and provider clients. */
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

    /**
     * Lists configurations from newest to oldest.
     *
     * @return stored configurations ordered by creation time
     */
    public List<SyncConfigDocument> list() {
        MongoQueryOptions options = new MongoQueryOptions(List.of(),
                List.of(MongoQueryOptions.Sort.descending(SyncConfigDocument.FIELD_CREATED_AT)));
        return configRepository.findAll(options);
    }

    /**
     * Finds one configuration by its database ID.
     *
     * @param configId configuration MongoDB ID
     * @return the matching configuration
     */
    public SyncConfigDocument get(ObjectId configId) {
        return findConfig(configId);
    }

    /**
     * Validates and stores a provisioning config, then queues its Notion setup.
     * The returned config remains in {@code PROVISIONING} until the job finishes.
     *
     * @param input requested project, sync options, status, and schedule
     * @return the saved config awaiting provisioning
     */
    public SyncConfigDocument create(ConfigInput input) {
        validate(input, true);
        // Validate and resolve the Plane project.
        PlaneProjectDocument project = projectRepository.findById(input.planeProjectDocumentId())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.PROJECT_NOT_FOUND,
                        "The selected Plane project was not found."));
        if (!project.isAvailable() || project.isArchivedInPlane()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.PROJECT_NOT_SELECTABLE,
                    "Choose an available, non-archived Plane project.");
        }
        // Reject projects already configured or provisioned.
        if (configRepository.existsByPlaneProjectDocumentId(project.id())) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_ALREADY_EXISTS,
                    ApiErrorMessages.Application.CONFIG_ALREADY_EXISTS);
        }
        if (hasAnyNotionDestination(project)) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.NOTION_DESTINATION_ALREADY_EXISTS,
                    "This Plane project already has Notion destination details but no sync config. Repair the destination before retrying.");
        }
        // Save provisioning state and enqueue setup.
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

    /**
     * Queues an update while holding pending sync jobs for the config.
     * The returned config remains {@code UPDATING} until processing completes.
     *
     * @param configId configuration MongoDB ID
     * @param input    requested status, sync options, and schedule
     * @return the queued config state
     */
    public SyncConfigDocument update(ObjectId configId, ConfigInput input) {
        validate(input, false);
        SyncConfigDocument old = findConfig(configId);
        // Reject changes during another operation.
        if (old.operationStatus() == ConfigOperationStatus.PROVISIONING
                || old.operationStatus() == ConfigOperationStatus.UPDATING) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_OPERATION_IN_PROGRESS,
                    "Wait for the current configuration operation to finish before editing it again.");
        }
        // Persist queued state and hold syncs.
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

    /**
     * Provisions the Notion destination, applies the queued config values, and
     * resets its schedule. Called by the worker for a claimed config job.
     *
     * @param job claimed create or update operation
     * @return the ready config after persistence
     */
    public SyncConfigDocument processOperation(ConfigOperationJobDocument job) {
        // Load config and resolve its project.
        SyncConfigDocument config = findConfig(job.configId());
        PlaneProjectDocument project = projectRepository.findById(config.planeProjectDocumentId())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.PROJECT_NOT_FOUND,
                        ApiErrorMessages.Application.PLANE_PROJECT_NOT_FOUND));
        project = ensureNotionDestination(project);

        // Build the final configuration state.
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
        // Reset scheduling and persist ready state.
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
        // Refresh Notion summaries as best effort.
        try {
            notionHierarchyService.refreshSummaries();
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not refresh Notion hierarchy summary after config operation {}", job.id());
        }
        return ready;
    }

    /**
     * Marks a failed config operation and restores held schedules for updates.
     *
     * @param job          failed create or update operation
     * @param errorCode    stable error code recorded for the config
     * @param errorMessage readable failure detail
     */
    public void failOperation(ConfigOperationJobDocument job, String errorCode, String errorMessage) {
        // Find the config that needs recovery.
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

    /** Builds a pending job containing the requested operation values. */
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

    /** Creates and stores a complete Notion destination when missing. */
    private PlaneProjectDocument ensureNotionDestination(PlaneProjectDocument project) {
        // Reuse destinations with complete identifiers.
        if (project.notionProjectPageId() != null && project.notionDatabaseId() != null
                && project.notionDataSourceId() != null) {
            return project;
        }
        // Reject incomplete destination metadata for repair.
        if (hasAnyNotionDestination(project)) {
            throw new IntegrationException(ApiErrorCodes.Notion.DESTINATION_INCOMPLETE, 409,
                    "The project's Notion destination is incomplete. Repair it before retrying setup.");
        }
        // Fetch Plane options and create destination.
        String workspacePageId = notionHierarchyService.ensureWorkspacePage(project.planeWorkspaceSlug())
                .notionPageId();
        List<String> states = planeClient.listWorkItemStates(project.planeWorkspaceSlug(), project.planeProjectId())
                .stream().map(State::name).filter(name -> name != null && !name.isBlank()).distinct().toList();
        List<String> labels = planeClient.listWorkItemLabels(project.planeWorkspaceSlug(), project.planeProjectId())
                .stream().map(Label::name).filter(name -> name != null && !name.isBlank()).distinct().toList();
        ProjectDestination destination = notionClient.createProjectDestination(workspacePageId,
                project.planeProjectName(), states, labels);
        // Persist IDs; archive page on failure.
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

    /** Returns whether any Notion destination identifier is present. */
    private boolean hasAnyNotionDestination(PlaneProjectDocument project) {
        return project.notionProjectPageId() != null || project.notionDatabaseId() != null
                || project.notionDataSourceId() != null;
    }

    /** Copies a project with the newly provisioned Notion IDs. */
    private PlaneProjectDocument withNotionDestination(PlaneProjectDocument project, ProjectDestination destination,
            Instant updatedAt) {
        return new PlaneProjectDocument(project.id(), project.planeWorkspaceSlug(), project.planeProjectId(),
                project.planeProjectName(),
                project.planeProjectIdentifier(), project.isArchivedInPlane(), project.isAvailable(),
                destination.pageId(),
                destination.databaseId(), destination.dataSourceId(), project.createdAt(), updatedAt);
    }

    /** Finds a config or raises the standard not-found service error. */
    private SyncConfigDocument findConfig(ObjectId configId) {
        return configRepository.findById(configId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.CONFIG_NOT_FOUND,
                        ApiErrorMessages.Application.CONFIG_NOT_FOUND));
    }

    /** Validates required fields, sync options, schedule rules, and time window. */
    private void validate(ConfigInput input, boolean creating) {
        // Check required fields and sync options.
        if (input == null || input.schedule() == null || input.schedule().type() == null || input.syncOptions() == null
                || (creating && input.planeProjectDocumentId() == null)) {
            throw invalid("A Plane project, sync options, and schedule type are required.");
        }
        validateOptions(input.syncOptions());
        if (!creating && input.status() == null) {
            throw invalid("A config status is required.");
        }
        // Validate interval or calendar schedule fields.
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
        // Ensure the schedule window is valid.
        if (schedule.startAt() != null && schedule.endAt() != null && !schedule.endAt().isAfter(schedule.startAt())) {
            throw invalid("end_at must be after start_at.");
        }
    }

    /** Rejects unsupported or disabled synchronization options. */
    private void validateOptions(SyncOptions options) {
        if (!options.workItems()) {
            throw invalid("Enable work_items for this prototype.");
        }
        if (options.pages()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.FEATURE_NOT_AVAILABLE,
                    "Page sync is not implemented in the first cut.");
        }
    }

    /** Removes duplicate weekdays and normalizes null to empty. */
    private List<java.time.DayOfWeek> normalizedDays(List<java.time.DayOfWeek> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }

    /** Copies the schedule while normalizing its calendar weekdays. */
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

    /** Creates the standard bad-request exception for invalid config input. */
    private ServiceException invalid(String message) {
        return new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.INVALID_CONFIG, message);
    }

    /**
     * Input values used when creating or updating a sync config.
     *
     * @param planeProjectDocumentId project ID, required only when creating
     * @param status                 desired config status; required when updating
     * @param syncOptions            selected data types to synchronize
     * @param schedule               desired synchronization schedule
     */
    public record ConfigInput(ObjectId planeProjectDocumentId, ConfigStatus status, SyncOptions syncOptions,
            SyncSchedule schedule) {
    }
}
