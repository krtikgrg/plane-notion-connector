package dev.planetonotion.repositories.mappings;

import dev.planetonotion.models.documents.SyncMappingDocument;
import dev.planetonotion.common.MongoCollectionConstants;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

public class SyncMappingRepositoryImpl implements SyncMappingRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public SyncMappingRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void bulkUpsert(List<SyncMappingDocument> mappings) {
        if (mappings.isEmpty()) return;
        BulkOperations operations = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, SyncMappingDocument.class);
        mappings.forEach(mapping -> {
            Query query = Query.query(new Criteria().andOperator(
                    Criteria.where("target_data_source_id").is(mapping.targetDataSourceId()),
                    Criteria.where("source_id").is(mapping.sourceId())));
            Update update = new Update()
                    .set("target_id", mapping.targetId())
                    .set("source_hash", mapping.sourceHash())
                    .setOnInsert("target_data_source_id", mapping.targetDataSourceId())
                    .setOnInsert("source_id", mapping.sourceId());
            operations.upsert(query, update);
        });
        operations.execute();
    }

    @Override
    public Map<String, Long> countByTargetDataSourceIds(Collection<String> dataSourceIds) {
        if (dataSourceIds.isEmpty()) return Map.of();
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria
                        .where("target_data_source_id").in(dataSourceIds)
                        .and("target_id").ne(null)),
                Aggregation.group("target_data_source_id").count().as("count"));
        AggregationResults<org.bson.Document> results = mongoTemplate.aggregate(
                aggregation, MongoCollectionConstants.SYNC_MAPPINGS, org.bson.Document.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        results.getMappedResults().forEach(result -> counts.put(result.getString("_id"),
                result.get("count", Number.class).longValue()));
        return counts;
    }
}
