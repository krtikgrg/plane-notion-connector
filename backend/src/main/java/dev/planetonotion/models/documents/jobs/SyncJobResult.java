package dev.planetonotion.models.documents.jobs;

import org.springframework.data.mongodb.core.mapping.Field;

/** Counts describing the work completed by one sync job. */
public record SyncJobResult(
        /** Eligible, active, non-draft Plane work items evaluated by the job. */
        @Field("eligible_plane_work_items_count") int eligiblePlaneWorkItemsCount,

        /** Notion pages created for eligible Plane work items. */
        @Field("created_notion_pages_count") int createdNotionPagesCount,

        /** Existing Notion pages updated from Plane work items. */
        @Field("updated_notion_pages_count") int updatedNotionPagesCount,

        /** Eligible work items whose mapped Notion page already matched Plane. */
        @Field("unchanged_notion_pages_count") int unchangedNotionPagesCount) {

    /** Returns zeroed counts for a newly enqueued job. */
    public static SyncJobResult empty() {
        return new SyncJobResult(0, 0, 0, 0);
    }
}
