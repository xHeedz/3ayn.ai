# 3ayn — steps feature prototype

Detects an object, measures how far it is in metres, converts that to steps,
and speaks it with a clock direction. Runs entirely on-device, no backend.

This is the laptop prototype. The point is to validate the approach and tune
the numbers before any of it goes into Flutter.

## Setup

```
cd 3ayn-steps
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
sudo apt install espeak-ng
```

Your Legion has a discrete GPU, so install CUDA torch for a large speedup:

```
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
python -c "import torch; print(torch.cuda.is_available())"
```

Model weights download automatically on first run (~18MB for the two YOLO26
models). The open-vocabulary detector pulls an extra ~600MB of CLIP weights,
one time only.

## Calibrate first

Every distance the app reports depends on your step length. Do not skip this.

```
python calibrate.py --distance 10.0 --steps 14
```

Measure out 10 metres, walk it at a normal pace, count every footfall. Repeat
three times and average. Saved to `~/.3ayn-steps.json`.

Fallback if you can't measure right now:

```
python calibrate.py --height 1.75
```

## Run

```
python live.py --preview
```

That uses the COCO detector — 80 fixed classes. Useful indoor ones: chair,
couch, bed, dining table, tv, sink, refrigerator, oven, toilet, potted plant,
person.

**"door" is not in COCO.** For doors you need the open-vocabulary detector,
which takes arbitrary text:

```
python live.py --classes door,doorway,stairs --target door --speak
```

Slower and much heavier, but it detects things COCO never trained on.

Test on a recording instead of a live camera:

```
python live.py --source hallway.mp4 --preview
```

## Tests

```
python -m pytest test_logic.py -v
```

25 tests covering the geometry and the announcement logic. All of it is pure
functions with no camera or model dependency, which is why it ports cleanly to
Dart later.

## How it works

Two models run on each frame. The detector says what and where. The depth
model returns a per-pixel distance map in metres. For each detection we sample
the depth inside it, convert metres to steps, and work out the clock direction
from where it sits in the frame.

Three decisions in here are less obvious than they look:

**Low percentile, not mean.** A bounding box always contains background —
floor between chair legs, wall beside a door frame — and background is farther
away, so an average reports objects as more distant than they are. We take the
25th percentile, which leans toward the near face of the object. That's the
surface you actually reach, and erring near is the safe direction.

**Always round down.** 8.9 steps becomes 8. Arriving early and reaching out is
safe; overshooting into a door is not.

**Stale frames are discarded.** Every reading carries the timestamp of when the
frame was captured. If more than 500ms passed before it was processed, it gets
thrown away rather than spoken, because by then the user has moved and the
number is wrong. Silence beats a confident wrong distance.

## Tuning

- `--every N` — process every Nth frame. Raise it if the loop lags.
- `--conf` — detection confidence floor. Lower catches more, false-positives more.
- `--hfov` — your camera's horizontal field of view. Wrong value skews all the
  clock directions. Laptop webcams are usually 60–78 degrees.

The announcement bands live in `steps/announcer.py` as `bands=(10, 5, 3, 1, 0)`.
Those are step counts at which it speaks.

## Known limits

- Monocular depth degrades on blank walls, glass, mirrors, and in low light.
- The open-vocabulary detector is too heavy for a phone as-is. For mobile you'd
  either export it with the class embeddings baked in, or fine-tune a small
  YOLO on a door dataset.
- No obstacle detection below knee height or above head height. This
  supplements a cane, it does not replace one.
