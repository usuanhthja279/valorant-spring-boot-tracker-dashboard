Guest historical-data restrictions patch
========================================

Backend enforcement:
- GET /api/snapshots
- GET /api/snapshots/range
- GET /api/analytics
- GET /api/channels/history
- GET /api/channels/comparison

These endpoints now require an authenticated Spring Security session. Unauthenticated API requests receive HTTP 401 JSON. Public live/current endpoints remain permitted.

Frontend:
- guest-history-lock.js adds a lock/blur overlay on the history range, historical analytics, channel history/comparison panels on game.html, channel.html, and stream-comparison.html.
- Live/current dashboard areas are not intentionally locked.

Integration:
1. Back up the project.
2. Merge the files in this ZIP into the matching paths in your project.
3. Keep your application.yml and database files.
4. Run: gradlew.bat clean build
5. Restart and test both a guest session and a logged-in session.

Important:
- This patch has not been compiled against the complete project in this environment.
- Add any additional history endpoints discovered by your tests to the authenticated matcher list.
- UI blurring is only presentation; backend route protection is the actual access control.
