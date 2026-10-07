TrackerService cache/fallback fix

Cache scope:
- lastSuccessfulData is keyed by GAME + PLATFORM.
- consecutiveFailures is keyed by GAME + PLATFORM.
- A failed provider for Game B can never reuse Game A's cached response.

Run-based fallback:
- Run 1 success: cache the successful result.
- Run 2 failure/no data: reuse Run 1.
- Run 3 failure/no data: reuse Run 1.
- Run 4 failure/no data: return no streams and retain the failure reason in game+platform provider health.
- Any later success resets the failure counter and replaces the cache.

The previous 3-minute stale-data grace period and 3-minute 429 cooldown were removed. 429/errors/empty responses are handled as failed collection runs, so the policy is based on collection runs rather than elapsed time.

Provider health:
- Existing platform-level providerHealth is retained for backward compatibility.
- gameProviderHealth is added and keyed by GAME + PLATFORM.
- getProviderHealth(game) returns the exact YouTube/Twitch/Kick status for that game.
