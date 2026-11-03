package dev.planetonotion.models.documents.configs;

import org.springframework.data.mongodb.core.mapping.Field;

/** Enabled Plane content types for one sync configuration. */
public record SyncOptions(
        /** Whether to sync Plane work items. */
        @Field("work_items") boolean workItems,

        /** Whether to sync Plane pages; page sync is not implemented yet. */
        @Field("pages") boolean pages) {
}
