package dev.planetonotion.controllers;

import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.MongoIdParser;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.models.ApiDtos.ConfigResponse;
import dev.planetonotion.models.ApiDtos.EnqueuedJobResponse;
import dev.planetonotion.models.ApiDtos.JobHistoryResponse;
import dev.planetonotion.models.ApiDtos.JobResponse;
import dev.planetonotion.models.ApiDtos.ManualProjectSyncRequest;
import dev.planetonotion.models.ApiDtos.PlaneWorkItemResponse;
import dev.planetonotion.models.ApiDtos.ProjectResponse;
import dev.planetonotion.models.ApiDtos.UpsertConfigRequest;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.jobs.ManualSyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.services.ConfigService;
import dev.planetonotion.integrations.PlaneClient;
import dev.planetonotion.services.ProjectCatalogService;
import dev.planetonotion.services.sync.SyncJobService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/configs")
public class ConfigController {

    private final ConfigService configService;
    private final ProjectCatalogService projectCatalogService;
    private final PlaneClient planeClient;
    private final SyncJobService syncJobService;

    public ConfigController(ConfigService configService, ProjectCatalogService projectCatalogService,
            PlaneClient planeClient, SyncJobService syncJobService) {
        this.configService = configService;
        this.projectCatalogService = projectCatalogService;
        this.planeClient = planeClient;
        this.syncJobService = syncJobService;
    }

    @GetMapping
    public List<ConfigResponse> list() {
        List<SyncConfigDocument> configs = configService.list();
        Map<ObjectId, PlaneProjectDocument> projectsById = projectCatalogService.findAllById(
                configs.stream().map(SyncConfigDocument::planeProjectDocumentId).distinct().toList())
                .stream().collect(Collectors.toMap(PlaneProjectDocument::id, Function.identity()));
        return configs.stream().map(config -> toResponse(config, projectsById.get(config.planeProjectDocumentId())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConfigResponse create(@Valid @RequestBody UpsertConfigRequest request) {
        return toResponse(configService.create(new ConfigService.ConfigInput(
                MongoIdParser.required(request.planeProjectDocumentId(), "plane_project_document_id"), request.status(),
                request.syncOptions(), request.schedule())));
    }

    @GetMapping("/{config_id}")
    public ConfigResponse get(@PathVariable("config_id") String configId) {
        return toResponse(configService.get(MongoIdParser.required(configId, "config_id")));
    }

    @PatchMapping("/{config_id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConfigResponse update(@PathVariable("config_id") String configId,
            @Valid @RequestBody UpsertConfigRequest request) {
        return toResponse(configService.update(MongoIdParser.required(configId, "config_id"),
                new ConfigService.ConfigInput(null, request.status(), request.syncOptions(), request.schedule())));
    }

    @PostMapping("/{config_id}/sync-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EnqueuedJobResponse enqueueProjectSync(@PathVariable("config_id") String configId,
            @RequestBody ManualProjectSyncRequest request) {
        return toEnqueuedResponse(syncJobService.enqueueProject(
                MongoIdParser.required(configId, "config_id"), request.forceArchivedConfig()));
    }

    @GetMapping("/{config_id}/sync-jobs")
    public JobHistoryResponse listConfigRuns(@PathVariable("config_id") String configId,
            @RequestParam(required = false, name = "job_status") JobStatus jobStatus,
            @RequestParam(required = false, name = "job_type") JobType jobType,
            @RequestParam(required = false, name = "created_from") Instant createdFrom,
            @RequestParam(required = false, name = "created_to") Instant createdTo,
            @RequestParam(required = false, name = "before_id") String beforeId,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize) {
        validatePageSize(pageSize);
        ObjectId id = MongoIdParser.required(configId, "config_id");
        var page = syncJobService.list(id, jobStatus, jobType, createdFrom, createdTo,
                MongoIdParser.optional(beforeId, "before_id"), pageSize);
        return new JobHistoryResponse(page.items().stream().map(this::toJobResponse).toList(), page.nextBeforeId());
    }

    private ConfigResponse toResponse(SyncConfigDocument config) {
        return toResponse(config, projectCatalogService.findById(config.planeProjectDocumentId()));
    }

    private ConfigResponse toResponse(SyncConfigDocument config, PlaneProjectDocument project) {
        if (project == null)
            project = projectCatalogService.findById(config.planeProjectDocumentId());
        ProjectResponse projectResponse = new ProjectResponse(project.id().toHexString(), project.planeWorkspaceSlug(),
                project.planeProjectId(), project.planeProjectName(), project.planeProjectIdentifier(),
                planeClient.projectUrl(project.planeWorkspaceSlug(), project.planeProjectId()),
                project.isArchivedInPlane(), project.isAvailable());
        return new ConfigResponse(config.id().toHexString(), config.status(), config.operationStatus(),
                config.operationError(), projectResponse,
                project.notionProjectPageId(), project.notionDatabaseId(), project.notionDataSourceId(),
                config.syncOptions(), config.schedule(), config.createdAt(), config.updatedAt());
    }

    private EnqueuedJobResponse toEnqueuedResponse(SyncJobDocument job) {
        return new EnqueuedJobResponse(job.id().toHexString(), job.jobStatus(), job.createdAt());
    }

    private JobResponse toJobResponse(SyncJobDocument job) {
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

    private void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException(HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE", "page_size must be between 1 and 100.");
        }
    }
}
