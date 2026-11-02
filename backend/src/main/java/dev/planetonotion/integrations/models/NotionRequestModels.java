package dev.planetonotion.integrations.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.planetonotion.integrations.NotionApiConstants.PropertyNames;
import java.util.List;

/** Typed request payloads for the Notion API. */
public final class NotionRequestModels {

    private NotionRequestModels() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParentReference(
            String type,
            @JsonProperty("page_id") String pageId,
            @JsonProperty("data_source_id") String dataSourceId) {

        public static ParentReference page(String pageId) {
            return new ParentReference("page_id", pageId, null);
        }

        public static ParentReference dataSource(String dataSourceId) {
            return new ParentReference("data_source_id", null, dataSourceId);
        }
    }

    public record CreateTitlePageRequest(ParentReference parent, TitlePageProperties properties) {
    }

    public record UpdateTitlePageRequest(TitlePageProperties properties) {
    }

    public record CreateWorkItemPageRequest(ParentReference parent, WorkItemProperties properties) {
    }

    public record UpdateWorkItemPageRequest(WorkItemProperties properties) {
    }

    /**
     * Typed value object for the fixed title property used by workspace and project
     * pages.
     */
    public record TitlePageProperties(TitleProperty title) {
    }

    /**
     * Typed values for the fixed work-item database schema used by this
     * integration.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkItemProperties(
            TitleProperty name,
            @JsonProperty("identifier") RichTextProperty identifier,
            @JsonProperty(PropertyNames.WORK_ITEM_ID) RichTextProperty workItemId,
            @JsonProperty("work_item_url") UrlProperty workItemUrl,
            RichTextProperty description,
            SelectProperty state,
            SelectProperty priority,
            RichTextProperty assignees,
            @JsonProperty("labels") MultiSelectProperty labels,
            @JsonProperty("due_date") DateProperty dueDate,
            @JsonProperty("plane_updated_at") DateProperty planeUpdatedAt,
            @JsonProperty("plane_lifecycle") SelectProperty planeLifecycle,
            @JsonProperty("workspace_slug") RichTextProperty workspaceSlug,
            @JsonProperty("project_id") RichTextProperty projectId,
            @JsonProperty("project_identifier") RichTextProperty projectIdentifier,
            @JsonProperty("project_name") RichTextProperty projectName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TitleProperty(List<RichText> title) {
        public static TitleProperty of(List<RichText> value) {
            return new TitleProperty(value);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RichTextProperty(@JsonProperty("rich_text") List<RichText> richText) {
        public static RichTextProperty of(List<RichText> value) {
            return new RichTextProperty(value);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UrlProperty(String url) {
        public static UrlProperty of(String value) {
            return new UrlProperty(value);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SelectProperty(NamedOption select) {
        public static SelectProperty of(NamedOption value) {
            return new SelectProperty(value);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MultiSelectProperty(@JsonProperty("multi_select") List<NamedOption> multiSelect) {
        public static MultiSelectProperty of(List<NamedOption> value) {
            return new MultiSelectProperty(value);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DateProperty(DateValue date) {
        public static DateProperty of(DateValue value) {
            return new DateProperty(value);
        }
    }

    public record MovePageRequest(ParentReference parent) {
    }

    public record CreateDatabaseRequest(
            ParentReference parent,
            List<RichText> title,
            @JsonProperty("is_inline") Boolean isInline,
            @JsonProperty("initial_data_source") InitialDataSource initialDataSource) {
    }

    public record InitialDataSource(WorkItemSchema properties) {
    }

    public record UpdateDataSourceRequest(WorkItemSchemaUpdates properties) {
    }

    /** Typed schema fields used to create the fixed work-item data source. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkItemSchema(
            PropertyDefinition name,
            PropertyDefinition identifier,
            @JsonProperty(PropertyNames.WORK_ITEM_ID) PropertyDefinition workItemId,
            @JsonProperty("work_item_url") PropertyDefinition workItemUrl,
            PropertyDefinition description,
            PropertyDefinition state,
            PropertyDefinition priority,
            PropertyDefinition assignees,
            PropertyDefinition labels,
            @JsonProperty("due_date") PropertyDefinition dueDate,
            @JsonProperty("plane_updated_at") PropertyDefinition planeUpdatedAt,
            @JsonProperty("plane_lifecycle") PropertyDefinition planeLifecycle,
            @JsonProperty("workspace_slug") PropertyDefinition workspaceSlug,
            @JsonProperty("project_id") PropertyDefinition projectId,
            @JsonProperty("project_identifier") PropertyDefinition projectIdentifier,
            @JsonProperty("project_name") PropertyDefinition projectName) {
    }

    /**
     * Typed subset of schema fields updated when Plane adds new state or label
     * options.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkItemSchemaUpdates(PropertyUpdate state, PropertyUpdate labels) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyUpdate(SelectSchema select, @JsonProperty("multi_select") MultiSelectSchema multiSelect) {
    }

    public record QueryDataSourceRequest(
            @JsonProperty("page_size") Integer pageSize,
            Filter filter) {
    }

    public record Filter(String property, @JsonProperty("rich_text") RichTextFilter richText) {
    }

    public record RichTextFilter(String equals) {
    }

    /**
     * The property names are dynamic options, but each supported schema type is
     * explicit.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyDefinition(
            EmptySchema title,
            @JsonProperty("rich_text") EmptySchema richText,
            EmptySchema url,
            SelectSchema select,
            @JsonProperty("multi_select") MultiSelectSchema multiSelect,
            EmptySchema date) {

        public static PropertyDefinition titleDefinition() {
            return new PropertyDefinition(new EmptySchema(), null, null, null, null, null);
        }

        public static PropertyDefinition richTextDefinition() {
            return new PropertyDefinition(null, new EmptySchema(), null, null, null, null);
        }

        public static PropertyDefinition urlDefinition() {
            return new PropertyDefinition(null, null, new EmptySchema(), null, null, null);
        }

        public static PropertyDefinition select(List<NamedOption> options) {
            return new PropertyDefinition(null, null, null, new SelectSchema(options), null, null);
        }

        public static PropertyDefinition multiSelect(List<NamedOption> options) {
            return new PropertyDefinition(null, null, null, null, new MultiSelectSchema(options), null);
        }

        public static PropertyDefinition dateDefinition() {
            return new PropertyDefinition(null, null, null, null, null, new EmptySchema());
        }
    }

    public record EmptySchema() {
    }

    public record SelectSchema(List<NamedOption> options) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MultiSelectSchema(List<NamedOption> options) {
    }

    public record NamedOption(String name) {
    }

    public record DateValue(String start) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RichText(String type, TextValue text, Annotations annotations) {

        public static RichText plain(String content) {
            return new RichText("text", new TextValue(content), null);
        }

        public static RichText bold(String content) {
            return new RichText("text", new TextValue(content), new Annotations(true));
        }
    }

    public record TextValue(String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Annotations(Boolean bold) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppendBlockChildrenRequest(
            List<Block> children,
            Position position) {
    }

    public record Position(String type, @JsonProperty("after_block") AfterBlock afterBlock) {
    }

    public record AfterBlock(String id) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(
            String object,
            String type,
            @JsonProperty("heading_2") Heading heading2,
            Callout callout,
            Table table,
            @JsonProperty("table_row") TableRow tableRow) {

        public static Block heading(String text) {
            return new Block("block", "heading_2", new Heading(List.of(RichText.plain(text))), null, null, null);
        }

        public static Block callout(String text) {
            return new Block("block", "callout", null,
                    new Callout(List.of(RichText.plain(text)), new Icon("emoji", "📊")), null, null);
        }

        public static Block table(int width, List<TableRow> children) {
            return new Block("block", "table", null, null,
                    new Table(width, true, false,
                            children.stream().map(Block::tableRow).toList()),
                    null);
        }

        public static Block tableRow(TableRow row) {
            return new Block("block", "table_row", null, null, null, row);
        }
    }

    public record Heading(@JsonProperty("rich_text") List<RichText> richText) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Callout(@JsonProperty("rich_text") List<RichText> richText, Icon icon) {
    }

    public record Icon(String type, String emoji) {
    }

    public record Table(
            @JsonProperty("table_width") int tableWidth,
            @JsonProperty("has_column_header") boolean hasColumnHeader,
            @JsonProperty("has_row_header") boolean hasRowHeader,
            List<Block> children) {
    }

    public record TableRow(List<List<RichText>> cells) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateBlockRequest(
            @JsonProperty("heading_2") Heading heading2,
            Callout callout,
            @JsonProperty("table_row") TableRow tableRow,
            @JsonProperty("in_trash") Boolean inTrash) {

        public static UpdateBlockRequest heading(String text) {
            return new UpdateBlockRequest(new Heading(List.of(RichText.plain(text))), null, null, null);
        }

        public static UpdateBlockRequest callout(String text) {
            return new UpdateBlockRequest(null,
                    new Callout(List.of(RichText.plain(text)), null), null, null);
        }

        public static UpdateBlockRequest tableRow(TableRow row) {
            return new UpdateBlockRequest(null, null, row, null);
        }

        public static UpdateBlockRequest trash() {
            return new UpdateBlockRequest(null, null, null, true);
        }
    }
}
