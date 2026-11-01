package dev.planetonotion.models.documents;

import dev.planetonotion.common.constants.MongoCollections;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = MongoCollections.SYNC_MAPPINGS)
@CompoundIndex(name = "destination_source_unique", def = "{'target_data_source_id': 1, 'source_id': 1}", unique = true)
public record SyncMappingDocument(
        @Id ObjectId id,
        @Field("source_id") String sourceId,
        @Field("target_id") String targetId,
        @Field("target_data_source_id") String targetDataSourceId,
        @Field("source_hash") String sourceHash) {
}
