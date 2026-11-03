package dev.planetonotion.common;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime provider settings for Plane and Notion integrations.
 *
 * <p>
 * The Plane API base URL is for API requests; the app base URL is for Plane
 * links.
 */
@ConfigurationProperties(prefix = "integration")
public record IntegrationProperties(
        URI planeApiBaseUrl,
        URI planeAppBaseUrl,
        String planePersonalAccessToken,
        URI notionApiBaseUrl,
        String notionConnectionToken,
        String notionParentPageId) {
}
