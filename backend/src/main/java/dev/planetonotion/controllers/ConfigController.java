package dev.planetonotion.controllers;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.MongoIdParser;
import dev.planetonotion.services.PlaneService;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.models.ApiDtos.ConfigResponse;
import dev.planetonotion.models.ApiDtos.CreateConfigRequest;
import dev.planetonotion.models.ApiDtos.EnqueuedJobResponse;
import dev.planetonotion.models.ApiDtos.JobResponse;
import dev.planetonotion.models.ApiDtos.JobHistoryResponse;
import dev.planetonotion.models.ApiDtos.ManualProjectSyncRequest;
import dev.planetonotion.models.ApiDtos.ProjectResponse;
import dev.planetonotion.models.ApiDtos.UpdateConfigRequest;
import dev.planetonotion.services.ConfigService;
import dev.planetonotion.services.ProjectCatalogService;
import dev.planetonotion.services.sync.SyncJobService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
    private final PlaneService planeService;
    private final SyncJobService syncJobService;

    public ConfigController(ConfigService configService, ProjectCatalogService projectCatalogService,
            PlaneService planeService, SyncJobService syncJobService) {
        this.configService = configService;
        this.projectCatalogService = projectCatalogService;
        this.planeService = planeService;
        this.syncJobService = syncJobService;
    }

    @GetMapping
    public List<ConfigResponse> list() {
        List<SyncConfigDocument> configs = configService.list();
        Map<ObjectId, PlaneProjectDocument> projectsById = projectCatalogService.findAllById(
                configs.stream().map(SyncConfigDocument::planeProjectRef).distinct().toList())
                .stream().collect(Collectors.toMap(PlaneProjectDocument::id, Function.identity()));
        return configs.stream().map(config -> toResponse(config, projectsById.get(config.planeProjectRef()))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConfigResponse create(@Valid @RequestBody CreateConfigRequest request) {
        return toResponse(configService.create(new ConfigService.ConfigInput(
                MongoIdParser.required(request.planeProjectRef(), "plane_project_ref"), request.status(),
                request.syncOptions(), request.scheduleType(), request.intervalValue(), request.intervalUnit(),
                request.calendarFrequency(), request.calendarInterval(), request.calendarDaysOfWeek(),
                request.calendarTimeOfDay(), request.startAt(), request.endAt())));
    }

    @GetMapping("/{config_id}")
    public ConfigResponse get(@PathVariable("config_id") String configId) {
        return toResponse(configService.get(MongoIdParser.required(configId, "config_id")));
    }

    @PatchMapping("/{config_id}")
    public ConfigResponse update(@PathVariable("config_id") String configId,
            @Valid @RequestBody UpdateConfigRequest request) {
        return toResponse(configService.update(MongoIdParser.required(configId, "config_id"),
                new ConfigService.ConfigInput(null, request.status(), request.syncOptions(), request.scheduleType(),
                        request.intervalValue(), request.intervalUnit(), request.calendarFrequency(),
                        request.calendarInterval(), request.calendarDaysOfWeek(), request.calendarTimeOfDay(),
                        request.startAt(), request.endAt())));
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
            @RequestParam(required = false) SyncJobStatus status,
            @RequestParam(required = false, name = "trigger_type") SyncJobType triggerType,
            @RequestParam(required = false, name = "created_from") Instant createdFrom,
            @RequestParam(required = false, name = "created_to") Instant createdTo,
            @RequestParam(required = false, name = "before_id") String beforeId,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize) {
        validatePageSize(pageSize);
        ObjectId id = MongoIdParser.required(configId, "config_id");
        var page = syncJobService.list(id, status, triggerType, createdFrom, createdTo,
                MongoIdParser.optional(beforeId, "before_id"), pageSize);
        return new JobHistoryResponse(page.items().stream().map(this::toJobResponse).toList(), page.nextBeforeId());
    }

    private ConfigResponse toResponse(SyncConfigDocument config) {
        return toResponse(config, projectCatalogService.findById(config.planeProjectRef()));
    }

    private ConfigResponse toResponse(SyncConfigDocument config, PlaneProjectDocument project) {
        if (project == null) project = projectCatalogService.findById(config.planeProjectRef());
        ProjectResponse projectResponse = new ProjectResponse(project.id().toHexString(), project.workspaceSlug(),
                project.projectId(), project.name(), project.identifier(),
                planeService.projectUrl(project.workspaceSlug(), project.projectId()),
                project.isArchived(), project.isAvailable(), project.lastRefreshedAt());
        return new ConfigResponse(config.id().toHexString(), config.status(), projectResponse,
                config.notionProjectPageId(), config.notionDatabaseId(), config.notionDataSourceId(),
                config.syncOptions(), config.scheduleType(), config.intervalValue(), config.intervalUnit(),
                config.calendarFrequency(), config.calendarInterval(), config.calendarDaysOfWeek(),
                config.calendarTimeOfDay(), config.startAt(), config.endAt(), config.createdAt(), config.updatedAt());
    }

    private EnqueuedJobResponse toEnqueuedResponse(SyncJobDocument job) {
        return new EnqueuedJobResponse(job.id().toHexString(), job.status(), job.createdAt());
    }

    private JobResponse toJobResponse(SyncJobDocument job) {
        return new JobResponse(job.id().toHexString(), job.triggerType(), job.syncScope(), job.status(),
                job.configId() == null ? null : job.configId().toHexString(),
                job.planeProjectRef() == null ? null : job.planeProjectRef().toHexString(),
                job.requestedTaskUrl(), job.workItemId(), job.scheduledFor(), job.startedAt(), job.finishedAt(),
                job.attemptCount(), job.fetchedCount(), job.eligibleCount(), job.createdCount(), job.updatedCount(),
                job.unchangedCount(), job.failedCount(), job.errorCode(), job.errorMessage(), job.createdAt());
    }

    private void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new dev.planetonotion.common.exceptions.ServiceException(HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE", "page_size must be between 1 and 100.");
        }
    }
}
