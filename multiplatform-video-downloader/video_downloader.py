#!/usr/bin/env python3
"""Download videos/sections via yt-dlp, with automatic Kick VOD manifest discovery."""
from __future__ import annotations

import argparse
import concurrent.futures
import tempfile
import asyncio
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse, urlsplit, urlunsplit, parse_qsl, urlencode


PLATFORMS = {
    "youtube.com": "YouTube",
    "youtu.be": "YouTube",
    "twitch.tv": "Twitch",
    "kick.com": "Kick",
}


def platform_name(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    if host.startswith("www."):
        host = host[4:]
    for domain, name in PLATFORMS.items():
        if host == domain or host.endswith("." + domain):
            return name
    return "Other supported site"


def ensure_ytdlp() -> list[str]:
    exe = shutil.which("yt-dlp")
    if exe:
        return [exe]
    check = subprocess.run(
        [sys.executable, "-m", "yt_dlp", "--version"],
        capture_output=True, text=True, check=False,
    )
    if check.returncode == 0:
        return [sys.executable, "-m", "yt_dlp"]
    print(
        "yt-dlp is not installed. Run: python -m pip install -U 'yt-dlp[default,curl-cffi]'",
        file=sys.stderr,
    )
    raise SystemExit(2)


def parse_time(value: str) -> int:
    """Convert SS, MM:SS, or HH:MM:SS into whole seconds."""
    parts = value.strip().split(":")
    if not 1 <= len(parts) <= 3 or any(not p.isdigit() for p in parts):
        raise argparse.ArgumentTypeError(
            f"Invalid time {value!r}. Use seconds, MM:SS, or HH:MM:SS (e.g. 05:00)."
        )
    nums = [int(p) for p in parts]
    if len(parts) == 1:
        return nums[0]
    if len(parts) == 2:
        if nums[1] >= 60:
            raise argparse.ArgumentTypeError("Seconds in MM:SS must be below 60.")
        return nums[0] * 60 + nums[1]
    if nums[1] >= 60 or nums[2] >= 60:
        raise argparse.ArgumentTypeError("Minutes and seconds in HH:MM:SS must be below 60.")
    return nums[0] * 3600 + nums[1] * 60 + nums[2]


def safe_url(url: str) -> str:
    """Hide signed Kick tokens from terminal command output."""
    try:
        parts = urlsplit(url)
        query = [
            (key, "[REDACTED]" if key.lower() in {"init", "token", "sig", "signature"} else value)
            for key, value in parse_qsl(parts.query, keep_blank_values=True)
        ]
        return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))
    except Exception:
        return "<URL redacted>"


def run(command: list[str]) -> int:
    shown = [safe_url(p) if p.startswith(("http://", "https://")) else p for p in command]
    print("\n>", " ".join(f'"{p}"' if " " in p else p for p in shown))
    return subprocess.run(command, check=False).returncode


def find_vod_manifest(value):
    """Recursively find a playback_url.vod value in decoded JSON."""
    if isinstance(value, dict):
        playback = value.get("playback_url")
        if isinstance(playback, dict):
            vod = playback.get("vod")
            if isinstance(vod, str) and vod.startswith(("https://", "http://")):
                return vod
        for child in value.values():
            found = find_vod_manifest(child)
            if found:
                return found
    elif isinstance(value, list):
        for child in value:
            found = find_vod_manifest(child)
            if found:
                return found
    return None


async def _discover_manifest_async(page_url: str) -> str | None:
    try:
        from playwright.async_api import async_playwright
    except ImportError:
        return None

    found = {"url": None}

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        context = await browser.new_context(
            user_agent=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        "AppleWebKit/537.36 (KHTML, like Gecko) "
                        "Chrome/130.0.0.0 Safari/537.36"),
            viewport={"width": 1365, "height": 900},
        )
        page = await context.new_page()

        async def inspect_response(response):
            if found["url"]:
                return
            ctype = (response.headers.get("content-type") or "").lower()
            # Kick player/API responses are JSON; avoid reading media playlists.
            if "json" not in ctype and not response.url.lower().endswith(".json"):
                return
            try:
                data = await response.json()
                manifest = find_vod_manifest(data)
                if manifest:
                    found["url"] = manifest
            except Exception:
                pass

        page.on("response", lambda response: asyncio.create_task(inspect_response(response)))
        try:
            await page.goto(page_url, wait_until="domcontentloaded", timeout=45000)
            # Give the player/API time to initialize and issue its playback requests.
            for _ in range(12):
                if found["url"]:
                    break
                await page.wait_for_timeout(1500)
                # A play control can be necessary for the player to request VOD data.
                if _ == 2:
                    for selector in (
                        'button[aria-label*="Play" i]',
                        'button[title*="Play" i]',
                        '[data-testid*="play" i]',
                    ):
                        try:
                            locator = page.locator(selector).first
                            if await locator.is_visible(timeout=500):
                                await locator.click(timeout=1000)
                                break
                        except Exception:
                            continue
        except Exception as exc:
            print(f"Kick browser discovery could not load the VOD page ({type(exc).__name__}).",
                  file=sys.stderr)
        finally:
            await context.close()
            await browser.close()
    return found["url"]


def discover_kick_manifest(page_url: str) -> str | None:
    """Capture playback_url.vod from Kick's own player/API traffic."""
    print("Kick's normal extractor failed; automatically inspecting the VOD player network responses...")
    try:
        manifest = asyncio.run(_discover_manifest_async(page_url))
    except Exception as exc:
        print(f"Automatic Kick manifest discovery failed ({type(exc).__name__}).", file=sys.stderr)
        return None
    if manifest:
        print("Found a Kick VOD manifest automatically.")
        return manifest
    return None



def probe_duration(path: Path) -> float | None:
    """Return media duration using ffprobe, or None if probing fails."""
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        return None
    result = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        return None
    try:
        return float(result.stdout.strip())
    except (ValueError, TypeError):
        return None


def parallel_youtube_section(
    common: list[str],
    url: str,
    out_dir: Path,
    start: int,
    end: int,
    max_height: int,
    workers: int,
    cookies_from_browser: str | None,
) -> int:
    """Download time sections concurrently, retry failed chunks, then concatenate.

    Each retry starts a fresh yt-dlp process so it re-extracts fresh signed media URLs.
    Successful chunks are retained between retries. Stream-copy cuts may be keyframe-aligned.
    """
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("Parallel mode requires FFmpeg on PATH.", file=sys.stderr)
        return 2

    duration = end - start
    chunk_len = (duration + workers - 1) // workers
    chunks = []
    cursor = start
    while cursor < end:
        chunk_end = min(end, cursor + chunk_len)
        chunks.append((cursor, chunk_end))
        cursor = chunk_end

    temp_root = Path(tempfile.mkdtemp(prefix="ytdlp_chunks_", dir=str(out_dir.resolve())))
    chunk_paths = []
    max_attempts = 3
    try:
        def download_one(item):
            idx, chunk_start, chunk_end = item
            chunk_path = temp_root / f"chunk_{idx:02d}.mp4"
            last_code = 1

            for attempt in range(1, max_attempts + 1):
                # Remove incomplete/old candidates before retrying, but never delete
                # completed chunks belonging to other workers.
                for candidate in temp_root.glob(f"chunk_{idx:02d}.*"):
                    try:
                        candidate.unlink()
                    except OSError:
                        pass

                command = [
                    *common,
                    "--format",
                    f"bestvideo[height<={max_height}]+bestaudio/"
                    f"best[height<={max_height}]/best",
                    "--output", str(temp_root / f"chunk_{idx:02d}.%(ext)s"),
                    "--no-part",
                    "--download-sections", f"*{chunk_start}-{chunk_end}",
                    "--external-downloader", "ffmpeg",
                    "--external-downloader-args", "ffmpeg_o:-c copy",
                    "--merge-output-format", "mp4",
                    "--no-write-info-json",
                    "--no-overwrites",
                    url,
                ]
                print(
                    f"[chunk {idx}/{len(chunks)}] {chunk_start}s–{chunk_end}s "
                    f"(attempt {attempt}/{max_attempts})",
                    flush=True,
                )
                last_code = run(command)
                candidates = sorted(
                    p for p in temp_root.glob(f"chunk_{idx:02d}.*")
                    if p.suffix.lower() in {".mp4", ".mkv", ".webm"} and p.is_file()
                )
                if last_code == 0 and candidates:
                    return idx, 0, candidates[0]

                # A zero exit code without an output is also considered a failure.
                print(
                    f"[chunk {idx}] attempt {attempt} failed (exit code {last_code}).",
                    file=sys.stderr,
                    flush=True,
                )
                if attempt < max_attempts:
                    print(
                        f"[chunk {idx}] retrying with a fresh yt-dlp extraction...",
                        file=sys.stderr,
                        flush=True,
                    )

            return idx, last_code or 1, None

        jobs = [(i + 1, a, b) for i, (a, b) in enumerate(chunks)]
        results = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(download_one, job) for job in jobs]
            for future in concurrent.futures.as_completed(futures):
                results.append(future.result())

        failures = [r for r in results if r[1] != 0 or r[2] is None]
        if failures:
            print("One or more parallel chunks failed after retries:", file=sys.stderr)
            for idx, code, _ in sorted(failures):
                print(f"  chunk {idx}: final exit code {code}", file=sys.stderr)
            print(f"Successful chunks and diagnostics retained in: {temp_root}", file=sys.stderr)
            return 1

        by_index = {idx: path for idx, _, path in results}
        chunk_paths = [by_index[i] for i in range(1, len(chunks) + 1)]

        concat_file = temp_root / "concat.txt"
        concat_file.write_text(
            "".join(
                f"file '{p.resolve().as_posix().replace(chr(39), chr(39)+chr(92)+chr(39)+chr(39))}'\n"
                for p in chunk_paths
            ),
            encoding="utf-8",
        )

        parsed = urlparse(url)
        query = dict(parse_qsl(urlsplit(url).query))
        video_id = query.get("v", "")
        if not video_id and parsed.path:
            video_id = parsed.path.rstrip("/").split("/")[-1]
        video_id = re.sub(r"[^A-Za-z0-9._-]+", "_", video_id or "youtube_clip")
        final_path = out_dir.resolve() / f"{video_id} [{start}-{end}]s parallel.mp4"
        if final_path.exists():
            from datetime import datetime
            final_path = out_dir.resolve() / (
                f"{video_id} [{start}-{end}]s parallel_{datetime.now():%Y%m%d_%H%M%S}.mp4"
            )

        concat_command = [
            ffmpeg, "-hide_banner", "-y", "-f", "concat", "-safe", "0",
            "-i", str(concat_file), "-c", "copy", "-movflags", "+faststart", str(final_path),
        ]
        print("\nMerging chunks without re-encoding...")
        merge_result = subprocess.run(concat_command, check=False)
        if merge_result.returncode != 0:
            print("FFmpeg could not merge the chunks.", file=sys.stderr)
            return merge_result.returncode

        actual_duration = probe_duration(final_path)
        expected_duration = float(duration)
        if actual_duration is not None:
            print(f"Expected duration: about {expected_duration:.1f}s; merged duration: {actual_duration:.1f}s")
            if abs(actual_duration - expected_duration) > max(8.0, workers * 2.0):
                print("WARNING: merged duration differs noticeably from requested duration; inspect the clip.")
        print(f"\nParallel download complete: {final_path}")
        print("Chunks were stream-copied; boundaries may be keyframe-aligned rather than exact.")
        return 0
    finally:
        # Only remove temporary data after a successful merged output is present.
        if 'final_path' in locals() and final_path.exists() and probe_duration(final_path) is not None:
            shutil.rmtree(temp_root, ignore_errors=True)
        else:
            print(f"Temporary chunks retained for troubleshooting: {temp_root}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Download an authorized YouTube, Twitch, or Kick VOD, optionally trimming a time range."
    )
    parser.add_argument("url", help="YouTube, Twitch, Kick, or another yt-dlp-supported video URL")
    parser.add_argument("--output-dir", default="input", help="Save folder (default: ./input)")
    parser.add_argument("--max-height", type=int, default=1080, help="Maximum video height (default: 1080p)")
    parser.add_argument("--parallel-chunks", type=int, default=1, help="Download a trimmed YouTube section in this many parallel chunks (default: 1; try 3 for fewer 403 errors)")
    parser.add_argument("--list-formats", action="store_true", help="List available formats without downloading")
    parser.add_argument("--start", type=parse_time, help="Clip start: seconds, MM:SS, or HH:MM:SS")
    parser.add_argument("--end", type=parse_time, help="Clip end: seconds, MM:SS, or HH:MM:SS")
    parser.add_argument(
        "--cookies-from-browser",
        choices=["chrome", "edge", "firefox", "brave", "chromium"],
        help="Use your own browser session for content your account is authorized to access",
    )
    parser.add_argument("--no-playlist", action="store_true", help="Treat URL as a single video")
    args = parser.parse_args()

    if args.max_height < 144:
        parser.error("--max-height must be at least 144")
    if args.parallel_chunks < 1 or args.parallel_chunks > 10:
        parser.error("--parallel-chunks must be between 1 and 10")
    if (args.start is None) != (args.end is None):
        parser.error("--start and --end must be supplied together.")
    if args.start is not None and args.end <= args.start:
        parser.error("--end must be later than --start.")
    if args.start is not None and args.list_formats:
        parser.error("--list-formats cannot be combined with --start/--end.")

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    platform = platform_name(args.url)
    common = [*ensure_ytdlp(), "--no-warnings"]
    if args.no_playlist:
        common.append("--no-playlist")
    if args.cookies_from_browser:
        common.extend(["--cookies-from-browser", args.cookies_from_browser])

    print(f"Detected platform: {platform}")

    if args.list_formats:
        first_command = [*common, "--list-formats"]
        code = run([*first_command, args.url])
        if code == 0 or platform != "Kick":
            return code
        manifest = discover_kick_manifest(args.url)
        if not manifest:
            print(
                "\nCould not discover the Kick VOD manifest automatically. "
                "Install Playwright with: python -m pip install playwright; "
                "then run: python -m playwright install chromium",
                file=sys.stderr,
            )
            return code
        return run([*common, "--impersonate", "chrome", "--list-formats", manifest])

    if args.parallel_chunks > 1:
        if platform != "YouTube":
            parser.error("--parallel-chunks currently supports YouTube URLs only.")
        if args.start is None or args.end is None:
            parser.error("--parallel-chunks requires both --start and --end.")
        print(f"Parallel chunk mode: {args.parallel_chunks} workers; stream-copy cuts prioritize speed.")
        return parallel_youtube_section(
            common, args.url, out_dir, args.start, args.end, args.max_height,
            args.parallel_chunks, args.cookies_from_browser
        )

    fmt = (
        f"bestvideo[height<={args.max_height}]+bestaudio/"
        f"best[height<={args.max_height}]/best"
    )
    output_template = str(out_dir / "%(title).160B [%(id)s].%(ext)s")
    base_command = [
        *common,
        "--format", fmt,
        "--merge-output-format", "mp4",
        "--output", output_template,
        "--windows-filenames",
        "--write-info-json",
        "--no-overwrites",
    ]

    if args.start is not None:
        base_command.extend([
            "--download-sections", f"*{args.start}-{args.end}",
            "--force-keyframes-at-cuts",
        ])
        print(f"Requested clip: {args.start}s to {args.end}s ({args.end - args.start}s long)")
        print("Section downloads need FFmpeg on PATH; exact cuts may vary with source keyframes.")
    else:
        print("Requested download: full video")

    print(f"Maximum resolution: {args.max_height}p")
    print(f"Saving to: {out_dir.resolve()}")
    print("Only download videos you own or are authorized to download.")

    code = run([*base_command, args.url])
    if code == 0:
        print("\nDownload finished. Check the saved video and .info.json in:", out_dir.resolve())
        return 0

    if platform == "Kick":
        manifest = discover_kick_manifest(args.url)
        if manifest:
            # Generic HLS extraction derives the title from the manifest URL, which
            # contains a huge signed token. Override the output template with a safe,
            # stable filename based on the original Kick VOD ID.
            vod_id = (urlparse(args.url).path.rstrip("/").split("/")[-1] or "kick-vod")
            safe_output = str(out_dir / f"Kick VOD [{vod_id}].%(ext)s")
            fallback_options = base_command[len(common):].copy()
            try:
                output_index = fallback_options.index("--output")
                fallback_options[output_index + 1] = safe_output
            except (ValueError, IndexError):
                fallback_options.extend(["--output", safe_output])
            # Kick's CloudFront HLS segment URLs can end in a bare path such as `/0`
            # rather than a recognized media extension. When yt-dlp uses FFmpeg for
            # section downloads, explicitly allow those HLS segment URLs.
            code = run([
                *common,
                "--impersonate", "chrome",
                "--downloader-args", "ffmpeg_i:-allowed_extensions ALL",
                *fallback_options,
                manifest,
            ])
            if code == 0:
                print("\nKick VOD download finished. Check the saved video and .info.json in:", out_dir.resolve())
                return 0
        print(
            "\nAutomatic Kick fallback could not complete. Ensure Playwright is installed and Chromium is available:\n"
            "  python -m pip install playwright\n"
            "  python -m playwright install chromium\n"
            "If it still fails, the VOD may require an authenticated browser session or Kick may have changed its API.",
            file=sys.stderr,
        )
    else:
        print(
            "\nDownload failed. Update yt-dlp with `python -m pip install -U 'yt-dlp[default,curl-cffi]'`, "
            "check the URL and permissions, and confirm FFmpeg is installed for clips.",
            file=sys.stderr,
        )
        if platform == "Twitch":
            print("Some Twitch VODs may require your own browser session; try --cookies-from-browser chrome.")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
