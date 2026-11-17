# Daylight

**An open-source Android calendar with a familiar day view, and the weather painted underneath it.**

*Daylight is a working name. The repository is `calendar`.*

## Why

On a phone, the calendar question is usually "what does today look like?". The day view answers half
of it: when you're busy. The other half lives in a weather app: is it dark by the time I leave, will the
6pm walk be in the rain, do I need a coat for the morning?

Daylight answers both on one screen. The day view works the way you already expect from mainstream
calendar apps. Behind it, the hours quietly carry the weather.

## What it does

- **A day view that feels familiar.**
  - Swipe left and right between days, and pinch to zoom the hours.
  - Rounded colour blocks, side by side when events overlap, plus an all-day row and the current-time line.
- **The feel of each hour, in the background.**
  - **Light:** warm when it's bright, flat grey when overcast, deep blue after dark.
  - **Cold:** an icy cast when it's cold, a warm one when it's warm.
  - **Rain:** fine streaks while it rains, denser when it rains harder.
- **The day at a glance:** a weather icon and the high and low next to the date, plus the chance of rain on wet days.
- **Your calendars, no new account.** Daylight reads the calendars your phone already syncs (Google,
  Exchange, CalDAV) through Android's calendar store. It never asks for your Google password, and its
  own servers don't exist.

Events always win: weather is painted under the grid, and nothing ever covers an event.

## Status

Early. See [docs/SPEC.md](docs/SPEC.md) for the full spec and phases.

| Phase | Scope | State |
|---|---|---|
| 1 | Day view with weather (read-only) | Not started: spec ready, see [docs/TASKS.md](docs/TASKS.md) |
| 2 | Event details, create, edit, drag to reschedule | Planned |
| 3 | Week view | Planned |

## Build

Requires JDK 21 and the Android SDK (platform 36). [`scripts/setup-android-sdk.sh`](scripts/setup-android-sdk.sh)
installs the SDK on macOS or Linux.

```sh
./gradlew test                 # unit + screenshot tests (no device needed)
./gradlew :app:installDebug    # install on a connected phone (adb)
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how it's put together.

## Privacy

Your calendar never leaves the phone. The only network request is the hourly forecast for your
approximate location, sent to [Open-Meteo](https://open-meteo.com/).

---

*An independent project. Not affiliated with Google or any calendar vendor. Weather data by
[Open-Meteo](https://open-meteo.com/) (CC BY 4.0). MIT licensed.*
