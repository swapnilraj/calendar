# Spec

This is the product and behaviour spec. [ARCHITECTURE.md](ARCHITECTURE.md) covers how it's built. When
the two disagree, this file wins, and it should be updated in the same change as the behaviour.

## Principles

An explicit Add people action may convert an eligible, current ordinary new-event draft into a
dedicated meeting-creation draft using one exact-record compare-and-set. Raw fields and authored
recurrence remain intact; conversion never reads meeting authority, requests calendar permission or
writes a provider event. Existing edits, legacy/recovery/frozen drafts, incoming-review namespaces
and drafts with unresolved notices stay ineligible. Once conversion begins, the ordinary controller
remains closed even if readback is unavailable; retained browser recovery decides which exact record
survived. The action is exposed only by a host supplying the meeting callback; application routing
remains separate.

Private incoming CREATE completion (draft version7) and EDIT review (version8) retain raw caller
suggestions separately from provider source authority. Unknown fields, duplicate fields, future versions,
mismatched identities and malformed proof fail closed without normalizing or applying suggestions.
Prepared EDIT candidates bind exact source token/baseline and reject legacy or already-frozen native
drafts; consumed handoff receipts retain session/generation or source hashes rather than caller
title/notes/token payload. These opaque draft documents add no storage schema, provider write,
automatic retry, or receipt4/6 migration. This packet supplies protocol prerequisites; services and UI
acceptance remain separate.

Incoming calendar read sessions resolve caller-provided references through bounded provider reads before displaying details. Sessions pin the original calendar namespace and event identity; restoration, asynchronous reads and permission or source changes never silently adopt a replacement or choose an arbitrary recurring occurrence. Raw caller fields remain untrusted review suggestions and never become a mutation command. The entry Activity and injectable review interfaces are supplied as an inactive foundation: exported intent filters, application ownership and date/create/edit review destinations require a separate integration step before public activation.

### Retained meeting-family drafts

The draft browser identifies response, creation and organizer-owned meeting records before ordinary
draft decoding. Unknown or corrupt family/version records remain retained with no ordinary launch.
Each family exposes Open only when the host supplies its matching callback; refreshing or listing
never submits, normalizes or rewrites a draft. Discard keeps exact-record concurrency checks.
Existing ordinary sorting, display and error behavior remain unchanged. Application routing is separate.

### Fenced agenda widget engine

Widget application integration supplies durable binding/bootstrap/quarantine ownership before opening
framework work. Configuration saves use the widget's own context and exact revision. Widget event
opens revalidate binding, calendar namespace and occurrence before showing read-only details; Today,
Create and More actions use validated one-shot navigation and retain existing editor drafts. The five
widget components are activated only after their real owners, configuration and destinations exist.

Widget work is serialized and bootstraps its durable binding/removal ledger before processing updates.
Epoch and generation checks prevent stale work from recreating removed or rebound widgets. Widget
configuration uses its own persistent namespace and selection; reading a widget never changes provider
visibility, synchronization or events. Widget persistence does not change the calendar-store schema.
Framework activation, configuration/open activities and application ownership are separate integration
steps; real destinations and resources must exist before the widget is offered in the manifest.

### Self-contained ordinary edit recovery

New one-off UPDATE/DELETE operations freeze receipt4 source and native write plans before dispatch.
Retry adopts the durable winner and rechecks its original namespace and source; attempted uncertain
operations never replay. READ-only recovery requires exact native results and complete child proof.
Reserve journal space for both minimal and complete acknowledgement records before new or adopted
writes, including maximum-width child IDs. Predictable overflow returns unavailable without writing.
Retain historical receipt2/3 recovery, recurring retirement restrictions and existing command signatures.
Unattempted legacy CREATE and explicit temporal UPDATE retries also apply current native-zone
compatibility admission; Java-only zones unsupported by Android return ZONE without dispatch.
Attempted/committed recovery and frozen legacy receipt bytes are not migrated or replayed.
Authored timed bounds must align to whole milliseconds; nonrepresentable precision returns TIME
before dispatch. Exact milliseconds and negative epochs remain supported. Frozen receipt4 original
or result bounds with non-native precision stay uncertain and cannot authorize retry or acknowledgement;
their bytes are never rounded or migrated. Untouched imported fields retain their original values.

### Explicit organizer edit and cancellation

The injectable organizer-owned meeting workflow retains the original calendar/account, event and
revision identities when editing guests or meeting fields. Saving or cancelling requires an explicit
action, preserves unknown provider metadata and keeps conflicts and uncertain outcomes reviewable.
Opening a draft or returning from a permission prompt never submits automatically. A local save or
cancellation does not confirm delivery to guests; production routing is a separate integration gate.

### Authored recurring creation in the phone editor

Production creation enables daily, weekly, monthly and yearly recurrence through the full-height
phone editor. The embedded host defaults this capability off; enabling it requires the existing
recurring-create writer. A complete rule and explicit master-time review are required before a
confirmed create. Changing relevant fields invalidates that review. Opening, restoring or returning
from a permission request never submits a create. Frozen commands and uncertain outcomes retain
their existing receipt6 recovery rules, and local save does not establish server delivery.
This packet enables ordinary recurring creation only; meeting promotion, RSVP and scoped-series
editing remain separate application routes.

### Schedule range endpoints

Schedule always shows its loaded date range and explicit Earlier/Later controls, including when
the shared phone header replaces component navigation. Each enabled action selects the adjacent
range's first date and requests that one bounded replacement window through the existing callback.
Scrolling never starts another provider read or appends pages. The year 1/9999 boundary disables
the unavailable direction with a readable boundary explanation. Selecting an event keeps its exact
occurrence reference; selecting an in-window date leaves the loaded range unchanged.
These controls belong to ready data. Existing loading, permission, unavailable and read-limit
presentations replace the event list and its range actions. Empty selection, no calendars and
partial device coverage remain distinct and never imply complete account coverage. Normal phone
presentation may use a compact date gutter and event cards; large text and narrow layouts must
retain wrapping titles, chronological ordering, repeated-hour offsets and accessible actions.

Production calendars with settings use one shared phone toolbar across Day, three-day, Week,
Month and Schedule. The month control opens a paged mini-month with an explicit arbitrary-date/year
picker; month paging alone does not select a date or mutate events. Today uses the selected display
zone. Choosing a date updates the shared view range, while view changes preserve that date and
app-local calendar filters. Back collapses the date navigator before leaving the calendar.

The navigation drawer offers implemented views above account calendars; Schedule retains the
persisted AGENDA identifier. Injected weather controls remain in the drawer footer, and timed views
retain an accessible Time scale dialog after redundant view-specific toolbars are hidden. No Search
button is rendered until an actual search port is connected. Standalone view components retain their
existing navigation chrome by default. This bounded presentation does not establish live Google
Calendar gesture or pixel parity and changes no provider visibility, synchronization or write policy.

One application-owned reminder runtime serializes framework work and settings commits. Private
receivers and bounded jobs recover after content, boot, clock, timezone and permission changes.
Permission-loss cleanup can cancel app-owned delivery even when configuration reads fail, without
substituting a successful disabled configuration or changing retained event/post history.

Reminder delivery claims are coordinated with durable post identities, nonce-bound alarms and
fresh permission/configuration checks. Unknown posting outcomes are retained without automatic
repost. Unpinning a calendar clears its active app-owned alert while preserving posted history,
so re-enabling does not alert again solely because it was unpinned. These delivery components
require application runtime/setup wiring and physical-device verification before release.

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
Edit-source eligibility checks validate writable calendar access before event data and explicit guest
visibility before private child rows, including when invoked from a readable details screen.

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
Explicit known privacy/availability are retained; unresolved metadata is shown for review.

Creation destinations include calendar ID, account name/type, owner and optional provider UID.
Defaults, raw drafts, frozen commands and journal receipts retain that identity; a reused numeric ID
never grants permission to save to its replacement. Missing or changed proof requires explicit calendar
reselection while retaining draft fields. A temporarily missing UID does not erase a remembered pin,
but disables its use until matching metadata returns or the user explicitly selects a destination.
Cosmetic calendar renames/colour changes do not invalidate it. Submitted legacy commands without
namespace proof allow read-only recovery, never retry. Copied calendar-default privacy approval is
bound to the complete destination identity. Local commit readback verifies namespace before reading
private event details and before acknowledging the receipt. Restored unsubmitted edits also require
the saved source calendar identity before adopting a fresh edit token; missing legacy proof retains
the draft for explicit review.

Provider input budgets cover rows, cells and text payloads before retaining complete range/detail or
mutation source snapshots. Exceeding an authority-read budget yields LimitExceeded without a usable
partial snapshot or edit token. The UI keeps this distinct from an empty calendar. Raw metadata is
never clipped to make a source snapshot appear valid.

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

Editor components retain durable draft identity, original raw inputs and submitted operation context.
A draft browser can distinguish resumable edits, submitted recovery and unreadable/future formats;
source-missing submitted operations remain recovery-only. Preparing a launch first persists and
reads back its draft. App routing to these components is a separate integration change.

Reminder source/ledger components now read events independently of display filters, pin calendar
namespace identities, and include advance notifications for events beyond the ordinary event window.
A bounded discovery absence never proves a tracked event disappeared; tracked keys are revalidated
separately. Private SQLite delivery history retains generation/post identity and rejects stale action
tokens after snooze reposts. Background app delivery is not enabled by these components alone.

The Application owns one reminder runtime/coordinator/ledger, with fresh reminder configuration and
device-zone suppliers. Startup registers recovery, resume and local event changes reconcile, and
private alarm/action/recovery receivers plus the permission-protected job service are registered.
Exact-alarm access has an inexact fallback. Reminders remain disabled by default; the drawer's Event
reminders action opens explicit calendar selection and permission/delivery setup. Saving confirmed
local choices triggers reconciliation; missing permissions show setup without prompting automatically.
Raw choices remain mounted during permission recovery, and explicit reload starts a fresh collector
and form only after the user requests discard. An uncertain preference save is read back before any
success acknowledgement. Device delivery is not claimed by a local save.
Delivery setup shows Checking until its latest requested reconciliation completes, and never presents
old Ready health while current notification/calendar permissions or precise-alarm access are missing.
Reminder setup enrolls settings CAS and readback in the runtime's configuration-change ordering before
acknowledging a save. Old in-flight delivery cannot cross that acknowledged application update; after
source awaits, delivery and snooze also recheck fresh configuration and permissions. Known disable or
permission loss attempts alarm/notification teardown independently of ledger success. Unknown settings
remain unavailable, and known permission loss permits narrow teardown without running a delivery cycle.
This does not make private settings, CalendarProvider and Android notifications one atomic transaction.

Reminder preferences have a separate DataStore read state within the app preference store. Missing
settings start disabled; corrupt or unavailable settings never substitute disabled configuration or
overwrite the stored value. Background configuration suppliers perform a fresh read. Setup saves use
an atomic expected-snapshot comparison, so delayed setup or namespace strengthening cannot overwrite
newer choices. A lost acknowledgement requires readback/review. Display filters and event defaults do
not select reminder calendars. These persistence seams do not themselves enable reminder setup.

Notification-open decoding requires canonical app data and a complete post identity. The read-only
resolver verifies the persisted post/token, current enabled namespace and freshly resolved original
occurrence key before fetching details. It follows moved exceptions instead of trusting an old event
number, then rechecks detail namespace, configuration and persisted post before publishing. Stale,
retired, replaced or unverifiable posts expose no private details. A matching interrupted post can open without changing its delivery state or inventing an
acknowledgement. Private notification opens now use their own native read-only Activity, leaving
calendar display selection and open editor drafts unchanged. It clears old details when the post or
context changes, observes only while started, and clears details on stop. Explicit setup can send a
generic test notification without calendar, provider or ledger writes; system acceptance is distinct
from a person observing delivery. Synthetic-provider journeys cover the connected settings/runtime
and notification-open Activity; physical background delivery and account acceptance remain separate.

Native editing retains known visibility and availability and distinguishes unchanged fields from an
explicit clear. Unknown source metadata is preserved. Duplicate-draft components copy one occurrence
without guests/recurrence, retaining explicit Private/Public/Confidential choices. Inherited Calendar
default visibility needs target-calendar-specific approval when its calendar changes; unproven frozen
legacy commands stay recovery-only. The app duplication action is a separate routing packet.

The settings form validates raw default-duration/reminder/display-zone inputs, retains invalid values
and save failures, and blocks edits/dismissal during a caller-owned save. Its preference codec mutates
only display keys and the shared view key; calendar selection/default, zoom and reminder policy keys
are preserved. The caller owns atomic storage and applying these values to the application.

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

### M8 — Search, preferences and Android entry points

Search title/location/description with cancellation/debounce and calendar filters. Default range is
previous 12 through next 24 months; show it and offer custom dates/expansion and coverage limitations.
Add default view/calendar/duration/reminders, first weekday/weekends/declined visibility, system/light/
dark theme, device/fixed display zone and weather visibility/units preferences.

Provide a resizable agenda widget with independent calendar selection/privacy and Create/Today/event
actions. Validate incoming calendar view/date/create/edit intents, preserve occurrence refs, and handle
permission recovery. Support external keyboard focus/navigation.

Incoming event links first perform a bounded, fresh metadata read of the exact event item and readable
calendar namespace, checking that metadata and namespace remain stable across the read. This lookup
does not query private children or construct an occurrence reference. Canonical refs come only from
provider-expanded Instances; recurring/detached event IDs require an explicit occurrence hint. Hidden
app/provider display filters do not authorize or prevent a directly requested readable link. Missing
or canceled/deleted rows, ambiguous identity, and insufficient read authority do not open stale details.

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

## Typed retirement contract (publication foundation)

An app-private operation retirement port accepts the exact typed create/update/delete, series or
meeting request; a caller-supplied fingerprint is not authority. A matching permanent fence is
required before an eventual frozen-draft replacement/deletion. Inspection returning NotRetired is
not proof that a request never dispatched. Attempted or committed requests cannot be advertised as
safely abandoned, and an unavailable acknowledgement requires inspection of the same original request.

The ordinary editor recognizes a retirement result only for its own operation ID, stops changes and
submission, and leaves the raw draft untouched. A retirement result for another ID remains uncertain.
This contract packet adds no provider retirement implementation, abandonment UI, meeting writer or
recurrence activation. Existing serialized drafts, frozen commands and writer fingerprints are
unchanged; the backend and connected retirement actions require separate verified packets.

The following ordinary backend packet provides permanent app-private retirement for shipped
one-off create/update/delete requests. Exact journal-record comparison fences attempts and later
phase changes; retired commands return before permission checks or provider reads. Inspection and
retirement do not query the calendar provider, rewrite a draft or change the existing command JSON,
hashes, receipt format or database schema. Series, meetings, meeting creation and recurring ordinary
requests remain Unsupported by this retirement service until their own encoders ship. Connected
discard/replacement actions and application composition remain a separate implementation packet.

## Internal authored recurrence preparation

The internal authored-recurrence compiler and raw-field codecs preserve incomplete draft input,
validate bounded daily/weekly/monthly/yearly intent, retain explicit time-zone and UTC-offset
choices, and distinguish invalid input from provider-semantics review. These helpers alone do not
expose repeat controls or authorize native writes. Provider expansion, actual first-instance checks
and invitation delivery require separate implementation and verification; imported recurrence stays
unchanged.

### Ordinary reminder-only edits

Changing alert minutes alone validates the calendar's alert methods, limits, duplicate minutes and
default sentinel without authoring TIME or ZONE. Imported NULL zones, native time values, opaque
metadata and non-alert reminder rows remain unchanged. Creating an event or explicitly changing its
time still requires the existing temporal validation.

## Atomic ordinary drafts and retirement

New ordinary create/edit/duplicate drafts use strict version5 generations and exact-record local
compare-and-set. Opening a missing or replaced route never recreates it. Existing version1 raw drafts
require an explicit upgrade; their frozen create/update/delete commands retain the original version1
JSON and journal identity. Frozen recovery is source-free and cannot silently return to editing.
The existing editor sheet offers explicit local discard, permanent retirement before replacement,
and cleanup acknowledgment after a known local outcome. Dispatch or recorded commits block retirement;
unknown acknowledgments and foreign successors retain the draft instead of permitting another save.
Permission grants, replacement preparation and reopening never submit automatically. Recurrence
controls and other feature workflows remain unactivated by this ordinary-draft migration.

## Signed candidate release process

A manual release workflow must use an owner-controlled stable key held outside Git, verify its
public certificate fingerprint, embed an increasing version code and create a draft GitHub release
with the APK and checksum after the complete automated gate. It must never generate a replacement
key or publish automatically. Draft candidates remain pending until account/device/upgrade and
seven-day beta acceptance is recorded. See [RELEASE_SIGNING.md](RELEASE_SIGNING.md) for provisioning
and the one-time debug-to-stable certificate transition.

## Familiar phone event presentation

Ordinary event editing and details use a familiar full-height phone presentation with fixed close,
Save or verified edit/overflow actions. Focused date/time, calendar, notification, text and metadata
panels preserve raw input and explicit overlap choices while delegating all draft, permission,
retirement and mutation authority to the existing controller. Details clear their payload and
actions on loading, permission loss or unavailable reads; edit eligibility requires the same exact
occurrence and non-empty full calendar namespace as the fresh details read. Daylight's own drawn
glyphs and branding remain in use. Existing sheet fixtures are retained. This presentation does not
activate repeat authoring, invitation responses or meeting creation. Exact Google Calendar Android
visual and interaction parity remains **UNVERIFIED**, including the editor title bar.

### Ordinary CREATE backend and immutable recovery

The ordinary writer accepts one-off events and supported authored daily, weekly, monthly and yearly
CREATE rules. A recurring master uses NULL DTEND, bounded RRULE/DURATION and UTC-date all-day values;
linked alerts are inserted in the same asserted non-yielding batch. New one-off and recurring
operations use receipt6: initial validation freezes exact native values, first-instance bounds,
an operation-bound plan digest and a generated UID. New timed preparation rejects provider-incompatible
timezone spellings without normalizing accepted names. Concurrent writers adopt the durable prepare
winner; exact retries reuse its UID/plan rather than current authoring rules. READ-only recovery
needs no edit snapshot, current recurrence compiler or stored-zone lookup. Fresh namespace, ACL and
capability checks fence dispatch. Existing command JSON/fingerprints and permanent retirement fences
remain unchanged. See [the private protocol and acceptance contract](ORDINARY_CREATE_RECEIPT6.md).

A canonical positive master acknowledgement and bounded raw result facts become COMMITTED before
child acknowledgement interpretation or payload reads. Both create modes require exact unique child
IDs at authored alert indices, complete membership/payload and actual first-instance bounds. A minimal
known-ID/calendar/UID binding precedes private reads and is rechecked before verification. Observed
identity drift stays uncertain without private readback. Missing/oversized child evidence retains the
known commit; invalid/lost master acknowledgement never supplies a guessed ID, UID search or replay.
Predictably unrecordable ACK/storage/query budgets block dispatch before a provider batch.

Legacy receipt2/3/5 operations retain their original wire/protocol and gain no invented UID or child
proof. Receipt2 remains weaker; receipt3 commits stay conservative, with exact undispatched retry
preserving prepared bytes and earning receipt5 only after its new dispatch. Receipt5 retains exact
ACK bindings and its current-compilation limitation. Legacy attempted/known commits never migrate or
replay through recovery. Recurring UPDATE/DELETE, series and meeting retirement remain unsupported
by this publication. These are local provider guarantees, not Google/Workspace sync evidence,
scoped-edit admission, provider-clone certification or physical-device acceptance. Live recurring-create
UI activation is a separate packet and remains disabled by default here.

## Native meeting adapter publication

Injected native meeting adapters preserve their existing asserted writes, durable frozen commands,
exact identities, conflict and uncertain-outcome behavior. Owned meeting edits retain unknown metadata
and opaque guests. Imported/generated recurrence scopes require issued source rights and the existing
provider-clone admission policy; publishing adapters does not admit a new provider. Retrying an unknown
create or inferring invitation/server delivery from a device-local save remains forbidden.

Meeting retirement uses the exact meeting fingerprint encoders while the already-published ordinary
recurring UPDATE/DELETE retirement safety gate remains closed. Meeting-authored time validation may
use its own provider-zone check without changing existing ordinary validation or receipt protocols.
This adapter packet is not production CalendarApp/meeting-route wiring, account acceptance, a schema
migration or an ordinary receipt4 migration.

## Complete series source reading

The backend series source adapter reads the complete bounded master/exception/child graph
independently of the visible date range. It validates the selected original occurrence and actual
bounds, current calendar namespace, permission and write capability. Only repeated complete-source
checks may issue an opaque private edit snapshot/token; incomplete coverage never produces a
partial editable source. This performs no CalendarProvider mutation or visibility/sync change.

Unsupported recurrence/date syntax remains raw and requires review. The source adapter itself
issues no mutation scopes: scope capabilities remain empty/default UNKNOWN until a writer supplies
its own fresh checks. Adding the adapter does not wire a new UI action, writer, provider clone
profile or EXDATE transfer. Synthetic source tests do not establish native cache behavior or
Google/Workspace propagation.


## Scoped series backend publication

The bounded series backend admits only scopes supported by complete fresh source, exact occurrence
identity, current ACL/capability and the injected provider policy. The production clone policy remains
unverified and admits no clone-dependent scope. This publication does not wire a UI action.
THIS/ALL edit/delete and FOLLOWING delete preserve their existing bounded planner and journal rules;
Unsupported or incomplete recurrence requires review.

THIS/ALL edits and scoped deletes retain the legacy series protocol: recovery can depend on stored
source and current planning. New exception-free FOLLOWING edits use the privately versioned
[frozen Following3 protocol](FOLLOWING_EXECUTION_RECEIPT3.md): immutable winning native plan/UID,
complete indexed count/insert acknowledgements, guarded child reads and READ-only known-commit
recovery. Existing legacy Following2 operations retain their bytes and conservative protocol; no
silent migration or replay of uncertain attempted writes occurs. Nonzero splits still require an
explicitly verified injected clone policy and an issued prefix UID or sync identity; the production
default remains unverified. Ordinal-zero in-place eligibility is unchanged. This does not establish
EXDATE/exception transfer or Google/Workspace propagation. Permanent retirement fences bind exact
operation/fingerprint; unsupported families and unknown phases fail closed without provider dispatch.
Synthetic provider tests are local evidence only, not installed-provider certification.


## Connected filtered search

The shared phone toolbar and drawer open literal, case-insensitive search through the published
bounded reader/invalidation helpers. Search retains its raw query and explicit date range, applies
app-local calendar selection, display zone and declined-event preference, and masks old rows
synchronously when that context changes. Read limits, empty selection, unavailable/permission states
and partial device coverage stay explicit. Load-more is deliberate and bounded; no provider visibility
or synchronization setting is changed. Opening a result navigates to its date and exact occurrence
reference without a write, while preserving calendar filters. Production enables the search port;
embedded callers default to absent. This does not search cloud-only calendars or establish Google
main-screen gesture/pixel parity.

## Phone Settings and General

The drawer opens full-height Settings → General for existing supported preferences. Grouped
rows/switches and focused in-place choice/numeric/zone editors replace the production bulk sheet;
legacy component fixtures retain their sheet API. Current persisted view is labelled View. Home
exposes real default-calendar and optional reminder-setup callbacks; unsupported account controls
are absent. Weather rules/constants and provider visibility/sync are unchanged.

An explicit choice atomically changes only its owned field/key pair. Independent edits preserve
unrelated raw values, filters, defaults, zoom and newer view choices. The latest explicit same-field
choice wins. Invalid values never enter storage; IO failure retains input for explicit retry and
cancellation propagates. Loading/read failure masks all editor controls/system Back while retaining
unfinished input. A Saved acknowledgement closes only its submitted editor. Cancel abandons the
proposal; opening, typing, Back, dismissal and recreation never auto-save.


## Exception-free Following EXDATE metadata bridge

The [receipt4 contract](FOLLOWING_EXDATE_RECEIPT4.md) defines the bounded native adapter for changing
metadata from a chosen occurrence when EXDATE is independently decoded and the exception graph is
empty. New4 freezes exact lexical partitions/native writes, nominal COUNT/end limits and issued
clock/zone/WHOLE context. Nonempty exclusions expose metadata capabilities only; unsupported syntax,
excluded selection and temporal/rule changes remain unavailable or require review before dispatch.

Existing2/3 operations retain issued protocol/bytes through every phase; recovery never replans or
upgrades them. Namespace/ACK/child-binding/CAS/retirement and clone-profile checks remain in force.
Default Unverified admits no new clone-dependent operation. The local195 checks and separate105
provider/3 synthetic UI aggregate checks pass; fullCI, native Google/Workspace propagation, moved or
cancelled exception graph transfer and external daily-driver acceptance remain separate gates.

## Adaptive phone Month overview

At normal text size with adequate phone space, the existing six-week Month grid fits all seven
columns and six rows at360×640 and393×851. Available dimensions and measured font line sizes set row
and summary budgets; date targets remain at least48dp and crowded cells cannot grow fitted rows.
Text is not shrunk. Date algorithms, first weekday, adjacent dates, selected/hidden-weekend access,
app-local filters, continuation and exact occurrence identity stay unchanged.

Compact summaries/counts are plain text merged into one date action, which opens the complete existing
day list and each exact event. No tiny independent event target is introduced. Detailed/wide/large-font
or constrained layouts keep reachable scrolling and existing shortcuts. Four captures were inspected;
39 functional aggregate checks and the complete2,359-test/debug/lint gate pass. Google main-screen
interaction/row-count/pixel parity remains unverified; this publication makes no cloud/provider claim.

## Optional local contact suggestion source

An injected contact source returns bounded raw email/display-name tuples from the device's default
contacts directory. It reads only after explicit opt-in and READ_CONTACTS authority, preserves raw
addresses and names, and reports permission loss, cancellation, unavailable data and limits without
substituting successful empty results. It never enumerates account directories, writes contacts,
uses network services or logs contact payloads. This source-only packet does not connect a picker,
request a runtime permission or enable guest changes; those application routes require separate
composition and retained-draft verification.


## Injectable meeting creation presentation

A meeting creation presentation may consume the existing typed CalendarMeetings port and durable
private draft store. It preserves raw guest email/name rows, requires explicit save and recurrence
review, and retains conflict/permission/uncertain outcomes. Optional local contact suggestions require
explicit per-opening opt-in and READ_CONTACTS authority. Unknown recurring-creation eligibility stays
unavailable; a presentation must not infer admission from a writable calendar. This publication packet
does not connect production meeting routes, supply a native meeting writer or request contacts access;
those require separate composition and adapter verification. Local outcomes never claim invitations
were delivered or a server accepted the meeting.

## Injectable attendee response presentation

Attendee responses retain exact occurrence/calendar identity, explicit desired response and recurrence
scope, durable drafts, fresh authority checks and frozen uncertain outcomes. Opening, permission return
or restoration never submits a response. The presentation accepts an explicit 12/24-hour convention
for its timed summary, alongside display zone; this formatting does not change native event time.
This packet supplies injected presentation only. Production details/browser routes and Google/Workspace
response delivery need separate integration and acceptance. Unwired browser cases are retained for
that integration rather than skipped as successful presentation tests.
