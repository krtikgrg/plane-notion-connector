package dev.planetonotion.models.documents;

import dev.planetonotion.common.MongoCollectionConstants;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Links one Plane work item to its Notion page in a specific Notion data
 * source.
 * A null {@code notionPageId} marks a pending create that can be recovered on
 * retry.
 */
@Document(collection = MongoCollectionConstants.SYNC_MAPPINGS)
// Supports exact mapping lookups and prevents duplicate rows per destination
// and Plane item.
@CompoundIndex(name = "notion_destination_plane_work_item_unique", def = "{'notion_data_source_id': 1, 'plane_work_item_id': 1}", unique = true)
public record SyncMappingDocument(
        /** Mongo identifier for this source-to-destination mapping. */
        @Id ObjectId id,

        /** UUID of the source work item in Plane. */
        @Field("plane_work_item_id") String planeWorkItemId,

        /**
         * Notion page ID; null until a pending page create is completed or recovered.
         */
        @Field("notion_page_id") String notionPageId,

        /**
         * Notion data source containing the target page; scopes mappings across
         * destinations.
         */
        @Field("notion_data_source_id") String notionDataSourceId,

        /**
         * SHA-256 of normalized Plane-derived Notion properties, used to skip unchanged
         * writes.
         */
        @Field("plane_source_hash") String planeSourceHash) {

    /** Java property name for the source Plane work-item ID. */
    public static final String FIELD_PLANE_WORK_ITEM_ID = "planeWorkItemId";

    /** Java property name for the destination Notion page ID. */
    public static final String FIELD_NOTION_PAGE_ID = "notionPageId";

    /** Java property name for the destination Notion data-source ID. */
    public static final String FIELD_NOTION_DATA_SOURCE_ID = "notionDataSourceId";

    /** Java property name for the source properties hash. */
    public static final String FIELD_PLANE_SOURCE_HASH = "planeSourceHash";
}
