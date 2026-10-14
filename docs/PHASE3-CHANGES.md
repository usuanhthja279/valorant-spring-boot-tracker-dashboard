# Phase 3 — Server-side historical range loading

## What changed

- The main dashboard keeps summary cards tied to the latest snapshot while fetching chart history from `GET /api/snapshots/range` for 30-minute, 1-hour, 6-hour, 24-hour, date, and custom selections.
- Date and custom ranges are sent as ISO-8601 timestamps with offsets; chart filtering no longer depends on the latest 500 snapshots returned by `/api/snapshots`.
- Channel details now loads the latest channel records separately from the selected chart range. This keeps the current cards current when a historical date/custom range is selected.
- Channel chart range queries use the bounded `GET /api/channels/history?name=...&from=...&to=...&limit=20000` endpoint; the backend caps requested history at 20,000 rows.
- Historical chart controls trigger a range fetch rather than filtering only the initially loaded local list.

## Endpoints used

- `GET /api/snapshots` — newest snapshots for current summary values.
- `GET /api/snapshots/range?from=...&to=...&limit=5000` — chart data for the selected dashboard time range.
- `GET /api/channels/history?name=...&from=...&to=...&limit=20000` — channel history for the selected range.
- `GET /api/channels/history?name=...&limit=20000` — latest channel records used for current cards.

## Validation

- Check JavaScript syntax for the embedded scripts before deployment.
- Run `./gradlew test` with Java 21 and Gradle distribution access.
- Verify the date/custom filters with data older than the latest 500 snapshots, and verify that current channel cards do not change when browsing historical periods.

## Notes

The `/api/snapshots` endpoint intentionally remains bounded to 500 recent records for backward compatibility. The `Overall` chart selection therefore still reflects the latest returned snapshot window; date and custom ranges should be used for specific historical periods.
