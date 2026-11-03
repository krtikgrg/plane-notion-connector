package dev.planetonotion.integrations.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import dev.planetonotion.integrations.models.NotionRequestModels.NamedOption;
import dev.planetonotion.integrations.models.NotionRequestModels.ParentReference;

/**
 * Typed response records for the Notion API fields consumed by this
 * integration.
 * These are intentionally partial: they model only the response fields the
 * application reads, not every field Notion may return.
 */
public final class NotionResponseModels {

    private NotionResponseModels() {
    }

    /** Page identifier, parent location, and the title property read by the app. */
    public record Page(
            String id,
            ParentReference parent,
            PageProperties properties) {
    }

    /** Properties from a page response that this integration inspects. */
    public record PageProperties(PageProperty title) {
    }

    /** Rich-text entries for a page's title property. */
    public record PageProperty(List<RichTextResponse> title) {
    }

    /** Database ID and the data-source IDs returned when a database is created. */
    public record Database(String id, @JsonProperty("data_sources") List<DataSourceSummary> dataSources) {
    }

    /** Data-source identifier returned as part of a database response. */
    public record DataSourceSummary(String id) {
    }

    /**
     * Current data-source schema fields inspected to synchronize select options.
     */
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

    /**
     * Select and multi-select definitions the app reads from a work-item schema.
     */
    public record PropertySchema(
            SelectOptions select,
            @JsonProperty("multi_select") SelectOptions multiSelect) {
    }

    /** Available option names for a select or multi-select property. */
    public record SelectOptions(List<NamedOption> options) {
    }

    /** Matching pages returned by a data-source query. */
    public record PageQuery(List<Page> results) {
    }

    /** One paginated response of child blocks, including continuation details. */
    public record BlockList(
            List<Block> results,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    /** Blocks created by an append-children request. */
    public record BlockAppend(List<Block> results) {
    }

    /** Block ID, type, and supported content used by summary synchronization. */
    public record Block(
            String id,
            String type,
            @JsonProperty("heading_2") RichTextContent heading2,
            Paragraph paragraph,
            Callout callout,
            Table table,
            @JsonProperty("table_row") TableRow tableRow) {
    }

    /** Plain, unformatted text extracted from a Notion rich-text response item. */
    public record RichTextResponse(@JsonProperty("plain_text") String plainText) {
    }

    /** Rich-text fragments inside a heading or other rich-text block. */
    public record RichTextContent(@JsonProperty("rich_text") List<RichTextResponse> richText) {
    }

    /** Plain-text fragments inside a paragraph block. */
    public record Paragraph(@JsonProperty("rich_text") List<RichTextResponse> richText) {
    }

    /** Plain-text fragments inside a callout block. */
    public record Callout(@JsonProperty("rich_text") List<RichTextResponse> richText) {
    }

    /** Table dimensions and header settings consumed by summary replacement. */
    public record Table(
            @JsonProperty("table_width") Integer tableWidth,
            @JsonProperty("has_column_header") Boolean hasColumnHeader,
            @JsonProperty("has_row_header") Boolean hasRowHeader) {
    }

    /** Cell text for one row of a summary table. */
    public record TableRow(List<List<RichTextResponse>> cells) {
    }
}
