package dev.planetonotion.services;

import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.integrations.NotionClient.ProjectDestination;
import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import dev.planetonotion.repositories.AdHocNotionDestinationRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AdHocNotionDestinationService {

    private final AdHocNotionDestinationRepository destinationRepository;
    private final NotionClient notionClient;
    private final IntegrationProperties properties;

    public AdHocNotionDestinationService(AdHocNotionDestinationRepository destinationRepository,
            NotionClient notionClient, IntegrationProperties properties) {
        this.destinationRepository = destinationRepository;
        this.notionClient = notionClient;
        this.properties = properties;
    }

    public synchronized AdHocNotionDestinationDocument getOrCreate() {
        return destinationRepository.findById(AdHocNotionDestinationDocument.SHARED_DESTINATION_ID)
                .orElseGet(this::createAndStore);
    }

    private AdHocNotionDestinationDocument createAndStore() {
        ProjectDestination destination = legacyDestination();
        boolean createdByService = destination == null;
        if (createdByService) destination = notionClient.createAdHocDestination();

        Instant now = Instant.now();
        AdHocNotionDestinationDocument document = new AdHocNotionDestinationDocument(
                AdHocNotionDestinationDocument.SHARED_DESTINATION_ID,
                destination.pageId(), destination.databaseId(), destination.dataSourceId(), now, now);
        try {
            return destinationRepository.save(document);
        } catch (RuntimeException exception) {
            if (createdByService) {
                try {
                    notionClient.archivePage(destination.pageId());
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    private ProjectDestination legacyDestination() {
        String pageId = properties.legacyNotionAdHocPageId();
        String databaseId = properties.legacyNotionAdHocDatabaseId();
        String dataSourceId = properties.legacyNotionAdHocDataSourceId();
        boolean anyConfigured = hasText(pageId) || hasText(databaseId) || hasText(dataSourceId);
        if (!anyConfigured) return null;
        if (!hasText(pageId) || !hasText(databaseId) || !hasText(dataSourceId)) {
            throw new IllegalStateException("Legacy ad hoc Notion destination IDs are incomplete; provide all three IDs or remove them.");
        }
        return new ProjectDestination(pageId, databaseId, dataSourceId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
