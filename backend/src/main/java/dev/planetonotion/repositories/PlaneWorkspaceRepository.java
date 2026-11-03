package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import java.util.Optional;

/** Persistence operations for cached Plane workspace summaries. */
public interface PlaneWorkspaceRepository {

    /** Finds a cached workspace by its Plane slug. */
    Optional<PlaneWorkspaceDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug);

    /** Saves one cached workspace document. */
    PlaneWorkspaceDocument save(PlaneWorkspaceDocument workspace);
}
