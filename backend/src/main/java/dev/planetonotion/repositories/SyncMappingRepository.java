package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.SyncMappingDocument;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Persistence operations needed for Plane-to-Notion work-item mappings. */
public interface SyncMappingRepository {

    /**
     * Returns mappings in a Notion data source for the supplied Plane work-item
     * IDs.
     */
    List<SyncMappingDocument> findByNotionDataSourceIdAndPlaneWorkItemIdIn(
            String notionDataSourceId, Collection<String> planeWorkItemIds);

    /** Upserts mapping rows in one unordered MongoDB bulk operation. */
    void bulkUpsert(List<SyncMappingDocument> mappings);

    /** Counts mappings with a Notion page ID, grouped by Notion data source ID. */
    Map<String, Long> countByNotionDataSourceIds(Collection<String> notionDataSourceIds);
}
