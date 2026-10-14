# Phase 5 — Dashboard UX and performance

## Analytics cap fix
- Historical analytics now reads the selected time window in bounded 5,000-row keyset pages ordered by timestamp and ID.
- Aggregation continues across every page, so peak/average metrics and first-to-last channel growth use the full selected window without retaining every sample in memory.
- Removed the global 20,000-row analytics cap and the misleading “Growth rankings unavailable…” message. The API reports `truncated: false`, `analyticsComplete: true`, and `growthRankingsComplete: true` when the request completes successfully.
- Uses the half-open date range `[from, to)`, consistent with the historical chart APIs.

## Dashboard UX
- Shows explicit loading states while analytics refreshes.
- Ignores stale analytics responses when the user changes ranges before a prior request completes.
- Shows a clearer error state if analytics cannot load.
- Clarifies that Overall mode charts the newest 5,000 snapshots while Overall analytics covers the latest 30 days.

## Verification
- Existing build test report in the supplied project recorded 7 tests, 0 failures, 0 skipped before these Phase 5 edits.
- A fresh Gradle test run could not start in this environment because the wrapper attempted to download Gradle 9.6.0 from `services.gradle.org`, which is unreachable here.
- JavaScript syntax, ZIP integrity, and source checks are performed for this Phase 5 archive; Java tests still need to be rerun on a machine with Gradle available.
