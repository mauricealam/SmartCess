# SmartCess backend (FastAPI)

Thin, stateless proxy between the SmartCess Android app and the Meta Model
API. The Meta key lives here (environment variable) and never reaches phones.

- `POST /api/auth/google` — exchange a Google ID token for a session pair.
- `POST /api/auth/refresh` — rotate the session pair.
- `POST /api/auth/signout` — client wipes tokens (`{"ok": true}`).
- `POST /api/ai/chat` — transparent chat-completions proxy (OpenAI-compatible
  subset in and out; unknown fields rejected).
- `POST /api/ai/bill-extract` — server-composed bill prompt over an uploaded
  image; returns `BillExtraction` JSON, retries once on malformed JSON.
- `WS /api/voice/realtime` — PCM audio up, transcript JSON down, `endStream`
  to finish input, normal `1000` close at the end.
- `GET /health` — `{status:"ok", version}` for deploys, monitoring, and app
  warm-up. No auth.

Sessions are stateless signed JWTs (15-min access, 7-day refresh). v1 has no
database: sign-out is client-side wipe, and rate limits are in-memory
(20 chat/min, 10 bill/min, 1 live voice session per user). Nothing spoken,
photographed, or transcribed is written to disk or logs; logs carry request
IDs, truncated user hashes, endpoints, latency, status, and token counts only.

## Render free-tier configuration

One web service, Docker runtime, free plan — see [render.yaml](../render.yaml):

- Build: `pip install -r requirements.txt` (from the Dockerfile).
- Start: `uvicorn app.main:app --host 0.0.0.0 --port $PORT`.
- Health check path: `/health` (Render restarts unhealthy deploys).
- No extra services, no disk, no background workers: the 750 free
  instance hours cover one always-on service for a full month.

## Environment variables (names only — values live in Render, never in git)

| Variable | Required | Purpose |
|---|---|---|
| `META_API_KEY` | yes | Meta Model API key, added server-side to Meta calls |
| `JWT_SECRET` | yes | HMAC secret for session tokens (Render `generateValue`) |
| `GOOGLE_SERVER_CLIENT_ID` | yes | Web OAuth client ID; token `aud` must match or exchange fails |
| `META_BASE_URL` | no | Default `https://api.meta.ai/v1` |
| `META_STT_URL` | no | Default `wss://api.meta.ai/v1/asr/realtime` |
| `STT_MODEL` | no | Default `muse-voice-transcribe-1.0` |

Local runs copy `.env.example` to `.env` (git-ignored).

## Health-check and cold-start behavior

Render free spins the service down after ~15 minutes without traffic; the
next request pays a cold start (typically 30–60s: container boot + imports).

- `/health` is cheap and unauthenticated: the app calls it at launch when
  signed in. Until it answers 200, the app shows an accessible
  “Preparing SmartCess…” state (live-region announcement, no dead end).
- First AI/voice call after a sleep may take up to ~60s. The app retries
  once on connection failure/timeout, then shows “SmartCess is starting.
  Please try again in a moment.” — never a raw server error.
- Requirement 9: only genuine traffic keeps the service awake. There is no
  ping loop in the app or backend; an active voice session counts as
  traffic while it lasts. Do not add synthetic keep-alive: it burns the
  750-hour budget for nothing.

## WebSocket relay design

```
App --WSS--> backend --WSS--> Meta wss://api.meta.ai/v1/asr/realtime
  binary PCM 24kHz mono frames -----------> binary frames
  {"type":"endStream"} -------------------> {"type":"endStream"}
  {"type":"transcript",...} <-------------- transcript frames
  close 1000 <------------------------------ close 1000
```

- Auth: `?token=<access JWT>` or `Authorization: Bearer` header. Bad or
  missing token closes with `4401`; a second concurrent session for the
  same user closes with `4429`; missing Meta key closes with `4502`.
- The backend performs the Meta handshake (key in handshake, never the
  HTTP header), then relays both directions with a 60s idle timeout and a
  5-minute session cap. Either side closing ends the relay cleanly.
- Audio and transcripts exist only in flight; nothing is recorded.

## Cost expectation

$0/month while inside free limits: 1 service × ≤750 hours, no disk, no
paid add-ons. Overages only if you add services, disks, or exceed
bandwidth/usage caps — check the Render dashboard before judging week.

## Rollback and key rotation

- Rollback: Render keeps every deploy; Dashboard → service → “Rollback to
  this deploy” restores the previous image in under a minute. The API is
  versioned (`/health` reports `version`); the app tolerates older
  backends because the contract is additive-only in v1.
- Key rotation: create the new key in the Meta console → Render Dashboard
  → Environment → update `META_API_KEY` → “Save” (auto-redeploys, ~2 min
  of cold start) → revoke the old key in Meta. No app update, no user
  action: sessions are unaffected.
- JWT secret rotation: same flow via `JWT_SECRET`; all users re-sign-in
  once (acceptable, and the reason access tokens live 15 minutes).

## Pre-demo checklist (warm 5–10 minutes before judging)

1. Open `https://<service>.onrender.com/health` — expect
   `{"status":"ok"}` within ~60s (cold start). This wakes the service.
2. On the demo phone (signed in): launch SmartCess, confirm no
   “Preparing SmartCess…” banner remains after a few seconds.
3. Run one short Talk test: “What can you help me with?” → real spoken
   answer. This proves app → backend → Meta end to end.
4. Run one Scan Bill on a sample bill → extracted fields render.
5. Keep the judging gap under ~15 minutes of silence, or repeat step 1
   (free tier sleeps without traffic).

## Local development

```sh
cd backend
python -m venv .venv
.venv\Scripts\activate        # Windows (PowerShell)
pip install -r requirements.txt
copy .env.example .env        # fill in values; never commit .env
uvicorn app.main:app --reload # http://127.0.0.1:8000
pytest                        # mocked Google/Meta; no live calls, no keys needed
```

API docs (dev only): `http://127.0.0.1:8000/docs`.
