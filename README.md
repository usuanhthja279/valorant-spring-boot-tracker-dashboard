# VALORANT Spring Boot Live Tracker

Java 21 + Spring Boot + WebClient + JPA/H2.

## Run
Copy `src/main/resources/application.example.yml` to
`src/main/resources/application.yml`, then set these environment variables:

    YOUTUBE_API_KEY
    TWITCH_CLIENT_ID
    TWITCH_CLIENT_SECRET
    KICK_CLIENT_ID
    KICK_CLIENT_SECRET

Then:

    .\gradlew.bat bootRun

## Windows startup task

To start the tracker automatically at Windows startup and daily at 6:00 AM only
when port 8080 is not already listening, open PowerShell as Administrator and
run:

    .\scripts\Register-ValorantTrackerTask.ps1

The scheduled task runs as SYSTEM with highest privileges. It starts the app with
the Gradle wrapper and writes output to `tracker-startup.log` and
`tracker-startup-error.log` in the project directory. Remove it with:

    Unregister-ScheduledTask -TaskName "Valorant Live Tracker - Start If Stopped" -Confirm:$false

The tracker collects from Twitch and Kick every 60 seconds. YouTube results are
refreshed every 5 minutes by default and cached between refreshes to reduce API
quota usage. Configure `tracker.youtube.refresh-interval-ms` to change this interval.
Each platform is fetched independently; a failed platform uses its last successful
results while the other platform fetches continue.

## API
GET http://localhost:8080/api/status
POST http://localhost:8080/api/collect
GET http://localhost:8080/api/snapshots
GET http://localhost:8080/api/top10
GET http://localhost:8080/api/top10?platform=YouTube
GET http://localhost:8080/api/top10?platform=Twitch
GET http://localhost:8080/api/top10?platform=Kick
GET http://localhost:8080/api/streams
GET http://localhost:8080/api/streams?platform=Twitch
GET http://localhost:8080/api/channels/history?name=GOFNS
GET http://localhost:8080/api/streams/youtube
GET http://localhost:8080/api/streams/youtube/scraped
GET http://localhost:8080/api/streams/twitch
GET http://localhost:8080/api/streams/kick
GET http://localhost:8080/api/streams/kick/scraped

Data is stored in ./data/valorant-tracker.mv.db.

Kick's public API/authentication can change; if Kick returns 401/403, update KickService according to the current Kick developer documentation.


## Built-in web dashboard

The project now includes a browser dashboard at:

http://localhost:8080/

It shows:
- YouTube live viewers
- Twitch live viewers
- Kick live viewers
- Combined VALORANT viewers
- Combined minute-by-minute trend
- Platform-by-platform trend
- Overall Top 10
- YouTube/Twitch/Kick Top 10
- Stream titles and clickable channel URLs
- Manual "Collect now" button

No Streamlit or separate frontend server is required.
