# SmartCess

**SmartCess turns inaccessible life admin into independent action.**

SmartCess is a voice-first, TalkBack-first Android life-admin assistant for blind and
low-vision users. It reads bills, summarizes documents, tracks appointments and
routines, and speaks a prioritized Daily Briefing — every action available by both
voice and touch. The frontend is a native Android application (Kotlin + Jetpack
Compose) backed by a Python FastAPI service; there is no web or PWA client.

Current build: `1.0.0-cam.25` — 417 JVM unit tests green, lint reports 0 errors,
keyless debug APK (no API key inside; all AI goes through the backend session).

## Features

| Module | What it does |
|---|---|
| Talk (universal voice) | Push-to-talk anywhere from Home: speak a request, hear the answer. Creates appointments, routines, and reminders; speaks the Daily Briefing; answers saved-bill questions; cancels reminders and archives bills — every committing step reads back details and waits for spoken yes/no |
| Daily Briefing + Home Today | Prioritized list (overdue bills, bills due < 7 days, today's/tomorrow's appointments, routines due). Every row taps through to details and deletes per-item with confirmation |
| Bill Watcher | Guided camera scan with spoken + haptic positioning ("move left", "ready"), tap or voice capture → AI extraction → spoken review (save / retake / delete) → save → optional reminder → bill comparison |
| Email Briefing | Reads the saved on-device inbox. The inbox is currently empty — Gmail is not connected yet — so this section stays dormant; no demo mail ships |
| Document Reader | Scan or import a document: plain summary + Q&A; saved documents can be read back from Talk ("here is the summary", never presented as full text) |
| Appointment Assistant | Voice or manual creation; phone-calendar export and 30/60/90-min travel reminders, each behind its own approval |
| Routine Guardian | Daily routines (medication/delivery/recurring) with reminders and completion tracking. Daily schedules only — weekly/monthly requests are refused with an explanation, never silently scheduled daily. Never gives medical advice |
| Reminders | Voice or manual creation with read-back + confirmation, cancellation with record read-back, scheduled local notifications |

Core mantra: **Transcribe listens, Spark thinks, ML Kit guides, the app acts safely.**

## How voice works

Talk uses push-to-talk over HTTPS, not a live socket:

1. Tap Talk (audible + haptic cue), speak, auto-stop on silence.
2. The recording uploads to our backend, which transcribes it with
   `muse-voice-transcribe-1.0`.
3. Intents and structured extraction run on `muse-spark-1.3`, with an explicit
   `muse-wonder-1.0` fallback (no silent model switching).
4. Android TTS speaks the answer; the same text shows for low-vision users.
5. Follow-up turns stay hands-free: the mic reopens after a 400 ms audio-tail
   guard, up to a cap, then asks for a manual tap.

Live realtime transcription is deferred (the backend keeps a dormant relay for
that future milestone). The Meta API key lives only on the backend.

## Backend

Thin stateless FastAPI proxy (`backend/`) between the app and the Meta Model API:
Google sign-in → session JWTs → chat proxy, bill extraction, and voice
transcription. The backend runs at `https://smartcess-sg.onrender.com` (Render,
Singapore region — Meta chat models 404 from some other Render regions, so the
region is load-bearing). Full endpoint, environment-variable, and
cold-start documentation in [`backend/README.md`](backend/README.md).

## Meta AI Integration & Verification

Every model call runs on the **Meta Model API** (`https://api.meta.ai`), invoked
**only by the backend** — the Android app never contacts Meta directly and never
holds the Meta key in distributed builds.

| Exact model ID | Purpose in SmartCess |
|---|---|
| `muse-spark-1.3` | Primary brain: Talk intent classification, appointment/routine/reminder slot extraction, bill-field extraction from captures, document summaries, saved-bill Q&A |
| `muse-wonder-1.0` | Explicit fallback, called only when a Spark request fails; the outgoing model is logged per request, never switched silently |
| `muse-voice-transcribe-1.0` | Transcription of every push-to-talk recording, including camera "say capture" and spoken review answers |
| `muse-spark-1.2` | Present in the backend allowlist but **never called** (the app falls back 1.3 → Wonder directly) |

Not used: `sam-3.1` and `muse-image-1.0` exist only as off-by-default feature
flags; the app never calls them.

**Request path.** The app sends exactly two AI requests, both authenticated with
its session JWT over HTTPS to the backend:
`POST api/ai/chat` (every Spark call, including bill extraction — the backend's
separate `/api/ai/bill-extract` route exists but the app does not call it) and
`POST api/voice/transcribe` (WAV upload). The backend validates the JWT,
attaches the server-side Meta key, and forwards to
`POST https://api.meta.ai/v1/chat/completions` and
`POST https://api.meta.ai/v1/asr/transcribe` respectively. (A debug-only
developer key slot exists for troubleshooting; test and release builds are
keyless and always use the backend path.)

**Verifying with backend logs.** Each hop is logged; correlate by request ID
(`rid=`). A healthy Talk turn shows all three lines:

```text
httpx … POST https://api.meta.ai/v1/chat/completions "HTTP/1.1 200 OK"
smartcess.ai chat sub=10752449 model=muse-spark-1.3 3650ms in=346 out=948
smartcess.http POST /api/ai/chat -> 200 3651ms rid=4d4d9c394709
```

What the logs expose, honestly:

| Signal | Where | Notes |
|---|---|---|
| Meta endpoint + Meta status | httpx line | Exact URL and Meta HTTP status per call |
| Model ID | `model=` on the `smartcess.ai chat` line | Only the chat path restates it per request |
| App request ID | `rid=` on every `smartcess.http` line | Correlates app→backend hops; Meta's own request IDs are not surfaced |
| App status + latency | `-> 200 3651ms` on the `smartcess.http` line | End-to-end per request |
| Meta latency + tokens | `3650ms in=.. out=..` on the `smartcess.ai` line | Chat only |
| STT file + latency | `stt file sub=.. bytes=.. ms=.. Xms` | Model comes from the `muse-voice-transcribe-1.0` server setting, not restated per line |

Known logging gaps (not claimed otherwise): the bill-extraction success line
does not restate the model (it uses the `muse-spark-1.3` server default), and
the app-side `UsageTracker` collects aggregate per-feature counts/latency in
memory only — it is not displayed anywhere, so **the app has no visible
per-request log; verification happens in the Render backend logs** (Dashboard →
service → Logs, or `render logs`).

## Prerequisites

- Android Studio (Hedgehog or newer) with Android SDK 34
- JDK 17
- A physical device or emulator, API 26+ (Android 8.0); TalkBack for accessibility testing
- Gradle 8.7+ locally, or network access on first build so the wrapper can fetch it

## Setup

1. Clone the repo:
   ```sh
   git clone https://github.com/mauricealam/SmartCess.git
   cd SmartCess
   ```
2. Set up the backend (one free Render service; full guide in `backend/README.md`):
   - Deploy `backend/` to Render with `META_API_KEY`, `JWT_SECRET`, and
     `GOOGLE_SERVER_CLIENT_ID` as environment secrets.
   - Point the app at the backend:
     `BACKEND_URL=https://smartcess-sg.onrender.com/` (process env,
     `local.properties`, or root `.env` — first non-blank wins).
3. Register Google Sign-In (one-time, Google Cloud Console, one project):
   - Create an OAuth client ID of type **Android** for package
     `com.smartcess.app` with your keystore SHA-1 (debug keystore for dev,
     release keystore for production). This registers the app with Google.
   - Create an OAuth client ID of type **Web application** in the same
     project. Its client-ID string is the server client ID: put it in the
     backend's `GOOGLE_SERVER_CLIENT_ID` (Render) and in the app's
     `GOOGLE_SERVER_CLIENT_ID` (root `.env`, `local.properties`, or process
     env at build time). No `google-services.json` is needed.
4. Test APKs must be keyless: build with `KEYLESS_DEBUG=true` so no Meta key
   is baked in (the app uses the backend/session path). A hidden developer
   screen (Settings → tap Version 7 times) can hold a local key for direct-Meta
   debugging only — never distribute such APKs.
5. Open the project in Android Studio and let Gradle sync, or build from the CLI:
   ```sh
   ./gradlew :app:assembleDebug
   ```

## Build

```sh
KEYLESS_DEBUG=true ./gradlew :app:assembleDebug   # keyless debug APK
./gradlew :app:assembleRelease        # release APK/AAB (minified, see proguard-rules.pro)
./gradlew :app:testDebugUnitTest      # JVM unit tests (417)
./gradlew :app:lintDebug              # Android lint (must report no errors)
./gradlew :app:assembleDebugAndroidTest  # instrumented tests compile
```

CI (`.github/workflows/android.yml`) runs build + unit tests + lint +
test-compile on every push/PR to `main`; `nightly.yml` runs instrumented tests
on an emulator.

## Accessibility

SmartCess is TalkBack-first; accessibility issues block release.

- Every screen has a heading, content descriptions, and logical focus order.
- Status and error updates use live regions and are spoken, never shown silently.
- All touch targets are ≥ 48dp; single-column layout with 16dp margins.
- Info is never color-only (urgency chips always carry words).
- Large-text and high-contrast modes are supported; minimum type size is 12sp.
- Voice ↔ touch parity: everything speakable is tappable and vice versa
  (e.g. "Scan my bill" ⇔ the Scan Bill button; capture by tap or by saying "capture").
- Guided scanning speaks one instruction per direction (no nagging repeats)
  with haptic ticks ("Move closer…", "Hold straight…", "Ready. Tap capture or
  say capture").

Manual check: enable TalkBack and complete the full bill flow (talk → scan → review →
save → reminder) using audio alone.

## Offline behavior

SmartCess degrades gracefully with no network:

- Guided camera framing keeps working fully on-device (ML Kit + TTS + haptics).
- OCR falls back to on-device ML Kit; raw text is read aloud.
- Offline output is always labelled **"Offline mode. Please review."**
- Saves are allowed but flagged low-confidence for later review; retry when online.
- Bill capture, briefings, and reminders work from cached local data where possible.

## Limitations

Completed and device-verified:

- Universal Talk executor (appointments, routines, reminders, briefing, bill
  Q&A, cancellation, archive) with read-back + spoken confirmation
- Guided bill/document scanning with voice capture and save / retake / delete
  review, bill reminders, and bill comparison
- Appointments (calendar export, travel reminders), daily routines with
  completion tracking, reminders with scheduled notifications
- Document import + summary + saved-document reading; tappable Home Today rows
  with per-item delete; Settings with JSON/CSV export and full delete
- Offline on-device framing, OCR fallback, and labelled offline drafts

Prototype / dormant (present but not end-to-end):

- Email briefing: the saved-inbox UI exists but no provider is connected, so
  the inbox is empty and the section never renders
- Realtime transcription relay: a dormant backend WebSocket exists; the app
  ships push-to-talk upload only
- SAM 3.1 / Muse Image: off-by-default flags, never called

Planned (not started): Gmail connection, weekly/monthly routine schedules,
opt-in cloud backup, usage trends, additional languages, wearables.

Also note: TalkBack semantics are built in but no full TalkBack-only pass has
been run, and the free Render tier sleeps after ~15 min idle (first request
takes ~30–60s to wake it).

## Project structure

```text
SmartCess/
├── app/src/main/java/com/smartcess/app/
│   ├── core/        # ai (Spark client, prompts, schemas, safety),
│   │                # voice (push-to-talk), media (compression),
│   │                # work (reminders), privacy (export/delete, analytics)
│   ├── data/        # local (Room v2 entities, DAOs) + repository
│   │                # (validation, briefing aggregation)
│   ├── di/          # Hilt modules
│   ├── features/    # appointments, auth, bills, briefing, camera,
│   │                # documents, email, home, onboarding, reminders,
│   │                # routines, settings, voice
│   ├── navigation/  # Routes + nav graph
│   └── ui/          # theme, components, formatters
├── app/src/test/    # JVM unit tests (JUnit4 + MockK)
├── app/src/androidTest/  # instrumented tests (Compose + migration)
├── backend/         # FastAPI proxy (see backend/README.md)
└── README.md ARCHITECTURE.md RELEASE.md PRIVACY.md
```

Further reading: [ARCHITECTURE.md](ARCHITECTURE.md) ·
[RELEASE.md](RELEASE.md) · [PRIVACY.md](PRIVACY.md)

## Safety & privacy

- Prepare–confirm–act: reads/summaries are automatic; saves, reminders, and
  calendar writes happen only after explicit user confirmation.
- The app never pays bills, sends email, submits forms, or places calls automatically.
- No medical, legal, or financial advice; account/card digits are masked in speech.
- Camera and microphone are used only on request; framing runs on-device.
- Storage is local-first with export and full delete in Settings.
- Analytics are opt-in and carry metadata only — no images, message text,
  keys, or PII are ever logged. There is no crash reporter.
