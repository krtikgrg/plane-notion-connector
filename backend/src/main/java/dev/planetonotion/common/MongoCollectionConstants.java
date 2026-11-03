package dev.planetonotion.common;

/**
 * Names the MongoDB collections used to persist Plane-to-Notion integration
 * state.
 */
public final class MongoCollectionConstants {

    /** Cached Plane projects and their configured Notion project destinations. */
    public static final String PLANE_PROJECTS = "plane_projects";
    /** Maps Plane workspace slugs to their Notion workspace pages. */
    public static final String PLANE_WORKSPACES = "plane_workspaces";
    /** Per-project sync settings, status, and schedule. */
    public static final String SYNC_CONFIGS = "sync_configs";
    /** Queued sync/configuration jobs and durable sync run history. */
    public static final String SYNC_JOBS = "sync_jobs";
    /** Maps Plane work items to Notion pages, scoped by Notion data source. */
    public static final String SYNC_MAPPINGS = "sync_mappings";
    /** Stores the shared Notion destination used for ad hoc task URL syncs. */
    public static final String NOTION_DESTINATIONS = "notion_destinations";

    private MongoCollectionConstants() {
    }
}
