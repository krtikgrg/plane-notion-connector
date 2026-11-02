package dev.planetonotion.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import dev.planetonotion.repositories.MongoQueryOptions.Filter;
import dev.planetonotion.repositories.MongoQueryOptions.Sort;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

class MongoQueryUtilsTest {

    @Test
    void toQuery_combinesRequiredAndOptionalFiltersAndAppliesSorts() {
        MongoQueryOptions options = new MongoQueryOptions(
                List.of(
                        Filter.equalTo("isAvailable", true),
                        Filter.equalTo("isArchivedInPlane", false)),
                List.of(Sort.ascending("planeProjectName")));

        Query query = MongoQueryUtils.toQuery(options,
                Filter.equalTo("planeWorkspaceSlug", "demo-workspace"));

        assertThat(query.getQueryObject().getList("$and", Document.class)).containsExactly(
                new Document("planeWorkspaceSlug", "demo-workspace"),
                new Document("isAvailable", true),
                new Document("isArchivedInPlane", false));
        assertThat(query.getSortObject()).containsEntry("planeProjectName", 1);
    }

    @Test
    void toQuery_withoutFiltersOrSort_returnsAnUnconstrainedQuery() {
        Query query = MongoQueryUtils.toQuery(MongoQueryOptions.empty());

        assertThat(query.getQueryObject()).isEmpty();
        assertThat(query.getSortObject()).isEmpty();
    }

    @Test
    void toQuery_mapsSupportedComparisonOperators() {
        MongoQueryOptions options = new MongoQueryOptions(
                List.of(
                        Filter.notEqualTo("state", "closed"),
                        Filter.in("priority", List.of("high", "urgent")),
                        Filter.greaterThanOrEqual("createdAt", 10),
                        Filter.greaterThan("updatedAt", 11),
                        Filter.lessThan("createdAt", 20),
                        Filter.lessThanOrEqual("updatedAt", 21),
                        Filter.exists("planeProjectId", true)),
                List.of());

        Query query = MongoQueryUtils.toQuery(options);

        assertThat(query.getQueryObject().getList("$and", Document.class)).containsExactly(
                new Document("state", new Document("$ne", "closed")),
                new Document("priority", new Document("$in", List.of("high", "urgent"))),
                new Document("createdAt", new Document("$gte", 10)),
                new Document("updatedAt", new Document("$gt", 11)),
                new Document("createdAt", new Document("$lt", 20)),
                new Document("updatedAt", new Document("$lte", 21)),
                new Document("planeProjectId", new Document("$exists", true)));
    }
}
