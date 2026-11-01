# Plane - Notion Connector

Copies active Plane work items into a Notion database for each configured project. Includes scheduled and manual syncs, run history, and a local dashboard.

**Stack:** Java 21, Spring Boot, React + TypeScript, Vite, MongoDB.

## Run with Docker

1. Copy `.env.example` to `.env` and add the Plane token, Notion parent page ID, ad-hoc destination IDs, and Notion connection API token. The personal token is a local developer fallback.
2. Run `docker compose up --build`.
3. Open [http://127.0.0.1:8080](http://127.0.0.1:8080).

The UI and API run in one container; MongoDB runs in a separate local container. The `.env` file is ignored by Git and excluded from the Docker build.

## Run for development

Requires Java 21, Maven, Node.js, and a local MongoDB.

1. Copy `.env.example` to `.env` and fill in the credentials, Notion parent page ID, and ad-hoc destination IDs. Prefer `NOTION_CONNECTION_API_TOKEN`; `NOTION_CONNECTION_TOKEN` is accepted as an older alias, with `NOTION_PERSONAL_ACCESS_TOKEN` as a final developer fallback.
2. Start MongoDB.
3. In one terminal, run `cd backend && mvn spring-boot:run`.
4. In another, run `cd frontend && npm ci && npm run dev`.
5. Open [http://127.0.0.1:5173](http://127.0.0.1:5173).

Plane and Notion API base URLs default to their public cloud endpoints. Override them for self-hosted Plane or another Notion API endpoint.
