# Phase 9 — Clearer Historical Analytics

- Added a **Peak individual streams** table to Historical Analytics.
- Each row shows rank, channel name, stream platform, peak viewers, and the time that channel reached its peak within the selected range.
- Channel names are clickable and open the existing channel-history panel for that channel/platform.
- Added `peakTimestamp` to the analytics API's per-channel aggregation; ties retain the first timestamp at which the peak was reached.
- Renamed the confusing “Individual stream samples” metric to “Data observations” and clarified that observations are channel records, not unique viewers.
- Preserved prior Phase 1–8 work, including shaded trend charts, dashboard/platform icons, custom draggable range controls, offline/current-state fixes, selected-range history behavior, and loading/query optimizations.

## Validation
- Inline JavaScript syntax check passed with Node.js.
- ZIP integrity check passed.
- Gradle/Java compilation has not been verified in this environment; the prior Gradle wrapper could not download its distribution because network DNS access was unavailable.


## Phase 9 follow-up: live cards
- Added embedded SVG platform icons beside YouTube, Twitch, and Kick on the live viewer summary cards, plus a combined-viewers icon.
- Added compact, shaded mini trend lines to each live viewer card using the same selected-range snapshot series and visual treatment as peak-viewership sparklines.
- Existing peak cards, trend shading, analytics and history behavior retained.

## Historical analytics consistency and single top stream
- Historical analytics now shows only the single highest-peaking individual stream.
- Peak combined viewers and its timestamp are calculated from the same snapshot series used by the Combined platform row.
- Average combined viewers uses the same snapshot series for both the summary metric and Combined platform row.
- Updated explanatory copy to clarify that data observations are per-stream records, not unique viewers.
