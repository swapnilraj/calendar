# Architecture

How Daylight is built. [SPEC.md](SPEC.md) says *what* it does; this file says *how*. Keep both current.

## Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| UI | Jetpack Compose + Material 3 | Dynamic colour (Material You) on Android 12+; light and dark themes follow the system |
| Calendar data | `CalendarContract` via `ContentResolver` | Shared bounded reads use `Instances`; native writes use asserted batches and durable operation journals. Installed account sync remains external. |
| Weather | Open-Meteo over `HttpURLConnection` | No API key, and no HTTP library needed |
| JSON | kotlinx.serialization (`JsonElement` parsing) | |
| Async | Coroutines + Flow | |
| Settings | DataStore Preferences | App-local display/filter/default-calendar preferences and independent reminder policy; widget configuration uses its own durable namespace. |
| DI | None. Constructor injection, wired in `CalendarApp`. | Revisit only if wiring gets painful. |
| Build | Gradle Kotlin DSL, version catalog, AGP 9 (built-in Kotlin) | JDK 21 to run Gradle; bytecode target 17 |
| Tests | JUnit 4; Robolectric + Roborazzi for screenshot tests | All tests run on the JVM, with no device or emulator, so they work in cloud sessions |
| SDK | `minSdk 29`, `compileSdk 37.0`, `targetSdk 36` | Compose's pinned version requires compile SDK 37 |

### Pinned versions

These were current stable on 2026-11-17. Bump them deliberately, never casually, and record any bump in
the PR.

| Component | Version |
|---|---|
| Android Gradle Plugin (AGP) | 9.5.1 |
| Gradle wrapper | 9.9.1 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| activity-compose | 1.13.0 |
| lifecycle | 2.11.0 |
| core-ktx | 1.19.1 |
| coroutines | 1.11.0 |
| kotlinx-serialization-json | 1.13.0 |
| datastore-preferences | 1.3.0 |
| junit | 4.13.2 |
| androidx.test.ext:junit | 1.3.0 |
| robolectric | 4.18 |
| roborazzi | 1.79.0 |

On AGP 9, Kotlin support is built in. **Do not** apply `org.jetbrains.kotlin.android`. Do apply
`org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` where needed.

## Daily-driver architecture (approved target; bootstrap in progress)

The existing app below describes the read-only baseline. Wave 0 is adding `:calendar-core`, a pure
Kotlin/JVM module for shared calendar identity/date contracts and later geometry, validation,
recurrence plans and reminder calculations. It must contain no Android imports. The lead owns module
wiring; initial contracts are being implemented, not yet a complete repository/editor API.

Target boundaries:

Current live read path: `CalendarApp.calendarReader` → one active-view range in `LiveCalendarDay`
→ shared occurrence presentation → native details. Day, three-day, week, month and agenda share
app-local filters; declined filtering is presentation-only. Details retain exact occurrence refs.
Provider invalidation refreshes shared ranges. The old `CalendarRepository` remains legacy code/tests.
One atomic preference snapshot supplies theme, display zone, live settings, selection/default/view/zoom;
weather resolution uses the device zone while forecast samples group in the selected display zone.
Ordinary native writes are wired; scoped series, widget and reminder runtime components await their
remaining application integration gates.

- Android provider adapters share range invalidation and expose calendar metadata, details and typed
  mutation outcomes. Display segmentation is separate from occurrence/master/original-instance identity.
- Pure recurrence plans are interpreted by one Android batch writer. `org.dmfs:lib-recur:0.17.1` is
  pinned in `:calendar-core` for recurrence parsing/transformation; provider-expanded Instances remain
  the display source. Recurrence implementation and provider verification remain in progress.
- One DataStore instance holds preferences. Versioned private SQLite holds drafts, operation recovery,
  delivery state and snoozes; it is not a competing calendar event database.
- Mutation outcomes include local success, conflict, permission/provider failure and uncertain outcome.
  Unknown create outcomes must be reconciled, never blindly retried.
- Reminder delivery contracts bind each audible post to a durable per-post token, separate from the
  generation alias. Snooze/dismiss actions must match that token; stale notification actions cannot
  act on a later post. Series mutation ports require a complete selected-bound source and fresh token;
  UI-generated recurrence plans are never accepted as provider execution authority. Nullable privacy/
  availability patches distinguish Keep from an explicit clear and preserve unknown source values.
- AlarmManager owns reminder delivery; JobScheduler reconciles, alongside provider reminder signals.
  The notification ledger is independent from display selection. Background/device behaviour remains
  unverified until the explicit acceptance gates pass.
- Lead owns shared contracts, composition/navigation, Gradle, manifest, CI and release wiring. Feature
  agents inject ports rather than reaching across ownership or accessing ContentResolver from UI.
- Keep existing `:weather` rules/constants unchanged. Pure transition geometry can mask ambiguous
  weather bands in Android presentation without changing normal-day weather calculations.

See [SPEC.md](SPEC.md) for behaviour, [TASKS.md](TASKS.md) for parallel waves and
[DAILY_DRIVER_PROGRESS.md](DAILY_DRIVER_PROGRESS.md) for completed versus pending evidence.

## Baseline modules

```
calendar/
├─ weather/                  pure Kotlin/JVM library. No Android imports, ever.
│  └─ src/main/kotlin/dev/swapnilraj/calendar/weather/
│     ├─ Condition.kt        Condition enum, WMO code → condition/label, severity
│     ├─ OpenMeteo.kt        URL builders + JSON parsing (forecast, geocoding); takes a `fetch: (String) -> String`
│     ├─ Bands.kt            DayHour, SkyBand, TempBand, precipIntensity, precipLabel, wetnessAtLines
│     ├─ Tint.kt             tintFor(hour, theme, wet) → Rgba; the colour constants
│     ├─ Oklab.kt            sRGB ↔ OKLab, lerp(Rgba, Rgba, t) in OKLab with premultiplied alpha
│     ├─ DayHours.kt         hoursForDay(forecast, LocalDate, ZoneId) → 25 DayHours (00:00…24:00), DST-aware
│     ├─ Texture.kt          per-hour-block rain/snow intensity + rowAlpha(y) interpolation (geometry only, no drawing)
│     └─ Summary.kt          daySummary(...) → icon condition, hi/lo, max rain %
│
└─ app/                      Android application, `dev.swapnilraj.calendar`
   └─ src/main/kotlin/dev/swapnilraj/calendar/
      ├─ MainActivity.kt     edge-to-edge, setContent { DaylightTheme { DayScreen(...) } }
      ├─ CalendarApp.kt      Application: builds repositories once (manual DI)
      ├─ data/
      │  ├─ CalendarRepository.kt   Instances query for [start, end) → List<CalEvent>; Flow that re-emits on ContentObserver
      │  ├─ WeatherRepository.kt    location → forecast with 30-min memory cache + stale fallback; Flow<WeatherState>
      │  ├─ LocationSource.kt       last known coarse location (LocationManager, no Play Services) → tz-city fallback
      │  └─ Settings.kt             DataStore: hour height
      └─ ui/
         ├─ theme/                  DaylightTheme (dynamic colour, typography)
         ├─ DayScreen.kt            top bar + HorizontalPager of DayPage; owns shared scroll + zoom state
         ├─ DayPage.kt              header + all-day row + scrollable DayGrid for one date
         ├─ DayGrid.kt              gutter labels, WeatherBackground, hour lines, EventBlocks, NowLine
         ├─ WeatherBackground.kt    Modifier.drawBehind: OKLab gradient + streak/dot texture from the weather module
         ├─ EventLayout.kt          pure function: events → (column, columns) per event (overlap clusters)
         ├─ EventBlock.kt           rounded block, contrast text, declined styling
         ├─ DayHeader.kt            weekday/date (today circle) + weather summary chip
         └─ PermissionGate.kt       READ_CALENDAR / location rationale + request
```

**Why a separate `weather` module:**
- All the weather rules are pure functions, so they're testable on the JVM in milliseconds.
- They are the same rules as the desktop prototype. Keeping Android out of them keeps porting and
  comparing honest.
- The Android side only *draws* what the module computes.

## Data flow

```
              ┌──────────────── DayScreen (date = pager page) ───────────────┐
CalendarRepository.events(date) ─┐                                           │
                                 ├─► DayPage(date, events, weather) ─► DayGrid
WeatherRepository.forecast ──────┘        │
   (hourly list, tz)                      └─ weather.hoursForDay(forecast, date, zone)
                                              ├─ tintFor(...) at 25 lines ─► gradient stops (OKLab-interpolated)
                                              ├─ block intensities       ─► streak rows
                                              └─ daySummary(...)          ─► header chip
```

- **Shared state:** `DayScreen` owns the vertical `ScrollState` value and the hour height, so paging
  keeps the same hours in view.
- **Hoisting:** each `DayPage` is stateless apart from what it is passed.
- **Repository flows:** collect them with `collectAsStateWithLifecycle`.

## Key algorithms

### Hours for a day (DST-aware)

- **Instants:** resolve each of the 24 local clock-hour lines in the zone, followed by the next
  local midnight. Do not add 24 elapsed hours to the first midnight: DST days last 23 or 25 hours.
  Gaps resolve forward and overlaps use the later offset, matching the desktop conversion.
- **Grid position:** the grid maps hour row r to local clock time r:00, so convert with
  `date.atTime(r, 0).atZone(zone).withLaterOffsetAtOverlap()` for r in 0..23, plus the next day's midnight for 24.
- **Picking a sample:** for each instant, take the forecast sample whose `time` equals it, and if none
  does, the nearest one within 30 minutes.
- **Missing samples:** if any of the 25 is missing, return null, which means no weather for that day.
  The desktop rule is the same.

### Gradient

- **Colours:** compute 25 `Rgba` tints at the hour lines.
- **Stops:** Compose's `Brush.verticalGradient` interpolates in sRGB, so pre-interpolate in OKLab. Use
  4 sub-stops per hour (97 stops), each `lerpOklab(tint[i], tint[i+1], s/4)`.
- **Painting:** paint the gradient over the full grid height with `drawRect`.

### Texture

- **Intensity:** block k (hour k→k+1) uses `precipIntensity(hours[k+1])`.
- **Fading between hours:** row alpha at y is interpolated linearly between block centres
  `(k + 0.5) * hourPx`, and clamped at the ends.
- **Rain pattern:** a 12×18 dp tile with two strokes (`M9 1.5 L6 9.5` and `M3.5 10.5 L1 16.5`),
  1 dp wide with round caps.
- **Drawing:** draw tile rows top to bottom, tiled across the width, and skip rows whose alpha is 0.
- **Snow:** a 12×12 tile with dots at (3,3) r1.1 and (9.5,9) r0.9.

### Event layout

1. Sort by (start, −duration).
2. Sweep to form clusters of transitively overlapping events.
3. Inside each cluster, place events greedily into the first column whose last event has ended.
4. Width = cluster width / column count.

Zero-length and very short events render with a minimum height of 20 dp (18.75 minutes at the
default 64dp/hour zoom), shifted inside the last row if necessary. Minimum visible height also
participates in overlap detection.

## Testing

| Layer | Test |
|---|---|
| `weather` | JUnit for everything. Port the desktop tests from `test/bands.test.ts`, `test/feel.test.ts`, `test/openMeteo.test.ts`, `test/summary.test.ts` and `test/wmo.test.ts`. Add DST tests (Europe/London in late March and late October). |
| `app` logic | JUnit for `EventLayout` (clusters, columns, short events) |
| `app` UI | Roborazzi screenshots (Robolectric, `@Config(sdk = [35])`, `@GraphicsMode(NATIVE)`) of `DayGrid` with fake events and weather: dry day, rainy evening, snowy morning, overlaps, × light and dark |

Recorded screenshots go in `app/src/test/screenshots/`. They are committed, and CI verifies them.

## Build and CI

- `./gradlew test` runs every test, including screenshot verification.
- `./gradlew :app:assembleDebug` builds the APK. `:app:installDebug` installs it over adb.
- **CI** (`.github/workflows/ci.yml`) runs `test`, `assembleDebug` and Android lint on pushes and
  PRs, and uploads the debug APK as an artifact.
- **Signing:**
  - Debug builds use the default debug key.
  - A stable release key is a later decision. Don't commit any keystore.

## Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-11-18 | Add the already-pinned pure JVM coroutines-core dependency to `:calendar-core` | Share cancellable search Flow contracts without Android imports; retain the application coroutine version |
| 2026-11-18 | Pin `org.dmfs:lib-recur:0.17.1` in pure `:calendar-core` | Use a tested RFC recurrence parser/iterator for editing rather than hand-writing recurrence semantics; displayed instances still come from Android's provider |
| 2026-11-17 | Fresh Compose app; no fork of Etar or Fossify | Fossify's day view is a list. Etar's grid is a dated 5k-line canvas view, and the owner rejected it. |
| 2026-11-17 | Calendar store instead of the Google Calendar API | No OAuth or Google Cloud project; works with any account synced to the phone |
| 2026-11-17 | Weather rules in a pure JVM module | Testable anywhere; must match the desktop prototype exactly |
| 2026-11-17 | No Play Services | Coarse `LocationManager` is enough; keeps the app usable on de-Googled phones |
| 2026-11-17 | Compose UI test JUnit4 and Roborazzi Compose (using the pinned BOM / Roborazzi version) | Launch the real skeleton activity and record light/dark baselines without an emulator |
| 2026-11-17 | Compile SDK 37.0 and build tools 37.0.0; retain target SDK 36 and minimum SDK 29 | The pinned Compose BOM resolves to Compose 1.12.1, whose AAR metadata requires compile SDK 37; SDK 36 fails `checkDebugAarMetadata` |
| 2026-11-17 | Pinned kotlinx.serialization JSON in `:weather` | Parse Open-Meteo responses without Android or an HTTP dependency; reject mismatched arrays per the Android spec, while preserving desktop null/absent optional fields |
| 2026-11-17 | Pinned core-ktx, coroutines-android, lifecycle-runtime-compose in `:app` | Runtime permission checks, observer-driven data loading off the main thread, and lifecycle-aware collection |
| 2026-11-17 | Pinned DataStore Preferences and BOM-managed Compose test manifest | Remember zoom across launches and render isolated calendar screens with synthetic data in JVM tests |
| 2026-11-17 | Day grid uses rgb(25,25,25) in dark mode | Match the desktop weather substrate; keep dynamic color on the surrounding UI and event colors intact |

### Bounded search boundary

Search requests retain the selected calendar scope, display zone and declined policy and return exact
occurrence references with plain text only. The default UI range spans the previous 12 through next 24
months; custom/expanded windows are bounded to 10 years per request. Results are limited to 200 with
explicit expansion up to 1000 and a truncation signal, never an inferred total. The provider adapter
shares reader invalidation and must bound raw scans before identity/materialization or private joins.
Search UI and adapter are being implemented; a new port does not enable a search route.
