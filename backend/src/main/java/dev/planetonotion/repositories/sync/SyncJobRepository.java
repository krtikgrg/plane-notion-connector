package dev.planetonotion.repositories.sync;

import dev.planetonotion.common.enums.SyncJobStatus;
import dev.planetonotion.common.enums.SyncJobType;
import dev.planetonotion.models.documents.SyncJobDocument;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SyncJobRepository extends MongoRepository<SyncJobDocument, ObjectId>, SyncJobRepositoryCustom {

    List<SyncJobDocument> findByConfigIdAndTriggerTypeAndStatusOrderByScheduledForAsc(
            ObjectId configId, SyncJobType triggerType, SyncJobStatus status);

    void deleteByConfigIdAndTriggerTypeAndStatus(ObjectId configId, SyncJobType triggerType, SyncJobStatus status);
}
