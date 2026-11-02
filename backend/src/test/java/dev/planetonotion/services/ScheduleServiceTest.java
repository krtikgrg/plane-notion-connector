package dev.planetonotion.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.TestData;
import dev.planetonotion.models.documents.jobs.SyncJobResult;
import dev.planetonotion.common.PlaneToNotionEnums.ConfigStatus;
import dev.planetonotion.common.PlaneToNotionEnums.CalendarFrequency;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.models.documents.configs.SyncConfigDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.configs.SyncOptions;
import dev.planetonotion.models.documents.configs.SyncSchedule;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private SyncJobRepository jobRepository;

    private ScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleService(jobRepository);
    }

    @Test
    void reset_createsFirstIntervalRunAtFutureStartAnchor() {
        Instant start = Instant.now().plusSeconds(3600);
        SyncConfigDocument config = TestData.config(new ObjectId(), new ObjectId(), ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, start, null);

        service.reset(config);

        ArgumentCaptor<SyncJobDocument> captor = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).deleteByConfigIdAndJobTypeAndJobStatus(config.id(), SyncJobType.SCHEDULED, SyncJobStatus.PENDING);
        verify(jobRepository).save(captor.capture());
        assertThat(TestData.scheduledFor(captor.getValue())).isEqualTo(start);
        assertThat(captor.getValue().jobStatus()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(captor.getValue().jobType()).isEqualTo(SyncJobType.SCHEDULED);
    }

    @Test
    void reset_removesPendingScheduleButDoesNotCreateOneWhenDisabled() {
        SyncConfigDocument config = TestData.config(new ObjectId(), new ObjectId(), ConfigStatus.DISABLED,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, null, null);

        service.reset(config);

        verify(jobRepository).deleteByConfigIdAndJobTypeAndJobStatus(config.id(), SyncJobType.SCHEDULED, SyncJobStatus.PENDING);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void reset_treatsEndTimeAsExclusive() {
        Instant start = Instant.now().plusSeconds(600);
        SyncConfigDocument config = TestData.config(new ObjectId(), new ObjectId(), ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, IntervalUnit.MINUTES, start, start);

        service.reset(config);

        verify(jobRepository, never()).save(any());
    }

    @Test
    void reset_anchorsDailyCalendarToStartAtAndUsesUtcTime() {
        Instant start = Instant.parse("2030-01-01T12:00:00Z");
        SyncConfigDocument config = calendarConfig(start, start, null, 2, CalendarFrequency.DAILY, List.of(),
                LocalTime.of(11, 15));

        service.reset(config);

        ArgumentCaptor<SyncJobDocument> captor = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).save(captor.capture());
        assertThat(TestData.scheduledFor(captor.getValue())).isEqualTo(Instant.parse("2030-01-03T11:15:00Z"));
    }

    @Test
    void reset_usesCreatedAtAsCalendarAnchorWhenStartAtIsMissing() {
        Instant createdAt = Instant.parse("2030-01-01T12:00:00Z");
        SyncConfigDocument config = calendarConfig(createdAt, null, null, 2, CalendarFrequency.DAILY, List.of(),
                LocalTime.of(11, 15));

        service.reset(config);

        ArgumentCaptor<SyncJobDocument> captor = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).save(captor.capture());
        assertThat(TestData.scheduledFor(captor.getValue())).isEqualTo(Instant.parse("2030-01-03T11:15:00Z"));
    }

    @Test
    void reset_andAfterRun_followSelectedDayOnEveryNthCalendarWeek() {
        Instant anchor = Instant.parse("2030-01-01T08:00:00Z"); // Tuesday, week zero
        SyncConfigDocument config = calendarConfig(anchor, anchor, null, 2, CalendarFrequency.WEEKLY, List.of(DayOfWeek.FRIDAY),
                LocalTime.of(9, 0));

        service.reset(config);

        ArgumentCaptor<SyncJobDocument> firstCaptor = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).save(firstCaptor.capture());
        SyncJobDocument first = firstCaptor.getValue();
        assertThat(TestData.scheduledFor(first)).isEqualTo(Instant.parse("2030-01-04T09:00:00Z"));

        org.mockito.Mockito.clearInvocations(jobRepository);
        when(jobRepository.findPendingScheduledJobs(config.id())).thenReturn(List.of());
        service.afterRun(first, config);

        ArgumentCaptor<SyncJobDocument> nextCaptor = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).save(nextCaptor.capture());
        assertThat(nextCaptor.getValue().jobStatus()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(TestData.scheduledFor(nextCaptor.getValue())).isEqualTo(Instant.parse("2030-01-18T09:00:00Z"));
    }

    @Test
    void reset_includesCalendarOccurrenceAtStart() {
        Instant occurrence = Instant.parse("2030-01-07T09:30:00Z"); // Monday
        SyncConfigDocument startsAtOccurrence = calendarConfig(occurrence.minusSeconds(3600), occurrence,
                null, 1, CalendarFrequency.DAILY, List.of(), LocalTime.of(9, 30));

        service.reset(startsAtOccurrence);

        ArgumentCaptor<SyncJobDocument> captor = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).save(captor.capture());
        assertThat(TestData.scheduledFor(captor.getValue())).isEqualTo(occurrence);
    }

    @Test
    void reset_excludesCalendarOccurrenceAtEnd() {
        Instant occurrence = Instant.parse("2030-01-07T09:30:00Z"); // Monday
        Instant start = occurrence.minusSeconds(86_400).plusSeconds(60); // After Sunday's 09:30 occurrence
        SyncConfigDocument endsAtOccurrence = calendarConfig(start, start, occurrence, 1, CalendarFrequency.DAILY,
                List.of(), LocalTime.of(9, 30));
        service.reset(endsAtOccurrence);

        verify(jobRepository, never()).save(any());
    }

    @Test
    void afterLongScheduledRun_recordsMissedOccurrencesAndQueuesNextFutureRun() {
        ObjectId configId = new ObjectId();
        ObjectId projectRef = new ObjectId();
        Instant now = Instant.now();
        SyncConfigDocument config = TestData.config(configId, projectRef, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 10, IntervalUnit.MINUTES, now.minusSeconds(7200), null);
        ScheduledSyncJobDocument finished = new ScheduledSyncJobDocument(new ObjectId(), SyncJobType.SCHEDULED, SyncScope.PROJECT,
                SyncJobStatus.SUCCESS, configId, projectRef, now.minusSeconds(3600),
                now.minusSeconds(3700), now, 1, new SyncJobResult(5, 2, 1, 2), null, null,
                now.minusSeconds(3700), now);
        when(jobRepository.findPendingScheduledJobs(configId)).thenReturn(List.of());

        service.afterRun(finished, config);

        ArgumentCaptor<SyncJobDocument> captor = ArgumentCaptor.forClass(SyncJobDocument.class);
        org.mockito.Mockito.verify(jobRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        List<SyncJobDocument> created = captor.getAllValues();
        assertThat(created).anyMatch(job -> job.jobStatus() == SyncJobStatus.SKIPPED_OVERLAP);
        assertThat(created).anyMatch(job -> job instanceof ScheduledSyncJobDocument scheduled
                && job.jobStatus() == SyncJobStatus.PENDING && scheduled.scheduledFor().isAfter(Instant.now()));
    }

    private SyncConfigDocument calendarConfig(Instant createdAt, Instant startAt, Instant endAt,
            int interval, CalendarFrequency frequency, List<DayOfWeek> days, LocalTime runAt) {
        return new SyncConfigDocument(new ObjectId(), ConfigStatus.ACTIVE, new ObjectId(),
                new SyncOptions(true, false), new SyncSchedule(ScheduleType.CALENDAR, startAt, endAt, null,
                        new SyncSchedule.Calendar(frequency, interval, days, runAt)), createdAt, createdAt);
    }
}
