package dev.planetonotion.services.sync;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.config.PlaneProperties;
import dev.planetonotion.common.enums.ConfigStatus;
import dev.planetonotion.common.enums.SyncJobStatus;
import dev.planetonotion.common.enums.SyncJobType;
import dev.planetonotion.common.enums.SyncScope;
import dev.planetonotion.common.exceptions.ServiceException;
import dev.planetonotion.integrations.plane.PlaneClient;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.repositories.config.SyncConfigRepository;
import dev.planetonotion.repositories.projects.PlaneProjectRepository;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import dev.planetonotion.repositories.sync.SyncJobRepositoryCustom;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SyncJobService {

    private final SyncJobRepository jobRepository;
    private final SyncConfigRepository configRepository;
    private final PlaneProjectRepository projectRepository;
    private final PlaneClient planeClient;
    private final PlaneProperties planeProperties;

    public SyncJobService(SyncJobRepository jobRepository, SyncConfigRepository configRepository,
            PlaneProjectRepository projectRepository, PlaneClient planeClient, PlaneProperties planeProperties) {
        this.jobRepository = jobRepository;
        this.configRepository = configRepository;
        this.projectRepository = projectRepository;
        this.planeClient = planeClient;
        this.planeProperties = planeProperties;
    }

    public SyncJobDocument enqueueProject(ObjectId configId, boolean forceArchivedConfig) {
        SyncConfigDocument config = config(configId);
        validateArchivedConfig(config, forceArchivedConfig);
        return saveJob(config, config.planeProjectRef(), SyncJobType.MANUAL, SyncScope.PROJECT,
                null, null, forceArchivedConfig, null);
    }

    public SyncJobDocument enqueueTask(String taskUrl, ObjectId requestedConfigId, boolean forceArchivedConfig) {
        TaskUrl taskUrlParts = parseTaskUrl(taskUrl);
        JsonNode item = taskUrlParts.identifier() != null
                ? planeClient.getWorkItemByIdentifier(taskUrlParts.workspaceSlug(), taskUrlParts.identifier())
                : planeClient.getWorkItem(taskUrlParts.workspaceSlug(), taskUrlParts.projectId(), taskUrlParts.workItemId());
        if (item.path("is_draft").asBoolean(false)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "DRAFT_WORK_ITEM", "A draft work item cannot be synced.");
        }
        if (!item.path("archived_at").isMissingNode() && !item.path("archived_at").isNull()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "ARCHIVED_WORK_ITEM", "An archived work item cannot be synced.");
        }

        String projectId = item.path("project_id").asString(taskUrlParts.projectId());
        String workItemId = item.path("id").asString("");
        if (projectId.isBlank() || workItemId.isBlank()) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "PLANE_RESPONSE_INVALID", "Plane did not identify this work item's project and ID.");
        }
        if (taskUrlParts.projectId() != null && !taskUrlParts.projectId().equals(projectId)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "TASK_PROJECT_MISMATCH", "The task URL does not match the work item's Plane project.");
        }

        PlaneProjectDocument project = findOrCacheProject(taskUrlParts.workspaceSlug(), projectId);
        SyncConfigDocument config = configRepository.findByPlaneProjectRef(project.id()).orElse(null);
        if (requestedConfigId != null) {
            SyncConfigDocument requested = config(requestedConfigId);
            if (!requested.planeProjectRef().equals(project.id())) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "TASK_PROJECT_MISMATCH", "The task URL does not belong to the selected config's project.");
            }
            config = requested;
        }

        if (config == null) {
            return saveJob(null, project.id(), SyncJobType.MANUAL, SyncScope.WORK_ITEM,
                    taskUrl, workItemId, forceArchivedConfig, null);
        }
        validateArchivedConfig(config, forceArchivedConfig);
        return saveJob(config, project.id(), SyncJobType.MANUAL, SyncScope.WORK_ITEM,
                taskUrl, workItemId, forceArchivedConfig, null);
    }

    public SyncJobRepositoryCustom.JobPage list(ObjectId configId, SyncJobStatus status, SyncJobType triggerType,
            Instant createdFrom, Instant createdTo, ObjectId beforeId, int pageSize) {
        return jobRepository.findJobs(configId, status, triggerType, createdFrom, createdTo, beforeId, pageSize);
    }

    public Map<String, Instant> nextScheduledRuns() {
        Map<String, Instant> nextRuns = new LinkedHashMap<>();
        jobRepository.findPendingScheduledJobs().forEach(job -> {
            if (job.configId() != null && job.scheduledFor() != null) {
                nextRuns.putIfAbsent(job.configId().toHexString(), job.scheduledFor());
            }
        });
        return nextRuns;
    }

    public SyncJobDocument get(ObjectId jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "SYNC_JOB_NOT_FOUND", "The sync run was not found."));
    }

    private PlaneProjectDocument findOrCacheProject(String workspaceSlug, String projectId) {
        return projectRepository.findByWorkspaceSlugAndProjectId(workspaceSlug, projectId).orElseGet(() -> {
            JsonNode source = planeClient.getProject(workspaceSlug, projectId);
            Instant now = Instant.now();
            JsonNode archivedAt = source.path("archived_at");
            boolean isArchived = !archivedAt.isMissingNode() ? !archivedAt.isNull()
                    : source.path("is_archived").asBoolean(false);
            return projectRepository.save(new PlaneProjectDocument(null, workspaceSlug, projectId,
                    source.path("name").asString("Unnamed project"), source.path("identifier").asString(""),
                    isArchived, true, now, now, now));
        });
    }

    private SyncConfigDocument config(ObjectId configId) {
        return configRepository.findById(configId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", "The sync config was not found."));
    }

    private void validateArchivedConfig(SyncConfigDocument config, boolean forceArchivedConfig) {
        if (config.status() == ConfigStatus.ARCHIVED && !forceArchivedConfig) {
            throw new ServiceException(HttpStatus.CONFLICT, "CONFIG_ARCHIVED", "Enable force run on archived config to continue.");
        }
    }

    private SyncJobDocument saveJob(SyncConfigDocument config, ObjectId projectRef, SyncJobType type,
            SyncScope scope, String requestedTaskUrl, String workItemId, boolean forceArchived, Instant scheduledFor) {
        Instant now = Instant.now();
        return jobRepository.save(new SyncJobDocument(null, type, scope, SyncJobStatus.PENDING,
                config == null ? null : config.id(), projectRef, requestedTaskUrl, workItemId, forceArchived,
                scheduledFor, null, null, 0, 0, 0, 0, 0, 0, 0, null, null, now, now));
    }

    private TaskUrl parseTaskUrl(String value) {
        URI url;
        try {
            url = URI.create(value);
        } catch (RuntimeException exception) {
            throw invalidTaskUrl();
        }
        if (url.getHost() == null || !url.getHost().equalsIgnoreCase(planeProperties.appBaseUrl().getHost())
                || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_PLANE_HOST", "Enter a task URL from the configured Plane instance.");
        }
        String[] path = url.getPath().split("/");
        if (path.length < 3 || path[1].isBlank()) throw invalidTaskUrl();
        String workspace = path[1];
        for (int index = 2; index < path.length; index++) {
            if ("browse".equals(path[index]) && index + 1 < path.length && !path[index + 1].isBlank()) {
                return new TaskUrl(workspace, null, path[index + 1], null);
            }
            if ("issues".equals(path[index]) && index + 1 < path.length && !path[index + 1].isBlank()) {
                String project = null;
                for (int prior = 2; prior < index; prior++) {
                    if ("projects".equals(path[prior]) && prior + 1 < index) project = path[prior + 1];
                }
                if (project != null) return new TaskUrl(workspace, project, null, path[index + 1]);
            }
        }
        throw invalidTaskUrl();
    }

    private ServiceException invalidTaskUrl() {
        return new ServiceException(HttpStatus.BAD_REQUEST, "INVALID_TASK_URL", "Enter a Plane task URL with a workspace and task identifier.");
    }

    private record TaskUrl(String workspaceSlug, String projectId, String identifier, String workItemId) {
    }
}
