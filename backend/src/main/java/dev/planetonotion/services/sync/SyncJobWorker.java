package dev.planetonotion.services.sync;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import dev.planetonotion.services.AdHocNotionDestinationService;
import dev.planetonotion.services.NotionHierarchyService;
import dev.planetonotion.services.ProjectCatalogService;
import dev.planetonotion.services.ScheduleService;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncJobWorker.class);
    private static final Duration CHECKPOINT_OVERLAP = Duration.ofMillis(100);

    private final SyncJobRepository jobRepository;
    private final SyncConfigRepository configRepository;
    private final PlaneProjectRepository projectRepository;
    private final WorkItemSyncService workItemSyncService;
    private final ProjectCatalogService projectCatalogService;
    private final NotionHierarchyService notionHierarchyService;
    private final ScheduleService scheduleService;
    private final AdHocNotionDestinationService adHocDestinationService;

    public SyncJobWorker(SyncJobRepository jobRepository, SyncConfigRepository configRepository,
            PlaneProjectRepository projectRepository, WorkItemSyncService workItemSyncService,
            ProjectCatalogService projectCatalogService, NotionHierarchyService notionHierarchyService,
            ScheduleService scheduleService, AdHocNotionDestinationService adHocDestinationService) {
        this.jobRepository = jobRepository;
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.workItemSyncService = workItemSyncService;
        this.projectCatalogService = projectCatalogService;
        this.notionHierarchyService = notionHierarchyService;
        this.scheduleService = scheduleService;
        this.adHocDestinationService = adHocDestinationService;
    }

    @Scheduled(fixedDelayString = "${sync.worker.poll-interval-ms:5000}")
    public void claimAndRunOne() {
        jobRepository.claimNextDueJob(Instant.now()).ifPresent(this::run);
    }

    private void run(SyncJobDocument job) {
        AtomicReference<WorkItemSyncService.SyncResult> progress = new AtomicReference<>(
                new WorkItemSyncService.SyncResult(0, 0, 0, 0));
        try {
            WorkItemSyncService.SyncResult result = execute(job, progress);
            Instant finishedAt = Instant.now();
            jobRepository.save(copy(job, SyncJobStatus.SUCCESS, finishedAt, result.eligibleCount(), result.eligibleCount(),
                    result.createdCount(), result.updatedCount(), result.unchangedCount(), 0, null, null));
        } catch (Exception exception) {
            Instant finishedAt = Instant.now();
            ErrorDetails error = errorDetails(exception);
            WorkItemSyncService.SyncResult partial = progress.get();
            jobRepository.save(copy(job, SyncJobStatus.FAILED, finishedAt, partial.eligibleCount(),
                    partial.eligibleCount(), partial.createdCount(), partial.updatedCount(),
                    partial.unchangedCount(), 1, error.code(), error.message()));
            LOGGER.warn("Sync job {} failed with {} ({})", job.id(), error.code(),
                    exception.getClass().getSimpleName());
        }
        scheduleFollowing(job);
        if (job.configId() != null) notionHierarchyService.refreshSummaries();
    }

    private WorkItemSyncService.SyncResult execute(SyncJobDocument job,
            AtomicReference<WorkItemSyncService.SyncResult> progress) {
        if (job.planeProjectRef() == null) {
            throw new ServiceException(org.springframework.http.HttpStatus.CONFLICT, "SYNC_DESTINATION_MISSING", "A sync destination is required.");
        }
        PlaneProjectDocument project = projectRepository.findById(job.planeProjectRef())
                .orElseThrow(() -> new ServiceException(org.springframework.http.HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The Plane project was not found."));
        if (job.configId() == null) {
            if (job.syncScope() != dev.planetonotion.common.PlaneToNotionEnums.SyncScope.WORK_ITEM) {
                throw new ServiceException(org.springframework.http.HttpStatus.CONFLICT, "SYNC_DESTINATION_MISSING", "A sync destination is required.");
            }
            var destination = adHocDestinationService.getOrCreate();
            return workItemSyncService.syncOneAdHoc(project, job.workItemId(), destination.notionDataSourceId(), progress::set);
        }
        SyncConfigDocument config = configRepository.findById(job.configId())
                .orElseThrow(() -> new ServiceException(org.springframework.http.HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", "The sync config was not found."));
        if (job.triggerType() == SyncJobType.MANUAL && config.status() == ConfigStatus.ARCHIVED
                && !job.forceArchivedConfig()) {
            throw new ServiceException(org.springframework.http.HttpStatus.CONFLICT, "CONFIG_ARCHIVED", "Enable force run on archived config to continue.");
        }
        project = projectCatalogService.refreshProject(project.id());
        notionHierarchyService.synchronizeProjectPage(project, config);
        if (job.syncScope() == dev.planetonotion.common.PlaneToNotionEnums.SyncScope.PROJECT) {
            Instant upperCutoff = job.startedAt() == null ? Instant.now() : job.startedAt();
            Instant checkpoint = jobRepository.findLatestSuccessfulProjectSyncStart(config.id())
                    .map(startedAt -> startedAt.minus(CHECKPOINT_OVERLAP)).orElse(null);
            return workItemSyncService.syncProject(project, config, checkpoint, upperCutoff, progress::set);
        }
        return workItemSyncService.syncOne(config, project, job.workItemId(), progress::set);
    }

    private void scheduleFollowing(SyncJobDocument finishedJob) {
        if (finishedJob.configId() == null) return;
        try {
            configRepository.findById(finishedJob.configId())
                    .ifPresent(config -> scheduleService.afterRun(finishedJob, config));
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not schedule the next run for config {}", finishedJob.configId());
        }
    }

    private SyncJobDocument copy(SyncJobDocument old, SyncJobStatus status, Instant finishedAt, int fetched,
            int eligible, int created, int updated, int unchanged, int failed, String errorCode, String errorMessage) {
        return new SyncJobDocument(old.id(), old.triggerType(), old.syncScope(), status, old.configId(),
                old.planeProjectRef(), old.requestedTaskUrl(), old.workItemId(), old.forceArchivedConfig(),
                old.scheduledFor(), old.startedAt(), finishedAt, old.attemptCount(), fetched, eligible, created, updated,
                unchanged, failed, errorCode, errorMessage, old.createdAt(), finishedAt);
    }

    private ErrorDetails errorDetails(Exception exception) {
        if (exception instanceof IntegrationException integration) {
            return new ErrorDetails(integration.errorCode(), integration.getMessage());
        }
        if (exception instanceof ServiceException service) {
            return new ErrorDetails(service.errorCode(), service.getMessage());
        }
        return new ErrorDetails("SYNC_FAILED", "The sync failed. Check the local service logs for details.");
    }

    private record ErrorDetails(String code, String message) {
    }
}
