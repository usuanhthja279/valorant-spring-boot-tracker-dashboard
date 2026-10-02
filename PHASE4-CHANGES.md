# Phase 4 — Historical analytics

## Added
- `GET /api/analytics?from=<ISO-8601>&to=<ISO-8601>&limit=20000` computes analytics from persisted stream samples in a validated time window.
- Overall peak sampled viewers and average viewers per stream sample.
- Per-platform sample count, peak viewers, and average viewers.
- Top 10 channels ordered by signed viewer change between their first and last sample in the selected window; includes first/latest viewers, change, peak, average, and timestamps.
- Dashboard analytics section uses the selected time range, and shows platform comparisons and channel growth alongside existing historical charts.
- `Overall` range uses the most recent 30 days for analytics to keep the query bounded; other ranges use selected from/to values.
- The API clamps the requested sample limit to the existing 20,000 maximum and reports `truncated` when the returned sample count reaches that cap.

## Metric interpretation / limitations
- Viewer growth is the difference between a channel's first and last stored samples in the selected window, not unique viewers or a causal growth rate.
- Average viewers is the arithmetic average across collected stream samples, so channels sampled more often contribute more samples.
- If the 20,000-row cap is reached, the response marks analytics as potentially partial. The query keeps the most recent samples within the requested window; metrics and growth periods can therefore be partial.
- Channel identity uses provider plus channel ID where available, falling back to normalized channel name.

## Validation
- The analytics query is bounded to the configured sample limit and returns most-recent samples when that limit is reached.
- Run `./gradlew test` (or `gradlew.bat test` on Windows) before deployment.
