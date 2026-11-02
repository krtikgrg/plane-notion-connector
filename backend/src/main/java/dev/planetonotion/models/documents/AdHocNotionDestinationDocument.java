package dev.planetonotion.models.documents;

import dev.planetonotion.common.MongoCollectionConstants;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Stores the shared Notion destination used for manual task syncs whose Plane
 * project has no sync config.
 * The single record is reused across such projects; each project is identified
 * on its sync mappings and jobs.
 */
@Document(collection = MongoCollectionConstants.NOTION_DESTINATIONS)
public record AdHocNotionDestinationDocument(
        /**
         * Stable Mongo document key for the one shared ad hoc destination.
         */
        @Id String id,

        /**
         * Notion page under the configured parent page that organizes ad hoc synced
         * work items.
         */
        @Field("notion_page_id") String notionPageId,

        /**
         * Notion database container for work items synced through the shared ad hoc
         * destination.
         */
        @Field("notion_database_id") String notionDatabaseId,

        /**
         * Data source inside the Notion database, used to query and write synced
         * work-item pages.
         */
        @Field("notion_data_source_id") String notionDataSourceId,

        /**
         * When this shared destination record was first created locally.
         */
        @Field("created_at") Instant createdAt,

        /**
         * When this shared destination record was last changed locally.
         */
        @Field("updated_at") Instant updatedAt) {

    /**
     * Stable ID that ensures all unconfigured projects reuse one ad hoc Notion
     * destination.
     */
    public static final String SHARED_DESTINATION_ID = "shared_ad_hoc";
}
