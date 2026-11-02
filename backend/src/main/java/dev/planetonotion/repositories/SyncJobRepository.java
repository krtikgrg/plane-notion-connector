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

    Optional<SyncJobDocument> claimNextDueJob(Instant now);

    Optional<Instant> findLatestSuccessfulProjectSyncStart(ObjectId configId);

    JobPage findJobs(ObjectId configId, SyncJobStatus jobStatus, SyncJobType jobType,
            Instant createdFrom, Instant createdTo, ObjectId beforeId, int pageSize);

    List<SyncJobDocument> findPendingScheduledJobs();

    List<SyncJobDocument> findPendingScheduledJobs(ObjectId configId);

    void deleteByConfigIdAndJobTypeAndJobStatus(ObjectId configId, SyncJobType jobType, SyncJobStatus jobStatus);

    Optional<SyncJobDocument> findById(ObjectId id);

    SyncJobDocument save(SyncJobDocument job);

    /** One page of history and the keyset cursor for the following page, if any. */
    record JobPage(List<SyncJobDocument> items, String nextBeforeId) {
    }
}
