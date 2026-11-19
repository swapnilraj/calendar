# Implementation reconciliation: completed checkpoint and later source review

This snapshot reconciles all **151 historical inventory IDs**, in their original order, against
integrated source at **bf2ef3f**. The companion [CSV](implementation-reconciliation.csv) records
bounded behavior, source/test evidence and remaining acceptance. The original
[feature inventory](feature-inventory.csv), baseline audit/probes and physical `NOT_RUN` evidence
are unchanged. The CSV remains a historical reconciliation at `bf2ef3f`, not a rewrite of the audited
`23808c1` baseline. The later source review below records bounded changes at `e1877a4` without
changing those CSV rows. The dated source review at `6ee8f6e` below supersedes older
implementation-gap statements while preserving their historical cutoffs.

The lead reports that the complete required test, debug APK assembly and lint gate passed at
`bf2ef3f`, with no failures, errors or skipped tests. This is local synthetic-provider evidence,
not acceptance of every requirement, actual Google synchronization or a signed daily-driver release.
This docs packet inspects source and existing evidence; it does not run Gradle or device/account tests.

Earlier attempts retain their original meaning:

- The previous reconciliation cutoff, `da9af95`, recorded a failed aggregate attempt caused by an
  older draft-browser namespace fixture. Widget application integration was still unfinished then.
- `5bd64d4` was a later complete local gate, including connected widget and recurring-creation flows.
- The `bdc3f1c` attempt failed two recurring journey fixtures that omitted explicit permission
  preflight; `60502c4` repaired those fixtures and their focused journeys passed.
- The `8b0de43` attempt failed one conditional COUNT-field test interaction. The fresh-field
  synchronization repair is integrated as `58070dd`; its focused journeys and the later `bf2ef3f`
  aggregate passed. These later results do not turn the earlier failed attempts into passes.

## Reading the evidence

| Status | Meaning |
| --- | --- |
| `verified-locally` | The named bounded behavior is connected and has local source/test evidence. Physical and Google acceptance remain separate. |
| `partial` | A useful subset exists, or only backend/presentation/contract work exists; the inventory acceptance is not complete. |
| `not-implemented` | The named action is not enabled. Related metadata or fixed defaults do not implement an editable feature. |
| `external-pending` | Acceptance requires device/account/provider evidence that has not been run. |
| `excluded` | Outside the accepted Android daily-driver release scope. |

`live-real-adapters-synthetic-provider` exercises connected application routes with actual adapters,
SQL and synthetic provider transactions. `live-synthetic` may use injected mutation ports.
`backend-real-adapters-synthetic-provider`, `component`, `pure` and `contract-only` do not mean an
app action is discoverable or enabled. Read each row's remaining acceptance; test totals are not a
completion measure. A SQLite reopen or Activity recreation is not an actual process-kill test.

## Additional source review — 19 November 2026, at 6ee8f6e

This additive review preserves the historical CSV, inventory IDs and earlier failed attempts. Its
source cutoff includes later ordinary recovery, incoming EDIT, meeting interfaces, contacts and
private-activity cleanup fixes. Source presence and focused synthetic checks do not complete the
inventory's device/account/beta acceptance. This documentation review runs no Gradle or account/device
operations. The lead's required full gate at `6ee8f6e` passed **2,177 tests** (app1,903,
core257, weather17), debug APK assembly and lint, with zero failures, errors or skipped tests.
Command: `./gradlew test :app:assembleDebug :app:lintDebug`; session log:
`/tmp/daylight-contact-picker-full-gate.log` (6m10s; completed19 November2026 at00:00UTC).
The earlier `0a122bc` gate passed2,124 tests. These later passes supersede the old unresolved-build
status while preserving the earlier failed attempt. See [the progress ledger](../DAILY_DRIVER_PROGRESS.md)
for subsequent checkpoints and external acceptance. No device/account/beta gate is inferred from them.

- **Ordinary draft/recovery completion (EDIT-16/17/18):** the historical `e1877a4` concurrency failure
  is not the current unresolved build result. Subsequent fixture/concurrency corrections and the
  completed gate above supersede that status without changing the original failure evidence.
  `data/calendar/OrdinaryEditReceipt.kt` and `CalendarWriter.kt` now freeze newly prepared ordinary
  UPDATE/DELETE source and native changes in receipt4. READ-only recovery survives `edit_snapshots`
  removal and requires exact native event, child membership/payload, occurrence and namespace proof.
  Incomplete acknowledgments and changed UID/sync identity remain uncertain without replay.
  `CalendarWriterTest.kt` covers snapshot purge/reopen, exact retry, drift, child acknowledgments,
  cancellation and legacy compatibility. Historical receipt2 edits retain their snapshot dependency.
- **Public EDIT and authored missing zones (OS-05/EDIT-08):** public typed/untyped EDIT is now
  manifest-enabled through `CalendarEntryActivity.kt`, with the non-exported
  `CalendarEditReviewActivity.kt` continuation, explicit Apply, generation-bound ordinary handoff and
  durable receipts. `CalendarEditReviewServices.kt` issues separate authored-zone intent when an
  explicit proposed zone applies to a timed NULL-zone source, even if it equals the renderer.
  Zone-only changes retain TIME.Keep; changed times without a proposed zone remain under review.
  Arrival, grant and reopening do not author a mutation. Evidence: `PublicCalendarEditJourneyTest.kt`,
  `CalendarEditZoneAuthoringTest.kt`, `PublicCalendarEditZoneJourneyTest.kt` and the ordinary
  `events/NullZoneTravelEditTest.kt` checks. Earlier missing-activation/zone-intent statements are
  obsolete for this cutoff; imported unsupported/frozen/legacy formats are not silently reinterpreted.
- **Meeting creation and owned organizer UI (GUEST-04/06, DET-13):** `ui/LiveCalendarDay.kt` now
  connects ordinary Add people promotion, `MeetingCreationWorkflow.kt`, and the owned one-off details
  route through `OwnedMeetingWorkflow.kt`. Dedicated codecs/controllers preserve feature/generation,
  raw guests, source authority and frozen commands. Creation schema2 connects typed authored
  recurrence review; owned one-off editing supports issued sparse guest changes and separately
  confirmed cancellation while preserving other responses and opaque fields. Evidence:
  `NativeMeetingCreateJourneyTest.kt`, `NativeRecurringMeetingCreateJourneyTest.kt` and
  `NativeOwnedMeetingJourneyTest.kt`. New one-off meeting receipt2 and recurring meeting receipts provide
  source-free recovery; historical one-off receipt1 retains its snapshot dependency. This does not
  implement recurring organizer guest changes or establish invitation/update/cancellation delivery.
- **Optional device contacts and phone people entry:** `contacts/DeviceContactSuggestionSource.kt`,
  `data/contacts/AndroidDeviceContactSuggestionSource.kt` and `ui/events/meetings/people/PhoneGuestPicker.kt`
  are connected through `CalendarApp.kt`, `ui/DaylightContent.kt` and the create/owned workflows.
  An existing OS grant alone does not opt in a picker session. The adapter queries only local DEFAULT
  email suggestions with bounded projection/work and cancellation, with no remote directory search/account enumeration,
  contact writes or new network service. Selected strings append to existing raw draft rows with no
  guessed role or event save; the existing draft representation encodes a missing name as empty.
  Five actual-host synthetic checks in `NativeContactGuestPickerJourneyTest.kt` passed, covering
  pre-grant opt-in, active and late grants, recreation and owned guest preservation with zero calendar
  batches. The contacts packet adds20 adapter checks,24 picker/session/parent checks, those five
  actual-host journeys and four screenshot checks. `events/meetings/people/PhoneGuestPickerScreenshotsTest.kt`
  covers manual, selected results, denied/dark and large-text RTL presentations. Physical contacts
  permission behavior and the Google Calendar people-picker interaction remain unverified.
- **Private review lifecycle and exact cleanup:** `CalendarSeedReviewActivity.kt`,
  `CalendarEditReviewActivity.kt` and `ui/EventWriteWorkflow.kt` retain an accepted write/known-outcome
  owner while stop/resume or authority refresh masks its presentation. Four unchanged controlled
  `PrivateReviewCleanupLifecycleJourneyTest.kt` regressions failed before the fix and passed after it:
  CREATE/EDIT, with/without a newer successor, acknowledge an already applied exact cleanup once,
  finish with RESULT_OK and preserve the successor with one provider batch. Permission-mask component
  checks remain separate. An Activity transition is not an OS process-kill or physical lifecycle test.
- **Remaining recurrence admission and phone UX:** production still supplies
  `ProviderClonePolicy.Unverified`; no provider clone profile is admitted. The LOCAL API35
  [18-case cache experiment](evidence/native-local-provider-api35-2026-11-18.json) is bounded native
  evidence, not whole-writer, hidden-metadata or Google/Workspace certification. Core
  `RecurrenceFollowingMetadata.kt` remains metadata-only; native Following execution still rejects
  exception transfer/exclusions. Generated THIS/nonzero-tail operations remain intentionally gated.
  Empty-grid creation still requires long press in `ui/grid/TimedGridGestureInput.kt`; tap-slot quick
  creation and other phone reference reconciliation remain source work, not completed parity.

Personal/Workspace round-trips, offline conflict acceptance, physical reminders/Doze/reboot/timezone/
TalkBack/upgrade checks, the seven-day work/personal beta, stable owner signing and durable published
release remain pending. The prepared draft-release mechanism and fixture-key tests do not satisfy
those gates. Google Calendar main flows/exact parity remain unverified; this review preserves every
external `NOT_RUN` status. Source and test paths use the same relative roots described below.

## Later integrated source review at e1877a4

The following corrections supersede the corresponding pending-implementation statements in the
historical sections below. This is a source/test-evidence review, not a new complete acceptance
checkpoint. The latest required aggregate attempt at `e1877a4` failed
`AtomicDraftStoreTest.concurrentAbsentCreatesHaveExactlyOneWinnerAndNeverReplaceIt`; the current
full test/debug APK/lint gate remains pending. No Gradle or device/account tests were run for this
documentation update.

- **Public incoming entry points (OS-03/04/05):** VIEW event/date and INSERT are manifest-enabled
  through `android/navigation/CalendarEntryActivity.kt`. INSERT uses the private
  `CalendarSeedReviewActivity.kt`, durable version-7 raw review, and permanent handoff receipts;
  arrival, permission grant and reopening do not submit an event. Evidence:
  `PublicCalendarEntryJourneyTest.kt`, `CalendarEntryActivityTest.kt`,
  `CalendarSeedReviewServicesTest.kt` and `CalendarSeedRefreshActivityTest.kt` under
  `app/src/test/kotlin/dev/swapnilraj/calendar/`. Public EDIT is still unadvertised and unintegrated
  at this source cutoff. External callers, device lifecycle and real-provider occurrence handling
  remain acceptance work; OS-05 is not complete merely because INSERT is connected.
- **Ordinary drafts and retirement (EDIT-16/17/18):** ordinary editor CAS, generation-bound raw
  persistence, frozen-command retirement/replacement and legacy review escape UI are now connected.
  `ui/events/EventEditorController.kt` adopts the local CAS result and matching in-memory receipt
  before honoring cancellation, while provider dispatch remains cancellable. `PhoneEventEditor.kt`
  exposes the supported retirement/replacement actions. Evidence: `events/OrdinaryAtomicSqlTest.kt`,
  `events/OrdinaryAtomicRetirementTest.kt`, `events/OrdinaryRetirementScreenTest.kt`,
  `events/DraftCasCancellationTest.kt` and `NativeOrdinaryRetirementJourneyTest.kt`. These replace
  the older “remaining ordinary CAS migration” and “backend retirement only” descriptions. The
  aggregate concurrency failure above and physical process-kill/storage/offline/upgrade acceptance
  remain unresolved; no general persistence-completion claim follows.
- **Authored zones (EDIT-08):** `data/calendar/CalendarWriter.kt` now uses the core
  `ProviderTimeZoneCompatibility.kt` guard for ordinary creation and explicit authored time/zone
  changes. Unsupported imported zones can remain unchanged for supported non-time edits. Evidence:
  `calendar-core/src/test/kotlin/dev/swapnilraj/calendar/core/ProviderTimeZoneCompatibilityTest.kt`
  and `app/src/test/kotlin/dev/swapnilraj/calendar/events/NullZoneTravelEditTest.kt`. The historical
  missing-guard statement is obsolete. Explicitly authoring the same displayed zone for an imported
  NULL-zone source still needs distinct zone-intent handling; physical provider/DST round trips
  remain pending.
- **Recurrence and generated response (REC-03/04/05/06, GUEST-01):** bounded FOLLOWING editing and
  generated THIS RSVP have integrated executors, UI and actual-app synthetic-provider journeys in
  `NativeFollowingJourneyTest.kt` and `NativeGeneratedRsvpJourneyTest.kt`. They are not generally
  enabled production clone capabilities. `CalendarApp.kt` supplies `ProviderClonePolicy.Unverified`
  from `data/calendar/ProviderClonePolicy.kt`, and no production profile is admitted. Generated
  THIS edit/cancellation/response and nonzero independent-tail splits therefore require review on
  unverified providers; `ProviderClonePolicyTest.kt` covers the gate. Existing materialized THIS,
  ALL, bounded FOLLOWING deletion and ordinal-zero behavior retain their separate eligibility.
  Wider exception/date-set transformations and real Google/Workspace occurrence sets remain pending.
- **Meeting backend versus interfaces (GUEST-04/06, DET-13):**
  `data/calendar/CalendarOwnedMeetingWriter.kt` now supports bounded owned one-off field/guest
  changes and cancellation, with `CalendarOwnedMeetingWriterTest.kt` evidence. The dedicated
  `ui/events/meetings/create/MeetingCreationController.kt` and its durable codec have
  `events/meetings/create/MeetingCreationControllerTest.kt` evidence. Guest creation and organizer
  editing/cancellation interfaces are still unconnected at this cutoff; the live details meeting
  mutation path in `ui/LiveCalendarDay.kt` is for invitees. Keep these requirements partial and
  distinguish existing backend support from the missing user-facing routes. No local receipt
  establishes invitation/update/cancellation delivery to a second account.
- **Phone presentation and navigation (CAL/VIEW/DET/EDIT):** the production routes now use
  `ui/events/PhoneEventEditor.kt` and `PhoneEventDetails.kt` through `ui/EventWriteWorkflow.kt` and
  `ui/LiveCalendarDay.kt`, including the private reminder/widget detail routes. Their fixed toolbar,
  focused pickers, raw-input retention and privacy scrubbing have component/screenshot evidence in
  `events/PhoneEventEditorScreenTest.kt`, `events/PhoneEventEditorPresentationTest.kt` and
  `events/PhoneEventDetailsTest.kt`. `CalendarPhoneNavigationTest.kt` and
  `PhoneCalendarNavigationJourneyTest.kt` cover the shared navigation across all five views. These
  are newer evidence for the bounded app routes; they do not prove Google Calendar interaction or
  pixel parity, physical accessibility, or the full daily-driver acceptance criteria.

Unless a module is stated explicitly, source paths above are relative to
`app/src/main/kotlin/dev/swapnilraj/calendar/`, and test paths are relative to
`app/src/test/kotlin/dev/swapnilraj/calendar/`. Manifest activation is in
`app/src/main/AndroidManifest.xml`. Account/device/beta/signing/release gates below remain pending.

## Historical connected application behavior at bf2ef3f

The app routes day, three-day, week, month and agenda views, calendar/account filtering, default
destination selection, date navigation, native details and filtered ranged search. Live settings
control view defaults, first weekday/weekends, declined entries, theme, display zone, event defaults
and weather visibility/units. Local fixtures cover dense layouts, exact occurrence selection, DST
rendering, all-day overflow, large text/RTL and permission/error recovery.

Ordinary guest-free create/edit/delete/duplicate actions use durable drafts, explicit permission
requests, fresh eligibility, conflict detection and exact-command recovery. Creation destinations,
defaults and inherited duplicate privacy approvals bind calendar namespace identity. Day/three-day/
week timed gestures prepare the native editor and require explicit Save. Actual MainActivity move
and cross-column resize journeys reach the real writer/SQL provider, preserve opaque fields/reminders
and lock retained drafts after namespace replacement. Empty-slot preparation also has component/port
evidence; this does not claim physical touch acceptance or recurring/all-day drag support.

**Known ordinary-event gap:** new ordinary one-off creation and explicit TIME/ZONE edits still need
the portable provider-zone guard for bare offsets/custom GMT IDs. Imported title-only edits must keep
unsupported original zone values rather than rewrite them. The new meeting creation guard does not
close this ordinary-writer gap; a green aggregate does not establish this missing acceptance.

Authored recurring creation is enabled, with supported Daily/Weekly/Monthly/Yearly controls and
explicit review. Actual MainActivity journeys prove RRULE/DURATION with null DTEND, first-instance
readback, permission grant requiring a fresh Save/review and recovery without duplicate creation.
Recurring THIS exception edits/cancellation, ALL edits/delete and bounded FOLLOWING deletion are
connected. ALL master time/zone/duration and supported rule edits now use explicit entire-series
review. Five actual app/real-writer journeys cover raw COUNT retention, rule/count changes that remove
the selected ordinal, exact imported UNTIL retention, overlapping same-master occurrence identities,
namespace refusal and frozen committed-operation recovery. The temporal executor requires one
supported RRULE without exceptions or date sets. Synthetic DAILY/WEEKLY expansion is a bounded test
projection, not Android/Google expansion proof. FOLLOWING edits/splits and wider exception/date-set
temporal mapping remain unfinished.

Eligible invitations expose native Yes/Maybe/No response actions. Actual application journeys prove
self-status-only decline, permission recovery requiring a new Save and frozen outcome recovery
without WRITE/replay. Existing recurring THIS exceptions and complete ALL response scopes have
connected scoped UI and bounded backend evidence. Generated-occurrence THIS response is pending.

Event reminders setup is connected to real DataStore CAS, independent namespace-pinned selection,
permission handling and generic test notifications. Application-owned runtime/source/SQL ledger,
notification/alarm adapters and private receivers/jobs are wired. Actual app journeys post through
NotificationManager and open fresh private native details without changing display-none filters.
Unpinning removes active own alerts while retaining delivery history. Sound, vibration, Doze/reboot,
background restrictions and duplicate-alert coexistence remain physical-device acceptance gates.

The agenda widget is enabled in the manifest and Application. Actual host/configuration Activity,
durable binding/configuration, rendered PendingIntent, MainActivity and private-detail journeys
prove independent filters, raw draft isolation, fresh namespace/generation validation and scrubbing
after stop/removal/reconfiguration. Missing host witnesses require safe setup. These synthetic-host
checks do not certify a physical launcher, restore/reboot behavior or device job delivery. A month
widget is not implemented.

Provider row/cell/text, search-retention, raw mutation/child and stored/canonical JSON budgets are
implemented. Limit failures remain explicit; incomplete reminder discovery does not erase history.
These are app-side bounds, not a guarantee about the provider's first CursorWindow allocation or
real-account/device performance.

## Historical backend work and remaining interfaces at bf2ef3f

SQLite CAS is used by meeting response drafts, series editor generation/revision leases and atomic
grid preparation. Ordinary drafts still need the remaining CAS migration. Review/reload now refuses
a known namespace replacement before deleting or preparing a new baseline; missing legacy identity
never authorizes adopting the current account. Legacy frozen commands retain exact read-only
recovery. Explicit discard-only escape UI for unbound legacy drafts remains pending.

Permanent retirement of exact frozen commands is implemented and backend-tested for ordinary,
series and meeting requests. This is not a connected retirement/abandonment UI. Pending requests
must not be advertised as safely discarded or replaced merely because the backend port exists.

Owned one-off **meeting creation with guests is backend-tested, UI pending**. The executor validates
original owner/calendar/type policy, atomically inserts event/organizer/guests/reminders and verifies
exact local receipts, including learned-UID downgrade refusal. Native guest entry, organizer guest
updates/removal, permission controls, rescheduling and cancellation are not connected features.
A valid parent acknowledgement with malformed child acknowledgements remains non-retryable
ATTEMPTED; the transient known parent ID is not retained across reopen. No row guessing or blind
replay is used. None of this proves sent invitations, email or second-account delivery.

Public incoming event/date/create/edit decoding and the minimal event navigation metadata reader
port are foundation work. Public resolver activation, manifest filters and permission-aware native
routing are still pending; MainActivity's public filter remains launcher-only. Private notification
and widget routes are already connected. Global keyboard shortcuts, recurring gestures, event-color
editing, weather hour-tap activation, manual city selection and month/agenda weather summaries also
remain unfinished.

This agrees with the [milestone acceptance ledger](../DAILY_DRIVER_PROGRESS.md#milestone-acceptance-ledger):
M5 has remaining ordinary persistence/recovery UI work; M6 has bounded connected recurrence but
unfinished scopes; M7 guest/organizer interfaces remain; M8 public entry points/device host acceptance
remain; M9 physical delivery remains. Older dated ledger evidence is historical. No interface or
backend packet is promoted to a live feature solely because it compiles or passes tests.

## Release acceptance still pending

| Gate | Evidence | Required completion |
| --- | --- | --- |
| M0 personal + Workspace compatibility | Physical/account matrix `NOT_RUN` | Dedicated authorized calendars and second-account create/update/offline/reconnect/recurrence/invite/RSVP/cancel/reminder round trips, comparing remote results. |
| Physical usability/background behavior | Physical matrix `NOT_RUN` | TalkBack/keyboard, dense calendars, tablet/landscape/multi-window, notifications/channels/Doze/reboot/timezone, widget host lifecycle and process-kill/upgrade recovery. |
| Seven-day work/personal beta | Not started | A full week including weekend, offline use, remote edits, recurrence, reminders and upgrade without data loss, duplicate writes or missed eligible alerts. |
| Durable APK release | Pending | Stable signing, upgrade/install verification, checksum, accurate limitations/release notes and a durable downloadable APK. Assembled debug APKs do not pass this gate. |

The accepted release excludes Microsoft certification, managed-profile aggregation, Tasks, direct
Google service APIs, conference generation, rooms/coworker availability, sharing administration and
specified desktop/cloud-only workflows. Existing exposed meeting links remain supported. Historical
optional inventory rows stay explicit in the CSV. The original audit's narrower widget/notification
scope does not override their inclusion in the accepted daily-driver goal.
