package dev.planetonotion.models.documents;

import dev.planetonotion.common.MongoCollectionConstants;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = MongoCollectionConstants.NOTION_DESTINATIONS)
public record AdHocNotionDestinationDocument(
        @Id String id,
        @Field("notion_page_id") String notionPageId,
        @Field("notion_database_id") String notionDatabaseId,
        @Field("notion_data_source_id") String notionDataSourceId,
        @Field("created_at") Instant createdAt,
        @Field("updated_at") Instant updatedAt) {

    public static final String SHARED_DESTINATION_ID = "shared_ad_hoc";
}
