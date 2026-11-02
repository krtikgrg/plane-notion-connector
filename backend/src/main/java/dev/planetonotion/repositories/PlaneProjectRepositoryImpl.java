package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.PlaneProjectDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
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

    public PlaneProjectRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public List<PlaneProjectDocument> findByPlaneWorkspaceSlug(String planeWorkspaceSlug) {
        Query query = Query.query(Criteria.where("planeWorkspaceSlug").is(planeWorkspaceSlug));
        return mongoTemplate.find(query, PlaneProjectDocument.class);
    }

    @Override
    public List<PlaneProjectDocument> findByPlaneWorkspaceSlugAndIsAvailableTrueAndIsArchivedInPlaneFalseOrderByPlaneProjectNameAsc(
            String planeWorkspaceSlug) {
        Criteria criteria = Criteria.where("planeWorkspaceSlug").is(planeWorkspaceSlug)
                .and("isAvailable").is(true)
                .and("isArchivedInPlane").is(false);
        Query query = Query.query(criteria).with(Sort.by(Sort.Order.asc("planeProjectName")));
        return mongoTemplate.find(query, PlaneProjectDocument.class);
    }

    @Override
    public Optional<PlaneProjectDocument> findByPlaneWorkspaceSlugAndPlaneProjectId(
            String planeWorkspaceSlug, String planeProjectId) {
        Criteria criteria = Criteria.where("planeWorkspaceSlug").is(planeWorkspaceSlug)
                .and("planeProjectId").is(planeProjectId);
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(criteria), PlaneProjectDocument.class));
    }

    @Override
    public Optional<PlaneProjectDocument> findById(ObjectId id) {
        return Optional.ofNullable(mongoTemplate.findById(id, PlaneProjectDocument.class));
    }

    @Override
    public List<PlaneProjectDocument> findAllById(List<ObjectId> ids) {
        if (ids.isEmpty()) return List.of();
        Query query = Query.query(Criteria.where("_id").in(ids));
        return mongoTemplate.find(query, PlaneProjectDocument.class);
    }

    @Override
    public PlaneProjectDocument save(PlaneProjectDocument project) {
        return mongoTemplate.save(project);
    }

    @Override
    public void bulkUpsert(List<PlaneProjectDocument> projects) {
        if (projects.isEmpty()) return;
        BulkOperations operations = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, PlaneProjectDocument.class);
        projects.forEach(project -> {
            Query query = Query.query(new Criteria().andOperator(
                    Criteria.where("planeWorkspaceSlug").is(project.planeWorkspaceSlug()),
                    Criteria.where("planeProjectId").is(project.planeProjectId())));
            Update update = new Update()
                    .set("planeProjectName", project.planeProjectName())
                    .set("planeProjectIdentifier", project.planeProjectIdentifier())
                    .set("isArchivedInPlane", project.isArchivedInPlane())
                    .set("isAvailable", project.isAvailable())
                    .set("updated_at", project.updatedAt())
                    .unset("last_refreshed_at")
                    .setOnInsert("planeWorkspaceSlug", project.planeWorkspaceSlug())
                    .setOnInsert("planeProjectId", project.planeProjectId())
                    .setOnInsert("created_at", project.createdAt());
            operations.upsert(query, update);
        });
        operations.execute();
    }
}
