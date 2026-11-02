package dev.planetonotion.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Generic AND-filter and sort options used by Mongo-backed repositories.
 *
 * @param filters conditions combined with AND
 * @param sorts   sort fields, applied in the supplied order
 */
public record MongoQueryOptions(List<Filter> filters, List<Sort> sorts) {

    public MongoQueryOptions {
        filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        sorts = List.copyOf(Objects.requireNonNull(sorts, "sorts"));
    }

    /** Returns options that do not add filters or sorting. */
    public static MongoQueryOptions empty() {
        return new MongoQueryOptions(List.of(), List.of());
    }

    /**
     * A comparison applied to one mapped document property.
     *
     * @param field    Java property name from the document's {@code Fields}
     *                 constants
     * @param operator comparison to apply
     * @param value    comparison value; collections are required for
     *                 {@link Operator#IN}
     */
    public record Filter(String field, Operator operator, Object value) {

        /** Supported filter operators. */
        public enum Operator {
            /** Exact match, including {@code null}. */
            EQUALS,
            /** Excludes the supplied value. */
            NOT_EQUALS,
            /** Matches any value in a collection. */
            IN,
            /** Matches values at or above the supplied value. */
            GREATER_THAN_OR_EQUAL,
            /** Matches values above the supplied value. */
            GREATER_THAN,
            /** Matches values below the supplied value. */
            LESS_THAN,
            /** Matches values at or below the supplied value. */
            LESS_THAN_OR_EQUAL,
            /** Matches fields that exist or are missing, based on the supplied boolean. */
            EXISTS
        }

        public Filter {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("Filter field must not be blank.");
            }
            Objects.requireNonNull(operator, "operator");
            if (operator == Operator.IN && !(value instanceof Collection<?>)) {
                throw new IllegalArgumentException("IN filters require a collection value.");
            }
            if (operator == Operator.EXISTS && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("EXISTS filters require a boolean value.");
            }
            if (operator != Operator.EXISTS && value == null && operator != Operator.EQUALS) {
                throw new IllegalArgumentException("This filter operator requires a value.");
            }
        }

        /** Creates an equality filter. */
        public static Filter equalTo(String field, Object value) {
            return new Filter(field, Operator.EQUALS, value);
        }

        /** Creates an inequality filter. */
        public static Filter notEqualTo(String field, Object value) {
            return new Filter(field, Operator.NOT_EQUALS, value);
        }

        /** Creates an inclusion filter. */
        public static Filter in(String field, Collection<?> values) {
            return new Filter(field, Operator.IN, values);
        }

        /** Creates a greater-than-or-equal filter. */
        public static Filter greaterThanOrEqual(String field, Object value) {
            return new Filter(field, Operator.GREATER_THAN_OR_EQUAL, value);
        }

        /** Creates a greater-than filter. */
        public static Filter greaterThan(String field, Object value) {
            return new Filter(field, Operator.GREATER_THAN, value);
        }

        /** Creates a less-than filter. */
        public static Filter lessThan(String field, Object value) {
            return new Filter(field, Operator.LESS_THAN, value);
        }

        /** Creates a less-than-or-equal filter. */
        public static Filter lessThanOrEqual(String field, Object value) {
            return new Filter(field, Operator.LESS_THAN_OR_EQUAL, value);
        }

        /** Creates an existence filter. */
        public static Filter exists(String field, boolean exists) {
            return new Filter(field, Operator.EXISTS, exists);
        }
    }

    /**
     * A field and direction used to order query results.
     *
     * @param field     Java property name from the document's {@code Fields}
     *                  constants
     * @param direction result ordering direction
     */
    public record Sort(String field, Direction direction) {

        /** Supported sort directions. */
        public enum Direction {
            /** Ascending result order. */
            ASCENDING,
            /** Descending result order. */
            DESCENDING
        }

        public Sort {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("Sort field must not be blank.");
            }
            Objects.requireNonNull(direction, "direction");
        }

        /** Creates an ascending sort. */
        public static Sort ascending(String field) {
            return new Sort(field, Direction.ASCENDING);
        }

        /** Creates a descending sort. */
        public static Sort descending(String field) {
            return new Sort(field, Direction.DESCENDING);
        }
    }

}
