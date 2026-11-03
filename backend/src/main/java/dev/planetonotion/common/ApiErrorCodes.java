package dev.planetonotion.common;

/** Stable API error codes grouped by the component that owns the failure. */
public final class ApiErrorCodes {

    private ApiErrorCodes() {
    }

    public static final class Plane {

        private Plane() {
        }

        /**
         * The supplied Plane URL uses a host other than the configured Plane instance.
         */
        public static final String INVALID_HOST = "INVALID_PLANE_HOST";
        /** Plane returned a response that is malformed or missing required data. */
        public static final String RESPONSE_INVALID = "PLANE_RESPONSE_INVALID";
        /** The Plane API token is missing from local configuration. */
        public static final String TOKEN_MISSING = "PLANE_TOKEN_MISSING";
        /** Plane returned an API error that has no more specific integration code. */
        public static final String API_ERROR = "PLANE_API_ERROR";
        /** The service could not connect to Plane or Plane did not respond in time. */
        public static final String UNAVAILABLE = "PLANE_UNAVAILABLE";
    }

    public static final class Notion {

        private Notion() {
        }

        /** The configured Notion parent page ID is missing. */
        public static final String CONFIGURATION_MISSING = "NOTION_CONFIGURATION_MISSING";
        /** Notion returned a response that is malformed or missing required data. */
        public static final String RESPONSE_INVALID = "NOTION_RESPONSE_INVALID";
        /** The Notion API token is missing from local configuration. */
        public static final String TOKEN_MISSING = "NOTION_TOKEN_MISSING";
        /** Notion returned an API error that has no more specific integration code. */
        public static final String API_ERROR = "NOTION_API_ERROR";
        /**
         * The service could not connect to Notion or Notion did not respond in time.
         */
        public static final String UNAVAILABLE = "NOTION_UNAVAILABLE";
        /** Notion failed to archive a temporary page created during setup. */
        public static final String ARCHIVE_FAILED = "NOTION_ARCHIVE_FAILED";
        /** Notion did not return the expected data source for a database. */
        public static final String DATA_SOURCE_MISSING = "NOTION_DATA_SOURCE_MISSING";
        /**
         * A project's stored Notion page, database, or data-source IDs are incomplete.
         */
        public static final String DESTINATION_INCOMPLETE = "NOTION_DESTINATION_INCOMPLETE";
        /**
         * Notion setup failed and cleanup of the partially created resources also
         * failed.
         */
        public static final String SETUP_CLEANUP_FAILED = "NOTION_SETUP_CLEANUP_FAILED";
    }

    public static final class Application {

        private Application() {
        }

        /** The requested Plane work item is archived and cannot be synced. */
        public static final String ARCHIVED_WORK_ITEM = "ARCHIVED_WORK_ITEM";
        /** A configuration already exists for the selected Plane project. */
        public static final String CONFIG_ALREADY_EXISTS = "CONFIG_ALREADY_EXISTS";
        /** The requested configuration is archived and cannot run normally. */
        public static final String CONFIG_ARCHIVED = "CONFIG_ARCHIVED";
        /** The requested configuration does not exist. */
        public static final String CONFIG_NOT_FOUND = "CONFIG_NOT_FOUND";
        /** A queued configuration create or update operation failed. */
        public static final String CONFIG_OPERATION_FAILED = "CONFIG_OPERATION_FAILED";
        /** A configuration create or update is already in progress. */
        public static final String CONFIG_OPERATION_IN_PROGRESS = "CONFIG_OPERATION_IN_PROGRESS";
        /** The configuration is still being provisioned and is not ready to run. */
        public static final String CONFIG_PROVISIONING = "CONFIG_PROVISIONING";
        /** The requested Plane work item is a draft and cannot be synced. */
        public static final String DRAFT_WORK_ITEM = "DRAFT_WORK_ITEM";
        /** The requested feature is not implemented or enabled in this service. */
        public static final String FEATURE_NOT_AVAILABLE = "FEATURE_NOT_AVAILABLE";
        /** The service encountered an unexpected error while handling the request. */
        public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
        /** The submitted configuration contains invalid values or combinations. */
        public static final String INVALID_CONFIG = "INVALID_CONFIG";
        /** A supplied identifier is not in the expected format. */
        public static final String INVALID_ID = "INVALID_ID";
        /** The requested page size is outside the supported range. */
        public static final String INVALID_PAGE_SIZE = "INVALID_PAGE_SIZE";
        /** The supplied Plane project URL is malformed or cannot identify a project. */
        public static final String INVALID_PROJECT_URL = "INVALID_PROJECT_URL";
        /** The request body, parameter, or value is missing or invalid. */
        public static final String INVALID_REQUEST = "INVALID_REQUEST";
        /** The supplied Plane task URL is malformed or cannot identify a work item. */
        public static final String INVALID_TASK_URL = "INVALID_TASK_URL";
        /**
         * The Plane project already has a Notion destination but no new config can
         * claim it.
         */
        public static final String NOTION_DESTINATION_ALREADY_EXISTS = "NOTION_DESTINATION_ALREADY_EXISTS";
        /** The Plane project is archived or unavailable for configuration. */
        public static final String PROJECT_NOT_SELECTABLE = "PROJECT_NOT_SELECTABLE";
        /** The requested Plane project could not be found. */
        public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";
        /** A sync was requested before its Notion destination finished provisioning. */
        public static final String SYNC_DESTINATION_NOT_READY = "SYNC_DESTINATION_NOT_READY";
        /** The project or shared ad hoc Notion destination has not been provisioned. */
        public static final String SYNC_DESTINATION_MISSING = "SYNC_DESTINATION_MISSING";
        /** A work-item sync failed; details are recorded on the sync job. */
        public static final String SYNC_FAILED = "SYNC_FAILED";
        /** The requested sync job or run-history record does not exist. */
        public static final String SYNC_JOB_NOT_FOUND = "SYNC_JOB_NOT_FOUND";
        /**
         * The task URL identifies a project different from the selected configuration.
         */
        public static final String TASK_PROJECT_MISMATCH = "TASK_PROJECT_MISMATCH";
        /** A workspace slug is required to resolve the Plane project. */
        public static final String WORKSPACE_SLUG_REQUIRED = "WORKSPACE_SLUG_REQUIRED";
        /** The queued task sync is missing the source Plane work-item identifier. */
        public static final String WORK_ITEM_REFERENCE_MISSING = "WORK_ITEM_REFERENCE_MISSING";
    }
}
