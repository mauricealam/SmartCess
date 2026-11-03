# SmartCess

**SmartCess turns inaccessible life admin into independent action.**

SmartCess is a voice-first, TalkBack-first Android life-admin assistant for blind and
low-vision users. It reads bills, summarizes documents, tracks appointments and
routines, and speaks a prioritized Daily Briefing — every action available by both
voice and touch.

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
transcription. Production runs on Render in Singapore
(`https://smartcess-sg.onrender.com`). Full endpoint, environment-variable, and
cold-start documentation in [`backend/README.md`](backend/README.md).

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
   - The app defaults to `https://smartcess-backend.onrender.com/`. To point
     at the Singapore production service or a local tunnel instead, set
     `BACKEND_URL` (process env, `local.properties`, or root `.env` — first
     non-blank wins).
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
