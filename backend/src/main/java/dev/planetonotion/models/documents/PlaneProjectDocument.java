package dev.planetonotion.models.documents;

import dev.planetonotion.common.MongoCollectionConstants;
import dev.planetonotion.common.utils.TextUtils;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Cached Plane project metadata and its optional, complete Notion destination.
 * A project can be cataloged before a sync configuration is created.
 */
@Document(collection = MongoCollectionConstants.PLANE_PROJECTS)
// Prevents duplicate local catalog entries for the same Plane project within a
// workspace.
@CompoundIndex(name = "workspace_project_unique", def = "{'plane_workspace_slug': 1, 'plane_project_id': 1}", unique = true)
public record PlaneProjectDocument(
        /**
         * Mongo ID.
         */
        @Id ObjectId id,

        /**
         * The slug for the workspace, for example:
         * Workspace Name: Demo Video Workspace
         * Workspace Slug: demo-video-workspace
         */
        @Field("plane_workspace_slug") String planeWorkspaceSlug,

        /**
         * Plane project ID from the Plane API, distinct from this document's Mongo ID.
         */
        @Field("plane_project_id") String planeProjectId,

        /** Human-readable Plane project name. */
        @Field("plane_project_name") String planeProjectName,

        /**
         * Short project key used in work-item identifiers, such as {@code KART} in
         * {@code KART-1234}.
         */
        @Field("plane_project_identifier") String planeProjectIdentifier,

        /**
         * Whether the project is archived in Plane.
         */
        @Field("is_archived_in_plane") boolean isArchivedInPlane,

        /**
         * Whether the most recent Plane project refresh returned this project. Missing
         * projects remain in the
         * catalog but cannot be selected for new configs.
         */
        @Field("is_available") boolean isAvailable,

        /**
         * Notion project page under its workspace page. Optional because a catalog
         * project can exist before
         * a sync configuration is created.
         */
        @Field("notion_project_page_id") String notionProjectPageId,

        /**
         * Notion database container for this project's work items.
         */
        @Field("notion_database_id") String notionDatabaseId,

        /**
         * Notion data source inside the database, used for work-item queries and
         * writes.
         */
        @Field("notion_data_source_id") String notionDataSourceId,

        /**
         * When this project record was first added to the local catalog.
         */
        @Field("created_at") Instant createdAt,

        /**
         * When this project record was last refreshed or its local catalog state
         * changed.
         */
        @Field("updated_at") Instant updatedAt) {

    /**
     * Enforces that a project's Notion page, database, and data source are stored
     * together or not at all.
     */
    public PlaneProjectDocument {
        boolean hasAnyDestination = notionProjectPageId != null || notionDatabaseId != null
                || notionDataSourceId != null;
        boolean hasCompleteDestination = TextUtils.hasText(notionProjectPageId)
                && TextUtils.hasText(notionDatabaseId) && TextUtils.hasText(notionDataSourceId);
        if (hasAnyDestination != hasCompleteDestination) {
            throw new IllegalArgumentException("Notion destination IDs must be either all present or all absent.");
        }
    }

}
