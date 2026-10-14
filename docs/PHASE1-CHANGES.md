# Phase 1 reliability changes

## Changes
- Provider health is tracked independently for YouTube, Twitch, and Kick.
- `GET /api/status` now includes each provider's status, last successful collection time, last error, stream count, and viewer count.
- Each snapshot stores provider availability. The dashboard displays `Unavailable` for a provider that failed and leaves a gap in its trend line rather than plotting a false zero.
- A failed provider no longer contributes its previous cached stream list to a new snapshot.
- Collection deduplicates streams per provider by stream ID, then channel ID, then normalized channel name.
- Collection timestamps retain seconds so multiple collection runs in one minute do not share a timestamp.
- Missing Twitch credentials now cause an explicit provider failure instead of an apparently successful empty result.
- Runtime configuration reads credentials from environment variables. No credentials are included in the sample configuration.

## Environment variables
- `YOUTUBE_API_KEY`
- `TWITCH_CLIENT_ID`
- `TWITCH_CLIENT_SECRET`
- `KICK_CLIENT_ID`
- `KICK_CLIENT_SECRET`
- Optional: `TRACKER_DB_URL`, `TRACKER_DB_USERNAME`, `TRACKER_DB_PASSWORD`, `SERVER_PORT`, `TRACKER_INTERVAL_MS`, `YOUTUBE_REFRESH_INTERVAL_MS`

## Notes
- Existing snapshots have a null provider-availability field; the dashboard treats these historical records as unknown, not failed.
- A successful empty provider response is represented as `UP` with zero streams/viewers. A failed request is represented as `DOWN`.
- Gradle tests could not be executed in this environment because the wrapper distribution could not be downloaded (network DNS unavailable). Run `./gradlew test` locally before deployment.
