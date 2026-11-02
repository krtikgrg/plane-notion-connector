package dev.planetonotion.controllers;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.MongoIdParser;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.jobs.ManualSyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.ApiDtos.EnqueuedJobResponse;
import dev.planetonotion.models.ApiDtos.JobResponse;
import dev.planetonotion.models.ApiDtos.JobHistoryResponse;
import dev.planetonotion.models.ApiDtos.ManualTaskSyncRequest;
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

@RestController
@RequestMapping("/api/sync-jobs")
public class SyncJobController {

    private final SyncJobService syncJobService;

    public SyncJobController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    @GetMapping
    public JobHistoryResponse list(@RequestParam(required = false, name = "config_id") String configId,
            @RequestParam(required = false, name = "job_status") SyncJobStatus jobStatus,
            @RequestParam(required = false, name = "job_type") SyncJobType jobType,
            @RequestParam(required = false, name = "created_from") Instant createdFrom,
            @RequestParam(required = false, name = "created_to") Instant createdTo,
            @RequestParam(required = false, name = "before_id") String beforeId,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize) {
        validatePageSize(pageSize);
        var page = syncJobService.list(MongoIdParser.optional(configId, "config_id"), jobStatus, jobType,
                createdFrom, createdTo, MongoIdParser.optional(beforeId, "before_id"), pageSize);
        return new JobHistoryResponse(page.items().stream().map(this::toResponse).toList(), page.nextBeforeId());
    }

    @GetMapping("/next-scheduled")
    public Map<String, Instant> nextScheduledRuns() {
        return syncJobService.nextScheduledRuns();
    }

    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EnqueuedJobResponse enqueueTask(@Valid @RequestBody ManualTaskSyncRequest request) {
        ObjectId configId = MongoIdParser.optional(request.configId(), "config_id");
        return toEnqueuedResponse(syncJobService.enqueueTask(request.taskUrl(), configId, request.forceArchivedConfig()));
    }

    @GetMapping("/{job_id}")
    public JobResponse get(@PathVariable("job_id") String jobId) {
        return toResponse(syncJobService.get(MongoIdParser.required(jobId, "job_id")));
    }

    private EnqueuedJobResponse toEnqueuedResponse(SyncJobDocument job) {
        return new EnqueuedJobResponse(job.id().toHexString(), job.jobStatus(), job.createdAt());
    }

    private JobResponse toResponse(SyncJobDocument job) {
        var planeWorkItem = job instanceof ManualSyncJobDocument manual && manual.planeWorkItem() != null
                ? new dev.planetonotion.models.ApiDtos.PlaneWorkItemResponse(
                        manual.planeWorkItem().taskUrl(), manual.planeWorkItem().workItemId())
                : null;
        var scheduledFor = job instanceof ScheduledSyncJobDocument scheduled ? scheduled.scheduledFor() : null;
        return new JobResponse(job.id().toHexString(), job.jobType(), job.scope(), job.jobStatus(),
                job.configId() == null ? null : job.configId().toHexString(),
                job.planeProjectDocumentId() == null ? null : job.planeProjectDocumentId().toHexString(),
                planeWorkItem, scheduledFor, job.startedAt(), job.finishedAt(), job.attemptCount(), job.result(),
                job.errorCode(), job.errorMessage(), job.createdAt());
    }

    private void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE", "page_size must be between 1 and 100.");
        }
    }
}
