package dev.planetonotion.common;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "integration")
public record IntegrationProperties(
        URI planeApiBaseUrl,
        URI planeAppBaseUrl,
        String planePersonalAccessToken,
        URI notionApiBaseUrl,
        String notionConnectionToken,
        String notionParentPageId,
        String legacyNotionAdHocPageId,
        String legacyNotionAdHocDatabaseId,
        String legacyNotionAdHocDataSourceId) {
}
