#!/usr/bin/env python3
"""
Detect candidate visual scene changes in already-extracted video frames.

This is a heuristic detector, not semantic AI: it flags visual changes that may
be cuts, replays, camera changes, HUD transitions, or other scene changes.
It does not identify VALORANT round starts/wins by itself.
"""
from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path

import cv2
import numpy as np

FRAME_RE = re.compile(r"frame_(\d+)_(\d+)ms\.(?:jpg|jpeg|png)$", re.IGNORECASE)


def parse_args():
    p = argparse.ArgumentParser(description="Detect candidate scene changes from extracted frames.")
    p.add_argument("--frames", default=r".\output\youtube-30-60-parallel\frames",
                   help="Folder containing extracted frames")
    p.add_argument("--output", default=r".\output\youtube-30-60-parallel\scene_detection",
                   help="Output folder for CSV and transition sheets")
    p.add_argument("--min-gap", type=float, default=2.0,
                   help="Minimum seconds between reported candidates")
    p.add_argument("--threshold", type=float, default=0.38,
                   help="Combined change-score threshold (0-1); lower finds more candidates")
    p.add_argument("--review-radius", type=float, default=2.0,
                   help="Seconds before/after each candidate shown in review sheets")
    p.add_argument("--max-review-images", type=int, default=240,
                   help="Legacy compatibility option; all unique review images are now preserved")
    p.add_argument("--thumb-width", type=int, default=480)
    p.add_argument("--thumb-height", type=int, default=270)
    p.add_argument("--columns", type=int, default=3)
    p.add_argument("--batch-size", type=int, default=24)
    return p.parse_args()


def read_frames(folder: Path):
    items = []
    for path in folder.iterdir():
        m = FRAME_RE.match(path.name)
        if m and path.is_file():
            items.append((int(m.group(2)), int(m.group(1)), path))
    items.sort(key=lambda x: x[0])
    return items


def feature(path: Path):
    image = cv2.imread(str(path))
    if image is None:
        return None
    small = cv2.resize(image, (320, 180), interpolation=cv2.INTER_AREA)
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], None, [24, 16], [0, 180, 0, 256])
    cv2.normalize(hist, hist, alpha=1, norm_type=cv2.NORM_L1)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    # Light blur suppresses tiny compression/noise changes before pixel comparison.
    gray = cv2.GaussianBlur(gray, (5, 5), 0)
    return image, hist, gray


def main():
    args = parse_args()
    frame_dir = Path(args.frames).resolve()
    out_dir = Path(args.output).resolve()
    if not frame_dir.is_dir():
        raise SystemExit(f"Frames folder not found: {frame_dir}")

    frames = read_frames(frame_dir)
    if len(frames) < 2:
        raise SystemExit(f"Need at least 2 recognized frames in {frame_dir}")

    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"Found {len(frames)} frames. Computing visual-change scores...")

    previous = None
    scored = []
    for i, (ts_ms, index, path) in enumerate(frames):
        current = feature(path)
        if current is None:
            print(f"WARNING: unreadable image: {path.name}")
            continue
        image, hist, gray = current
        if previous is None:
            hist_score = pixel_score = 0.0
        else:
            hist_score = float(cv2.compareHist(previous[1], hist, cv2.HISTCMP_BHATTACHARYYA))
            # Normalize mean absolute grayscale difference to 0..1.
            pixel_score = float(np.mean(cv2.absdiff(previous[2], gray)) / 255.0)
        # Histogram is more robust to small movement; pixel diff catches layout changes.
        combined = min(1.0, 0.65 * hist_score + 0.35 * pixel_score)
        scored.append({
            "timestamp_ms": ts_ms, "timestamp_seconds": ts_ms / 1000.0,
            "frame_index": index, "filename": path.name,
            "histogram_change": hist_score, "pixel_change": pixel_score,
            "combined_score": combined,
        })
        previous = current
        if (i + 1) % 100 == 0 or i + 1 == len(frames):
            print(f"Processed {i + 1}/{len(frames)} frames")

    # Select high-scoring candidates, then suppress nearby detections.
    candidates = [r for r in scored if r["combined_score"] >= args.threshold]
    candidates.sort(key=lambda r: r["combined_score"], reverse=True)
    selected = []
    for candidate in candidates:
        if all(abs(candidate["timestamp_seconds"] - s["timestamp_seconds"]) >= args.min_gap
               for s in selected):
            selected.append(candidate)
    selected.sort(key=lambda r: r["timestamp_seconds"])

    csv_path = out_dir / "scene_changes.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "timestamp_seconds", "frame_index", "filename",
            "histogram_change", "pixel_change", "combined_score"
        ])
        writer.writeheader()
        for row in selected:
            writer.writerow({
                "timestamp_seconds": f'{row["timestamp_seconds"]:.3f}',
                "frame_index": row["frame_index"], "filename": row["filename"],
                "histogram_change": f'{row["histogram_change"]:.5f}',
                "pixel_change": f'{row["pixel_change"]:.5f}',
                "combined_score": f'{row["combined_score"]:.5f}',
            })

    # Make review thumbnails from selected transitions and their nearby context.
    review_items = []
    if selected:
        for row in selected:
            center = row["timestamp_ms"]
            nearby = sorted(frames, key=lambda x: abs(x[0] - center))
            for item in nearby:
                if abs(item[0] - center) <= args.review_radius * 1000:
                    review_items.append((item[0], item[1], item[2], row["combined_score"]))
        # Deduplicate and preserve ALL review frames in timestamp order.
        # --max-review-images is retained for CLI compatibility but no longer
        # truncates the complete review timeline.
        unique = {item[2].name: item for item in review_items}
        review_items = sorted(unique.values(), key=lambda x: x[0])

    review_dir = out_dir / "transition_sheets"
    review_dir.mkdir(exist_ok=True)
    cols = max(1, args.columns)
    batch_size = max(cols, args.batch_size)
    batch_size = (batch_size // cols) * cols
    for batch_no, start in enumerate(range(0, len(review_items), batch_size), start=1):
        batch = review_items[start:start + batch_size]
        thumbs = []
        for ts_ms, index, path, score in batch:
            image = cv2.imread(str(path))
            if image is None:
                continue
            # Keep the full gameplay/HUD image visible; place the label in a footer.
            resized = cv2.resize(image, (args.thumb_width, args.thumb_height), interpolation=cv2.INTER_AREA)
            footer_h = 32
            thumb = np.zeros((args.thumb_height + footer_h, args.thumb_width, 3), dtype=np.uint8)
            thumb[:args.thumb_height, :, :] = resized
            label = f"{ts_ms/1000:7.1f}s | frame {index}"
            cv2.putText(thumb, label, (8, args.thumb_height + 22),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.58, (255, 255, 255), 1, cv2.LINE_AA)
            thumbs.append(thumb)
        if not thumbs:
            continue
        rows = (len(thumbs) + cols - 1) // cols
        cell_height = args.thumb_height + 32
        canvas = np.zeros((rows * cell_height, cols * args.thumb_width, 3), dtype=np.uint8)
        for i, thumb in enumerate(thumbs):
            y, x = (i // cols) * cell_height, (i % cols) * args.thumb_width
            canvas[y:y + cell_height, x:x + args.thumb_width] = thumb
        dest = review_dir / f"transition_review_{batch_no:03d}.jpg"
        if not cv2.imwrite(str(dest), canvas, [cv2.IMWRITE_JPEG_QUALITY, 88]):
            raise SystemExit(f"Could not write {dest}")

    readme = out_dir / "README.txt"
    readme.write_text(
        "Candidate scene-change detection results\n\n"
        f"Input frames: {len(frames)}\n"
        f"Frames scored: {len(scored)}\n"
        f"Threshold: {args.threshold}\n"
        f"Minimum gap: {args.min_gap} seconds\n"
        f"Candidates reported: {len(selected)}\n\n"
        "Files:\n"
        "  scene_changes.csv - timestamped candidate changes and scores\n"
        "  transition_sheets/ - nearby frames for visual review\n\n"
        "Important: scores are heuristic visual differences, NOT semantic scene labels. "
        "A candidate may be a camera cut, HUD animation, explosion, replay, or other visual change. "
        "Review candidates before using them as round boundaries.\n",
        encoding="utf-8"
    )

    print(f"\nCandidate scene changes: {len(selected)}")
    print(f"CSV: {csv_path}")
    print(f"Review sheets: {review_dir}")
    print(f"README: {readme}")
    print("If too many candidates appear, increase --threshold; if too few, lower it.")


if __name__ == "__main__":
    main()
