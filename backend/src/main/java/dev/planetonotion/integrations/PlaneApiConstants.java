package dev.planetonotion.integrations;

import java.util.List;

/**
 * Plane API paths, query parameters, URL markers, and mapping values used by
 * the
 * integration.
 */
public final class PlaneApiConstants {

    private PlaneApiConstants() {
    }

    /** Path segments for the Plane v2 API requests made by this application. */
    public static final class Paths {
        /**
         * Gets information about the authenticated Plane user; returns user details.
         */
        public static final String USERS_ME = "/api/v2/users/me/";
        /** Starts workspace-scoped API routes; requests append a workspace slug. */
        public static final String WORKSPACES = "/api/v2/workspaces/";
        /** Lists projects or reads one project within a workspace. */
        public static final String PROJECTS = "/projects/";
        /** Lists or reads work items within a workspace and project. */
        public static final String WORK_ITEMS = "/work-items/";
        /** Lists the workflow states configured for a project. */
        public static final String STATES = "/states/";
        /** Lists the labels configured for a project. */
        public static final String LABELS = "/labels/";

        private Paths() {
        }
    }

    /**
     * Query parameter names used to filter, expand, and page through Plane results.
     */
    public static final class QueryParameters {
        /** Whether the project listing should include archived projects. */
        public static final String INCLUDE_ARCHIVED = "include_archived";
        /** Comma-separated names of response fields to include. */
        public static final String FIELDS = "fields";
        /** Whether to include or exclude archived work items. */
        public static final String ARCHIVED = "archived";
        /** Whether to include or exclude draft work items. */
        public static final String IS_DRAFT = "is_draft";
        /** Related objects to include with each work item, such as state or labels. */
        public static final String EXPAND = "expand";
        /** Selects the pagination mode for a work-item listing. */
        public static final String PAGINATE = "paginate";
        /** Field used to sort a response, here the work item's update timestamp. */
        public static final String ORDER_BY = "order_by";
        /** Inclusive lower bound for the work item's update timestamp filter. */
        public static final String UPDATED_AT_GTE = "updated_at__gte";
        /** Inclusive upper bound for the work item's update timestamp filter. */
        public static final String UPDATED_AT_LTE = "updated_at__lte";
        /** Maximum number of records requested in a response page. */
        public static final String PER_PAGE = "per_page";
        /** Whether the response should include a total record count. */
        public static final String COUNT = "count";
        /** Cursor identifying the next page in cursor-based pagination. */
        public static final String CURSOR = "cursor";
        /** Numeric starting offset in offset-based pagination. */
        public static final String OFFSET = "offset";

        private QueryParameters() {
        }
    }

    /** Common values supplied to Plane query parameters by this integration. */
    public static final class QueryValues {
        /** String representation of a true query-parameter value. */
        public static final String TRUE = "true";
        /** String representation of a false query-parameter value. */
        public static final String FALSE = "false";
        /** Requests cursor-based pagination for work-item listings. */
        public static final String CURSOR_PAGINATION = "cursor";
        /** Sorts work items by their last-updated timestamp. */
        public static final String UPDATED_AT = "updated_at";
        /** Related work-item objects requested inline with each result. */
        public static final String WORK_ITEM_EXPANSIONS = "state,assignees,labels";
        /** Project response fields needed for the local project catalog. */
        public static final String PROJECT_FIELDS = "id,name,identifier,archived_at";
        /** Work-item response fields needed to map and synchronize a Plane task. */
        public static final String WORK_ITEM_FIELDS = String.join(",", List.of("id", "identifier", "project_id",
                "state_id", "name", "description_html", "priority", "assignee_ids", "label_ids", "target_date",
                "archived_at", "updated_at", "is_draft"));

        private QueryValues() {
        }
    }

    /** URL schemes and route markers recognized when parsing Plane links. */
    public static final class TaskUrl {
        /** HTTP URL scheme accepted by the URL parser. */
        public static final String HTTP_SCHEME = "http";
        /** HTTPS URL scheme accepted by the URL parser. */
        public static final String HTTPS_SCHEME = "https";
        /** Path segment identifying a project in Plane app URLs. */
        public static final String PROJECTS_SEGMENT = "projects";
        /** Path segment used by workspace browse URLs. */
        public static final String BROWSE_SEGMENT = "browse";
        /** Path segment identifying a work-item route in Plane app URLs. */
        public static final String ISSUES_SEGMENT = "issues";

        private TaskUrl() {
        }
    }

    /** Plane priority values and the corresponding options offered in Notion. */
    public static final class Priority {
        /** Plane's value for a work item with no assigned priority. */
        public static final String NONE = "none";
        /** Named Plane priorities provisioned as options in Notion select fields. */
        public static final List<String> NOTION_OPTIONS = List.of("urgent", "high", "medium", "low");

        private Priority() {
        }
    }
}
