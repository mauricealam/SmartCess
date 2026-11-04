# Contributing to SmartCess

Thanks for helping make life admin accessible. Please read this before opening
a pull request.

## Getting started

1. Follow README "Prerequisites" and "Setup" (Android Studio, JDK 17,
   `BACKEND_URL`, `GOOGLE_SERVER_CLIENT_ID`).
2. Build keyless: `KEYLESS_DEBUG=true ./gradlew :app:assembleDebug`.
3. Run the gates before every push (below).

## Required gates (all must pass)

```sh
./gradlew :app:testDebugUnitTest   # 417 JVM unit tests, 0 failures
./gradlew :app:lintDebug           # must report no errors
```

- New behavior needs a unit test next to the code it covers
  (`app/src/test/...`, JUnit4 + MockK). Mocked tests prove logic; say in the
  PR whether you also verified on-device.
- Keep the working voice/scan flows green — do not weaken existing tests to
  fit a change.

## Secrets — non-negotiable

- Never commit API keys, `.env` contents, `local.properties`, keystores, or
  `google-services.json` (all git-ignored; keep them that way).
- Test builds must stay keyless (`KEYLESS_DEBUG=true`). Never log keys,
  tokens, audio, transcripts, images, or message contents.
- The Meta key lives only in the backend environment. PRs touching key
  handling get extra review.

## Accessibility is a release blocker

- Every screen: heading first, logical focus order, content descriptions,
  live regions for status/errors (spoken, never silent-only).
- Touch targets ≥ 48dp, single column, 16dp margins, info never color-only.
- Voice ↔ touch parity: anything speakable must be tappable and vice versa.
- Verify with TalkBack on a device for any UI change.

## Safety rules for actions

- Reads and summaries are automatic; **saves, reminders, calendar writes,
  archives, and deletes require explicit user confirmation** (dialog or spoken
  yes/no) — never add a path that skips it.
- Banned actions have no code path and must stay that way: paying bills,
  sending email, submitting forms, navigating, placing calls.
- No medical, legal, or financial advice anywhere, including prompts and
  fallback strings.

## Pull requests

- One focused change per PR; describe what you verified (tests / lint /
  device, with build ID).
- Match the existing architecture (MVVM + Repository, Hilt, StateFlow, Room
  for persistence) — no new frameworks without discussion.
- Update README/ARCHITECTURE/RELEASE if behavior, setup, models, or limits change.

## Reporting issues

Include: build ID (Settings → Version), steps to reproduce, what you expected
vs. what happened, and whether TalkBack was on. Never paste keys, tokens, or
personal bill/document contents into issues.
