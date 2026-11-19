# Spec

This is the product and behaviour spec. [ARCHITECTURE.md](ARCHITECTURE.md) covers how it's built. When
the two disagree, this file wins, and it should be updated in the same change as the behaviour.

## Principles

Ordinary recurring UPDATE and DELETE requests retain the closed retirement boundary: neither
inspection nor retirement may create an abandonment tombstone for them. Scoped recurring operations
use the dedicated Series family and its exact encoder; meeting and authored recurring-create
retirement keep their distinct existing fingerprints. Legacy ordinary requests remain retained for
review instead of being reissued through a different operation family.

Legacy ordinary draft versions1/4 accept an absent feature tag or the exact `ordinary-editor` tag.
After known family dispatch, foreign or malformed tags remain retained and unreadable without an
ordinary launch. Version5 keeps its strict envelope validation; no draft bytes are rewritten by listing.

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

### Google Calendar Android UX reference

The user's confirmed UX target is the current Google Calendar Android phone app. Match its navigation
structure, screen hierarchy, control placement, date/view switching, gestures and ordinary event and
meeting workflows, rather than treating similar feature coverage as UX parity. Preserve Daylight
branding and the existing weather rules. Compare the core calendar with weather hidden, then verify
the optional weather layer separately. Reference observations and remaining differences belong in
`GOOGLE_CALENDAR_UX_PARITY.md`.

Pin reference app build, device size/density, Android version, locale, theme and font scale when
making a verified parity claim. Official screenshots/help are evidence for what they show, not proof
of live interaction timing or exact dimensions. APK inspection, where available, is read-only
reference analysis; implementation stays in this repository. A current Google Calendar APK has been installed and its onboarding rendered in an isolated
Android emulator; see the dated evidence update in GOOGLE_CALENDAR_UX_PARITY.md. Main calendar
flows and exact live/pixel parity remain unverified. Provider
permissions, draft preservation and uncertain-save handling remain required under the matched UI.

Invitation response summaries use the host's selected 12/24-hour convention and display time zone,
including explicit offsets. Formatting never changes the frozen event time, draft or response command.

Production ordinary create/edit and event details use full-height phone presentations with fixed
close/Save or close/edit/overflow controls. Date/time and repeat inputs open focused pickers, with
raw invalid input and explicit offset choices retained for review. This presentation delegates the
existing draft, permission, conflict and recovery rules; it does not authorize writes on opening or
dismissing. Details lose their old payload and actions immediately when the read state loses
authority. Ordinary edit eligibility must match the exact occurrence and non-empty full calendar
identity of the freshly read details. A writer-side READ denial clears the details payload and links;
it does not leave old private fields visible with disabled buttons. Phone row subtitles use content
direction so English numeric phrases keep their order in RTL layouts. Standalone legacy sheets
remain available to existing component fixtures.

Private CREATE/EDIT review activities retain the exact native editor owner while an accepted write
or known local outcome awaits cleanup acknowledgment, including stop/resume transitions. Fresh
authority checks mask the presentation without replacing that owner. A successful exact deletion
acknowledgment can complete the original activity while preserving a newer draft generation;
backgrounding never authorizes a second provider write. Masked editors offer a safe Close action
when no operation is running.

For a modern raw ordinary edit with a NULL provider timezone, explicitly selecting the currently
rendered timezone is an authored zone choice even when the visible text stays identical. Persist
that intent separately from raw fields: a zone-only choice emits ZONE.Set and TIME.Keep; later time
edits emit both. Generic no-op callbacks and known-source same-zone selections stay no-ops. Frozen
commands, legacy drafts and meeting formats are unchanged. Incoming EDIT review can apply an explicitly proposed zone to an eligible NULL-zone timed source,
including its original renderer zone. Fresh explicit Apply records this intent in the exact prepared
ordinary candidate and requires issued ZONE rights. A zone-only proposal preserves raw times and
offsets; proposed instants author both deltas. Missing-zone temporal changes, including fold-offset
changes, stay under review. Opening, permission return and reopening never author or submit; existing
prepared candidates and frozen commands resume unchanged.

New ordinary UPDATE/DELETE operations store self-contained receipt4 source and native write plans
before dispatch. Their exact commands and retirement fingerprints remain unchanged. Explicit retry
adopts the winning prepared plan and rechecks its original source; uncertain attempted operations
never replay. READ-only recovery can survive local edit-snapshot loss and requires exact namespace,
native result, child membership and complete acknowledgements. Changed or missing proof remains
uncertain. Legacy receipt2/3 recovery stays on its existing protocol; receipt2 edits still need their
retained snapshots. Providers that assign or change a formerly NULL event UID/sync identity after
commit cannot be inferred to match; their recovery stays conservative pending account verification.
Before a new or adopted receipt4 edit reaches the provider, reserve journal space for both minimal
and complete acknowledgement records, including maximum-width child IDs. A predictable overflow
returns unavailable without attempting any native write; exact-fit records remain dispatchable.
Authored timed bounds for new creates and explicit temporal edits must align to whole milliseconds;
nonrepresentable precision returns TIME without dispatch. Exact milliseconds, including negative
epochs, remain valid. Frozen receipt4 original/result bounds with non-native precision remain
uncertain and cannot authorize retry or acknowledgement; their bytes are never rounded or migrated.
Untouched imported temporal fields remain unchanged. Unattempted legacy temporal retries also apply
current native-zone admission; attempted/committed recovery retains its existing frozen protocol.

The SDK-only reminder identity build requires an explicit debug opt-in and a separate application
ID/Application. Its unbound ports cannot access calendar/store data, schedule/cancel alarms or jobs,
or post notifications. Startup admission requires bounded, attributed, settled counters and exact
process exit; identity evidence never establishes reminder delivery. Ordinary debug/release builds
retain CalendarApp and their normal components. Provider and reminder probe flags are exclusive.

New ordinary one-off and recurring CREATE operations use receipt6. Initial validation freezes native
values, first-instance bounds, an operation-bound plan digest and a generated UID. Competing writers
adopt the durable prepare winner; retries reuse its UID/plan rather than current authoring rules.
READ-only recovery does not invoke the recurrence compiler or resolve stored timezone IDs. Current
namespace, ACL and capability checks still fence dispatch. Frozen commands and retirement fingerprints
remain unchanged. See [the protocol and acceptance contract](ORDINARY_CREATE_RECEIPT6.md).

A canonical positive master acknowledgement and bounded raw result facts become COMMITTED before
child acknowledgement interpretation or payload reads. Both create modes require exact unique child
IDs at authored alert indices, complete membership/payload and actual first-instance bounds. A minimal
known-ID/calendar/UID binding precedes private reads and is rechecked before verification. Observed
identity drift stays uncertain without private readback. Missing/oversized child evidence retains the
known commit; invalid/lost master acknowledgement never supplies a guessed ID, UID search or replay.
Predictably unrecordable ACK/storage/query budgets block dispatch before a provider batch.

Legacy receipt2/3/5 operations stay on their original wire/protocol and never gain an invented UID
or child proof. Receipt2 remains weaker; receipt3 commits stay conservative, with exact undispatched
retry preserving prepared bytes and earning receipt5 only after its new dispatch. Receipt5 retains
exact ACK bindings and its current-compilation limitation. No legacy attempted/known commit migrates
or replays through recovery. Receipt4 edits remain unchanged. None of this establishes Google/Workspace
delivery, provider-clone admission or physical-device acceptance.

Owned one-off meeting details open a dedicated full-height organizer editor after exact occurrence,
calendar namespace and issued-rights checks. Existing guest identity and response metadata are
read-only; supported guest additions, removals and role changes produce sparse native commands.
Unknown values remain unchanged. Cancellation is a separate confirmed action and excludes unsaved
field/guest edits. Opening, closing, granting permission and restoring a draft never submit a write.
The browser restores the exact owned feature/generation; frozen recovery keeps its original command.
Read-permission loss masks the presentation while retaining in-flight cleanup. A draft replaced by
another session cannot be overwritten or deleted; an explicit close warning permits leaving it.
These local organizer actions do not establish server delivery or recurring meeting support.

The live native creation editor supports authored daily, weekly, monthly and yearly rules with
interval, count or inclusive date limits, explicit master-time review and durable frozen recovery.
A recurring local save is reported only after known-master journal receipt and actual first native
instance verification; it never claims Google delivery. Imported unsupported recurrence remains
preserved and does not imply editing rights.
The phone creation review uses the selected 12/24-hour convention for both first-event bounds
and the short expected-occurrence preview.

Timed editor gestures are connected in Day, three-day and week views. Cross-column moves use
physically visible column bounds (including RTL); continued fragments retain their full original
source and grab offset. Opening another navigation or modal flow cancels a pending drop.

Agenda widget setup, refresh and internal navigation are app-wired. New setup defaults to Busy,
explicit calendar selection is independent of display filters, and title access requires namespace
and read-rights proof. Event clicks revalidate their rendered namespace before opening a private
read-only details screen, preserving filters and drafts. Missing host markers after reboot require
manual reconfiguration rather than adopting stored data without proof. Captured setup contexts
prevent an old form from adopting a known replacement widget slot.

Day-view timed create, move and resize gestures prepare durable native-editor drafts; they never
save automatically. Editing requires fresh provider eligibility and the same occurrence/calendar
namespace before and after preparation. Restoring an unsubmitted edit also requires its original
full calendar identity; missing legacy identity remains under review rather than adopting a new
source token. Frozen recovery retains the original command. Unknown source zones cannot authorize
a timed gesture. Overlaps require an explicit UTC-offset choice; gap alternatives preserve the
original edit time or disclose the ordinary valid-date creation defaults.

Authored recurrence is a distinct new-event input. Until a writer supports that input completely,
it rejects the command before journaling or provider writes instead of creating a one-off event.
Existing one-off operations retain their representation; recurring updates remain on scoped series
operations. Bounded recurrence previews do not establish the provider's complete future expansion.

Native invitation details expose Respond only after the meeting writer issues response rights for
that exact occurrence and calendar namespace. Opening a response creates or reopens a durable draft;
choosing a response and scope never writes until Save. A calendar permission prompt returns without
submitting, so granting access requires another explicit Save. The draft browser restores the same
response request; frozen or uncertain requests are recovered without creating another operation.
A local save refreshes the calendar and does not claim delivery to the organizer.

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

The drawer opens full-height phone Settings, then General. Supported preferences use grouped rows,
switches and focused in-place choice/numeric/zone editors, with independent default-calendar and
reminder-setup actions. The current view is labelled View rather than implying a separate startup
preference. Unsupported account/server settings are absent. Each explicit choice saves only its own
field in the existing atomic calendar preference store. First weekday, hidden weekend columns, declined-event filtering,
device/fixed display zone, system/light/dark theme and weather visibility/units apply to the live app.
An unavailable save retains raw settings for explicit retry; choosing a creation calendar remains
independent. Loading/unavailable preference reads mask all editor controls and system Back while
keeping the owner and exact unfinished input. Successful save acknowledgement closes only its
submitted editor, leaving Settings open. Cancel abandons a failed proposal without a hidden retry.
Opening, dismissing, recreating and typing never save. Legacy bulk-sheet fixtures remain supported.
Weather location resolution still uses device location/device-zone fallback; a display-zone preference
only changes the calendar clock. Fahrenheit converts display values, leaving pure weather rules and
Celsius forecast data unchanged. Theme overrides apply consistently to the grid and weather substrate.
The main Create action starts a timed draft using saved duration/reminder defaults; month/date-sheet
creation starts an all-day draft with its separate reminder default. Existing unresolved reminders
are never reinterpreted through these new-event defaults. Reminder calendar setup and notification
opening retain their separate workflows and authority checks.

Owned guest-free recurring details now use a separate native scoped route. Supported actions are
issued by the provider adapter per scope; the picker never defaults a destructive scope or enables
unknown rights. Entire-series non-temporal edits use the master template and retain exception
payloads; selected existing-exception edits use that occurrence. Supported RRULE templates can create a new linked this-occurrence edit/cancellation through one
asserted public exception transaction, preserving observed bounds and cloned child data. Following
deletion truncates at the original recurrence identity and removes only future exception originals;
raw exclusions and past overrides stay intact. Following edits remain unavailable. Whole-series temporal and pattern changes require explicit
provider-issued TIME/ZONE/rule rights and a proven owned guest-free single-RRULE master without
exceptions or exclusions. Their native editor uses the master DTSTART and logical duration, never
the selected occurrence's day; timed/all-day conversion remains unsupported. Independent rule parts
retain unchanged COUNT, imported exact UNTIL and raw recurrence data. An explicit before/after review
precedes the exact durable WholeEdit command. New series drafts use atomic record comparison with
immutable source namespace, generation and unique revision proofs; missing legacy temporal context
requires explicit review. Submitted legacy/new commands remain read-only recovery operations and
cannot be discarded or reissued under a new ID without a durable retirement fence. Opening an editor
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
Active-alert scope cleanup is independent of SQL delivery-history retention. The notification adapter
must cancel its own unconfigured or provably replaced/removed calendar alerts using bounded active
system notifications, rather than scanning all historical reminder rows. Retained delivery history
still prevents re-alerting when the same calendar is re-enabled. Missing provider observation does not
prove removal; malformed legacy alert scope is unverified. Adapter wiring follows this contract.
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
Notification-open decoding requires canonical app data and a complete post identity. The read-only
resolver verifies the persisted post/token, current enabled namespace and freshly resolved original
occurrence key before fetching details. It follows moved exceptions instead of trusting an old event
number, then rechecks detail namespace, configuration and persisted post before publishing. Stale,
retired, replaced or unverifiable posts expose no private details. Activity presentation remains a
separate private Activity that uses native read-only details without changing filters or interrupting
an editor. It drops old rows when its post/context changes, observes while started, and clears details
when observation stops. A matching interrupted post can open without changing its delivery state or
inventing an acknowledgement. Resolver tests do not establish physical notification navigation;
integrated Activity and device acceptance remain separate evidence. A generic test notification is
available only through an explicit setup action, uses no event/provider/ledger writes, and needs no
calendar access. System acceptance is distinct from a person observing delivery. Real notification
tap journeys now pass local synthetic-provider tests; physical/background/account gates remain pending.

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

Calendar access values must use canonical decimal representation and fit Int before narrowing.
Representable unknown levels remain metadata observations with UNKNOWN capabilities. Range and
detail reads require a known FREEBUSY-or-higher admission before querying private rows; unknown
levels do not trigger Instances, Events or child reads. Known FREEBUSY retains its existing Busy
scrubbing. The known-level decision membership, default ALL projections and ARGB conversion remain
unchanged; this rule does not reinterpret colour bits as access rights.

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

Preserving an imported source through a new exception or independent tail requires an admitted
provider clone profile. A null public query projection does not expose every physical event or
attendee column. Rejecting returned unknown fields alone cannot prove that hidden metadata will
survive. Before production activation, new-exception THIS edit/cancel/RSVP and nonzero-ordinal
FOLLOWING splits must default to review for an unverified provider, both when issuing capabilities
and before dispatch/retry. In-place updates, ordinal-zero ALL-equivalent edits, read-only recovery
and typed retirement retain their independent policies. See PROVIDER_CLONE_OBSERVABILITY_AUDIT.md
for the concrete static finding and required evidence. The gate is implemented; production has no
admitted profiles. Explicit SDK-only synthetic fixture profiles are test evidence, not device
compatibility proof.

Support confirmed deletion, duplicate-as-draft (guests omitted by default), and verified copy/delete
moves for guest-free non-recurring events. Cross-account moves of meetings/series are excluded. Timed
grid drag/resize previews snap to 15 minutes and use the same validation/save path; editor is the
accessible alternative.
Pure gap/overlap previews retain their snapped logical wall target without implying an instant or
choosing an offset. The UI must carry that target into explicit review instead of rounding it again.

### M6 — Recurrence

A pure semantic graph proof recomputes existing planners and conserves every known exception and
exclusion mapping before future transfer execution. It rejects contradictory selected bounds,
clock-uncertain ordinal mappings and excessive aggregate work. Raw date-set provenance and existing
COUNT/end plans remain unchanged. This proof issues no provider authority, clone admission or new
editing capability; unproved RDATE transformations and existing native restrictions remain closed.

Recurring action eligibility must match the first fresh details read's full calendar namespace.
A namespace change between those reads scrubs details and exposes no series action or draft.

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
not establish invitation delivery. Eligible RSVP is connected to the native interface. Owned one-off
meeting creation has a bounded provider executor: exact owner/account identity, one explicit organizer
row, required/optional invited guests and reminders are inserted and asserted atomically. It omits
implicit self-row and organizer flags, journals exact requests, and verifies native rows/occurrence
before local success. Delegated meeting creation remains review-only.

A separate V2 backend supports authored recurring meeting creation only with freshly issued recurring
creation eligibility. Old V1 authority, commands and receipts remain one-off-compatible. The winning
prepared operation stores the exact compiled RRULE/DURATION/first bounds and generated UID once;
retry and recovery adopt that stored plan without recompiling under newer timezone rules. Native
acknowledged parent and child IDs are persisted before post-read verification. A valid parent with
malformed child acknowledgement becomes a self-contained COMMITTED receipt with incomplete-ACK
proof and remains Uncertain without replay or child-ID reconstruction. Reminder verification reads
only bounded ID/event membership from the raw collection and payloads from UID-guarded joined item
queries, followed by repeated membership and parent checks. Source eligibility is advisory; the
actual authored event, guests and batch/receipt budgets are separately validated. Version6 document
schema2 connects issued recurring rights, raw Repeat controls and an explicit typed recurrence review.
Confirmation rechecks exact source rights before a durable frozen command and provider dispatch;
permission grants never submit automatically. The first preview must equal DTSTART. Recurring success
requires ALL scope and the exact first bounds/master/original key; one-off success keeps THIS scope.
Schema1 frozen serialization and recovery remain unchanged. Unfrozen schema1 upgrades only after
explicit fresh calendar selection. Frozen schema2 requests never reparse raw inputs or recompile the
recurrence on recovery/retry. Exact no-op callbacks preserve review; real changes invalidate it.
Real account delivery verification remains separate. The guest-creation interface is connected through eligible unsaved
ordinary events; invitation delivery still requires controlled account verification.

New one-off meeting operations use self-contained receipt2 with the exact issued source,
provider values/child rows, stable generated UID and first bounds frozen once before dispatch.
Concurrent preparation and explicit retries adopt the winning stored plan. Known-commit recovery
works after edit snapshots are removed, requires no WRITE permission and never creates again.
Command/token/signature and retirement formats remain compatible. Historical receipt1 is not
rewritten: it retains its local snapshot dependency and remains Uncertain if that proof is gone.
Malformed child acknowledgement with a known positive parent ID becomes a permanent incomplete
COMMITTED receipt for new operations; no child search, reconstruction or replay follows.

Owned one-off organizer edits, guest add/type/remove and cancellation also have a bounded provider
executor behind the meeting port. It issues THIS-only rights after exact owner/account/organizer and
ACL proof, applies sparse asserted deltas, preserves other responses and unrepresented columns,
and cancels by event status rather than attendee decline or deletion. New guest IDs and linked
reminders are journaled before readback; known-commit recovery is read-only. This backend does not
yet provide an organizer-edit screen. Recurring/delegated organizer changes remain unsupported.

The dedicated version6 meeting-creation draft/controller retains raw guest and event inputs with
exact generation/CAS ownership. Frozen typed requests include a stable raw fingerprint and recover
without reparsing mutable input using a newer validator. A full-height phone guest-entry screen
and generation-checked draft-browser route use this dedicated controller. Incoming guest requests
remain separate and unsupported; source/controller tests are not delivery proof.

Eligible live ordinary CREATE editors expose Add people through explicit host opt-in. This action
atomically replaces the same modern version5 raw draft with a version6 meeting draft, using a fresh
generation, revision and operation ID. Every raw event field is preserved; organizer authority,
guests and policy acknowledgement start empty. The meeting controller requires a fresh eligible
calendar source before submission. The old controller becomes inert once the conditional swap
starts; exact replacement readback is required before navigation, including acknowledged writes.
An uncertain swap leaves one browser-recoverable record and never seeds a second draft. Competing
freezes or autosaves must lose exact-record CAS. Edit, incoming/pinned, legacy, recovery and copied
warning-bearing drafts cannot promote. Opening a meeting route reloads the durable feature and
generation; missing, replaced or corrupt records never create another draft. Local success remains
separate from invitation delivery.
New meeting editors require atomic complete-record draft comparison for creation, replacement and
deletion. A storage/acknowledgement failure requires readback before dispatch; a mismatch retains inputs
for review. Exact normalized no-op callbacks preserve a reviewed command and its draft revision;
actual edits invalidate review. Once a local draft compare-and-set starts, its receipt and local
ownership adoption finish together before caller cancellation is honored. Provider operations remain
cancellable; cancelling during local freeze retains the original request without dispatching it. A sequence of separate load/upsert calls is not atomic. Series/response editors use atomic
records and generations; ordinary editors and connected frozen-command retirement workflows also
use exact-record ownership. A malformed child acknowledgement must never trigger a replay.
Historical receipt formats may retain conservative uncertainty after a partial acknowledgement;
new self-contained meeting receipts preserve known parent identity without claiming complete success.

A pure metadata-only FOLLOWING helper proves its own bounded nominal COUNT/UNTIL partition and
literal native EXDATE groups without trusting a caller-supplied plan. It preserves exception original
identity, actual bounds, untouched metadata and selected dirty-field merge/conflict semantics. Its
output is semantic planning only. Nonzero prefix writes repeat exact unchanged original start,
explicit NULL end, raw duration/zone/all-day/original context alongside the new RRULE so native
instance/raw-time refresh can run; unrelated end-zone metadata stays untouched.
Metadata-only and rule-only independent tails retain the original raw end-timezone, including NULL
and empty string, when appointment/zone/duration are kept. Explicit temporal edits retain their
existing encoding. Already prepared legacy material keeps its exact issued end-timezone rather
than being rebased onto fresh preservation. These checks do not prove installed-provider behavior. Native exception transfer, installed-provider warm
cache acceptance and independently admitted profiles remain separate gates; this helper enables no
new capability.

New exception-free Following operations use family receipt3 to freeze original raw source, exact
prefix context/native tail values, one prepare-winning independent UID and first-instance bounds.
Retries compare that original source without edit snapshots or current recurrence compilation.
Every native assertion/update/delete count and inserted parent/child acknowledgement is bound to
its prepared index before local success. READ-only known-commit recovery requires fresh namespace,
issued identity and complete exact child/native-instance proof, and does not require new clone
admission. WHOLE keeps existing in-place issued-null policies without claiming an unissued stable
identity; SPLIT requires an issued prefix UID or sync identity. Default Unverified still blocks
independent tails. Existing receipt2 retries retain their exact old bytes/protocol, while historical
known commits remain uncertain rather than gaining invented complete ACK evidence. This backend
change activates no EXDATE/exception transfer, clone profile, Google delivery or physical acceptance.
Pre-dispatch frozen source checks read child membership as IDs, then compare known reminder/attendee
items under issued calendar/UID guards with parent checks around payload reads. Empty property sets
are checked without property payloads; nonempty WHOLE properties retain the existing in-place policy.
See [the private Following protocol](FOLLOWING_EXECUTION_RECEIPT3.md).
Pure selected-local-calendar overlap assessment requires exact draft session/revision, source
namespace/generation, proposed time, planning zone, full calendar identities and excluded occurrence
context equality. Timed overlap is half-open; DATE/DATE overlap uses dates, while mixed DATE/timed
occupancy uses deterministic day bounds in the explicit planning zone. Known free, cancelled or
declined observations do not occupy time. Unknown overlapping metadata remains possible, partial
coverage cannot certify no conflict, and empty selection/unavailable reads stay distinct. Only a
complete selected local window with no known or possible conflict may report that advisory result.
This pure contract activates no UI/native read adapter and grants no save authority or coworker
availability claim; later adapters must mask invalidated or permission-lost contexts immediately.

The native selected-calendar advisory reader pins full calendar identities and requests ALL
invitations with declined rows included, independently of display, widget and reminder filters.
It shares provider invalidation and emits Loading before guarded IO for each invalidation. Canonical
raw calendar authority and batched occupancy metadata are checked through two range/metadata passes
under one bounded attempt budget. Unknown observations remain possible; partial, unsupported or
unavailable reads never certify clear time. Complete coverage describes the selected local provider
window only. This read port activates no UI, save authority, coworker availability or cloud guarantee;
permission loss without a provider notification still requires lifecycle masking and refresh.

Editor overlap diagnostics may consume read-only temporal input and draft correlation data only while the current draft lease is usable. Pending persistence, stale or hidden sources, legacy/recovery and frozen/outcome states expose no active binding. Time-only normalization retains existing save semantics, unchanged imported precision and explicit DST choices. The binding exposes no edit token, private field payload or mutation authority. This seam does not activate an advisory interface or alter saving.

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

Incoming Android EDIT suggestions use the same read-only entry resolver and a private version8
review activity. Only an exact freshly readable owned, guest-free one-off source is eligible. The
original token/baseline and caller suggestions remain separate; explicit Apply persists a prepared
ordinary draft before a permanent minimal handoff receipt, and only native Save writes the event.
Unsupported guest/recurrence/field suggestions remain visible and block Apply. Every saved
`incoming-edit:` draft reopens through the private source-pinned activity, including frozen recovery;
a generic editor route cannot bypass that guard. A consumed receipt never reseeds the operation.
READ loss supplies a scrubbed preferences-only environment so known frozen outcomes can recover
without mutable source reads. Permission loss masks editor fields/actions before checking local
frozen proof while retaining the mounted controller and its operation/outcome cleanup effects.
This does not establish device/account delivery or Google interaction parity.

Incoming Android VIEW event/date and INSERT event intents use an exported read-only entry activity.
Explicit Create review opens a private native-editor activity; arrival, Cancel and permission grants
never submit provider writes. Incomplete inputs remain durable, unsupported recurrence and guests stay
visible, and an exact permanent handoff receipt precedes Save access. The browser hides only strictly
recognized handoff receipts, routes unfinished incoming inputs back through their private review,
and blocks generic incoming-native launches without a matching receipt. Date navigation consumes its
session once and converts the original instant using fresh display-zone settings. Private date hints
accept only the factory's canonical URI, with exactly one session value; duplicate parameters,
encoded aliases and alternative numeric date representations cannot select a navigation session.
Eligible EDIT
suggestions use the separate source-pinned version8 review above; recurring/guest editing and live
Google UX verification remain separate.

### Pending invitation display filter

General → Invitations offers All events (the default) and Pending invitations only through a single typed InvitationFilter setting change. The display filter applies to calendar views and search; changing search context immediately hides stale rows and prevents opening a hit from the previous context. Only confirmed pending invitations appear in pending-only results. Unknown invitation classification remains explicit in coverage and qualifies empty search results rather than claiming a complete absence. Reminder, widget, direct-link and conflict reads keep their independent ALL requests. Existing calendar selection, declined-event controls, preference readiness and source/privacy masking remain in force.

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

## Signed candidate release process

A manual release workflow must use an owner-controlled stable key held outside Git, verify its
public certificate fingerprint, embed an increasing version code and create a draft GitHub release
with the APK and checksum after the complete automated gate. It must never generate a replacement
key or publish automatically. Draft candidates remain pending until account/device/upgrade and
seven-day beta acceptance is recorded. See [RELEASE_SIGNING.md](RELEASE_SIGNING.md) for provisioning
and the one-time debug-to-stable certificate transition.

## Optional local guest suggestions

A shared Add people picker may search the current Android user's local DEFAULT contact email
directory only after an explicit opt-in for that picker session. Opening it or typing alone never
requests contacts access. Decline, denial or revocation leaves manual email entry available. No
cloud directory lookup, contacts write, account enumeration, contact history or managed-profile
aggregation is introduced. Permission prompts remain host-owned.

Suggestions carry only raw email/name strings and no contact identity or calendar authority. Only
byte-identical suggestion tuples may be deduplicated; differing names/addresses remain explicit
choices. Selection appends a fresh raw row through each feature's existing serialized draft path;
it does not normalize existing rows, select a new role, merge opaque guests or submit an event.
Create/owned-edit advisory and authoritative validation keep their existing rules. The picker
closes only after its existing raw persistence acknowledges Done; lost acknowledgments, stale
callbacks and foreign generations preserve the current draft/recovery guarantees.

The main meeting-create and owned-edit workflows supply the bounded local contact source and an
explicit READ_CONTACTS launcher. Grants/resume refresh permission state only; they never append a
guest or submit a calendar write. Picker opt-in resets independently on every opening, so a late
grant cannot enable a newly opened session. The existing String-name draft format encodes a chosen
null suggestion name as empty on append; both raw source tuples remain visible and are never merged
or removed. Selection feedback may mark null/empty names as the same projected draft addition.

The people picker and editable new-guest email rows request an email keyboard. Its IME Done action
only clears focus and hides the keyboard: it does not append the query, finish the picker, request
contacts access or save the event. Raw partial or invalid addresses remain in the existing draft path.
The picker exposes a named accessibility pane and heading. Native IME-first Back and TalkBack
spoken behavior still require device verification.

Existing fixed phone-editor save/conflict outcomes and stable create/owned draft errors expose
polite live-region semantics. Raw fields and private review payloads do not become announcement
regions. Shared focused picker panels expose named panes and headings; authority masking removes
their messages, private fields and open child panes while retaining the backing draft. Ordinary
DRAFT_UNAVAILABLE remains visible and traversable but is intentionally non-live: failed debounced
autosaves can clear/recreate that warning while the user types. No guessed announcement history or
new persistence seam is introduced. Material date-picker announcements remain intact, and actual
TalkBack spoken order/repetition still requires device acceptance.


### Field-only phone preference saves

An explicit phone-settings choice atomically writes only its own field (or its timezone/reminder
key pair), preserving unrelated raw preferences, current view, calendar filters and defaults.
Concurrent independent choices do not replace a stale whole settings object; the latest explicit
choice for the same reversible display field wins. Invalid values do not enter storage. IO failure
returns unavailable for explicit retry, and cancellation propagates. Opening, typing, dismissing or
recreating settings does not itself save. This preference API does not change event or reminder-policy
conflict rules; the legacy bulk settings API remains available to its existing callers.


## Exception-free Following EXDATE metadata bridge

The approved [receipt4 contract](FOLLOWING_EXDATE_RECEIPT4.md) defines the bounded adapter implementation
of metadata-only Following edits with independently decoded EXDATE and no exception graph. It freezes
exact lexical partitions and native EXDATE writes, preserves nominal COUNT/end limits, and refuses
unsupported syntax/excluded selection. Legacy2/3 operations retain their issued bytes and recovery;
no clone/profile or account compatibility is inferred. Focused source/provider checks pass; the
complete2,359 aggregate gate passes; native/account acceptance and exception-graph transfer remain pending.


## Adaptive phone Month overview

At normal text size and an adequate phone viewport, the existing six-week Month grid fits all seven
columns and six rows at360×640 and393×851. Available space and measured font line sizes set row and
summary budgets; date targets remain at least48dp and crowded dates cannot grow fitted rows. Text
is not shrunk. Dates, first weekday, adjacent dates, hidden/selected-weekend access, app-local filters
and exact occurrence identity retain their existing algorithms.

A compact date is one accessible action opening the complete existing day-event list. Plain event
summaries and a truthful hidden count use the available slots; no tiny independent event button is
introduced. Summary text remains merged/discoverable under that date action. The full list opens each
exact event. Detailed/wide/large-text or constrained layouts retain reachable scrolling and existing
shortcuts. Four affected/new captures were recorded/inspected; functional checks and the combined
2,359-test/debug/lint gate pass. Exact current Google main-screen gesture/row-count/pixel parity remains unverified.


## Isolated whole-series graph duration receipt contract

The independent `SERIES_GRAPH_DURATION_V1` family uses receipt version 1 and the fingerprint
prefix `series-graph-duration-v1:`. Its explicit command contains only the selected recurring
reference, exact source token and a positive whole-second timed duration or positive whole-day
all-day duration. Issued execution capability defaults to UNKNOWN; readable source and a complete
semantic graph proof do not grant write authority.

The strict codec binds the exact unchanged canonical sourceV1 bytes, command, complete calendar
namespace including provider ID, all live membership, moved/cancelled exception identities and
actual bounds, unchanged child sets and raw exclusions. It recomputes the bounded recurrence graph
proof and derives an expected postimage changing only the existing master DURATION. Every exception
and all other raw fields remain exact. Native all-day boundaries and year limits, original-selected
source-duration bounds, strict phase/acknowledgement evidence and worst committed-envelope limits
are validated before a receipt can be created or adopted. RDATE/EXRULE temporal changes remain closed.

This is an inert command/port and receipt codec only. No provider executor, editor/frozen-submission
activation, retirement branch, native authority, clone profile or schema change is enabled. Existing
WholeEdit guards, sourceV1 bytes, legacy fingerprints and numeric/frozen/receipt families are not
reinterpreted or migrated. Provider, account and device acceptance remain separate gates.
