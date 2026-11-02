package dev.planetonotion.models.documents.configs;

import dev.planetonotion.common.MongoCollectionConstants;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Sync settings for one Plane project. Project and Notion destination metadata
 * live in
 * {@code PlaneProjectDocument}, which can exist before this configuration is
 * created.
 */
@Document(collection = MongoCollectionConstants.SYNC_CONFIGS)
@TypeAlias("sync_config")
public record SyncConfigDocument(
        /** MongoDB ID for this sync configuration. */
        @Id ObjectId id,

        /** Lifecycle state that controls whether scheduled syncs run. */
        ConfigStatus status,

        /**
         * MongoDB ID of the Plane project catalog document this configuration syncs.
         * The unique index enforces one config per project, including disabled or
         * archived configs.
         */
        @Indexed(name = "plane_project_config_unique", unique = true) @Field("plane_project_document_id") ObjectId planeProjectDocumentId,

        /** Typed switches for the Plane content types this configuration syncs. */
        @Field("sync_options") SyncOptions syncOptions,

        /** Schedule type, boundaries, and the matching interval or calendar rule. */
        @Field("schedule") SyncSchedule schedule,

        /** When this configuration was created. */
        @Field("created_at") Instant createdAt,

        /** When this configuration was last updated. */
        @Field("updated_at") Instant updatedAt) {

    /** Java property name for this document's MongoDB identifier. */
    public static final String FIELD_ID = "id";

    /** Java property name for the linked Plane project document ID. */
    public static final String FIELD_PLANE_PROJECT_DOCUMENT_ID = "planeProjectDocumentId";

    /** Java property name used to sort configurations by creation time. */
    public static final String FIELD_CREATED_AT = "createdAt";
}
