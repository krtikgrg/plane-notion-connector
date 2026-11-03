package dev.planetonotion.integrations.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Typed response records for the Plane API fields used by this integration. */
public final class PlaneApiModels {

    private PlaneApiModels() {
    }

    /**
     * Minimal authenticated-user response consumed by the connection check.
     *
     * @param id Plane's identifier for the authenticated user
     */
    public record ConnectionUser(String id) {
    }

    /**
     * Common response envelope returned by paginated Plane list endpoints.
     * In offset mode, {@code next} is the numeric offset for the next request;
     * in cursor mode, {@code nextCursor} is an opaque token. These are different
     * pagination forms.
     *
     * @param data       records on this page
     * @param next       numeric offset for the next page when using offset
     *                   pagination
     * @param hasMore    whether more cursor-paginated results are available
     * @param nextCursor opaque cursor to send when requesting the next page
     * @param <T>        type of record contained in {@code data}
     */
    public record Page<T>(
            List<T> data,
            String next,
            @JsonProperty("has_more") Boolean hasMore,
            @JsonProperty("next_cursor") String nextCursor) {
    }

    /**
     * Project fields consumed by the project catalog and sync setup.
     *
     * @param id         Plane's UUID for the project
     * @param name       project name shown to users
     * @param identifier short project key used in work-item identifiers
     * @param archivedAt timestamp set when the project is archived, if present
     * @param isArchived whether Plane marks the project as archived
     */
    public record Project(
            String id,
            String name,
            String identifier,
            @JsonProperty("archived_at") String archivedAt,
            @JsonProperty("is_archived") Boolean isArchived) {
    }

    /**
     * Work-item fields consumed by sync and Notion projection logic. Related
     * state, assignee, and label details are populated when requested as expanded
     * response fields.
     *
     * @param id              Plane's UUID for the work item
     * @param identifier      human-readable key, such as {@code DEMO-9}
     * @param projectId       UUID of the work item's Plane project
     * @param stateId         UUID of its workflow state
     * @param name            work-item title
     * @param descriptionHtml description returned as HTML
     * @param priority        Plane priority value
     * @param assigneeIds     UUIDs of assigned users
     * @param labelIds        UUIDs of attached labels
     * @param targetDate      target or due date, if set
     * @param archivedAt      timestamp set when the work item is archived, if
     *                        present
     * @param updatedAt       last-update timestamp used for incremental sync
     * @param isDraft         whether Plane marks the work item as a draft
     * @param state           expanded state details, when requested
     * @param assignees       expanded assignee details, when requested
     * @param labels          expanded label details, when requested
     */
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

    /**
     * Project state fields needed to configure Notion select options.
     *
     * @param id   Plane's UUID for the state
     * @param name state name displayed in Plane
     */
    public record State(String id, String name) {
    }

    /**
     * Project label fields needed to configure Notion multi-select options.
     *
     * @param id   Plane's UUID for the label
     * @param name label name displayed in Plane
     */
    public record Label(String id, String name) {
    }

    /**
     * Expanded Plane user fields used to produce a readable assignee value.
     *
     * @param id          Plane's UUID for the user
     * @param displayName preferred display name, when available
     * @param firstName   user's first name, used as a fallback
     * @param lastName    user's last name, used as a fallback
     */
    public record Person(
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName) {
    }
}
