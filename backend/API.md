# Local API contract

All routes use JSON and return errors as `{"error_code":"...","message":"..."}`. The service binds to `127.0.0.1` by default. It has no user authentication; do not expose it to a network without adding authentication and TLS. Set `SERVER_ADDRESS` only for a secured deployment.

## Health and Plane project catalog

| Method and route | Request/query | Success response | Expected errors |
|---|---|---|---|
| `GET /api/health` | None | `{ "status": "UP" }` | None |
| `POST /api/plane/connection/test` | None; uses the server's Plane credentials | `{ "status": "CONNECTED" }` | `502` if the upstream connection or credentials fail |
| `GET /api/plane/projects?workspace_slug=...` | Required workspace slug | Array of cached project records | `400` for blank slug; `502` for Plane errors |
| `POST /api/plane/projects/refresh` | `{ "workspace_slug": "..." }` | `{ "workspace_slug", "refreshed_at", "projects" }` | `400` for missing slug; `500` for Mongo errors; `502` for Plane errors |
| `POST /api/plane/projects/resolve` | `{ "project_url": "..." }` | One project record | `400` for invalid URL/host; `404` if Plane cannot find it; `502` for other upstream errors |

The cached Mongo project document stores `plane_workspace_slug`, `plane_project_id`, `plane_project_name`, `plane_project_identifier`, `is_archived_in_plane`, `is_available`, and optional Notion destination IDs. The API keeps its existing project field names (`workspace_slug`, `project_id`, `name`, `identifier`, `is_archived`, `is_available`) and exposes the Mongo ID as `plane_project_document_id`. All three Notion IDs are either set together or absent.

## Configurations

| Method and route | Request/query | Success response | Expected errors |
|---|---|---|---|
| `GET /api/configs` | None | Array of config records with joined project metadata | `404` if a referenced project is missing; `500` for persistence errors |
| `POST /api/configs` | Shared upsert request: `plane_project_document_id` (required), typed `sync_options`, and nested `schedule`; `status` is optional and defaults to `ACTIVE` | Config record; `201 Created` | `400` invalid options/schedule/ID; `404` project missing; `409` config already exists; `502` integration or incomplete cleanup failure |
| `GET /api/configs/{config_id}` | Mongo ObjectId | One config record | `400` invalid ID; `404` config/project missing |
| `PATCH /api/configs/{config_id}` | Same upsert request; status, typed `sync_options`, and nested `schedule` are required. `plane_project_document_id` is ignored | Updated config record | `400` invalid options/schedule/ID; `404` config/project missing; `500` if persistence or schedule update fails and rollback succeeds; `502` if rollback also fails |

Config responses include joined Plane project data and the project's Notion page/database/data-source IDs, plus config status, sync options, schedule, and timestamps. Mongo `sync_configs` stores only the project document reference and sync settings; the destination IDs live on `plane_projects`. `sync_options` has `work_items` and `pages` booleans. `schedule` has `type`, `start_at`, `end_at`, and exactly one of `interval` (`value`, `unit`) or `calendar` (`frequency`, `interval`, `days_of_week`, `time_of_day`). Schedule start is inclusive and end is exclusive; calendar times use UTC.

Example interval schedule:

```json
{
  "sync_options": { "work_items": true, "pages": false },
  "schedule": {
    "type": "INTERVAL",
    "start_at": null,
    "end_at": null,
    "interval": { "value": 30, "unit": "MINUTES" },
    "calendar": null
  }
}
```

Example weekly calendar schedule:

```json
{
  "sync_options": { "work_items": true, "pages": false },
  "schedule": {
    "type": "CALENDAR",
    "start_at": null,
    "end_at": null,
    "interval": null,
    "calendar": {
      "frequency": "WEEKLY",
      "interval": 1,
      "days_of_week": ["MONDAY"],
      "time_of_day": "09:00:00"
    }
  }
}
```

## Sync jobs and run history

| Method and route | Request/query | Success response | Expected errors |
|---|---|---|---|
| `POST /api/configs/{config_id}/sync-jobs` | `{ "force_archived_config": false }` | `{ "job_id", "job_status", "created_at" }`; `202 Accepted` | `400` invalid ID; `404` config missing; `409` archived config without force |
| `GET /api/configs/{config_id}/sync-jobs` | Optional `job_status`, `job_type`, `created_from`, `created_to`, `before_id`, `page_size` | `{ "items": [job], "next_before_id": "..." }` | `400` invalid filter/ID or page size |
| `POST /api/sync-jobs/manual` | `{ "task_url": "...", "config_id": "..."? , "force_archived_config": false }` | `{ "job_id", "job_status", "created_at" }`; `202 Accepted` | `400` invalid URL, archived/draft task, or project mismatch; `404` task/project/config missing; `409` archived config without force; `502` other upstream errors |
| `GET /api/sync-jobs` | Optional `config_id`, `job_status`, `job_type`, `created_from`, `created_to`, `before_id`, `page_size` | `{ "items": [job], "next_before_id": "..." }` | `400` invalid filter/ID or page size |
| `GET /api/sync-jobs/next-scheduled` | None | Object mapping config IDs to their earliest pending scheduled timestamp | `500` for persistence errors |
| `GET /api/sync-jobs/{job_id}` | Mongo ObjectId | One job record | `400` invalid ID; `404` job missing |

Run-history pages are sorted by descending Mongo `_id`. `page_size` defaults to 20 and is limited to 100. `before_id` is the last job ID returned by the previous page. Job responses include `job_type`, `scope`, `job_status`, `plane_project_document_id` (the Mongo ID of the cached Plane project record), and `plane_work_item` (null for project runs; otherwise `{ "task_url", "work_item_id" }). Distinct outcome counts are grouped under `result`: `eligible_plane_work_items_count`, `created_notion_pages_count`, `updated_notion_pages_count`, and `unchanged_notion_pages_count`. `error_code` and `error_message` remain strings and are null unless the run needs an outcome explanation. Internally, Mongo stores manual and scheduled records as concrete subtypes in the same collection; the controller maps both to this same response DTO. Manual Mongo records own `plane_work_item` and `force_archived_config`, while scheduled records own `scheduled_for`; irrelevant fields are omitted from each subtype. The Mongo documents also store `result` as a nested object.
