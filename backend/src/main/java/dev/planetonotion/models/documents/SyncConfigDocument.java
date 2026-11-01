package dev.planetonotion.models.documents;

import dev.planetonotion.common.constants.MongoCollections;
import dev.planetonotion.common.enums.ConfigStatus;
import dev.planetonotion.common.enums.ScheduleType;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = MongoCollections.SYNC_CONFIGS)
public record SyncConfigDocument(
        @Id ObjectId id,
        ConfigStatus status,
        @Indexed(name = "plane_project_config_unique", unique = true)
        @Field("plane_project_ref") ObjectId planeProjectRef,
        @Field("notion_project_page_id") String notionProjectPageId,
        @Field("notion_database_id") String notionDatabaseId,
        @Field("notion_data_source_id") String notionDataSourceId,
        @Field("sync_options") Map<String, Boolean> syncOptions,
        @Field("schedule_type") ScheduleType scheduleType,
        @Field("interval_value") Integer intervalValue,
        @Field("interval_unit") String intervalUnit,
        @Field("calendar_frequency") String calendarFrequency,
        @Field("calendar_interval") Integer calendarInterval,
        @Field("calendar_days_of_week") List<String> calendarDaysOfWeek,
        @Field("calendar_time_of_day") LocalTime calendarTimeOfDay,
        @Field("start_at") Instant startAt,
        @Field("end_at") Instant endAt,
        @Field("created_at") Instant createdAt,
        @Field("updated_at") Instant updatedAt) {
}
