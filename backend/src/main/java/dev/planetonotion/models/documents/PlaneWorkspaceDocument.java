package dev.planetonotion.models.documents;

import dev.planetonotion.common.MongoCollectionConstants;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Maps a Plane workspace to the Notion workspace page that contains its synced
 * project pages.
 */
@Document(collection = MongoCollectionConstants.PLANE_WORKSPACES)
public record PlaneWorkspaceDocument(
        /**
         * MongoDB ID for this local workspace mapping.
         */
        @Id ObjectId id,

        /**
         * Plane workspace slug represented by this mapping. The unique index guarantees
         * one
         * Notion workspace page mapping per Plane workspace.
         */
        @Indexed(name = "plane_workspace_slug_unique", unique = true) @Field("plane_workspace_slug") String planeWorkspaceSlug,

        /**
         * Notion page ID used as the parent for the workspace's project pages and
         * summary.
         */
        @Field("notion_page_id") String notionPageId,

        /**
         * When this workspace mapping was first stored locally.
         */
        @Field("created_at") Instant createdAt,

        /**
         * When this workspace mapping was last updated locally.
         */
        @Field("updated_at") Instant updatedAt) {

    /** Java property name for the workspace slug. */
    public static final String FIELD_PLANE_WORKSPACE_SLUG = "planeWorkspaceSlug";
}
