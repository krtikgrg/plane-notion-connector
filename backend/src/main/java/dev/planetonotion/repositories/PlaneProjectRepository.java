package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneProjectDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;

/** Persistence operations for the cached Plane project catalog. */
public interface PlaneProjectRepository {

    void bulkUpsert(List<PlaneProjectDocument> projects);

    List<PlaneProjectDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug);

    List<PlaneProjectDocument> findByPlaneWorkspaceSlugAndIsAvailableTrueAndIsArchivedInPlaneFalseOrderByPlaneProjectNameAsc(
            String planeWorkspaceSlug);

    Optional<PlaneProjectDocument> findByPlaneWorkspaceSlugAndPlaneProjectId(String planeWorkspaceSlug,
            String planeProjectId);

    Optional<PlaneProjectDocument> findById(ObjectId id);

    List<PlaneProjectDocument> findAllById(List<ObjectId> ids);

    PlaneProjectDocument save(PlaneProjectDocument project);
}
