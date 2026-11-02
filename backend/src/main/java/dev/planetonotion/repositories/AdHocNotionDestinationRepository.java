package dev.planetonotion.repositories;

import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import java.util.Optional;

/** Persistence operations for the shared manual-only Notion destination. */
public interface AdHocNotionDestinationRepository {

    /** Finds the shared destination used by manual sync jobs. */
    Optional<AdHocNotionDestinationDocument> findById(String id);

    /** Saves the shared manual-sync destination. */
    AdHocNotionDestinationDocument save(AdHocNotionDestinationDocument destination);
}
