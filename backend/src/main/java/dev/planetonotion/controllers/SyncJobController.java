package dev.planetonotion.controllers;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.MongoIdParser;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.models.ApiDtos.EnqueuedJobResponse;
import dev.planetonotion.models.ApiDtos.JobHistoryResponse;
import dev.planetonotion.models.ApiDtos.JobResponse;
import dev.planetonotion.models.ApiDtos.ManualTaskSyncRequest;
import dev.planetonotion.models.ApiDtos.PlaneWorkItemResponse;
import dev.planetonotion.models.documents.jobs.ManualSyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.services.sync.SyncJobService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for manual sync jobs and job history. */
@RestController
@RequestMapping("/api/sync-jobs")
public class SyncJobController {

    private final SyncJobService syncJobService;

    /** Creates the controller with the sync job service. */
    public SyncJobController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    /**
     * Lists paginated sync history with optional filters.
     *
     * @param configId    optional config MongoDB ID
     * @param jobStatus   optional status filter
     * @param jobType     optional job-type filter
     * @param createdFrom optional inclusive creation-time lower bound
     * @param createdTo   optional exclusive creation-time upper bound
     * @param beforeId    optional page cursor
     * @param pageSize    maximum number of history rows
     * @return matching jobs and the next page cursor
     */
    @GetMapping
    public JobHistoryResponse list(@RequestParam(required = false, name = "config_id") String configId,
            @RequestParam(required = false, name = "job_status") JobStatus jobStatus,
            @RequestParam(required = false, name = "job_type") JobType jobType,
            @RequestParam(required = false, name = "created_from") Instant createdFrom,
            @RequestParam(required = false, name = "created_to") Instant createdTo,
            @RequestParam(required = false, name = "before_id") String beforeId,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize) {
        validatePageSize(pageSize);
        var page = syncJobService.list(MongoIdParser.optional(configId, "config_id"), jobStatus, jobType,
                createdFrom, createdTo, MongoIdParser.optional(beforeId, "before_id"), pageSize);
        return new JobHistoryResponse(page.items().stream().map(this::toResponse).toList(), page.nextBeforeId());
    }

    /**
     * Returns each config's next pending scheduled run.
     *
     * @return config IDs mapped to next scheduled times
     */
    @GetMapping("/next-scheduled")
    public Map<String, Instant> nextScheduledRuns() {
        return syncJobService.nextScheduledRuns();
    }

    /**
     * Validates and queues a manual task-URL sync.
     *
     * @param request task URL, optional config, and archived-config override
     * @return accepted job ID and status
     */
    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EnqueuedJobResponse enqueueTask(@Valid @RequestBody ManualTaskSyncRequest request) {
        ObjectId configId = MongoIdParser.optional(request.configId(), "config_id");
        return toEnqueuedResponse(
                syncJobService.enqueueTask(request.taskUrl(), configId, request.forceArchivedConfig()));
    }

    /**
     * Returns one sync job by database ID.
     *
     * @param jobId sync job MongoDB ID
     * @return matching job response
     */
    @GetMapping("/{job_id}")
    public JobResponse get(@PathVariable("job_id") String jobId) {
        return toResponse(syncJobService.get(MongoIdParser.required(jobId, "job_id")));
    }

    /** Maps a queued job to its accepted-job response. */
    private EnqueuedJobResponse toEnqueuedResponse(SyncJobDocument job) {
        return new EnqueuedJobResponse(job.id().toHexString(), job.jobStatus(), job.createdAt());
    }

    /** Maps a job subtype to its API response. */
    private JobResponse toResponse(SyncJobDocument job) {
        var planeWorkItem = job instanceof ManualSyncJobDocument manual && manual.planeWorkItem() != null
                ? new PlaneWorkItemResponse(
                        manual.planeWorkItem().taskUrl(), manual.planeWorkItem().workItemId())
                : null;
        var scheduledFor = job instanceof ScheduledSyncJobDocument scheduled ? scheduled.scheduledFor() : null;
        return new JobResponse(job.id().toHexString(), job.jobType(), job.scope(), job.jobStatus(),
                job.configId() == null ? null : job.configId().toHexString(),
                job.planeProjectDocumentId() == null ? null : job.planeProjectDocumentId().toHexString(),
                planeWorkItem, scheduledFor, job.startedAt(), job.finishedAt(), job.attemptCount(), job.result(),
                job.errorCode(), job.errorMessage(), job.createdAt());
    }

    /** Rejects history page sizes outside the supported range. */
    private void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.INVALID_PAGE_SIZE,
                    ApiErrorMessages.Application.INVALID_PAGE_SIZE);
        }
    }
}
