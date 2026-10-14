# VALORANT Multi-Platform VOD Downloader

One Python CLI for YouTube, Twitch, and Kick, powered by `yt-dlp`. Download a full VOD or a selected time range, up to a chosen maximum resolution, and save a metadata `.info.json` file beside the video.

Use only videos you own or are authorized to download. This tool does not bypass DRM, private-video restrictions, paywalls, or other access controls.

## 1. Setup (Windows PowerShell)

Open PowerShell in this folder:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

Install FFmpeg separately and confirm `ffmpeg -version` works. FFmpeg is needed for selected-section downloads and merging separate audio/video streams.

## 2. Download part of a YouTube VOD

```powershell
python downloader.py "https://www.youtube.com/watch?v=VIDEO_ID" --start 05:00 --end 10:00
```

## 3. Download part of a Twitch VOD

Use the Twitch VOD URL, for example:

```powershell
python downloader.py "https://www.twitch.tv/videos/VIDEO_ID" --start 05:00 --end 10:00
```

List available qualities before downloading:

```powershell
python downloader.py "https://www.twitch.tv/videos/VIDEO_ID" --list-formats
```

If a VOD is accessible only when signed in, you may use your own browser session:

```powershell
python downloader.py "https://www.twitch.tv/videos/VIDEO_ID" --cookies-from-browser chrome --start 05:00 --end 10:00
```

## 4. Download part of a Kick VOD

Paste the exact VOD URL copied from Kick:

```powershell
python downloader.py "https://kick.com/CHANNEL/video/VIDEO_ID" --start 05:00 --end 10:00
```

Kick URL patterns can vary, so use the actual URL from the VOD page. Check available formats first:

```powershell
python downloader.py "PASTE_KICK_VOD_URL_HERE" --list-formats
```

## Time formats

Start and end times accept seconds, `MM:SS`, or `HH:MM:SS`:

```powershell
python downloader.py "VIDEO_URL" --start 300 --end 600
python downloader.py "VIDEO_URL" --start 01:12:00 --end 01:12:30
```

`--start` and `--end` must be supplied together. The end time must be later than the start time.

## Quality and output folder

Limit to 720p:

```powershell
python downloader.py "VIDEO_URL" --start 05:00 --end 10:00 --max-height 720
```

Save to a separate folder:

```powershell
python downloader.py "VIDEO_URL" --start 05:00 --end 10:00 --output-dir "input\vod-001"
```

By default, videos and metadata JSON files are saved in `input/`. The file name includes the video title and platform ID. Actual quality depends on the formats the platform makes available.

## Troubleshooting

Update yt-dlp first:

```powershell
python -m pip install -U yt-dlp
```

If a clip download fails, verify `ffmpeg -version`. Platform support changes over time; some VODs may be deleted, expired, private, region-limited, or require account access. Never export or share browser cookies.
