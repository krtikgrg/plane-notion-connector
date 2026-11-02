package dev.planetonotion.common;

/** Stable API error codes grouped by the component that owns the failure. */
public final class ApiErrorCodes {

    private ApiErrorCodes() {
    }

    public static final class Plane {

        private Plane() {
        }

        public static final String INVALID_HOST = "INVALID_PLANE_HOST";
        public static final String RESPONSE_INVALID = "PLANE_RESPONSE_INVALID";
        public static final String TOKEN_MISSING = "PLANE_TOKEN_MISSING";
        public static final String API_ERROR = "PLANE_API_ERROR";
        public static final String UNAVAILABLE = "PLANE_UNAVAILABLE";
    }

    public static final class Notion {

        private Notion() {
        }

        public static final String CONFIGURATION_MISSING = "NOTION_CONFIGURATION_MISSING";
        public static final String RESPONSE_INVALID = "NOTION_RESPONSE_INVALID";
        public static final String TOKEN_MISSING = "NOTION_TOKEN_MISSING";
        public static final String API_ERROR = "NOTION_API_ERROR";
        public static final String UNAVAILABLE = "NOTION_UNAVAILABLE";
        public static final String ARCHIVE_FAILED = "NOTION_ARCHIVE_FAILED";
        public static final String DATA_SOURCE_MISSING = "NOTION_DATA_SOURCE_MISSING";
        public static final String DESTINATION_INCOMPLETE = "NOTION_DESTINATION_INCOMPLETE";
        public static final String SETUP_CLEANUP_FAILED = "NOTION_SETUP_CLEANUP_FAILED";
    }

    public static final class Application {

        private Application() {
        }

        public static final String ARCHIVED_WORK_ITEM = "ARCHIVED_WORK_ITEM";
        public static final String CONFIG_ALREADY_EXISTS = "CONFIG_ALREADY_EXISTS";
        public static final String CONFIG_ARCHIVED = "CONFIG_ARCHIVED";
        public static final String CONFIG_NOT_FOUND = "CONFIG_NOT_FOUND";
        public static final String CONFIG_OPERATION_FAILED = "CONFIG_OPERATION_FAILED";
        public static final String CONFIG_OPERATION_IN_PROGRESS = "CONFIG_OPERATION_IN_PROGRESS";
        public static final String CONFIG_PROVISIONING = "CONFIG_PROVISIONING";
        public static final String DRAFT_WORK_ITEM = "DRAFT_WORK_ITEM";
        public static final String FEATURE_NOT_AVAILABLE = "FEATURE_NOT_AVAILABLE";
        public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
        public static final String INVALID_CONFIG = "INVALID_CONFIG";
        public static final String INVALID_ID = "INVALID_ID";
        public static final String INVALID_PAGE_SIZE = "INVALID_PAGE_SIZE";
        public static final String INVALID_PROJECT_URL = "INVALID_PROJECT_URL";
        public static final String INVALID_REQUEST = "INVALID_REQUEST";
        public static final String INVALID_TASK_URL = "INVALID_TASK_URL";
        public static final String NOTION_DESTINATION_ALREADY_EXISTS = "NOTION_DESTINATION_ALREADY_EXISTS";
        public static final String PROJECT_NOT_SELECTABLE = "PROJECT_NOT_SELECTABLE";
        public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";
        public static final String SYNC_DESTINATION_NOT_READY = "SYNC_DESTINATION_NOT_READY";
        public static final String SYNC_DESTINATION_MISSING = "SYNC_DESTINATION_MISSING";
        public static final String SYNC_FAILED = "SYNC_FAILED";
        public static final String SYNC_JOB_NOT_FOUND = "SYNC_JOB_NOT_FOUND";
        public static final String TASK_PROJECT_MISMATCH = "TASK_PROJECT_MISMATCH";
        public static final String WORKSPACE_SLUG_REQUIRED = "WORKSPACE_SLUG_REQUIRED";
        public static final String WORK_ITEM_REFERENCE_MISSING = "WORK_ITEM_REFERENCE_MISSING";
    }
}
