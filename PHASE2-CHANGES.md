# Phase 2 changes — history and query performance

## Included

- Added database indexes for snapshot timestamps and stream sample lookups by timestamp, channel, platform, and stream ID.
- Added `GET /api/snapshots/range?from=<ISO_OFFSET_DATE_TIME>&to=<ISO_OFFSET_DATE_TIME>&limit=5000` for server-side bounded time-range reads.
- Extended `GET /api/channels/history` with optional `from`, `to`, and `limit` parameters. Existing `?name=` calls remain compatible.
- Added input validation for reversed/missing time ranges and a hard cap of 20,000 rows per history request to avoid accidental unbounded responses.
- Preserved the existing `/api/snapshots` endpoint and dashboard response shape for backward compatibility.

## Example requests

```text
GET /api/snapshots/range?from=2026-10-02T09:00:00%2B05:30&to=2026-10-02T10:00:00%2B05:30&limit=1000
GET /api/channels/history?name=rickstanely&from=2026-10-02T09:00:00%2B05:30&to=2026-10-02T10:00:00%2B05:30&limit=1000
GET /api/channels/history?name=rickstanely&limit=5000
```

Use URL-encoded `+` (`%2B`) in offset timestamps, or send UTC timestamps ending in `Z`.

## Notes

- Existing range controls and charts are retained. The new range endpoints let the UI request only the selected time window instead of downloading all historical rows.
- Schema updates are applied by Hibernate because `spring.jpa.hibernate.ddl-auto=update` is configured.
- No historical records are automatically deleted.
- Run `./gradlew test` and verify the endpoints against the local H2 database before deploying. A full Gradle build was not confirmed in this environment.
