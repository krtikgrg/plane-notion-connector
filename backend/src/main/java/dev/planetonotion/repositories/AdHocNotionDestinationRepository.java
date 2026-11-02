package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import java.util.Optional;

/** Persistence operations for the shared manual-only Notion destination. */
public interface AdHocNotionDestinationRepository {

    Optional<AdHocNotionDestinationDocument> findById(String id);

    AdHocNotionDestinationDocument save(AdHocNotionDestinationDocument destination);
}
