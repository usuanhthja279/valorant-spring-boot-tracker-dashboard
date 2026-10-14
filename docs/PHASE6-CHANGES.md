# Phase 6 — Production readiness

## Implemented

- Added a shared servlet request-logging filter. Every HTTP request logs method, path (without query parameters), response status, duration in milliseconds, and whether it crossed the slow-request threshold.
- Added `X-Request-Id` correlation IDs. A safe caller-provided ID is reused; otherwise the server creates a UUID. The ID is returned in the response and added to SLF4J MDC while the request is handled.
- 4xx responses and slow requests are logged at WARN; 5xx responses and uncaught request failures at ERROR; ordinary requests at INFO. No request body, query string, authorization header, or provider credentials are logged by the filter.
- Added `GET /api/health`, which executes a lightweight database query and returns HTTP 503 when the database check fails. Existing `/api/status` continues to expose collection/provider status.
- Made Hibernate schema behavior configurable through `TRACKER_DDL_AUTO` and slow-request threshold configurable through `TRACKER_SLOW_REQUEST_MS` (default 1000 ms).
- Added health endpoint and request-ID filter tests.

## Existing provider resilience

`TrackerService` fetches YouTube, Twitch, and Kick independently and records each provider's health. A provider failure is logged and does not prevent the other providers from being included in the collection cycle. YouTube scraper watch-page rate-limit backoff remains in place. Provider API contracts can change, so 401/403/429 outcomes should be checked against live provider responses during deployment validation.

## Database change / migration procedure

The existing application uses Hibernate `ddl-auto: update` by default for local development and the current H2 file database. Before deploying a schema/index change:

1. Stop the app and make a copy of the H2 database files (or take a database-native backup for another database).
2. Test the candidate version against a copy of the production database and verify startup, history endpoints, analytics, and expected indexes.
3. For a controlled deployment, apply reviewed schema/index SQL as a separately versioned migration and set `TRACKER_DDL_AUTO=validate`; do not use `validate` until the schema is already at the required version.
4. Keep the previous application artifact and backup until smoke tests pass. Roll back by restoring the backup and previous artifact if validation fails.

This phase documents a safe migration workflow; it does not automatically convert an existing H2 database to a different database engine.

## Configuration

- `SERVER_PORT` (default `8080`)
- `TRACKER_DB_URL` (default `jdbc:h2:file:./data/valorant-tracker`)
- `TRACKER_DB_USERNAME`, `TRACKER_DB_PASSWORD`
- `TRACKER_DDL_AUTO` (`update` default for local use; `validate` for a pre-migrated deployment)
- `TRACKER_INTERVAL_MS` (default `60000`)
- `YOUTUBE_REFRESH_INTERVAL_MS` (default `300000`)
- `TRACKER_SLOW_REQUEST_MS` (default `1000`)
- `YOUTUBE_API_KEY`, `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET`, `KICK_CLIENT_ID`, `KICK_CLIENT_SECRET`

Keep secrets in environment variables or a secrets manager; do not commit real credentials to YAML or logs.

## Validation status

Added tests for health success/failure and request ID behavior. Run `./gradlew test` (Windows: `gradlew.bat test`) on a machine with Java 21 and Gradle dependency access. In this environment, the Gradle wrapper previously could not download Gradle because network DNS access was unavailable, so the Java test suite must be run locally before treating the build as verified.
