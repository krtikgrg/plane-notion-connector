package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import java.util.Optional;

/** Persistence operations for cached Plane workspace summaries. */
public interface PlaneWorkspaceRepository {

    Optional<PlaneWorkspaceDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug);

    PlaneWorkspaceDocument save(PlaneWorkspaceDocument workspace);
}
