package dev.planetonotion.integrations.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Typed response records for the Plane API fields used by this integration. */
public final class PlaneApiModels {

    private PlaneApiModels() {
    }

    /** Minimal authenticated-user response consumed by the connection check. */
    public record ConnectionUser(String id) {
    }

    /** Common paginated response envelope returned by Plane list endpoints. */
    public record Page<T>(
            List<T> data,
            String next,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    /** Concrete list envelope for Plane projects. */
    public record ProjectPage(List<Project> data, String next,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    /** Concrete list envelope for Plane work items. */
    public record WorkItemPage(List<WorkItem> data, String next,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    /** Concrete list envelope for Plane project states. */
    public record StatePage(List<State> data, String next,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    /** Concrete list envelope for Plane project labels. */
    public record LabelPage(List<Label> data, String next,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    /** Project fields consumed by the project catalog and sync setup. */
    public record Project(
            String id,
            String name,
            String identifier,
            @JsonProperty("archived_at") String archivedAt,
            @JsonProperty("is_archived") Boolean isArchived) {
    }

    /** Work-item fields consumed by sync and Notion projection logic. */
    public record WorkItem(
            String id,
            String identifier,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("state_id") String stateId,
            String name,
            @JsonProperty("description_html") String descriptionHtml,
            String priority,
            @JsonProperty("assignee_ids") List<String> assigneeIds,
            @JsonProperty("label_ids") List<String> labelIds,
            @JsonProperty("target_date") String targetDate,
            @JsonProperty("archived_at") String archivedAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("is_draft") Boolean isDraft,
            State state,
            List<Person> assignees,
            List<Label> labels) {
    }

    /** Project state fields needed to configure Notion select options. */
    public record State(String id, String name) {
    }

    /** Project label fields needed to configure Notion multi-select options. */
    public record Label(String id, String name) {
    }

    /** Expanded Plane user fields used to produce a readable assignee value. */
    public record Person(
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName) {
    }
}
