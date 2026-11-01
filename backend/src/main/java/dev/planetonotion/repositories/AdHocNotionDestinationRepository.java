package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdHocNotionDestinationRepository extends MongoRepository<AdHocNotionDestinationDocument, String> {
}
