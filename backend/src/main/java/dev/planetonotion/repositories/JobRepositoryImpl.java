package dev.planetonotion.repositories;

import dev.planetonotion.common.PlaneToNotionEnums.JobStatus;
import dev.planetonotion.common.PlaneToNotionEnums.JobType;
import dev.planetonotion.common.PlaneToNotionEnums.SyncScope;
import dev.planetonotion.models.documents.jobs.JobDocument;
import dev.planetonotion.models.documents.jobs.ScheduledSyncJobDocument;
import dev.planetonotion.models.documents.jobs.SyncJobDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * MongoTemplate implementation for job persistence and worker
 * coordination.
 */
@Repository
public class JobRepositoryImpl implements JobRepository {

    private final MongoTemplate mongoTemplate;

    /** Creates the repository with the MongoDB template used for persistence. */
    public JobRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public void holdPendingByConfigId(ObjectId configId, Instant updatedAt) {
        Query query = Query.query(Criteria.where(SyncJobDocument.FIELD_CONFIG_ID).is(configId)
                .and(SyncJobDocument.FIELD_JOB_STATUS).is(JobStatus.PENDING));
        Update update = new Update()
                .set(SyncJobDocument.FIELD_JOB_STATUS, JobStatus.HELD)
                .set(SyncJobDocument.FIELD_UPDATED_AT, updatedAt);
        mongoTemplate.updateMulti(query, update, SyncJobDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public void deletePendingAndHeldScheduledByConfigId(ObjectId configId) {
        Query query = Query.query(Criteria.where(SyncJobDocument.FIELD_CONFIG_ID).is(configId)
                .and(SyncJobDocument.FIELD_JOB_TYPE).is(JobType.SCHEDULED)
                .and(SyncJobDocument.FIELD_JOB_STATUS).in(JobStatus.PENDING, JobStatus.HELD));
        mongoTemplate.remove(query, SyncJobDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public void releaseHeldByConfigId(ObjectId configId, Instant updatedAt) {
        Query query = Query.query(Criteria.where(SyncJobDocument.FIELD_CONFIG_ID).is(configId)
                .and(SyncJobDocument.FIELD_JOB_STATUS).is(JobStatus.HELD));
        Update update = new Update()
                .set(SyncJobDocument.FIELD_JOB_STATUS, JobStatus.PENDING)
                .set(SyncJobDocument.FIELD_UPDATED_AT, updatedAt);
        mongoTemplate.updateMulti(query, update, SyncJobDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SyncJobDocument> findById(ObjectId id) {
        return Optional.ofNullable(mongoTemplate.findById(id, SyncJobDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public <T extends JobDocument> T save(T job) {
        return mongoTemplate.save(job);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<JobDocument> claimNextDueJob(Instant now) {
        Criteria scheduledNow = new Criteria().orOperator(
                Criteria.where(ScheduledSyncJobDocument.MONGO_FIELD_SCHEDULED_FOR).is(null),
                Criteria.where(ScheduledSyncJobDocument.MONGO_FIELD_SCHEDULED_FOR).lte(now));
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where(SyncJobDocument.FIELD_JOB_STATUS).is(JobStatus.PENDING),
                scheduledNow));
        query.with(Sort.by(Sort.Order.asc(SyncJobDocument.FIELD_CREATED_AT)));

        Update claim = new Update()
                .set(SyncJobDocument.FIELD_JOB_STATUS, JobStatus.RUNNING)
                .set(SyncJobDocument.FIELD_STARTED_AT, now)
                .set(SyncJobDocument.FIELD_UPDATED_AT, now)
                .inc(SyncJobDocument.FIELD_ATTEMPT_COUNT, 1);

        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                claim,
                FindAndModifyOptions.options().returnNew(true),
                JobDocument.class));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Instant> findLatestSuccessfulProjectSyncStart(ObjectId configId) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where(SyncJobDocument.FIELD_CONFIG_ID).is(configId),
                Criteria.where(SyncJobDocument.FIELD_SCOPE).is(SyncScope.PROJECT),
                Criteria.where(SyncJobDocument.FIELD_JOB_STATUS).is(JobStatus.SUCCESS),
                Criteria.where(SyncJobDocument.FIELD_STARTED_AT).exists(true)));
        query.with(Sort.by(Sort.Order.desc(SyncJobDocument.FIELD_STARTED_AT))).limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, SyncJobDocument.class))
                .map(SyncJobDocument::startedAt);
    }

    /** {@inheritDoc} */
    @Override
    public JobPage findJobs(ObjectId configId, JobStatus jobStatus, JobType jobType,
            Instant createdFrom, Instant createdTo, ObjectId beforeId, int pageSize) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where(SyncJobDocument.FIELD_JOB_TYPE).in(JobType.MANUAL, JobType.SCHEDULED));
        if (configId != null)
            filters.add(Criteria.where(SyncJobDocument.FIELD_CONFIG_ID).is(configId));
        if (jobStatus != null)
            filters.add(Criteria.where(SyncJobDocument.FIELD_JOB_STATUS).is(jobStatus));
        if (jobType != null)
            filters.add(Criteria.where(SyncJobDocument.FIELD_JOB_TYPE).is(jobType));
        if (createdFrom != null || createdTo != null) {
            Criteria createdAt = Criteria.where(SyncJobDocument.FIELD_CREATED_AT);
            if (createdFrom != null)
                createdAt.gte(createdFrom);
            if (createdTo != null)
                createdAt.lt(createdTo);
            filters.add(createdAt);
        }
        if (beforeId != null)
            filters.add(Criteria.where(SyncJobDocument.FIELD_ID).lt(beforeId));
        Query query = new Query();
        if (!filters.isEmpty())
            query.addCriteria(new Criteria().andOperator(filters.toArray(Criteria[]::new)));
        query.with(Sort.by(Sort.Order.desc(SyncJobDocument.FIELD_ID))).limit(pageSize + 1);
        List<SyncJobDocument> jobs = new ArrayList<>(mongoTemplate.find(query, SyncJobDocument.class));
        String nextBeforeId = null;
        if (jobs.size() > pageSize) {
            jobs.remove(pageSize);
            nextBeforeId = jobs.getLast().id().toHexString();
        }
        return new JobPage(jobs, nextBeforeId);
    }

    /** {@inheritDoc} */
    @Override
    public List<SyncJobDocument> findAllPendingScheduledJobs() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where(SyncJobDocument.FIELD_JOB_STATUS).is(JobStatus.PENDING),
                Criteria.where(SyncJobDocument.FIELD_JOB_TYPE).is(JobType.SCHEDULED)));
        // The shared job-status/type index narrows this to pending scheduled jobs.
        // The service chooses the earliest scheduled time per config while iterating.
        return mongoTemplate.find(query, SyncJobDocument.class);
    }

    /** {@inheritDoc} */
    @Override
    public List<SyncJobDocument> findPendingScheduledJobsByConfigId(ObjectId configId) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where(SyncJobDocument.FIELD_CONFIG_ID).is(configId),
                Criteria.where(SyncJobDocument.FIELD_JOB_STATUS).is(JobStatus.PENDING),
                Criteria.where(SyncJobDocument.FIELD_JOB_TYPE).is(JobType.SCHEDULED)));
        return mongoTemplate.find(query, SyncJobDocument.class);
    }
}
