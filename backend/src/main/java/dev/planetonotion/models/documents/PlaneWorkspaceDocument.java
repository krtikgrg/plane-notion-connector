package dev.planetonotion.models.documents;

import dev.planetonotion.common.constants.MongoCollections;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = MongoCollections.PLANE_WORKSPACES)
public record PlaneWorkspaceDocument(
        @Id ObjectId id,
        @Indexed(name = "workspace_slug_unique", unique = true)
        @Field("workspace_slug") String workspaceSlug,
        @Field("notion_page_id") String notionPageId,
        @Field("created_at") Instant createdAt,
        @Field("updated_at") Instant updatedAt) {
}
