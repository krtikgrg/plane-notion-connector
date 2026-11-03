package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/** Persistence operations for sync configurations. */
public interface SyncConfigRepository {

    /** Finds the sync configuration linked to a cached Plane project document. */
    Optional<SyncConfigDocument> findByPlaneProjectDocumentId(ObjectId planeProjectDocumentId);

    /** Checks for a project configuration without loading the full document. */
    boolean existsByPlaneProjectDocumentId(ObjectId planeProjectDocumentId);

    /**
     * Returns all sync configurations matching the supplied filters and sort order.
     */
    List<SyncConfigDocument> findAll(MongoQueryOptions options);

    /** Finds a sync configuration by its MongoDB document ID. */
    Optional<SyncConfigDocument> findById(ObjectId id);

    /** Inserts or updates a sync configuration. */
    SyncConfigDocument save(SyncConfigDocument config);

    /** Removes a sync configuration by its MongoDB document ID. */
    void deleteById(ObjectId id);
}
