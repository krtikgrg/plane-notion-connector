package dev.planetonotion;

import java.time.Instant;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.net.URI;
import java.util.List;
import org.bson.types.ObjectId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.CalendarFrequency;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.IntegrationProperties;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.models.documents.PlaneProjectDocument;
import dev.planetonotion.models.documents.jobs.ManualSyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobResult;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;

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
                URI.create(notionApiUrl), notionToken, notionParentPageId, "", "", "");
    }

    public static PlaneProjectDocument project(ObjectId id, String workspace, String projectId) {
        Instant now = Instant.now();
        return new PlaneProjectDocument(id, workspace, projectId, "Demo Project", "DEMO",
                false, true, "notion-project-page", "notion-database", "notion-data-source", now, now);
    }

    public static PlaneProjectDocument projectWithoutNotionDestination(ObjectId id, String workspace, String projectId) {
        Instant now = Instant.now();
        return new PlaneProjectDocument(id, workspace, projectId, "Demo Project", "DEMO",
                false, true, null, null, null, now, now);
    }

    public static SyncConfigDocument config(ObjectId id, ObjectId projectRef, ConfigStatus status,
            ScheduleType scheduleType, Integer intervalValue, IntervalUnit intervalUnit,
            Instant startAt, Instant endAt) {
        Instant createdAt = Instant.now();
        return new SyncConfigDocument(id, status, projectRef, new SyncOptions(true, false), new SyncSchedule(scheduleType, startAt, endAt,
                        scheduleType == ScheduleType.INTERVAL ? new SyncSchedule.Interval(intervalValue, intervalUnit) : null,
                        null), createdAt, createdAt);
    }

    public static SyncConfigDocument calendarConfig(ObjectId id, ObjectId projectRef, Instant startAt,
            Instant endAt, int interval, CalendarFrequency frequency,
            List<DayOfWeek> days, LocalTime time) {
        Instant createdAt = Instant.now();
        return new SyncConfigDocument(id, ConfigStatus.ACTIVE, projectRef, new SyncOptions(true, false), new SyncSchedule(ScheduleType.CALENDAR, startAt,
                        endAt, null, new SyncSchedule.Calendar(frequency, interval, days, time)), createdAt, createdAt);
    }

    public static SyncConfigDocument withId(SyncConfigDocument config, ObjectId id) {
        return new SyncConfigDocument(id, config.status(), config.planeProjectDocumentId(), config.syncOptions(), config.schedule(),
                config.createdAt(), config.updatedAt());
    }

    public static SyncJobDocument withJobId(SyncJobDocument job, ObjectId id) {
        return copyJob(job, id, job.jobStatus(), job.startedAt(), job.finishedAt(), job.attemptCount(), job.result(),
                job.errorCode(), job.errorMessage(), job.updatedAt());
    }

    public static SyncJobDocument withStatus(SyncJobDocument job, SyncJobStatus status) {
        Instant now = Instant.now();
        return copyJob(job, job.id(), status, now, null, 1, SyncJobResult.empty(),
                null, null, now);
    }

    public static Instant scheduledFor(SyncJobDocument job) {
        return job instanceof ScheduledSyncJobDocument scheduled ? scheduled.scheduledFor() : null;
    }

    private static SyncJobDocument copyJob(SyncJobDocument job, ObjectId id, SyncJobStatus status,
            Instant startedAt, Instant finishedAt, int attemptCount, SyncJobResult result,
            String errorCode, String errorMessage, Instant updatedAt) {
        if (job instanceof ManualSyncJobDocument manual) {
            return new ManualSyncJobDocument(id, job.jobType(), job.scope(), status, job.configId(),
                    job.planeProjectDocumentId(), manual.planeWorkItem(), manual.forceArchivedConfig(),
                    startedAt, finishedAt, attemptCount, result, errorCode, errorMessage, job.createdAt(), updatedAt);
        }
        if (job instanceof ScheduledSyncJobDocument scheduled) {
            return new ScheduledSyncJobDocument(id, job.jobType(), job.scope(), status, job.configId(),
                    job.planeProjectDocumentId(), scheduled.scheduledFor(), startedAt, finishedAt, attemptCount,
                    result, errorCode, errorMessage, job.createdAt(), updatedAt);
        }
        throw new IllegalArgumentException("Unknown sync job subtype: " + job.getClass().getName());
    }
}
