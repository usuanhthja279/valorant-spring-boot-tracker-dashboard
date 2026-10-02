# Phase 4 — Historical analytics (corrective update)

## Analytics endpoint
- `GET /api/analytics?from=<ISO-8601>&to=<ISO-8601>&limit=20000` computes metrics for the half-open interval `[from, to)`.
- Analytics fetches the newest samples first up to the configured cap, then processes those samples chronologically. A long window that exceeds the cap therefore does not silently analyze only its oldest records.
- If the cap is reached, `truncated=true`, `analyticsComplete=false`, and `growthRankingsComplete=false`. Growth rankings are returned empty because first-to-last growth cannot be reliably ranked from a partial window. Peak/average metrics are still based on the returned sample subset and the response/UI labels them partial.
- `from >= to` is rejected as HTTP 400. Missing required `from` or `to` query parameters are also HTTP 400 through Spring request-parameter validation.
- Per-platform sample count, peak and average; overall peak and average; top 10 channel viewer-change rankings when the selected window is complete.

## Range behavior
- `/api/snapshots/range`, `/api/channels/history`, and `/api/analytics` use an exclusive upper bound (`timestamp < to`). Selecting a calendar date ends at the next day's midnight without including that midnight sample.
- Snapshot range queries select the newest rows within the requested bounds and return them in chronological order.
- The dashboard's all-time chart request is bounded to the newest 5,000 snapshots. Its range status says it is showing up to the newest 5,000 snapshots rather than implying the chart contains every historical row.
- In all-time mode, the analytics panel uses the latest 30 days as its analytics window, independent of the chart's bounded all-time view.

## Tests and validation
- Added controller tests for analytics metrics, reversed analytics ranges, missing analytics range parameters, and reversed snapshot ranges (HTTP 400 expected).
- Inline dashboard JavaScript syntax check passed with Node.js.
- Full Gradle test suite passed after copying query results before reversing them.
