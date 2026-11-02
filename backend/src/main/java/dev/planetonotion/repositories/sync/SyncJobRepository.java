package dev.planetonotion.repositories.sync;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SyncJobRepository extends MongoRepository<SyncJobDocument, ObjectId>, SyncJobRepositoryCustom {

    void deleteByConfigIdAndJobTypeAndJobStatus(ObjectId configId, SyncJobType jobType, SyncJobStatus jobStatus);
}
