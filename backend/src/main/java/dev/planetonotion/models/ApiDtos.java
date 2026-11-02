package dev.planetonotion.models;

import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.models.documents.jobs.SyncJobResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** HTTP request and response records used by the local API controllers. */
public final class ApiDtos {

    /** Prevents instantiation of this DTO namespace. */
    private ApiDtos() {
    }

    /** Result status returned by health and Plane connection checks. */
    public record StatusResponse(
            /**
             * {@code UP} for health checks or {@code CONNECTED} for a successful Plane
             * connection check.
             */
            String status) {
    }

    /** Request to resolve and cache one Plane project from its URL. */
    public record ProjectUrlRequest(
            /** URL of the Plane project to resolve. */
            @NotBlank String projectUrl) {
    }

    /** Request to refresh the cached project catalog for one Plane workspace. */
    public record ProjectRefreshRequest(
            /** Plane workspace slug whose projects should be refreshed. */
            @NotBlank String workspaceSlug) {
    }

    /** Public API representation of one cached Plane project. */
    public record ProjectResponse(
            /** MongoDB ID of the cached Plane project document. */
            String planeProjectDocumentId,
            /** Slug of the Plane workspace containing this project. */
            String workspaceSlug,
            /** Plane's project ID. */
            String projectId,
            /** Human-readable project name. */
            String name,
            /** Short project key used in Plane work-item identifiers. */
            String identifier,
            /** Browser URL of the project in Plane. */
            String projectUrl,
            /** Whether the project is archived in Plane. */
            boolean isArchived,
            /** Whether the latest project refresh found the project. */
            boolean isAvailable) {
    }

    /** Result of refreshing all cached projects for one workspace. */
    public record ProjectRefreshResponse(
            /** Slug of the refreshed workspace. */
            String workspaceSlug,
            /** Time the refresh completed. */
            Instant refreshedAt,
            /** Refreshed project records returned for the workspace. */
            List<ProjectResponse> projects) {
    }

    /**
     * Shared write payload for config creation and updates. The project document ID
     * is required by the create
     * endpoint and ignored by the update endpoint; config status may be omitted on
     * create to use ACTIVE by default.
     */
    public record UpsertConfigRequest(
            /** Required when creating a config and unused when updating one. */
            String planeProjectDocumentId,
            /** Plane resource types enabled for sync. */
            @NotNull SyncOptions syncOptions,
            /**
             * Config status; optional on create (defaults to {@code ACTIVE}) and required
             * on update.
             */
            ConfigStatus status,
            /** Recurrence rule and optional schedule window. */
            @NotNull SyncSchedule schedule) {
    }

    /**
     * Public API representation of a sync config with its Plane project and Notion
     * destination.
     */
    public record ConfigResponse(
            /** MongoDB identifier of this config. */
            String configId,
            /** Lifecycle status that determines whether scheduled runs are created. */
            ConfigStatus status,
            /** Plane project catalog record associated with this config. */
            ProjectResponse project,
            /** Notion page containing the project's summary and work-item database. */
            String notionProjectPageId,
            /** Notion database container for the project's work items. */
            String notionDatabaseId,
            /** Data source used to query and write this project's work-item pages. */
            String notionDataSourceId,
            /** Plane resource types enabled for sync. */
            SyncOptions syncOptions,
            /** Recurrence rule and optional schedule window. */
            SyncSchedule schedule,
            /** Time the config was created. */
            Instant createdAt,
            /** Time the config was last updated. */
            Instant updatedAt) {
    }

    /** Request to enqueue a manual sync for one Plane task URL. */
    public record ManualTaskSyncRequest(
            /** URL of an active, non-archived, non-draft Plane work item. */
            @NotBlank String taskUrl,
            /**
             * Optional config ID; when absent, the task is synced to the shared ad hoc
             * destination.
             */
            String configId,
            /** Whether an archived config may be used for this manual run. */
            boolean forceArchivedConfig) {
    }

    /**
     * Request to enqueue a manual sync for every eligible work item in a configured
     * project.
     */
    public record ManualProjectSyncRequest(
            /** Whether an archived config may be used for this manual run. */
            boolean forceArchivedConfig) {
    }

    /** Acknowledgment returned after a manual job has been added to the queue. */
    public record EnqueuedJobResponse(
            /** MongoDB identifier of the queued job. */
            String jobId,
            /** Initial status of the newly queued job. */
            SyncJobStatus jobStatus,
            /** Time the job was created. */
            Instant createdAt) {
    }

    /**
     * One page of job history and the cursor for requesting the next older page.
     */
    public record JobHistoryResponse(
            /** Jobs on this page, ordered by descending MongoDB ID. */
            List<JobResponse> items,
            /** Cursor for the next page; null when there are no older jobs. */
            String nextBeforeId) {
    }

    /**
     * Plane work-item reference included in a task-specific manual job response.
     */
    public record PlaneWorkItemResponse(
            /** User-submitted Plane task URL. */
            String taskUrl,
            /** Plane UUID resolved from the task URL. */
            String workItemId) {
    }

    /**
     * Public representation of a sync job, shared by manual and scheduled job
     * types.
     */
    public record JobResponse(
            /** MongoDB ID of the job. */
            String jobId,
            /** Whether the job was manual or scheduled. */
            SyncJobType jobType,
            /** Whether the job targets a project or one work item. */
            SyncScope scope,
            /** Current lifecycle state of the job. */
            SyncJobStatus jobStatus,
            /** Config ID, or null for an ad hoc task-URL job. */
            String configId,
            /** MongoDB ID of the cached Plane project record. */
            String planeProjectDocumentId,
            /** Work-item reference for a task-specific run; null for project runs. */
            PlaneWorkItemResponse planeWorkItem,
            /** Scheduled occurrence time; null for manual jobs. */
            Instant scheduledFor,
            /** Time the worker claimed the job, or null before it starts. */
            Instant startedAt,
            /** Time the job finished, or null while pending or running. */
            Instant finishedAt,
            /** Number of worker attempts. */
            int attemptCount,
            /** Eligible item and Notion page outcome counts. */
            SyncJobResult result,
            /** Stable error category, or null on success. */
            String errorCode,
            /** Sanitized error detail, or null on success. */
            String errorMessage,
            /** Time the job was enqueued. */
            Instant createdAt) {
    }

    /** Sanitized error payload returned by API exception handling. */
    public record ApiError(
            /** Stable machine-readable error category. */
            String errorCode,
            /**
             * Safe user-facing explanation without upstream credentials or raw response
             * bodies.
             */
            String message) {
    }
}
