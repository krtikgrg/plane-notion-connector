# Plane - Notion Connector

Copies active Plane work items into a Notion database for each configured project. Includes scheduled and manual syncs, run history, and a local dashboard.

Config setup and edits run asynchronously through the shared MongoDB job queue; the UI shows provisioning status.

The first manual task sync without a project config creates a shared Notion destination under the configured parent page and stores its IDs in MongoDB.

**Stack:** Java 21, Spring Boot, React + TypeScript, Vite, MongoDB.

## Run with Docker

1. Copy `.env.example` to `.env` and add the Plane token, Notion connection API token, and Notion parent page ID.
2. Run `docker compose up --build`.
3. Open [http://127.0.0.1:8080](http://127.0.0.1:8080).

The UI and API run in one container; MongoDB runs in a separate local container. The `.env` file is ignored by Git and excluded from the Docker build.

## Run for development

Requires Java 21, Maven, Node.js, and a local MongoDB.

1. Copy `.env.example` to `.env`. Set `PLANE_PERSONAL_ACCESS_TOKEN`, `NOTION_CONNECTION_API_TOKEN`, and `NOTION_PARENT_PAGE_ID`. Make sure the Notion integration can access the parent page. `MONGODB_URI` is optional and defaults to `mongodb://localhost:27017`; the app uses the `plane_to_notion` database.
2. Start local MongoDB and confirm it is listening at the configured `MONGODB_URI`.
3. Before starting the app, check whether ports 8080 and 5173 are already occupied: `lsof -nP -iTCP:8080 -iTCP:5173 -sTCP:LISTEN`. Identify any listed process and stop the old backend or Vite process before proceeding; leave MongoDB running.
4. In the backend terminal, run `cd backend && mvn clean spring-boot:run`. Wait for Spring Boot to report that the application started, and leave this terminal open so backend errors remain visible. The clean build avoids stale classes from previous builds.
5. In a second terminal, run `cd frontend && npm ci && npm run dev -- --host 127.0.0.1`. Leave this terminal open as well.
6. In a third terminal, check the backend before opening the UI:

   ```sh
   curl --fail --show-error http://127.0.0.1:8080/api/health
   curl --fail --show-error http://127.0.0.1:8080/api/configs
   curl --fail --show-error 'http://127.0.0.1:8080/api/sync-jobs?page_size=20'
   ```

   Each request should return HTTP 200. The health response should contain `"status":"UP"`. If a request fails, check the backend terminal for the exception before continuing.
7. Open [http://127.0.0.1:5173](http://127.0.0.1:5173).

If the UI shows an API error, open the browser's Network panel to identify the failing `/api/...` request, then check the backend terminal for its stack trace. A 500 from the API means the backend encountered an unexpected exception; a Vite proxy error usually means the backend is unreachable or the two servers are using different host/port settings. The development proxy targets `127.0.0.1:8080`, matching the backend's default bind address.

Optional endpoint overrides are `PLANE_API_BASE_URL` (default `https://api.plane.so`), `PLANE_APP_BASE_URL` (default `https://app.plane.so`), and `NOTION_API_BASE_URL` (default `https://api.notion.com`). For self-hosted Plane, set both Plane URLs: the API URL is used for requests, and the app URL is used to validate and display Plane links.
