package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneWorkspaceDocument;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** MongoTemplate implementation for cached Plane workspace summaries. */
@Repository
public class PlaneWorkspaceRepositoryImpl implements PlaneWorkspaceRepository {

    private final MongoTemplate mongoTemplate;

    public PlaneWorkspaceRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<PlaneWorkspaceDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug) {
        Query query = Query.query(Criteria.where("planeWorkspaceSlug").is(planeWorkspaceSlug));
        return Optional.ofNullable(mongoTemplate.findOne(query, PlaneWorkspaceDocument.class));
    }

    @Override
    public PlaneWorkspaceDocument save(PlaneWorkspaceDocument workspace) {
        return mongoTemplate.save(workspace);
    }
}
