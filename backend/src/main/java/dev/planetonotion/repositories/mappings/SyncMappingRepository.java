package dev.planetonotion.repositories.mappings;

import dev.planetonotion.models.documents.SyncMappingDocument;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SyncMappingRepository extends MongoRepository<SyncMappingDocument, ObjectId>, SyncMappingRepositoryCustom {

    Optional<SyncMappingDocument> findByTargetDataSourceIdAndSourceId(String targetDataSourceId, String sourceId);

    List<SyncMappingDocument> findByTargetDataSourceIdAndSourceIdIn(String targetDataSourceId, Collection<String> sourceIds);

}
