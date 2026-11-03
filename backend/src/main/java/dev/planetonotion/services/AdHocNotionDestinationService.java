package dev.planetonotion.services;

import dev.planetonotion.integrations.NotionBlockClient;
import dev.planetonotion.integrations.NotionClient.ProjectDestination;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import dev.planetonotion.repositories.AdHocNotionDestinationRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Finds or provisions the shared Notion destination used for work items that do
 * not match a configured Plane project.
 */
@Service
public class AdHocNotionDestinationService {

    private final AdHocNotionDestinationRepository destinationRepository;
    private final NotionClient notionClient;
    private final NotionBlockClient notionBlockClient;

    /**
     * Supplies persistence and Notion clients used to provision the destination.
     */
    public AdHocNotionDestinationService(AdHocNotionDestinationRepository destinationRepository,
            NotionClient notionClient, NotionBlockClient notionBlockClient) {
        this.destinationRepository = destinationRepository;
        this.notionClient = notionClient;
        this.notionBlockClient = notionBlockClient;
    }

    /**
     * Returns the stored shared destination, creating it on Notion and storing
     * its identifiers if no destination record exists.
     *
     * @return the shared destination document
     */
    public synchronized AdHocNotionDestinationDocument getOrCreate() {
        return destinationRepository.findById(AdHocNotionDestinationDocument.SHARED_DESTINATION_ID)
                .orElseGet(this::createAndStore);
    }

    /**
     * Provisions the shared Notion destination and saves its IDs in MongoDB. If
     * saving fails, attempts to archive the newly created Notion page and adds
     * any cleanup failure as a suppressed exception.
     */
    private AdHocNotionDestinationDocument createAndStore() {
        ProjectDestination destination = notionClient.createAdHocDestination();

        Instant now = Instant.now();
        AdHocNotionDestinationDocument document = new AdHocNotionDestinationDocument(
                AdHocNotionDestinationDocument.SHARED_DESTINATION_ID,
                destination.pageId(), destination.databaseId(), destination.dataSourceId(), now, now);
        try {
            return destinationRepository.save(document);
        } catch (RuntimeException exception) {
            try {
                notionBlockClient.archivePage(destination.pageId());
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }
}
