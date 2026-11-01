package dev.planetonotion.services;

import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

@Service
public class ScheduleService {

    private final SyncJobRepository jobRepository;

    public ScheduleService(SyncJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public void reset(SyncConfigDocument config) {
        cancelPending(config.id());
        if (config.status() != ConfigStatus.ACTIVE) return;
        Instant anchor = config.startAt() == null ? config.createdAt() : config.startAt();
        Instant now = Instant.now();
        Instant first = config.scheduleType() == ScheduleType.INTERVAL
                ? intervalOccurrenceAtOrAfter(config, anchor, now)
                : calendarOccurrence(config, now.minusNanos(1));
        if (insideWindow(config, first)) jobRepository.save(newScheduledJob(config, first, SyncJobStatus.PENDING));
    }

    public void cancelPending(ObjectId configId) {
        jobRepository.deleteByConfigIdAndTriggerTypeAndStatus(configId, SyncJobType.SCHEDULED, SyncJobStatus.PENDING);
    }

    private Instant intervalOccurrenceAtOrAfter(SyncConfigDocument config, Instant anchor, Instant now) {
        long seconds = "HOURS".equals(config.intervalUnit()) ? 3600L : 60L;
        long period = seconds * config.intervalValue();
        if (!anchor.isBefore(now)) return anchor;
        long elapsed = Duration.between(anchor, now).getSeconds();
        long intervals = elapsed / period + 1;
        return anchor.plusSeconds(intervals * period);
    }

    public void afterRun(SyncJobDocument finishedJob, SyncConfigDocument config) {
        if (config.status() != ConfigStatus.ACTIVE) return;
        Instant now = Instant.now();
        var pending = jobRepository.findByConfigIdAndTriggerTypeAndStatusOrderByScheduledForAsc(
                config.id(), SyncJobType.SCHEDULED, SyncJobStatus.PENDING);
        Instant base;
        boolean pendingInFuture = false;
        if (!pending.isEmpty()) {
            base = null;
            for (SyncJobDocument job : pending) {
                if (job.scheduledFor() != null && job.scheduledFor().isAfter(now)) {
                    pendingInFuture = true;
                    continue;
                }
                Instant finishedAt = now;
                jobRepository.save(copyAsSkipped(job, finishedAt));
                if (base == null || job.scheduledFor().isAfter(base)) base = job.scheduledFor();
            }
            if (pendingInFuture || base == null) return;
        } else if (finishedJob.triggerType() == SyncJobType.SCHEDULED) {
            base = finishedJob.scheduledFor();
        } else {
            return;
        }

        Instant next = nextOccurrence(config, base);
        while (next != null && !next.isAfter(now)) {
            if (insideWindow(config, next)) jobRepository.save(newScheduledJob(config, next, SyncJobStatus.SKIPPED_OVERLAP));
            next = nextOccurrence(config, next);
        }
        if (insideWindow(config, next)) jobRepository.save(newScheduledJob(config, next, SyncJobStatus.PENDING));
    }

    private SyncJobDocument copyAsSkipped(SyncJobDocument job, Instant now) {
        return new SyncJobDocument(job.id(), job.triggerType(), job.syncScope(), SyncJobStatus.SKIPPED_OVERLAP,
                job.configId(), job.planeProjectRef(), job.requestedTaskUrl(), job.workItemId(),
                job.forceArchivedConfig(), job.scheduledFor(), null, now, job.attemptCount(),
                job.fetchedCount(), job.eligibleCount(), job.createdCount(), job.updatedCount(), job.unchangedCount(),
                job.failedCount(), "SKIPPED_OVERLAP", "The scheduled occurrence elapsed while this config was busy.",
                job.createdAt(), now);
    }

    private Instant nextOccurrence(SyncConfigDocument config, Instant previous) {
        if (previous == null) return null;
        if (config.scheduleType() == ScheduleType.INTERVAL) {
            long unitSeconds = "HOURS".equals(config.intervalUnit()) ? 3600L : 60L;
            return previous.plus(Duration.ofSeconds(unitSeconds * config.intervalValue()));
        }
        return calendarOccurrence(config, previous);
    }

    private Instant calendarOccurrence(SyncConfigDocument config, Instant after) {
        Instant anchorInstant = config.startAt() == null ? config.createdAt() : config.startAt();
        ZonedDateTime anchor = anchorInstant.atZone(ZoneOffset.UTC);
        LocalDate startDay = after.atZone(ZoneOffset.UTC).toLocalDate();
        LocalTime runAt = config.calendarTimeOfDay();
        for (int dayOffset = 0; dayOffset <= 3660; dayOffset++) {
            LocalDate day = startDay.plusDays(dayOffset);
            Instant candidate = day.atTime(runAt).toInstant(ZoneOffset.UTC);
            if (!candidate.isAfter(after) || candidate.isBefore(anchorInstant)) continue;
            long elapsedDays = ChronoUnit.DAYS.between(anchor.toLocalDate(), day);
            if ("DAILY".equals(config.calendarFrequency())) {
                if (elapsedDays % config.calendarInterval() == 0) return candidate;
                continue;
            }
            long elapsedWeeks = Math.floorDiv(elapsedDays, 7);
            boolean configuredDay = config.calendarDaysOfWeek().stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .anyMatch(day.getDayOfWeek().name()::equals);
            if (elapsedWeeks % config.calendarInterval() == 0 && configuredDay) return candidate;
        }
        return null;
    }

    private boolean insideWindow(SyncConfigDocument config, Instant occurrence) {
        return occurrence != null
                && (config.startAt() == null || !occurrence.isBefore(config.startAt()))
                && (config.endAt() == null || occurrence.isBefore(config.endAt()));
    }

    private SyncJobDocument newScheduledJob(SyncConfigDocument config, Instant scheduledFor, SyncJobStatus status) {
        Instant now = Instant.now();
        boolean skipped = status == SyncJobStatus.SKIPPED_OVERLAP;
        return new SyncJobDocument(null, SyncJobType.SCHEDULED, SyncScope.PROJECT, status,
                config.id(), config.planeProjectRef(), null, null, false, scheduledFor,
                null, skipped ? now : null, 0, 0, 0, 0, 0, 0, 0,
                skipped ? "SKIPPED_OVERLAP" : null,
                skipped ? "The scheduled occurrence elapsed while this config was busy." : null,
                now, now);
    }
}
