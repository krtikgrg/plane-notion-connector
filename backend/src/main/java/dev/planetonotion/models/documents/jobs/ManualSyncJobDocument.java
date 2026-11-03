package dev.planetonotion.models.documents.jobs;

import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.mapping.Field;

/** A manually requested project or work-item sync. */
@TypeAlias("manual_sync_job")
public final class ManualSyncJobDocument extends SyncJobDocument {

    /**
     * Null when this manual job syncs an entire project rather than one task URL.
     */
    @Field("plane_work_item")
    private final PlaneWorkItemReference planeWorkItem;

    /** Whether an archived config was explicitly allowed for this manual run. */
    @Field("force_archived_config")
    private final boolean forceArchivedConfig;

    /** Creates a manual run and validates its job type. */
    @PersistenceCreator
    public ManualSyncJobDocument(ObjectId id, JobType jobType, SyncScope scope, JobStatus jobStatus,
            ObjectId configId, ObjectId planeProjectDocumentId, PlaneWorkItemReference planeWorkItem,
            boolean forceArchivedConfig, Instant startedAt, Instant finishedAt, int attemptCount,
            SyncJobResult result, String errorCode, String errorMessage, Instant createdAt,
            Instant updatedAt) {
        super(id, jobType, scope, jobStatus, configId, planeProjectDocumentId, startedAt, finishedAt,
                attemptCount, result, errorCode, errorMessage, createdAt, updatedAt);
        if (jobType != JobType.MANUAL)
            throw new IllegalArgumentException("Manual job must have MANUAL job_type");
        this.planeWorkItem = planeWorkItem;
        this.forceArchivedConfig = forceArchivedConfig;
    }

    /**
     * Returns the task URL and resolved work-item ID, or null for a project-wide
     * manual run.
     */
    public PlaneWorkItemReference planeWorkItem() {
        return planeWorkItem;
    }

    /**
     * Returns whether this run was explicitly allowed to use an archived config.
     */
    public boolean forceArchivedConfig() {
        return forceArchivedConfig;
    }

    /**
     * The user-submitted task URL and the corresponding Plane work-item UUID for a
     * task-specific run.
     */
    public record PlaneWorkItemReference(
            /** Submitted URL used to request the manual sync. */
            @Field("task_url") String taskUrl,

            /** Plane UUID resolved from the submitted task URL. */
            @Field("work_item_id") String workItemId) {
    }
}
