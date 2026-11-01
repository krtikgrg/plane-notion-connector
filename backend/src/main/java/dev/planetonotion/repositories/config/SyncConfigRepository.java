package dev.planetonotion.repositories.config;

import dev.planetonotion.models.documents.SyncConfigDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SyncConfigRepository extends MongoRepository<SyncConfigDocument, ObjectId> {

    Optional<SyncConfigDocument> findByPlaneProjectRef(ObjectId planeProjectRef);

    boolean existsByPlaneProjectRef(ObjectId planeProjectRef);

    List<SyncConfigDocument> findAllByOrderByCreatedAtDesc();
}
