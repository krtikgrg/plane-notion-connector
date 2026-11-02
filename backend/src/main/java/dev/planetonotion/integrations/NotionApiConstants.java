package dev.planetonotion.integrations;

/** Notion API version, paths, and query parameters used by the adapter. */
public final class NotionApiConstants {

    private NotionApiConstants() {
    }

    public static final String VERSION = "2026-03-11";

    public static final class Headers {
        public static final String AUTHORIZATION = "Authorization";
        public static final String BEARER_PREFIX = "Bearer ";
        public static final String NOTION_VERSION = "Notion-Version";

        private Headers() {
        }
    }

    public static final class Paths {
        public static final String PAGES = "/v1/pages";
        public static final String DATABASES = "/v1/databases";
        public static final String DATA_SOURCES = "/v1/data_sources/";
        public static final String BLOCKS = "/v1/blocks/";
        public static final String CHILDREN = "/children";
        public static final String MOVE = "/move";
        public static final String QUERY = "/query";

        private Paths() {
        }
    }

    public static final class QueryParameters {
        public static final String PAGE_SIZE = "page_size";
        public static final String START_CURSOR = "start_cursor";

        private QueryParameters() {
        }
    }

    public static final class PropertyNames {
        public static final String WORK_ITEM_ID = "work_item_id";

        private PropertyNames() {
        }
    }

    public static final int BLOCKS_PAGE_SIZE = 100;
}
