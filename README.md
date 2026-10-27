# SmartCess

**SmartCess turns inaccessible life admin into independent action.**

SmartCess is a voice-first, TalkBack-first Android life-admin assistant for blind and
low-vision users. It reads bills, summarizes email and documents, tracks appointments
and routines, and speaks a prioritized Daily Briefing — every action available by both
voice and touch.

Built for the **Build for Your World** hackathon (Indie track) by Maurice, co-designed
and tested with Hillarry.

## Features (6 modules)

| Module | What it does |
|---|---|
| Daily Briefing | Prioritized spoken briefing: overdue bills, bills due < 7 days, today's/tomorrow's appointments, urgent email, document deadlines, routines due |
| Bill Watcher | Guided camera scan → extract → validate → review → compare → save/archive → remind |
| Email Briefing | Local demo inbox: summarize, prioritize, deadlines, reminders. Never sends, replies, deletes, or forwards |
| Document Reader | Scan/import PDF + image (1 page first): plain summary + Q&A |
| Appointment Assistant | Extract details, calendar event + 30/60/90-min travel reminders after approval |
| Routine Guardian | Medication/delivery/recurring tracking with reminders. Never gives medical advice |

Core mantra: **Transcribe listens, Spark thinks, ML Kit guides, the app acts safely.**

## Tech stack

Kotlin, Jetpack Compose + Material 3, MVVM + Repository, Hilt, Coroutines/Flow, Room,
WorkManager, CameraX, ML Kit (on-device framing + offline OCR), Android TTS/Vibrator,
Retrofit/OkHttp + kotlinx.serialization. AI: Muse Voice Transcribe (speech-to-text)
and Muse Spark (structured-output reasoning). See [ARCHITECTURE.md](ARCHITECTURE.md).

## Prerequisites

- Android Studio (Hedgehog or newer) with Android SDK 34
- JDK 17
- A physical device or emulator, API 26+ (Android 8.0), with TalkBack for accessibility testing

## Setup

1. Clone the repo:
   ```sh
   git clone https://github.com/<your-org>/SmartCess.git
   cd SmartCess
   ```
2. Create your local API-key file (git-ignored, never commit it):
   ```sh
   cp local.properties.example local.properties
   ```
   Then edit `local.properties`:
   ```properties
   META_API_KEY=<your-meta-model-api-key>
   # Optional endpoint overrides (defaults are the official Meta endpoints):
   # META_API_BASE_URL=https://...
   # META_STT_URL=wss://...
   ```
   You can also enter or override the key inside the app: **Settings → API key**
   (stored in EncryptedSharedPreferences; app-supplied key wins over BuildConfig).
   CI builds keyless, and release builds never embed a key.
3. Open the project in Android Studio and let Gradle sync, or build from the CLI:
   ```sh
   ./gradlew :app:assembleDebug
   ```
   (The Gradle wrapper is committed to the repo; no local Gradle install needed.)

## Build

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK/AAB (minified, see proguard-rules.pro)
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint (must be clean)
./gradlew :app:connectedDebugAndroidTest  # instrumented tests on a device/emulator
```

## Accessibility

SmartCess is TalkBack-first; accessibility issues block release.

- Every screen has a heading, content descriptions, and logical focus order.
- Status and error updates use live regions and are spoken, never shown silently.
- All touch targets are ≥ 48dp; single-column layout with 16dp margins.
- Info is never color-only (urgency chips always carry words).
- Large-text and high-contrast modes are supported; minimum type size is 12sp.
- Voice ↔ touch parity: everything speakable is tappable and vice versa
  (e.g. "Scan my bill" ⇔ the Scan Bill button; capture by tap or by saying "capture").
- Guided scanning speaks one instruction at a time with haptic ticks
  ("Move closer…", "Hold straight…", "Ready. Tap capture or say capture").

Manual check: enable TalkBack and complete the full bill flow (talk → scan → review →
save → reminder) using audio alone.

## Offline behavior

SmartCess degrades gracefully with no network or no API key:

- Guided camera framing keeps working fully on-device (ML Kit + TTS + haptics).
- OCR falls back to on-device ML Kit; raw text is read aloud.
- Offline output is always labelled **"Offline mode. Please review."**
- Saves are allowed but flagged low-confidence for later review; retry when online.
- Bill capture, briefings, and reminders work from cached local data where possible.

## Project structure

```text
app/src/main/java/com/smartcess/app/
├── core/        # ai (Spark client, prompts, schemas, tools, safety),
│                # voice (STT), media (compression), work (reminders),
│                # privacy (export/delete, analytics), config, serialization
├── data/        # local (Room entities, DAOs) + repository (validation, rules)
├── di/          # Hilt modules
├── features/    # home, voice, camera, briefing, bills, reminders, email,
│                # documents, appointments, routines, settings, more, onboarding
├── navigation/  # Routes + nav graph
└── ui/          # theme, components, formatters
```

Further reading: [ARCHITECTURE.md](ARCHITECTURE.md) ·
[DEMO_SCRIPT.md](DEMO_SCRIPT.md) · [RELEASE.md](RELEASE.md) · [PRIVACY.md](PRIVACY.md)

## Safety & privacy

- Prepare–confirm–act: reads/summaries are automatic; saves, reminders, and
  calendar writes happen only after explicit user confirmation.
- The app never pays bills, sends email, submits forms, or places calls automatically.
- No medical, legal, or financial advice; account/card digits are masked in speech.
- Camera and microphone are used only on request; framing runs on-device.
- Storage is local-first with export and full delete in Settings.
- Analytics and crash reporting are opt-in and carry metadata only — no images,
  message text, keys, or PII are ever logged.
