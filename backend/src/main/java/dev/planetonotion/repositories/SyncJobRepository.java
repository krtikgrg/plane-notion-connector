package dev.planetonotion.repositories;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/** Persistence operations for scheduled and manual sync jobs. */
public interface SyncJobRepository {

    /**
     * Atomically claims and returns one pending job whose scheduled time is due.
     */
    Optional<SyncJobDocument> claimNextDueJob(Instant now);

    /**
     * Returns the start time of the latest successful project-scope run. The worker
     * uses it as the Plane incremental-sync checkpoint; failed runs do not advance
     * the checkpoint.
     */
    Optional<Instant> findLatestSuccessfulProjectSyncStart(ObjectId configId);

    /**
     * Returns one filtered page of run history, ordered by descending MongoDB ID.
     */
    JobPage findJobs(ObjectId configId, SyncJobStatus jobStatus, SyncJobType jobType,
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

    /** Deletes jobs matching the configuration, type, and status. */
    void deleteByConfigIdAndJobTypeAndJobStatus(ObjectId configId, SyncJobType jobType, SyncJobStatus jobStatus);

    /** Finds a sync job by its MongoDB document ID. */
    Optional<SyncJobDocument> findById(ObjectId id);

    /** Inserts or updates a sync job. */
    SyncJobDocument save(SyncJobDocument job);

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
