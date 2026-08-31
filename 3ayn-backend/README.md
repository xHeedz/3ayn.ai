# 3ayn Backend, AWS SAM + Java 21

The whole stack in one template. Region is **eu-west-2**, stack name is
`threeayn`.

Eight Lambda functions, one REST API, three DynamoDB tables, one Cognito user
pool. `sam deploy` builds all of it.

---

## Before your first deploy

Do these three things or the deploy fails.

**1. Enable Bedrock model access.**
AWS Console → Bedrock → Model access → request **Amazon Nova**. It's a one-time
step and takes about a minute. Without it, every `/ask` call errors out.

The default `MODEL_ID` is `global.amazon.nova-2-lite-v1:0`. If the Converse call
comes back with a model-id error, check the exact inference profile ID for your
account and region in the Bedrock console, then update it in `template.yaml`
under Globals.

**2. Check your Polly voice.**
The default is **Hala**, a neural `ar-AE` Gulf Arabic voice. Zeina, the classic
`ar-SA` voice, only exists on the standard engine. To use her you need to set
both `POLLY_VOICE=Zeina` and `POLLY_ENGINE=standard` in Globals. Changing the
voice without the engine will fail.

**3. You don't need to create the face collection.**
`EnrollHandler` holds `rekognition:CreateCollection`, so `threeayn-faces` gets
created automatically the first time someone enrolls a face.

---

## Deploy

```bash
cd 3ayn-backend
sam build
sam deploy --guided     # stack: threeayn, region: eu-west-2, accept the rest
```

Three outputs matter. Paste all three into the app's **Settings → Account**:

| Output | Used for |
|---|---|
| `ApiBaseUrl` | every API call |
| `UserPoolId` | optional sign-in |
| `UserPoolClientId` | optional sign-in |

---

## Endpoints

Everything is `POST` with a JSON body unless noted. CORS is open
(`AllowOrigin: '*'`). `image` is a base64 JPEG, and both raw base64 and a full
data URL work.

### Vision and speech

| Path | Body | Returns | AWS service |
|---|---|---|---|
| `/ask` | `{ image, lang, question? }` | `{ text }` | Bedrock Nova 2 Lite |
| `/read` | `{ image, lang }` | `{ text }` | Textract |
| `/find` | `{ image, object, objectAr?, lang }` | `{ text }` | Rekognition Labels |
| `/who` | `{ image, lang }` | `{ text, match }` | Rekognition Faces + DynamoDB |
| `/enroll` | `{ image, name, relation }` | `{ text, faceId }` | Rekognition + DynamoDB |
| `/speak` | `{ text, lang }` | `{ audio }` base64 MP3 | Polly |

### Profiles

| Path | Method | Returns |
|---|---|---|
| `/user` | POST | creates or updates a wearer profile |
| `/user/{userId}` | GET | profile and spoken welcome |

### Guardian consent, six routes on one handler

`WatchHandler` implements the consent state machine. This is the security
boundary of the product.

| Path | Purpose |
|---|---|
| `POST /watch/start` | wearer opens a session |
| `POST /watch/request` | guardian requests access, wearer is notified out loud |
| `POST /watch/consent` | wearer allows or denies |
| `POST /watch/frame` | wearer uploads a frame, consent re-checked on every call |
| `POST /watch/stop` | either side ends the session |
| `GET /watch/{watchId}` | guardian polls for the latest frame and location |

The re-check on `/watch/frame` is deliberate and it does real work. Consent
isn't a flag set once when the session starts. If the wearer revokes access, the
very next frame upload gets rejected at the server. A stale or malicious client
can't keep streaming.

---

## Data model

| Table | Key | Holds |
|---|---|---|
| `ThreeAynProfiles` | `faceId` (S) | enrolled family: name, relation |
| `ThreeAynUsers` | `userId` (S) | wearer profile and preferences |
| `ThreeAynWatch` | `watchId` (S) | session state, consent status, last frame, last position |

All three are `PAY_PER_REQUEST`, so there's no provisioned capacity to forget
about.

Each Lambda only gets the actions it needs. `AskFunction` has Bedrock and
nothing else. `WhoFunction` has `SearchFacesByImage` plus `GetItem` on one
table. Least privilege per function instead of one shared role.

---

## Authentication, optional by design

Cognito pool `threeayn-users`, web client `threeayn-web`.

- Email as the username, auto-verified by an emailed code
- No MFA, recovery through verified email
- Public client with no secret, since you can't keep a secret in a browser app
- `ALLOW_USER_PASSWORD_AUTH` so the single-file frontend can authenticate over
  HTTPS without pulling in an SDK just to implement SRP
- Access and ID tokens last 60 minutes, refresh tokens last 30 days

Guest mode is a first-class path, not a fallback. 3ayn is an assistive device,
and a blind user locked out by a forgotten password is a product failure.
Signing in only links a wearer to a guardian and syncs across devices.

Passwords are never stored or seen by us. Cognito handles hashing,
verification, reset codes and lockout.

---

## Smoke tests

```bash
BASE=https://<api-id>.execute-api.eu-west-2.amazonaws.com/Prod
IMG=$(base64 -w0 test.jpg)

# Test /speak first. It needs no image and proves the whole pipeline.
curl -s $BASE/speak -H 'Content-Type: application/json' \
  -d '{"text":"مرحبا، أنا عين","lang":"ar"}' | head -c 200

curl -s $BASE/ask -H 'Content-Type: application/json' \
  -d "{\"image\":\"$IMG\",\"lang\":\"ar\"}"

curl -s $BASE/find -H 'Content-Type: application/json' \
  -d "{\"image\":\"$IMG\",\"object\":\"Bottle\",\"objectAr\":\"قنينة\",\"lang\":\"ar\"}"
```

If `/speak` works but `/ask` doesn't, it's Bedrock model access. See step 1.

---

## Cost guardrails

| Service | Free tier |
|---|---|
| Textract | 1,000 pages/month, first 3 months |
| Rekognition | 5,000 images/month, first 12 months |
| Polly | 5M standard or 1M neural characters/month, first 12 months |
| Bedrock Nova | No free tier, pay per token |
| DynamoDB | Pay-per-request, negligible at this scale |
| Lambda | 1M requests/month, always free |

Don't wire `/ask` into a continuous loop. Nova is cheap per call, fractions of a
cent per image, but a frame-rate loop isn't. Ask is tap-to-ask by design.

We evaluated Transcribe Streaming for voice input and rejected it. Per-minute
billing doesn't work for a device meant to listen all day. The frontend uses the
browser Web Speech API instead, which is weaker in Arabic but free and runs on
the device.

---

## Known issues

**`/read` can't read Arabic.** Textract supports Latin scripts only, so Read
mode, which is the feature most tied to 3ayn's Arabic identity, fails on Arabic
signs. Arabic should route through Bedrock, which handles it well. Fixing this
needs two changes: the handler logic, and `ReadFunction`'s IAM policy in
`template.yaml`, which currently grants `textract:DetectDocumentText` and
nothing else. Highest-priority open bug.

**Java cold starts run 3 to 8 seconds.** This is the main latency complaint.
Lambda SnapStart is the fix and it isn't enabled yet. An EventBridge Scheduler
warmer would also help before a live demo.

**No observability configured.** CloudWatch Logs work by default, but there's no
dashboard and X-Ray tracing is off.

---

## Code layout

```
3ayn-backend/
├── template.yaml                    the whole stack
├── samconfig.toml                   eu-west-2, stack name threeayn
└── bcknd/ThreeAynFunction/
    ├── pom.xml                      Java 21, AWS SDK v2, shade plugin
    └── src/main/java/com/threeayn/
        ├── handlers/                8 thin handlers, one per concern
        │   ├── AskHandler.java
        │   ├── ReadHandler.java
        │   ├── FindHandler.java
        │   ├── WhoHandler.java
        │   ├── EnrollHandler.java
        │   ├── SpeakHandler.java
        │   ├── UserHandler.java
        │   └── WatchHandler.java    ← the consent state machine
        └── util/
            ├── ApiResponse.java     shared JSON and CORS response builder
            └── RequestParser.java   body parsing, base64 and data URL handling
```

One Maven module, eight thin handlers, two shared utilities. The handlers only
do request handling and none of them import each other.
