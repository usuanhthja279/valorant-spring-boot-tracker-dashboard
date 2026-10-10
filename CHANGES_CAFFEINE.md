# Caffeine cache migration

- Replaced Spring Boot's default `simple` cache provider with Caffeine.
- Added `com.github.ben-manes.caffeine:caffeine` dependency.
- Configured caches with `maximumSize=500`, `expireAfterWrite=10m`, and `recordStats`.
- Kept tracker-driven cache invalidation asynchronous on the dedicated single-thread executor.
- Removed the synchronous `@CacheEvict` from `DynamicGameScheduler.refreshSchedule()` so the Liquipedia scheduling thread cannot block on cache clearing.
- `apiTeamLogos` is no longer synchronously evicted by the schedule refresh; it is protected by the Caffeine expiry policy instead.
- Existing `@Cacheable(..., sync=true)` annotations remain unchanged.

Note: The project was not compiled here because the uploaded source archive does not contain a Gradle wrapper and the environment does not provide a `gradle` executable.
