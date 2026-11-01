# Take-Home Assignment Context

## Purpose

This file is the working source of truth for planning and completing the Notion integration take-home assignment. It records the assignment, ideas considered, decisions, open questions, and implementation plan. Update it as decisions are made so the final demo and explanation stay aligned.

### Design principle

Keep the prototype simple, logical, and easy to explain. Add a field, endpoint, class, collection, abstraction, or runtime component only when it serves a clear current use case. Prefer a small number of cohesive components over decorative structure or speculative extensibility; simplicity is the quality bar, not a reason to make the design look less capable.

## Assignment

Build an integration, migration, or automation between Notion and a system of our choice (excluding an existing Notion marketplace integration) that solves a business or operational problem. Explain the business value and give a brief demo showing Notion knowledge, engineering proficiency, and an end-to-end design. The recruiter describes the exercise as a 30-minute technical integration scenario.

## Goals and constraints

- Deliver a focused, demonstrable integration with a clear user and business problem.
- Show a complete path from the source system, through the integration, into a useful Notion representation.
- Keep the scope realistic for the assignment and make the design easy to explain.
- Keep the external source authoritative where it owns the underlying data or operational state.
- Protect credentials. Do not read, copy, print, commit, or include `.env` contents in chat or project documentation.

## Current implementation state (2026-11-01)

This section is the current source of truth; the dated change log below preserves earlier decisions and may describe superseded states.

- The prototype is implemented as a Java 21 / Spring Boot service with a React + TypeScript / Vite UI. Spring Boot serves the production UI and API, and one in-process worker polls MongoDB jobs; there is no separate scheduler service.
- Users can create, list, and edit multiple project configurations; view per-config and paginated all-config run history; enqueue a project sync; and enqueue one task by Plane URL. Each config selects one workspace/project and provisions its Notion destination. Manual task URLs use that config when present, or a shared ad hoc destination otherwise.
- MongoDB stores workspace/project catalog entries, sync configs, jobs/run history, and source-to-Notion mappings. Checkpoint-based incremental sync is implemented for active, non-draft work items: Plane reads are paged at 200, the lower `updated_at` bound has 100 ms overlap, and the upper bound is fixed at run start. Archived items and drafts are excluded. Archive/unarchive can leave `updated_at` unchanged, so detecting reactivation requires the deferred reconciliation/events design discussed for the interview.
- Notion layout is parent page → workspace pages → project pages and per-project data sources. Workspace and parent summaries roll up configured project status and mapped item counts. The shared ad hoc page/database/data source are provisioned lazily for the first unconfigured task URL and stored in Mongo `notion_destinations`; later runs reuse those IDs. Config-specific Notion resource IDs remain on each config.
- Plane and Notion credentials and the Notion parent ID are local environment configuration. Notion API calls use only `NOTION_CONNECTION_API_TOKEN`; there is no legacy alias or personal-token fallback. Optional legacy ad hoc IDs are imported to Mongo once for existing local installations, but are not part of fresh setup. `.env` is ignored by Git and excluded from Docker build context; `.env.example` contains variable names only. Do not copy `.env` values into this file, GitHub, or chat.
- Runtime integration settings are collected in one `IntegrationProperties` record; integration enum values live in `PlaneToNotionEnums`. Controllers call services only. `PlaneService` is the application boundary around `PlaneClient`, and shared `HttpService` executes HTTP requests for the Plane and Notion clients. Cohesive multi-file sync/repository packages remain grouped; one-file packages were flattened.
- Spring Data supplies the implementation of simple derived repositories such as `PlaneWorkspaceRepository`. The `*RepositoryCustom` fragments and `*RepositoryImpl` classes remain for MongoTemplate operations that need atomic job claims, custom paginated queries, aggregation, or bulk upserts.
- `Dockerfile` builds the application image. `compose.yaml` runs the app with MongoDB as a separate service and a persistent Mongo volume. The image built successfully and Compose configuration validated on 2026-11-01; that packaging build skipped test execution.
- The project is in private repository [krtikgrg/plane-notion-connector](https://github.com/krtikgrg/plane-notion-connector). The initial code and docs are proposed in [PR #1](https://github.com/krtikgrg/plane-notion-connector/pull/1), which is open. Account-specific Notion IDs were removed from source and the PR branch was rewritten; workspace/project identifiers in this context were generalized.

## Ideas considered

The initial brainstorm includes:

- **Plane → Notion:** Synchronize company work items and updates from a self-hosted Plane instance into Notion.
- **Airflow → Notion:** Show DAG status and run history; operational controls could be a later extension.
- **Substack → Notion:** Save selected publication posts into a personal reading database using RSS.
- **Insomnia → Notion:** Publish API collections/specifications as structured API documentation.
- **VictoriaLogs → Notion:** Publish filtered incident summaries and trends, not raw log streams.
- **Company wiki → Notion:** Import/synchronize pages, subject to identifying the wiki and preserving access permissions.
- **Expense tracking → Notion:** Normalize and reconcile transactions from sources such as bank alerts/statements, Splitwise, or app exports.

The discussion notes favor one-way synchronization for an initial build, retain the source system as authoritative, and recommend deduplication, provenance, incremental updates, retries, and explicit permissions. Raw logs and unrestricted operational controls are poor initial scopes for Notion.

## Current decision record

### Selected idea

**Plane → Notion, one-way synchronization of selected work items.** This is the selected direction for planning and implementation, based on the assignment and the available information about the source environment.

### Why Plane is the leading candidate

- It addresses a concrete company workflow: making Plane work visible to stakeholders in Notion.
- Its business value depends on there being stakeholders who need a Notion-based project view but do not work directly in Plane; use this as the stated target audience in the demo.
- It naturally demonstrates an end-to-end integration: source event or API fetch, validation, field mapping, idempotent create/update, and a linked Notion record.
- A one-way MVP has a clear ownership model and avoids the conflict resolution required by two-way sync.
- The source notes identify Plane as a strong MVP candidate. Its self-hosted deployment means API base URL, authentication, and webhook availability must be confirmed before implementation.
- Notion's current official Connections gallery highlights task integrations for products such as Jira, Asana, and ClickUp. Searches did not surface a Plane → Notion connection in the official gallery. Plane's own marketplace does list a Notion importer, but that moves Notion pages into Plane and is the opposite direction. Recheck the galleries before submission in case they change.
- Plane is accessible only from the company's internal network. A Worker hosted on Notion's infrastructure cannot directly call that private endpoint, so the integration runtime should run inside the network (or on a host with private network access) and send outbound requests to Notion. Do not expose the internal Plane API just to make a hosted Worker work.

### Initial scope

- Synchronize a small, defined set of Plane work items into a Notion database.
- Map a stable Plane identifier, title, status, assignee, priority/project where available, source URL, and last-updated time.
- Create or update the matching Notion page using the Plane identifier as the deduplication key.
- Include a small demo dataset or a clear live demonstration path.
- Keep Notion read-only with respect to Plane for the MVP; no write-back or broad historical migration unless the assignment needs it.
- Run a small integration service or scheduled job from inside the network. Start with polling Plane and outbound Notion API calls; this avoids requiring a public inbound endpoint for Plane webhooks.
- Build in phases. Phase 1 is a hostable service, currently run locally, that supports Plane Cloud or self-hosted Plane according to network reachability. Phase 2 is a Notion Worker for a reachable Plane Cloud/personal demo workspace. Keep runtime-specific scheduling/authentication separate; share normalization and mapping logic where the runtimes allow it.
- Use a Notion internal connection for the hostable service's team-owned API identity, scoped to the destination database. Use a PAT only for local developer exploration/CLI use where acting as the developer is appropriate; don't embed a developer PAT in runtime code.
- Use the Notion CLI as an optional developer tool for Worker development/deployment and API exploration, not as the hostable service runtime.
- Phase 1 service includes a thin local control page for config creation/editing, run health, config status (`ACTIVE`, `DISABLED`, or `ARCHIVED`), and on-demand sync of one task by Plane URL. A URL-only manual request resolves the task's workspace/project and uses the matching config if one exists; otherwise it writes to one shared manual-only/ad hoc Notion page/database for all unconfigured projects.
- Support multiple project config records, but use one Plane deployment and one Notion workspace for this local prototype. Plane API base URL and token are supplied globally through `.env`; no Plane instance-profile collection or per-config secret reference is needed now. Validate pasted Plane URLs against the configured Plane host, then use a factory to route workspace, project, and work-item URLs to the correct resolver. A workspace URL/slug loads the project's catalog; a project URL resolves and upserts one project record; a manual work-item URL must resolve to a non-archived, non-draft item. Do not scan a workspace when direct item lookup fails. The `plane_projects` Mongo collection caches metadata by `(workspace_slug, project_id)`. The UI has a manual “Resync projects” action. A backend unique constraint prevents more than one config for the same workspace/project across all config statuses; duplicate attempts direct the user to the existing config. The Notion parent comes from `NOTION_PARENT_PAGE_ID`; config creation provisions the child project page and database automatically. Each config has one JSON `sync_options` object with defined boolean resource keys (initially `work_items: true`, `pages: false`); use nested resource settings only if they become necessary. Config status is `ACTIVE`, `DISABLED`, or `ARCHIVED`; there is no separate schedule-enabled boolean. A future `start_at` anchors the first scheduled occurrence, otherwise config creation time is the anchor. Repeat at a fixed cadence (initially every 30 minutes), with optional start/end bounds. For this prototype, interpret calendar schedules in UTC; timezone-aware recurrence is a high-level discussion item. Keep derived `next_run_at` out of config. Defer Plane instance profiles until the service needs multiple Plane deployments or customer-specific Plane connections; at that point a profile can hold deployment metadata and a reference to a secret manager entry.
- Use MongoDB for configs and durable sync jobs/run history. The single local in-process worker claims and processes jobs synchronously, so manual requests wait behind active work and scheduled occurrences during that work are recorded as `SKIPPED_OVERLAP`. An on-demand URL request may have `config_id: null`; save the URL, resolve the work item and its project, then match a config by workspace/project. If a config exists, write to its destination; otherwise write to one shared manual-only/ad hoc destination, whose rows carry workspace and project identity. Later config creation intentionally creates its own destination; it need not reuse or migrate rows from the shared ad hoc database. The shared ad hoc database has no schedule. The “Force run on archived config” checkbox is present on both project and task manual-run forms. The backend allows a run against an `ARCHIVED` config only when the submitted force flag is true; otherwise it returns an error. For non-archived configs/ad hoc runs, the flag has no effect. The API/service creates or replaces the first pending scheduled job when a config is created, activated, or its schedule changes. The queue worker marks jobs `SUCCESS` or `FAILED`, then creates the next scheduled job if the config remains `ACTIVE` and within its schedule window. Manual jobs do not chain. Keep at most one pending scheduled job per config; updates replace pending scheduled jobs without changing an in-progress job. At completion, enumerate missed scheduled occurrences as `SKIPPED_OVERLAP` reports and queue the next future occurrence. Multi-instance/per-config locking and stuck-run recovery are production-hardening topics, outside the local prototype.
- If a config remains busy for a long time (for example, more than a day), the skipped-occurrence records could grow substantially. Cleanup/retention or aggregation is a production-hardening discussion item, not a Phase 1 implementation requirement. Timezone-aware calendar scheduling and DST behavior are high-level discussion items; the prototype evaluates calendar rules in UTC. Missed-run recovery after service outages is also out of scope for the local demo; discuss these reliability choices in the final write-up. Keep Elasticsearch deferred.
- MongoDB connection string, Plane API URL/token, Notion connection token, and the Notion parent page ID are supplied through `.env` for local development. The app provisions the shared ad hoc destination under that parent on first use and stores its page/database/data-source IDs in Mongo; existing optional `NOTION_AD_HOC_*` values are a one-time migration path only. Mongo stores no raw credentials. Never make arbitrary user-supplied URL hosts into outbound requests. Database and collection names are constants in source code. Do not print credential values or include them in chat/project documentation. If multi-deployment support is added later, Mongo can store a secret reference (the environment-variable/secret-manager key name only) in a Plane instance profile, while the secret value remains outside the database.
- The local UI has a configs list page, a per-config edit/detail page with that config's runs, and a separate all-runs page with user filters. `NOTION_PARENT_PAGE_ID` is the configured parent for generated Notion content. Each config gets its own child project page and inline task database. Separately, create one shared ad hoc child page/database under the parent for manual URL requests whose projects have no config; it can contain rows from multiple projects, with workspace/project metadata on each row. A later config for one of those projects gets a separate destination, so duplicate task representation across the shared ad hoc database and configured database is acceptable. The parent index links to configs and the shared ad hoc page. Keep generated summary/index content in clearly managed sections so user-authored content is not overwritten.
- The provided production Plane notes are inspiration for generic field/state/priority mapping only. Do not copy company tasks, member lists, project IDs, or other internal details into this personal interview project. Use synthetic tasks in personal Plane Cloud.
- Phase 2 Worker targets reachable Plane Cloud and provides scheduled-sync runtime there; its run history can be inspected through Notion's Worker tooling. Validate Worker config and single-task on-demand paths after the local service is established. Keep service-specific features (such as MongoDB reporting) out of the Worker unless a clear need emerges.
- Prototype scope: one Plane project per config, active non-draft work items only, scheduled sync controlled by config status, and on-demand sync by task URL. Archived work items and drafts are excluded. Comments/activity history and attachments are follow-on items. Notion → Plane write-back is out of implementation scope, though its design can be explored separately alongside attachments.
- MongoDB stores the workspace/project catalog, configs, durable sync jobs/run history, and `sync_mappings`. Each mapping records the source ID, target Notion page ID, target data-source ID, and source hash, enabling idempotent upserts and hash-based unchanged detection without querying Notion for every source item. Elasticsearch is not needed for this prototype.
- A personal Free Notion workspace is suitable for a synthetic-data proof of concept. Use the company workspace and approved data for the final company-facing demo when access is available.
- Enterprise AI credits are optional product exploration, not an integration dependency. Keep the sync deterministic; a Notion AI summary of task/activity changes could be a later demonstration if workspace policy permits.

## Other candidates and trade-offs

| Idea | Strength | Main constraint |
|---|---|---|
| Airflow → Notion | Distinctive operations use case; valuable high-level DAG visibility | Actions such as pause/trigger need careful authorization, state checks, and audit logging; read-only is a safer initial scope |
| Substack → Notion | Straightforward feed-based ingestion and an easy personal knowledge-management demo | Less connected to a company operational problem; feed content varies |
| Insomnia → Notion | Clear developer productivity use case; useful structured API docs | Export/API details depend on the targeted Insomnia setup |
| VictoriaLogs → Notion | Relevant incident summaries and service-level trends | Must aggregate; raw log volume is unsuitable for Notion |
| Wiki → Notion | Potentially useful knowledge consolidation | Source platform and permission mapping are unknown |
| Expense tracking → Notion | Concrete personal finance use case with deduplication/reconciliation depth | Multiple data sources, sensitive data, and platform/policy constraints make the scope larger |

## Plan

1. **Build the focused Phase 1 story:** users create and manage multiple configs, each selecting one Plane workspace/project and one Notion destination. Selected work items sync on a schedule or on demand. Plane remains authoritative.
2. **Use an isolated demo source:** configure a Plane Cloud or self-hosted instance through environment variables, and select a non-production workspace and project in the UI. Workspace and project names are not hard-coded. Use synthetic data only. Configure the Plane API URL/token and Notion connection through `.env`; do not add a Plane instance-profile collection or store credentials in Mongo. The service can later support self-hosted Plane by changing the environment configuration if the network permits.
3. **Define the config contract and flow:** user enters a workspace URL/slug or a project URL; a URL-handler factory identifies workspace/project/work-item input and routes it to the appropriate resolver. A workspace input loads that workspace's project catalog; a project URL resolves/upserts one project. The “Resync projects” button refreshes metadata for the selected workspace. Validate that submitted hosts match the one configured Plane base URL, test API access, and use `(workspace_slug, project_id)` as the unique catalog key. The config stores only `plane_project_ref` plus Notion destination IDs, `sync_options` such as `{"work_items": true, "pages": false}`, status (`ACTIVE`, `DISABLED`, `ARCHIVED`), and schedule fields; it never duplicates project metadata or stores raw credentials. Config creation provisions the Notion child page/database below `NOTION_PARENT_PAGE_ID`.
4. **Implement the sync contract:** use Plane v2 work-item `id` as the Notion upsert key; use the proposed schema below; resolve task URLs to workspace/project; sync only active non-draft items; use checkpoint-based incremental reads after the initial scan and periodic full reconciliation only as a deferred discussion item.
5. **Define Mongo collections and state transitions:** `plane_projects` catalog keyed by workspace slug/project ID, `sync_configs` and `sync_jobs` referencing the catalog, `sync_mappings` mapping a Plane work-item ID to its Notion page within a target data source, and one `notion_destinations` record for the shared ad hoc page/database/data source. Ensure one in-progress sync per config or shared ad hoc destination; manual requests wait in the queue while scheduled occurrences for that destination are recorded as `SKIPPED_OVERLAP`. Defer long-run cleanup/retention and robust outage recovery to production hardening.
6. **Define scheduling and queue behavior:** config creation or activation creates the first pending scheduled job. Its anchor is explicit `start_at`, if present, or config creation time otherwise. Start is inclusive; end is exclusive. Repeat on the configured fixed cadence; update/cancel pending work on schedule changes or `DISABLED`/`ARCHIVED` status. The queue worker marks completion and creates the next future job when the config remains `ACTIVE` and within its window. Manual sync is allowed while `DISABLED`; overlapping manual and scheduled runs are serialized by `sync_jobs`. Use a sleeping timer and wake-up signal or bounded queue poll, not a tight polling loop. Missed-run recovery is out of scope for the local demo.
7. **Define the local UI/API contract:** configs list; create/edit/detail per config; Plane connection test using `.env` credentials; workspace input/selection and project picker backed by the catalog; “Resync projects” action; status controls (`ACTIVE`, `DISABLED`, `ARCHIVED`); request a full project sync or one task URL; a URL-only manual route with project resolution and shared ad hoc destination; run history per config; and an all-runs page with filters. Always show the “Force run on archived config” checkbox on manual-run forms; backend validation enforces it only for archived configs. Validate the submitted host against the one configured Plane base URL, then require the resolved Plane task to be active and non-draft.
8. **Choose and design the Notion destination:** use `NOTION_PARENT_PAGE_ID` as the shared parent. Create one child project page per config, then create that config's inline task database with the compact data-source schema below. On first unconfigured manual task sync, create one shared ad hoc page and inline database, adding workspace/project identity columns because rows can come from multiple projects. Store the shared destination IDs in one `notion_destinations` Mongo record and each configured project's IDs in `sync_configs`. Start with the default table view; a board grouped by Plane state is an optional enhancement after API validation. Add a generated summary to each project page and a parent-page index/TOC linking to all project pages. Field mapping customization is deferred.
9. **Inspect public Plane API docs and verify runtime compatibility:** use the current public v2 reference as the primary source; check auth, base URL/version support for Cloud and self-hosted Plane, available fields/relations, archived listing, pagination, update filters, and deletion events. Treat the internal Plane skill as generic context only; do not copy internal data or conventions into the demo. Prefer polling/reconciliation for the local service unless an authenticated, reachable webhook path is available.
10. **Demonstrate the implemented vertical slice:** manual task-URL sync → idempotent Notion create/update → Mongo run record → UI result, plus scheduled checkpoint-based sync, overlap/run history, and config status changes. Archive/unarchive reconciliation limits and deleted/moved item handling remain explicit discussion points.
11. **Prepare the 30-minute narrative:** problem and audience, architecture/network boundary, live sync, re-run/idempotency, run history and scheduler, trade-offs and roadmap.

## Implementation checks and remaining details

- The selected test project is `Demo Project` in workspace `demo-workspace`. Its standard work item fields are title, description, state, priority, assignees, labels, start date, due date, cycle, and modules. Project settings expose states, labels, estimates, work item types, templates, and workflows. Work item types/custom properties are currently off; keep them out of the first schema. Per-config custom field mapping remains deferred; the proposed fixed schema is below.
- The project currently has the five state groups `Backlog`, `Unstarted`, `Started`, `Completed`, and `Cancelled`; its states include `Backlog`, `Todo`, `In Progress`, `Done`, and `Cancelled`. Added custom state `Ready for Review` under `Started`. Preserve both the exact Plane state name and its group in Notion/reporting so custom states are not lost or hard-coded away.
- The project came with seven Plane sample work items. Added demo tasks `DEMO-8` (Todo, High) and `DEMO-9` (Ready for Review, Medium), with descriptions; keep these and the starter data for the sync demonstration.
- Confirmed reconciliation behavior: preserve the task title, and represent `Active`, `Archived in Plane`, `Deleted in Plane` (only with a confirmed delete event), and `Missing from configured project` (reconciliation only). A complete successful scan sets missing; only a confirmed delete webhook sets deleted. Never delete the corresponding Notion page during sync.
- For this local prototype, polling/reconciliation remains the fallback because a local Plane service cannot receive a webhook unless Plane can reach a public callback. Distinguish deletion from a move/out-of-scope condition if a workspace-wide lookup can confirm it.
- Project catalog behavior is settled: refresh only the selected workspace on user request; upsert metadata and last-refreshed time; retain unseen entries as unavailable rather than deleting catalog history; show active projects by default and keep archived projects out of new config selection.
- Plane API check: the updated all-workspace credential returns 200 for identity, workspace project listing, project states, and work-item listing in `demo-workspace`. The earlier workspace-specific 401 is resolved. The test project has nine work items and six states, including `Ready for Review`; no credential value was printed.
- Notion write validation passed with the configured connection token and Notion API version `2026-03-11`: temporary page, database, and database row were each created successfully and then deleted via the API (all responses 200). Notion's API deletion moves objects to Trash. This verifies page/database/row writes and cleanup; test page updates and summary/index refresh during implementation. No sample objects were left outside Trash.
- The Notion database creation request used `POST /v1/databases` with an `initial_data_source` schema and succeeded. The current object model separates a database container, data sources (each owns schema and rows), and views. Although the create-database page appears under the “Databases (deprecated)” navigation group, its current request schema exposes `initial_data_source` and the latest API version (`2026-03-11`). Do not use the legacy flat database-properties model. For this prototype, create one inline database under the config page with its initial data source/schema; use data-source APIs for querying or changing that schema. A separate API view is optional—the data-source docs say a standard table view is created automatically, and the prototype needs no custom view setup for the first cut.
- Read Plane and Notion credentials directly from `.env` in this single-connection prototype; no Mongo secret-reference lookup is needed. Keep Plane's personal access token backend-only; Plane documents API keys as full-owner-permission credentials rather than scope-limited keys.

### Final agreed Notion work-item schema

Each configured project page contains one inline work-items database/data source. Each row is one Notion page for one Plane work item. Keep a small common work-item schema; the shared manual-only data source adds project identity fields because it contains items from multiple projects.

| Property | Notion type | Source / purpose |
|---|---|---|
| `name` | `title` | Plane work-item name and Notion page title. |
| `identifier` | `rich_text` | Human key such as `DEMO-9`. |
| `work_item_id` | `rich_text` | Plane work-item UUID; unique within this project's data source. |
| `work_item_url` | `url` | Direct Plane link. |
| `description` | `rich_text` | Plain-text Plane description. |
| `state` | `select` | Exact Plane state name, including custom states; use as the board grouping field if we add a board. |
| `priority` | `select` | Plane priority; map Plane's `none` value to an empty Notion select. |
| `assignees` | `rich_text` | Readable assignee names/handles, joined into text. |
| `labels` | `multi_select` | Plane label names. |
| `due_date` | `date` | Plane target/due date, when set. |
| `plane_updated_at` | `date` | Source update timestamp for sorting and freshness. |
| `plane_lifecycle` | `select` | Integration enum: `ACTIVE` or `MISSING_FROM_PROJECT`. An active-only scan cannot distinguish an archived, deleted, or moved item; resolving those cases is a discussion pointer. |

The configured project's data source uses just these properties. The shared ad hoc data source additionally has `workspace_slug`, `project_id`, and `project_name` (`rich_text`) so entries from multiple projects remain identifiable. Notion data sources do not enforce uniqueness; Mongo `sync_mappings` provides the source-item-to-target-page lookup, scoped by data source. A serialized sync per destination prevents concurrent create races.

Configure Plane state/label options from the selected project's metadata; add newly observed options as needed and retain old options so historical rows remain readable. Keep source row IDs and last-sync/run state in their authoritative systems rather than duplicating them as Notion properties.

Defer cycle, modules, estimate, parent item, work-item type, custom fields, comments/activity, and attachments. Cycles/modules/estimate require additional relationship data and are not essential to the first end-to-end story. Keep run outcomes/counts in Mongo `sync_jobs` and the generated project summary rather than adding more task-row properties.

### Data contracts and JSON examples

Use `snake_case` for app-owned fields through the Plane adapter, TypeScript models, MongoDB, and Notion property names. Plane already returns snake_case, so retain that naming in the API DTO and enrich it without renaming. Mongo automatically reserves `_id`; all other fields use snake_case. Date values shown below as ISO strings are stored as BSON Dates in Mongo.

All integration-owned enum constants use uppercase values (`INTERVAL`, `CALENDAR`, `WEEKLY`, `PENDING`, `ACTIVE`, etc.). Preserve Plane-owned values such as state names and the lowercase priority enum exactly as Plane returns them. Prototype timestamps and schedule evaluation use UTC; timezone-aware scheduling and DST behavior are deferred to the high-level discussion. The `plane_project_ref` examples below stand for BSON ObjectIds referencing `plane_projects`.

MongoDB uses these collections. Completed and skipped jobs remain in `sync_jobs` and provide both the durable queue and run-history views; there is no separate `sync_runs` collection in the first cut.

#### `sync_configs` — one integration config per Plane project

```json
{
  "_id": "<object_id>",
  "status": "ACTIVE",
  "plane_project_ref": "<plane_projects_object_id>",
  "notion_project_page_id": "<notion_project_page_id>",
  "notion_database_id": "<notion_database_id>",
  "notion_data_source_id": "<notion_data_source_id>",
  "sync_options": {
    "work_items": true,
    "pages": false
  },
  "schedule_type": "INTERVAL",
  "interval_value": 30,
  "interval_unit": "MINUTES",
  "calendar_frequency": null,
  "calendar_interval": null,
  "calendar_days_of_week": [],
  "calendar_time_of_day": null,
  "start_at": null,
  "end_at": null,
  "created_at": "2026-10-31T12:00:00.000Z",
  "updated_at": "2026-10-31T12:00:00.000Z"
}
```

For a calendar schedule, set `schedule_type` to `CALENDAR`, use an uppercase `calendar_frequency` such as `WEEKLY`, and populate the calendar fields; interval fields are null. Interpret calendar rules in UTC for the prototype. `start_at` is inclusive; `end_at` is exclusive. If `start_at` is null for an interval, `created_at` is the cadence anchor. Do not store `next_run_at` on the config; the worker derives it from the schedule and job history. The unique config index is on `plane_project_ref`, including disabled and archived configs; the catalog itself is unique on `workspace_slug + project_id`.

#### `plane_projects` — cached Plane project catalog for the picker

```json
{
  "_id": "<object_id>",
  "workspace_slug": "demo-workspace",
  "project_id": "<plane_project_uuid>",
  "name": "Demo Project",
  "identifier": "PLANE",
  "is_archived": false,
  "is_available": true,
  "last_refreshed_at": "2026-10-31T12:00:00.000Z",
  "created_at": "2026-10-31T12:00:00.000Z",
  "updated_at": "2026-10-31T12:00:00.000Z"
}
```

Unique index: `workspace_slug + project_id`. Keep an unseen project as unavailable instead of deleting its catalog history. Build the project URL when needed from the Plane app base URL, workspace slug, and project ID; do not persist this derived value.

There is no separate `plane_workspaces` collection in the prototype. Plane API project endpoints require a workspace slug, so the config flow obtains one from a known local workspace list, a user-entered workspace URL/slug, or a pasted project URL. Store the slug on each cached project and use it to scope API calls. I did not find a general list-all-workspaces endpoint in the current v2 docs reviewed, so an empty workspace collection would not discover additional workspaces by itself. Add a workspace catalog only if the app later needs workspace names/metadata or managed onboarding beyond the known-slug/input flow.

#### `sync_jobs` — queue entries and durable run history

```json
{
  "_id": "<object_id>",
  "trigger_type": "MANUAL",
  "sync_scope": "WORK_ITEM",
  "status": "PENDING",
  "config_id": null,
  "plane_project_ref": null,
  "requested_task_url": "<plane_work_item_url>",
  "work_item_id": null,
  "force_archived_config": false,
  "scheduled_for": null,
  "started_at": null,
  "finished_at": null,
  "attempt_count": 0,
  "fetched_count": 0,
  "eligible_count": 0,
  "created_count": 0,
  "updated_count": 0,
  "unchanged_count": 0,
  "failed_count": 0,
  "error_code": null,
  "error_message": null,
  "created_at": "2026-10-31T12:00:00.000Z",
  "updated_at": "2026-10-31T12:00:00.000Z"
}
```

After URL resolution, set `plane_project_ref` and `config_id` if a config exists; leave `config_id` null for the shared ad hoc destination. Destination is derived from whether a config reference exists. Project identity is joined from `plane_projects`, rather than copied into every job. Project jobs use `sync_scope: "PROJECT"`; scheduled jobs use `trigger_type: "SCHEDULED"` and set `scheduled_for`. Status values: `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `SKIPPED_OVERLAP`. Keep counts and sanitized error details as flat fields. Indexes cover job claiming, config/status-filtered `_id` history, the successful project checkpoint lookup, and pending scheduled jobs.

#### Ad hoc Notion destination — one Mongo record

The prototype has exactly one shared ad hoc destination. On first use, the service creates its page and inline database under `NOTION_PARENT_PAGE_ID`, then saves the returned IDs in Mongo. Existing local installations can seed the record from all three legacy IDs once; fresh installs do not set those values.

```json
{
  "_id": "shared_ad_hoc",
  "notion_page_id": "<notion_page_id>",
  "notion_database_id": "<notion_database_id>",
  "notion_data_source_id": "<notion_data_source_id>",
  "created_at": "2026-10-31T12:00:00.000Z",
  "updated_at": "2026-10-31T12:00:00.000Z"
}
```

Persist all three Notion IDs: the project page ID (summary/index), database ID (container-level operations and views), and data-source ID (task row operations and schema changes). The database ID is retained even though the first cut uses the default table view, so later database management or custom views can use it without changing the config model.

#### `sync_mappings` — Plane work item to Notion page lookup

Keep this collection to one mapping per source work item per Notion data source. This avoids repeating Notion queries on ordinary syncs and supports the intentional case where a Plane item appears in both its configured project database and the shared ad hoc database. A mapping with a null `target_id` represents a pending create: it is persisted before the Notion create request so a retry can recover a page if the create succeeded but its response was lost.

```json
{
  "_id": "<object_id>",
  "source_id": "<plane_work_item_uuid>",
  "target_id": "<notion_page_id_or_null_while_create_is_pending>",
  "target_data_source_id": "<notion_data_source_id>",
  "source_hash": "<sha256_of_synced_properties>"
}
```

Create a unique compound index on `target_data_source_id + source_id`. `source_id` is the Plane work-item UUID; `target_id` is the Notion page UUID, or null while a create is pending. The data-source ID scopes the mapping because one source item may be copied into multiple Notion destinations. `source_hash` is the SHA-256 of normalized Plane-derived Notion properties; it lets a sync count unchanged source rows accurately. The sync still reapplies mapped properties so Notion-side drift is corrected and missing pages can be recreated. No separate project ID, workspace slug, source/target type, timestamps, or explicit sync status is needed: project identity is visible on ad hoc Notion rows, pending state is represented by the null target, and run timing is already in `sync_jobs`. A retry queries Notion by `work_item_id` only for pending mappings, recovers the page if an earlier create succeeded ambiguously, and otherwise creates it. Normal first-time creates add no Notion lookup request. Pending mappings are excluded from synced-item rollups. The local single worker serializes writes; per-destination distributed locking is a production-hardening item.

#### Notion database creation and data-source schema

The database is the container and the data source owns its property schema/rows. Property keys use `snake_case`. State and label options are initialized from Plane metadata and updated when new options appear. The following create payload is for one configured project. The shared ad hoc data source adds workspace and project identity properties (`workspace_slug`, `project_id`, `project_identifier`, and `project_name`).

```json
{
  "parent": {
    "type": "page_id",
    "page_id": "<config_project_page_id>"
  },
  "title": [
    {
      "type": "text",
      "text": {
        "content": "Plane Work Items"
      }
    }
  ],
  "is_inline": true,
  "initial_data_source": {
    "properties": {
      "name": { "title": {} },
      "identifier": { "rich_text": {} },
      "work_item_id": { "rich_text": {} },
      "work_item_url": { "url": {} },
      "description": { "rich_text": {} },
      "state": { "select": { "options": [] } },
      "priority": {
        "select": {
          "options": [
            { "name": "urgent" },
            { "name": "high" },
            { "name": "medium" },
            { "name": "low" }
          ]
        }
      },
      "assignees": { "rich_text": {} },
      "labels": { "multi_select": { "options": [] } },
      "due_date": { "date": {} },
      "plane_updated_at": { "date": {} },
      "plane_lifecycle": {
        "select": {
          "options": [
            { "name": "ACTIVE" },
            { "name": "MISSING_FROM_PROJECT" }
          ]
        }
      }
    }
  }
}
```

For the shared ad hoc database, add these properties to the schema:

```json
{
  "workspace_slug": { "rich_text": {} },
  "project_id": { "rich_text": {} },
  "project_name": { "rich_text": {} }
}
```

The service uses `sync_mappings` to find the Notion page by `target_data_source_id + source_id`, then updates that page. Before creating a page, it saves a pending mapping. If a retry finds that pending mapping, it queries Notion by the visible `work_item_id` property to recover a page created before an ambiguous failure; if no page exists, it proceeds with create. The work-item ID and (for ad hoc rows) project identity remain visible as Notion properties for people browsing the database.

For Notion row writes, map Plane data to those same property keys, for example:

```json
{
  "name": { "title": [{ "text": { "content": "Verify custom Plane statuses in Notion" } }] },
  "identifier": { "rich_text": [{ "text": { "content": "DEMO-9" } }] },
  "work_item_id": { "rich_text": [{ "text": { "content": "<plane_work_item_uuid>" } }] },
  "work_item_url": { "url": "<plane_work_item_url>" },
  "description": { "rich_text": [{ "text": { "content": "<plain_text_description>" } }] },
  "state": { "select": { "name": "Ready for Review" } },
  "priority": { "select": { "name": "medium" } },
  "assignees": { "rich_text": [] },
  "labels": { "multi_select": [] },
  "due_date": { "date": null },
  "plane_updated_at": { "date": { "start": "2026-10-31T11:00:00.000Z" } },
  "plane_lifecycle": { "select": { "name": "ACTIVE" } }
}
```

The shared ad hoc row additionally writes `workspace_slug`, `project_id`, and `project_name`. Do not add `last_synced_at` to every task row: job/run history and the project summary already represent synchronization freshness.

#### Plane work-item record in the service

The Plane API wire keys are snake_case. Use the returned fields directly in the TypeScript API DTO and add resolved relation data using the same naming convention; do not rename fields to camelCase. Request `description_html` explicitly in the list's `fields` because Plane omits it by default. The list endpoint omits `custom_fields`; it is detail-only and out of the first schema.

```ts
export interface PlaneWorkItem {
  id: string;
  identifier: string;
  project_id: string;
  state_id: string;
  name: string;
  description_html: string | null;
  priority: "urgent" | "high" | "medium" | "low" | "none" | null;
  assignee_ids: string[];
  label_ids: string[];
  target_date: string | null;
  archived_at: string | null;
  updated_at: string;
  state?: { id: string; name: string; group: string };
  assignees?: Array<{ id: string; display_name: string }>;
  labels?: Array<{ id: string; name: string; color: string }>;
  work_item_url: string;
}
```

This is the app DTO, not a copy of every field Plane can return: request only fields used by the Notion mapping and lifecycle handling, and expand `state`, `assignees`, and `labels`. `work_item_url` is derived by the Plane client from the configured app base URL, workspace slug, and project/work-item metadata. Plane's priority enum includes `none`; map it to an empty Notion select. Plane's paginated collection envelope is handled separately from this record type. See the official [Plane expanding relations reference](https://developers.plane.so/api-reference/v2/expanding-relations).

### Confirmed schema/demo choices

- **Description representation:** use a plain-text `description` property; Plane HTML formatting is dropped.
- **Views:** use the standard table view. A board grouped by `state` is an optional demo enhancement and depends on validating the create-view API against this database.
- **Field scope:** use the schema above; leave related fields and custom properties deferred.
- **Dynamic options:** keep exact Plane state names and groups, update Notion select options when new source states/labels appear, and leave historical options in place if they disappear from Plane.

### Settled decisions and implementation progress

The core scope, sync direction, collections, Notion schema, project identity, overlap behavior, UTC prototype scheduling, default table view, and stack are settled. Use Java with Spring Boot for the backend and React + TypeScript built with Vite for the frontend. Vite runs separately during development; its static build is packaged with Spring Boot for a single local deployment that serves the UI/API and runs the queue worker. Notion's engineering material describes React rendering in its product, and its marketing-site migration documents TypeScript and Next.js; these support the frontend choice but do not establish a single company-wide backend stack. [Notion's data model and React rendering](https://www.notion.com/blog/data-model-behind-notion), [Notion's React/TypeScript/Next.js migration](https://www.notion.com/blog/migrating-notion-marketing-to-next-js).

Agreed refinements: manual runs use a Plane task URL (no bare-ID-only form for now); config project binding stays fixed, users edit status/schedule/sync options, configs and run history are retained without hard deletion, and force-manual sync remains available for archived configs. Config creation and schedule changes compensate for partial persistence failures; if cleanup cannot complete, the API returns an explicit error for operator follow-up. Failed jobs are terminal and can be rerun manually. Keep both interval and calendar schedule types; evaluate calendar rules in UTC for the prototype. Timezone-aware recurrence and DST behavior are high-level discussion topics only. Demo choreography and filters can wait until the prototype is working.

Use a layered Java package layout with feature subpackages: `controllers/{config,projects,sync}`, `services/{config,projects,sync,scheduling}`, `repositories/{config,projects,sync,mappings}`, `integrations/{plane,notion}`, `models/{documents,dto}`, and `common/{config,constants,enums,exceptions,utils}`. Group cohesive operations in a small number of classes (for example, one `PlaneClient` for related Plane API calls, one `NotionClient` for related Notion API calls, and controllers/services organized by feature). Do not create a file for every function or endpoint. Split a class only when a responsibility is meaningfully independent or the class becomes hard to understand. Extract repeated behavior into narrowly named helpers when reuse is real; avoid catch-all `IntegrationUtils`/`RepositoryUtils` by default. Keep persistence behavior such as atomic job claiming in the relevant repository. The utilities package can remain empty until a true cross-feature helper is needed.

The original product scope is settled. The first sync-performance and bounded-read changes are implemented and covered by tests. Notion Worker/AI-credit exploration, attachments, comments/activity, write-back, Elasticsearch, distributed worker capacity, and robust outage recovery remain deferred.

#### Frontend usability and visual review

- Overview, Configurations, and Run history use the available content width. The sidebar stays fixed without a scrollbar; Configurations and Run history keep scrolling inside their lists rather than the whole page. The Configurations table sizes to its contents for short lists and has a bounded internal scroll area for longer lists.
- UI text sizes have been increased twice by 1 px at the user's request. Table headers and first-column padding, responsive page insets, and viewport-clamped hover descriptions have also been refined. Side navigation items do not show hover descriptions; graph bars do.
- Overview metrics explicitly describe their latest-run scope; success rate uses completed runs and shows no percentage before any run completes. The Sync pulse chart uses actual recent jobs in chronological order, with timestamp labels and per-run hover details; it no longer displays placeholder bars.
- Run-history tables label the outcome/count column `CHANGES`. Configuration detail shows the Notion database and data-source IDs with copy buttons and feedback. The new-configuration form scrolls independently while keeping its create/cancel footer visible.
- The latest visual review covered Overview, Configurations, Run history, configuration detail, and create/edit/manual-sync dialogs. Navigation and safe modal interactions were checked; sync-enqueue and config-save actions were not triggered. Database ID copy feedback was verified. `npm run build` passed TypeScript checking and the Vite production build.

### Sync and scale behavior

The local prototype remains single-tenant. The implementation stays within the existing service/worker architecture.

#### Checkpoint-based incremental sync; archived work items and drafts excluded

- Run an initial full sync of active, non-draft work items. Later project runs use Plane `updated_at` filters and cursor paging with `archived=false`, `is_draft=false`, and a page size of 200. Work items are streamed through bounded batches; archived items and drafts are neither fetched nor mapped. Manual task URL sync resolves the exact item and rejects archived or draft items. A failed detail lookup does not fall back to a project-wide scan.
- Incremental windows use inclusive `updated_at__gte` from the previous successful project-scope run's `started_at` minus 100 ms, and `updated_at__lte` at the current run's start time. Timestamps retain `Instant` precision. The fixed upper bound leaves changes made after run start for the next window; inclusive bounds and the overlap make timestamp ties safe to replay. The overlap is not a guarantee against arbitrary clock skew or delayed API visibility.
- The last successful project job is the checkpoint; no separate config checkpoint field is stored. Work-item-scope manual runs do not become project checkpoints. The worker advances the checkpoint only after a project sync succeeds; a failed run keeps the previous checkpoint and retries the overlapping interval.
- `eligible_count` is recorded for each run: initial active/non-draft candidates, changed candidates in an incremental window, or one eligible manual task. Failed partial runs retain the count observed before failure and the created/updated/unchanged counts. The existing `fetched_count` field remains for stored run compatibility; the UI reports `eligible_count`.
- A general daily reconciliation that includes archived items is out of prototype scope and remains an interview discussion pointer. Plane does not update `updated_at` when a work item is archived or unarchived, so an item that becomes active again may be missed by incremental polling until a manual/full sync. Deletion and move reconciliation are also out of scope.

#### Bounded batching and Notion write behavior

- Plane API reads use the 200-row page-size constant. Work-item sync buffers no more than 1,000 rows, fetches mappings with one data-source-scoped `$in` query per buffer, and bulk-upserts mapping changes. Project catalog refresh also pages Plane results and bulk-upserts metadata/availability changes in Mongo batches of 1,000.
- Notion page create/update remains one request per item. Before a PATCH, compare the normalized source hash; matching hashes skip the request. New creates first bulk-write pending mappings for the batch, then bulk-write completed mappings as results are collected, flushing completed mappings before propagating a later item error. Only retries of pending creates add a Notion query by `work_item_id`; ordinary syncs do not query Notion. This mitigates ambiguous-create duplicates but cannot provide a cross-system transaction or strict exactly-once guarantee. Rate-limit backoff remains production hardening.
- Incremental reads do not compare all mappings or mark absent items missing. That avoids a full mapping scan and avoids treating a delta response as a full project snapshot.

#### Config create/update compensation

- Config creation provisions the Notion page/database, saves the config with a known ID, and seeds its first scheduled job. If saving or seeding fails, it removes pending schedule jobs, deletes the config by that ID, and archives the Notion page. If compensation fails, the API returns `CONFIG_CREATE_CLEANUP_FAILED` with an instruction to inspect before retrying.
- A config update that saves but cannot reseed the schedule restores the old config and schedule. If that rollback also fails, the API returns `CONFIG_UPDATE_ROLLBACK_FAILED`.
- Mongo and Notion do not share an atomic transaction. These are compensating workflows; a process crash between steps can still leave partial state. Durable recovery for a crash during provisioning remains a production-hardening item.

#### Config/project listing and task URL fallback

- `GET /api/configs` fetches the referenced project records with one `findAllById` call.
- Project refresh pages Plane results and bulk-upserts project metadata and unavailable markers in Mongo batches of 1,000.
- Manual task URLs use the exact detail/by-identifier endpoint and reject archived/draft items. A failed lookup does not scan the project's items; the API returns the upstream not-found/inaccessible error.

#### Run history, dashboard, and worker claim verification

- All-run and per-config endpoints paginate by descending Mongo `_id`, using `before_id` and `page_size` (default 20, maximum 100). They fetch one extra row to produce the next cursor and do not calculate an unbounded total count. The frontend loads more using the returned cursor.
- The dashboard gets next-run values from a separate pending-scheduled-jobs query, not run history. The frontend no longer scans/sorts job history to derive per-config next runs. Both all-runs and per-config run views use cursor pagination and load-more controls.
- The local Mongo explain for `claimNextDueJob` uses `job_claim_idx` (`status, created_at, scheduled_for`) with an `IXSCAN` followed by a limit; there is no blocking sort. The local `sync_jobs` collection currently has zero rows, so `totalKeysExamined` and `totalDocsExamined` were both zero and this does not measure scan behavior under backlog. `scheduled_for` remains a residual filter with broad index bounds. For the single-threaded prototype and one pending schedule per config, retain this index/query; repeat explain with representative queue data before a production-scale decision.
- Run history remains retained without hard deletion; pagination bounds reads. A production retention policy and long-run/missed-occurrence cleanup remain deferred.

The agreed performance changes and config create/update compensation are implemented and covered by service, business-flow, repository/API, and frontend tests. Provider rate limits and production recovery/retention remain follow-up work.

### Local service API contract

See [backend/API.md](backend/API.md) for endpoint arguments, responses, status codes, pagination, and the local-only security boundary.

Keep the UI API small and resource-oriented. All routes use JSON and snake_case. API responses use DTOs rather than exposing Mongo documents directly. Run history is read from `sync_jobs`; there is no separate runs collection.

| Method and route | Purpose |
|---|---|
| `GET /api/health` | Local service readiness. |
| `GET /api/dashboard` | Return config summaries, each config's next pending scheduled run, and a bounded recent-run list; avoid loading all run history for dashboard calculations. |
| `POST /api/plane/connection/test` | Verify the configured Plane credentials/base URL without exposing credentials. |
| `GET /api/plane/projects?workspace_slug=...` | List cached projects for the workspace. |
| `POST /api/plane/projects/refresh` | Refresh the catalog for a workspace slug and return the refreshed projects. |
| `POST /api/plane/projects/resolve` | Resolve a pasted project URL, validate its host, and upsert its project catalog entry. |
| `GET /api/configs` | List configs with project summary fields joined from `plane_projects`. |
| `POST /api/configs` | Create a config from `plane_project_ref`, `sync_options`, status, and schedule; provision its Notion page/database/data source. |
| `GET /api/configs/{config_id}` | Read one config and its project metadata. |
| `PATCH /api/configs/{config_id}` | Update allowed fields: status, sync options, and schedule; project binding remains fixed. |
| `POST /api/configs/{config_id}/sync-jobs` | Enqueue an on-demand full-project sync for that config; accept `force_archived_config` for the archived-config guard. |
| `POST /api/sync-jobs/manual` | Enqueue an on-demand task-URL sync; optionally include `config_id` as UI context plus `force_archived_config`, and always validate the URL-resolved project. If there is no matching config, use the shared ad hoc destination. |
| `GET /api/configs/{config_id}/sync-jobs` | List run history for one config with optional filters and cursor pagination (`before_id`, `page_size`; 20 default, 100 maximum). |
| `GET /api/sync-jobs` | List one run-history page with optional filters and cursor pagination (`before_id`, `page_size`; 20 default, 100 maximum). Response contains `items` and `next_before_id`. |
| `GET /api/sync-jobs/next-scheduled` | Return each config's earliest pending scheduled run, separate from run history. |
| `GET /api/sync-jobs/{job_id}` | Read job status/result for UI polling after enqueue. |

Enqueue endpoints return `202 Accepted` with a compact job DTO (`job_id`, `status`, `created_at`). Config creation returns the persisted config DTO after Notion setup succeeds; if setup fails, clean up created Notion objects where possible and return an actionable error. Run-history endpoints return a page object with `items` and `next_before_id`.

## References

See [`important_refs.txt`](important_refs.txt) for the supplied links. The idea notes point to official Notion API and OAuth documentation, Plane documentation, and source-specific references. Verify current API behavior against official documentation when implementation begins.

Notion API validation references: [Create a database](https://developers.notion.com/reference/create-a-database), [Database object](https://developers.notion.com/reference/database), [Data source object](https://developers.notion.com/reference/data-source), [Data source properties](https://developers.notion.com/reference/property-object), [Page property values](https://developers.notion.com/reference/page-property-values), [Create a data source](https://developers.notion.com/reference/create-a-data-source), [Query a data source](https://developers.notion.com/reference/query-a-data-source), [Create a view](https://developers.notion.com/reference/create-view), and [Delete a block](https://developers.notion.com/reference/delete-a-block). The current model is database container → data source schema/rows → view presentation. Create with `POST /v1/databases` and `initial_data_source`; later rows are pages parented to a data source, and queries use `/v1/data_sources/{id}/query`. Notion's docs still put the create-database page in the deprecated navigation group, so keep an API contract check in implementation; its request schema documents the current `2026-03-11` version.

### Connection checks (read-only)

- The configured Notion personal token returned `200` from `/v1/users/me` and could read `NOTION_PARENT_PAGE_ID`.
- The configured Notion connection token returned `200` from `/v1/users/me` and could read `NOTION_PARENT_PAGE_ID`. Use this connection token for service calls; the personal token remains a developer fallback.
- The configured Plane personal access token returned `200` from Plane Cloud `/api/v2/users/me/`.
- The initial connection checks made only GET requests. Later Notion write validation created a temporary page, database, and row and deleted them afterward (see implementation checks). Token values were not printed or written into project documentation.

### Public Plane API findings (primary source)

- Plane's [API v2 introduction](https://developers.plane.so/api-reference/v2/introduction) calls v2 the current REST API. Plane Cloud uses `https://api.plane.so`; self-hosted uses the instance base URL with `/api/v2/` paths. API keys are sent using `X-Api-Key`. Confirm that the self-hosted version used in any deployment supports the public v2 endpoints.
- The [project list endpoint](https://developers.plane.so/api-reference/v2/projects/list-projects) is scoped by workspace slug: `/api/v2/workspaces/{slug}/projects/`, and its results are paginated. The current public v2 docs expose `/users/me` to validate credentials but no unscoped workspace-list endpoint; a live `GET /api/v2/workspaces/` check returned 404. So a project picker needs a known workspace slug from the Plane connection setup or user input; a direct project URL can provide both workspace and project context.
- A [project work-item list](https://developers.plane.so/api-reference/v2/work-items/list-work-items) can return `id`, human `identifier`, `sequence_id`, name, priority, project/state/type IDs, assignee/label IDs, cycle/module/parent/estimate IDs, dates, draft flag, sort order, completion/archive timestamps, external IDs, and created/updated audit IDs/timestamps. Request `description_html` explicitly in the list's `fields`. Use `expand` to include state, assignee, label, cycle, module, parent, type, and estimate objects alongside IDs.
- List responses intentionally omit `custom_fields`; retrieve each item individually to get them. That can mean an additional API call per work item, so custom fields should stay out of the first schema unless needed. List pagination supports offset and cursor modes, with cursor recommended for deep traversal. `per_page` is capped at 200.
- The API can include archived work items with `archived=true` or return only them with `archived=only`, but the prototype intentionally requests active items only. For the interview discussion, note that archive/unarchive do not change `updated_at`; a future incremental design needs reconciliation or events to detect those transitions.
- The [workspace work-item lookup by identifier](https://developers.plane.so/api-reference/v2/work-items/get-work-item-by-identifier) resolves a human key such as `PROJ-142` to its `project_id` and item `id` without knowing the project UUID. It is read-only and does not return archived items. This supports a URL-only manual request after parsing/validating the Plane instance and workspace slug from the URL.
- Plane exposes `workitem.deleted` among its [webhook events](https://developers.plane.so/dev-tools/intro-webhooks). V2 delete payloads carry the previous record in `previous_attributes`; delete events have an empty `data` object. However, webhook callbacks must be publicly reachable, and Plane's public docs say local/private endpoints are not accepted. For the interview discussion, outline how a future version could combine active/archived reconciliation with events to distinguish deletion, archival, and moves. The prototype does not implement archived-item handling.
- The [delete work item API](https://developers.plane.so/api-reference/v2/work-items/delete-work-item) removes the item from API reads and has no undelete endpoint. Notion remains untouched by deletion: mark the synced page name/property as deleted or missing in Plane instead of removing it.

Additional official Notion API references checked for this design:

- [Create a database](https://developers.notion.com/reference/create-a-database): use the current request shape with `parent`, `title`, optional `is_inline`, and `initial_data_source.properties`. This creates the database container and its first schema-bearing data source under a page. The docs advertise API version `2026-03-11`; the legacy navigation label is confusing, but the modern request shape was successfully validated in the prototype.
- [Database](https://developers.notion.com/reference/database) and [Data source](https://developers.notion.com/reference/data-source): since API version `2025-09-03`, the database is a container for one or more data sources; a data source owns the property schema and rows (Notion pages).
- [Create a data source](https://developers.notion.com/reference/create-a-data-source) and [Query a data source](https://developers.notion.com/reference/query-a-data-source): create an additional data source beneath an existing database and query rows through its ID. New data sources get a standard table view by default.
- [Create a view](https://developers.notion.com/reference/create-view): supports adding table/board and other view types to a data source (or creating a linked database block on a page). Custom view configuration is optional for the first cut; use the default table view.

## Notion Worker and marketplace research

- Workers are Node/TypeScript programs hosted by Notion. They support scheduled database syncs, Custom Agent tools, and external HTTP webhook handlers. A sync defaults to every 30 minutes; supported intervals currently range from 5 minutes to 7 days. The CLI can trigger a sync manually and enable or disable capabilities.
- A Worker can declare a managed output database with a schema and primary-key property, which allows stable external IDs to upsert the same row. It can also attach an existing database, but then it controls declared properties and rows; synced values are read-only and sync runs can overwrite or delete synced data.
- The Worker can read/write other Notion pages and databases through `context.notion`. Sync and webhook capabilities require a Notion API token configured as a Worker secret; use a scoped internal-connection token and explicitly share the config/output pages with it. Keep Plane API credentials in Worker secrets, not the config database.
- One project config can live in a separate Notion config database/page and be read by both Worker and hostable service. Keep that editable config separate from the Worker-managed output database. A `scheduled_enabled` setting can make scheduled runs no-op; actual capability pausing is also available through the CLI.
- Worker tools accept typed input and can be invoked with the CLI or by a Custom Agent; webhook handlers accept external HTTP events. These provide candidate paths for on-demand task ID/URL sync, but safe upsert behavior into the same Worker-managed output database still needs validation.
- Notion's current managed Jira database sync is a close product analogue: it creates Jira Spaces and Jira Work Items databases, supports selected spaces, history windows, standard and selected custom properties, and periodic one-way updates. It currently supports Jira Cloud (not Jira Server/Data Center); the current guide says comments and attachments are not synced and requires Business/Enterprise. The separate Jira AI Connector indexes projects, issues, and comments for permission-aware Notion AI search, not a browsable synced task database. Official docs also contain an older Jira Sync guide with different two-way-sync statements; use the current managed-sync guide when comparing behavior and recheck before submission.
- Differentiation for Plane should emphasize configurable Plane project selection, Cloud and self-hosted deployment modes, on-demand single-task sync, visible run controls, and (as later work) activity/comments. The existing Jira connection makes a generic “sync tasks into Notion” pitch less distinctive, but it is not a Plane marketplace integration.

## Source notes

- [`problem.txt`](problem.txt): recruiter-provided assignment statement.
- [`ideas.txt`](ideas.txt): initial brainstorm.
- [`chatgpt_web_discussion_on_ideas.md`](../chatgpt_web_discussion_on_ideas.md): summary of prior research and comparison of the ideas.
- [`important_refs.txt`](../important_refs.txt): links supplied for later research.

## Change log

The entries below record the sequence of decisions and implementation steps. When an entry conflicts with the current implementation state or decision record above, use the current sections above.

- 2026-10-31: Created the initial context and decision record; selected Plane → Notion one-way synchronization after comparing ideas against the assignment and checking current Notion Connections search results.
- 2026-10-31: Recorded that Plane is internal-network-only. The working runtime is now an in-network poller/service using a Notion internal connection; hosted Notion Workers are not a fit unless private network access changes.
- 2026-10-31: Set implementation order: local/hostable service first, Notion Worker later. The service has one JSON sync-options config, schedule configuration, MongoDB jobs/run history, schedule toggle, and on-demand task sync. Elasticsearch remains deferred. Comments/activity history and attachments are follow-on items; Notion → Plane is explored but not implemented.
- 2026-10-31: Clarified that `next_run_at` is derived runtime scheduling state and should not be stored in the integration config; compute it from the schedule and job history, adding separate scheduler state only if needed.
- 2026-10-31: Clarified that the app supports multiple configs, each selecting one Plane project and one Notion destination. The UI includes a configs list, per-config detail/runs, and a filtered all-runs page. Mongo connection is in `.env`; collection names are source constants. Plane/Notion tokens must not be read yet. Personal Plane Cloud will use synthetic tasks; production notes are inspiration only, not a source of company data for this project.
- 2026-10-31: Researched Worker sync/config capabilities and Notion's Jira connections. Config can be a separate Notion database/page; Worker-managed output data must remain under one writer. Jira is a close analogue, with a separate AI search connector.
- 2026-10-31: Chose service-created Notion task databases with a locally defined default schema, created as part of config setup. A parent page (selected per config or set as a service default) is still required, and its access must be granted to the Notion connection. Save the resulting database ID on the config. Defer user-editable field mapping; use a fixed schema for Phase 1. Official Notion docs show database creation with an initial data-source schema under a parent page and require insert-content capability, while the endpoint appears under the deprecated database section; validate the current API/version path before coding.
- 2026-10-31: Adopted the scheduled-job chain design for the local service: config create/enable/schedule update prepares one pending scheduled job; the queue worker marks it `SUCCESS`/`FAILED` and creates the next scheduled job only if the config remains enabled. Manual jobs do not chain. Support interval and calendar schedules; still decide interval fixed-delay versus fixed-cadence semantics. Keep one pending scheduled job per config and supersede pending jobs on schedule edits.
- 2026-10-31: Updated the Notion layout and schedule choices: `NOTION_PARENT_PAGE_ID` is the shared parent; each config gets a child project page, a generated summary, and a task database; the parent page gets an index/TOC. Start the schedule at the next scheduled occurrence, with optional `startTime`/`endTime` still to define. Use fixed cadence (initial example: every 30 minutes); skip overlapping scheduled occurrences for that config and record them for reporting without enqueuing sync work. Configs refer to named `.env` secrets; keep a fixed default field mapping for Phase 1.
- 2026-10-31: Clarified that the task database is inline on each config's project page; table and board views are views within that database. Cadence is every configured interval from `startTime`, or from config creation time if no start time is set. Config state replaces a separate schedule-enabled flag: `ACTIVE`, `DISABLED` (manual pause), or `ARCHIVED` (end time reached). When a long run completes, create one `SKIPPED_OVERLAP` history item for each cadence occurrence that elapsed while busy, then enqueue the next future run. Use the scoped Notion connection secret for the service instead of a personal PAT, subject to confirming page access/capabilities. Suggested project summary and parent TOC include item/status totals, latest/last-successful sync, schedule status, and next run; the user authorized adding the needed summary.
- 2026-10-31: Adopted deterministic summary content for the prototype: each config page shows project identity/status, total work items and counts by Plane status, latest run result/time, last successful sync, created/updated/unchanged/error counts, and next scheduled run. The parent TOC shows project/config name, status, last successful sync/freshness, next run, and a link to the project page. Refresh the summary after each sync and the TOC after config create/rename/disable/archive.
- 2026-10-31: Confirmed schedule window semantics: `startTime` inclusive, `endTime` exclusive; a future start can remain `ACTIVE` with an upcoming run shown. A single config's manual and scheduled syncs may not overlap. A manual request arriving during a scheduled run should queue; cadence occurrences during a manual or scheduled run become individual `SKIPPED_OVERLAP` history records created after the active run finishes. Manual URL requests begin unbound, resolve the task's Plane instance/workspace/project, then match the corresponding config; no matching config means no known Notion destination. Sync full-project listings include archived items; deleted Plane records are retained as marked Notion pages, never deleted from Notion.
- 2026-10-31: Reviewed the Plane local skill for general endpoint context only; excluded all internal company identifiers/data. Public Plane API docs are primary: v2 is current, list supports archived records and pagination, detail reads expose custom fields while collection reads omit them, identifier lookup returns project ID for URL-only task resolution, and v2 webhooks report deleted work items (but need a public callback URL). For the local/private service, plan complete-scan reconciliation as the delete fallback.
- 2026-10-31: Added a reusable manual-only ad hoc Notion destination for task-URL requests whose project has no regular config. Create/reuse one page and task database per Plane instance/workspace/project, include project metadata, and sync only the requested task; if a regular config is later created, reuse that destination. Add a per-request checkbox to force a one-time run for an `ARCHIVED` config without reactivating its schedule. Archive-force behavior is separate from including archived Plane work items, which regular full-project syncs already do.
- 2026-10-31: Corrected ad hoc behavior: use one shared manual-only Notion page/database for tasks from all projects without a config; store workspace/project metadata per row. A later regular config gets its own page/database and may duplicate the ad hoc row. Always display the archived-config force checkbox; the backend only permits an archived config run when the flag is true and otherwise returns an error. Manual URL requests exclude drafts but include archived work items, using the list API fallback when Plane detail reads hide them. Archived Plane work items remain distinct from archived sync configs.
- 2026-10-31: Defined config creation UX: users select a service-preconfigured Plane connection (label only, no raw token), then paste a project URL or choose workspace/project from API-fetched options. Validate the host against configured connections, test access, and store canonical source IDs. `NOTION_PARENT_PAGE_ID` and the selected connection create the config's Notion project page/database automatically. Actual per-project custom fields are loaded only after a project is selected.
- 2026-10-31: Added a Plane project catalog and duplicate-config guard. Cache project metadata in Mongo keyed by Plane connection, workspace slug, and project ID; fetch it for the picker and refresh only on a UI action. A project URL resolves and upserts its project record. Enforce one config per source project with a database unique key, including disabled/archived configs. Public API v2's project list requires a workspace slug; provide one via connection setup/user input, while a direct project URL supplies it for that request.
- 2026-10-31: User accepted both workspace discovery paths: known workspace slugs can be preconfigured with a Plane connection and shown in a picker, and users can enter a workspace URL/slug; a project URL can resolve both directly.
- 2026-10-31: Replaced ambiguous “Plane connection” wording with “Plane instance profile”: stable instance identity, accepted app URL host(s), API base URL, secret reference, and optional known workspace slugs. Use separate factories for URL-kind resolution and API client selection. Project-catalog/config uniqueness is based on Plane instance + workspace slug + project ID, never the credential reference. User chose to queue manual runs arriving during a scheduled run; scheduled occurrences in the meantime are still written as `SKIPPED_OVERLAP`.
- 2026-10-31: Clarified Plane credential/profile storage. A Plane API token is a credential for a Plane account on a particular deployment; it can access more than one workspace if that account has access. Keep the raw token in `.env` (or a secret manager in a hosted deployment), never in Mongo or chat. A Plane instance profile is deployment-level metadata, not one profile per token or workspace; it can store the stable instance ID, URL host/API base URL, label, and a named secret reference. Token rotation updates the secret without changing project identity or creating a new catalog/config namespace.
- 2026-10-31: Added the user's Plane Cloud workspace URL for read-only testing: `https://app.plane.so/secondary-workspace/` (workspace slug `secondary-workspace`). A read-only Plane API project-list call returned one accessible project: name `secondary-workspace`, identifier `SECONDARY`, project ID `<redacted-account-id>`. Use this project as the initial code/schema test target. The API call only listed metadata; it did not create or modify Plane data, and no token values were printed.
- 2026-10-31: Created a separate Plane Cloud workspace for safe demo development: `https://app.plane.so/demo-workspace/` (workspace slug `demo-workspace`). Plane created its starter project `Demo Project` (project ID `<redacted-account-id>`). Prefer this isolated workspace/project for implementation and sync testing; the previously listed `secondary-workspace` workspace/project remains a secondary reference. The workspace setup was done through the Plane UI; no API tokens were exposed. The new workspace currently shows a Business trial ending in 13 days; no plan selection or payment action was made.
- 2026-10-31: Simplified the prototype's Plane connectivity: one Plane deployment and one Notion workspace are configured globally by environment variables, so no Plane instance profile or Mongo secret reference is needed in the first cut. Keep instance profiles as a future extension for multiple deployments/customer connections. A Mongo secret reference, if later needed, is only a key/name pointing to an environment or secret-manager value; never the secret itself.
- 2026-10-31: Inspected the demo project's work item create form and project settings. Standard fields shown are title, description, state, priority, assignee, label, start/due date, cycle, and module. The five state groups are Backlog, Unstarted, Started, Completed, and Cancelled. Added `Ready for Review` under Started and created demo work items `DEMO-8` (Todo, High) and `DEMO-9` (Ready for Review, Medium). Project Work item Types/custom properties remain disabled.
- 2026-10-31: Validated Notion API writes using the configured connection: created a temporary child page, database with initial data-source schema, and one sample row, then deleted the row, database, and page. All create/delete requests returned HTTP 200; Notion deletion places objects in Trash. The sample objects were created only for this check; no tokens or values were printed.
- 2026-10-31: Checked the current official Notion API docs. Since API version `2025-09-03`, a database is a container and each data source owns its schema and rows. Create the database under the config page with `POST /v1/databases` and `initial_data_source.properties` (latest documented API version `2026-03-11`), then use data-source APIs for schema and queries. The “Databases (deprecated)” navigation grouping is confusing: the page also documents the modern initial-data-source request. A standard table view is created by default; custom views can use `POST /v1/views` but are not required for the MVP. No change is needed to the validated create flow.
- 2026-10-31: Rechecked Plane Cloud with the newly supplied all-workspace token, without displaying it. The API can now list projects in `demo-workspace`, read the project's six states and nine work items, and resolve the custom `Ready for Review` state. This replaces the prior 401 blocker. A listed work item includes IDs/keys for project, state, type, assignees, labels, cycles, modules, parent, estimate, dates, priority, archive/completion timestamps, and created/updated audit metadata; the list response did not include `custom_fields`. Keep the credential in `.env` only.
- 2026-10-31: Drafted the initial Notion work-item data-source schema: title/name, human key, composite Plane source key, readable workspace/project identity, source URL, description, exact Plane state and state group, priority, assignees, labels, start/due dates, source timestamps, source lifecycle, and last-synced time. Reuse it in per-config and shared ad hoc databases; defer cycles, modules, estimates, type/custom fields, comments/activity, and attachments.
- 2026-10-31: User confirmed plain-text descriptions, the default table view, and dynamic state/label option updates that retain historical options. User selected `snake_case` across app code, Mongo documents, and Notion property keys to match Plane's HTTP payload and avoid renaming; Mongo's reserved `_id` remains the sole exception. TypeScript is accepted for the front end; `Asia/Kolkata` is the local schedule timezone default. Added initial JSON contracts for all Mongo collections, the Notion database/data source, and the Plane work-item DTO. The service/back-end framework remains open.
- 2026-10-31: Simplified the proposed persistence model: `sync_configs` references the cached `plane_projects` record by `plane_project_ref`, and `sync_jobs` also references that project so workspace/project metadata is not repeated in every job. `sync_mappings` stores `source_id`, `target_id`, `target_data_source_id`, and the `source_hash` needed for accurate unchanged counts and idempotent writes; it has a unique compound index on destination + source. That destination scope is needed because a Plane task may be copied to both a configured database and the shared ad hoc database. Continue to omit `system_settings`; configure the single ad hoc Notion destination IDs through `.env`. Use uppercase integration-owned enum values (`INTERVAL`, `CALENDAR`, `WEEKLY`, job/config states, etc.) and store time zones as IANA IDs such as `Asia/Kolkata`. Keep the Notion data-source schema focused on fields needed to identify, inspect, and preserve Plane work items; only the shared ad hoc data source needs workspace/project identity columns.
- 2026-10-31: Workspace enumeration and storage were initially deferred: project discovery needs a known slug, URL, or project URL, and the Plane v2 project-list API is scoped by slug. That decision was superseded on 2026-11-01 when the Notion hierarchy required a stable workspace-page reference. Add `plane_workspaces` keyed uniquely by `workspace_slug`, storing its Notion page ID; this is destination metadata, not a workspace-discovery mechanism. Avoid storing derived `project_url`; keep the Plane app DTO limited to fields needed for sync (plus expanded state/assignees/labels). Keep the mapping bean to its lookup identifiers plus the small source hash required for unchanged detection; omit timestamps because job history already provides run timing. Retain the Notion project page, database container, and data-source IDs in each config and the shared ad hoc destination; they identify distinct Notion resources and support page summaries, database-level operations/views, and row/schema operations respectively.
- 2026-10-31: User selected Java/Spring Boot for the backend and React + TypeScript with Vite for the frontend. Bundle Vite's static production build with Spring Boot so one local app serves the UI/API and runs the queue worker; use Vite separately during development. User chose task URLs for manual runs, accepted the proposed config lifecycle and terminal-failure/manual-rerun behavior, accepted both interval and calendar scheduling, and deferred run filters/demo choreography until the prototype works.
- 2026-10-31: User deferred timezone implementation in the prototype; evaluate interval/calendar schedules in UTC and reserve timezone-aware scheduling/DST behavior for the high-level discussion. User wants code organization planned next, including Java services, Mongo stores/repositories, controllers, and the in-process queue worker. API contracts will be decided separately.
- 2026-10-31: User chose a layered package layout with top-level `controllers/`, `services/`, `repositories/`, and `common/` directories, each organized into feature subdirectories, plus a utilities area. Extract repeated behavior into narrowly scoped helpers; avoid duplication while avoiding generic catch-all `IntegrationUtils`/`RepositoryUtils` abstractions. Keep domain-specific integration parsing/mapping and repository query/claim logic in clearly named components.
- 2026-10-31: User set simplicity as the primary design rule: keep the prototype logical and understandable, and avoid complexity, file/class/endpoint sprawl, or speculative abstractions added just to appear sophisticated. Prefer the simplest design that meets the agreed use cases.
- 2026-10-31: User clarified to keep the file layout pragmatic: group related functions and endpoints in cohesive classes rather than creating one file per function. Keep the feature/layer folders, but split classes only for meaningful responsibility boundaries or maintainability; leave utilities empty until a real shared helper is identified.
- 2026-10-31: Began the backend with the four agreed Mongo document models and repository layer: project catalog, sync configs, sync jobs/run history, and sync mappings. The job repository atomically claims due pending jobs; the local runtime is intended to use one in-process worker thread.
- 2026-10-31: Added the Plane and Notion HTTP clients. Plane reads use v2 endpoints for projects, work items, project states, labels, and identifier lookup; project work-item scans include archived items and use cursor pagination. Notion provisioning uses the database container plus `initial_data_source`, and row operations use pages parented to a data source. State/label options are extended while retaining existing options. External error messages expose the service and HTTP status only, never upstream bodies or credentials.
- 2026-10-31: Added backend services for the Plane project catalog, config creation/update, UTC interval/calendar scheduling, durable manual/scheduled jobs, one in-process queue worker, and mapped Plane work-item upserts/reconciliation to Notion. Follow-up implementation now provisions and uses the shared ad hoc destination for manual task URLs whose projects have no config.
- 2026-10-31: Added the REST API DTOs and controllers for health, Plane connection/catalog, configs, manual sync enqueue, and run history/detail. API JSON is snake_case, enqueue responses are `202 Accepted`, and upstream/internal error bodies do not expose credentials or raw integration responses. Run-list filters support config, status, trigger type, and creation time range.
- 2026-11-01: Current first-cut status: the backend exposes health, Plane connection/catalog, config lifecycle, project/task manual sync, and run history APIs. A no-config task URL queues an active, non-draft work item against the shared ad hoc destination; archived items and drafts are rejected. The local worker processes these jobs, and `sync_mappings.source_hash` supports accurate created/updated/unchanged counts. Mongo run-history filters are executed in the repository query.
- 2026-11-01: The shared Notion destination has been created under the configured parent page. Its page, database, and data-source IDs are set using `NOTION_AD_HOC_PAGE_ID`, `NOTION_AD_HOC_DATABASE_ID`, and `NOTION_AD_HOC_DATA_SOURCE_ID`; the data source includes workspace and project identity fields and lifecycle options `ACTIVE`, `ARCHIVED_IN_PLANE`, and `MISSING_FROM_PROJECT`. Notion credentials remain in `.env`; no credential values are recorded here.
- 2026-11-01: The React + TypeScript frontend is under `frontend/` using Vite. It has the dashboard, config list/detail, run history filters, project lookup, config editor, schedules, project sync dialog, and task-URL dialog. Both manual sync dialogs show the archived-config force checkbox. Vite proxies `/api` during development and writes the production build into Spring Boot static resources.
- 2026-11-01: Workspace hierarchy implementation: `NOTION_PARENT_PAGE_ID` contains one child page per Plane workspace, and each configured project page is nested beneath its workspace page. A config sync refreshes the cached Plane project metadata, updates the Notion project title, and moves existing project pages under the correct workspace page. Each workspace page carries an app-maintained rollup of configured project status and mapped-item counts; the parent page aggregates workspace totals. These summaries are generated from Mongo configs/projects/mappings rather than native Notion relation/rollup properties, preserving separate task data sources per project. Existing project pages migrate lazily on their next config sync. Comments/activity, attachments, Notion → Plane, and production outage/cleanup handling remain follow-on/high-level topics.
- 2026-10-31: Compiled both code pieces successfully. `npm run build` passed TypeScript checking and emitted the Vite bundle to Spring Boot's static resources. Maven `-DskipTests compile` compiled all 36 backend source files. Compilation exposed that Spring Boot 4 uses Jackson 3 and does not include Jackson through the MVC starter alone; added `spring-boot-starter-jackson` and updated the JSON tree imports to `tools.jackson.databind.JsonNode`. No tests were run. `.env` was not accessed.
- 2026-10-31: Resolved the Jackson deprecation warning: replaced deprecated `JsonNode.asText(String)` fallback calls with `JsonNode.asString(String)` for string-valued fields. Recompiled all 36 backend source files with deprecation diagnostics enabled; build succeeded with no deprecation warnings. No tests were run.
- 2026-10-31: Added 15 backend tests using `spring-boot-starter-test`: 13 service-level cases for config validation/create/update, project catalog refresh, manual task enqueue and archived-config force behavior, and schedule seeding/window/overlap rules; plus two business-flow cases covering config creation through scheduled-job seeding and queued project sync through Plane fetch, Notion page creation, mapping persistence, and successful job completion. `mvn test` passes all 15 tests. Mockito's inline mock-maker attachment warning is non-fatal in this runtime; a subclass mock-maker test resource is configured because these tests do not require final-class mocking. `.env` was not accessed.
- 2026-11-01: Local runtime smoke check passed after starting Spring Boot and Vite. Backend `/api/health` returned `UP`; Vite served the app and proxied API calls; the Plane connection test returned `CONNECTED`; refreshing the `secondary-workspace` workspace returned one project, and the Mongo-backed project catalog read it back. The first refresh exposed a Plane v2 query error because `is_archived` is not a requestable project field; changed the request to `archived_at` and derive archive state from that field. Re-ran all 15 Maven tests successfully. No Notion config/database was created during this smoke check, so live Notion provisioning and a full live sync remain unverified in this run. `.env` was not accessed or printed.
- 2026-11-01: Code review fixes: archived work-item URL lookup now falls back from hidden detail reads to `archived=true` project-list lookup, while drafts remain excluded; shared ad hoc page/database/data source provisioned and wired through enqueue, worker, and Notion rows; source hashes make created/updated/unchanged counts reflect content differences while mapped properties are still reapplied to repair Notion drift or recreate missing pages; lifecycle options omit `DELETED_IN_PLANE` and reconciliation consistently marks an item missing from the project. Run filters moved from in-memory filtering to Mongo query criteria. Added Plane and Notion HTTP-client contract tests, repository query tests, ad hoc end-to-end service-flow coverage, unchanged-count coverage, and frontend interaction coverage for both archived-config force dialogs. Maven passes 22 tests; the two frontend tests pass; `npm run build` passes. A full Spring Boot startup succeeded and `/api/health` returned `UP`; the temporary server was stopped. Credential values were not printed or copied into project docs.
- 2026-11-01: Planning correction: checkpoint-based incremental sync is in scope for active, non-draft items; archived items and drafts are excluded. Incremental windows use inclusive `updated_at` bounds, a fixed run-start upper cutoff, 100 ms overlap, and a checkpoint derived from the last successful project sync. Daily reconciliation, including archived items, stays an interview discussion pointer. Each run records `eligible_count` as the number selected/evaluated in that run scope (partial observed count on failure). The frontend should get next scheduled runs from pending schedule jobs, not scan paginated history. A read-only Mongo explain selected `job_claim_idx` with IXSCAN and no blocking sort; the local `sync_jobs` collection was empty, so scan efficiency under backlog remains unmeasured. The Mongo URI was read only inside the explain process; no `.env` value was printed or added to project docs.
- 2026-11-01: An active-only sync/hash-skip code change was started before planning was finalized, then reverted at the user's direction to remain in planning. Those behaviors remain decisions for the implementation phase. At that time, this shell had no `mvn` executable on PATH, Homebrew did not list Maven, and the project had no Maven wrapper; existing Surefire reports from earlier runs remained under `backend/target/surefire-reports`. No tests were run for the reverted attempt. No `.env` values were printed or added to project docs.
- 2026-11-01: Per the user's authorization, installed Maven with Homebrew. `mvn -version` now resolves Maven 3.10.0 from `/opt/homebrew` and runs with the existing Temurin Java 21; no separate PATH edit was needed. Homebrew also installed Maven's declared dependencies and upgraded 19 existing formula dependencies. No project tests or compilation were run; planning remains in progress.
- 2026-11-01: Implemented checkpoint-based incremental Plane reads (200-row cursor pages, inclusive updated-at window, 100 ms overlap, successful project-run checkpoint), active/non-draft selection, bounded 1,000-item Mongo mapping batches, bulk mapping/project-catalog upserts, hash-based Notion PATCH skipping, and per-run eligible counts including partial failures. Removed absent-item marking from incremental reads and removed project-list fallbacks for manual task lookups. Added descending Mongo `_id` pagination for all/config run history, separate pending-schedule lookup for dashboard next runs, and batched config-project joins. Both run-history views now support loading subsequent cursor pages. Documented archive/unarchive completeness limits as discussion-only. Backend Maven suite: 25 tests pass; frontend: 4 tests pass and `npm run build` passes. Maven uses Homebrew 3.10.0 / Temurin Java 21. No live Plane/Notion sync or Mongo explain was run in this implementation pass; `.env` was not read or printed.
- 2026-11-01: Reviewed backend/frontend against the coding and PR-review guides. Added compensating rollback for config create and schedule-update failures, including explicit cleanup-failure codes; removed unused client methods; reduced the Plane connection response to status only; returned `404` for missing upstream resources; validated blank workspace slugs; and bound the local service to loopback by default. Added `backend/API.md` with route contracts and the local-only security boundary. Frontend now ignores stale run/config responses, keeps dashboard recent runs separate from paginated history, uses typed config payloads, and tests that filter changes win over slower prior responses. Backend: 32 tests pass. Frontend: 5 tests pass and production build passes. No live dependency audit, external sync, or database explain was run; `.env` was not read or printed.
- 2026-11-01: Empirically exercised the local app through the browser and live Plane/Notion APIs. Fixed two stale Mongo index-name collisions, the Spring Boot 4 Mongo property prefix (now forces the source-defined `plane_to_notion` database), and Notion token-name fallback from `NOTION_CONNECTION_TOKEN` to `NOTION_PERSONAL_ACCESS_TOKEN`. Dropped the four app collections from the prior `test` database, preserved the newly created demo config and sync history while moving them to `plane_to_notion`, and verified only the current indexes were created: project/catalog unique indexes, mapping unique index, and the five sync-job query indexes. The migration copied one project, one config, seven run records, and seven mappings; two later task-URL checks added two successful run records. Created one ACTIVE 30-minute config for the `secondary-workspace` Plane project and its Notion destination; a live project run synced 7 eligible work items (7 created, 0 failed). A later incremental run completed with 0 eligible items; manual task-URL syncs completed with 1 eligible and 1 unchanged, confirming existing Notion rows are not duplicated. The live checkpoint path exposed a projection/mapping failure on repeat project runs; removing the partial projection fixed it. Updated the frontend so enqueue and refresh reload the visible run-history page; verified the new queued row appears and then transitions to success. Four failed run records from pre-fix reproductions remain visible in run history as useful diagnostics. Backend: 32 tests pass; frontend: 6 tests pass and production build passes. The local service and Vite UI are running at `http://127.0.0.1:8080` and `http://127.0.0.1:5173`. `.env` was not read or printed. Spring Boot 4 database property reference: https://docs.spring.io/spring-boot/appendix/application-properties/.
- 2026-11-01: Re-ran the live workspace/page hierarchy path. The first SAMPLE01 sync renamed its Notion project page to the current Plane name and moved it under `Plane Workspace — demo-workspace`; a second sync succeeded and left the page under that same parent. The code fetches the page on each sync but only calls Notion's move endpoint when the parent differs, so a second migration is not performed. This work runs inside the existing queued sync-job worker and is asynchronous from the API request; there is no separate migration job or thread. The representative no-delta runs completed in 2.6–3.6 seconds (they fetched 0 changed items and performed no work-item writes). Historical seed runs recorded an initial 50-item sync in 24.2 seconds and a 2-item create/update incremental sync in 2.9 seconds; the 15-project bulk scenario was not re-run after hierarchy changes.
- 2026-11-01: Fixed the Notion workspace and parent rollup failure: `NotionClient.upsertPageSummary` used `POST /v1/blocks/{block_id}/children`, which returned `400 invalid_request_url`; current Notion docs require `PATCH`. After changing to PATCH, live workspace and parent summaries appeared and refreshed successfully. Archived 14 app-generated synthetic QA project pages and the now-unused synthetic QA workspace page, preserving the Plane Connector parent, unrelated Notion pages, shared manual-sync page, SAMPLE01, and secondary-workspace mirrors. Pruned their Mongo configs, projects, workspaces, mappings, and run noise without changing any Plane source projects or tasks. Final Mongo counts after the final manual URL replay: 2 `sync_configs`, 2 `plane_projects`, 2 `plane_workspaces`, 8 `sync_jobs`, 59 `sync_mappings`; expected unique/query indexes remain. The parent rollup reports 2 configured projects and 58 project-mapped items (the separate manual destination has 1 mapped task). SAMPLE01 remains DISABLED; secondary-workspace remains ACTIVE. The UI manual URL replay completed successfully with 1 eligible, 1 unchanged and no duplicate Notion row. Added a Notion client contract test for PATCH summary append; all 33 backend tests pass. Mockito/JDK dynamic-agent warnings are non-fatal. Credential values were not printed or added to this document.
- 2026-11-01: Updated the context after the latest frontend design pass. Widened the Overview content, made the Configurations panel content-height with bounded list scrolling, clarified dashboard metric/chart scope, renamed run-history details to `CHANGES`, added Notion ID copy actions, and separated the create form's scroll area from its persistent footer. Increased text sizes twice by 1 px per request. Browser review confirmed the updated layouts and copy feedback; `npm run build` passed. No sync was enqueued or configuration saved during the review.
- 2026-11-01: Applied first-time-user clarity fixes from designer and product review. Config rows and detail now label the Plane workspace slug and project identifier; source/destination terminology, schedule/status meanings, local-time timestamps, config-edit timestamps, setup guidance, and sync actions are clearer. Improved table contrast, semantics, keyboard focus, and action labels. Frontend production build passed; no automated tests were run in this pass. Current project catalog/API DTO carries the workspace slug, but not a separate Plane workspace display name; the UI labels the slug explicitly rather than deriving a potentially incorrect name.
- 2026-11-01: Current visible Plane SAMPLE01 project name remains `Sample QA Project`. The suffix came from the earlier rename-propagation test and is present in Plane itself, so the UI is reflecting its source. It was not reverted during the UI clarity pass; no Plane project data was changed in that pass. The app still needs the source project title restored if the unsuffixed name is desired.
- 2026-11-01: Prepared the private GitHub repository and open PR #1. Added a concise README, blank `.env.example`, Dockerfile, and Compose stack with MongoDB. Moved `ideas.txt`, `problem.txt`, and this context file into `docs/`. Found the shared ad hoc Notion page/database/data-source IDs hard-coded in Java; moved them to `NOTION_AD_HOC_PAGE_ID`, `NOTION_AD_HOC_DATABASE_ID`, and `NOTION_AD_HOC_DATA_SOURCE_ID` environment variables, updated the example/configuration/docs, and rewrote the PR branch so the values are absent from the current PR diff. Generalized account-specific workspace/project references in this context. `.env` remains ignored and was not printed or committed. Docker image build and `docker compose config --quiet` succeeded; the build skipped test execution.
- 2026-11-01: Corrected the Notion token environment-variable precedence after noticing that local `.env` uses `NOTION_CONNECTION_API_TOKEN`, while the app had only checked `NOTION_CONNECTION_TOKEN` and then fallen back to the personal token. The app now prefers `NOTION_CONNECTION_API_TOKEN`, keeps `NOTION_CONNECTION_TOKEN` as a compatibility alias, and uses `NOTION_PERSONAL_ACCESS_TOKEN` only as a final local-development fallback. Updated the blank example and setup instructions. `.env` values were not printed or changed.
- 2026-11-01: Removed all Notion token fallback behavior per user direction. The service now reads only `NOTION_CONNECTION_API_TOKEN`; the personal-token variable was removed from `.env.example`, and setup/current-state documentation reflects the single-token requirement. `.env` was not read or changed.
- 2026-11-01: Addressed PR design comments by consolidating Plane/Notion settings into `IntegrationProperties`, grouping enums in `PlaneToNotionEnums`, adding `PlaneService` as the controller/application boundary around `PlaneClient`, and extracting shared HTTP execution into `HttpService`. Flattened one-file packages, moved `NotionHierarchyService` into the service layer root, renamed/moved Mongo collection constants, and moved `MongoIdParser` and API DTOs out of singleton packages. The review confirmed both `INTERVAL` and `CALENDAR` scheduling are implemented. Kept custom Spring Data repository fragments for atomic/bulk/query operations; simple workspace repository methods are generated by Spring Data. Docker build succeeded with test execution skipped; no test suite was run for this review pass.
- 2026-11-01: Addressed PR feedback on ambiguous Notion creates and invalid weekly schedules. The sync persists pending Mongo mappings before creates, retries recover any created Notion page by `work_item_id`, and rollups exclude pending records. Weekly calendar schedules require at least one selected weekday in the UI; backend validation remains the final guard. Added regression coverage; 46 backend tests and 7 frontend tests pass, with the frontend production build passing.
- 2026-11-01: Addressed the PR comment about ad hoc Notion setup. On the first task-URL sync for a project without a config, the service now creates the shared page and inline database under `NOTION_PARENT_PAGE_ID`, then stores page/database/data-source IDs in the single `notion_destinations` Mongo record. Later runs reuse the Mongo record. Existing local `NOTION_AD_HOC_*` values can seed that record once to preserve current mappings; fresh setup no longer asks users to provide them. Added destination service and Notion client tests; all 43 backend tests pass.
