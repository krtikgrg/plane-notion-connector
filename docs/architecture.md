# Plane to Notion Connector Architecture

Current implementation: a React + TypeScript control UI and one Java 21 / Spring Boot service. The service exposes the local REST API and runs a single background worker that claims durable jobs from MongoDB.

```mermaid
flowchart LR
  UI[Browser UI<br/>React + TypeScript] --> API[REST controllers]
  API --> APP[Application services<br/>Config, project catalog, sync jobs]
  SCHEDULE[ScheduleService] -->|enqueue due occurrence| JOBS[(MongoDB<br/>sync_jobs)]
  APP -->|enqueue sync or config operation| JOBS
  JOBS -->|atomic claim| WORKER[SyncJobWorker<br/>polls every 5 seconds]

  WORKER -->|MANUAL / SCHEDULED| SYNC[WorkItemSyncService]
  WORKER -->|CONFIGURATION<br/>CREATE / UPDATE| CONFIG[ConfigService<br/>NotionHierarchyService]

  SYNC --> PLANE[PlaneClient]
  PLANE --> PLANEAPI[Plane API]
  SYNC --> DEST{Choose destination}
  DEST -->|matching project config| PROJECTDEST[Configured project destination]
  DEST -->|no matching config| ADHOC[AdHocNotionDestinationService<br/>create once, then reuse]
  CONFIG --> PROJECTDEST
  CONFIG -->|provision or refresh hierarchy| NOTIONBLOCK[NotionBlockClient<br/>blocks, summaries, archival]
  PROJECTDEST --> NOTION[NotionClient<br/>pages, databases, data sources, work items]
  ADHOC --> NOTION
  NOTION --> NOTIONAPI[Notion API]
  NOTIONBLOCK --> NOTIONAPI

  subgraph DB[MongoDB: plane_to_notion]
    JOBS
    CONFIGS[sync_configs<br/>project reference, options, schedule, status]
    PROJECTS[plane_projects<br/>Plane metadata + configured Notion page/database/data source IDs]
    WORKSPACES[plane_workspaces<br/>Plane workspace slug to Notion workspace page]
    ADHOCDATA[notion_destinations<br/>shared ad hoc destination IDs]
    MAPPINGS[sync_mappings<br/>work item to Notion page + source hash]
  end

  APP --> CONFIGS
  CONFIG --> CONFIGS
  APP --> PROJECTS
  CONFIG --> PROJECTS
  APP --> WORKSPACES
  SYNC --> MAPPINGS
  ADHOC --> ADHOCDATA
```

## Job and destination behavior

- The `sync_jobs` collection is the durable queue for `MANUAL`, `SCHEDULED`, and `CONFIGURATION` jobs. Configuration jobs carry `CREATE` or `UPDATE`; only manual and scheduled sync jobs appear in run history.
- Config creation and updates return before Notion provisioning finishes. The UI shows `PROVISIONING` or `UPDATING` until the worker completes the operation and sets `READY` or `FAILED`.
- A sync uses the Notion destination IDs stored on its `plane_projects` record when a matching config exists. An unconfigured task URL uses the shared destination, whose IDs are stored in `notion_destinations` and provisioned lazily on first use.
- `NotionClient` handles page, database, data-source, and work-item operations. `NotionBlockClient` handles block reads/writes, hierarchy summaries, and page archival.
- `sync_configs` stores a reference to a Plane project record plus sync options, schedule, and lifecycle/operation status. It does not own the Notion destination IDs.
- Project sync uses checkpointed reads for active, non-draft Plane work items. Work-item mappings and source hashes make writes idempotent and skip unchanged Notion updates.
