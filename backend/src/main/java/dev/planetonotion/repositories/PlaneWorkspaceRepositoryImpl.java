package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** MongoTemplate implementation for cached Plane workspace summaries. */
@Repository
public class PlaneWorkspaceRepositoryImpl implements PlaneWorkspaceRepository {

    private final MongoTemplate mongoTemplate;

    /** Creates the repository with the MongoDB template used for persistence. */
    public PlaneWorkspaceRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PlaneWorkspaceDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug) {
        Query query = MongoQueryUtils.toQuery(MongoQueryOptions.empty(),
                MongoQueryOptions.Filter.equalTo(
                        PlaneWorkspaceDocument.FIELD_PLANE_WORKSPACE_SLUG, planeWorkspaceSlug));
        return Optional.ofNullable(mongoTemplate.findOne(query, PlaneWorkspaceDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public PlaneWorkspaceDocument save(PlaneWorkspaceDocument workspace) {
        return mongoTemplate.save(workspace);
    }
}
