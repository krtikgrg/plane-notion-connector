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
                    Criteria.where("workspace_slug").is(project.workspaceSlug()),
                    Criteria.where("project_id").is(project.projectId())));
            Update update = new Update()
                    .set("name", project.name())
                    .set("identifier", project.identifier())
                    .set("is_archived", project.isArchived())
                    .set("is_available", project.isAvailable())
                    .set("last_refreshed_at", project.lastRefreshedAt())
                    .set("updated_at", project.updatedAt())
                    .setOnInsert("workspace_slug", project.workspaceSlug())
                    .setOnInsert("project_id", project.projectId())
                    .setOnInsert("created_at", project.createdAt());
            operations.upsert(query, update);
        });
        operations.execute();
    }
}
