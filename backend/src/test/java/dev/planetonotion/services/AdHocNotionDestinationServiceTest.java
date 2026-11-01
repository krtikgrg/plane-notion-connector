package dev.planetonotion.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.planetonotion.TestData;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.integrations.NotionClient;
import dev.planetonotion.models.documents.AdHocNotionDestinationDocument;
import dev.planetonotion.repositories.AdHocNotionDestinationRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdHocNotionDestinationServiceTest {

    private static final String DESTINATION_ID = AdHocNotionDestinationDocument.SHARED_DESTINATION_ID;

    @Mock private AdHocNotionDestinationRepository destinationRepository;
    @Mock private NotionClient notionClient;

    private AdHocNotionDestinationService service;

    @BeforeEach
    void setUp() {
        service = new AdHocNotionDestinationService(destinationRepository, notionClient,
                TestData.integrationProperties());
    }

    @Test
    void getOrCreate_reusesDestinationFromMongo() {
        AdHocNotionDestinationDocument existing = destination("existing-page", "existing-database", "existing-source");
        when(destinationRepository.findById(DESTINATION_ID)).thenReturn(Optional.of(existing));

        assertThat(service.getOrCreate()).isSameAs(existing);

        verifyNoInteractions(notionClient);
        verify(destinationRepository, never()).save(any());
    }

    @Test
    void getOrCreate_createsDestinationAndPersistsItsIdentifiersWhenMissing() {
        when(destinationRepository.findById(DESTINATION_ID)).thenReturn(Optional.empty());
        when(notionClient.createAdHocDestination()).thenReturn(
                new NotionClient.ProjectDestination("new-page", "new-database", "new-source"));
        when(destinationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AdHocNotionDestinationDocument result = service.getOrCreate();

        assertThat(result.id()).isEqualTo(DESTINATION_ID);
        assertThat(result.notionPageId()).isEqualTo("new-page");
        assertThat(result.notionDatabaseId()).isEqualTo("new-database");
        assertThat(result.notionDataSourceId()).isEqualTo("new-source");
        ArgumentCaptor<AdHocNotionDestinationDocument> saved = ArgumentCaptor.forClass(AdHocNotionDestinationDocument.class);
        verify(destinationRepository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(result);
    }

    @Test
    void getOrCreate_migratesCompleteLegacyEnvironmentIdsIntoMongo() {
        IntegrationProperties legacyProperties = new IntegrationProperties(
                java.net.URI.create("https://api.plane.test"), java.net.URI.create("https://app.plane.test"), "unused",
                java.net.URI.create("https://notion.test"), "unused", "parent-page-id",
                "legacy-page", "legacy-database", "legacy-source");
        service = new AdHocNotionDestinationService(destinationRepository, notionClient, legacyProperties);
        when(destinationRepository.findById(DESTINATION_ID)).thenReturn(Optional.empty());
        when(destinationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AdHocNotionDestinationDocument result = service.getOrCreate();

        assertThat(result.notionPageId()).isEqualTo("legacy-page");
        assertThat(result.notionDatabaseId()).isEqualTo("legacy-database");
        assertThat(result.notionDataSourceId()).isEqualTo("legacy-source");
        verifyNoInteractions(notionClient);
        verify(destinationRepository).save(result);
    }

    @Test
    void getOrCreate_archivesNewNotionPageIfMongoPersistenceFails() {
        when(destinationRepository.findById(DESTINATION_ID)).thenReturn(Optional.empty());
        when(notionClient.createAdHocDestination()).thenReturn(
                new NotionClient.ProjectDestination("new-page", "new-database", "new-source"));
        when(destinationRepository.save(any())).thenThrow(new IllegalStateException("Mongo unavailable"));

        assertThatThrownBy(service::getOrCreate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Mongo unavailable");

        verify(notionClient).archivePage("new-page");
    }

    private AdHocNotionDestinationDocument destination(String page, String database, String source) {
        Instant now = Instant.now();
        return new AdHocNotionDestinationDocument(DESTINATION_ID, page, database, source, now, now);
    }
}
