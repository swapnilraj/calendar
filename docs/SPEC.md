# Spec

This is the product and behaviour spec. [ARCHITECTURE.md](ARCHITECTURE.md) covers how it's built. When
the two disagree, this file wins, and it should be updated in the same change as the behaviour.

## Principles

Implementation checkpoint (18 November 2026): the live day pager now reads a shared three-day window
and opens native details, including existing meeting links. Changing its calendar selection or date
context closes old details. These automated read workflows do not establish real Google sync, native
editing, all planned views, or reminder delivery; see DAILY_DRIVER_PROGRESS.md for remaining gates.

The live app now exposes Day and Month through its view chooser and an app-local calendar drawer.
Calendar selection, creation default, view and zoom are read as one preference snapshot before any
calendar subscriptions begin. An unavailable preference store shows Retry instead of substituting
provider visibility. Month date sheets survive adjacent-month range loading; supported date picking
and navigation span years 1–9999. Creation controls appear only when a working editor route exists.

Journal recovery distinguishes an unknown outcome from proof that a command never dispatched or
atomically rolled back. Such proof permits only an explicit retry using the original operation ID,
payload and edit token; recovery itself never writes. Missing or unreadable journals remain unknown.

The day renderer now splits timed events at offset transitions on the fixed wall-clock grid, retains
the original event identity for every segment, exposes offset/part and true-duration descriptions,
and masks weather in skipped or repeated clock bands. The current-time line sits below event
surfaces. These synthetic rendering checks do not establish exact Google visual parity.

Ordinary owned guest-free one-off events now have native create/edit/confirmed-delete routes. The
editor opens without write permission; Save/Delete requests it, and a grant never retries the
mutation automatically. Ask-each-time leaves the calendar unselected. The writer freshly validates
rights/source/token and journals linked local writes; results explicitly describe device-local
saves/deletes. Recurrence/guest writes and server propagation remain separate pending work.

Native detail reads retain optional provider availability, privacy, status, organizer, self-response
and guest-policy metadata without inferring unknown defaults. Full guest lists are read only with
explicit organizer or guest-list visibility proof. Free/busy-only details contain none of that
restricted metadata or event-specific styling. Guest-policy flags do not authorize event writes.

Native details describe supported recurrence patterns in readable localized language; unknown or
complex rules show Custom recurrence without changing their stored rule. Ref-only series remain
labeled as part of a series.

Native details present availability, visibility, status, organizer role, own response and advisory
guest policy using readable labels. Calendar-default visibility is never inferred public. Unknown
guest access shows unavailable rather than an empty guest list. Cancelled events have no featured
Join or mutation actions; stored links remain explicit links. Account labels hide raw provider types.

The live view chooser now exposes Day, 3 days, Week, Month and Agenda with shared app-local filters.
Three-day/week selection does not shift its range; explicit navigation changes it, and week windows
align to the locale's first weekday (clamped at supported year bounds). Readable multi-day columns
pan horizontally on narrow/large-text layouts. Date headers open the shared day-event sheet.
Day and multi-day views share a logical wall-minute anchor through view switches; day restoration
converts it using current density/zoom instead of persisting pixels. Old pixel state is not reused.

The calendar drawer now opens durable drafts even when calendar metadata is unavailable. New/edit/
duplicate routes are published only after their draft record is saved. Restored routes require that
record; a missing completed-create draft cannot silently seed another event. Submitted operations
open recovery without requiring a still-present source event. Duplicating copies only the displayed
occurrence into a new draft, without guests or recurrence; no provider mutation occurs before Save.
Explicit known privacy/availability are retained; unresolved metadata is shown for review. A copied
inherited Calendar default visibility must be approved for its target calendar; changing calendars
invalidates that inherited approval. Frozen legacy commands lacking proof remain recovery-only.

Creation destinations include calendar ID, account name/type, owner and optional provider UID.
Defaults, raw drafts, frozen commands and journal receipts retain that identity; a reused numeric ID
never grants permission to save to its replacement. Missing or changed proof requires explicit calendar
reselection while retaining draft fields. A temporarily missing UID does not erase a remembered pin,
but disables its use until matching metadata returns or the user explicitly selects a destination.
Cosmetic calendar renames/colour changes do not invalidate it. Submitted legacy commands without
namespace proof allow read-only recovery, never retry. Copied calendar-default privacy approval is
bound to the complete destination identity. Local commit readback verifies namespace before reading
private event details and before acknowledging the receipt.

The drawer Settings screen now saves display preferences atomically with the existing calendar
selection/default/view/zoom snapshot. First weekday, hidden weekend columns, declined-event filtering,
device/fixed display zone, system/light/dark theme and weather visibility/units apply to the live app.
An unavailable save retains raw settings for retry; choosing a creation calendar remains independent.
Weather location resolution still uses device location/device-zone fallback; a display-zone preference
only changes the calendar clock. Fahrenheit converts display values, leaving pure weather rules and
Celsius forecast data unchanged. Theme overrides apply consistently to the grid and weather substrate.
The main Create action starts a timed draft using saved duration/reminder defaults; month/date-sheet
creation starts an all-day draft with its separate reminder default. Existing unresolved reminders
are never reinterpreted through these new-event defaults. Reminder setup remains separate pending
runtime wiring.

Owned guest-free recurring details now use a separate native scoped route. Supported actions are
issued by the provider adapter per scope; the picker never defaults a destructive scope or enables
unknown rights. Entire-series non-temporal edits use the master template and retain exception
payloads; selected existing-exception edits use that occurrence. Supported RRULE templates can create a new linked this-occurrence edit/cancellation through one
asserted public exception transaction, preserving observed bounds and cloned child data. Following
deletion truncates at the original recurrence identity and removes only future exception originals;
raw exclusions and past overrides stay intact. Following edits and temporal/pattern changes remain
unavailable until their executors are integrated. Opening an editor
requires its exact durable series draft; submitted operations recover without a live source, and a
missing completed draft never becomes a fresh request. Write grants never automatically resubmit.
Explicit discard retires the old controller so it cannot delete a newly prepared replacement draft.
A failed swipe flush restores the sheet and keeps edits visible.

The drawer Search route uses debounced cancellable provider search with app calendar and declined
filters. Query/range survive calendar selection changes, and explicit no-selection starts no search
read. Every timed result includes a date/year; all-day results retain their floating UTC dates.
Opening a result navigates day/week/three-day/month/agenda to its date before native details. The day
pager explicitly seeks external search selections while retaining the shared logical time anchor.
Changed query/range/calendar/zone/declined context clears old private rows before effects run.

Provider input budgets cover rows, cells and text payloads before retaining complete range/detail or
mutation source snapshots. Exceeding an authority-read budget yields LimitExceeded (series edits need
review), without a usable partial snapshot or edit token. Search may return verified completed chunks
with an explicit truncated indication, including when no matches were retained. The UI keeps this
distinct from an empty calendar or a complete no-match result. Raw metadata is never clipped to make
a source snapshot appear valid. Payload enforcement and its UI wiring follow the shared contract;
the contract alone does not establish bounded provider reads.

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

The Application owns one reminder runtime/coordinator/ledger, with fresh reminder configuration and
device-zone suppliers. Startup registers recovery, resume and local event changes reconcile, and
private alarm/action/recovery receivers plus the permission-protected job service are registered.
Exact-alarm access has an inexact fallback. Reminders remain disabled by default; calendar setup and
notification-open navigation are still pending integration, and no device delivery is claimed.
Notification permission must be requested through explicit setup before posting.
Runtime startup, resume, notification actions and content-job rearming serialize configuration reads
with job registration/cancellation. An older suspended read cannot cancel a newer enabled registration
or resurrect observation after a later disable. Cycle health is published within the operation boundary.
Reminder preferences have a separate DataStore read state within the app preference store. Missing
settings start disabled; corrupt or unavailable settings never substitute disabled configuration or
overwrite the stored value. Background configuration suppliers perform a fresh read. Setup saves use
an atomic expected-snapshot comparison, so delayed setup or namespace strengthening cannot overwrite
newer choices. A lost acknowledgement requires readback/review. Display filters and event defaults do
not select reminder calendars. These persistence seams do not themselves enable reminder setup.

## Approved daily-driver target (18 November 2026)

This target supersedes the original phase roadmap and its widget/notification exclusions below.
It describes required behaviour, **not completed implementation**. The historical phase sections
retain the existing day-view and weather baseline. See [progress](DAILY_DRIVER_PROGRESS.md) for
actual implementation/evidence and [tasks](TASKS.md) for the dependency order.

### Accounts, privacy and ownership

- Support personal Google and Google Workspace calendars exposed by Android's calendar provider.
  The installed Google sync adapter handles synchronization; it must remain enabled even when the
  user disables duplicate Google Calendar notifications. There is no app OAuth, backend or sync engine.
- Calendar display selection is app-local. Never change provider visibility or synchronization just
  to hide a calendar. Reminder enablement is independent of display selection.
- Keep independent branding, no analytics/private-event logging, and no application network services
  other than Open-Meteo. Existing weather constants and pure-JVM weather rules remain unchanged.
- Outside this release: Microsoft certification, managed-profile aggregation, Tasks, direct Google
  service APIs, conference creation, room booking, coworker availability and sharing administration.
  Exposed existing meeting links are supported. Do not promise access bypassing employer policy.

### M0 — Google compatibility evidence

Use dedicated authorized personal and Workspace test calendars, with a second test account for
organizer/attendee checks. Prove ordinary mutations, offline/reconnect sync, recurrence exceptions,
series splits, guest/RSVP propagation and reminder round-tripping. Local rows are not proof of cloud
synchronization or invitation delivery. A blocked required provider workflow blocks release; external
handoff does not count as native completion. Do not alter real meetings for test evidence.

### M1 — Reliable day rendering

- Keep three compact all-day entries and make overflow an accessible action. A scrolling day-events
  sheet exposes every event, full title and date range; rows open the relevant occurrence. Support
  Back/dismissal, focus restoration, live data updates and dismissal on date navigation. Include a
  date-header action to reach all day events when grid density is difficult to use.
- Announce all-day events using their UTC-encoded dates and exclusive end-date semantics, never
  converted storage-boundary times. Localize text/plurals; preserve complete accessible titles.
- Measure header/gutter/date/event geometry for large text. Minimum event height includes scaled text
  and padding and participates in overlap detection. Secondary text appears only when it fits.
  Keep zoom 32–160dp/hour and store a logical time anchor rather than raw scroll pixels.
- Mirror gutter, grid, weather, event columns and navigation coherently in RTL, exactly once. Use
  bidi-safe ranges and complete semantics.
- Keep a 24-hour wall-clock grid. Clip events by actual instants and split at every offset transition;
  map each constant-offset segment with that segment's offset. Preserve real duration and occurrence
  identity. Label repeated/missing bands and affected offsets; mask weather within ambiguous/missing
  bands. Handle half-hour transitions too. Exact Google Android DST visual parity is unverified.

### M2 — Calendars and range reads

Read calendar identity, account name/type, display name/colour, owner, timezone, visibility, sync and
access constraints. Keep provider-local IDs distinct from cloud IDs and conservative capabilities
separate from runtime permissions and event restrictions. Unknown access is not permission to write.

Use one shared observed invalidation source and cancellable IO range queries. Bounds cover local
appointments and UTC all-day dates; exact half-open filtering follows the query. Include zero-length
events starting inside the range, exclude starts at its exclusive end, and return a multi-day
occurrence once before view segmentation. No per-cell month queries or per-page observer fan-out.

Group sidebar calendars by account. Persist independent selections across views/search and restart;
handle renamed calendars, removed accounts and newly discovered calendars. Initially select readable
provider-visible calendars; new ones follow that default unless explicitly deselected. Keep a separate
default writable creation calendar. Explain hidden/sync-disabled/provider-unavailable coverage and
distinguish permission denial, no calendars, no selection and no matching events.

### M3 — Navigation and planning views

Deliver day, three-day, week, month and paginated agenda with one shared selected date and persistent
view preference. Support arbitrary date picking, Today, previous/next, first weekday and hidden
weekends. Switching views retains the selected date. Month arithmetic covers leap years, six rows,
adjacent-month dates and multi-day continuation. Date taps open day events, event taps open details,
and empty-space long presses create on that date. Every crowded date has accessible overflow.

Hourly weather belongs in timed grids; month/agenda use compact daily summaries only where forecast
coverage exists. Unavailable dates remain ordinary calendar dates, not misleading forecasts.

### M4 — Native details and meeting access

Show full title, dates, duration, zones, calendar/account, description/location, organizer, guest
responses, recurrence, reminders, availability, privacy and supported colour. Preserve occurrence
identity through navigation, notifications, widgets and search. Render descriptions without executing
embedded content. Open maps/telephone/email/web through appropriate Android handlers. Show Join for
an unambiguous existing conference URL; otherwise list labelled links. Derive actions from actual
rights; specialized/read-only events remain readable without unsupported edit promises.

### M5 — Native ordinary event management

Create from the persistent action, grid slot and month date, using the browsed date. Support title,
calendar, start/end, zones, all-day/multi-day, location, notes, reminders, guests and supported
availability/privacy/colour/guest controls. Default duration is 60 minutes, configurable. Blank titles
display as “No title”. Explicitly resolve ambiguous input and reject nonexistent clock times.

Retain drafts through rotation, process death and failures. Request write permission only when needed;
denial preserves read-only use. Validate current calendar/event rights and source state before writes,
apply supported changed fields only and preserve unknown metadata. Batch linked event/reminder/guest
changes. Detect conflicts and offer review/reload rather than overwriting. Journal uncertain outcomes;
never automatically resubmit an uncertain create. Reload before reporting a local save; do not claim
server acknowledgement the app cannot observe. Offline saves use the provider's normal sync path.

Support confirmed deletion, duplicate-as-draft (guests omitted by default), and verified copy/delete
moves for guest-free non-recurring events. Cross-account moves of meetings/series are excluded. Timed
grid drag/resize previews snap to 15 minutes and use the same validation/save path; editor is the
accessible alternative.

### M6 — Recurrence

Offer daily/weekly/weekday/monthly/yearly presets, custom intervals/weekdays/monthly date or ordinal,
and count/date/no-end limits. Preserve master/original occurrence identity and timezone/duration,
RRULE/RDATE/EXDATE and explicit exceptions.

“This event” writes a linked exception; “all” updates the master retaining explicit exceptions;
“this and following” truncates/splits at the original occurrence boundary and transfers the future
portion. Equivalent deletion scopes apply. Partition moved exceptions by original identity; retain
overrides, re-anchor by recurrence ordinal on future-pattern changes, and require review for overrides
that cannot map safely. Preserve unsupported recurrence syntax and allow non-pattern field edits
without silently simplifying the rule. Verify COUNT/UNTIL, exclusions, moved exceptions, month ends,
leap days, all-day patterns and DST against Google as well as unit fixtures.

### M7 — Everyday work meetings

Support guest email entry, required/optional attendees where exposed, response display, eligible
Yes/Maybe/No RSVP, organizer guest changes, rescheduling and cancellation. Distinguish decline from
owned-event deletion and show attendee consequences before saving. Respect guest visibility/edit
restrictions. Provide pending-invitation filtering and conflicts against the user's selected calendars;
these are not coworker availability. Do not offer unverified suppression of attendee updates. Verify
invitation/RSVP/reschedule/recurrence-change/cancellation effects in the second account.

The shared meeting port reuses persisted edit tokens and the global operation journal with distinct
feature kinds. Guest changes are explicit add/type/remove deltas through opaque source/scope-bound
selectors, never wholesale replacement or caller-supplied responses. Backend-issued rights default
unknown; ordinary guest-free writers remain separate. THIS affects its occurrence. ALL RSVP updates
only self responses on the master and active exceptions; ALL guest changes require complete,
unambiguous mapping and preserve other guests, responses and overrides. Unsupported scopes refuse
before dispatch. Organizer cancellation and attendee decline are distinct commands. Dedicated durable
meeting drafts freeze the exact command before submission; recovery is read-only. Local outcomes do
not establish invitation delivery. The interface is a dependency contract, not enabled meeting writes.

### M8 — Search, preferences and Android entry points

Search title/location/description with cancellation/debounce and calendar filters. Default range is
previous 12 through next 24 months; show it and offer custom dates/expansion and coverage limitations.
Add default view/calendar/duration/reminders, first weekday/weekends/declined visibility, system/light/
dark theme, device/fixed display zone and weather visibility/units preferences.

Provide a resizable agenda widget with independent calendar selection/privacy and Create/Today/event
actions. Validate incoming calendar view/date/create/edit intents, preserve occurrence refs, and handle
permission recovery. Support external keyboard focus/navigation.

### M9 — App-owned reminders

- Own notification presentation, dismissals, snoozes and delivery tracking. Guide setup for notification
  permission, exact-alarm access, channels, test notifications and disabling duplicate alerts elsewhere.
- Read and preserve existing reminders, obey calendar limits and preserve non-alert methods without
  treating them as local notifications. New defaults: 10 minutes before timed events; 09:00 the previous
  day for all-day events. All-day reminder date/time uses device zone independently of display zone.
- Plan reminders due in a rolling 90-day window, accounting for advance reminders on events beyond the
  ordinary event window. Schedule next due delivery and reconcile provider edits, resume, reboot,
  clock/zone changes, upgrades and restored permissions. Use content-trigger and persisted periodic jobs.
- Revalidate events before posting; deduplicate app alarms/provider broadcasts with stable delivery
  identity. Persist 5/10/30-minute snoozes. Use private lock-screen presentation by default and respect DND.
- Show reminder-health degradation when precise delivery is unavailable. Test actual Android/Doze
  limitations; never claim an inexact fallback is precise.

### Release acceptance

All M0–M9 requirements need implementation and evidence. Run test/debug build/lint and inspect changed
Roborazzi screenshots with synthetic data. Add pure temporal/recurrence/reminder, fake-provider,
persistence/migration, UI and integrated failure/recovery tests. Verify controlled Google/Workspace
round-trips and physical-device TalkBack, permissions, reboot/Doze, timezones, offline/conflicts and APK
upgrades. No mocked/local-only result substitutes for remote/device evidence.

Complete seven days of work/personal beta including weekend, offline use, remote edits, recurrence,
reminders and upgrade. Data loss, duplicate writes, wrong recurrence scope, missed eligible reminders
or failed meeting propagation block release. Publish a stably signed upgradeable APK/checksum/release
notes in a durable GitHub release, with signing material outside Git. If device/account/signing/beta
access is unavailable, continue independent work but leave those gates pending.

## Historical phase 1: the day view (read-only)

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

## Historical phase 2: events

- **Tap an event:** an in-app details sheet with time, location, calendar, description, attendees and
  your RSVP status.
- **Create:** long-press a slot, or use the FAB, to open an editor with title, time, all-day, calendar,
  location and notes. Saves through `CalendarContract.Events`, which needs `WRITE_CALENDAR`.
- **Drag:** long-press an event to move it, and drag its bottom edge to resize. Snaps to 15 minutes.
  Recurring events ask "this event / all events".
- **Hour readout:** tap empty space to briefly show `18:00 · Dark · Light rain 1.9 mm · Cool 11°`, the
  same wording as desktop.

## Historical phase 3: week view

- **Same grid** with 7 columns. Swipe pages by week, and the first day follows the system setting.
- **The weather layer** works per column. It already depends only on column geometry.

## Historical initial exclusions (superseded where stated above)

- Tasks.
- Home-screen widgets.
- Our own sync or accounts.
- Notifications. The system calendar app keeps sending reminders for the same events.
