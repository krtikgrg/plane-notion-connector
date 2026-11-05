# Plane and Notion API Glossary

This reference documents the external APIs called by the Plane-to-Notion connector. Examples use values observed in the live `p2n-e2e-fresh-03-nov-2026` workspace, including project `P2N E2E Fresh Project 02` (`P2NF2`) and its synced item `P2NF2-3`. Request and response examples are real successful calls or excerpts from their responses. A response excerpt is labeled when fields are omitted for readability or privacy.

The connector is one-way: it reads Plane and writes to Notion. It does not call Plane create, update, archive, or delete APIs.

## Authentication and copy/paste setup

Plane Cloud requests use `https://api.plane.so`, the `X-Api-Key` header, and the v2 API. Notion requests use `https://api.notion.com`, bearer authentication, and the `Notion-Version: 2026-03-11` header. Provide tokens in the shell; never paste token values into this document, source code, or a shared terminal transcript. For the local app, the backend reads its Notion connection token from `NOTION_CONNECTION_API_TOKEN`.

```bash
export PLANE_API_KEY='PASTE_AN_AUTHORIZED_PLANE_TOKEN_HERE'
export NOTION_TOKEN='PASTE_AN_AUTHORIZED_NOTION_TOKEN_HERE'
```

The examples below can then be copied directly. Plane list calls use `per_page=200`, which is the connector's maximum page size. Paginated endpoints may require following the returned `next`, `next_cursor`, or `has_more` values.

## Plane API

Plane v2 is read-only in this integration. API-key authentication acts with the key owner's Plane permissions. The examples use the real demo workspace slug `p2n-e2e-fresh-03-nov-2026`, project UUID `f16aff8e-ca3a-4f28-9024-91c514ae200b`, and work-item key `P2NF2-3` / UUID `55703772-2525-482b-a8c5-5001320187c7`.

### 1. Check the Plane credential

`GET /api/v2/users/me/` verifies the credential before project-scoped calls. The response excerpt omits the actual account ID and email.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/users/me/' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

```json
{
  "principal_kind": "api_key"
}
```

The live request returned HTTP `200`. Plane documents `401` for missing or invalid credentials. [Authentication and API keys](https://developers.plane.so/api-reference/v2/authentication).

### 2. List projects in a workspace

The catalog refresh calls this endpoint with archived projects included so it can retain unavailable/archive metadata. `fields` keeps the response small.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/workspaces/p2n-e2e-fresh-03-nov-2026/projects/?per_page=200&count=false&offset=0&include_archived=true&fields=id,name,identifier,archived_at' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

```json
{
  "data": [
    {
      "id": "f16aff8e-ca3a-4f28-9024-91c514ae200b",
      "name": "P2N E2E Fresh Project 02",
      "identifier": "P2NF2",
      "archived_at": null
    }
  ],
  "has_more": null,
  "next_cursor": null
}
```

This is the selected project row from the live list response. The application follows the response's offset `next` link when one is present. [List projects](https://developers.plane.so/api-reference/v2/projects/list-projects).

### 3. Read one project

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/workspaces/p2n-e2e-fresh-03-nov-2026/projects/f16aff8e-ca3a-4f28-9024-91c514ae200b/' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

```json
{
  "id": "f16aff8e-ca3a-4f28-9024-91c514ae200b",
  "name": "P2N E2E Fresh Project 02",
  "identifier": "P2NF2",
  "archived_at": null
}
```

This is used when resolving a project URL. [Get a project](https://developers.plane.so/api-reference/v2/projects/get-project).

### 4. List project workflow states

The sync reads project states to add newly observed state names to the Notion `state` select. The complete live response contained five states; this excerpt is the actual `Backlog` record.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/workspaces/p2n-e2e-fresh-03-nov-2026/projects/f16aff8e-ca3a-4f28-9024-91c514ae200b/states/?per_page=200&count=false&offset=0' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

```json
{
  "data": [
    {
      "id": "57849d35-e2af-4719-a30b-13b63c8a1df7",
      "name": "Backlog",
      "color": "#60646C",
      "group": "backlog",
      "is_default": true
    }
  ],
  "next": null
}
```

The response excerpt omits other returned state fields and the four additional states. [List states](https://developers.plane.so/api-reference/v2/states/list-states).

### 5. List project labels

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/workspaces/p2n-e2e-fresh-03-nov-2026/projects/f16aff8e-ca3a-4f28-9024-91c514ae200b/labels/?per_page=200&count=false&offset=0' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

```json
{
  "data": [],
  "next": null
}
```

The selected demo project had no labels when this request was verified. [List labels](https://developers.plane.so/api-reference/v2/labels/list-labels).

### 6. List active, non-draft work items

Project sync uses cursor pagination, asks for state/assignee/label expansions, and excludes archived items and drafts. Later incremental requests also add `updated_at__gte` and `updated_at__lte`; those bounds are calculated from the last successful run and current run start time.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/workspaces/p2n-e2e-fresh-03-nov-2026/projects/f16aff8e-ca3a-4f28-9024-91c514ae200b/work-items/?per_page=200&paginate=cursor&archived=false&is_draft=false&expand=state,assignees,labels&fields=id,identifier,project_id,state_id,name,description_html,priority,assignee_ids,label_ids,target_date,archived_at,updated_at,is_draft&order_by=updated_at' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

```json
{
  "data": [
    {
      "id": "55703772-2525-482b-a8c5-5001320187c7",
      "identifier": "P2NF2-3",
      "project_id": "f16aff8e-ca3a-4f28-9024-91c514ae200b",
      "state_id": "57849d35-e2af-4719-a30b-13b63c8a1df7",
      "name": "P2N Fresh 02 task 03 configured sync",
      "description_html": "<p></p>",
      "priority": "none",
      "assignee_ids": [],
      "label_ids": [],
      "target_date": null,
      "is_draft": false,
      "archived_at": null,
      "updated_at": "2026-11-03T17:33:38.401000Z",
      "state": {
        "id": "57849d35-e2af-4719-a30b-13b63c8a1df7",
        "name": "Backlog",
        "color": "#60646C",
        "group": "backlog"
      },
      "assignees": [],
      "labels": []
    }
  ],
  "has_more": false,
  "next_cursor": null
}
```

This is the actual matching row from the successful live list request. The project also contained `P2NF2-1` and `P2NF2-2`. [List work items](https://developers.plane.so/api-reference/v2/work-items/list-work-items).

### 7. Resolve a work item by its human key

This is the URL-only manual-sync lookup. It resolves the project and item UUID from a key such as `P2NF2-3` without listing a project. Archived or inaccessible items are not returned.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/workspaces/p2n-e2e-fresh-03-nov-2026/work-items/P2NF2-3/?expand=state,assignees,labels' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

```json
{
  "id": "55703772-2525-482b-a8c5-5001320187c7",
  "identifier": "P2NF2-3",
  "project_id": "f16aff8e-ca3a-4f28-9024-91c514ae200b",
  "state_id": "57849d35-e2af-4719-a30b-13b63c8a1df7",
  "name": "P2N Fresh 02 task 03 configured sync",
  "description_html": "<p></p>",
  "priority": "none",
  "assignee_ids": [],
  "label_ids": [],
  "target_date": null,
  "archived_at": null,
  "updated_at": "2026-11-03T17:33:38.401000Z",
  "is_draft": false,
  "state": { "id": "57849d35-e2af-4719-a30b-13b63c8a1df7", "name": "Backlog", "color": "#60646C", "group": "backlog" },
  "assignees": [],
  "labels": []
}
```

[Get a work item by identifier](https://developers.plane.so/api-reference/v2/work-items/get-work-item-by-identifier).

### 8. Read a work item by UUID

Full project sync re-reads each item by UUID before syncing it, and the manual URL path uses the UUID parsed from the URL.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.plane.so/api/v2/workspaces/p2n-e2e-fresh-03-nov-2026/projects/f16aff8e-ca3a-4f28-9024-91c514ae200b/work-items/55703772-2525-482b-a8c5-5001320187c7/?expand=state,assignees,labels' \
  -H "X-Api-Key: $PLANE_API_KEY" \
  -H 'Accept: application/json'
```

The live response is the same item object shown in the preceding section. [Get a work item](https://developers.plane.so/api-reference/v2/work-items/get-work-item).

## Notion API

The live examples below use `Notion-Version: 2026-03-11`. The stable project destination is page `40d53374-9003-81bb-a4f3-e885c792e435`, database `8a35d0a1-b8b5-4ed8-bc2f-521c4754d881`, and data source `e964a31e-d184-48b1-8c17-050cb75c9f16`. The data source contains the app's `name`, `identifier`, `work_item_id`, `work_item_url`, `description`, `state`, `priority`, `assignees`, `labels`, `due_date`, `plane_updated_at`, and `plane_lifecycle` properties.

Unless noted otherwise, these examples use the same headers:

```bash
NOTION_HEADERS=(-H "Authorization: Bearer $NOTION_TOKEN" \
  -H 'Notion-Version: 2026-03-11' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json')
```

### 1. Retrieve a page

The app reads a project page before reconciling its title or parent. This live request reads the final submission page.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.notion.com/v1/pages/40d53374-9003-80f5-b788-cdb63290ccf7' \
  "${NOTION_HEADERS[@]}"
```

```json
{
  "object": "page",
  "id": "40d53374-9003-80f5-b788-cdb63290ccf7",
  "url": "https://app.notion.com/p/FDE-India-Take-Home-Exercise-40d53374900380f5b788cdb63290ccf7",
  "parent": { "type": "workspace", "workspace": true },
  "properties": {
    "title": {
      "type": "title",
      "title": [{ "type": "text", "plain_text": "FDE India - Take Home Exercise" }]
    }
  }
}
```

The live GET returned HTTP `200`; this response excerpt keeps only the page ID, URL, parent, and title. [Retrieve a page](https://developers.notion.com/reference/retrieve-a-page).

### 2. Create a page

The app creates workspace/project pages and creates task rows. This tested page-create request made a temporary child of the real `P2N E2E Fresh Project 02` page. Its returned test page was later archived to Notion Trash.

```bash
curl --fail-with-body --silent --show-error -X POST \
  'https://api.notion.com/v1/pages' "${NOTION_HEADERS[@]}" \
  --data '{
    "parent": { "type": "page_id", "page_id": "40d53374-9003-81bb-a4f3-e885c792e435" },
    "properties": {
      "title": {
        "title": [{ "type": "text", "text": { "content": "API Glossary Sample (temporary)" } }]
      }
    }
  }'
```

```json
{
  "object": "page",
  "id": "40f53374-9003-8102-b683-cabda9fa9453",
  "parent": { "type": "page_id", "page_id": "40d53374-9003-81bb-a4f3-e885c792e435" },
  "properties": {
    "title": { "title": [{ "type": "text", "plain_text": "API Glossary Sample (temporary)" }] }
  }
}
```

The test returned HTTP `200`. A create request returns a fresh page ID each time; use that returned ID for subsequent operations. [Create a page](https://developers.notion.com/reference/post-page).

### 3. Create an inline database and its initial data source

The connector creates each project's work-item database with `initial_data_source`. This verified request was parented to the temporary page from the prior example. The API returned a database and its initial data source in one response.

```bash
export TEMP_PAGE_ID='40f53374-9003-8102-b683-cabda9fa9453'
curl --fail-with-body --silent --show-error -X POST \
  'https://api.notion.com/v1/databases' "${NOTION_HEADERS[@]}" \
  --data "{\
    \"parent\":{\"type\":\"page_id\",\"page_id\":\"$TEMP_PAGE_ID\"},\
    \"title\":[{\"type\":\"text\",\"text\":{\"content\":\"API Glossary Sample Items\"}}],\
    \"is_inline\":true,\
    \"initial_data_source\":{\"properties\":{\
      \"name\":{\"title\":{}},\"identifier\":{\"rich_text\":{}},\
      \"work_item_id\":{\"rich_text\":{}},\"work_item_url\":{\"url\":{}},\
      \"description\":{\"rich_text\":{}},\"state\":{\"select\":{\"options\":[{\"name\":\"Backlog\"},{\"name\":\"Done\"}]}},\
      \"priority\":{\"select\":{\"options\":[{\"name\":\"urgent\"},{\"name\":\"high\"},{\"name\":\"medium\"},{\"name\":\"low\"}]}},\
      \"assignees\":{\"rich_text\":{}},\"labels\":{\"multi_select\":{\"options\":[{\"name\":\"demo\"}]}},\
      \"due_date\":{\"date\":{}},\"plane_updated_at\":{\"date\":{}},\
      \"plane_lifecycle\":{\"select\":{\"options\":[{\"name\":\"ACTIVE\"},{\"name\":\"MISSING_FROM_PROJECT\"}]}}\
    }}\
  }"
```

```json
{
  "object": "database",
  "id": "d3df602c-dde5-436b-9bc9-e9a4fbe99937",
  "parent": { "type": "page_id", "page_id": "40f53374-9003-8102-b683-cabda9fa9453" },
  "is_inline": true,
  "data_sources": [
    { "id": "55f6cd81-6745-4d7c-b994-97d9d394205a", "name": "API Glossary Sample Items" }
  ]
}
```

The test returned HTTP `200`. Both generated test objects were later archived. For the app's stable project destination, the live database ID is `8a35d0a1-b8b5-4ed8-bc2f-521c4754d881` and its data-source ID is `e964a31e-d184-48b1-8c17-050cb75c9f16`. [Create a database](https://developers.notion.com/reference/create-a-database).

### 4. Retrieve a database

The app consumes the database response at creation. This additional live GET verifies the current database/data-source relationship and is useful when inspecting an existing destination.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.notion.com/v1/databases/8a35d0a1-b8b5-4ed8-bc2f-521c4754d881' \
  "${NOTION_HEADERS[@]}"
```

```json
{
  "object": "database",
  "id": "8a35d0a1-b8b5-4ed8-bc2f-521c4754d881",
  "parent": { "type": "page_id", "page_id": "40d53374-9003-81bb-a4f3-e885c792e435" },
  "is_inline": true,
  "data_sources": [
    { "id": "e964a31e-d184-48b1-8c17-050cb75c9f16", "name": "Plane Work Items" }
  ]
}
```

The live GET returned HTTP `200`. [Database object](https://developers.notion.com/reference/database).

### 5. Retrieve a data source and its schema

```bash
curl --fail-with-body --silent --show-error \
  'https://api.notion.com/v1/data_sources/e964a31e-d184-48b1-8c17-050cb75c9f16' \
  "${NOTION_HEADERS[@]}"
```

```json
{
  "object": "data_source",
  "id": "e964a31e-d184-48b1-8c17-050cb75c9f16",
  "parent": { "type": "database_id", "database_id": "8a35d0a1-b8b5-4ed8-bc2f-521c4754d881" },
  "properties": {
    "name": { "type": "title" },
    "identifier": { "type": "rich_text" },
    "work_item_id": { "type": "rich_text" },
    "work_item_url": { "type": "url" },
    "description": { "type": "rich_text" },
    "state": { "type": "select", "select": { "options": [{ "name": "Backlog" }, { "name": "Todo" }, { "name": "In Progress" }, { "name": "Done" }, { "name": "Cancelled" }] } },
    "priority": { "type": "select" },
    "assignees": { "type": "rich_text" },
    "labels": { "type": "multi_select", "multi_select": { "options": [] } },
    "due_date": { "type": "date" },
    "plane_updated_at": { "type": "date" },
    "plane_lifecycle": { "type": "select" }
  }
}
```

The live GET returned HTTP `200`; option IDs and other schema metadata are omitted here. [Data source object](https://developers.notion.com/reference/data-source).

### 6. Update data-source select and multi-select options

The app merges new Plane state/label choices into the current schema. This live PATCH resubmitted the existing choices on the real data source, so it made no visible schema change.

```bash
curl --fail-with-body --silent --show-error -X PATCH \
  'https://api.notion.com/v1/data_sources/e964a31e-d184-48b1-8c17-050cb75c9f16' \
  "${NOTION_HEADERS[@]}" \
  --data '{
    "properties": {
      "state": { "select": { "options": [
        { "name": "Backlog" }, { "name": "Todo" }, { "name": "In Progress" },
        { "name": "Done" }, { "name": "Cancelled" }
      ] } },
      "labels": { "multi_select": { "options": [] } }
    }
  }'
```

```json
{
  "object": "data_source",
  "id": "e964a31e-d184-48b1-8c17-050cb75c9f16",
  "title": [{ "type": "text", "plain_text": "Plane Work Items" }],
  "properties": {
    "state": { "type": "select", "select": { "options": [{ "name": "Backlog" }, { "name": "Todo" }, { "name": "In Progress" }, { "name": "Done" }, { "name": "Cancelled" }] } },
    "labels": { "type": "multi_select", "multi_select": { "options": [] } }
  }
}
```

The live PATCH returned HTTP `200`. [Update a data source](https://developers.notion.com/reference/update-a-data-source).

### 7. Query rows by Plane work-item UUID

This is the idempotency-recovery query used before retrying a pending Notion page create. The real request found the `P2NF2-3` row.

```bash
curl --fail-with-body --silent --show-error -X POST \
  'https://api.notion.com/v1/data_sources/e964a31e-d184-48b1-8c17-050cb75c9f16/query' \
  "${NOTION_HEADERS[@]}" \
  --data '{
    "page_size": 1,
    "filter": {
      "property": "work_item_id",
      "rich_text": { "equals": "55703772-2525-482b-a8c5-5001320187c7" }
    }
  }'
```

```json
{
  "results": [
    {
      "object": "page",
      "id": "40d53374-9003-8139-9388-dd198ff4dab1",
      "parent": {
        "type": "data_source_id",
        "data_source_id": "e964a31e-d184-48b1-8c17-050cb75c9f16",
        "database_id": "8a35d0a1-b8b5-4ed8-bc2f-521c4754d881"
      },
      "properties": {
        "name": { "title": [{ "type": "text", "plain_text": "P2N Fresh 02 task 03 configured sync" }] },
        "identifier": { "rich_text": [{ "type": "text", "plain_text": "P2NF2-3" }] },
        "work_item_id": { "rich_text": [{ "type": "text", "plain_text": "55703772-2525-482b-a8c5-5001320187c7" }] }
      }
    }
  ],
  "has_more": false,
  "next_cursor": null
}
```

The live query returned HTTP `200`. [Query a data source](https://developers.notion.com/reference/query-a-data-source).

### 8. Create a row in a data source

This is the app's Notion row-create shape. The sample values below are synthetic and independent of Plane. The verified sample request used a temporary data source; its returned row was archived after the API test.

```bash
export TEMP_DATA_SOURCE_ID='55f6cd81-6745-4d7c-b994-97d9d394205a'
curl --fail-with-body --silent --show-error -X POST \
  'https://api.notion.com/v1/pages' "${NOTION_HEADERS[@]}" \
  --data "{\
    \"parent\":{\"type\":\"data_source_id\",\"data_source_id\":\"$TEMP_DATA_SOURCE_ID\"},\
    \"properties\":{\
      \"name\":{\"title\":[{\"type\":\"text\",\"text\":{\"content\":\"API Glossary sample item\"}}]},\
      \"identifier\":{\"rich_text\":[{\"type\":\"text\",\"text\":{\"content\":\"API-1\"}}]},\
      \"work_item_id\":{\"rich_text\":[{\"type\":\"text\",\"text\":{\"content\":\"api-item-1\"}}]},\
      \"work_item_url\":{\"url\":\"https://example.com/items/API-1\"},\
      \"description\":{\"rich_text\":[{\"type\":\"text\",\"text\":{\"content\":\"Temporary API payload example.\"}}]},\
      \"state\":{\"select\":{\"name\":\"Backlog\"}},\"priority\":{\"select\":{\"name\":\"medium\"}},\
      \"assignees\":{\"rich_text\":[{\"type\":\"text\",\"text\":{\"content\":\"Kartik\"}}]},\
      \"labels\":{\"multi_select\":[{\"name\":\"demo\"}]},\
      \"plane_updated_at\":{\"date\":{\"start\":\"2026-11-03T17:33:38.401Z\"}},\
      \"plane_lifecycle\":{\"select\":{\"name\":\"ACTIVE\"}}\
    }\
  }"
```

```json
{
  "object": "page",
  "id": "40f53374-9003-818e-b24d-eeb51cdf1b36",
  "parent": {
    "type": "data_source_id",
    "data_source_id": "55f6cd81-6745-4d7c-b994-97d9d394205a",
    "database_id": "d3df602c-dde5-436b-9bc9-e9a4fbe99937"
  },
  "properties": {
    "name": { "title": [{ "type": "text", "plain_text": "API Glossary sample item" }] },
    "identifier": { "rich_text": [{ "type": "text", "plain_text": "API-1" }] }
  }
}
```

The live create returned HTTP `200`. Each create makes a new page; the test row and its temporary database were moved to Notion Trash after the test. [Create a page](https://developers.notion.com/reference/post-page).

### 9. Update a page's mapped properties

The app updates a row when the normalized Plane-derived properties changed. This live request resent the row's existing title and state, so the row values remained the same.

```bash
curl --fail-with-body --silent --show-error -X PATCH \
  'https://api.notion.com/v1/pages/40d53374-9003-8139-9388-dd198ff4dab1' \
  "${NOTION_HEADERS[@]}" \
  --data '{
    "properties": {
      "name": { "title": [{ "type": "text", "text": { "content": "P2N Fresh 02 task 03 configured sync" } }] },
      "state": { "select": { "name": "Backlog" } }
    }
  }'
```

```json
{
  "object": "page",
  "id": "40d53374-9003-8139-9388-dd198ff4dab1",
  "parent": {
    "type": "data_source_id",
    "data_source_id": "e964a31e-d184-48b1-8c17-050cb75c9f16",
    "database_id": "8a35d0a1-b8b5-4ed8-bc2f-521c4754d881"
  },
  "in_trash": false,
  "properties": {
    "name": { "title": [{ "type": "text", "plain_text": "P2N Fresh 02 task 03 configured sync" }] },
    "state": { "type": "select", "select": { "name": "Backlog" } },
    "identifier": { "type": "rich_text", "rich_text": [{ "type": "text", "plain_text": "P2NF2-3" }] }
  }
}
```

The live PATCH returned HTTP `200`; the response excerpt omits unchanged properties. [Update a page](https://developers.notion.com/reference/update-page).

### 10. Move a page to a new parent

The app moves project pages when the mapped workspace parent changes. The live test moved a temporary page to the existing workspace page and then restored the original parent.

```bash
export TEMP_PAGE_ID='40f53374-9003-8102-b683-cabda9fa9453'
curl --fail-with-body --silent --show-error -X POST \
  "https://api.notion.com/v1/pages/$TEMP_PAGE_ID/move" "${NOTION_HEADERS[@]}" \
  --data '{
    "parent": { "type": "page_id", "page_id": "40d53374-9003-81c4-8fbf-e380efcea4dc" }
  }'
```

```json
{
  "object": "page",
  "id": "40f53374-9003-8102-b683-cabda9fa9453",
  "parent": { "type": "page_id", "page_id": "40d53374-9003-81c4-8fbf-e380efcea4dc" }
}
```

The move returned HTTP `200`; the page was then moved back to project page `40d53374-9003-81bb-a4f3-e885c792e435`. Use a fresh page ID from the create-page response when repeating this test. [Move a page](https://developers.notion.com/reference/move-page).

### 11. Read child blocks

The app reads page blocks to refresh workspace and parent-page summaries. This live request inspected the project page, which contains its task database block.

```bash
curl --fail-with-body --silent --show-error \
  'https://api.notion.com/v1/blocks/40d53374-9003-81bb-a4f3-e885c792e435/children?page_size=100' \
  "${NOTION_HEADERS[@]}"
```

```json
{
  "results": [
    { "object": "block", "id": "8a35d0a1-b8b5-4ed8-bc2f-521c4754d881", "type": "child_database" }
  ],
  "has_more": false,
  "next_cursor": null
}
```

The live GET returned HTTP `200`. The app requests `page_size=100` and follows `next_cursor` when `has_more` is true. [Retrieve block children](https://developers.notion.com/reference/get-block-children).

### 12. Append child blocks

The app appends headings, callouts, and table blocks when it refreshes summary sections. This verified request added temporary blocks to a temporary page; that page was later archived.

```bash
curl --fail-with-body --silent --show-error -X PATCH \
  'https://api.notion.com/v1/blocks/40f53374-9003-8102-b683-cabda9fa9453/children' \
  "${NOTION_HEADERS[@]}" \
  --data '{
    "children": [
      { "object": "block", "type": "heading_2", "heading_2": { "rich_text": [{ "type": "text", "text": { "content": "Temporary API summary" } }] } },
      { "object": "block", "type": "callout", "callout": { "rich_text": [{ "type": "text", "text": { "content": "Temporary API validation callout." } }] } }
    ]
  }'
```

```json
{
  "results": [
    { "object": "block", "id": "40f53374-9003-817c-bbe1-dc9c30824248", "type": "heading_2" },
    { "object": "block", "id": "40f53374-9003-81d7-ba92-fb8878ac7c33", "type": "callout" }
  ]
}
```

The live PATCH returned HTTP `200`. The returned block IDs are generated values; use IDs from the response for later block updates. [Append block children](https://developers.notion.com/reference/patch-block-children).

### 13. Update a block or move it to Trash

The app uses this endpoint to revise summary headings/callouts/table rows and to move obsolete blocks to Trash. The `in_trash` field is the archive request field used by this integration.

Update a heading:

```bash
curl --fail-with-body --silent --show-error -X PATCH \
  'https://api.notion.com/v1/blocks/40f53374-9003-817c-bbe1-dc9c30824248' \
  "${NOTION_HEADERS[@]}" \
  --data '{
    "heading_2": { "rich_text": [{ "type": "text", "text": { "content": "Temporary API summary (updated)" } }] }
  }'
```

```json
{
  "object": "block",
  "id": "40f53374-9003-817c-bbe1-dc9c30824248",
  "type": "heading_2",
  "heading_2": { "rich_text": [{ "type": "text", "plain_text": "Temporary API summary (updated)" }] }
}
```

The live update returned HTTP `200`.

Move a block to Trash:

```bash
curl --fail-with-body --silent --show-error -X PATCH \
  'https://api.notion.com/v1/blocks/40f53374-9003-8150-8fc6-e3129eed02ca' \
  "${NOTION_HEADERS[@]}" \
  --data '{ "in_trash": true }'
```

```json
{
  "object": "block",
  "id": "40f53374-9003-8150-8fc6-e3129eed02ca",
  "type": "paragraph",
  "in_trash": true
}
```

The live archive PATCH returned HTTP `200`. Both examples used temporary blocks that were then cleaned up with their temporary parent page. [Update a block](https://developers.notion.com/reference/update-a-block).

### 14. Archive a page or database block

The app uses `DELETE /v1/blocks/{id}` to archive a page created during incomplete setup. Notion moves deleted blocks to Trash; it does not permanently erase them.

```bash
curl --fail-with-body --silent --show-error -X DELETE \
  'https://api.notion.com/v1/blocks/40f53374-9003-8166-a12e-e0f20ed528e2' \
  "${NOTION_HEADERS[@]}"
```

```json
{
  "object": "page",
  "id": "40f53374-9003-8166-a12e-e0f20ed528e2",
  "in_trash": true
}
```

The live DELETE returned HTTP `200`. The request ID above is from a temporary validation page already in Trash; for a new setup test, use the fresh page ID returned by the create-page request. [Delete a block](https://developers.notion.com/reference/delete-a-block).

## Live verification record

- Plane requests listed above were sent to Plane Cloud using the actual demo workspace, project, and `P2NF2-3` item. All listed requests returned HTTP `200`.
- Notion page, database, data-source, query, create, update, move, block read/append/update/archive, and delete examples were exercised against the actual project destination or temporary test objects. Successful calls returned HTTP `200`.
- Temporary Notion test page `40f53374-9003-8102-b683-cabda9fa9453`, database `d3df602c-dde5-436b-9bc9-e9a4fbe99937`, data source `55f6cd81-6745-4d7c-b994-97d9d394205a`, and sample row `40f53374-9003-818e-b24d-eeb51cdf1b36` were archived to Notion Trash after verification. The example IDs from those create calls are recorded responses; new create calls return new IDs.

## Official references

- Plane: [v2 introduction](https://developers.plane.so/api-reference/v2/introduction), [authentication](https://developers.plane.so/api-reference/v2/authentication), [project list](https://developers.plane.so/api-reference/v2/projects/list-projects), [work-item list](https://developers.plane.so/api-reference/v2/work-items/list-work-items), and [work-item lookup by identifier](https://developers.plane.so/api-reference/v2/work-items/get-work-item-by-identifier).
- Notion: [create a page](https://developers.notion.com/reference/post-page), [create a database](https://developers.notion.com/reference/create-a-database), [database](https://developers.notion.com/reference/database), [data source](https://developers.notion.com/reference/data-source), [query a data source](https://developers.notion.com/reference/query-a-data-source), [page properties](https://developers.notion.com/reference/page-property-values), [append block children](https://developers.notion.com/reference/patch-block-children), [update a block](https://developers.notion.com/reference/update-a-block), and [delete a block](https://developers.notion.com/reference/delete-a-block).
