# Tasks

Current work is the daily-driver goal below. Each checkbox requires its acceptance evidence, not just
source or a passing build. Preserve the historical phase-1 checks below; their unverified phone gates
remain pending. [SPEC.md](SPEC.md) is the behaviour contract and
[DAILY_DRIVER_PROGRESS.md](DAILY_DRIVER_PROGRESS.md) records current status.

## Current Wave 0 — first implementation packet

- [ ] **DD-00 Bootstrap:** carry approved scope into docs, add pure `:calendar-core`, establish tested
  identity/date contracts and build/wiring seams. Done when baseline and new contract tests compile
  and pass with debug build/lint. In progress; do not mark complete before lead verification.
- [ ] **M0 compatibility:** dedicated personal/Workspace test-account harness and organizer/attendee
  checks for mutations, offline sync, recurrence and reminders. Real-device/account evidence pending.

## Parallel ownership

| Owner | Exclusive work |
|---|---|
| Lead | Contracts/module wiring, composition/navigation, Gradle/manifest/CI, shared docs, integration/release |
| A | Provider metadata/ranges/details/search/writes and SQLite schema/migrations/draft/journal/ledger |
| B | Pure temporal geometry, validation, recurrence transformations and reminder calculations |
| C | Day fixes, sidebar, views/date navigation/search/preferences and weather presentation |
| D | Native details/editor, draft/recovery, guest/RSVP and recurrence-scope UI |
| E | Alarms/jobs/receivers/notifications/snooze, reminder setup, widget and intent decoder |
| F | Independent integration/device/accessibility/performance verification and controlled fixtures |

Each feature owner writes focused tests; F independently validates, not replaces them. After the
bounded shared-checkout bootstrap, use isolated worktrees and fresh task branches from the latest
integration commit. Lead integrates small reviewed PRs. Contracts change through the lead; A alone
owns migrations. Use separate feature strings/tests/screenshot names. At most two focused Gradle
runs; serialize full builds and screenshot recording. Shared SDK setup happens once.

Each task packet states base commit, owner, allowed files, dependencies, user-visible acceptance,
tests, wiring requests and handoff commit/PR. Reports identify remaining fakes and external checks.

## Wave 1 — independent foundations

- [ ] A: metadata/capabilities, shared range/detail reads and initial persistence.
- [ ] B: all-day/time interval rules, DST segments, validation and logical time anchors.
- [ ] C: overflow/large-font/RTL fixes and fixture-backed sidebar/navigation.
- [ ] D: injected native details/editor shells.
- [ ] E: reminder setup/scheduler/notification shells with explicit fake ports.
- [ ] F: adversarial provider and cross-feature contract checks.
- [ ] Integration gate: live read-only day → details, corrected D1–D5 and verified shared observation.

## Wave 2 — planning and ordinary writes

- [ ] A: batched one-off writes, conflict/journal recovery and persistent drafts.
- [ ] B: recurrence plans and timed/all-day reminder calculations.
- [ ] C: persisted filters and complete month/week/three-day/agenda date state.
- [ ] D: live native create/edit/delete/duplicate and draft/conflict recovery.
- [ ] E: persistent alarm planning/snooze/reconciliation and incoming intents.
- [ ] F: offline/process-death/permission/duplicate-submit integrated tests.
- [ ] Integration gate: create/edit/delete refresh every view and reminder plan; offline reconnect
  yields one intended Google event. No auto-retry of uncertain creates.

## Wave 3 — recurring meetings and completion

- [ ] B → A → D: pure one/future/all plans → provider batches → eligible UI actions; F verifies
  occurrence sets and Google round-trip, preserving exceptions/limits/zones.
- [ ] A + D + F: guests/RSVP/organizer updates and cancellation verified in the second account.
- [ ] C: search/range controls/preferences and dense-calendar access.
- [ ] E: agenda widget, notification actions, reminder-health and lifecycle recovery.
- [ ] Lead: occurrence-safe navigation from notifications/widgets/intents.
- [ ] Integration gate: recurrence and two-account propagation pass; reboot/timezone/upgrade reminders
  and search/external links reach the correct occurrence.

## Wave 4 — stabilization and release

- [ ] Required full test/debug build/lint and inspected screenshot baselines.
- [ ] Physical-device Google/Workspace, TalkBack/font/permission/Doze/reboot/upgrade evidence.
- [ ] Performance on a declared device: no main-thread provider queries, observer leaks, unbounded
  loading/cache; target warm view changes <300ms and first local range <1s on dense fixtures.
- [ ] Seven-day work/personal beta including weekend, offline period, remote edits, recurring meetings,
  reminders and upgrade; no data-loss/duplicate/scope/eligible-alert/propagation blockers.
- [ ] Stable signing outside Git; fresh-install/upgrade verification; durable GitHub release APK,
  checksum and release notes. Audit requirements reconciled with evidence.

If external access is missing, continue independent work and leave gates pending. A fake-backed UI,
local write or process-only alarm does not pass live release acceptance.

## Historical phase 1: day view with weather

- [x] **T1. Project skeleton**
  - Gradle wrapper 9.9.1, `settings.gradle.kts` with modules `:app` and `:weather`, and
    `gradle/libs.versions.toml` with the versions pinned in ARCHITECTURE.md.
  - `:weather` is a Kotlin JVM library. `:app` is an Android application (`dev.swapnilraj.calendar`,
    label "Daylight", minSdk 29, target 36, compile 37.0) with an empty Compose screen.
  - *Done when:* `./gradlew test :app:assembleDebug` passes from a clean clone.
- [x] **T2. CI.** `.github/workflows/ci.yml` on push/PR to main. It uses JDK 21 (temurin), sets up the
  Android SDK, runs `./gradlew test :app:assembleDebug`, and uploads `app-debug.apk`.
  - *Done when:* the workflow is green on a PR.
  - Verified on PR #1: Actions run 44117076549 (JDK 21, tests, debug APK artifact).
- [x] **T3. Weather: model + Open-Meteo parsing**
  - `Condition.kt` and `OpenMeteo.kt` per WEATHER_REFERENCE.md.
  - Tests port the desktop `wmo` and `openMeteo` tests, and use a fake `fetch`.
  - *Done when:* the tests pass, including malformed-JSON and length-mismatch cases.
- [x] **T4. Weather: bands, intensity, tint, OKLab**
  - `Bands.kt`, `Tint.kt` and `Oklab.kt`.
  - Tests port the desktop `bands` and `feel` tests, plus OKLab round-trip and lerp end-point tests.
- [x] **T5. Weather: hours for a day + summary + texture geometry**
  - `DayHours.kt`, `Summary.kt` and `Texture.kt`.
  - Tests cover the alignment rule (block k uses the stamp at k+1), London DST days in March and
    October, missing samples returning null, and the summary rules.
- [ ] **T6. Calendar data**
  - `CalendarRepository` (Instances for a day → `CalEvent`, observer-driven Flow), the
    `READ_CALENDAR` gate UI, and `EventLayout` with unit tests.
  - Implemented and tested with a synthetic CalendarContract provider, observer refresh, UTC all-day
    boundaries, midnight clipping, and overlap clusters. Real-device comparison is still pending.
  - *Done when:* today's events match the system calendar on a device (never log event contents).
- [x] **T7. Day grid (no weather yet)**
  - `DayScreen` with a `HorizontalPager`, then `DayPage`, `DayHeader`, `DayGrid`, `EventBlock` and
    `NowLine`.
  - Includes the shared vertical scroll, pinch zoom (persisted), the today button, and opening events
    in the system calendar.
  - Adds Roborazzi screenshots for overlaps, all-day events and today, in light and dark.
- [ ] **T8. Weather layer**
  - `LocationSource`, `WeatherRepository`, `WeatherBackground` (OKLab gradient + texture), and the
    header summary chip.
  - Adds screenshots for a dry day, a rainy evening and a snowy morning, in light and dark.
  - *Done when:* the SPEC phase 1 acceptance list holds on a real phone.
  - Implementation, cache/offline tests and six weather screenshots are included; real-phone
    acceptance remains pending, so this checkbox intentionally stays open.
- [ ] **T9. Polish pass on a real phone**
  - Compare side by side with the stock calendar app's day view: spacing, typography, paging feel and
    scroll restore.
  - Tune the light-theme tint, and record the new values in WEATHER_REFERENCE.md.

## Historical roadmap

The original later-phase placeholder is superseded by the daily-driver waves above.
