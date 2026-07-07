# 3ayn Backend — SAM + Java 21

Six endpoints, one deploy. Region: eu-west-2.

## Endpoints (all POST, JSON, CORS enabled)

| Path      | Body                                   | Returns                | AWS service        |
|-----------|----------------------------------------|------------------------|--------------------|
| /ask      | { image, lang, question? }             | { text }               | Bedrock Nova Lite  |
| /read     | { image, lang }                        | { text }               | Textract           |
| /find     | { image, object, objectAr?, lang }     | { text }               | Rekognition Labels |
| /who      | { image, lang }                        | { text, match }        | Rekognition Faces  |
| /enroll   | { image, name, relation }              | { text, faceId }       | Rekognition + DynamoDB |
| /speak    | { text, lang }                         | { audio: base64 mp3 }  | Polly              |

`image` = base64 JPEG (raw base64 or full data URL — both accepted).

## Before first deploy — do these or it WILL fail

1. **Enable Bedrock model access**: AWS Console -> Bedrock -> Model access ->
   request access to **Amazon Nova Lite**. Takes a minute, one-time.
2. If Nova Lite is not served from eu-west-2 in your account, the EU
   cross-region inference profile `eu.amazon.nova-lite-v1:0` (already the
   default MODEL_ID) routes it. If the Converse call errors with a model-id
   message, check the exact profile id in the Bedrock console and update
   MODEL_ID in template.yaml.
3. Polly voices: default is Hala (neural, ar-AE Gulf Arabic). Zeina (the
   classic ar-SA voice) is STANDARD engine only — to use her, set
   POLLY_VOICE=Zeina, POLLY_ENGINE=standard in template.yaml Globals.

## Build & deploy

```bash
cd 3ayn-backend
sam build
sam deploy --guided     # stack name: threeayn, region: eu-west-2, accept the rest
```

Copy the `ApiBaseUrl` output. Paste it into the app's Settings tab.

## Smoke tests

```bash
BASE=https://<api-id>.execute-api.eu-west-2.amazonaws.com/Prod
IMG=$(base64 -w0 test.jpg)

curl -s $BASE/speak -H 'Content-Type: application/json' \
  -d '{"text":"مرحبا، أنا عين","lang":"ar"}' | head -c 200

curl -s $BASE/ask -H 'Content-Type: application/json' \
  -d "{\"image\":\"$IMG\",\"lang\":\"ar\"}"

curl -s $BASE/read -H 'Content-Type: application/json' \
  -d "{\"image\":\"$IMG\",\"lang\":\"ar\"}"

curl -s $BASE/find -H 'Content-Type: application/json' \
  -d "{\"image\":\"$IMG\",\"object\":\"Bottle\",\"objectAr\":\"قنينة\",\"lang\":\"ar\"}"
```

Test /speak first — it needs no image and proves the whole pipeline.

## Cost guardrails (free-tier awareness)

- Textract: 1,000 pages/month free (first 3 months). Fine for demo use.
- Rekognition: 5,000 images/month free (first 12 months).
- Polly: 5M standard / 1M neural chars/month free (first 12 months).
- Bedrock Nova Lite: NO free tier — pay per token, but Lite is the cheapest
  multimodal model (~fractions of a cent per image). Do NOT wire /ask into a
  continuous loop; it is tap-to-ask only. The always-on social loop stays
  on-device (MediaPipe) at zero cloud cost — that is deliberate.

## Architecture note for the report

Latency-critical narration (social intent) never leaves the device.
Cloud endpoints handle only user-initiated, latency-tolerant requests.
One Maven module, six thin handlers, shared ApiResponse/RequestParser utils.
