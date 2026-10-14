VLR per-round fetch timestamp change

Files:
- VlrLiveRoundTrackerService.java: adds firstFetchedAt, lastFetchedAt, fetchCount, and lastUpdatedAt to each fetched round before persisted JSON merge.
- match.html: shows per-round fetch metadata in Round Timeline cards.

No database migration is needed because rounds are already persisted as JSON in roundsJson.
In-game event timestamps (such as 0:31) are not modified.

Validation:
- Source files were written and read back.
- ZIP integrity checked.
- Java compilation and browser execution were not run in this environment.
