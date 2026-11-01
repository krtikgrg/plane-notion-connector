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

A project record contains `plane_project_ref`, `workspace_slug`, `project_id`, `name`, `identifier`, `project_url`, `is_archived`, `is_available`, and `last_refreshed_at`.

## Configurations

| Method and route | Request/query | Success response | Expected errors |
|---|---|---|---|
| `GET /api/configs` | None | Array of config records with joined project metadata | `404` if a referenced project is missing; `500` for persistence errors |
| `POST /api/configs` | Project ref, status, sync options, schedule fields | Config record; `201 Created` | `400` invalid options/schedule; `404` project missing; `409` config already exists; `502` integration or incomplete cleanup failure |
| `GET /api/configs/{config_id}` | Mongo ObjectId | One config record | `400` invalid ID; `404` config/project missing |
| `PATCH /api/configs/{config_id}` | Status, sync options, schedule fields | Updated config record | `400` invalid options/schedule/ID; `404` config/project missing; `500` if persistence or schedule update fails and rollback succeeds; `502` if rollback also fails |

Config records include the Plane project, Notion page/database/data-source IDs, `sync_options`, status, schedule settings, window bounds, and timestamps. Schedule start is inclusive and end is exclusive; calendar times use UTC.

## Sync jobs and run history

| Method and route | Request/query | Success response | Expected errors |
|---|---|---|---|
| `POST /api/configs/{config_id}/sync-jobs` | `{ "force_archived_config": false }` | `{ "job_id", "status", "created_at" }`; `202 Accepted` | `400` invalid ID; `404` config missing; `409` archived config without force |
| `GET /api/configs/{config_id}/sync-jobs` | Optional `status`, `trigger_type`, `created_from`, `created_to`, `before_id`, `page_size` | `{ "items": [job], "next_before_id": "..." }` | `400` invalid filter/ID or page size |
| `POST /api/sync-jobs/manual` | `{ "task_url": "...", "config_id": "..."? , "force_archived_config": false }` | `{ "job_id", "status", "created_at" }`; `202 Accepted` | `400` invalid URL, archived/draft task, or project mismatch; `404` task/project/config missing; `409` archived config without force; `502` other upstream errors |
| `GET /api/sync-jobs` | Optional `config_id`, `status`, `trigger_type`, `created_from`, `created_to`, `before_id`, `page_size` | `{ "items": [job], "next_before_id": "..." }` | `400` invalid filter/ID or page size |
| `GET /api/sync-jobs/next-scheduled` | None | Object mapping config IDs to their earliest pending scheduled timestamp | `500` for persistence errors |
| `GET /api/sync-jobs/{job_id}` | Mongo ObjectId | One job record | `400` invalid ID; `404` job missing |

Run-history pages are sorted by descending Mongo `_id`. `page_size` defaults to 20 and is limited to 100. `before_id` is the last job ID returned by the previous page. Job records include scope, trigger, status, checkpoint/run timestamps, counts (`eligible_count`, `created_count`, `updated_count`, `unchanged_count`, `failed_count`), and sanitized error details.
