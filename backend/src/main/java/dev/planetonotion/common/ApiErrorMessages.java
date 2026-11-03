package dev.planetonotion.common;

/** Reusable error messages paired with stable API error codes. */
public final class ApiErrorMessages {

    private ApiErrorMessages() {
    }

    public static final class Application {

        private Application() {
        }

        public static final String ARCHIVED_WORK_ITEM = "An archived work item cannot be synced.";
        public static final String CONFIG_ALREADY_EXISTS = "This Plane project already has a sync config.";
        public static final String CONFIG_ARCHIVED_FORCE_RUN_REQUIRED = "Enable force run on archived config to continue.";
        public static final String CONFIG_NOT_FOUND = "The sync config was not found.";
        public static final String DRAFT_WORK_ITEM = "A draft work item cannot be synced.";
        public static final String INVALID_PAGE_SIZE = "page_size must be between 1 and 100.";
        public static final String PLANE_PROJECT_NOT_FOUND = "The Plane project was not found.";
        public static final String SCHEDULED_RUN_ELAPSED = "This scheduled run was skipped because another sync for this configuration was still running when it was due.";
        public static final String SYNC_DESTINATION_REQUIRED = "This sync job is missing the project or destination information required to run.";
    }

    public static final class Plane {

        private Plane() {
        }

        /**
         * Defensive error for malformed pagination metadata; valid responses under
         * Plane v2's
         * documented contract should not trigger it.
         */
        public static final String INVALID_PAGINATION = "Plane returned invalid pagination data.";
        public static final String TOKEN_MISSING = "PLANE_PERSONAL_ACCESS_TOKEN is not configured.";
    }

    public static final class Notion {

        private Notion() {
        }

        public static final String TOKEN_MISSING = "NOTION_CONNECTION_API_TOKEN is not configured.";
    }
}
