package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneProjectDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/** Persistence operations for the cached Plane project catalog. */
public interface PlaneProjectRepository {

    /**
     * Inserts or updates a batch of projects using workspace and Plane project IDs.
     */
    void bulkUpsert(List<PlaneProjectDocument> projects);

    /**
     * Returns cached projects in a workspace matching the supplied filters and sort
     * order.
     */
    List<PlaneProjectDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug, MongoQueryOptions options);

    /** Finds a project by its workspace slug and Plane project ID. */
    Optional<PlaneProjectDocument> findByPlaneWorkspaceSlugAndPlaneProjectId(String planeWorkspaceSlug,
            String planeProjectId);

    /** Finds a cached project by its MongoDB document ID. */
    Optional<PlaneProjectDocument> findById(ObjectId id);

    /** Returns cached projects matching the supplied MongoDB document IDs. */
    List<PlaneProjectDocument> findAllById(List<ObjectId> ids);

    /** Saves one cached project document. */
    PlaneProjectDocument save(PlaneProjectDocument project);
}
