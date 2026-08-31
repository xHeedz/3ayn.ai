#!/usr/bin/env python3
"""
Live steps feature. Point a camera at a room, hear how many steps to things.

  python live.py                                  # COCO classes, webcam
  python live.py --classes door,stairs            # open vocabulary
  python live.py --target door --speak            # only announce doors, out loud
  python live.py --source room.mp4 --preview      # test on a recording

Press q to quit (preview mode) or Ctrl-C (headless).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time

import cv2

from steps.announcer import StepAnnouncer
from steps.geometry import DEFAULT_HFOV_DEG
from steps.pipeline import StepsPipeline, pick_target

CONFIG_PATH = os.path.expanduser("~/.3ayn-steps.json")


def load_config() -> dict:
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH) as f:
                return json.load(f)
        except (OSError, json.JSONDecodeError):
            pass
    return {}


class Speaker:
    """
    Speaks via espeak-ng if it's installed, otherwise prints.

    Non-blocking on purpose: a blocking TTS call stalls the capture loop, and
    a stalled loop means stale frames, which is the exact thing the announcer
    is built to reject.
    """

    def __init__(self, enabled: bool):
        self.binary = shutil.which("espeak-ng") or shutil.which("espeak")
        self.enabled = enabled and self.binary is not None
        if enabled and not self.enabled:
            print("[warn] espeak-ng not found, printing instead. "
                  "Install with: sudo apt install espeak-ng", file=sys.stderr)
        self._proc = None

    def say(self, text: str) -> None:
        print(f"  >> {text}", flush=True)
        if not self.enabled:
            return
        if self._proc is not None and self._proc.poll() is None:
            self._proc.kill()
        self._proc = subprocess.Popen(
            [self.binary, "-s", "165", text],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )


def main() -> int:
    cfg = load_config()

    p = argparse.ArgumentParser(description="3ayn steps feature prototype")
    p.add_argument("--source", default="0",
                   help="webcam index, or path to an image/video file")
    p.add_argument("--classes", default=None,
                   help="comma-separated open-vocabulary classes, e.g. door,stairs")
    p.add_argument("--target", default=None,
                   help="only announce this label, e.g. door")
    p.add_argument("--step-length", type=float, default=cfg.get("step_length_m", 0.72),
                   help="metres per step (run calibrate.py to measure yours)")
    p.add_argument("--hfov", type=float, default=cfg.get("hfov_deg", DEFAULT_HFOV_DEG),
                   help="camera horizontal field of view in degrees")
    p.add_argument("--conf", type=float, default=0.35, help="detection confidence floor")
    p.add_argument("--every", type=int, default=2,
                   help="process every Nth frame (raise it if the loop lags)")
    p.add_argument("--speak", action="store_true", help="speak out loud via espeak-ng")
    p.add_argument("--preview", action="store_true", help="show an annotated window")
    args = p.parse_args()

    classes = [c.strip() for c in args.classes.split(",")] if args.classes else None

    if classes:
        print(f"[init] open-vocabulary detector, classes: {classes}")
        print("[init] first run downloads ~600MB of CLIP weights, be patient")
    else:
        print("[init] COCO detector (80 classes -- note: 'door' is NOT one of them)")
    print(f"[init] step length {args.step_length:.2f}m, HFOV {args.hfov:.0f} deg")

    pipeline = StepsPipeline(classes=classes, conf=args.conf)
    announcer = StepAnnouncer(step_length_m=args.step_length)
    speaker = Speaker(args.speak)

    source = int(args.source) if args.source.isdigit() else args.source
    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        print(f"[error] could not open source: {args.source}", file=sys.stderr)
        return 1

    frame_no = 0
    latencies = []
    print("[init] running -- Ctrl-C to stop\n")

    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            frame_no += 1

            if frame_no % args.every:
                continue

            # Timestamp at capture, not after inference. The announcer uses the
            # gap between the two to throw away readings that took too long.
            captured_at = time.time()
            targets = pipeline.process(frame, hfov_deg=args.hfov, timestamp=captured_at)
            latencies.append(time.time() - captured_at)

            target = pick_target(targets, wanted=args.target)
            message = announcer.update(target, time.time())
            if message:
                speaker.say(message)

            if args.preview:
                for t in targets[:5]:
                    colour = (0, 200, 0) if t is target else (140, 140, 140)
                    cv2.putText(frame, f"{t.label} {t.distance_m:.1f}m",
                                (10, 30 + 26 * targets.index(t)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7, colour, 2)
                cv2.imshow("3ayn steps", frame)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break
    except KeyboardInterrupt:
        print("\n[stop] interrupted")
    finally:
        cap.release()
        if args.preview:
            cv2.destroyAllWindows()

    if latencies:
        avg = sum(latencies) / len(latencies)
        print(f"\n[perf] {len(latencies)} frames, {avg*1000:.0f}ms per frame "
              f"({1/avg:.1f} fps effective)")
        if avg > 0.5:
            print("[perf] slower than the 0.5s staleness limit -- raise --every "
                  "or drop --conf, otherwise readings get discarded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
