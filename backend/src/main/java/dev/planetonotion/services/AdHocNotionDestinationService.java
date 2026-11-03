package dev.planetonotion.services;

import dev.planetonotion.integrations.NotionBlockClient;
import dev.planetonotion.integrations.NotionClient.ProjectDestination;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import dev.planetonotion.repositories.AdHocNotionDestinationRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AdHocNotionDestinationService {

    private final AdHocNotionDestinationRepository destinationRepository;
    private final NotionClient notionClient;
    private final NotionBlockClient notionBlockClient;

    public AdHocNotionDestinationService(AdHocNotionDestinationRepository destinationRepository,
            NotionClient notionClient, NotionBlockClient notionBlockClient) {
        this.destinationRepository = destinationRepository;
        this.notionClient = notionClient;
        this.notionBlockClient = notionBlockClient;
    }

    public synchronized AdHocNotionDestinationDocument getOrCreate() {
        return destinationRepository.findById(AdHocNotionDestinationDocument.SHARED_DESTINATION_ID)
                .orElseGet(this::createAndStore);
    }

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
