# Phase 12 — Isolated YouTube Worldwide / Pagination Test

## Goal

Add a separate test path for investigating the YouTube channel Live page, its region selector, and continuation-based pagination. This is isolated from the production collection flow so it can be tested without changing the tracker’s existing YouTube results.

## Changes added

- Added `YouTubeWorldwideTestService`.
  - Fetches the VALORANT channel `/live` page.
  - Attempts to parse the page's `ytInitialData`.
  - Searches the initial JSON and subsequent browse responses for video renderers.
  - Extracts continuation tokens and follows them through YouTube's internal `youtubei/v1/browse` endpoint.
  - Deduplicates videos by video ID across pages.
  - Returns page-by-page diagnostics, unique video count, whether a continuation remains, and parsed stream data.
  - Limits a request to at most 10 pages and adds a short delay between continuation requests.
  - Returns failures in the test endpoint response instead of feeding errors into the production tracker.
- Added `YouTubeWorldwideTestController`.
  - Exposes `GET /api/test/youtube/worldwide?maxPages=5`.
- Added `YOUTUBE-WORLDWIDE-TEST.md` with testing instructions and limitations.
- Updated this README with the experimental endpoint.
- Preserved the existing production services and prior phase changes in the project archive.

## How to test

Run the application as usual, then call:

`GET http://localhost:8080/api/test/youtube/worldwide?maxPages=5`

`maxPages` includes the initial HTML page and is capped at 10. Inspect the JSON fields `ok`, `pagesFetched`, `uniqueVideos`, `hasMore`, `pageDiagnostics`, `detectedWorldwideMenuHint`, and `streams`.

## Important limitation

This is a pagination investigation, **not a confirmed Worldwide implementation**. The initial `/live` HTML may contain the default “My region” results. The prototype follows continuation tokens it finds, but it does not yet prove that the token corresponds to the Worldwide selection.

To confirm Worldwide, open Chrome DevTools → Network → Fetch/XHR, select Worldwide, and inspect the request payload and response that loads the results. The request/token flow may need to be adjusted to match the observed browser request. YouTube's internal browse endpoint is undocumented and can change.

## Validation

- ZIP integrity check passed.
- Java compilation and runtime behavior have not been verified in this environment.
- The experimental endpoint should be tested locally before relying on its parsed stream counts.
