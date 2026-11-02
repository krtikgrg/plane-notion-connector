package dev.planetonotion.repositories.sync;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

public interface SyncJobRepositoryCustom {

    Optional<SyncJobDocument> claimNextDueJob(Instant now);

    Optional<Instant> findLatestSuccessfulProjectSyncStart(ObjectId configId);

    JobPage findJobs(ObjectId configId, SyncJobStatus jobStatus, SyncJobType jobType,
            Instant createdFrom, Instant createdTo, ObjectId beforeId, int pageSize);

    List<SyncJobDocument> findPendingScheduledJobs();

    List<SyncJobDocument> findPendingScheduledJobs(ObjectId configId);

    record JobPage(List<SyncJobDocument> items, String nextBeforeId) {
    }
}
