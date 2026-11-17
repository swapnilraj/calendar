# Spec

This is the product and behaviour spec. [ARCHITECTURE.md](ARCHITECTURE.md) covers how it's built. When
the two disagree, this file wins, and it should be updated in the same change as the behaviour.

## Principles

1. **Events always win.** Weather is painted under the hour lines and events. Nothing covers an event
   or its text, and event colours are never tinted.
2. **Glanceable, not readable.** "Rainy evening, dark by five" should land in under a second without
   reading a number. Numbers live in the header and the hour readout.
3. **One signal per question.** Light, cold and rain each get their own channel: brightness, hue and
   texture.
4. **Honest data.** Rain is drawn in the hour it falls, density follows the measured amount, and a dry
   day never shows a rain chance.
5. **Familiar first.** The day view should feel like the mainstream calendar apps people already use,
   without copying anyone's branding, icons or fonts.
6. **No accounts.** Calendar data comes from Android's calendar store. No sign-in, and no server of our own.

## Phase 1: the day view (read-only)

### Layout, top to bottom

1. **Top bar:** the month and year of the visible day (e.g. "November 2026") and a "today" button that
   shows today's date number. Tapping it jumps back to today.
2. **Day header row:**
   - **Left column (gutter width):** the weekday ("Tue") above the date number. When the day is today,
     the date sits in a filled circle in the primary colour.
   - **Right:** all-day events stacked as slim chips (max 3 visible, then "+N").
   - **Weather summary**, right-aligned on the weekday line: an icon, then high / low ("11° / 6°"),
     then the rain chance, only when the icon is wet ("🌧 70%").
3. **Hour grid**, scrollable vertically, 24 hours:
   - **Gutter:** hour labels ("1 AM" … "11 PM", or 24-hour per the system setting), aligned to the hour
     lines. There's no label at midnight.
   - **Hour lines:** hairlines across the event area.
   - **Events:** rounded rectangles (radius 6dp) in the event's display colour. Text colour is picked for
     contrast. They show the title, plus the time range when the block is tall enough.
   - **Overlaps:** overlapping events are laid out side by side in equal columns within their overlap
     cluster. There's a 2dp gap between columns and 4dp at the right edge (the "tap to create" margin).
     Events shorter than 20dp use a minimum visible height, including in collision detection. At the
     bottom of the day, their blocks shift upward enough to remain visible inside the grid.
   - **Current-time line:** a primary-coloured line with a dot at the gutter edge, on today only. It
     updates every minute.
   - **Weather layer:** see below. It's painted under the hour lines and events.

### Interaction

| Gesture | Result |
|---|---|
| Swipe horizontally | Previous or next day (paged, with snapping) |
| Scroll vertically | Scrolls the hours. The position is kept when paging between days. |
| Pinch vertically | Zooms the hour height between 32dp and 160dp (default 64dp), shared across days, and remembered across launches |
| Tap "today" | Animates to today |
| Tap an event | Phase 1: opens the event in the system calendar app (`ACTION_VIEW` on the event URI) |
| Long-press an empty slot | Phase 1: nothing. Phase 2: create. |

- **First open:** scrolls so the current hour sits about a third of the way down the screen.
- **Paging:** the pager is effectively unbounded. Page 0 is far in the past and today is in the middle.

### Data

- **Events:** `CalendarContract.Instances`, for visible calendars only, in the device time zone.
  Recurring events arrive already expanded. The view refreshes when the provider changes (a
  `ContentObserver` on the Instances URI).
- **Colour:** `DISPLAY_COLOR`.
- **Declined events:** the attendee's own status is declined. They are shown with a strikethrough title
  and an outlined, unfilled block.
- **Permissions:** `READ_CALENDAR`, requested on first launch. Without it, the screen explains why it's
  needed and offers a button to grant it.

### Weather layer

The rules are ported from the desktop prototype (`notion-calendar-weather`) and must stay behaviourally
identical:

- **Source:** Open-Meteo hourly forecast, requested in GMT with unix timestamps and these fields:
  `temperature_2m`, `weather_code`, `precipitation_probability`, `precipitation`, `is_day`.
  - It fetches 1 past day and 16 forecast days.
  - Results are cached in memory for 30 minutes, with a stale fallback when offline.
- **Location**, in this order:
  1. The device's last known coarse location, if `ACCESS_COARSE_LOCATION` is granted. Rounded to
     2 decimals before being sent.
  2. Otherwise, geocode the city in the device's time zone ID (`Europe/London` → "London").
  3. Otherwise, no weather. The grid looks exactly like a normal calendar.
  The weather options menu offers optional approximate-location access, manual refresh and the
  Open-Meteo attribution. Automatic refresh runs while the screen is visible; returning to the app
  reuses the 30-minute cache where possible.
- **Hours of a day:** 25 samples, the local 00:00 … 24:00 instants (24:00 is the next day's 00:00), so
  colour flows across midnight. On DST days, use the actual local instants (23 or 25 hours of real time).
  The grid still shows 24 rows.
- **Signals per hour:**
  - **Sky band:**
    - `dark` at night or during a thunderstorm;
    - `bright` when clear;
    - `mixed` when partly cloudy;
    - otherwise `overcast`.
  - **Temperature band** (°C, absolute): freezing ≤0, cold ≤7, cool ≤14, mild ≤20, warm ≤27, hot >27.
  - **Precipitation intensity** (0–1), counted only for wet conditions:
    - With a measured amount: `sqrt(mm / 6)`, or `sqrt(mm / 3)` for snow (water-equivalent). Clamped to
      [0.12, 1]. Below 0.05 mm it counts as zero.
    - Without one, fall back to the probability.
- **Alignment:** Open-Meteo stamps precipitation at the **end** of the hour it fell in, so the hour
  block k→k+1 uses the stamp at k+1. The tint at hour line i uses the mean wetness of the blocks either
  side.
- **Tint per hour line:** sky colour, plus a temperature cast (gentler at night), plus slate when rainy
  or pale when snowy. Colours are interpolated between lines in **OKLab**. The constants live in
  `weather/…/Tint.kt` and are shared with desktop.
  - **Dark theme:** used as-is.
  - **Light theme:** deeper colours with more opacity (alpha capped at 0.30).
- **Texture:** rain is drawn as short slanted streaks (a 12×18 dp pattern) and snow as dots. The alpha
  per row is interpolated between hour-block centres from the block intensities. Nothing is drawn on a
  dry day.
- **Header summary:** the most severe condition lasting at least 2 daytime hours (thunderstorms need 1),
  otherwise the most common one. Plus the high and low, and the maximum rain probability.

### Acceptance (phase 1)

- With calendar permission granted, today's events match the system calendar app for the same day,
  including all-day, recurring and overlapping events.
- Swiping left and right pages by exactly one day. The top bar month updates.
- With location or network unavailable, the grid renders normally with no weather, and nothing errors visibly.
- The weather tint and streak rows for an hour use the same data as the hour readout. This is checked by
  unit tests on the hours-for-day mapping and the alignment rule.
- Screenshot tests render a dry day, a rainy evening and a snowy morning, in light and dark themes.

## Phase 2: events

- **Tap an event:** an in-app details sheet with time, location, calendar, description, attendees and
  your RSVP status.
- **Create:** long-press a slot, or use the FAB, to open an editor with title, time, all-day, calendar,
  location and notes. Saves through `CalendarContract.Events`, which needs `WRITE_CALENDAR`.
- **Drag:** long-press an event to move it, and drag its bottom edge to resize. Snaps to 15 minutes.
  Recurring events ask "this event / all events".
- **Hour readout:** tap empty space to briefly show `18:00 · Dark · Light rain 1.9 mm · Cool 11°`, the
  same wording as desktop.

## Phase 3: week view

- **Same grid** with 7 columns. Swipe pages by week, and the first day follows the system setting.
- **The weather layer** works per column. It already depends only on column geometry.

## Out of scope

- Tasks.
- Home-screen widgets.
- Our own sync or accounts.
- Notifications. The system calendar app keeps sending reminders for the same events.
