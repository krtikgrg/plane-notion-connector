package dev.planetonotion.models.documents;

import dev.planetonotion.common.constants.MongoCollections;
import dev.planetonotion.common.enums.SyncJobStatus;
import dev.planetonotion.common.enums.SyncJobType;
import dev.planetonotion.common.enums.SyncScope;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = MongoCollections.SYNC_JOBS)
@CompoundIndex(name = "job_claim_idx", def = "{'status': 1, 'created_at': 1, 'scheduled_for': 1}")
@CompoundIndex(name = "config_runs_id_idx", def = "{'config_id': 1, '_id': -1}")
@CompoundIndex(name = "runs_status_trigger_id_idx", def = "{'status': 1, 'trigger_type': 1, '_id': -1}")
@CompoundIndex(name = "project_checkpoint_idx", def = "{'config_id': 1, 'sync_scope': 1, 'status': 1, 'started_at': -1}")
@CompoundIndex(name = "next_scheduled_jobs_idx", def = "{'status': 1, 'trigger_type': 1, 'scheduled_for': 1}")
public record SyncJobDocument(
        @Id ObjectId id,
        @Field("trigger_type") SyncJobType triggerType,
        @Field("sync_scope") SyncScope syncScope,
        SyncJobStatus status,
        @Field("config_id") ObjectId configId,
        @Field("plane_project_ref") ObjectId planeProjectRef,
        @Field("requested_task_url") String requestedTaskUrl,
        @Field("work_item_id") String workItemId,
        @Field("force_archived_config") boolean forceArchivedConfig,
        @Field("scheduled_for") Instant scheduledFor,
        @Field("started_at") Instant startedAt,
        @Field("finished_at") Instant finishedAt,
        @Field("attempt_count") int attemptCount,
        @Field("fetched_count") int fetchedCount,
        @Field("eligible_count") int eligibleCount,
        @Field("created_count") int createdCount,
        @Field("updated_count") int updatedCount,
        @Field("unchanged_count") int unchangedCount,
        @Field("failed_count") int failedCount,
        @Field("error_code") String errorCode,
        @Field("error_message") String errorMessage,
        @Field("created_at") Instant createdAt,
        @Field("updated_at") Instant updatedAt) {
}
