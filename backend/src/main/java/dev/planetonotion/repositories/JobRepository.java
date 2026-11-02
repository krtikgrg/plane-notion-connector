package dev.planetonotion.repositories;

import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.models.documents.jobs.JobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/** Persistence operations for the shared sync and configuration job queue. */
public interface JobRepository {
    // Queue claim/save operations cover every job type; run-history operations
    // return sync jobs only.

    /**
     * Atomically claims and returns one pending job whose scheduled time is due.
     */
    Optional<JobDocument> claimNextDueJob(Instant now);

    /**
     * Returns the start time of the latest successful project-scope run. The worker
     * uses it as the Plane incremental-sync checkpoint; failed runs do not advance
     * the checkpoint.
     */
    Optional<Instant> findLatestSuccessfulProjectSyncStart(ObjectId configId);

    /**
     * Returns one filtered page of run history, ordered by descending MongoDB ID.
     */
    JobPage findJobs(ObjectId configId, JobStatus jobStatus, JobType jobType,
            Instant createdFrom, Instant createdTo, ObjectId beforeId, int pageSize);

    /**
     * Returns pending scheduled jobs across configs for the dashboard's next-run
     * summary.
     */
    List<SyncJobDocument> findAllPendingScheduledJobs();

    /**
     * Returns pending scheduled jobs for one config so its scheduler can resolve
     * overlaps.
     */
    List<SyncJobDocument> findPendingScheduledJobsByConfigId(ObjectId configId);

    /** Holds queued sync work while a configuration update is being applied. */
    void holdPendingByConfigId(ObjectId configId, Instant updatedAt);

    /**
     * Deletes queued or held scheduled occurrences before a schedule is rebuilt.
     */
    void deletePendingAndHeldScheduledByConfigId(ObjectId configId);

    /** Requeues held manual sync work after a configuration operation finishes. */
    void releaseHeldByConfigId(ObjectId configId, Instant updatedAt);

    /** Finds a sync job by its MongoDB document ID. */
    Optional<SyncJobDocument> findById(ObjectId id);

    /** Inserts or updates a sync job. */
    /** Inserts or updates any concrete job subtype in the shared collection. */
    <T extends JobDocument> T save(T job);

    /**
     * A page of history and the keyset cursor to request the following page, when
     * present.
     *
     * @param items        jobs in this page, ordered by descending MongoDB ID
     * @param nextBeforeId hexadecimal ID cursor, or {@code null} when there is no
     *                     next page
     */
    record JobPage(List<SyncJobDocument> items, String nextBeforeId) {
    }
}
