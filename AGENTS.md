# Agent guide

Instructions for any coding agent working in this repo (Claude Code, Codex, …), locally or in the cloud.

## Read first

1. [docs/SPEC.md](docs/SPEC.md): what the app does. It is the source of truth for behaviour.
2. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the stack, pinned versions, modules and algorithms.
3. [docs/WEATHER_REFERENCE.md](docs/WEATHER_REFERENCE.md): every weather constant and rule. Don't
   invent new ones.
4. [docs/TASKS.md](docs/TASKS.md): the ordered work. Pick the first unchecked task unless told otherwise.

## Rules

- **Scope.** Do one task per change, and don't silently expand scope. If the spec is ambiguous, or seems
  wrong, say so in your summary or PR instead of guessing. When behaviour changes, update SPEC.md in the
  same change.
- **Weather.**
  - Weather logic lives in `:weather` and must stay pure Kotlin/JVM: no `android.*` imports.
  - It must match the desktop prototype. Port its tests, don't reinvent them.
- **Events always win.** Never draw weather above hour lines or events, and never tint event colours.
- **Branding.** Familiar UX, no copying. Don't use Google's (or anyone's) logos, icons, fonts, colours
  as brand assets, or product names in the UI.
- **Privacy.**
  - No analytics, crash reporters or network calls other than Open-Meteo.
  - Never log event titles, attendees or locations, even in debug builds.
  - Round coordinates to 2 decimals before sending them.
- **Dependencies.** Use the versions pinned in ARCHITECTURE.md. Adding a dependency needs a line in the
  decisions log explaining why.
- **Before finishing:** run `./gradlew test :app:assembleDebug` and make sure it passes. If you changed UI,
  re-record the affected screenshots (`./gradlew recordRoborazziDebug`), look at them, and mention which
  ones changed.
- **Git.**
  - Work on a branch. Don't push to `main` or force-push unless the user asks.
  - Commit messages explain why, not just what.
  - Never commit keystores, `local.properties` or secrets.

## Environment

- **JDK 21** runs Gradle, and bytecode targets 17.
- **Android SDK:** set `ANDROID_HOME`, or let `scripts/setup-android-sdk.sh` install the platform-37.0 SDK.
  It writes `local.properties` (git-ignored).
- **Cloud sessions** (Linux, no phone, no emulator):
  - Run `scripts/setup-android-sdk.sh` first. It needs network access to `dl.google.com`, Maven Central
    and the Gradle distribution host.
  - All tests are JVM-only, so `./gradlew test` works there. Screenshot tests are how you check UI
    changes without a device.
  - You can't install on the owner's phone from the cloud. Say so instead of trying.
- **Local sessions** with the owner's phone on adb (wireless debugging): `./gradlew :app:installDebug`.
  The app installs alongside the stock calendar app. Native save/delete controls write to the calendar
  store. Automated verification must use dedicated authorized fixtures, not real meetings; never
  screenshot real events into commits or PRs.
