package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/** Persistence operations for sync configurations. */
public interface SyncConfigRepository {

    Optional<SyncConfigDocument> findByPlaneProjectDocumentId(ObjectId planeProjectDocumentId);

    boolean existsByPlaneProjectDocumentId(ObjectId planeProjectDocumentId);

    List<SyncConfigDocument> findAllByOrderByCreatedAtDesc();

    List<SyncConfigDocument> findAll();

    Optional<SyncConfigDocument> findById(ObjectId id);

    SyncConfigDocument save(SyncConfigDocument config);

    void deleteById(ObjectId id);
}
