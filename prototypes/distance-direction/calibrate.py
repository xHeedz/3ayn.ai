#!/usr/bin/env python3
"""
Measure step length. Do this before trusting any distance the app reports.

The height formula is a population average. Real step length varies a lot,
and blind users walking with a cane typically take shorter, more deliberate
steps than the average -- often 15-25% shorter. Guessing here means every
distance in the app is wrong by that same margin.

  python calibrate.py --distance 5.0 --steps 7     # measured walk
  python calibrate.py --height 1.75                # rough fallback
  python calibrate.py --show                       # print saved config
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from steps.geometry import step_length_from_height

CONFIG_PATH = os.path.expanduser("~/.3ayn-steps.json")


def save(**values) -> None:
    cfg = {}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH) as f:
                cfg = json.load(f)
        except (OSError, json.JSONDecodeError):
            pass
    cfg.update(values)
    with open(CONFIG_PATH, "w") as f:
        json.dump(cfg, f, indent=2)
    print(f"saved to {CONFIG_PATH}")


def main() -> int:
    p = argparse.ArgumentParser(description="calibrate step length")
    p.add_argument("--distance", type=float, help="measured walk distance in metres")
    p.add_argument("--steps", type=int, help="steps taken over that distance")
    p.add_argument("--height", type=float, help="body height in metres (fallback)")
    p.add_argument("--hfov", type=float, help="camera horizontal FOV in degrees")
    p.add_argument("--show", action="store_true", help="print current config")
    args = p.parse_args()

    if args.show:
        if os.path.exists(CONFIG_PATH):
            with open(CONFIG_PATH) as f:
                print(json.dumps(json.load(f), indent=2))
        else:
            print("no config yet")
        return 0

    if args.hfov:
        save(hfov_deg=args.hfov)

    if args.distance and args.steps:
        if args.steps <= 0 or args.distance <= 0:
            print("distance and steps must both be positive", file=sys.stderr)
            return 1
        length = args.distance / args.steps
        if not 0.3 <= length <= 1.2:
            print(f"warning: {length:.2f}m per step is outside the normal "
                  f"0.3-1.2m range -- double-check the measurement", file=sys.stderr)
        print(f"step length: {length:.3f} m")
        print(f"  (a 10m corridor would read as {int(10/length)} steps)")
        save(step_length_m=round(length, 3))
        return 0

    if args.height:
        length = step_length_from_height(args.height)
        print(f"estimated step length: {length:.3f} m")
        print("this is a population average -- measure a real walk when you can")
        save(step_length_m=round(length, 3))
        return 0

    print(__doc__)
    print("\nHow to measure properly:")
    print("  1. Mark a start line and measure out a straight 10 metres.")
    print("  2. Walk it at a normal, comfortable pace -- not deliberately even.")
    print("  3. Count every footfall, both feet. That total is --steps.")
    print("  4. Repeat three times and average, walking gait is noisy.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
