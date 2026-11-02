package dev.planetonotion.services;

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

@Service
public class ScheduleService {

    private final JobRepository jobRepository;

    public ScheduleService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public void reset(SyncConfigDocument config) {
        cancelPending(config.id());
        if (config.status() != ConfigStatus.ACTIVE)
            return;
        SyncSchedule schedule = config.schedule();
        Instant anchor = schedule.startAt() == null ? config.createdAt() : schedule.startAt();
        Instant now = Instant.now();
        Instant first = schedule.type() == ScheduleType.INTERVAL
                ? intervalOccurrenceAtOrAfter(schedule, anchor, now)
                : calendarOccurrence(config, now.minusNanos(1));
        if (insideWindow(schedule, first))
            jobRepository.save(newScheduledJob(config, first, JobStatus.PENDING));
    }

    public void cancelPending(ObjectId configId) {
        jobRepository.deletePendingAndHeldScheduledByConfigId(configId);
    }

    private Instant intervalOccurrenceAtOrAfter(SyncSchedule schedule, Instant anchor, Instant now) {
        long period = intervalPeriod(schedule.interval()).getSeconds();
        if (!anchor.isBefore(now))
            return anchor;
        long elapsed = Duration.between(anchor, now).getSeconds();
        long intervals = elapsed / period + 1;
        return anchor.plusSeconds(intervals * period);
    }

    public void afterRun(SyncJobDocument finishedJob, SyncConfigDocument config) {
        if (config.status() != ConfigStatus.ACTIVE)
            return;
        Instant now = Instant.now();
        var pending = jobRepository.findPendingScheduledJobsByConfigId(config.id());
        Instant base;
        boolean pendingInFuture = false;
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
            if (pendingInFuture || base == null)
                return;
        } else if (finishedJob instanceof ScheduledSyncJobDocument scheduled) {
            base = scheduled.scheduledFor();
        } else {
            return;
        }

        Instant next = nextOccurrence(config, base);
        while (next != null && !next.isAfter(now)) {
            if (insideWindow(config.schedule(), next))
                jobRepository.save(newScheduledJob(config, next, JobStatus.SKIPPED_OVERLAP));
            next = nextOccurrence(config, next);
        }
        if (insideWindow(config.schedule(), next))
            jobRepository.save(newScheduledJob(config, next, JobStatus.PENDING));
    }

    private ScheduledSyncJobDocument copyAsSkipped(ScheduledSyncJobDocument job, Instant now) {
        return new ScheduledSyncJobDocument(job.id(), job.jobType(), job.scope(), JobStatus.SKIPPED_OVERLAP,
                job.configId(), job.planeProjectDocumentId(), job.scheduledFor(), null, now, job.attemptCount(),
                job.result(), "SKIPPED_OVERLAP", "The scheduled occurrence elapsed while this config was busy.",
                job.createdAt(), now);
    }

    private Instant nextOccurrence(SyncConfigDocument config, Instant previous) {
        if (previous == null)
            return null;
        SyncSchedule schedule = config.schedule();
        if (schedule.type() == ScheduleType.INTERVAL) {
            return previous.plus(intervalPeriod(schedule.interval()));
        }
        return calendarOccurrence(config, previous);
    }

    private Duration intervalPeriod(SyncSchedule.Interval interval) {
        if (interval.value() == null) {
            throw new IllegalStateException("Unsupported interval schedule unit or value.");
        }
        return switch (interval.unit()) {
            case MINUTES -> Duration.ofMinutes(interval.value());
            case HOURS -> Duration.ofHours(interval.value());
        };
    }

    private Instant calendarOccurrence(SyncConfigDocument config, Instant after) {
        SyncSchedule schedule = config.schedule();
        Instant anchorInstant = schedule.startAt() == null ? config.createdAt() : schedule.startAt();
        ZonedDateTime anchor = anchorInstant.atZone(ZoneOffset.UTC);
        LocalDate startDay = after.atZone(ZoneOffset.UTC).toLocalDate();
        LocalTime runAt = schedule.calendar().timeOfDay();
        for (int dayOffset = 0; dayOffset <= 3660; dayOffset++) {
            LocalDate day = startDay.plusDays(dayOffset);
            Instant candidate = day.atTime(runAt).toInstant(ZoneOffset.UTC);
            if (!candidate.isAfter(after) || candidate.isBefore(anchorInstant))
                continue;
            long elapsedDays = ChronoUnit.DAYS.between(anchor.toLocalDate(), day);
            if (schedule.calendar().frequency() == CalendarFrequency.DAILY) {
                if (elapsedDays % schedule.calendar().interval() == 0)
                    return candidate;
                continue;
            }
            long elapsedWeeks = Math.floorDiv(elapsedDays, 7);
            boolean configuredDay = schedule.calendar().daysOfWeek().contains(day.getDayOfWeek());
            if (elapsedWeeks % schedule.calendar().interval() == 0 && configuredDay)
                return candidate;
        }
        return null;
    }

    private boolean insideWindow(SyncSchedule schedule, Instant occurrence) {
        return occurrence != null
                && (schedule.startAt() == null || !occurrence.isBefore(schedule.startAt()))
                && (schedule.endAt() == null || occurrence.isBefore(schedule.endAt()));
    }

    private ScheduledSyncJobDocument newScheduledJob(SyncConfigDocument config, Instant scheduledFor,
            JobStatus status) {
        Instant now = Instant.now();
        boolean skipped = status == JobStatus.SKIPPED_OVERLAP;
        return new ScheduledSyncJobDocument(new ObjectId(), JobType.SCHEDULED, SyncScope.PROJECT, status,
                config.id(), config.planeProjectDocumentId(), scheduledFor, null, skipped ? now : null, 0,
                SyncJobResult.empty(),
                skipped ? "SKIPPED_OVERLAP" : null,
                skipped ? "The scheduled occurrence elapsed while this config was busy." : null,
                now, now);
    }
}
