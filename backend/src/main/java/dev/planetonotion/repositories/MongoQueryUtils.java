package dev.planetonotion.repositories;

import dev.planetonotion.repositories.MongoQueryOptions.Filter;
import dev.planetonotion.repositories.MongoQueryOptions.Sort.Direction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Converts repository query options into Spring Data Mongo queries. */
public final class MongoQueryUtils {

    private MongoQueryUtils() {
    }

    /**
     * Builds an AND-filtered Mongo query and applies the requested sort order.
     *
     * @param options optional filters and sort fields
     * @return the corresponding Spring Data query
     */
    public static Query toQuery(MongoQueryOptions options) {
        return toQuery(options, new Filter[0]);
    }

    /**
     * Builds a query with required filters plus the options supplied by the caller.
     *
     * @param options         optional filters and sort fields
     * @param requiredFilters filters that the repository always applies
     * @return the corresponding Spring Data query
     */
    public static Query toQuery(MongoQueryOptions options, Filter... requiredFilters) {
        List<Filter> filters = new ArrayList<>(List.of(requiredFilters));
        filters.addAll(options.filters());
        Query query = new Query();
        if (!filters.isEmpty()) {
            Criteria[] criteria = filters.stream()
                    .map(MongoQueryUtils::toCriteria)
                    .toArray(Criteria[]::new);
            query.addCriteria(new Criteria().andOperator(criteria));
        }
        if (!options.sorts().isEmpty()) {
            List<Sort.Order> orders = options.sorts().stream()
                    .map(MongoQueryUtils::toOrder)
                    .toList();
            query.with(Sort.by(orders));
        }
        return query;
    }

    private static Criteria toCriteria(Filter filter) {
        Criteria criteria = Criteria.where(filter.field());
        return switch (filter.operator()) {
            case EQUALS -> criteria.is(filter.value());
            case NOT_EQUALS -> criteria.ne(filter.value());
            case IN -> criteria.in((Collection<?>) filter.value());
            case GREATER_THAN_OR_EQUAL -> criteria.gte(filter.value());
            case GREATER_THAN -> criteria.gt(filter.value());
            case LESS_THAN -> criteria.lt(filter.value());
            case LESS_THAN_OR_EQUAL -> criteria.lte(filter.value());
            case EXISTS -> criteria.exists((Boolean) filter.value());
        };
    }

    private static Sort.Order toOrder(MongoQueryOptions.Sort sort) {
        Sort.Direction direction = sort.direction() == Direction.ASCENDING
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return new Sort.Order(direction, sort.field());
    }
}
