package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * MongoTemplate implementation for the shared manual-only Notion destination.
 */
@Repository
public class AdHocNotionDestinationRepositoryImpl implements AdHocNotionDestinationRepository {

    private final MongoTemplate mongoTemplate;

    /** Creates the repository with the MongoDB template used for persistence. */
    public AdHocNotionDestinationRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AdHocNotionDestinationDocument> findById(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, AdHocNotionDestinationDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public AdHocNotionDestinationDocument save(AdHocNotionDestinationDocument destination) {
        return mongoTemplate.save(destination);
    }
}
