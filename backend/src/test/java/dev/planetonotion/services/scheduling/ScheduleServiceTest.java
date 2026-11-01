package dev.planetonotion.services.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.TestData;
import dev.planetonotion.common.enums.ConfigStatus;
import dev.planetonotion.common.enums.ScheduleType;
import dev.planetonotion.common.enums.SyncJobStatus;
import dev.planetonotion.common.enums.SyncJobType;
import dev.planetonotion.common.enums.SyncScope;
import dev.planetonotion.models.documents.SyncConfigDocument;
import dev.planetonotion.models.documents.SyncJobDocument;
import dev.planetonotion.repositories.sync.SyncJobRepository;
import java.time.Instant;
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
                ScheduleType.INTERVAL, 30, "MINUTES", start, null);

        service.reset(config);

        ArgumentCaptor<SyncJobDocument> captor = ArgumentCaptor.forClass(SyncJobDocument.class);
        verify(jobRepository).deleteByConfigIdAndTriggerTypeAndStatus(config.id(), SyncJobType.SCHEDULED, SyncJobStatus.PENDING);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().scheduledFor()).isEqualTo(start);
        assertThat(captor.getValue().status()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(captor.getValue().triggerType()).isEqualTo(SyncJobType.SCHEDULED);
    }

    @Test
    void reset_removesPendingScheduleButDoesNotCreateOneWhenDisabled() {
        SyncConfigDocument config = TestData.config(new ObjectId(), new ObjectId(), ConfigStatus.DISABLED,
                ScheduleType.INTERVAL, 30, "MINUTES", null, null);

        service.reset(config);

        verify(jobRepository).deleteByConfigIdAndTriggerTypeAndStatus(config.id(), SyncJobType.SCHEDULED, SyncJobStatus.PENDING);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void reset_treatsEndTimeAsExclusive() {
        Instant start = Instant.now().plusSeconds(600);
        SyncConfigDocument config = TestData.config(new ObjectId(), new ObjectId(), ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 30, "MINUTES", start, start);

        service.reset(config);

        verify(jobRepository, never()).save(any());
    }

    @Test
    void afterLongScheduledRun_recordsMissedOccurrencesAndQueuesNextFutureRun() {
        ObjectId configId = new ObjectId();
        ObjectId projectRef = new ObjectId();
        Instant now = Instant.now();
        SyncConfigDocument config = TestData.config(configId, projectRef, ConfigStatus.ACTIVE,
                ScheduleType.INTERVAL, 10, "MINUTES", now.minusSeconds(7200), null);
        SyncJobDocument finished = new SyncJobDocument(new ObjectId(), SyncJobType.SCHEDULED, SyncScope.PROJECT,
                SyncJobStatus.SUCCESS, configId, projectRef, null, null, false, now.minusSeconds(3600),
                now.minusSeconds(3700), now, 1, 5, 5, 2, 1, 2, 0, null, null, now.minusSeconds(3700), now);
        when(jobRepository.findByConfigIdAndTriggerTypeAndStatusOrderByScheduledForAsc(
                configId, SyncJobType.SCHEDULED, SyncJobStatus.PENDING)).thenReturn(List.of());

        service.afterRun(finished, config);

        ArgumentCaptor<SyncJobDocument> captor = ArgumentCaptor.forClass(SyncJobDocument.class);
        org.mockito.Mockito.verify(jobRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        List<SyncJobDocument> created = captor.getAllValues();
        assertThat(created).anyMatch(job -> job.status() == SyncJobStatus.SKIPPED_OVERLAP);
        assertThat(created).anyMatch(job -> job.status() == SyncJobStatus.PENDING
                && job.scheduledFor().isAfter(Instant.now()));
    }
}
