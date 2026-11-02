package dev.planetonotion.repositories.projects;

import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlaneWorkspaceRepository extends MongoRepository<PlaneWorkspaceDocument, ObjectId> {

    Optional<PlaneWorkspaceDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug);
}
