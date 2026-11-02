package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.SyncMappingDocument;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * MongoTemplate implementation for the mapping persistence operations used by
 * the app.
 */
@Repository
public class SyncMappingRepositoryImpl implements SyncMappingRepository {

    private static final String GROUPED_DATA_SOURCE_ID_FIELD = "_id";
    private static final String COUNT_ALIAS = "count";

    private final MongoTemplate mongoTemplate;

    /** Creates the repository with the MongoDB template used for persistence. */
    public SyncMappingRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public List<SyncMappingDocument> findByNotionDataSourceIdAndPlaneWorkItemIdIn(
            String notionDataSourceId, Collection<String> planeWorkItemIds) {
        if (planeWorkItemIds.isEmpty())
            return List.of();
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where(SyncMappingDocument.FIELD_NOTION_DATA_SOURCE_ID).is(notionDataSourceId),
                Criteria.where(SyncMappingDocument.FIELD_PLANE_WORK_ITEM_ID).in(planeWorkItemIds)));
        return mongoTemplate.find(query, SyncMappingDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public void bulkUpsert(List<SyncMappingDocument> mappings) {
        if (mappings.isEmpty())
            return;
        BulkOperations operations = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, SyncMappingDocument.class);
        mappings.forEach(mapping -> {
            Query query = Query.query(new Criteria().andOperator(
                    Criteria.where(SyncMappingDocument.FIELD_NOTION_DATA_SOURCE_ID).is(mapping.notionDataSourceId()),
                    Criteria.where(SyncMappingDocument.FIELD_PLANE_WORK_ITEM_ID).is(mapping.planeWorkItemId())));
            Update update = new Update()
                    .set(SyncMappingDocument.FIELD_NOTION_PAGE_ID, mapping.notionPageId())
                    .set(SyncMappingDocument.FIELD_PLANE_SOURCE_HASH, mapping.planeSourceHash())
                    .setOnInsert(SyncMappingDocument.FIELD_NOTION_DATA_SOURCE_ID, mapping.notionDataSourceId())
                    .setOnInsert(SyncMappingDocument.FIELD_PLANE_WORK_ITEM_ID, mapping.planeWorkItemId());
            operations.upsert(query, update);
        });
        operations.execute();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Long> countByNotionDataSourceIds(Collection<String> notionDataSourceIds) {
        if (notionDataSourceIds.isEmpty())
            return Map.of();
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria
                        .where(SyncMappingDocument.FIELD_NOTION_DATA_SOURCE_ID).in(notionDataSourceIds)
                        .and(SyncMappingDocument.FIELD_NOTION_PAGE_ID).ne(null)),
                Aggregation.group(SyncMappingDocument.FIELD_NOTION_DATA_SOURCE_ID).count().as(COUNT_ALIAS));
        AggregationResults<Document> results = mongoTemplate.aggregate(
                aggregation, SyncMappingDocument.class, Document.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        results.getMappedResults().forEach(result -> counts.put(result.getString(GROUPED_DATA_SOURCE_ID_FIELD),
                result.get(COUNT_ALIAS, Number.class).longValue()));
        return counts;
    }
}
