package dev.planetonotion.common.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notion")
public record NotionProperties(URI apiBaseUrl, String connectionToken, String parentPageId) {
}
