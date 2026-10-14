#!/usr/bin/env python3
"""Step 2: create review sheets and a timestamped scene-label CSV for extracted VALORANT VOD frames.

This is deliberately a human-in-the-loop baseline. It does NOT pretend to infer semantic
scene labels from simple image heuristics. It creates contact sheets at a configurable
sampling interval and a CSV template so labels can be checked before training/automation.
"""
from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path

import cv2
import numpy as np

FRAME_RE = re.compile(r"frame_(\d+)_(\d+)ms\.(?:jpg|jpeg|png)$", re.IGNORECASE)
LABELS = [
    "gameplay",
    "player_camera",
    "replay",
    "between_round",
    "transition",
    "other",
    "unknown",
]

def parse_args():
    p = argparse.ArgumentParser(description="Prepare scene-review sheets and CSV for extracted VOD frames.")
    p.add_argument("--frames", default=r".\output\kick-5-10\frames", help="Folder containing extracted frames")
    p.add_argument("--output", default=r".\output\kick-5-10\scene_review", help="Output folder")
    p.add_argument("--every-seconds", type=float, default=5.0, help="Include one review image every N seconds")
    p.add_argument("--columns", type=int, default=4, help="Contact sheet columns")
    p.add_argument("--thumb-width", type=int, default=480, help="Thumbnail width in pixels")
    p.add_argument("--thumb-height", type=int, default=270, help="Thumbnail height in pixels")
    p.add_argument("--batch-size", type=int, default=48, help="Maximum thumbnails per contact sheet")
    return p.parse_args()

def main():
    args = parse_args()
    frame_dir = Path(args.frames).resolve()
    out_dir = Path(args.output).resolve()
    if not frame_dir.is_dir():
        raise SystemExit(f"Frames folder not found: {frame_dir}")

    all_frames = []
    for path in frame_dir.iterdir():
        match = FRAME_RE.match(path.name)
        if match and path.is_file():
            all_frames.append((int(match.group(2)), int(match.group(1)), path))
    all_frames.sort(key=lambda item: item[0])
    if not all_frames:
        raise SystemExit(f"No frame_XXXXXX_XXXXXXXXXXms.jpg files found in: {frame_dir}")

    out_dir.mkdir(parents=True, exist_ok=True)
    step_ms = max(1, int(args.every_seconds * 1000))
    selected = []
    next_ms = 0
    for timestamp_ms, index, path in all_frames:
        if timestamp_ms >= next_ms:
            selected.append((timestamp_ms, index, path))
            next_ms = timestamp_ms + step_ms

    csv_path = out_dir / "scene_labels.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow(["timestamp_seconds", "frame_index", "filename", "scene_label", "notes"])
        for timestamp_ms, index, path in selected:
            writer.writerow([f"{timestamp_ms / 1000:.3f}", index, path.name, "unknown", ""])

    cols = max(1, args.columns)
    batch_size = max(cols, args.batch_size)
    batch_size = (batch_size // cols) * cols
    for batch_no, start in enumerate(range(0, len(selected), batch_size), start=1):
        batch = selected[start:start + batch_size]
        thumbs = []
        for timestamp_ms, index, path in batch:
            image = cv2.imread(str(path))
            if image is None:
                continue
            thumb = cv2.resize(image, (args.thumb_width, args.thumb_height), interpolation=cv2.INTER_AREA)
            cv2.rectangle(thumb, (0, 0), (thumb.shape[1], 34), (0, 0, 0), -1)
            label = f"{timestamp_ms/1000:07.1f}s | frame {index}"
            cv2.putText(thumb, label, (10, 23), cv2.FONT_HERSHEY_SIMPLEX, 0.62, (255,255,255), 1, cv2.LINE_AA)
            thumbs.append(thumb)

        if not thumbs:
            continue
        rows = (len(thumbs) + cols - 1) // cols
        canvas = np.zeros((rows * args.thumb_height, cols * args.thumb_width, 3), dtype=np.uint8)
        for i, thumb in enumerate(thumbs):
            y = (i // cols) * args.thumb_height
            x = (i % cols) * args.thumb_width
            canvas[y:y + args.thumb_height, x:x + args.thumb_width] = thumb
        sheet_path = out_dir / f"scene_review_{batch_no:03d}.jpg"
        if not cv2.imwrite(str(sheet_path), canvas, [cv2.IMWRITE_JPEG_QUALITY, 88]):
            raise SystemExit(f"Could not write contact sheet: {sheet_path}")

    readme = out_dir / "README.txt"
    readme.write_text(
        "Step 2 scene-review outputs\n\n"
        f"Frames folder: {frame_dir}\n"
        f"All extracted frames found: {len(all_frames)}\n"
        f"Review frames selected: {len(selected)} (approximately every {args.every_seconds:g} seconds)\n\n"
        "1. Open scene_review_001.jpg, scene_review_002.jpg, etc. to review the timeline.\n"
        "2. Open scene_labels.csv in Excel or a text editor.\n"
        "3. Change scene_label from 'unknown' to one of:\n"
        + ", ".join(LABELS) + "\n"
        "4. Add optional notes for ambiguous scenes.\n\n"
        "Important: this is a review/template stage, not automatic AI classification. "
        "We will use verified labels to build and evaluate the next classifier rather than "
        "claiming that simple visual rules can reliably distinguish all broadcast scenes.\n",
        encoding="utf-8"
    )

    print(f"Frames found: {len(all_frames)}")
    print(f"Review frames selected: {len(selected)}")
    print(f"Scene review sheets: {out_dir}")
    print(f"Label template: {csv_path}")
    print(f"Readme: {readme}")

if __name__ == "__main__":
    main()
