package dev.planetonotion.models.documents.jobs;

import dev.planetonotion.common.MongoCollectionConstants;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Shared queue fields for sync runs and configuration operations. */
@Document(collection = MongoCollectionConstants.SYNC_JOBS)
// Finds the oldest queued job, while the scheduled_for key excludes future
// runs.
@CompoundIndex(name = "job_claim_idx", def = "{'job_status': 1, 'created_at': 1, 'scheduled_for': 1}")
// Supports config run-history pagination by descending Mongo ID.
@CompoundIndex(name = "config_runs_id_idx", def = "{'config_id': 1, '_id': -1}")
// Supports filtered history and lookup of pending scheduled runs.
@CompoundIndex(name = "runs_job_status_type_id_idx", def = "{'job_status': 1, 'job_type': 1, '_id': -1}")
// Supports the latest successful project run checkpoint lookup.
@CompoundIndex(name = "project_checkpoint_idx", def = "{'config_id': 1, 'scope': 1, 'job_status': 1, 'started_at': -1}")
public abstract class JobDocument {

    /** MongoDB identifier for this job. */
    @Id
    private final ObjectId id;

    /** Selects the worker handler for this job. */
    @Field("job_type")
    private final JobType jobType;

    /** Current lifecycle status shared by queue handlers. */
    @Field("job_status")
    private final JobStatus jobStatus;

    /** Configuration affected by this job; null only for ad hoc task syncs. */
    @Field("config_id")
    private final ObjectId configId;

    /** When the worker claimed this job; null before it starts. */
    @Field("started_at")
    private final Instant startedAt;

    /** When processing ended; null while pending or running. */
    @Field("finished_at")
    private final Instant finishedAt;

    /** Number of times the worker claimed this job. */
    @Field("attempt_count")
    private final int attemptCount;

    /** Sanitized result category, when processing failed. */
    @Field("error_code")
    private final String errorCode;

    /** Sanitized user-facing detail; must not include credentials or raw bodies. */
    @Field("error_message")
    private final String errorMessage;

    /** When this job was first queued. */
    @Field("created_at")
    private final Instant createdAt;

    /** When this job was last changed. */
    @Field("updated_at")
    private final Instant updatedAt;

    /** Java property name for this document's MongoDB identifier. */
    public static final String FIELD_ID = "id";
    /** Java property name for the job's configuration reference. */
    public static final String FIELD_CONFIG_ID = "configId";
    /** Java property name used to dispatch the job to its handler. */
    public static final String FIELD_JOB_TYPE = "jobType";
    /** Java property name for the job's current lifecycle status. */
    public static final String FIELD_JOB_STATUS = "jobStatus";
    /** Java property name for the worker start timestamp. */
    public static final String FIELD_STARTED_AT = "startedAt";
    /** Java property name for the attempt count. */
    public static final String FIELD_ATTEMPT_COUNT = "attemptCount";
    /** Java property name for the creation timestamp. */
    public static final String FIELD_CREATED_AT = "createdAt";
    /** Java property name for the last update timestamp. */
    public static final String FIELD_UPDATED_AT = "updatedAt";

    /** Initializes fields shared by every queued job type. */
    protected JobDocument(ObjectId id, JobType jobType, JobStatus jobStatus, ObjectId configId,
            Instant startedAt, Instant finishedAt, int attemptCount, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.jobType = jobType;
        this.jobStatus = jobStatus;
        this.configId = configId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.attemptCount = attemptCount;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Returns this job's MongoDB ID. */
    public ObjectId id() {
        return id;
    }

    /** Returns the queue discriminator used for worker dispatch. */
    public JobType jobType() {
        return jobType;
    }

    /** Returns the current queue status. */
    public JobStatus jobStatus() {
        return jobStatus;
    }

    /** Returns the associated config ID, or null for ad hoc syncs. */
    public ObjectId configId() {
        return configId;
    }

    /** Returns when the worker claimed this job. */
    public Instant startedAt() {
        return startedAt;
    }

    /** Returns when processing ended. */
    public Instant finishedAt() {
        return finishedAt;
    }

    /** Returns the number of worker attempts. */
    public int attemptCount() {
        return attemptCount;
    }

    /** Returns a sanitized failure category. */
    public String errorCode() {
        return errorCode;
    }

    /** Returns sanitized failure detail. */
    public String errorMessage() {
        return errorMessage;
    }

    /** Returns when this job was queued. */
    public Instant createdAt() {
        return createdAt;
    }

    /** Returns when this job was last updated. */
    public Instant updatedAt() {
        return updatedAt;
    }
}
