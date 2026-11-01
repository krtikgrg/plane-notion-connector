package dev.planetonotion.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notion.ad-hoc")
public record AdHocNotionDestination(String pageId, String databaseId, String dataSourceId) {
}
