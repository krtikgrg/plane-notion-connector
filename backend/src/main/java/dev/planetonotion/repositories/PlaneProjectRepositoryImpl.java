package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneProjectDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** MongoTemplate implementation for the cached Plane project catalog. */
@Repository
public class PlaneProjectRepositoryImpl implements PlaneProjectRepository {

    private final MongoTemplate mongoTemplate;

    /** Creates the repository with the MongoDB template used for persistence. */
    public PlaneProjectRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public List<PlaneProjectDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug, MongoQueryOptions options) {
        Query query = MongoQueryUtils.toQuery(options,
                MongoQueryOptions.Filter.equalTo(PlaneProjectDocument.FIELD_PLANE_WORKSPACE_SLUG, planeWorkspaceSlug));
        return mongoTemplate.find(query, PlaneProjectDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PlaneProjectDocument> findByPlaneWorkspaceSlugAndPlaneProjectId(
            String planeWorkspaceSlug, String planeProjectId) {
        Criteria criteria = Criteria.where(PlaneProjectDocument.FIELD_PLANE_WORKSPACE_SLUG).is(planeWorkspaceSlug)
                .and(PlaneProjectDocument.FIELD_PLANE_PROJECT_ID).is(planeProjectId);
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(criteria), PlaneProjectDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PlaneProjectDocument> findById(ObjectId id) {
        return Optional.ofNullable(mongoTemplate.findById(id, PlaneProjectDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public List<PlaneProjectDocument> findAllById(List<ObjectId> ids) {
        if (ids.isEmpty())
            return List.of();
        Query query = Query.query(Criteria.where(PlaneProjectDocument.FIELD_ID).in(ids));
        return mongoTemplate.find(query, PlaneProjectDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public PlaneProjectDocument save(PlaneProjectDocument project) {
        return mongoTemplate.save(project);
    }

    /** {@inheritDoc} */
    @Override
    public void bulkUpsert(List<PlaneProjectDocument> projects) {
        if (projects.isEmpty())
            return;
        BulkOperations operations = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
                PlaneProjectDocument.class);
        projects.forEach(project -> {
            Query query = Query.query(new Criteria().andOperator(
                    Criteria.where(PlaneProjectDocument.FIELD_PLANE_WORKSPACE_SLUG).is(project.planeWorkspaceSlug()),
                    Criteria.where(PlaneProjectDocument.FIELD_PLANE_PROJECT_ID).is(project.planeProjectId())));
            Update update = new Update()
                    .set(PlaneProjectDocument.FIELD_PLANE_PROJECT_NAME, project.planeProjectName())
                    .set(PlaneProjectDocument.FIELD_PLANE_PROJECT_IDENTIFIER, project.planeProjectIdentifier())
                    .set(PlaneProjectDocument.FIELD_IS_ARCHIVED_IN_PLANE, project.isArchivedInPlane())
                    .set(PlaneProjectDocument.FIELD_IS_AVAILABLE, project.isAvailable())
                    .set(PlaneProjectDocument.FIELD_UPDATED_AT, project.updatedAt())
                    .setOnInsert(PlaneProjectDocument.FIELD_PLANE_WORKSPACE_SLUG, project.planeWorkspaceSlug())
                    .setOnInsert(PlaneProjectDocument.FIELD_PLANE_PROJECT_ID, project.planeProjectId())
                    .setOnInsert(PlaneProjectDocument.FIELD_CREATED_AT, project.createdAt());
            operations.upsert(query, update);
        });
        operations.execute();
    }
}
