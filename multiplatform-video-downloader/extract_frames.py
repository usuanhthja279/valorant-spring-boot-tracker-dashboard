#!/usr/bin/env python3
"""Extract sampled frames and create a contact sheet for VOD inspection."""
from __future__ import annotations

import argparse
import concurrent.futures
import threading
import time
import json
import math
import shutil
import subprocess
import sys
from pathlib import Path

import cv2


def run_capture(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        details = (result.stderr or result.stdout or "Unknown command error").strip()
        raise RuntimeError(details)
    return result.stdout.strip()


def probe_duration(video: Path) -> float:
    output = run_capture([
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(video),
    ])
    try:
        return float(output)
    except ValueError as exc:
        raise RuntimeError(f"Could not parse video duration from ffprobe output: {output!r}") from exc


def fmt_time(seconds: float) -> str:
    total_ms = max(0, int(round(seconds * 1000)))
    hours, rem = divmod(total_ms, 3_600_000)
    minutes, rem = divmod(rem, 60_000)
    secs, ms = divmod(rem, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}.{ms:03d}"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Sample a local video in parallel and automatically create segmented contact sheets."
    )
    parser.add_argument("--input", required=True, help="Path to local video file")
    parser.add_argument("--start", type=float, default=0, help="Start offset in seconds (default: 0)")
    parser.add_argument("--duration", type=float, default=None, help="Segment duration in seconds (default: from --start to end of video)")
    parser.add_argument("--interval", type=float, default=1, help="Seconds between samples (default: 1)")
    parser.add_argument("--chunk-duration", type=float, default=300, help="Chunk length in seconds (default: 300)")
    parser.add_argument("--workers", type=int, default=2, help="Parallel chunk workers (default: 2; try 2 before 3)")
    parser.add_argument("--progress-every", type=int, default=100, help="Log progress every N completed samples (default: 100)")
    parser.add_argument("--output", default="output/frame-scan", help="Output directory")
    parser.add_argument("--width", type=int, default=960, help="Saved frame width; 0 keeps original resolution")
    parser.add_argument("--sheet-columns", type=int, default=10, help="Contact sheet columns (default: 10)")
    parser.add_argument("--sheet-cell-width", type=int, default=240, help="Contact sheet thumbnail width (default: 240)")
    parser.add_argument("--sheet-segment-seconds", type=float, default=300, help="Create a separate contact sheet for each N seconds of video (default: 300)")
    parser.add_argument("--jpeg-quality", type=int, default=92, help="Saved frame JPEG quality, 1-100")
    args = parser.parse_args()

    video = Path(args.input)
    if not video.is_file():
        parser.error(f"Input video does not exist: {video}")
    if (args.start < 0 or (args.duration is not None and args.duration <= 0)
            or args.interval <= 0 or args.chunk_duration <= 0
            or args.workers < 1 or args.progress_every < 1 or args.sheet_segment_seconds <= 0):
        parser.error("--start must be >= 0; duration, interval, chunk-duration, workers, and progress-every must be > 0")
    if args.width < 0 or args.sheet_columns < 1 or args.sheet_cell_width < 100:
        parser.error("--width must be >= 0, --sheet-columns >= 1, and --sheet-cell-width >= 100")
    if not 1 <= args.jpeg_quality <= 100:
        parser.error("--jpeg-quality must be between 1 and 100")

    for tool in ("ffprobe", "ffmpeg"):
        if shutil.which(tool) is None:
            parser.error(f"{tool} was not found on PATH. Install FFmpeg and open a new PowerShell window.")

    try:
        full_duration = probe_duration(video)
    except (RuntimeError, OSError) as exc:
        print(f"Could not inspect input video: {exc}", file=sys.stderr)
        return 2

    if args.start >= full_duration:
        parser.error(f"--start ({args.start}s) is beyond video duration ({full_duration:.3f}s)")
    end = min(args.start + args.duration, full_duration) if args.duration is not None else full_duration
    actual_duration = end - args.start

    out_dir = Path(args.output)
    frames_dir = out_dir / "frames"
    frames_dir.mkdir(parents=True, exist_ok=True)

    # Probe source frame rate for manifest metadata.
    fps = None
    try:
        fps_text = run_capture([
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=r_frame_rate",
            "-of", "default=noprint_wrappers=1:nokey=1", str(video),
        ])
        if "/" in fps_text:
            num, den = fps_text.split("/", 1)
            fps = float(num) / float(den) if float(den) else None
        else:
            fps = float(fps_text)
        if fps is not None and (not math.isfinite(fps) or fps <= 0):
            fps = None
    except (RuntimeError, ValueError, ZeroDivisionError):
        fps = None

    records: list[dict] = []
    sheet_items: list[tuple[Path, float]] = []
    sample_count = int(math.ceil(actual_duration / args.interval - 1e-12))
    jpeg_params = [int(cv2.IMWRITE_JPEG_QUALITY), args.jpeg_quality]

    print(f"Input: {video.resolve()}")
    print(f"Video duration: {full_duration:.3f}s")
    print(f"Sampling from {args.start:.3f}s to {end:.3f}s every {args.interval:g}s")
    print(f"Expected samples: {sample_count}")
    print(f"Chunk duration: {args.chunk_duration:g}s | workers: {args.workers}")
    print(f"Output frames: {frames_dir.resolve()}")
    print(f"Progress logs every {args.progress_every} completed samples.")
    started_at = time.monotonic()

    # Use separate chunk directories to prevent FFmpeg output collisions.
    chunks = []
    cursor = args.start
    chunk_id = 0
    while cursor < end - 1e-9:
        chunk_end = min(end, cursor + args.chunk_duration)
        chunks.append((chunk_id, cursor, chunk_end))
        cursor = chunk_end
        chunk_id += 1

    progress_lock = threading.Lock()
    completed_samples = 0
    last_progress_bucket = 0
    chunk_results = {}

    def extract_chunk(chunk):
        idx, chunk_start, chunk_end = chunk
        chunk_dir = out_dir / f"_chunks" / f"chunk_{idx:04d}"
        chunk_dir.mkdir(parents=True, exist_ok=True)
        count = int(math.ceil((chunk_end - chunk_start) / args.interval))
        pattern = chunk_dir / "sample_%06d.jpg"
        vf_parts = [f"fps=1/{args.interval:g}"]
        if args.width:
            vf_parts.append(f"scale='min({args.width},iw)':-2")
        vf = ",".join(vf_parts)
        qscale = max(2, min(31, round((100 - args.jpeg_quality) * 0.29 + 2)))
        command = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", f"{chunk_start:.6f}", "-i", str(video),
            "-t", f"{(chunk_end - chunk_start):.6f}",
            "-map", "0:v:0", "-an", "-sn", "-dn",
            "-vf", vf, "-q:v", str(qscale),
            "-frames:v", str(count), str(pattern),
        ]
        chunk_started = time.monotonic()
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        elapsed = time.monotonic() - chunk_started
        if result.returncode != 0:
            return idx, chunk_start, chunk_end, [], elapsed, (result.stderr or result.stdout).strip()

        files = sorted(chunk_dir.glob("sample_*.jpg"))
        # Compute timestamp per output position; each chunk starts on the global sampling grid.
        positions = []
        first_index = int(round((chunk_start - args.start) / args.interval))
        for local_i, path in enumerate(files):
            global_i = first_index + local_i
            ts = args.start + global_i * args.interval
            if ts < chunk_end and ts < end:
                positions.append((global_i, ts, path))
        return idx, chunk_start, chunk_end, positions, elapsed, None

    print(f"Starting {len(chunks)} chunks...", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(extract_chunk, chunk): chunk for chunk in chunks}
        pending = set(futures)
        reported_count = 0
        while pending:
            done, pending = concurrent.futures.wait(
                pending, timeout=1.0, return_when=concurrent.futures.FIRST_COMPLETED
            )
            # Count frames already written by FFmpeg in all active chunk folders.
            # This gives useful progress while the chunks are still running.
            on_disk = len(list((out_dir / "_chunks").glob("chunk_*/sample_*.jpg")))
            completed_estimate = min(sample_count, on_disk)
            elapsed = time.monotonic() - started_at
            rate = completed_estimate / elapsed if elapsed > 0 else 0
            remaining = max(0, sample_count - completed_estimate)
            eta = remaining / rate if rate > 0 else 0
            bucket = completed_estimate // args.progress_every
            if bucket > last_progress_bucket or (not pending and completed_estimate > reported_count):
                last_progress_bucket = bucket
                reported_count = completed_estimate
                percent = completed_estimate * 100 / max(1, sample_count)
                print(
                    f"Progress: {completed_estimate}/{sample_count} samples "
                    f"({percent:.1f}%) | elapsed {elapsed:.1f}s | ETA {eta:.1f}s",
                    flush=True,
                )
            for future in done:
                idx, chunk_start, chunk_end, positions, chunk_elapsed, error = future.result()
                if error:
                    print(f"[chunk {idx + 1}/{len(chunks)}] FAILED ({chunk_start:.0f}-{chunk_end:.0f}s): {error}", file=sys.stderr, flush=True)
                    for other in pending:
                        other.cancel()
                    return 2
                chunk_results[idx] = positions
                print(
                    f"[chunk {idx + 1}/{len(chunks)}] complete: {len(positions)} samples "
                    f"from {chunk_start:.0f}-{chunk_end:.0f}s in {chunk_elapsed:.1f}s",
                    flush=True,
                )

    ordered_positions = sorted(
        (entry for positions in chunk_results.values() for entry in positions),
        key=lambda entry: entry[0]
    )
    for global_i, target_seconds, temp_path in ordered_positions:
        filename = f"frame_{global_i:06d}_{int(round(target_seconds * 1000)):010d}ms.jpg"
        frame_path = frames_dir / filename
        temp_path.replace(frame_path)
        frame = cv2.imread(str(frame_path))
        if frame is None:
            print(f"Warning: could not read extracted frame {frame_path.name}; skipping.")
            continue
        h, w = frame.shape[:2]
        records.append({
            "index": global_i,
            "file": str(Path("frames") / filename),
            "requestedTimestampSeconds": round(target_seconds, 3),
            "requestedTimestamp": fmt_time(target_seconds),
            "decodedTimestampSeconds": round(target_seconds, 3),
            "decodedTimestamp": fmt_time(target_seconds),
            "originalWidth": w if not args.width else 0,
            "originalHeight": h if not args.width else 0,
            "savedWidth": int(w),
            "savedHeight": int(h),
        })
        sheet_items.append((frame_path, target_seconds))

    shutil.rmtree(out_dir / "_chunks", ignore_errors=True)

    manifest = {
        "inputFile": str(video.resolve()),
        "inputDurationSeconds": round(full_duration, 3),
        "inputFps": fps,
        "requestedStartSeconds": args.start,
        "requestedDurationSeconds": round(actual_duration, 3),
        "actualEndSeconds": round(end, 3),
        "intervalSeconds": args.interval,
        "chunkDurationSeconds": args.chunk_duration,
        "parallelWorkers": args.workers,
        "timestampNote": (
            "Frames are extracted in parallel FFmpeg chunks. Timestamps are requested sample "
            "positions and may differ slightly from exact frame PTS."
        ),
        "frames": records,
    }
    manifest_path = out_dir / "frame_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    if not sheet_items:
        print("No frames were extracted. Check the input video and seek range.", file=sys.stderr)
        return 2

    # Create one readable contact sheet per time segment (default: five minutes).
    # This runs automatically after extraction and manifest generation.
    cell_w = args.sheet_cell_width
    label_h = 30
    sheet_dir = out_dir / "contact_sheets"
    sheet_dir.mkdir(parents=True, exist_ok=True)
    grouped: dict[int, list[tuple[Path, float]]] = {}
    for frame_path, seconds in sheet_items:
        segment_index = int(max(0, seconds - args.start) // args.sheet_segment_seconds)
        grouped.setdefault(segment_index, []).append((frame_path, seconds))

    sheet_paths: list[Path] = []
    np = __import__("numpy")
    for segment_index in sorted(grouped):
        items = grouped[segment_index]
        thumbs = []
        for path, seconds in items:
            image = cv2.imread(str(path))
            if image is None:
                continue
            h, w = image.shape[:2]
            cell_h_img = max(1, round(h * cell_w / w))
            thumb = cv2.resize(image, (cell_w, cell_h_img), interpolation=cv2.INTER_AREA)
            cell = cv2.copyMakeBorder(
                thumb, 0, label_h, 0, 0, cv2.BORDER_CONSTANT, value=(24, 24, 24)
            )
            cv2.putText(
                cell, fmt_time(seconds), (8, cell.shape[0] - 8),
                cv2.FONT_HERSHEY_SIMPLEX, 0.65, (255, 255, 255), 1, cv2.LINE_AA
            )
            thumbs.append(cell)

        if not thumbs:
            continue
        cols = args.sheet_columns
        rows = math.ceil(len(thumbs) / cols)
        cell_h = max(img.shape[0] for img in thumbs)
        canvas = np.full((rows * cell_h, cols * cell_w, 3), 24, dtype="uint8")
        for i, thumb in enumerate(thumbs):
            y = (i // cols) * cell_h
            x = (i % cols) * cell_w
            canvas[y:y + thumb.shape[0], x:x + thumb.shape[1]] = thumb

        segment_start = args.start + segment_index * args.sheet_segment_seconds
        segment_end = min(end, segment_start + args.sheet_segment_seconds)
        sheet_path = sheet_dir / f"contact_sheet_{int(segment_start):06d}-{int(segment_end):06d}s.jpg"
        if cv2.imwrite(str(sheet_path), canvas, jpeg_params):
            sheet_paths.append(sheet_path)
            print(f"Contact sheet: {sheet_path.resolve()} ({len(thumbs)} frames, {fmt_time(segment_start)}–{fmt_time(segment_end)})")
        else:
            print(f"Warning: failed to write contact sheet: {sheet_path}", file=sys.stderr)

    total_elapsed = time.monotonic() - started_at
    print("\nExtraction complete.")
    print(f"Frames extracted: {len(records)}/{sample_count}")
    print(f"Total elapsed: {total_elapsed:.1f}s ({total_elapsed / 60:.2f} min)")
    print(f"Manifest: {manifest_path.resolve()}")
    print(f"Contact sheets created: {len(sheet_paths)} in {sheet_dir.resolve()}")
    print("The full-resolution source video remains unchanged.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
