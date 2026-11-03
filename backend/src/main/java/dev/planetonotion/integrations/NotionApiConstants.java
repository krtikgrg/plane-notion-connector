package dev.planetonotion.integrations;

/**
 * Notion API version, endpoint paths, headers, and query names used by the
 * integration. Endpoint documentation describes the operations used by this
 * application and their main inputs and outputs.
 */
public final class NotionApiConstants {

    private NotionApiConstants() {
    }

    /** Notion API version sent with each request. */
    public static final String VERSION = "2026-03-11";

    /**
     * HTTP header names and values required to authenticate and version requests.
     */
    public static final class Headers {
        /** Carries the integration token using the bearer-token scheme. */
        public static final String AUTHORIZATION = "Authorization";
        /**
         * Prefix placed before the Notion connection token in the authorization header.
         */
        public static final String BEARER_PREFIX = "Bearer ";
        /** Selects the Notion API version used to interpret the request. */
        public static final String NOTION_VERSION = "Notion-Version";

        private Headers() {
        }
    }

    /** Notion API endpoint path segments used to build requests. */
    public static final class Paths {
        /**
         * Pages endpoint. POST creates a page under a parent, GET reads a page
         * by ID, and PATCH updates its properties. For this integration, a page
         * is created under a parent page or data source. PATCH changes either a
         * project page's title or a work item's mapped Plane fields.
         * The response fields used here are the Notion page ID, its parent
         * reference, and its title; Notion may return additional fields.
         */
        public static final String PAGES = "/v1/pages";
        /**
         * Databases endpoint. POST creates a database under a parent page using
         * its title and initial data-source schema; it returns the database and
         * the initial data-source details.
         */
        public static final String DATABASES = "/v1/databases";
        /**
         * Base path for data-source operations. Append a data-source ID to read
         * its schema (GET), update its properties/options (PATCH), or query its
         * rows by appending {@link #QUERY}. Reads return schema details; queries
         * return matching page rows and pagination information.
         */
        public static final String DATA_SOURCES = "/v1/data_sources/";
        /**
         * Base path for block operations. Append a block or page ID to read its
         * children, append children, update it, or archive it. Responses contain
         * block details or the results of the child-block operation.
         */
        public static final String BLOCKS = "/v1/blocks/";
        /**
         * Child-block route segment. GET takes a block ID plus pagination query
         * values and returns child blocks; PATCH takes a block ID and a list of
         * blocks to append, returning the created blocks.
         */
        public static final String CHILDREN = "/children";
        /**
         * Page-move route segment. POST takes the page ID in the path and a new
         * parent reference in the body, returning the moved page.
         */
        public static final String MOVE = "/move";
        /**
         * Data-source query route segment. POST takes a data-source ID in the
         * path and filters and page size in the body; it returns matching pages
         * and pagination details.
         */
        public static final String QUERY = "/query";

        private Paths() {
        }
    }

    /** Query-string parameter names used when paging through Notion results. */
    public static final class QueryParameters {
        /** Maximum number of child blocks requested in one response. */
        public static final String PAGE_SIZE = "page_size";
        /** Cursor returned by Notion that asks for the next page of results. */
        public static final String START_CURSOR = "start_cursor";

        private QueryParameters() {
        }
    }

    /** Notion property names referenced by integration queries. */
    public static final class PropertyNames {
        /** Stored Plane work-item identifier used to find a row after a retry. */
        public static final String WORK_ITEM_ID = "work_item_id";

        private PropertyNames() {
        }
    }

    /** Page size used when requesting child blocks from Notion. */
    public static final int BLOCKS_PAGE_SIZE = 100;
}
