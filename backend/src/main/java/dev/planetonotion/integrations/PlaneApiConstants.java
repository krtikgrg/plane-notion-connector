package dev.planetonotion.integrations;

import java.util.List;

/**
 * Plane API paths, query parameters, and task URL markers used by the adapter.
 */
public final class PlaneApiConstants {

    private PlaneApiConstants() {
    }

    public static final class Paths {
        public static final String USERS_ME = "/api/v2/users/me/";
        public static final String WORKSPACES = "/api/v2/workspaces/";
        public static final String PROJECTS = "/projects/";
        public static final String WORK_ITEMS = "/work-items/";
        public static final String STATES = "/states/";
        public static final String LABELS = "/labels/";

        private Paths() {
        }
    }

    public static final class QueryParameters {
        public static final String INCLUDE_ARCHIVED = "include_archived";
        public static final String FIELDS = "fields";
        public static final String ARCHIVED = "archived";
        public static final String IS_DRAFT = "is_draft";
        public static final String EXPAND = "expand";
        public static final String PAGINATE = "paginate";
        public static final String ORDER_BY = "order_by";
        public static final String UPDATED_AT_GTE = "updated_at__gte";
        public static final String UPDATED_AT_LTE = "updated_at__lte";
        public static final String PER_PAGE = "per_page";
        public static final String COUNT = "count";
        public static final String CURSOR = "cursor";
        public static final String OFFSET = "offset";

        private QueryParameters() {
        }
    }

    public static final class QueryValues {
        public static final String TRUE = "true";
        public static final String FALSE = "false";
        public static final String CURSOR_PAGINATION = "cursor";
        public static final String UPDATED_AT = "updated_at";
        public static final String WORK_ITEM_EXPANSIONS = "state,assignees,labels";
        public static final String PROJECT_FIELDS = "id,name,identifier,archived_at";
        public static final String WORK_ITEM_FIELDS = String.join(",", List.of("id", "identifier", "project_id",
                "state_id", "name", "description_html", "priority", "assignee_ids", "label_ids", "target_date",
                "archived_at", "updated_at", "is_draft"));

        private QueryValues() {
        }
    }

    public static final class TaskUrl {
        public static final String HTTP_SCHEME = "http";
        public static final String HTTPS_SCHEME = "https";
        public static final String PROJECTS_SEGMENT = "projects";
        public static final String BROWSE_SEGMENT = "browse";
        public static final String ISSUES_SEGMENT = "issues";

        private TaskUrl() {
        }
    }

    public static final class Priority {
        public static final String NONE = "none";
        public static final List<String> NOTION_OPTIONS = List.of("urgent", "high", "medium", "low");

        private Priority() {
        }
    }
}
