<p align="center">
  <img src="docs/3ayn-logo-transparent.png" width="200" alt="3ayn عين">
</p>

<h1 align="center">3ayn · عين</h1>

<p align="center">
  <b>An Arabic-first assistive vision assistant for blind and low-vision users.</b><br>
  Point a camera at the world and hear what's there, in Arabic, out loud.
</p>

<p align="center">
  <a href="https://main.d2t3tjeuucf48t.amplifyapp.com">Live app</a> ·
  <a href="3ayn-backend/README.md">Backend docs</a> ·
  <a href="#work-outside-this-branch">Work outside this branch</a> ·
  <a href="#roadmap">Roadmap</a>
</p>

<p align="center">
  Amazon Industry Program 2026 · IEEE Women in Engineering, AUB
</p>

---

## Contents

- [What 3ayn does](#what-3ayn-does)
- [How we decided things](#how-we-decided-things)
- [Architecture](#architecture)
- [AWS services](#aws-services)
- [Repository layout](#repository-layout)
- [Running it](#running-it)
- [API reference](#api-reference)
- [Privacy and consent](#privacy-and-consent)
- [Accessibility](#accessibility)
- [What works well and what doesn't](#what-works-well-and-what-doesnt)
- [Work outside this branch](#work-outside-this-branch)
- [Roadmap](#roadmap)
- [Team](#team)

---

## What 3ayn does

3ayn is built for a family, not a single user. One blind or low-vision person,
one or more guardians, one shared account. The blind person gets an
Arabic-speaking assistant that sees. The guardian gets a way to help from
anywhere, but only when they're invited in.

**For the blind user**

| Mode | What it does | Spoken example |
|---|---|---|
| **Ask** | Describes the scene in front of the camera | «شاب يقف أمام رف كتب» |
| **Read** | Reads text on a sign, menu or medicine box | reads the label aloud |
| **Find** | Locates an object and gives its direction | «قنينة على يسارك» |
| **Who** | Names a family member the user enrolled | «أمامك ماريتا، أختك» |
| **Live narration** | Continuous description while walking | announces as things change |

**For the guardian**

A separate web view showing the wearer's live camera frames and their location
on a map. It only works after the blind person approves that specific session.

---

## How we decided things

These shaped real code, so they're worth stating.

**Sign-in stays optional, permanently.** If a blind person can be locked out of
their assistive device by a forgotten password, the product has failed. Guest
profiles work fully and always will. An account only links a wearer to a
guardian and syncs settings across devices.

**Consent is a server-side state machine.** Guardian access gets checked on
every single frame upload, not once at onboarding. If the blind person revokes
access, frames stop at the server. A stale client can't keep streaming.

**A stale answer is worse than no answer.** The service worker caches the app
shell but never caches API responses. Telling a blind user there's a chair to
their left, about a room they left five minutes ago, is dangerous.

**Only enrolled faces get matched.** Strangers are never stored, fingerprinted
or clustered. Face recognition is opt-in, one person at a time, by name.

**3ayn assists, it doesn't decide.** Everything it says is information the user
acts on, never an instruction they have to trust with their safety. It won't
answer "is it safe to cross?"

---

## Architecture

```
┌──────────────────┐         ┌──────────────────┐
│   Blind side     │         │  Guardian side   │
│  app/index.html  │         │ app/viewer.html  │
│  PWA, installable│         │  Leaflet map     │
└────────┬─────────┘         └────────┬─────────┘
         │                            │
         │      HTTPS, base64 JPEG    │
         └──────────┬─────────────────┘
                    ▼
         ┌─────────────────────┐
         │  API Gateway (REST) │
         │      CORS, /Prod    │
         └──────────┬──────────┘
                    ▼
      ┌───────────────────────────────┐
      │  Lambda · Java 21 · 8 handlers│
      └───┬────┬────┬────┬────┬───┬───┘
          │    │    │    │    │   │
      Bedrock  │  Rekog  │  Polly │
      Nova 2   │  Labels │  Hala  │
      Lite  Textract  + Faces  DynamoDB ×3
                                   │
                              Cognito (optional)
```

Each side of the frontend is one self-contained HTML file. No build step, no
bundler, no framework. `app/index.html` is the whole blind-side app at about
2,400 lines of HTML, CSS and vanilla JS. We chose that on purpose. It deploys
anywhere, it loads on a bad connection, and a teammate can read the whole thing
in an afternoon.

---

## AWS services

All of this is declared in
[`3ayn-backend/template.yaml`](3ayn-backend/template.yaml) and deploys from that
one file. Region is **eu-west-2**.

| Service | Used for | Detail |
|---|---|---|
| **Bedrock** | Scene description, Ask | `global.amazon.nova-2-lite-v1:0` |
| **Textract** | Text reading (English) | `DetectDocumentText` |
| **Rekognition** | Object detection for Find | `DetectLabels` |
| **Rekognition Face Collections** | Family recognition | collection `threeayn-faces` |
| **Polly** | Arabic speech output | voice `Hala`, neural engine |
| **Lambda** | All request handling | Java 21, 1024 MB, 30 s timeout |
| **API Gateway** | REST API | stage `Prod`, CORS enabled |
| **DynamoDB** | Profiles, users, watch sessions | 3 tables, pay-per-request |
| **Cognito** | Optional accounts | pool `threeayn-users` |
| **Amplify Hosting** | PWA deployment | serves `app/` |
| **AWS SAM** | Infrastructure as code | whole stack, one template |

---

## Repository layout

```
3ayn.ai/
├── app/                        ← DEPLOYED. Amplify serves this directory.
│   ├── index.html                blind-side PWA (Ask/Read/Find/Who/Settings)
│   ├── viewer.html               guardian view (camera + Leaflet map)
│   ├── sw.js                     service worker
│   ├── manifest.webmanifest      PWA manifest
│   └── icon-*.png                install icons
│
├── 3ayn-backend/               ← SAM stack
│   ├── template.yaml             the entire AWS stack
│   ├── samconfig.toml            deploy config (eu-west-2)
│   ├── README.md                 endpoint contracts, smoke tests, costs
│   └── bcknd/ThreeAynFunction/   Java 21 Maven module, 8 handlers
│
├── hardware/                   ← drawings + interactive 3D viewer, both devices
│   ├── drawing-set/index.html    dimensioned technical drawings
│   └── viewer/index.html         rotate and inspect in 3D
│
├── prototypes/
│   └── distance-direction/     ← Python, runs on a laptop, not in the app yet
│       ├── steps/                geometry, announcer, pipeline
│       ├── live.py               webcam loop
│       ├── calibrate.py          measure your step length
│       └── test_logic.py         25 unit tests
│
├── docs/                       ← pitch deck, logo assets
│
└── amplify.yml                   build config (baseDirectory: app)
```

`amplify.yml` sets `baseDirectory: app`, so `app/` is the only frontend that
ships. Everything else is reference material.

---

## Running it

### Frontend, locally

```bash
cd app
python3 -m http.server 8000
```

Open `http://localhost:8000`. The camera needs `localhost` or HTTPS. It won't
work over a plain LAN IP.

On first run, either create an account or tap **Continue as guest**. Then paste
your backend URL into **Settings → Account**.

### Backend

```bash
cd 3ayn-backend
sam build
sam deploy --guided     # stack: threeayn, region: eu-west-2
```

You have to enable Bedrock model access for Amazon Nova in the AWS console
before your first deploy or it fails. The full pre-flight checklist, endpoint
contracts and smoke tests are in
[`3ayn-backend/README.md`](3ayn-backend/README.md).

Copy the `ApiBaseUrl`, `UserPoolId` and `UserPoolClientId` outputs into the
app's Settings tab.

---

## API reference

Everything is `POST` with a JSON body unless noted. `image` is a base64 JPEG.
Raw base64 or a full data URL both work.

| Endpoint | Service | Returns |
|---|---|---|
| `/ask` | Bedrock Nova 2 Lite | `{ text }` scene description |
| `/read` | Textract | `{ text }` text found in frame |
| `/find` | Rekognition Labels | `{ text }` object and direction |
| `/who` | Rekognition Faces + DynamoDB | `{ text, match }` name and relation |
| `/enroll` | Rekognition + DynamoDB | `{ text, faceId }` adds a known face |
| `/speak` | Polly | `{ audio }` base64 MP3 |
| `/user` · `GET /user/{userId}` | DynamoDB | wearer profile |

**Guardian consent flow**, six routes on one handler:

| Route | Purpose |
|---|---|
| `POST /watch/start` | wearer opens a session |
| `POST /watch/request` | guardian requests access |
| `POST /watch/consent` | wearer allows or denies |
| `POST /watch/frame` | wearer uploads a frame, consent re-checked here |
| `POST /watch/stop` | either side ends the session |
| `GET /watch/{watchId}` | guardian polls for the latest frame and location |

---

## Privacy and consent

The consent handshake is the centre of the product. It isn't a feature we bolted
on at the end.

1. The guardian presses **Request camera access**. Nothing is shared yet.
2. The blind person's device announces the request out loud in Arabic.
3. They approve or deny with a deliberate action.
4. While sharing is active, a permanent banner says so. Transparency is dignity.
5. Every frame upload re-checks consent on the server. Revoking is instant.

Face recognition only matches people the family enrolled themselves, by name,
with that person's consent. There's no clustering, no stranger database, no
background identification.

---

## Accessibility

We measured contrast ratios instead of eyeballing them, and the computed values
live as comments in the stylesheet so anyone can audit them:

```css
--ink:#0B1725;      /* 18.1:1 on white  */
--accent:#0A2F6B;   /* 12.87:1 on white */
--red:#C1121F;      /* 6.22:1. #FF3B30 was only 3.55:1 and failed */
```

Two full themes ship. A navy and white guardian theme, and a black and yellow
high-contrast theme for low-vision users (`--accent:#FFD400`, 14.67:1).

Also in: role-based onboarding, multi-profile switching, Arabic and English
throughout, screen wake lock during sessions, and offline detection that
announces connection changes out loud instead of showing silent on-screen text.

---

## What works well and what doesn't

Stated plainly, because a judge will ask about exactly this and because whoever
picks up the code next deserves to know.

Camera AI isn't equally good at everything:

| The user asks | Reliability | What's actually true |
|---|---|---|
| "What's in front of me?" | **Strong** | The most dependable thing a multimodal model does |
| "Read this" | **Strong** in English | See the Textract issue below |
| "Who is this?" | **Strong** | Only for enrolled faces. Everyone else stays "a person" |
| "Find my keys" | **Partial** | Fine if the object is in frame. Aiming a camera blind is the hard part |
| "How far is that?" | **Weak** | A single camera can't measure distance reliably |
| "Is it safe to cross?" | **Never** | 3ayn won't answer this. Any safety guarantee is a liability |

### Known issues

**`/read` sends Arabic through Textract, which doesn't support Arabic.**
Textract handles Latin scripts only, so Read mode fails on Arabic signs. Arabic
should go through Bedrock instead. Fixing it needs a code change and a template
change, since `ReadFunction`'s IAM policy currently grants Textract and nothing
else. This is the highest-priority open bug.

**No gesture layer.** Tap, double-tap, long-press and triple-tap for SOS are
designed but not implemented. Navigation still uses standard tap targets.

**Sign-up is email and password.** For a blind first-run this should be a spoken
or scanned invite code that the guardian generates. Right now it isn't.

**Voice input uses the browser Web Speech API**, not Amazon Transcribe. We
looked at Transcribe Streaming and dropped it because per-minute billing doesn't
work for a device that listens all day. Browser recognition is weaker in Arabic.
That's a trade-off we made knowingly.

**Offline voice caching is half done.** The service worker caches the app shell
correctly, but Polly clips aren't persisted to IndexedDB yet, so cached Arabic
phrases don't survive an app restart.

**Guardian location** uses the browser Geolocation API with Leaflet and
OpenStreetMap, not Amazon Location Service. There are no geofences or safe zones
yet.

**No hardware integration.** The cane and glasses exist as CAD only.

---

## Work outside this branch

Real work lives outside `main`. It's listed here so none of it is invisible.

### Native mobile apps, two branches, not merged

| Branch | Date | Contents |
|---|---|---|
| `mobile-native-apps` | Jul 12 | Full Flutter scaffold: `android/` and `ios/` trees, Gradle and Xcode config, launcher icons, `Info.plist` |
| `mobile-native-apps-spike` | Jul 17 | The Dart logic: `main.dart`, `screens.dart`, `app_state.dart`, `narrator.dart`, `api_service.dart` |

The app targets Android and iOS through Flutter, using `image_picker` for
capture, `http` for backend calls and `flutter_tts` for on-device narration. It
runs in mock mode by default, so the whole app navigates and speaks without a
backend.

The two branches were never combined. The scaffold has no app logic and the
spike has no platform folders. Merging them is a clear, self-contained task for
whoever picks this up. Android was tested over wireless ADB on a Samsung tablet.
iOS was built on a borrowed Mac with Xcode and CocoaPods.

### Other branches

| Branch | Status |
|---|---|
| `redesign/accessibility-theme` | Merged into `main` on Aug 22 |
| `william-branch` | Merged into `main` on Aug 23, live location and wake lock |
| `archive/main-snapshot` | Point-in-time snapshot, kept for safety |

### Distance and direction prototype

Lives in `prototypes/distance-direction/`. A working Python prototype that
answers "how far, and in which direction?" using YOLO object detection together
with monocular metric depth. It announces results the way a blind person
actually navigates:

> "Chair, four steps, two o'clock."

Steps and clock positions rather than metres and degrees. That framing was the
whole point of building it.

| File | Role |
|---|---|
| `steps/geometry.py` | Depth sampling, angle maths, clock-face mapping, step conversion |
| `steps/announcer.py` | When to speak: thresholds, staleness guards, rate limiting, hysteresis |
| `steps/pipeline.py` | Two-model inference, detector plus depth |
| `live.py` | Webcam loop with optional speech through `espeak-ng` |
| `calibrate.py` | Measures the user's own step length |
| `test_logic.py` | 25 unit tests covering the geometry and the announcer |

All 25 tests pass. The neural networks turned out to be the easy part.
Announcement timing was the hard problem. An assistant that repeats itself every
frame is unusable.

It runs on a laptop only. Two things block it from shipping:

1. Distance accuracy has never been checked against a tape measure. Everything
   downstream assumes that number is right.
2. "Door" isn't in COCO's 80 classes. Door detection needs an open-vocabulary
   model like YOLOE. This matters, because doors are one of the most requested
   detections from blind users.

```bash
cd prototypes/distance-direction
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt        # CPU torch: --index-url https://download.pytorch.org/whl/cpu
python calibrate.py                    # measure your step length first
python live.py
```

### Hardware

Two companion devices, fully specified and drawn, neither fabricated. Both live
in `hardware/` with their own README.

| File | What it is |
|---|---|
| `hardware/drawing-set/index.html` | Dimensioned technical drawing set, both devices |
| `hardware/viewer/index.html` | Interactive 3D viewer, rotate and inspect both |
| `hardware/3ayn-glasses.obj` `.mtl` `.glb` | Glasses geometry, exported from the viewer |

Both pages are self-contained and open in any browser from a local clone.
GitHub renders HTML as source, so they will not display from github.com
directly.

**`3AYN-CN-01`, the cane. It feels.**

The cane handles distance and danger, which are the two things a camera is worst
at. Everything here runs locally and instantly and never touches the network.

| Part | Why it's there |
|---|---|
| ESP32 or Pi Zero 2 W | ESP32 preferred: lighter, cheaper, much better battery life |
| Ultrasonic, forward | Aimed at chest and head height. A cane can't detect a low branch, an open truck door or a hanging sign, and those cause real head injuries every day |
| Ultrasonic, angled down | Warns about steps and drop-offs before the tip reaches the edge |
| IMU / accelerometer | Detects a fall or a dropped cane and fires an automatic SOS |
| Handle vibration motor | Closer obstacle means a stronger buzz. Silent, private, instant |
| Recessed SOS button | Findable without sight, hard to press by accident |
| Li-Po with USB-C | Target a full day of use |

**`3AYN-GL-01`, the glasses. They see.**

The glasses carry meaning: what something is, who someone is, what a sign says.

| Part | Why it's there |
|---|---|
| ESP32-CAM or Pi Zero 2 W | Streams to the phone over Wi-Fi, never straight to the cloud |
| Two temple motors | Left and right directional cues without sound. Blind people navigate by hearing, so we don't fill their ears with beeps |
| Bone-conduction audio | Speech reaches the user while both ears stay open to traffic and voices |
| Temple button | "Describe what's in front of me" without touching the phone |
| Li-Po 3.7 V 210 mAh, IP54 | Camera streaming drains this fast, so plan for on-demand capture |

**Why the two devices stay separate.** A cloud round trip takes 1 to 3 seconds.
A person walking normally covers 2 to 4 metres in that time. So an obstacle
warning that travels to the cloud and back arrives after the user has already
hit the obstacle.

> Slow questions go to the cloud. Fast danger never leaves the cane.

Safety events still publish to AWS afterwards, so the activity log stays
complete. The warning itself is never delayed by it.

**Build status.** Nothing has been fabricated, and the blocker is a hardware
fault rather than the design. The Pi 3 board is confirmed dead, a 4-blink
firmware failure. The Pi 2 boots but is ethernet only. Camera Module v1 is
detected over I²C yet times out on the CSI data lanes, most likely a faulty
ribbon cable. That is a $3 part and it is still unresolved.

### `docs/`

The ten-slide pitch deck, with speaker notes embedded in each slide. The demo
video is attached to the `v1` release rather than committed here, since a 19 MB
file would sit in every future clone of the repo forever.

---

## Roadmap

**Immediate, unblocks everything else**

1. Fix `/read` so Arabic goes through Bedrock instead of Textract.
2. Validate the distance prototype against a tape measure.
3. Merge the two Flutter branches into one app that builds.
4. Replace email and password first-run with a guardian-generated invite code.

**Next**

5. Port distance and direction into the app once the accuracy is proven.
6. Build the gesture layer, including triple-tap SOS.
7. Persist Polly clips to IndexedDB so offline phrases survive a restart.
8. YOLOE for open-vocabulary detection, starting with doors.
9. Guardian dashboard: activity timeline, safe zones, push alerts.

**Later**

10. Two-way voice in live view, which turns a camera into a lifeline.
11. Cane and glasses firmware, IoT Core telemetry with the safety loop local.
12. Currency identification for Lebanese notes by sight.
13. Object memory, so the app can answer "where did I leave my keys?"
14. Lebanese dialect tuning, so it speaks the way the user's family speaks.

The one that matters most is time with actual blind and low-vision users. Every
decision about what 3ayn says and when it says it is currently our best guess.
Fifteen seconds of a real user's reaction is worth more than a month of
guessing.

---

## Team

Built by **Team 8**, Amazon Industry Program 2026, IEEE Women in Engineering, AUB.

Frontend and app architecture, voice commands: **Carla**.
Live location and wake lock: **William**.
Backend, accessibility system, consent architecture, deployment, prototypes: **Hadi**.
