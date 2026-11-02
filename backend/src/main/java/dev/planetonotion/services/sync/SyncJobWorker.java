package dev.planetonotion.services.sync;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.jobs.ConfigOperationJobDocument;
import dev.planetonotion.models.documents.jobs.JobDocument;
import dev.planetonotion.models.documents.jobs.ManualSyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobResult;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.JobRepository;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.services.AdHocNotionDestinationService;
import dev.planetonotion.services.ConfigService;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.services.ProjectCatalogService;
import dev.planetonotion.services.ScheduleService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncJobWorker.class);
    private static final Duration CHECKPOINT_OVERLAP = Duration.ofMillis(100);

    private final JobRepository jobRepository;
    private final SyncConfigRepository configRepository;
    private final PlaneProjectRepository projectRepository;
    private final WorkItemSyncService workItemSyncService;
    private final ProjectCatalogService projectCatalogService;
    private final NotionHierarchyService notionHierarchyService;
    private final ScheduleService scheduleService;
    private final AdHocNotionDestinationService adHocDestinationService;
    private final ConfigService configService;

    public SyncJobWorker(JobRepository jobRepository, SyncConfigRepository configRepository,
            PlaneProjectRepository projectRepository, WorkItemSyncService workItemSyncService,
            ProjectCatalogService projectCatalogService, NotionHierarchyService notionHierarchyService,
            ScheduleService scheduleService, AdHocNotionDestinationService adHocDestinationService,
            ConfigService configService) {
        this.jobRepository = jobRepository;
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.workItemSyncService = workItemSyncService;
        this.projectCatalogService = projectCatalogService;
        this.notionHierarchyService = notionHierarchyService;
        this.scheduleService = scheduleService;
        this.adHocDestinationService = adHocDestinationService;
        this.configService = configService;
    }

    @Scheduled(fixedDelayString = "${sync.worker.poll-interval-ms:5000}")
    public void claimAndRunOne() {
        jobRepository.claimNextDueJob(Instant.now()).ifPresent(this::dispatch);
    }

    private void dispatch(JobDocument job) {
        if (job instanceof ConfigOperationJobDocument operation) {
            runConfigurationOperation(operation);
            return;
        }
        if (job instanceof SyncJobDocument syncJob) {
            run(syncJob);
            return;
        }
        throw new IllegalStateException("Unknown queued job type: " + job.getClass().getName());
    }

    private void runConfigurationOperation(ConfigOperationJobDocument job) {
        try {
            configService.processOperation(job);
            jobRepository.save(copyConfigurationJob(job, JobStatus.SUCCESS, Instant.now(), null, null));
        } catch (Exception exception) {
            ErrorDetails error = configurationErrorDetails(exception);
            try {
                configService.failOperation(job, error.code(), error.message());
            } catch (RuntimeException recoveryFailure) {
                exception.addSuppressed(recoveryFailure);
                LOGGER.warn("Could not restore config state after operation {} failed", job.id());
            }
            jobRepository.save(copyConfigurationJob(job, JobStatus.FAILED, Instant.now(),
                    error.code(), error.message()));
            LOGGER.warn("Configuration job {} failed with {} ({})", job.id(), error.code(),
                    exception.getClass().getSimpleName());
        }
    }

    private ConfigOperationJobDocument copyConfigurationJob(ConfigOperationJobDocument job,
            JobStatus status, Instant finishedAt, String errorCode, String errorMessage) {
        return new ConfigOperationJobDocument(job.id(), job.jobType(), status, job.configId(), job.operationType(),
                job.targetStatus(), job.targetSyncOptions(), job.targetSchedule(), job.startedAt(), finishedAt,
                job.attemptCount(), errorCode, errorMessage, job.createdAt(), finishedAt);
    }

    private void run(SyncJobDocument job) {
        AtomicReference<WorkItemSyncService.SyncResult> progress = new AtomicReference<>(
                new WorkItemSyncService.SyncResult(0, 0, 0, 0));
        try {
            WorkItemSyncService.SyncResult result = execute(job, progress);
            Instant finishedAt = Instant.now();
            jobRepository.save(copy(job, JobStatus.SUCCESS, finishedAt, toJobResult(result), null, null));
        } catch (Exception exception) {
            Instant finishedAt = Instant.now();
            ErrorDetails error = errorDetails(exception);
            WorkItemSyncService.SyncResult partial = progress.get();
            jobRepository.save(copy(job, JobStatus.FAILED, finishedAt, toJobResult(partial),
                    error.code(), error.message()));
            LOGGER.warn("Sync job {} failed with {} ({})", job.id(), error.code(),
                    exception.getClass().getSimpleName());
        }
        scheduleFollowing(job);
        if (job.configId() != null)
            notionHierarchyService.refreshSummaries();
    }

    private WorkItemSyncService.SyncResult execute(SyncJobDocument job,
            AtomicReference<WorkItemSyncService.SyncResult> progress) {
        if (job.planeProjectDocumentId() == null) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.SYNC_DESTINATION_MISSING,
                    ApiErrorMessages.Application.SYNC_DESTINATION_REQUIRED);
        }
        PlaneProjectDocument project = projectRepository.findById(job.planeProjectDocumentId())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.PROJECT_NOT_FOUND,
                        ApiErrorMessages.Application.PLANE_PROJECT_NOT_FOUND));
        if (job.configId() == null) {
            if (job.scope() != SyncScope.WORK_ITEM) {
                throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.SYNC_DESTINATION_MISSING,
                        ApiErrorMessages.Application.SYNC_DESTINATION_REQUIRED);
            }
            var destination = adHocDestinationService.getOrCreate();
            return workItemSyncService.syncOneAdHoc(project, workItemId(job), destination.notionDataSourceId(),
                    progress::set);
        }
        SyncConfigDocument config = configRepository.findById(job.configId())
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.CONFIG_NOT_FOUND,
                        ApiErrorMessages.Application.CONFIG_NOT_FOUND));
        boolean forceArchivedConfig = job instanceof ManualSyncJobDocument manual && manual.forceArchivedConfig();
        if (job.jobType() == JobType.MANUAL && config.status() == ConfigStatus.ARCHIVED
                && !forceArchivedConfig) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_ARCHIVED,
                    ApiErrorMessages.Application.CONFIG_ARCHIVED_FORCE_RUN_REQUIRED);
        }
        project = projectCatalogService.refreshProject(project.id());
        notionHierarchyService.synchronizeProjectPage(project);
        if (job.scope() == SyncScope.PROJECT) {
            Instant upperCutoff = job.startedAt() == null ? Instant.now() : job.startedAt();
            Instant checkpoint = jobRepository.findLatestSuccessfulProjectSyncStart(config.id())
                    .map(startedAt -> startedAt.minus(CHECKPOINT_OVERLAP)).orElse(null);
            return workItemSyncService.syncProject(project, checkpoint, upperCutoff, progress::set);
        }
        return workItemSyncService.syncOne(project, workItemId(job), progress::set);
    }

    private void scheduleFollowing(SyncJobDocument finishedJob) {
        if (finishedJob.configId() == null)
            return;
        try {
            configRepository.findById(finishedJob.configId())
                    .filter(config -> config.operationStatus() == ConfigOperationStatus.READY)
                    .ifPresent(config -> scheduleService.afterRun(finishedJob, config));
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not schedule the next run for config {}", finishedJob.configId());
        }
    }

    private SyncJobDocument copy(SyncJobDocument old, JobStatus status, Instant finishedAt,
            SyncJobResult result, String errorCode, String errorMessage) {
        if (old instanceof ManualSyncJobDocument manual) {
            return new ManualSyncJobDocument(old.id(), old.jobType(), old.scope(), status, old.configId(),
                    old.planeProjectDocumentId(), manual.planeWorkItem(), manual.forceArchivedConfig(),
                    old.startedAt(), finishedAt, old.attemptCount(), result, errorCode, errorMessage,
                    old.createdAt(), finishedAt);
        }
        if (old instanceof ScheduledSyncJobDocument scheduled) {
            return new ScheduledSyncJobDocument(old.id(), old.jobType(), old.scope(), status, old.configId(),
                    old.planeProjectDocumentId(), scheduled.scheduledFor(), old.startedAt(), finishedAt,
                    old.attemptCount(), result, errorCode, errorMessage, old.createdAt(), finishedAt);
        }
        throw new IllegalArgumentException("Unknown sync job subtype: " + old.getClass().getName());
    }

    private String workItemId(SyncJobDocument job) {
        if (job instanceof ManualSyncJobDocument manual && manual.planeWorkItem() != null) {
            return manual.planeWorkItem().workItemId();
        }
        throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.WORK_ITEM_REFERENCE_MISSING,
                "A manual work-item job must include its Plane work-item reference.");
    }

    private SyncJobResult toJobResult(WorkItemSyncService.SyncResult result) {
        return new SyncJobResult(result.eligibleCount(), result.createdCount(), result.updatedCount(),
                result.unchangedCount());
    }

    private ErrorDetails errorDetails(Exception exception) {
        if (exception instanceof IntegrationException integration) {
            return new ErrorDetails(integration.errorCode(), integration.getMessage());
        }
        if (exception instanceof ServiceException service) {
            return new ErrorDetails(service.errorCode(), service.getMessage());
        }
        return new ErrorDetails(ApiErrorCodes.Application.SYNC_FAILED,
                "The sync failed. Check the local service logs for details.");
    }

    private ErrorDetails configurationErrorDetails(Exception exception) {
        if (exception instanceof IntegrationException integration) {
            return new ErrorDetails(integration.errorCode(), integration.getMessage());
        }
        if (exception instanceof ServiceException service) {
            return new ErrorDetails(service.errorCode(), service.getMessage());
        }
        return new ErrorDetails(ApiErrorCodes.Application.CONFIG_OPERATION_FAILED,
                "Configuration setup failed. Check the local service logs for details.");
    }

    private record ErrorDetails(String code, String message) {
    }
}
