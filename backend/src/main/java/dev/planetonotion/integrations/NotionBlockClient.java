package dev.planetonotion.integrations;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.common.PlaneToNotionEnums.IntegrationProvider;
import dev.planetonotion.integrations.models.NotionRequestModels.AfterBlock;
import dev.planetonotion.integrations.models.NotionRequestModels.AppendBlockChildrenRequest;
import dev.planetonotion.integrations.models.NotionRequestModels.Block;
import dev.planetonotion.integrations.models.NotionRequestModels.Position;
import dev.planetonotion.integrations.models.NotionRequestModels.RichText;
import dev.planetonotion.integrations.models.NotionRequestModels.TableRow;
import dev.planetonotion.integrations.models.NotionRequestModels.UpdateBlockRequest;
import dev.planetonotion.integrations.models.NotionResponseModels.BlockAppend;
import dev.planetonotion.integrations.models.NotionResponseModels.BlockList;
import dev.planetonotion.integrations.models.NotionResponseModels;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import static dev.planetonotion.integrations.NotionApiConstants.Headers.*;
import static dev.planetonotion.integrations.NotionApiConstants.Paths.BLOCKS;
import static dev.planetonotion.integrations.NotionApiConstants.Paths.CHILDREN;
import static dev.planetonotion.integrations.NotionApiConstants.QueryParameters.PAGE_SIZE;
import static dev.planetonotion.integrations.NotionApiConstants.QueryParameters.START_CURSOR;
import static dev.planetonotion.integrations.NotionApiConstants.VERSION;

/**
 * Creates and synchronizes Notion block content used for workspace and project
 * summaries, including their callouts and tabular work-item summaries.
 */
@Component
public class NotionBlockClient {

    private static final int NOTION_BLOCK_APPEND_BATCH_SIZE = 100;

    private final RestClient client;
    private final HttpService httpService;
    private final IntegrationProperties properties;

    /**
     * Creates the client using the configured Notion API settings and shared HTTP
     * service.
     */
    @Autowired
    public NotionBlockClient(IntegrationProperties properties, HttpService httpService,
            IntegrationRestClientFactory clientFactory) {
        this(properties, clientFactory.builder(properties.notionApiBaseUrl().toString()), httpService);
    }

    /** Creates a client with a supplied builder for integration tests. */
    NotionBlockClient(IntegrationProperties properties, RestClient.Builder builder) {
        this(properties, builder, new HttpService());
    }

    /** Configures the API base URL, authorization header, and API version. */
    private NotionBlockClient(IntegrationProperties properties, RestClient.Builder builder, HttpService httpService) {
        this.properties = properties;
        this.httpService = httpService;
        this.client = builder.baseUrl(properties.notionApiBaseUrl().toString())
                .defaultHeader(AUTHORIZATION, BEARER_PREFIX + properties.notionConnectionToken())
                .defaultHeader(NOTION_VERSION, VERSION)
                .build();
    }

    /**
     * Creates or updates the summary section on a Notion page. The section has a
     * heading, a callout, and a table; existing rows are updated, appended,
     * removed, or rebuilt as needed to match the supplied values.
     *
     * @param pageId         Notion page whose block children contain the summary
     * @param heading        current section heading
     * @param legacyHeadings earlier headings that identify the same section
     * @param summaryPrefix  prefix used to find obsolete summary paragraphs
     * @param summary        text displayed in the section callout
     * @param columns        table header labels
     * @param rows           table data rows, with values in column order
     */
    public void upsertPageTableSummary(String pageId, String heading, List<String> legacyHeadings,
            String summaryPrefix, String summary, List<String> columns, List<List<String>> rows) {
        List<NotionResponseModels.Block> pageBlocks = readChildren(pageId);
        var sectionHeading = pageBlocks.stream()
                .filter(block -> isHeading(block) && (heading.equals(blockText(block))
                        || legacyHeadings.contains(blockText(block))))
                .findFirst().orElse(null);

        if (sectionHeading == null) {
            sectionHeading = appendBlocks(pageId, List.of(Block.heading(heading))).results().get(0);
            pageBlocks = readChildren(pageId);
        } else if (!heading.equals(blockText(sectionHeading))) {
            patch(sectionHeading.id(), UpdateBlockRequest.heading(heading));
        }

        List<NotionResponseModels.Block> section = sectionBlocks(pageBlocks, sectionHeading.id());
        var callout = section.stream().filter(block -> "callout".equals(block.type())).findFirst().orElse(null);
        var table = section.stream().filter(block -> "table".equals(block.type())).findFirst().orElse(null);

        for (var block : section) {
            if ("paragraph".equals(block.type()) && blockText(block).startsWith(summaryPrefix))
                trashBlock(block);
        }

        if (callout == null) {
            callout = appendBlocks(pageId, List.of(Block.callout(summary)), sectionHeading.id()).results().get(0);
        } else if (!summary.equals(blockText(callout))) {
            patch(callout.id(), UpdateBlockRequest.callout(summary));
        }

        boolean tableWidthChanged = table != null && table.table() != null
                && table.table().tableWidth() != null && table.table().tableWidth() != columns.size();
        synchronizeTable(pageId, callout.id(), table, columns, rows, tableWidthChanged);
    }

    /**
     * Archives the Notion page whose block children are identified by
     * {@code pageId}.
     */
    public void archivePage(String pageId) {
        requireToken();
        httpService.delete(client, IntegrationProvider.NOTION, BLOCKS + segment(pageId),
                ApiErrorCodes.Notion.ARCHIVE_FAILED, "Notion could not archive the temporary project page.");
    }

    /** Reads every child block, following Notion's cursor pagination. */
    private List<NotionResponseModels.Block> readChildren(String blockId) {
        List<NotionResponseModels.Block> blocks = new ArrayList<>();
        String cursor = null;
        do {
            String path = BLOCKS + segment(blockId) + CHILDREN + "?" + PAGE_SIZE + "="
                    + NotionApiConstants.BLOCKS_PAGE_SIZE;
            if (cursor != null)
                path += "&" + START_CURSOR + "=" + segment(cursor);
            BlockList response = get(path, BlockList.class);
            if (response.results() != null)
                blocks.addAll(response.results());
            cursor = response.hasMore() != null && response.hasMore() ? response.nextCursor() : null;
        } while (cursor != null && !cursor.isBlank());
        return blocks;
    }

    /** Returns the blocks after a section heading up to the next heading. */
    private List<NotionResponseModels.Block> sectionBlocks(
            List<NotionResponseModels.Block> blocks, String headingId) {
        int start = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (headingId.equals(blocks.get(i).id())) {
                start = i;
                break;
            }
        }
        if (start < 0)
            return List.of();
        List<NotionResponseModels.Block> result = new ArrayList<>();
        for (int i = start + 1; i < blocks.size(); i++) {
            var block = blocks.get(i);
            if (isHeading(block))
                break;
            result.add(block);
        }
        return result;
    }

    /** Returns whether the block is one of Notion's heading block types. */
    private boolean isHeading(NotionResponseModels.Block block) {
        return block.type() != null && block.type().startsWith("heading_");
    }

    /** Joins the visible rich-text content for supported summary block types. */
    private String blockText(NotionResponseModels.Block block) {
        List<NotionResponseModels.RichTextResponse> text = switch (block.type()) {
            case "heading_2" -> block.heading2() == null ? List.of() : block.heading2().richText();
            case "paragraph" -> block.paragraph() == null ? List.of() : block.paragraph().richText();
            case "callout" -> block.callout() == null ? List.of() : block.callout().richText();
            default -> List.of();
        };
        if (text == null)
            return "";
        StringBuilder result = new StringBuilder();
        text.forEach(part -> result.append(part.plainText() == null ? "" : part.plainText()));
        return result.toString();
    }

    /** Builds a Notion table row, bolding each cell when it is the header row. */
    private TableRow tableRow(List<String> cells, boolean header) {
        return new TableRow(cells.stream().map(cell -> List.of(header ? RichText.bold(cell) : RichText.plain(cell)))
                .toList());
    }

    /** Prepends the column labels as the first row of the desired table. */
    private List<List<String>> withHeader(List<String> columns, List<List<String>> rows) {
        List<List<String>> result = new ArrayList<>();
        result.add(columns);
        result.addAll(rows);
        return result;
    }

    /** Chooses row-level updates or a full table rebuild based on the changes. */
    private void synchronizeTable(String pageId, String afterBlockId,
            NotionResponseModels.Block existingTable,
            List<String> columns, List<List<String>> rows, boolean tableWidthChanged) {
        List<List<String>> desiredRows = withHeader(columns, rows);
        if (existingTable == null || tableWidthChanged) {
            rebuildTable(pageId, afterBlockId, existingTable, columns.size(), desiredRows);
            return;
        }

        List<NotionResponseModels.Block> existingRows = readChildren(existingTable.id());
        int overlappingRows = Math.min(existingRows.size(), desiredRows.size());
        List<Integer> changedRows = new ArrayList<>();
        for (int i = 0; i < overlappingRows; i++) {
            if (!rowMatches(existingRows.get(i), desiredRows.get(i)))
                changedRows.add(i);
        }

        int additionCount = Math.max(0, desiredRows.size() - existingRows.size());
        int deletionCount = Math.max(0, existingRows.size() - desiredRows.size());
        int inPlaceWriteCount = changedRows.size()
                + batchesFor(additionCount)
                + deletionCount;
        int rebuildWriteCount = 1 + batchesFor(Math.max(0, desiredRows.size() - NOTION_BLOCK_APPEND_BATCH_SIZE)) + 1;
        if (inPlaceWriteCount <= rebuildWriteCount) {
            updateChangedRows(existingRows, desiredRows, changedRows);
            appendMissingRows(existingTable.id(), existingRows.size(), desiredRows);
            removeExtraRows(existingRows, desiredRows.size());
            return;
        }

        rebuildTable(pageId, afterBlockId, existingTable, columns.size(), desiredRows);
    }

    /** Patches only rows whose cell contents differ from the desired table. */
    private void updateChangedRows(
            List<NotionResponseModels.Block> existingRows,
            List<List<String>> desiredRows, List<Integer> changedRows) {
        for (int index : changedRows)
            patch(existingRows.get(index).id(),
                    UpdateBlockRequest.tableRow(tableRow(desiredRows.get(index), index == 0)));
    }

    /**
     * Appends desired rows that do not yet exist, respecting Notion's batch limit.
     */
    private void appendMissingRows(String tableId, int existingRowCount, List<List<String>> desiredRows) {
        if (desiredRows.size() <= existingRowCount)
            return;
        List<Block> additions = new ArrayList<>();
        for (int i = existingRowCount; i < desiredRows.size(); i++)
            additions.add(Block.tableRow(tableRow(desiredRows.get(i), i == 0)));
        appendBatched(tableId, additions);
    }

    /** Archives rows beyond the desired table length. */
    private void removeExtraRows(
            List<NotionResponseModels.Block> existingRows,
            int desiredRowCount) {
        for (int i = desiredRowCount; i < existingRows.size(); i++)
            trashBlock(existingRows.get(i));
    }

    /**
     * Creates a replacement table and archives the previous table after success.
     */
    private void rebuildTable(String pageId, String afterBlockId,
            NotionResponseModels.Block oldTable,
            int width, List<List<String>> desiredRows) {
        int firstBatchSize = Math.min(desiredRows.size(), NOTION_BLOCK_APPEND_BATCH_SIZE);
        List<TableRow> firstRows = tableRows(desiredRows.subList(0, firstBatchSize));
        Block tableBlock = Block.table(width, firstRows);
        BlockAppend response = appendBlocks(pageId, List.of(tableBlock), afterBlockId);
        if (response.results() == null || response.results().isEmpty()
                || response.results().get(0).id() == null || response.results().get(0).id().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.RESPONSE_INVALID, 502,
                    "Notion did not return the rebuilt summary table ID.");
        }
        if (desiredRows.size() > firstBatchSize) {
            List<Block> additionalRows = desiredRows.subList(firstBatchSize, desiredRows.size()).stream()
                    .map(row -> Block.tableRow(tableRow(row, false)))
                    .toList();
            appendBatched(response.results().get(0).id(), additionalRows);
        }
        if (oldTable != null)
            trashBlock(oldTable);
    }

    /** Converts table values to Notion rows, marking only the first as a header. */
    private List<TableRow> tableRows(List<List<String>> values) {
        List<TableRow> result = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++)
            result.add(tableRow(values.get(i), i == 0));
        return result;
    }

    /**
     * Compares an existing Notion table row's visible cell text with desired
     * values.
     */
    private boolean rowMatches(
            NotionResponseModels.Block existing,
            List<String> desired) {
        if (existing.tableRow() == null || existing.tableRow().cells() == null
                || existing.tableRow().cells().size() != desired.size())
            return false;
        for (int cellIndex = 0; cellIndex < desired.size(); cellIndex++) {
            List<NotionResponseModels.RichTextResponse> cell = existing.tableRow().cells().get(cellIndex);
            String content = cell == null ? ""
                    : cell.stream()
                            .map(part -> part.plainText() == null ? "" : part.plainText())
                            .collect(Collectors.joining());
            if (!desired.get(cellIndex).equals(content))
                return false;
        }
        return true;
    }

    /** Returns how many append requests are needed for the given block count. */
    private int batchesFor(int numberOfBlocks) {
        return (numberOfBlocks + NOTION_BLOCK_APPEND_BATCH_SIZE - 1) / NOTION_BLOCK_APPEND_BATCH_SIZE;
    }

    /** Appends blocks in batches no larger than Notion's supported limit. */
    private void appendBatched(String parentId, List<Block> blocks) {
        for (int start = 0; start < blocks.size(); start += NOTION_BLOCK_APPEND_BATCH_SIZE) {
            int end = Math.min(blocks.size(), start + NOTION_BLOCK_APPEND_BATCH_SIZE);
            appendBlocks(parentId, blocks.subList(start, end));
        }
    }

    /** Archives a block by setting its Notion archived state. */
    private void trashBlock(NotionResponseModels.Block block) {
        patch(block.id(), UpdateBlockRequest.trash());
    }

    /** Appends blocks to a parent without specifying a sibling position. */
    private BlockAppend appendBlocks(String parentId, List<Block> blocks) {
        return appendBlocks(parentId, blocks, null);
    }

    /** Appends blocks to a parent, optionally placing them after a given block. */
    private BlockAppend appendBlocks(String parentId, List<Block> blocks, String afterBlockId) {
        Position position = afterBlockId == null ? null : new Position("after_block", new AfterBlock(afterBlockId));
        return patchForResponse(BLOCKS + segment(parentId) + CHILDREN,
                new AppendBlockChildrenRequest(blocks, position), BlockAppend.class);
    }

    /**
     * Sends a Notion GET request after verifying that the connection token exists.
     */
    private <T> T get(String path, Class<T> responseType) {
        requireToken();
        return httpService.get(client, IntegrationProvider.NOTION, path, responseType);
    }

    /** Sends a Notion PATCH request for a block when no response body is needed. */
    private void patch(String blockId, Object body) {
        requireToken();
        httpService.patchWithoutResponse(client, IntegrationProvider.NOTION, BLOCKS + segment(blockId), body);
    }

    /** Sends a Notion PATCH request and decodes its response body. */
    private <T> T patchForResponse(String path, Object body, Class<T> responseType) {
        requireToken();
        return httpService.patch(client, IntegrationProvider.NOTION, path, body, responseType);
    }

    /** Fails early when no Notion connection token is configured. */
    private void requireToken() {
        if (properties.notionConnectionToken() == null || properties.notionConnectionToken().isBlank()) {
            throw new IntegrationException(ApiErrorCodes.Notion.TOKEN_MISSING, 0,
                    ApiErrorMessages.Notion.TOKEN_MISSING);
        }
    }

    /** Encodes a value so it can safely occupy one URL path segment. */
    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
