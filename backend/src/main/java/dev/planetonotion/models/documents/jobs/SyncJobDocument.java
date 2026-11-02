package dev.planetonotion.models.documents.jobs;

import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Field;

/** Common persisted fields for manual and scheduled sync runs. */
public abstract class SyncJobDocument extends JobDocument {

    /** Project-wide or single-work-item scope of this run. */
    @Field("scope")
    private final SyncScope scope;

    /** Mongo ID of the cached Plane project used as this run's source. */
    @Field("plane_project_document_id")
    private final ObjectId planeProjectDocumentId;

    /** Outcome counts for eligible work items. */
    @Field("result")
    private final SyncJobResult result;

    /** Java property name for the sync scope. */
    public static final String FIELD_SCOPE = "scope";

    /** Initializes fields shared by sync job subtypes. */
    protected SyncJobDocument(ObjectId id, JobType jobType, SyncScope scope, JobStatus jobStatus,
            ObjectId configId, ObjectId planeProjectDocumentId, Instant startedAt, Instant finishedAt,
            int attemptCount, SyncJobResult result, String errorCode, String errorMessage,
            Instant createdAt, Instant updatedAt) {
        super(id, jobType, jobStatus, configId, startedAt, finishedAt, attemptCount, errorCode, errorMessage,
                createdAt, updatedAt);
        this.scope = scope;
        this.planeProjectDocumentId = planeProjectDocumentId;
        this.result = result;
    }

    /** Returns whether this run targets a project or one work item. */
    public SyncScope scope() {
        return scope;
    }

    /** Returns the Mongo ID of the source Plane project. */
    public ObjectId planeProjectDocumentId() {
        return planeProjectDocumentId;
    }

    /** Returns outcome counts for the run. */
    public SyncJobResult result() {
        return result;
    }
}
