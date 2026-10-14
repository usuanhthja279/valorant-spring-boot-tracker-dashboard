# Selenium YouTube Worldwide discovery — isolated proof of concept

This feature is deliberately isolated from `TrackerService` and does not write to the production stream feed. No scheduled job is enabled yet.

## Endpoint

Start the existing Spring Boot application and call:

```text
GET http://localhost:8080/api/test/youtube/selenium/worldwide?maxStreams=50&validateWithApi=true
```

To use the exact VALORANT game-channel Live page shown in your browser, pass its full YouTube URL:

```text
GET http://localhost:8080/api/test/youtube/selenium/worldwide?maxStreams=50&validateWithApi=true&pageUrl=https%3A%2F%2Fwww.youtube.com%2Fchannel%2FYOUR_GAME_CHANNEL_ID%2Flive
```

Prefer setting `YOUTUBE_SELENIUM_LIVE_URL` in the environment to avoid URL-encoding the page URL on every request. The default URL is a live-filtered VALORANT search page; pass/configure the actual game-channel `/live` URL to test its Worldwide menu.

## Requirements (Windows/local PC)

- Java 21
- Google Chrome installed
- Network access to YouTube
- Selenium Manager downloads/resolves a compatible ChromeDriver automatically on first run (internet access required).
- YouTube Data API key configured as `tracker.youtube.api-key` if `validateWithApi=true`.

Example PowerShell environment variable before starting Spring Boot:

```powershell
$env:YOUTUBE_SELENIUM_LIVE_URL = "https://www.youtube.com/channel/YOUR_GAME_CHANNEL_ID/live"
.\gradlew.bat bootRun
```

If you use an IDE, set `YOUTUBE_SELENIUM_LIVE_URL` in the run configuration instead. You can temporarily set `YOUTUBE_SELENIUM_HEADLESS=false` to inspect Chrome during troubleshooting.

## Response / diagnostics

Returns `streams` containing video ID, channel ID when API validation supplies it, channel title, video title, viewer count and watch URL. Diagnostics include page URL, whether a Worldwide selector was clicked, cards seen, unique IDs, scroll count, API validation status, elapsed time and warnings.

- Without API validation, results are discovery candidates only; a video card may be stale or not currently live.
- With API validation, the Data API is used to retain items reported as `live`; concurrent viewer count may be unavailable. If API validation errors, the response includes a warning and falls back to discovered candidates.
- The selector interaction is best-effort and is explicitly reported as `worldwideSelectorClicked`; `false` means the result is not confirmed Worldwide.
- YouTube may change its DOM, require consent, rate-limit requests, or omit viewer counts.
- At most 50 results are collected per request.
- No ten-minute scheduler is enabled. Enable scheduling only after several manual runs show correct Worldwide selection, consistent IDs, live validation, and useful coverage.

## Build status

The change is source-level only in this environment. Compile/run it locally to confirm dependency resolution and Chrome/Selenium availability before relying on it.
