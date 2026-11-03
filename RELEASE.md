# SmartCess Release Guide (v1.0.0 — hackathon submission)

## Release package

Build the artifacts on a host with the Android SDK (see README setup):

```sh
KEYLESS_DEBUG=true ./gradlew :app:assembleDebug   # keyless test APK
./gradlew :app:bundleRelease          # app/build/outputs/bundle/release/*.aab
./gradlew :app:testDebugUnitTest      # unit-test report (417 tests)
```

Submission package (Build for Your World, Indie track — due Nov 4, 9PM ET):

- [ ] Public GitHub repo with README (setup, build, accessibility, offline behavior)
- [ ] 3-minute demo video (demo script is kept local-only, not in this repo)
- [ ] Keyless debug APK attached to the GitHub release for judges (release AAB optional)
- [ ] No secrets in the repo: verify `local.properties`, `.env`, and keystores are
      absent (`git status --ignored`; `.gitignore` covers them)

## GitHub repo structure

```text
SmartCess/
├── README.md ARCHITECTURE.md RELEASE.md PRIVACY.md
├── .github/workflows/android.yml   # CI: build + unit tests + lint + test-compile
├── .github/workflows/nightly.yml   # nightly emulator: instrumented tests
├── settings.gradle.kts  build.gradle.kts  gradle.properties
├── gradle/  local.properties.example  .gitignore
├── backend/  # FastAPI proxy (see backend/README.md)
└── app/
    ├── build.gradle.kts  proguard-rules.pro  schemas/  # Room schema history
    └── src/
        ├── main/   # AndroidManifest, Kotlin sources, res/
        ├── test/   # JVM unit tests (JUnit4 + MockK)
        └── androidTest/  # instrumented tests (Compose + migration)
```

## CI/CD readiness

`.github/workflows/android.yml` runs on every push/PR to `main`:

1. `gradle :app:assembleDebug` (no key needed — keyless builds run offline-capable)
2. `gradle :app:testDebugUnitTest`
3. `gradle :app:lintDebug` (must report no errors)
4. `gradle :app:assembleDebugAndroidTest` (instrumented tests compile)
5. Uploads APK + test report as artifacts

`.github/workflows/nightly.yml` runs instrumented tests (incl. the Room
migration test) on an API 29 emulator, nightly + on demand.

Secrets: set `META_API_KEY` in GitHub Actions secrets only if a CI lane ever needs
live AI calls; the default lanes are hermetic. Release signing: add a `release`
lane with `KEYSTORE_*` secrets when cutting a store build; the hackathon ships a
keyless debug APK + optional signed AAB.

## Performance budgets (must verify on a mid-range device)

| Budget | Target | How to check |
|---|---|---|
| Cold start | < 2s | Logcat `Displayed` timing, mid-range device |
| Voice → intent | < 3s after upload | STT final → intent log timestamps |
| Framing guidance | < 500ms / instruction | Frame-analyze → speak latency |
| Scan → result | < 30s | Capture tap → Bill Result render |
| Doc → summary (1pp) | < 45s | Import → summary render |
| Briefing | < 10s | Open → spoken summary start |
| Scan peak memory | < 300MB | Android Studio profiler |
| APK size | < 50MB | `ls -la` on release APK |
| Background drain | negligible | No background camera/work except scheduled reminders |

Optimizations already in place: image compression, sampled-frame analysis,
response caching, concurrent-call and per-minute AI limits, retry-with-backoff.
Not yet implemented: briefing caching, voice debounce, list pagination.
Note: the first request after the free backend sleeps pays a ~30–60s cold
start — warm it before judging (see `backend/README.md` pre-demo checklist).

## Device support

- Min API 26 (Android 8.0), target SDK 34; portrait phones primary
- Latest TalkBack; rear camera with autofocus preferred; microphone required
- Must run on 3GB RAM — test on one mid-range + one low-end device + emulator CI
- ML Kit GMS build: requires Play Services + first-run model download; without it
  the app degrades to manual capture with a spoken hint

## Known limitations (disclose with the submission)

- Email inbox is empty until Gmail connects — no demo mail ships.
- Routines support daily schedules only (weekly/monthly refused with explanation).
- Live realtime transcription is deferred; Talk uses push-to-talk upload.
- TalkBack semantics are built in but no full TalkBack-only pass has been run.
- Render free tier sleeps after ~15 min idle; first request wakes it slowly.

## Roadmap structure

- **Next (post-hackathon):** on-device framing-threshold tuning, AI-eval sample
  packs (20 bills / 10 docs / 10 appt+routine + framing cases), signed release
  keystore + AAB
- **3 months:** Gmail/Outlook, multi-page PDF, opt-in cloud backup, trends, SAM refine
- **6 months:** traffic/maps travel times, caregiver sharing, BM/ZH-CN/Tamil, wearables
- **12 months:** approval-gated payments, forms/tables, community features

## Risk mitigations

| Risk | Mitigation |
|---|---|
| Meta Model API unreachable / key absent | Fail-closed offline mode with labelled drafts; backend proxy with explicit model config; overrides via env |
| Invalid AI JSON | Retry once, then safe default + warning; schemas validated |
| Low-confidence extraction | 0.70 gate forces mandatory review + warning banner |
| No network | Full on-device framing + OCR + TTS, labelled "Offline mode. Please review." |
| Accidental side effects | Approval-gated actions; banned actions (pay/email-submit/call) have no code path |
| Medical-advice / crisis content | Refusal + supportive-crisis response via ContentSafety on AI output |
| Secret leakage | Meta key server-side only; test builds forced keyless (`KEYLESS_DEBUG=true`); never logged; CI hermetic |
| Regression | 417 unit tests + versioned prompts; lint must report no errors |

## User feedback hooks

- In-app: Settings → Send feedback (stored on-device; support address plugs in
  later); every error is visible + spoken with a next step (note which error
  for reports)
- GitHub Issues templates (bug / accessibility / idea) + a short post-demo survey
  link in the release notes
- Co-design loop with a blind TalkBack user: TalkBack-only walkthrough of each
  release; accessibility issues block the next release
- Opt-in analytics events (`voice_command`, `framing_guidance`, `error_shown`, …)
  carry metadata only — use aggregated funnels, never message content
