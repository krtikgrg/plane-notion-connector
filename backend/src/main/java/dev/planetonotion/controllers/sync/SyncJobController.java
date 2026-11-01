package dev.planetonotion.controllers.sync;

import dev.planetonotion.common.enums.SyncJobStatus;
import dev.planetonotion.common.enums.SyncJobType;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.utils.MongoIdParser;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.models.dto.ApiDtos.EnqueuedJobResponse;
import dev.planetonotion.models.dto.ApiDtos.JobResponse;
import dev.planetonotion.models.dto.ApiDtos.JobHistoryResponse;
import dev.planetonotion.models.dto.ApiDtos.ManualTaskSyncRequest;
import dev.planetonotion.services.sync.SyncJobService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
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
            @RequestParam(required = false) SyncJobStatus status,
            @RequestParam(required = false, name = "trigger_type") SyncJobType triggerType,
            @RequestParam(required = false, name = "created_from") Instant createdFrom,
            @RequestParam(required = false, name = "created_to") Instant createdTo,
            @RequestParam(required = false, name = "before_id") String beforeId,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize) {
        validatePageSize(pageSize);
        var page = syncJobService.list(MongoIdParser.optional(configId, "config_id"), status, triggerType,
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
        return new EnqueuedJobResponse(job.id().toHexString(), job.status(), job.createdAt());
    }

    private JobResponse toResponse(SyncJobDocument job) {
        return new JobResponse(job.id().toHexString(), job.triggerType(), job.syncScope(), job.status(),
                job.configId() == null ? null : job.configId().toHexString(),
                job.planeProjectRef() == null ? null : job.planeProjectRef().toHexString(),
                job.requestedTaskUrl(), job.workItemId(), job.scheduledFor(), job.startedAt(), job.finishedAt(),
                job.attemptCount(), job.fetchedCount(), job.eligibleCount(), job.createdCount(), job.updatedCount(),
                job.unchangedCount(), job.failedCount(), job.errorCode(), job.errorMessage(), job.createdAt());
    }

    private void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE", "page_size must be between 1 and 100.");
        }
    }
}
