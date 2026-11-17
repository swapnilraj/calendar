# Tasks

Ordered work for phase 1. Each task is one PR-sized change with a clear "done when". Do them in order:
later tasks assume earlier ones. Tick them off here, in the same PR that completes them.

## Phase 1: day view with weather

- [x] **T1. Project skeleton**
  - Gradle wrapper 9.9.1, `settings.gradle.kts` with modules `:app` and `:weather`, and
    `gradle/libs.versions.toml` with the versions pinned in ARCHITECTURE.md.
  - `:weather` is a Kotlin JVM library. `:app` is an Android application (`dev.swapnilraj.calendar`,
    label "Daylight", minSdk 29, target 36, compile 37.0) with an empty Compose screen.
  - *Done when:* `./gradlew test :app:assembleDebug` passes from a clean clone.
- [ ] **T2. CI.** `.github/workflows/ci.yml` on push/PR to main. It uses JDK 21 (temurin), sets up the
  Android SDK, runs `./gradlew test :app:assembleDebug`, and uploads `app-debug.apk`.
  - *Done when:* the workflow is green on a PR.
- [ ] **T3. Weather: model + Open-Meteo parsing**
  - `Condition.kt` and `OpenMeteo.kt` per WEATHER_REFERENCE.md.
  - Tests port the desktop `wmo` and `openMeteo` tests, and use a fake `fetch`.
  - *Done when:* the tests pass, including malformed-JSON and length-mismatch cases.
- [ ] **T4. Weather: bands, intensity, tint, OKLab**
  - `Bands.kt`, `Tint.kt` and `Oklab.kt`.
  - Tests port the desktop `bands` and `feel` tests, plus OKLab round-trip and lerp end-point tests.
- [ ] **T5. Weather: hours for a day + summary + texture geometry**
  - `DayHours.kt`, `Summary.kt` and `Texture.kt`.
  - Tests cover the alignment rule (block k uses the stamp at k+1), London DST days in March and
    October, missing samples returning null, and the summary rules.
- [ ] **T6. Calendar data**
  - `CalendarRepository` (Instances for a day → `CalEvent`, observer-driven Flow), the
    `READ_CALENDAR` gate UI, and `EventLayout` with unit tests.
  - *Done when:* the event list for "today" logs correctly on a device.
- [ ] **T7. Day grid (no weather yet)**
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
- [ ] **T9. Polish pass on a real phone**
  - Compare side by side with the stock calendar app's day view: spacing, typography, paging feel and
    scroll restore.
  - Tune the light-theme tint, and record the new values in WEATHER_REFERENCE.md.

## Later

Phase 2 (events) and phase 3 (week view) are specified in SPEC.md. Break them into tasks here when
phase 1 is done.
