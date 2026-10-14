#!/usr/bin/env python3
"""Rule-based overlay detectors for VALORANT VOD frames (960x540 grayscale analysis).
Flags per frame: replay_badge, round_win_banner, vct_logo_stinger, black_frame.
Usage: python detect_overlays.py --frames <frames_dir> --out overlay_flags.csv
Templates (binary masks) live in detector_templates/ next to this script."""
import argparse, csv, re
from pathlib import Path
import cv2, numpy as np

HERE = Path(__file__).resolve().parent
TPL = {n: cv2.imread(str(HERE / "detector_templates" / f"{n}.png"), 0).astype(np.float32) / 255
       for n in ("replay_badge", "round_win", "vct_logo")}
WIN = {"replay_badge": (.80, .90, 1.0, .97), "round_win": (.38, .22, .62, .29), "vct_logo": (.30, .15, .70, .85)}
THRESH = 0.5
FRAME_RE = re.compile(r"frame_(\d+)_(\d+)ms\.(?:jpg|jpeg|png)$", re.I)

def crop(a, b):
    h, w = a.shape
    return a[int(b[1]*h):int(b[3]*h), int(b[0]*w):int(b[2]*w)]

def score(gray, name):
    w = (crop(gray, WIN[name]) > 200).astype(np.float32)
    return float(cv2.matchTemplate(w, TPL[name], cv2.TM_CCOEFF_NORMED).max())

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", required=True); ap.add_argument("--out", default="overlay_flags.csv")
    a = ap.parse_args()
    rows = []
    for p in sorted(Path(a.frames).iterdir()):
        m = FRAME_RE.match(p.name)
        if not m: continue
        g = cv2.resize(cv2.imread(str(p), 0), (960, 540))
        s = {n: score(g, n) for n in TPL}
        rows.append(dict(frame_index=int(m.group(1)), timestamp_ms=int(m.group(2)), filename=p.name,
            replay_badge=int(s["replay_badge"] > THRESH), round_win_banner=int(s["round_win"] > THRESH),
            vct_logo_stinger=int(s["vct_logo"] > THRESH), black_frame=int(g.mean() < 30 and g.std() < 12),
            badge_score=round(s["replay_badge"], 3), round_win_score=round(s["round_win"], 3), logo_score=round(s["vct_logo"], 3)))
    with open(a.out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
    print(f"wrote {len(rows)} rows to {a.out}")
if __name__ == "__main__": main()
