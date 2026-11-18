# Google Calendar Android feature and workflow audit

This document preserves the original audit baseline. For the later implementation, see the
[151-item reconciliation](audit/implementation-reconciliation.md) and the
[current progress ledger](DAILY_DRIVER_PROGRESS.md). Historical defect probes and totals below are
not current feature-completion counts.

Daylight needs a broader read/write calendar product to satisfy the expected everyday workflows:
choosing calendars, navigating months, creating appointments, managing meetings and receiving
reminders. The present implementation is a read-only day screen with weather. Its successful build
and screenshot tests do not establish those missing workflows.

The audited production baseline is main commit `0ab431a` (implementation `1152119`). This audit adds
documentation, synthetic diagnostic probes and their evidence. It does not change application
behavior or amend the product scope in SPEC.md.

## Deliverables and interpretation

- [Feature inventory](audit/feature-inventory.csv): 151 individually identified checks, each with
  current status, priority, scope, implementation route, acceptance criterion, code evidence and a
  reference. These are workflow and quality checks, not 151 equally weighted Google features.
- [Inventory totals](audit/inventory-summary.json): 110 missing, 5 confirmed defects, 5 partial,
  10 unverified, 5 provider-dependent, 2 delegated and 14 implemented checks.
- [Diagnostic observations](audit/evidence/probe-results.json) and
  [reproducible probes](audit/CalendarAuditProbeTest.kt): five current defects reproduced on the JVM.
- Screenshots use synthetic events only. No installed Google Calendar app or physical phone was
  exercised. Google Android behavior is based on its official documentation; device-dependent
  behavior is marked unverified rather than inferred from a passing unit test.

Status meanings:

| Status | Meaning |
|---|---|
| Implemented | Source and existing checks support the bounded capability described. |
| Missing | No implementation of this action or contract exists in the audited app. |
| Partial | Some behavior exists, but it does not meet the complete acceptance criterion. |
| Delegated | Another installed calendar app performs the workflow. |
| Provider-dependent | Data can arrive from Android's provider; Daylight does not own the upstream feature. |
| Unverified | Requires device, provider or lifecycle validation; it is not counted as a confirmed defect. |
| Defect | An implemented behavior has a specific reproduced failure below. |

P1 means needed for dependable everyday calendar use, P2 means important completion, and P3 means
optional integration or wider scope. P1 is not a one-sprint commitment: the dependency order below
determines what can be implemented first. No percentage of Google Calendar parity is claimed.

## Confirmed defects in implemented behavior

Passing these diagnostic probes means the current defect was reproduced. It does not mean the
behavior is correct. The probes live outside the normal test suite so future work is not required
to preserve a defect just to keep ordinary CI green.

| Finding and inventory ID | Reproduction and actual result | Required behavior |
|---|---|---|
| D1 All-day overflow, `QA-01` | Render five all-day events. Only the first three have nodes. `+2 more` has no click action, and events four/five are absent. | An overflow action exposes and opens every remaining event. |
| D2 Fall-back duration, `REC-08` | London, 25 October 2026: `00:45Z` to `01:15Z` is 30 elapsed minutes, but its clock times are 01:45 BST to 01:15 GMT. `timedEvents` clamps the end minute to the start and produces zero duration before minimum-height expansion. | Adopt an explicit repeated-hour representation that preserves duration and disambiguates offsets. Do not silently replace a positive-duration event with a short reminder. |
| D3 All-day accessibility, `DET-12` | A UTC-date all-day event for 17 November exposes an accessibility description in Los Angeles of `Holiday 1, 16:00 – 16:00`. | Announce all-day status and the intended date/date range, without converting the storage boundaries into appointment times. |
| D4 Large text, `OS-08` | At 200% font scale, a short event's text layout reports height 18px and `overflowHeight=true`. The screenshot also shows clipped date-circle text and wrapped hour labels. | Adapt gutter/header sizing and short-entry presentation to scaled text; preserve readable alternatives in crowded layouts. |
| D5 RTL geometry, `OS-07` | In a right-to-left layout, the event starts at physical x=4, the hour labels move right, but the grid's vertical gutter stays at physical x=56 on the left. Weather and header mirroring also disagree with the grid. | Use one coherent logical-coordinate policy for the header, labels, grid, events, current-time line and weather. Verify bidirectional time-range presentation too. |

Evidence: [all-day overflow](audit/evidence/all_day_overflow.png),
[large text](audit/evidence/large_font.png), [RTL geometry](audit/evidence/rtl.png).
The exact numeric observations are retained in the diagnostic JSON.

Relevant source: [all-day truncation](../app/src/main/kotlin/dev/swapnilraj/calendar/ui/DayHeader.kt),
[clock-minute conversion](../app/src/main/kotlin/dev/swapnilraj/calendar/ui/EventLayout.kt),
[event semantics](../app/src/main/kotlin/dev/swapnilraj/calendar/ui/EventBlock.kt), and
[grid geometry](../app/src/main/kotlin/dev/swapnilraj/calendar/ui/DayGrid.kt).

The event DST defect is separate from the existing weather DST tests. Correctly choosing forecast
samples across the clock change does not verify how an appointment spanning the repeated hour is
drawn. The fixed-24-row grid needs an explicit product decision for repeated hours before this is
fixed; simply substituting elapsed duration into the current clock-position algorithm is incomplete.

## Calendar sidebar acceptance contract

Inventory `CAL-01` through `CAL-13` covers the requested sidebar. Its purpose is to control which
calendar data is displayed, with clear account and permission context.

1. Query calendar metadata and retain calendar ID, display name, account name/type, owner, display
   color, visibility, synchronization status and access level where exposed.
2. Group calendars by account, identifying accounts with both name and type. Distinguish multiple
   calendars with the same display name; never use a color or name as the data identity.
3. Show an accessible labeled checkbox for each displayable calendar, plus its color and relevant
   read-only/availability context. Google, Exchange and local calendars must not be assumed identical.
4. Apply selection consistently to day, month, week, schedule and search results. Changing one
   checkbox must not hide a second calendar in the same account.
5. Persist selection across a fresh launch. Handle newly discovered calendars, removed accounts,
   renamed calendars and an unavailable remembered default without losing unrelated preferences.
6. Distinguish an empty selection, no retained calendar data, and denied provider access. Each needs
   a useful recovery action rather than the same blank grid.
7. Keep display filtering and server synchronization separate. Android documents `VISIBLE` and
   `SYNC_EVENTS` as different fields. Choose and document app-local filtering versus provider
   visibility updates; do not silently turn off synchronization to hide a calendar.
8. Test provider-hidden and unsynchronized calendars explicitly. The provider documents that
   visibility affects occurrence generation, so deleting the current `VISIBLE = 1` selection alone
   does not establish that all calendars can be displayed. State any write-permission or sync
   prerequisite rather than presenting an ineffective checkbox.
9. Provide a separate default writable-calendar selection for creation. A viewable calendar is not
   necessarily writable, and event-level guest permissions can further limit edits.

Current blocker: [CalendarRepository](../app/src/main/kotlin/dev/swapnilraj/calendar/data/CalendarRepository.kt)
reads seven occurrence fields but not calendar identity; [CalEvent](../app/src/main/kotlin/dev/swapnilraj/calendar/data/CalEvent.kt)
cannot support reliable per-calendar selection or permission-aware actions yet.
The provider's [calendar metadata and visibility contract](https://developer.android.com/identity/providers/calendar-provider#calendars)
and [access-level reference](https://developer.android.com/reference/android/provider/CalendarContract.Events)
are the implementation basis. These acceptance choices are Daylight requirements, not a claim that
Google exposes identical internals.

## Month view and navigation acceptance contract

The current month name is a `Text` node, and navigation is a day pager. Month view requires a new
range-oriented presentation and data path, not forty-two embedded day screens.

1. Render real calendar dates with the chosen first weekday. Verify leap-year February, December to
   January, adjacent-month cells and a month requiring six rows.
2. Navigate adjacent months, jump to an arbitrary date, and return to today. Switching month/day/week
   retains a well-defined selected date instead of unexpectedly jumping to the device's current day.
3. Present timed and all-day events using the same calendar filters and identities as the day view.
   Multi-day events need consistent continuation and week-boundary treatment.
4. Ensure every crowded day's events remain reachable through an overflow/day-details interaction;
   copying the current noninteractive all-day `+N` treatment would reproduce D1.
5. Opening an occurrence must retain its event identity and instance start/end, including recurrences.
6. Define what tapping a date, an event and empty cell space does. Creation from month view must
   preselect that date and clearly distinguish all-day creation from timed-event entry.
7. Introduce a date-range repository API, a shared observer and grouping suitable for visible ranges.
   Reusing the current single-day observer separately for every cell would multiply provider work.
8. Preserve exact local/UTC all-day boundaries when building the range. Do not calculate a month
   or week as a fixed multiple of 24 elapsed hours across DST.
9. Establish a month-specific weather design. Hourly streaks cannot simply be stretched into a date
   cell; summaries must respect coverage limits and leave unavailable days as normal calendar cells.
10. Verify large text, RTL, narrow and wide windows, selection semantics and responsive rendering with
    dense synthetic calendars.

The missing navigation family also includes week, three-day, schedule/agenda, view selection, saved
view preference and event search. Google's Android [view/date navigation](https://support.google.com/calendar/answer/6110849?co=GENIE.Platform%3DAndroid&hl=en)
and [event search](https://support.google.com/calendar/answer/37176?co=GENIE.Platform%3DAndroid&hl=en)
are the comparison baseline; `NAV-*` records the individual checks.

## Event creation and editing acceptance contract

The manifest currently requests read access only. There is no native form or event-writing layer.
The existing view intent is a details handoff; it does not implement creation or editing.

1. Provide a create control and a grid-slot entry point. Prefill the selected date/time and configured
   duration; a draft on a browsed future date must not accidentally start today.
2. Request write access when needed while preserving read-only use after denial. Support a clear
   handoff alternative if a native write cannot be performed; label that as delegated behavior.
3. Choose a valid writable calendar. Account disappearance, read-only calendars and per-event guest
   permissions must be handled independently of the existence of `WRITE_CALENDAR`.
4. Cover title, start/end dates and times, time zones, all-day/multi-day state, calendar, location,
   notes and reminders. Offer colors, availability, privacy and guest fields only where supported.
5. Validate time ranges and date boundaries. All-day saves must preserve UTC-date/exclusive-end
   storage rules instead of applying ordinary device-zone appointment conversions.
6. Retain drafts during rotation and recoverable failures. Saving must not happen as a side effect
   of cancelling, restoring state or navigating away.
7. Prevent repeated Save taps from producing duplicates. Plan event, reminder and attendee writes
   consistently, including failure handling; do not report a partially constructed event as success.
8. Reload the saved occurrence and update the visible range. Distinguish a completed provider write
   from verified server synchronization and delivery of invitation emails.
9. Load an existing event without overwriting fields that the editor does not understand. A calendar
   row's access level alone is insufficient to establish that every event action is allowed.
10. Separate organizer cancellation, attendee removal/decline, duplication and ordinary editing.
    Each operation needs the appropriate occurrence/series scope and user-visible result.

Google's [Android editor](https://support.google.com/calendar/answer/72143?co=GENIE.Platform%3DAndroid&hl=en)
and [event visibility controls](https://support.google.com/calendar/answer/34580?co=GENIE.Platform%3DAndroid&hl=en)
establish the user-facing family. The detailed failure/draft/atomicity checks above are proposed
Daylight quality requirements, not claims that Google's implementation has been experimentally tested.

### Recurrence must be a distinct implementation task

Reading expanded `Instances` rows does not supply the write contract for a recurring series. Keep
master-event identity, original occurrence time, existing exceptions, recurrence data and relevant
time zones available. Implement individual, future and whole-series operations with tests showing
which occurrences changed and which did not.

A future-series edit needs particular care around a moved existing exception, a month-end pattern,
an all-day occurrence and a DST transition. A local ID is not a substitute for all this metadata.
The current model retains none of it. See Google's [series-edit scopes](https://support.google.com/calendar/answer/37115?co=GENIE.Platform%3DAndroid&hl=en)
and Android's [original-instance and exception fields](https://developer.android.com/reference/android/provider/CalendarContract.Events).

## Remaining workflow families

The CSV contains the individual checks and criteria; this table identifies what each family means
for implementation rather than treating a visible button as complete support.

| Family | What a complete workflow still needs |
|---|---|
| Native details | Full title, description, calendar/account, organizer, guests, recurrence, reminders, dates/zones and safe location/link actions. Presently delegated; the data projection must grow first. |
| Invitations | Actual attendee status, supported RSVP and notes, proposals, guest permissions, verified provider behavior and appropriate organizer actions. A local write is not proof of guest notification. |
| Meeting access | Opening an existing exposed URL is different from generating conference metadata. Do not make ordinary join links depend unnecessarily on a Google account integration. |
| Reminders | Display/edit stored values, obey calendar limits, choose notification ownership, handle all-day dates, permission denial, rescheduling, deletion, reboot/time-zone changes and coexistence with other apps. |
| Search and agenda | Query a defined provider-backed range, preserve occurrence identity, apply calendar filters and distinguish no matches from loading or unavailable data. |
| Widgets and Android entry points | Month/schedule widgets, incoming event/date/edit/insert intents, and external-keyboard workflows. Current manifest has only the launcher entry point. |
| Settings | View and calendar defaults, theme override, display zone, week settings, declined/short-entry preferences, and localized interface resources. Current durable setting is only hour height. |
| Reliability | Real Google/non-Google provider comparisons, access changes, large/dense calendars, state restoration, tablet/window sizing, TalkBack and offline editing/sync reporting. |
| Weather | Preserve the existing tint/alignment logic; add the planned hour readout and explicit designs for new views. City/unit/layer preferences are Daylight extensions, not Google feature gaps. |

References: Google's [RSVP workflows](https://support.google.com/calendar/answer/37135?co=GENIE.Platform%3DAndroid&hl=en),
[notification settings](https://support.google.com/calendar/answer/37242?co=GENIE.Platform%3DAndroid&hl=en),
[widgets](https://support.google.com/calendar/answer/10249848?co=GENIE.Platform%3DAndroid&hl=en),
[keyboard shortcuts](https://support.google.com/calendar/answer/37034?co=GENIE.Platform%3DAndroid&hl=en)
and [general preferences](https://support.google.com/calendar/answer/6084644?co=GENIE.Platform%3DAndroid&hl=en).

## Architecture boundaries that affect the backlog

| Boundary | Consequence and decision needed |
|---|---|
| Calendar identity is discarded | Add metadata and calendar IDs before sidebar filters, destination selection and access-aware actions. |
| Occurrences and master events are different | Extend the read/write model before recurrence editing; retain occurrence times through navigation and intents. |
| Local IDs are not Google API IDs | If service integration is selected, implement and verify a mapping strategy. Never send the current local numeric ID as though it were a cloud event ID. |
| Visibility and synchronization are separate | Choose app-local filters versus provider visibility writes, and explain unavailable provider data. |
| Data API is single-day | Add a range API and shared invalidation before month/week/search; preserve UTC all-day and local timed-event boundaries. |
| Notification ownership is external today | Choose delegated versus Daylight delivery before promising an independent replacement; otherwise duplicate or absent notifications are possible. |
| Ordinary provider access is per profile | Cross-profile calendar data has a separate enterprise/admin policy contract. Normal personal-profile queries do not establish access to a managed profile. |
| Google-specific data is not generic calendar data | Tasks, Drive files, cloud ACLs and specialized Workspace state need explicit service support or handoffs; do not infer them from Instances. |
| Existing privacy scope excludes service calls | Broader Google integrations require a deliberate specification change for authorization and data handling. Native views and ordinary provider edits do not inherently require a custom cloud backend. |

Google's [event resource](https://developers.google.com/workspace/calendar/api/v3/reference/events)
has its own event and calendar identities; the current local row ID alone is not an integration
mapping. An empty device-provider result also does not prove that no cloud events exist: account
sync, provider coverage and Google's data-sharing settings need troubleshooting rather than a
confident but unsupported diagnosis.

Google supports [personal/work calendar display subject to administrator policy](https://support.google.com/calendar/answer/15620294?hl=en).
Android documents separate [enterprise occurrence URIs and access conditions](https://developer.android.com/reference/android/provider/CalendarContract.Instances#ENTERPRISE_CONTENT_URI).

Optional service scope includes [Tasks](https://support.google.com/tasks/answer/9901136?co=GENIE.Platform%3DAndroid&hl=en),
[Drive attachments](https://support.google.com/calendar/answer/6192039?co=GENIE.Platform%3DAndroid&hl=en),
[calendar sharing](https://support.google.com/calendar/answer/37082?co=GENIE.Platform%3DAndroid&hl=en),
conference creation and eligible Workspace controls. Public APIs must be checked per capability;
Google's own app UI is not a promise that an equivalent public API exists. Basic guest/RSVP behavior
through a device provider also requires real-device validation.

Do not count appointment-schedule creation, file import/export, trash restoration, printing or
desktop-only views as phone-app baseline failures. Google's [booking creation](https://support.google.com/calendar/answer/10729749?hl=en)
requires a computer, and its [trash workflow](https://support.google.com/calendar/answer/37113?co=GENIE.Platform%3DAndroid&hl=en)
identifies restoration as a computer operation. These remain separately labeled wider-ecosystem rows.

## Implementation order and completion gates

These are proposed backlog slices, not changes already authorized or implemented by this audit.

| Order | Bounded change | Depends on | Completion gate |
|---|---|---|---|
| 1 | Correct reproduced day-view defects | Explicit repeated-hour policy for D2 | D1-D5 converted to correct-behavior regression tests; reviewed RTL/large-text/all-day screens. |
| 2 | Calendar identity, capabilities and date-range reads | Existing provider | Multiple accounts, read-only calendars, hidden/sync-disabled data and UTC all-day ranges represented correctly. |
| 3 | Sidebar and persisted filters | 2 | Toggle one calendar, restart, switch views and remove an account without leaking selection or losing other preferences. |
| 4 | Date picker and month view | 2, 3 | Six-row/leap-year months, distant navigation, filtered events, crowded-day overflow and occurrence opening all work. |
| 5 | Native event details | 2 | Full event and permission context is readable without another calendar app, including all-day accessibility and links. |
| 6 | One-off native editor | 2, 3, 5 | Create/edit/delete on an eligible calendar; permission denial, rotation, double-save and recoverable failures are covered. |
| 7 | Recurrence and invitation writes | 6 | One/future/all scopes and supported RSVP actions verified against actual account providers, with explicit sync/notification semantics. |
| 8 | Week, three-day, agenda and search | 2, 3, 5 | Shared date/filter state, range performance, crowded access and view-specific weather are verified. |
| 9 | Reminder ownership and Android integrations | 5, 6 and a delivery decision | Alerts, widgets and intents target the right occurrence and behave correctly after updates, reboot and permission changes. |
| 10 | Optional Google services | Separate product-policy and API-coverage decisions | Each capability has verified authorization, identity mapping, supported account conditions and a fallback. |

The requested sidebar, month view and event creation correspond to orders 2-6, with the shared data
foundation done once. This order makes the data and permission dependencies visible instead of
equating three new screens with a complete calendar product.

## Reproduction and validation

Run with JDK 21 and the project's configured Android SDK:

```sh
scripts/run-feature-audit-probes.sh
./gradlew test :app:assembleDebug
```

The probe runner temporarily copies its source into the app test directory, refuses to overwrite an
existing file, and removes its temporary source on exit. Diagnostic images go to `app/build/audit/`;
normal committed screenshot baselines are not modified. Probe assertions describe this audited
baseline's defects and should be revised or retired when those defects are fixed.

The five probes passed with five corresponding defect observations. The normal suite remains a
separate validation of the current implemented scope: after probe cleanup,
`./gradlew test :app:assembleDebug` passed with the 40 existing tests and unchanged screenshot
baselines. A physical-phone comparison, real provider
write behavior, actual TalkBack operation and performance benchmarks remain distinct acceptance
work; none is inferred from these JVM results.
