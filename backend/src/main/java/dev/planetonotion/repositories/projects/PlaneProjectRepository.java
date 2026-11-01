package dev.planetonotion.repositories.projects;

import dev.planetonotion.models.documents.PlaneProjectDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlaneProjectRepository extends MongoRepository<PlaneProjectDocument, ObjectId>, PlaneProjectRepositoryCustom {

    List<PlaneProjectDocument> findByWorkspaceSlug(String workspaceSlug);

    List<PlaneProjectDocument> findByWorkspaceSlugAndIsAvailableTrueAndIsArchivedFalseOrderByNameAsc(String workspaceSlug);

    Optional<PlaneProjectDocument> findByWorkspaceSlugAndProjectId(String workspaceSlug, String projectId);
}
