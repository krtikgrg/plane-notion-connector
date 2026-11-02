package dev.planetonotion.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.planetonotion.common.PlaneToNotionEnums.SyncJobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.SyncJobType;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class SyncJobRepositoryImplTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Test
    void findJobs_appliesFiltersAndUsesMongoIdKeysetPagination() {
        ObjectId configId = new ObjectId();
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");
        when(mongoTemplate.find(any(Query.class), eq(SyncJobDocument.class))).thenReturn(List.of());
        SyncJobRepositoryImpl repository = new SyncJobRepositoryImpl(mongoTemplate);

        repository.findJobs(configId, SyncJobStatus.SUCCESS, SyncJobType.MANUAL, from, to, null, 20);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(SyncJobDocument.class));
        String filter = query.getValue().getQueryObject().toString();
        assertThat(filter).contains(SyncJobDocument.FIELD_CONFIG_ID, SyncJobDocument.FIELD_JOB_STATUS, "SUCCESS",
                SyncJobDocument.FIELD_JOB_TYPE, "MANUAL", SyncJobDocument.FIELD_CREATED_AT, "$gte", "$lt");
        assertThat(query.getValue().getSortObject().get(SyncJobDocument.FIELD_ID)).isEqualTo(-1);
        assertThat(query.getValue().getLimit()).isEqualTo(21);
    }
}
