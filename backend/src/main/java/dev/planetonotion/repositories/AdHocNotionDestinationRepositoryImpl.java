package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/** MongoTemplate implementation for the shared manual-only Notion destination. */
@Repository
public class AdHocNotionDestinationRepositoryImpl implements AdHocNotionDestinationRepository {

    private final MongoTemplate mongoTemplate;

    public AdHocNotionDestinationRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<AdHocNotionDestinationDocument> findById(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, AdHocNotionDestinationDocument.class));
    }

    @Override
    public AdHocNotionDestinationDocument save(AdHocNotionDestinationDocument destination) {
        return mongoTemplate.save(destination);
    }
}
