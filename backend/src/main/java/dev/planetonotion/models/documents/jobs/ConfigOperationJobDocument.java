package dev.planetonotion.models.documents.jobs;

import dev.planetonotion.common.PlaneToNotionEnums.ConfigOperationType;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.mapping.Field;

/** A queued request to provision or update one sync configuration. */
@TypeAlias("configuration_job")
public final class ConfigOperationJobDocument extends JobDocument {

    /** Whether this operation creates a config or applies changed settings. */
    @Field("operation_type")
    private final ConfigOperationType operationType;

    /** Desired lifecycle status for an update; null for create operations. */
    @Field("target_status")
    private final ConfigStatus targetStatus;

    /** Desired sync options for an update; null for create operations. */
    @Field("target_sync_options")
    private final SyncOptions targetSyncOptions;

    /** Desired schedule for an update; null for create operations. */
    @Field("target_schedule")
    private final SyncSchedule targetSchedule;

    /** Creates a configuration operation job. */
    @PersistenceCreator
    public ConfigOperationJobDocument(ObjectId id, JobType jobType, JobStatus jobStatus, ObjectId configId,
            ConfigOperationType operationType, ConfigStatus targetStatus, SyncOptions targetSyncOptions,
            SyncSchedule targetSchedule, Instant startedAt, Instant finishedAt, int attemptCount,
            String errorCode, String errorMessage, Instant createdAt, Instant updatedAt) {
        super(id, jobType, jobStatus, configId, startedAt, finishedAt, attemptCount, errorCode, errorMessage,
                createdAt, updatedAt);
        if (jobType != JobType.CONFIGURATION) {
            throw new IllegalArgumentException("Configuration job must have CONFIGURATION job_type");
        }
        this.operationType = operationType;
        this.targetStatus = targetStatus;
        this.targetSyncOptions = targetSyncOptions;
        this.targetSchedule = targetSchedule;
    }

    /** Returns whether this job creates or updates its configuration. */
    public ConfigOperationType operationType() {
        return operationType;
    }

    /** Returns the requested lifecycle status, or null for create operations. */
    public ConfigStatus targetStatus() {
        return targetStatus;
    }

    /** Returns requested sync options, or null for create operations. */
    public SyncOptions targetSyncOptions() {
        return targetSyncOptions;
    }

    /** Returns requested schedule, or null for create operations. */
    public SyncSchedule targetSchedule() {
        return targetSchedule;
    }
}
