package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** MongoTemplate implementation for sync configuration persistence. */
@Repository
public class SyncConfigRepositoryImpl implements SyncConfigRepository {

    private final MongoTemplate mongoTemplate;

    public SyncConfigRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<SyncConfigDocument> findByPlaneProjectDocumentId(ObjectId planeProjectDocumentId) {
        Query query = Query.query(Criteria.where("planeProjectDocumentId").is(planeProjectDocumentId));
        return Optional.ofNullable(mongoTemplate.findOne(query, SyncConfigDocument.class));
    }

    @Override
    public boolean existsByPlaneProjectDocumentId(ObjectId planeProjectDocumentId) {
        Query query = Query.query(Criteria.where("planeProjectDocumentId").is(planeProjectDocumentId));
        return mongoTemplate.exists(query, SyncConfigDocument.class);
    }

    @Override
    public List<SyncConfigDocument> findAllByOrderByCreatedAtDesc() {
        Query query = new Query().with(Sort.by(Sort.Order.desc("createdAt")));
        return mongoTemplate.find(query, SyncConfigDocument.class);
    }

    @Override
    public List<SyncConfigDocument> findAll() {
        return mongoTemplate.findAll(SyncConfigDocument.class);
    }

    @Override
    public Optional<SyncConfigDocument> findById(ObjectId id) {
        return Optional.ofNullable(mongoTemplate.findById(id, SyncConfigDocument.class));
    }

    @Override
    public SyncConfigDocument save(SyncConfigDocument config) {
        return mongoTemplate.save(config);
    }

    @Override
    public void deleteById(ObjectId id) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)), SyncConfigDocument.class);
    }
}
