package dev.planetonotion;

import java.time.Instant;
import java.time.LocalTime;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.SyncConfigDocument;

public final class TestData {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestData() {
    }

    public static JsonNode json(String source) {
        return JSON.readTree(source);
    }

    public static IntegrationProperties integrationProperties() {
        return integrationProperties("https://api.plane.so", "https://app.plane.so", "unused",
                "https://api.notion.com", "unused", "parent-page-id");
    }

    public static IntegrationProperties integrationProperties(String planeApiUrl, String planeAppUrl,
            String planeToken, String notionApiUrl, String notionToken, String notionParentPageId) {
        return new IntegrationProperties(URI.create(planeApiUrl), URI.create(planeAppUrl), planeToken,
                URI.create(notionApiUrl), notionToken, notionParentPageId, "adhoc-page", "adhoc-database",
                "adhoc-data-source");
    }

    public static PlaneProjectDocument project(ObjectId id, String workspace, String projectId) {
        Instant now = Instant.now();
        return new PlaneProjectDocument(id, workspace, projectId, "Demo Project", "DEMO",
                false, true, now, now, now);
    }

    public static SyncConfigDocument config(ObjectId id, ObjectId projectRef, ConfigStatus status,
            ScheduleType scheduleType, Integer intervalValue, String intervalUnit,
            Instant startAt, Instant endAt) {
        Instant createdAt = Instant.now();
        return new SyncConfigDocument(id, status, projectRef, "notion-project-page", "notion-database",
                "notion-data-source", Map.of("work_items", true, "pages", false), scheduleType,
                intervalValue, intervalUnit, null, null, List.of(), null, startAt, endAt, createdAt, createdAt);
    }

    public static SyncConfigDocument calendarConfig(ObjectId id, ObjectId projectRef, Instant startAt,
            Instant endAt, int interval, String frequency, List<String> days, LocalTime time) {
        Instant createdAt = Instant.now();
        return new SyncConfigDocument(id, ConfigStatus.ACTIVE, projectRef, "notion-project-page", "notion-database",
                "notion-data-source", Map.of("work_items", true, "pages", false), ScheduleType.CALENDAR,
                null, null, frequency, interval, days, time, startAt, endAt, createdAt, createdAt);
    }

    public static SyncConfigDocument withId(SyncConfigDocument config, ObjectId id) {
        return new SyncConfigDocument(id, config.status(), config.planeProjectRef(), config.notionProjectPageId(),
                config.notionDatabaseId(), config.notionDataSourceId(), config.syncOptions(), config.scheduleType(),
                config.intervalValue(), config.intervalUnit(), config.calendarFrequency(), config.calendarInterval(),
                config.calendarDaysOfWeek(), config.calendarTimeOfDay(), config.startAt(), config.endAt(),
                config.createdAt(), config.updatedAt());
    }
}
