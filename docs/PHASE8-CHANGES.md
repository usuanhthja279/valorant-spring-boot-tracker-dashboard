# Phase 8 — dashboard accuracy and time-range improvements

## Changes
- Restored the translucent area shading beneath the combined viewer trend. The combined line remains white; fill is intentional.
- Added a draggable two-handle time-range control (start/end hours ago) for selecting a rolling window within the last 24 hours, with a minimum one-hour interval. Dragging updates the dashboard range and refreshes the charts and range-based analytics.
- Clarified historical analytics labels: peak/average combined viewers, individual stream samples, and analysis window. Added a note explaining snapshot totals versus per-channel stream samples.
- Added `platform` filtering to `/api/channels/history`, and the channel page now sends the selected platform to prevent cross-platform records with identical names from being mixed.
- On a channel marked offline, current cards no longer present stored historical values as current; they show dashes and identify sample timestamps as last recorded. Historical charts remain available.
- The channel's Open channel link now chooses a URL from the selected platform rather than whichever platform happened to have the latest record.
- Preserved chart gaps where values are unavailable instead of interpolating across them.

## Validation
- Static HTML/JavaScript syntax checks are run when Node.js is available.
- Full Gradle compilation/test status is reported separately; it depends on the Gradle distribution being available in the environment.

## Phase 8 follow-up — complete discussed UI/data fixes
- Preserved the shaded/fill areas on combined and peak sparklines.
- The draggable start/end hour sliders are hidden for preset ranges and appear only after selecting **Custom**.
- Historical analytics now explicitly explains the difference between snapshot-based combined viewer metrics and individual stream sample counts.
- Channel current cards only show a count when that platform is currently live; stale historical counts remain in the history chart only.
- “Open channel” is resolved from the selected platform rather than whichever platform has the newest sample, preventing cross-platform links from opening the wrong site.
- Existing Phase 5–8 changes in this ZIP are retained, including faster dashboard rendering, reduced current-history payload, indexed channel history query, search/status behavior, selected historical range stability, and range-selection reset behavior.
