# Hardware

Two companion devices for 3ayn: a smart cane and smart glasses. Both are fully
specified with dimensioned drawings and an interactive 3D model. Neither has
been fabricated.

## What's here

| Path | What it is |
|---|---|
| `drawing-set/index.html` | Dimensioned technical drawing set, both devices |
| `viewer/index.html` | Interactive 3D viewer, rotate and inspect both devices |
| `3ayn-glasses.obj` + `.mtl` | Glasses geometry with materials, exported from the viewer |
| `3ayn-glasses.glb` | Same geometry as a single binary glTF file |

## Viewing

Both pages are self-contained. Clone the repo and open either file in a browser:

```bash
xdg-open hardware/viewer/index.html
xdg-open hardware/drawing-set/index.html
```

GitHub shows HTML as source code rather than rendering it, so opening these
from github.com will not work. Use a local clone, or the GitHub Pages URLs if
Pages is enabled on this repo.

The viewer builds its geometry procedurally in Three.js and exports OBJ, MTL and
GLB on demand, so it needs no external model files to run.

## The two devices

### 3AYN-CN-01, the cane. It feels.

The cane handles distance and danger, the two things a camera is worst at.
Everything here runs locally and instantly and never touches the network.

| Part | Why it's there |
|---|---|
| ESP32 or Pi Zero 2 W | ESP32 preferred: lighter, cheaper, much better battery life |
| Ultrasonic, forward | Aimed at chest and head height. A cane cannot detect a low branch, an open truck door or a hanging sign, and those cause real head injuries every day |
| Ultrasonic, angled down | Warns about steps and drop-offs before the tip reaches the edge |
| IMU / accelerometer | Detects a fall or a dropped cane and fires an automatic SOS |
| Handle vibration motor | Closer obstacle means a stronger buzz. Silent, private, instant |
| Recessed SOS button | Findable without sight, hard to press by accident |
| Li-Po with USB-C | Target a full day of use |

### 3AYN-GL-01, the glasses. They see.

The glasses carry meaning: what something is, who someone is, what a sign says.

| Part | Why it's there |
|---|---|
| ESP32-CAM or Pi Zero 2 W | Streams to the phone over Wi-Fi, never straight to the cloud |
| Two temple motors | Left and right directional cues without sound. Blind people navigate by hearing, so we don't fill their ears with beeps |
| Bone-conduction audio | Speech reaches the user while both ears stay open to traffic and voices |
| Temple button | "Describe what's in front of me" without touching the phone |
| Li-Po 3.7 V 210 mAh, IP54 | Camera streaming drains this fast, so plan for on-demand capture |

## Why the two devices stay separate

A cloud round trip takes 1 to 3 seconds. A person walking normally covers 2 to 4
metres in that time. So an obstacle warning that travels to the cloud and back
arrives after the user has already hit the obstacle.

> Slow questions go to the cloud. Fast danger never leaves the cane.

Safety events still publish to AWS afterwards, so the activity log stays
complete. The warning itself is never delayed by it.

## Build status

Nothing has been fabricated. The blocker is a hardware fault, not the design.

The Pi 3 board is confirmed dead, a 4-blink firmware failure. The Pi 2 boots but
is ethernet only. Camera Module v1 is detected over I²C yet times out on the CSI
data lanes, most likely a faulty ribbon cable. That is a $3 part and it is still
unresolved.
