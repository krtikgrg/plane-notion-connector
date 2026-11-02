package dev.planetonotion.repositories.mappings;

import dev.planetonotion.models.documents.SyncMappingDocument;
import java.util.List;
import java.util.Collection;
import java.util.Map;

public interface SyncMappingRepositoryCustom {

    void bulkUpsert(List<SyncMappingDocument> mappings);

    Map<String, Long> countByNotionDataSourceIds(Collection<String> notionDataSourceIds);
}
