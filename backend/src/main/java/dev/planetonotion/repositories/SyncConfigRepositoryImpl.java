package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** MongoTemplate implementation for sync configuration persistence. */
@Repository
public class SyncConfigRepositoryImpl implements SyncConfigRepository {

    private final MongoTemplate mongoTemplate;

    /** Creates the repository with the MongoDB template used for persistence. */
    public SyncConfigRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SyncConfigDocument> findByPlaneProjectDocumentId(ObjectId planeProjectDocumentId) {
        Query query = Query.query(Criteria.where(SyncConfigDocument.FIELD_PLANE_PROJECT_DOCUMENT_ID)
                .is(planeProjectDocumentId));
        return Optional.ofNullable(mongoTemplate.findOne(query, SyncConfigDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByPlaneProjectDocumentId(ObjectId planeProjectDocumentId) {
        Query query = Query.query(Criteria.where(SyncConfigDocument.FIELD_PLANE_PROJECT_DOCUMENT_ID)
                .is(planeProjectDocumentId));
        return mongoTemplate.exists(query, SyncConfigDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public List<SyncConfigDocument> findAll(MongoQueryOptions options) {
        Query query = MongoQueryUtils.toQuery(options);
        return mongoTemplate.find(query, SyncConfigDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SyncConfigDocument> findById(ObjectId id) {
        return Optional.ofNullable(mongoTemplate.findById(id, SyncConfigDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public SyncConfigDocument save(SyncConfigDocument config) {
        return mongoTemplate.save(config);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(ObjectId id) {
        mongoTemplate.remove(Query.query(Criteria.where(SyncConfigDocument.FIELD_ID).is(id)), SyncConfigDocument.class);
    }
}
