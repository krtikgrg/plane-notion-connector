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

1. Copy `.env.example` to `.env` and fill in the credentials, including `NOTION_CONNECTION_API_TOKEN` and the Notion parent page ID.
2. Start MongoDB.
3. In one terminal, run `cd backend && mvn spring-boot:run`.
4. In another, run `cd frontend && npm ci && npm run dev`.
5. Open [http://127.0.0.1:5173](http://127.0.0.1:5173).

Optional endpoint overrides are `PLANE_API_BASE_URL` (default `https://api.plane.so`), `PLANE_APP_BASE_URL` (default `https://app.plane.so`), and `NOTION_API_BASE_URL` (default `https://api.notion.com`). For self-hosted Plane, set both Plane URLs: the API URL is used for requests, and the app URL is used to validate and display Plane links.
