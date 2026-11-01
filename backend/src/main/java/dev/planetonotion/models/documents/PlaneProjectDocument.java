package dev.planetonotion.models.documents;

import dev.planetonotion.common.constants.MongoCollections;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = MongoCollections.PLANE_PROJECTS)
@CompoundIndex(name = "workspace_project_unique", def = "{'workspace_slug': 1, 'project_id': 1}", unique = true)
public record PlaneProjectDocument(
        @Id ObjectId id,
        @Field("workspace_slug") String workspaceSlug,
        @Field("project_id") String projectId,
        String name,
        String identifier,
        @Field("is_archived") boolean isArchived,
        @Field("is_available") boolean isAvailable,
        @Field("last_refreshed_at") Instant lastRefreshedAt,
        @Field("created_at") Instant createdAt,
        @Field("updated_at") Instant updatedAt) {
}
