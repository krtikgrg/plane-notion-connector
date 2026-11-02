package dev.planetonotion.repositories.projects;

import dev.planetonotion.models.documents.PlaneProjectDocument;
import java.util.List;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class PlaneProjectRepositoryImpl implements PlaneProjectRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public PlaneProjectRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
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
                    .set("is_available", project.isAvailable())
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
