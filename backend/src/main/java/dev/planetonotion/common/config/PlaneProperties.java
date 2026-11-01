package dev.planetonotion.common.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plane")
public record PlaneProperties(URI apiBaseUrl, URI appBaseUrl, String personalAccessToken) {
}
