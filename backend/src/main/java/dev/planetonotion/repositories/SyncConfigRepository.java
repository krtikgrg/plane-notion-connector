package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SyncConfigRepository extends MongoRepository<SyncConfigDocument, ObjectId> {

    Optional<SyncConfigDocument> findByPlaneProjectDocumentId(ObjectId planeProjectDocumentId);

    boolean existsByPlaneProjectDocumentId(ObjectId planeProjectDocumentId);

    List<SyncConfigDocument> findAllByOrderByCreatedAtDesc();
}
