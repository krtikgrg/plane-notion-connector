package dev.planetonotion.repositories.sync;

import dev.planetonotion.common.enums.SyncJobStatus;
import dev.planetonotion.common.enums.SyncJobType;
import dev.planetonotion.models.documents.SyncJobDocument;
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

public class SyncJobRepositoryImpl implements SyncJobRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public SyncJobRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<SyncJobDocument> claimNextDueJob(Instant now) {
        Criteria scheduledNow = new Criteria().orOperator(
                Criteria.where("scheduled_for").is(null),
                Criteria.where("scheduled_for").lte(now));
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("status").is(SyncJobStatus.PENDING),
                scheduledNow));
        query.with(Sort.by(Sort.Order.asc("created_at")));

        Update claim = new Update()
                .set("status", SyncJobStatus.RUNNING)
                .set("started_at", now)
                .set("updated_at", now)
                .inc("attempt_count", 1);

        return Optional.ofNullable(mongoTemplate.findAndModify(
                query,
                claim,
                FindAndModifyOptions.options().returnNew(true),
                SyncJobDocument.class));
    }

    @Override
    public Optional<Instant> findLatestSuccessfulProjectSyncStart(ObjectId configId) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("config_id").is(configId),
                Criteria.where("sync_scope").is(dev.planetonotion.common.enums.SyncScope.PROJECT),
                Criteria.where("status").is(SyncJobStatus.SUCCESS),
                Criteria.where("started_at").exists(true)));
        query.with(Sort.by(Sort.Order.desc("started_at"))).limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, SyncJobDocument.class))
                .map(SyncJobDocument::startedAt);
    }

    @Override
    public JobPage findJobs(ObjectId configId, SyncJobStatus status, SyncJobType triggerType,
            Instant createdFrom, Instant createdTo, ObjectId beforeId, int pageSize) {
        List<Criteria> filters = new ArrayList<>();
        if (configId != null) filters.add(Criteria.where("config_id").is(configId));
        if (status != null) filters.add(Criteria.where("status").is(status));
        if (triggerType != null) filters.add(Criteria.where("trigger_type").is(triggerType));
        if (createdFrom != null || createdTo != null) {
            Criteria createdAt = Criteria.where("created_at");
            if (createdFrom != null) createdAt.gte(createdFrom);
            if (createdTo != null) createdAt.lt(createdTo);
            filters.add(createdAt);
        }
        if (beforeId != null) filters.add(Criteria.where("_id").lt(beforeId));
        Query query = new Query();
        if (!filters.isEmpty()) query.addCriteria(new Criteria().andOperator(filters.toArray(Criteria[]::new)));
        query.with(Sort.by(Sort.Order.desc("_id"))).limit(pageSize + 1);
        List<SyncJobDocument> jobs = new ArrayList<>(mongoTemplate.find(query, SyncJobDocument.class));
        String nextBeforeId = null;
        if (jobs.size() > pageSize) {
            jobs.remove(pageSize);
            nextBeforeId = jobs.getLast().id().toHexString();
        }
        return new JobPage(jobs, nextBeforeId);
    }

    @Override
    public List<SyncJobDocument> findPendingScheduledJobs() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("status").is(SyncJobStatus.PENDING),
                Criteria.where("trigger_type").is(SyncJobType.SCHEDULED)));
        query.with(Sort.by(Sort.Order.asc("scheduled_for")));
        return mongoTemplate.find(query, SyncJobDocument.class);
    }
}
