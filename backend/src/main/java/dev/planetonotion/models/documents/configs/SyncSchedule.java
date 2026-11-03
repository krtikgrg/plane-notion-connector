package dev.planetonotion.models.documents.configs;

import dev.planetonotion.common.PlaneToNotionEnums.ScheduleType;
import dev.planetonotion.common.PlaneToNotionEnums.CalendarFrequency;
import dev.planetonotion.common.PlaneToNotionEnums.IntervalUnit;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Schedule window and exactly one rule for a sync configuration. The rule
 * matching {@code type} is populated;
 * the other rule is null. Schedule evaluation uses UTC in this prototype.
 */
public record SyncSchedule(
        /** Selects interval-based or calendar-based recurrence. */
        @Field("type") ScheduleType type,

        /** Optional inclusive start boundary and interval cadence anchor. */
        @Field("start_at") Instant startAt,

        /** Optional exclusive end boundary. */
        @Field("end_at") Instant endAt,

        /** Interval rule; populated only when {@code type} is {@code INTERVAL}. */
        @Field("interval") Interval interval,

        /** Calendar rule; populated only when {@code type} is {@code CALENDAR}. */
        @Field("calendar") Calendar calendar) {

    /** Fixed cadence repeated every {@code value} minutes or hours. */
    public record Interval(
            /** Positive number of units between occurrences. */
            @Field("value") Integer value,

            /** Cadence unit, restricted to minutes and hours by this enum. */
            @Field("unit") IntervalUnit unit) {
    }

    /** Daily or selected-weekday recurrence evaluated at a recurring UTC time. */
    public record Calendar(
            /** Daily or weekly recurrence frequency. */
            @Field("frequency") CalendarFrequency frequency,

            /** Every N days for daily recurrence or every N weeks for weekly recurrence. */
            @Field("interval") Integer interval,

            /**
             * Weekdays used for weekly recurrence; daily recurrence does not use this list.
             */
            @Field("days_of_week") List<DayOfWeek> daysOfWeek,

            /**
             * UTC time of day, not an absolute timestamp or a timezone-aware local time.
             */
            @Field("time_of_day") LocalTime timeOfDay) {
    }

}
