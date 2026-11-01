package dev.planetonotion.models;

import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record HealthResponse(String status) {
    }

    public record PlaneConnectionResponse(String status) {
    }

    public record ProjectUrlRequest(@NotBlank String projectUrl) {
    }

    public record ProjectRefreshRequest(@NotBlank String workspaceSlug) {
    }

    public record ProjectResponse(String planeProjectRef, String workspaceSlug, String projectId,
            String name, String identifier, String projectUrl, boolean isArchived, boolean isAvailable,
            Instant lastRefreshedAt) {
    }

    public record ProjectRefreshResponse(String workspaceSlug, Instant refreshedAt, List<ProjectResponse> projects) {
    }

    public record CreateConfigRequest(@NotBlank String planeProjectRef, @NotNull Map<String, Boolean> syncOptions,
            ConfigStatus status, @NotNull ScheduleType scheduleType, Integer intervalValue, String intervalUnit,
            String calendarFrequency, Integer calendarInterval, List<String> calendarDaysOfWeek,
            LocalTime calendarTimeOfDay, Instant startAt, Instant endAt) {
    }

    public record UpdateConfigRequest(@NotNull ConfigStatus status, @NotNull Map<String, Boolean> syncOptions,
            @NotNull ScheduleType scheduleType, Integer intervalValue, String intervalUnit,
            String calendarFrequency, Integer calendarInterval, List<String> calendarDaysOfWeek,
            LocalTime calendarTimeOfDay, Instant startAt, Instant endAt) {
    }

    public record ConfigResponse(String configId, ConfigStatus status, ProjectResponse project,
            String notionProjectPageId, String notionDatabaseId, String notionDataSourceId,
            Map<String, Boolean> syncOptions, ScheduleType scheduleType, Integer intervalValue,
            String intervalUnit, String calendarFrequency, Integer calendarInterval,
            List<String> calendarDaysOfWeek, LocalTime calendarTimeOfDay, Instant startAt,
            Instant endAt, Instant createdAt, Instant updatedAt) {
    }

    public record ManualTaskSyncRequest(@NotBlank String taskUrl, String configId, boolean forceArchivedConfig) {
    }

    public record ManualProjectSyncRequest(boolean forceArchivedConfig) {
    }

    public record EnqueuedJobResponse(String jobId, SyncJobStatus status, Instant createdAt) {
    }

    public record JobHistoryResponse(List<JobResponse> items, String nextBeforeId) {
    }

    public record JobResponse(String jobId, SyncJobType triggerType, SyncScope syncScope,
            SyncJobStatus status, String configId, String planeProjectRef, String requestedTaskUrl,
            String workItemId, Instant scheduledFor, Instant startedAt, Instant finishedAt,
            int attemptCount, int fetchedCount, int eligibleCount, int createdCount, int updatedCount,
            int unchangedCount, int failedCount, String errorCode, String errorMessage, Instant createdAt) {
    }

    public record ApiError(String errorCode, String message) {
    }
}
