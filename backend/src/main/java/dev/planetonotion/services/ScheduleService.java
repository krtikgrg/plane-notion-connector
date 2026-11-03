package dev.planetonotion.services;

import dev.planetonotion.common.ApiErrorMessages;
import dev.planetonotion.common.PlaneToNotionEnums.CalendarFrequency;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobResult;
import dev.planetonotion.repositories.JobRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Creates scheduled jobs and advances active configs after sync runs finish.
 */
@Service
public class ScheduleService {

    private final JobRepository jobRepository;

    /** Creates the service with access to queued sync jobs. */
    public ScheduleService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Replaces pending scheduled jobs with the next eligible occurrence.
     * Inactive configs are left without a pending scheduled job.
     *
     * @param config config whose schedule is being reset
     */
    public void reset(SyncConfigDocument config) {
        // Remove pending schedules before recalculating.
        cancelPending(config.id());
        // Stop if the config is inactive.
        if (config.status() != ConfigStatus.ACTIVE)
            return;
        // Find the first eligible schedule occurrence.
        SyncSchedule schedule = config.schedule();
        Instant anchor = schedule.startAt() == null ? config.createdAt() : schedule.startAt();
        Instant now = Instant.now();
        Instant first = schedule.type() == ScheduleType.INTERVAL
                ? intervalOccurrenceAtOrAfter(schedule, anchor, now)
                : calendarOccurrence(config, now.minusNanos(1));
        if (insideWindow(schedule, first))
            jobRepository.save(newScheduledJob(config, first, JobStatus.PENDING));
    }

    /** Removes pending or held scheduled jobs for a config. */
    public void cancelPending(ObjectId configId) {
        jobRepository.deletePendingAndHeldScheduledByConfigId(configId);
    }

    /**
     * Finds the first interval occurrence at or after the current time.
     *
     * @param schedule config schedule containing the interval
     * @param anchor   start time from which intervals are measured
     * @param now      current time used to select the next occurrence
     * @return first interval occurrence that is not in the past
     */
    private Instant intervalOccurrenceAtOrAfter(SyncSchedule schedule, Instant anchor, Instant now) {
        long period = intervalPeriod(schedule.interval()).getSeconds();
        if (!anchor.isBefore(now))
            return anchor;
        long elapsed = Duration.between(anchor, now).getSeconds();
        long intervals = elapsed / period + 1;
        return anchor.plusSeconds(intervals * period);
    }

    /**
     * Advances an active config after a sync run, recording elapsed scheduled
     * occurrences as skipped and queuing the next future occurrence.
     *
     * @param finishedJob completed or failed sync job
     * @param config      current config and schedule definition
     */
    public void afterRun(SyncJobDocument finishedJob, SyncConfigDocument config) {
        // Stop scheduling immediately for inactive configs.
        if (config.status() != ConfigStatus.ACTIVE)
            return;
        // Capture now and inspect pending schedules.
        Instant now = Instant.now();
        var pending = jobRepository.findPendingScheduledJobsByConfigId(config.id());
        Instant base;
        boolean pendingInFuture = false;
        // Retire elapsed pending runs as skipped.
        if (!pending.isEmpty()) {
            base = null;
            for (SyncJobDocument candidate : pending) {
                if (!(candidate instanceof ScheduledSyncJobDocument job))
                    continue;
                if (job.scheduledFor() != null && job.scheduledFor().isAfter(now)) {
                    pendingInFuture = true;
                    continue;
                }
                Instant finishedAt = now;
                jobRepository.save(copyAsSkipped(job, finishedAt));
                if (base == null || job.scheduledFor().isAfter(base))
                    base = job.scheduledFor();
            }
            // Keep future pending work in place.
            if (pendingInFuture || base == null)
                return;
        } else if (finishedJob instanceof ScheduledSyncJobDocument scheduled) {
            // Anchor from the finished scheduled run.
            base = scheduled.scheduledFor();
        } else {
            return;
        }

        // Record missed slots before queuing next.
        Instant next = nextOccurrence(config, base);
        while (next != null && !next.isAfter(now)) {
            if (insideWindow(config.schedule(), next))
                jobRepository.save(newScheduledJob(config, next, JobStatus.SKIPPED_OVERLAP));
            next = nextOccurrence(config, next);
        }
        // Queue the first future schedule occurrence.
        if (insideWindow(config.schedule(), next))
            jobRepository.save(newScheduledJob(config, next, JobStatus.PENDING));
    }

    /** Copies an elapsed scheduled job with skipped status and details. */
    private ScheduledSyncJobDocument copyAsSkipped(ScheduledSyncJobDocument job, Instant now) {
        return new ScheduledSyncJobDocument(job.id(), job.jobType(), job.scope(), JobStatus.SKIPPED_OVERLAP,
                job.configId(), job.planeProjectDocumentId(), job.scheduledFor(), null, now, job.attemptCount(),
                job.result(), JobStatus.SKIPPED_OVERLAP.name(),
                ApiErrorMessages.Application.SCHEDULED_RUN_ELAPSED,
                job.createdAt(), now);
    }

    /** Calculates the next interval or calendar occurrence. */
    private Instant nextOccurrence(SyncConfigDocument config, Instant previous) {
        if (previous == null)
            return null;
        SyncSchedule schedule = config.schedule();
        if (schedule.type() == ScheduleType.INTERVAL) {
            return previous.plus(intervalPeriod(schedule.interval()));
        }
        return calendarOccurrence(config, previous);
    }

    /** Converts an interval value and unit into a duration. */
    private Duration intervalPeriod(SyncSchedule.Interval interval) {
        if (interval.value() == null) {
            throw new IllegalStateException("Unsupported interval schedule unit or value.");
        }
        return switch (interval.unit()) {
            case MINUTES -> Duration.ofMinutes(interval.value());
            case HOURS -> Duration.ofHours(interval.value());
        };
    }

    /** Finds the next daily or weekly occurrence in UTC. */
    private Instant calendarOccurrence(SyncConfigDocument config, Instant after) {
        SyncSchedule schedule = config.schedule();
        Instant anchorInstant = schedule.startAt() == null ? config.createdAt() : schedule.startAt();
        ZonedDateTime anchor = anchorInstant.atZone(ZoneOffset.UTC);
        LocalDate startDay = after.atZone(ZoneOffset.UTC).toLocalDate();
        LocalTime runAt = schedule.calendar().timeOfDay();
        // Search UTC dates after prior run.
        for (int dayOffset = 0; dayOffset <= 3660; dayOffset++) {
            LocalDate day = startDay.plusDays(dayOffset);
            Instant candidate = day.atTime(runAt).toInstant(ZoneOffset.UTC);
            if (!candidate.isAfter(after) || candidate.isBefore(anchorInstant))
                continue;
            long elapsedDays = ChronoUnit.DAYS.between(anchor.toLocalDate(), day);
            // Match the configured daily interval.
            if (schedule.calendar().frequency() == CalendarFrequency.DAILY) {
                if (elapsedDays % schedule.calendar().interval() == 0)
                    return candidate;
                continue;
            }
            // Match configured week and selected weekday.
            long elapsedWeeks = Math.floorDiv(elapsedDays, 7);
            boolean configuredDay = schedule.calendar().daysOfWeek().contains(day.getDayOfWeek());
            if (elapsedWeeks % schedule.calendar().interval() == 0 && configuredDay)
                return candidate;
        }
        return null;
    }

    /** Checks the window with an inclusive start and exclusive end. */
    private boolean insideWindow(SyncSchedule schedule, Instant occurrence) {
        return occurrence != null
                && (schedule.startAt() == null || !occurrence.isBefore(schedule.startAt()))
                && (schedule.endAt() == null || occurrence.isBefore(schedule.endAt()));
    }

    /** Creates a scheduled or skipped-overlap project job. */
    private ScheduledSyncJobDocument newScheduledJob(SyncConfigDocument config, Instant scheduledFor,
            JobStatus status) {
        Instant now = Instant.now();
        // Populate skip details only for overlaps.
        boolean skipped = status == JobStatus.SKIPPED_OVERLAP;
        return new ScheduledSyncJobDocument(new ObjectId(), JobType.SCHEDULED, SyncScope.PROJECT, status,
                config.id(), config.planeProjectDocumentId(), scheduledFor, null, skipped ? now : null, 0,
                SyncJobResult.empty(),
                skipped ? JobStatus.SKIPPED_OVERLAP.name() : null,
                skipped ? ApiErrorMessages.Application.SCHEDULED_RUN_ELAPSED : null,
                now, now);
    }
}
