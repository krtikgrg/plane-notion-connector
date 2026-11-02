package dev.planetonotion.models.documents.jobs;

import dev.planetonotion.common.MongoCollectionConstants;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Common persisted fields for manual and scheduled sync jobs. Both concrete job
 * types share
 * {@value MongoCollectionConstants#SYNC_JOBS}; the Mongo type alias restores
 * the concrete type.
 */
@Document(collection = MongoCollectionConstants.SYNC_JOBS)
// Lets the worker find the oldest pending job while filtering due jobs by
// scheduled_for.
@CompoundIndex(name = "job_claim_idx", def = "{'job_status': 1, 'created_at': 1, 'scheduled_for': 1}")
// Supports a config's run-history page in descending Mongo ID order and its
// before_id cursor.
@CompoundIndex(name = "config_runs_id_idx", def = "{'config_id': 1, '_id': -1}")
// Supports all-run history filtered by job status and type, and pending
// scheduled-job filtering.
@CompoundIndex(name = "runs_job_status_type_id_idx", def = "{'job_status': 1, 'job_type': 1, '_id': -1}")
// Finds the latest successful project-scope run used as the incremental-sync
// checkpoint.
@CompoundIndex(name = "project_checkpoint_idx", def = "{'config_id': 1, 'scope': 1, 'job_status': 1, 'started_at': -1}")
public abstract class SyncJobDocument {

    /** MongoDB identifier for this run. */
    @Id
    private final ObjectId id;

    /** Whether the run was requested manually or created by the schedule. */
    @Field("job_type")
    private final SyncJobType jobType;

    /** Project-wide or single-work-item scope of this run. */
    @Field("scope")
    private final SyncScope scope;

    /** Current lifecycle status of the run. */
    @Field("job_status")
    private final SyncJobStatus jobStatus;

    /**
     * Null only for ad hoc task-URL jobs; configured manual and scheduled jobs
     * share this reference.
     */
    @Field("config_id")
    private final ObjectId configId;

    /**
     * Mongo _id of the cached PlaneProjectDocument, used by every job to resolve
     * its source project.
     */
    @Field("plane_project_document_id")
    private final ObjectId planeProjectDocumentId;

    /**
     * When the worker claimed this run; also used as the incremental-sync
     * checkpoint on successful project runs.
     */
    @Field("started_at")
    private final Instant startedAt;

    /** When processing ended; null while the run is pending or running. */
    @Field("finished_at")
    private final Instant finishedAt;

    /** Number of worker attempts made for this run. */
    @Field("attempt_count")
    private final int attemptCount;

    /**
     * Counts of eligible items and their Notion create/update/unchanged outcomes.
     */
    @Field("result")
    private final SyncJobResult result;

    /**
     * Stable, sanitized outcome category. Examples: {@code PLANE_API_ERROR} means a
     * Plane
     * request failed, {@code NOTION_API_ERROR} means a Notion request failed,
     * {@code SYNC_DESTINATION_MISSING} means no usable Notion destination was
     * found, and
     * {@code SKIPPED_OVERLAP} means this scheduled occurrence elapsed while the
     * config was busy.
     */
    @Field("error_code")
    private final String errorCode;

    /**
     * Sanitized user-facing detail; must not include credentials or raw upstream
     * bodies.
     */
    @Field("error_message")
    private final String errorMessage;

    /** When this run record was first created. */
    @Field("created_at")
    private final Instant createdAt;

    /** When this run record was last changed. */
    @Field("updated_at")
    private final Instant updatedAt;

    /** Java property name for the MongoDB identifier. */
    public static final String FIELD_ID = "id";

    /** Java property name for the configuration document ID. */
    public static final String FIELD_CONFIG_ID = "configId";

    /** Java property name for the job type. */
    public static final String FIELD_JOB_TYPE = "jobType";

    /** Java property name for the sync scope. */
    public static final String FIELD_SCOPE = "scope";

    /** Java property name for the job status. */
    public static final String FIELD_JOB_STATUS = "jobStatus";

    /** Java property name for the worker start timestamp. */
    public static final String FIELD_STARTED_AT = "startedAt";

    /** Java property name for the attempt count. */
    public static final String FIELD_ATTEMPT_COUNT = "attemptCount";

    /** Java property name for the creation timestamp. */
    public static final String FIELD_CREATED_AT = "createdAt";

    /** Java property name for the last update timestamp. */
    public static final String FIELD_UPDATED_AT = "updatedAt";

    /** Initializes fields shared by manual and scheduled job subtypes. */
    protected SyncJobDocument(ObjectId id, SyncJobType jobType, SyncScope scope, SyncJobStatus jobStatus,
            ObjectId configId, ObjectId planeProjectDocumentId, Instant startedAt, Instant finishedAt,
            int attemptCount, SyncJobResult result, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.jobType = jobType;
        this.scope = scope;
        this.jobStatus = jobStatus;
        this.configId = configId;
        this.planeProjectDocumentId = planeProjectDocumentId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.attemptCount = attemptCount;
        this.result = result;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Returns the MongoDB job identifier. */
    public ObjectId id() {
        return id;
    }

    /** Returns the job trigger type. */
    public SyncJobType jobType() {
        return jobType;
    }

    /** Returns whether this run targets a project or one work item. */
    public SyncScope scope() {
        return scope;
    }

    /** Returns the current run status. */
    public SyncJobStatus jobStatus() {
        return jobStatus;
    }

    /** Returns the related config ID, or null for an ad hoc task-URL run. */
    public ObjectId configId() {
        return configId;
    }

    /** Returns the MongoDB ID of the source Plane project. */
    public ObjectId planeProjectDocumentId() {
        return planeProjectDocumentId;
    }

    /** Returns when the worker claimed the run, or null before it starts. */
    public Instant startedAt() {
        return startedAt;
    }

    /** Returns when the run finished, or null while it is pending or running. */
    public Instant finishedAt() {
        return finishedAt;
    }

    /** Returns how many times the worker attempted this run. */
    public int attemptCount() {
        return attemptCount;
    }

    /** Returns the item outcome counts. */
    public SyncJobResult result() {
        return result;
    }

    /** Returns a stable error category, or null when no error occurred. */
    public String errorCode() {
        return errorCode;
    }

    /** Returns sanitized error detail, or null when no error occurred. */
    public String errorMessage() {
        return errorMessage;
    }

    /** Returns when the run record was created. */
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns when the run record was last changed. */
    public Instant updatedAt() {
        return updatedAt;
    }

}
