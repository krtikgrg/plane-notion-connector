package dev.planetonotion.models.documents.jobs;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.mapping.Field;

/** A project sync created by the schedule for one specific occurrence. */
@TypeAlias("scheduled_sync_job")
public final class ScheduledSyncJobDocument extends SyncJobDocument {

    /** Scheduled occurrence; manual jobs do not store this field. */
    @Field("scheduled_for")
    private final Instant scheduledFor;

    /** Creates a scheduled run and validates its job type. */
    @PersistenceCreator
    public ScheduledSyncJobDocument(ObjectId id, SyncJobType jobType, SyncScope scope, SyncJobStatus jobStatus,
            ObjectId configId, ObjectId planeProjectDocumentId, Instant scheduledFor, Instant startedAt,
            Instant finishedAt, int attemptCount, SyncJobResult result, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt) {
        super(id, jobType, scope, jobStatus, configId, planeProjectDocumentId, startedAt, finishedAt,
                attemptCount, result, errorCode, errorMessage, createdAt, updatedAt);
        if (jobType != SyncJobType.SCHEDULED) {
            throw new IllegalArgumentException("Scheduled job must have SCHEDULED job_type");
        }
        this.scheduledFor = scheduledFor;
    }

    /**
     * Returns the scheduled occurrence time; null only when absent from legacy or
     * malformed data.
     */
    public Instant scheduledFor() {
        return scheduledFor;
    }
}
