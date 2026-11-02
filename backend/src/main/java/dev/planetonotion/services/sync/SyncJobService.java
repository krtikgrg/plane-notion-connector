package dev.planetonotion.services.sync;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ProjectDefaults;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.integrations.models.PlaneApiModels.Project;
import dev.planetonotion.integrations.models.PlaneApiModels.WorkItem;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.jobs.ManualSyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobResult;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.repositories.JobRepository;
import dev.planetonotion.repositories.PlaneProjectRepository;
import dev.planetonotion.repositories.SyncConfigRepository;
import dev.planetonotion.integrations.PlaneClient;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.BROWSE_SEGMENT;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.HTTP_SCHEME;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.HTTPS_SCHEME;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.ISSUES_SEGMENT;
import static dev.planetonotion.integrations.PlaneApiConstants.TaskUrl.PROJECTS_SEGMENT;

@Service
public class SyncJobService {

    private final JobRepository jobRepository;
    private final SyncConfigRepository configRepository;
    private final PlaneProjectRepository projectRepository;
    private final PlaneClient planeClient;
    private final IntegrationProperties integrationProperties;

    public SyncJobService(JobRepository jobRepository, SyncConfigRepository configRepository,
            PlaneProjectRepository projectRepository, PlaneClient planeClient,
            IntegrationProperties integrationProperties) {
        this.jobRepository = jobRepository;
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.planeClient = planeClient;
        this.integrationProperties = integrationProperties;
    }

    public SyncJobDocument enqueueProject(ObjectId configId, boolean forceArchivedConfig) {
        SyncConfigDocument config = config(configId);
        validateArchivedConfig(config, forceArchivedConfig);
        validateConfigQueueable(config);
        return saveJob(config, config.planeProjectDocumentId(), SyncScope.PROJECT,
                null, forceArchivedConfig);
    }

    public SyncJobDocument enqueueTask(String taskUrl, ObjectId requestedConfigId, boolean forceArchivedConfig) {
        TaskUrl taskUrlParts = parseTaskUrl(taskUrl);
        WorkItem item = taskUrlParts.identifier() != null
                ? planeClient.getWorkItemByIdentifier(taskUrlParts.workspaceSlug(), taskUrlParts.identifier())
                : planeClient.getWorkItem(taskUrlParts.workspaceSlug(), taskUrlParts.projectId(),
                        taskUrlParts.workItemId());
        if (Boolean.TRUE.equals(item.isDraft())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.DRAFT_WORK_ITEM,
                    ApiErrorMessages.Application.DRAFT_WORK_ITEM);
        }
        if (item.archivedAt() != null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.ARCHIVED_WORK_ITEM,
                    ApiErrorMessages.Application.ARCHIVED_WORK_ITEM);
        }

        String projectId = item.projectId() == null ? taskUrlParts.projectId() : item.projectId();
        String workItemId = item.id() == null ? "" : item.id();
        if (projectId.isBlank() || workItemId.isBlank()) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, ApiErrorCodes.Plane.RESPONSE_INVALID,
                    "Plane did not identify this work item's project and ID.");
        }
        if (taskUrlParts.projectId() != null && !taskUrlParts.projectId().equals(projectId)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.TASK_PROJECT_MISMATCH,
                    "The task URL does not match the work item's Plane project.");
        }

        PlaneProjectDocument project = findOrCacheProject(taskUrlParts.workspaceSlug(), projectId);
        SyncConfigDocument config = configRepository.findByPlaneProjectDocumentId(project.id()).orElse(null);
        if (requestedConfigId != null) {
            SyncConfigDocument requested = config(requestedConfigId);
            if (!requested.planeProjectDocumentId().equals(project.id())) {
                throw new ServiceException(HttpStatus.BAD_REQUEST,
                        ApiErrorCodes.Application.TASK_PROJECT_MISMATCH,
                        "The task URL does not belong to the selected config's project.");
            }
            config = requested;
        }

        if (config == null) {
            return saveJob(null, project.id(), SyncScope.WORK_ITEM,
                    new ManualSyncJobDocument.PlaneWorkItemReference(taskUrl, workItemId), forceArchivedConfig);
        }
        validateArchivedConfig(config, forceArchivedConfig);
        validateConfigQueueable(config);
        return saveJob(config, project.id(), SyncScope.WORK_ITEM,
                new ManualSyncJobDocument.PlaneWorkItemReference(taskUrl, workItemId), forceArchivedConfig);
    }

    public JobRepository.JobPage list(ObjectId configId, JobStatus jobStatus, JobType jobType,
            Instant createdFrom, Instant createdTo, ObjectId beforeId, int pageSize) {
        return jobRepository.findJobs(configId, jobStatus, jobType, createdFrom, createdTo, beforeId, pageSize);
    }

    public Map<String, Instant> nextScheduledRuns() {
        Map<String, Instant> nextRuns = new LinkedHashMap<>();
        jobRepository.findAllPendingScheduledJobs().forEach(job -> {
            if (job instanceof ScheduledSyncJobDocument scheduled
                    && job.configId() != null && scheduled.scheduledFor() != null) {
                nextRuns.merge(job.configId().toHexString(), scheduled.scheduledFor(),
                        (current, candidate) -> candidate.isBefore(current) ? candidate : current);
            }
        });
        return nextRuns;
    }

    public SyncJobDocument get(ObjectId jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.SYNC_JOB_NOT_FOUND,
                        "The sync run was not found."));
    }

    private PlaneProjectDocument findOrCacheProject(String workspaceSlug, String projectId) {
        return projectRepository.findByPlaneWorkspaceSlugAndPlaneProjectId(workspaceSlug, projectId).orElseGet(() -> {
            Project source = planeClient.getProject(workspaceSlug, projectId);
            Instant now = Instant.now();
            boolean isArchived = source.archivedAt() != null || Boolean.TRUE.equals(source.isArchived());
            return projectRepository.save(new PlaneProjectDocument(null, workspaceSlug, projectId,
                    source.name() == null ? ProjectDefaults.UNNAMED_PROJECT : source.name(),
                    source.identifier() == null ? "" : source.identifier(),
                    isArchived, true, null, null, null, now, now));
        });
    }

    private SyncConfigDocument config(ObjectId configId) {
        return configRepository.findById(configId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND,
                        ApiErrorCodes.Application.CONFIG_NOT_FOUND,
                        ApiErrorMessages.Application.CONFIG_NOT_FOUND));
    }

    private void validateArchivedConfig(SyncConfigDocument config, boolean forceArchivedConfig) {
        if (config.status() == ConfigStatus.ARCHIVED && !forceArchivedConfig) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_ARCHIVED,
                    ApiErrorMessages.Application.CONFIG_ARCHIVED_FORCE_RUN_REQUIRED);
        }
    }

    private void validateConfigQueueable(SyncConfigDocument config) {
        if (config.operationStatus() == ConfigOperationStatus.PROVISIONING) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.CONFIG_PROVISIONING,
                    "Wait for the Notion destination to finish provisioning before queueing a sync.");
        }
        PlaneProjectDocument project = projectRepository.findById(config.planeProjectDocumentId()).orElse(null);
        if (project == null || project.notionProjectPageId() == null || project.notionDatabaseId() == null
                || project.notionDataSourceId() == null) {
            throw new ServiceException(HttpStatus.CONFLICT, ApiErrorCodes.Application.SYNC_DESTINATION_NOT_READY,
                    "The Notion destination is not ready. Retry after configuration setup finishes.");
        }
    }

    private SyncJobDocument saveJob(SyncConfigDocument config, ObjectId projectRef, SyncScope scope,
            ManualSyncJobDocument.PlaneWorkItemReference planeWorkItem, boolean forceArchived) {
        Instant now = Instant.now();
        JobStatus initialStatus = config != null
                && config.operationStatus() == ConfigOperationStatus.UPDATING
                        ? JobStatus.HELD
                        : JobStatus.PENDING;
        return jobRepository.save(new ManualSyncJobDocument(new ObjectId(), JobType.MANUAL, scope, initialStatus,
                config == null ? null : config.id(), projectRef, planeWorkItem, forceArchived,
                null, null, 0, SyncJobResult.empty(), null, null, now, now));
    }

    private TaskUrl parseTaskUrl(String value) {
        URI url;
        try {
            url = URI.create(value);
        } catch (RuntimeException exception) {
            throw invalidTaskUrl();
        }
        if (url.getHost() == null || !url.getHost().equalsIgnoreCase(integrationProperties.planeAppBaseUrl().getHost())
                || !(HTTPS_SCHEME.equalsIgnoreCase(url.getScheme()) || HTTP_SCHEME.equalsIgnoreCase(url.getScheme()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Plane.INVALID_HOST,
                    "Enter a task URL from the configured Plane instance.");
        }
        String[] path = url.getPath().split("/");
        if (path.length < 3 || path[1].isBlank())
            throw invalidTaskUrl();
        String workspace = path[1];
        for (int index = 2; index < path.length; index++) {
            if (BROWSE_SEGMENT.equals(path[index]) && index + 1 < path.length && !path[index + 1].isBlank()) {
                return new TaskUrl(workspace, null, path[index + 1], null);
            }
            if (ISSUES_SEGMENT.equals(path[index]) && index + 1 < path.length && !path[index + 1].isBlank()) {
                String project = null;
                for (int prior = 2; prior < index; prior++) {
                    if (PROJECTS_SEGMENT.equals(path[prior]) && prior + 1 < index)
                        project = path[prior + 1];
                }
                if (project != null)
                    return new TaskUrl(workspace, project, null, path[index + 1]);
            }
        }
        throw invalidTaskUrl();
    }

    private ServiceException invalidTaskUrl() {
        return new ServiceException(HttpStatus.BAD_REQUEST, ApiErrorCodes.Application.INVALID_TASK_URL,
                "Enter a Plane task URL with a workspace and task identifier.");
    }

    private record TaskUrl(String workspaceSlug, String projectId, String identifier, String workItemId) {
    }
}
