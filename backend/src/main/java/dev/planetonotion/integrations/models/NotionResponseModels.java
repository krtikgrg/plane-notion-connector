package dev.planetonotion.integrations.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import dev.planetonotion.integrations.models.NotionRequestModels.NamedOption;
import dev.planetonotion.integrations.models.NotionRequestModels.ParentReference;

/**
 * Typed response records for the Notion API fields consumed by this
 * integration.
 */
public final class NotionResponseModels {

    private NotionResponseModels() {
    }

    public record Page(
            String id,
            ParentReference parent,
            Map<String, PageProperty> properties) {
    }

    public record PageProperty(List<RichTextResponse> title) {
    }

    public record Database(String id, @JsonProperty("data_sources") List<DataSourceSummary> dataSources) {
    }

    public record DataSourceSummary(String id) {
    }

    public record DataSource(WorkItemDataSourceSchema properties) {
    }

    /**
     * Typed subset of the known work-item data-source schema consumed by this
     * integration.
     */
    public record WorkItemDataSourceSchema(
            PropertySchema state,
            PropertySchema labels) {
    }

    public record PropertySchema(
            SelectOptions select,
            @JsonProperty("multi_select") SelectOptions multiSelect) {
    }

    public record SelectOptions(List<NamedOption> options) {
    }

    public record PageQuery(List<Page> results) {
    }

    public record BlockList(
            List<Block> results,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    public record BlockAppend(List<Block> results) {
    }

    public record Block(
            String id,
            String type,
            @JsonProperty("heading_2") RichTextContent heading2,
            Paragraph paragraph,
            Callout callout,
            Table table,
            @JsonProperty("table_row") TableRow tableRow) {
    }

    public record RichTextResponse(@JsonProperty("plain_text") String plainText) {
    }

    public record RichTextContent(@JsonProperty("rich_text") List<RichTextResponse> richText) {
    }

    public record Paragraph(@JsonProperty("rich_text") List<RichTextResponse> richText) {
    }

    public record Callout(@JsonProperty("rich_text") List<RichTextResponse> richText) {
    }

    public record Table(
            @JsonProperty("table_width") Integer tableWidth,
            @JsonProperty("has_column_header") Boolean hasColumnHeader,
            @JsonProperty("has_row_header") Boolean hasRowHeader) {
    }

    public record TableRow(List<List<RichTextResponse>> cells) {
    }
}
