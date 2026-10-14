# Phase 7 — Dashboard redesign and search autocomplete

## Dashboard changes

- Preserved the live viewer feed cards for YouTube, Twitch, Kick, and combined totals.
- Added four selected-range peak cards. Platform peaks use platform-total snapshot fields; combined peak uses the combined total at a single snapshot timestamp, not the sum of peaks from different times.
- Added matching YouTube, Twitch, Kick, and combined SVG icons to the live viewer cards, reusing the peak-card platform marks.
- Added compact historical analytics metrics: peak combined viewers, average combined viewers, snapshot/sample count, and selected-range label.
- Added platform comparison rows with platform-total peak/average based on snapshots, sample counts from the stream analytics endpoint, and trend sparklines; includes a combined row.
- Added a top channels by peak viewers table. The backend now returns `topChannelsByPeak` independently of growth rankings.
- Added a selected-range channel viewer history chart and peak/average/sample/first-to-last metrics. Select a channel in the ranking table to load it.
- Chart titles, peak cards, analytics labels, and channel history use the active selected range.
- Responsive dark layout with compact cards, sparklines, readable tables, and mobile breakpoints.

## Search autocomplete

- Dropdown remains hidden until the user types.
- Matches known current channels by name, platform, and available channel identifier, with exact/prefix matches prioritized.
- Displays channel, platform, latest sampled viewers, highlighted matching text, and no-match state.
- Supports mouse and keyboard navigation (Arrow Up/Down, Enter, Escape), outside-click dismissal, light input debounce, and refresh of suggestions as stream data updates.
- Suggestions use tracker data already loaded by `/api/streams?platform=Overall`; no network request per keystroke.

## Data definitions

- Peak cards show the highest observed platform-total/combined snapshot in the selected time window and the timestamp it occurred.
- The combined peak is selected from a single timestamp's combined total. It is not computed by summing platform-specific peaks that may occur at different times.
- Platform table peak and average use platform total snapshot values. Sample counts continue to represent individual stream samples, so these counts need not equal the number of snapshots.
- Top-channel ranking uses each channel's maximum stream-sample viewer count in the selected range.

## Validation

- Inline JavaScript syntax check with Node.js.
- ZIP integrity check.
- Java compilation and Gradle tests still require a local Gradle distribution/network access; not claimed as passed here.

## UI correction: peak chart sizing and platform marks
- Constrained each peak-card sparkline to a dedicated 32px-high wrapper so Chart.js cannot stretch it to the full card height.
- Added local inline SVG platform marks for YouTube, Twitch, and Kick in analytics rows and peak cards; no remote icon request is required.
- Kept the existing dashboard charts, selected-range calculations, and search autocomplete behavior.
- Validation: inline JavaScript syntax check passed. Browser/runtime rendering and Gradle tests were not run in this environment.


## Channel presence in search and channel detail (follow-up)
- Added `/api/channels/catalog`, which groups previously sampled channel identities and labels a channel LIVE only when it appears in the latest collection timestamp. Historical channels remain searchable as OFFLINE.
- Search suggestions show a clear LIVE/OFFLINE badge; viewer counts are shown only for live channels, while offline entries show the last-seen date.
- Selecting a suggestion carries both channel name and platform into the channel page to disambiguate identical names across platforms.
- Channel analytics page displays a LIVE NOW or OFFLINE · LAST SEEN badge and refreshes it every 60 seconds.
- If catalog loading fails, search falls back to known live streams and the channel page reports status unavailable rather than guessing.
