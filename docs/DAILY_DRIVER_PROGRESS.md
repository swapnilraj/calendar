# Daily-driver implementation progress

Updated 18 November 2026. Source of truth: [SPEC.md](SPEC.md); execution: [TASKS.md](TASKS.md).

## Latest local integration checkpoint

At `21d53d1`, the required full gate **passed: 2,019 tests** (core257, app1,745,
weather17), debug APK assembly and lint, with zero failures, errors or skipped tests. Command:
`./gradlew test :app:assembleDebug :app:lintDebug`; log:
`/tmp/daylight-meeting-edit-recurring-gate.log` (5m48s). Debug APK SHA-256:
`3e59e8cc563fdebdac0b1ef743c449c80c7e1ab14eab3fbdf28fa907dffb36d0`.
This remains debug-signed local evidence, not a stable production release.

External EDIT review/private reopening is connected and passed13 actual-app entry journeys.
Add people now atomically promotes one ordinary raw draft into a dedicated meeting draft; browser
reopening checks its feature and generation. Schema2 meeting creation connects issued recurring
rights, typed review and frozen commands while preserving schema1 frozen formats. Eleven actual-app
one-off/recurring journeys passed, including COUNT, multi-day UTC all-day inclusive UNTIL, permissions,
source changes and source-free recovery without a second batch. These use synthetic provider/SQLite
fixtures, not live Google sync. New one-off receipt2 is self-contained; historical receipt1 still
requires its retained local snapshot. Recurring, phone guest-form and ordinary review images were
recorded, inspected and verified, including the corrected duration/zone labels at 2x RTL.

The previous full attempt failed19 checks:17 owned-meeting tests could not seed through a fixture
view missing native joined uid2445, one old creation-capability assertion was stale, and one ordinary
review image needed the new UTC label. Test-only corrections preserve invalid-authority/no-write and
opaque-metadata/response assertions;107 focused regressions passed before this green full gate.

Nonzero Following prefix writes now repeat the unchanged original raw time context with the cut
rule, with154 focused checks including explicit cache-model controls. Actual installed-provider
warm-cache acceptance remains pending, and no production clone profile is admitted. The optional
SDK-only LOCAL probe APK builds. Its real API35 emulator identity-only run passed for
`com.android.providers.calendar` version35. Two guarded fixture attempts stopped on native API
contract errors in the test harness: the calendar insert ACK preserves its request query, and an
event-ID update URI forbids an additional selection. Both fixture cleanups were verified; both
harness fixes are committed and the SDK APK pair plus lint passed. A subsequent identity attempt
stopped at the ADB guard after the emulator disconnected, before fixture activity. No 18-case
runtime PASS is claimed. Identity evidence: `/tmp/daylight-native-provider-identity-30138a09/`;
failed fixture reports: `/tmp/daylight-native-provider-fixtures-30138a09/` and
`/tmp/daylight-native-provider-fixtures-calack2/`.
The probe is separate from Google/Workspace sync and UI parity certification.

Remaining implementation includes the owned organizer edit/guest-delta/cancellation screen, advanced
exception transfer/execution admission, the explicit same-renderer-zone edge on NULL-zone imports,
and release provisioning. Personal/Workspace round-trips, physical reminder/Doze/reboot/timezone/
TalkBack/upgrade checks, seven-day beta and stable signed release remain pending. The GitHub
integration currently cannot access Actions secrets (HTTP403); no signing key was provisioned.
PR53 typed retirement foundation passed full CI, merged into integration (`7f439b1`) and is attached.
PR54 ordinary backend retirement also passed CI and merged into integration (`21d88e5`); its
artifact attachment was requested, but the attachment service has not confirmed the result.

Earlier checkpoint and packet history:

At `bf2ef3f`, the complete required gate passed: **1,490 tests** (core221, app1,252,
weather17), debug APK assembly and lint; no failures, errors or skipped tests. This includes the
bounded owned one-off meeting creation backend, namespace-checked incoming-link metadata reads,
and five actual app whole-series temporal/rule journeys through the SQLite/native-provider fixture.
At that checkpoint, guest-creation UI and public incoming-link activation were pending; local
writes do not prove invitation delivery. Log: `/tmp/daylight-meeting-integrated-full.log` (3m26s).

At `23403b7`, the complete required gate **passed: 1,844 tests** (core232, app1,595,
weather17), debug APK assembly and lint, with zero failures, errors or skipped tests. Command:
`./gradlew test :app:assembleDebug :app:lintDebug`; log:
`/tmp/daylight-meeting-phone-integrated-full.log` (4m03s). Debug APK SHA-256:
`a82bb983b8a5e90116210e45dd058261a956f62f0355c2bb6652bcd9ecc3a9eb`.
This is a debug-signed local checkpoint, not an upgradeable production release.

The separate full-height meeting creation component now has44 focused checks, including exact
cleanup acknowledgements, continued typing during local CAS, and a cancellation fence preventing
provider dispatch after a cancelled durable freeze. Light, dark and large-text RTL screenshots
were recorded, inspected and verified. **Create entry, draft-browser routing and the ordinary
editor's Add people transfer remain unwired.** No invitation-delivery claim follows from this UI.

The preceding `c7a743b` run passed1,824 tests and built the APK but failed indentation lint,
fixed in `e1877a4`. Its rerun exposed independent cold SQLite helper initialization contention
before any CAS transaction. The healthy-CAS race now initializes both helpers first; a separate
failure/readback/explicit-retry test preserves storage-failure coverage. All strict single-winner
assertions remain. Production storage is unchanged and still conservatively returns Unavailable
for failed initialization; the app uses one lazy store. This race is not assumed Robolectric-only.

Incoming EDIT version8 review passed58 focused checks with two inspected screenshots in its
isolated branch. Independent review found READ loss could unmount a pending frozen editor; a
presentation-mask fix and regression are staged but **not yet compiled or run**. Lead App/manifest,
private-open and draft-browser activation are staged in `codex/dd-incoming-edit-integration`,
also unverified and outside the green checkpoint. Recurring meeting creation and advanced
FOLLOWING metadata helpers remain separate source/test packets.

Later integrated packets include:

- Ordinary editor generation/CAS, retirement, frozen restoration and cancelled-grid cleanup are
  connected to the actual app. Independent native/SQLite journeys cover replacement after a failed
  batch, Activity recreation and a fresh explicit Save without replaying the retired operation.
- Shared phone navigation is connected across all five views. Independent verification passed31
  actual app/provider/preferences checks and inspected12 affected images, including full live
  light/dark and large-text RTL fixtures.
- Following edits have a bounded native writer and durable UI. Three actual-app SQLite journeys
  cover COUNT/UNTIL splits and committed recovery. Controller result guards additionally passed90
  focused series/meeting checks: new tail and generated RSVP IDs must match the frozen occurrence
  context. Imported-row clone preservation has a newly identified public-projection limitation;
  the default-review provider-profile gate is integrated and passed168 focused checks. No production
  clone profile is admitted; synthetic fixtures are explicitly marked.
- Portable authored-timezone and alert-only validation are integrated. Six actual writer/SQLite
  regressions verify that a draft for a null-zone imported source preserves its original baseline
  after travel. Explicitly authoring a source zone for such an import remains a separate edge case.

Full-height phone editor/details and public VIEW/date/INSERT review are now integrated. The
combined host passed all7 incoming journeys together, all58 private-open/seed/retirement checks
with screenshot verification, and3 multi-day move/resize/namespace journeys. Existing fixtures now
use the real pickers, fixed toolbar actions and scoped icons. Three updated host images were
recorded and inspected; standalone day rendering was unchanged. Current full-gate results are
recorded above.

Generated THIS RSVP has3 actual-app synthetic-provider journeys, including READ-loss/recreation
and source-free SQL recovery with one batch. Clone admission still defaults to review in production.
The separate guest-creation controller/codecs passed25 checks; frozen requests do not reparse raw
input. Owned one-off organizer editing/guest deltas/cancellation passed104 backend-focused checks.
Their application entry/organizer interfaces, recurring meeting creation and public EDIT activation remain unfinished.

The current Google Calendar2026.44 APK has installed and rendered notification/calendar-access
onboarding in an isolated API35 software emulator. This is initial runtime evidence, not main-flow
or pixel parity; see GOOGLE_CALENDAR_UX_PARITY.md. No Google account is configured. Real account,
reminder/Doze/reboot/timezone/TalkBack/upgrade checks, seven-day beta and stable signed release remain
pending. PR50 reminder activation passed full CI, is merged into integration and is attached. PR51 bounded incoming-navigation
metadata also passed full CI and merged into integration (`68e0340`); its publication branch passed
228 focused checks. The newer aggregate phone integration remains separate from that published base.

Earlier, at `5bd64d4`, the complete required gate passed: **1,340 tests** (core189, app1,134,
weather17), debug APK assembly and lint; no failures, errors or skipped tests. This includes real
app journeys for widget setup/click/permission/draft isolation, recurring creation/recovery and
Day/three-day/week timed editor gestures. Screens for cross-column and larger-text RTL previews
were recorded and inspected. This is local synthetic-provider evidence, not physical-device or
Google delivery certification.

Subsequent packets have focused evidence: widget setup-context lease validation, portable event-zone
checks, whole-series temporal/rule editing with explicit review, namespace/generation-bound restored
editors, and permanent journal retirement of exact frozen requests. The whole-series review screenshot
was recorded and visually inspected. These packets are separate from the `5bd64d4` full checkpoint.

An intermediate full-gate attempt at `bdc3f1c` failed two of 1,218 app tests: existing native recurring journey
fixtures omitted the newly required explicit permission preflight. Commit `60502c4` supplies the
authorized fixture callback. A subsequent new journey needed fresh field lookup after scrolling;
that test synchronization was fixed in `58070dd`. Both fixes are included in the green `bf2ef3f` gate.
Guest/organizer workflows,
remaining recurrence scopes, incoming public intents, device/account/beta and signed-release gates
remain. PRs #48 (provider/authority foundation) and #49 (durable reminder settings/private opens)
passed full CI and are merged into integration. The ordinary authored-timezone provider guard is now integrated and focused-tested; it remains
outside the older complete checkpoint above.

Earlier local checkpoints:

At `945c6b5`, the full test/debug APK/lint gate passed with 1,171 tests. This is local
synthetic evidence; device/account/beta and signed-release gates remain pending.

At `e075f48`, native invitation details now connect backend-issued response rights to durable
response drafts, the response workflow and draft-browser recovery. The app compiles and 94 focused
meeting/details/navigation tests pass. Its full test/debug APK/lint gate passed with 1,191 tests;
this does not establish Google invitation delivery. Reminder setup is already wired through the
live app; the latest active-alert cleanup also preserves posted history when a calendar is unpinned.

The subsequent response-draft discovery fix reopens a reviewed replacement's exact generation
rather than preparing another operation. Its 49 focused checks passed. Three independent actual
MainActivity journeys also passed through the real meeting writer, SQLite draft/journal and a native
provider-semantic fixture: decline preserves other event/guest metadata, WRITE permission grant
needs another Save, and frozen committed-outcome recovery never replays. Two native UI crops were
recorded, inspected and verified. This establishes local RSVP integration, not remote delivery.

Day-grid creation, move and resize are now connected to durable native editors (`5fbb2b0`).
Focused helper/gesture/editor tests cover fresh eligibility, late namespace replacement, retained
original baselines, cancelled reviews, DST offset choices and gap alternatives. Three new screens
were recorded, inspected and verified; old screenshots stayed unchanged. The standalone restored
edit-namespace fix passed 62 focused checks. Recurring gestures remain on the existing scoped editor
route, and the full gate for the newer grid integration is still pending.

## Verified starting point

- Audited baseline: `23808c1` on `codex/calendar-feature-audit`; existing app is a read-only day view
  with weather. The audit describes 151 checks and five reproduced defects, not completed parity.
- The goal was activated after planning. **No daily-driver implementation had occurred before this
  Wave 0 session.** The earlier two agents reviewed dependencies/ownership only.
- Implementation branch: `codex/daily-driver`. Initial read-contract/module bootstrap and scope docs are implemented;
  mutation and persistence ports will be frozen before their dependent worker packets. A new module or contract alone does not establish native editing or new views.
- Baseline evidence remains in [the audit](GOOGLE_CALENDAR_AUDIT.md). Local verification: `./gradlew test :app:assembleDebug :app:lintDebug` passed (1,018 tests), including
  merged provider reads, native detail components, day fixes, reminder components and preferences. No device/cloud/beta result is claimed.

## Active implementation packets

Foundation commit: `7ebdfd9`; draft integration PR: https://github.com/swapnilraj/calendar/pull/3.
Local tests, APK assembly and lint passed after the bootstrap; GitHub integration CI is running.

Six workers have isolated worktrees under `/workspace/daylight-workers/`, based on the foundation:

| Packet | Owner/worktree | Current deliverable |
|---|---|---|
| A1 | Provider | Merged via PR #6; 10 provider tests; live three-day pager/details wired |
| B1 | Temporal | Merged via PR #4; geometry and live renderer tested; synthetic DST screens inspected |
| C1 | Calendar | Merged via PR #10; D1/D3/D4/D5 automated checks and inspected screens pass |
| D1 | Events | Merged via PR #8; live native details wired; remaining M4 metadata/actions pending |
| E1 | Android | Merged via PR #9; component tests pass; scheduler/delivery pending |
| F1 | Verification | Merged via PR #5; readiness and M0 matrix; actual device cases NOT_RUN |

Remaining packets are **in progress**, not integrated user-facing features. App-local selection and
creation-default persistence pass round-trip regressions. Sidebar, all five planning views and native
one-off editor routes pass synthetic provider-backed integration tests; device checks remain pending. The lead owns application
wiring and integration; workers do not overwrite shared contracts or each other's files.

## Milestone acceptance ledger

| Milestone | Status | Required acceptance evidence |
|---|---|---|
| Wave 0 bootstrap | Complete locally | Core module/contracts, preserved baseline, tests/build/lint verified |
| M0 Google compatibility | Pending external verification | Dedicated personal/Workspace two-account/provider/device round-trips |
| M1 rendering/accessibility | Partial: D1–D5 automated checks/screens pass; device accessibility pending | Correct D1–D5 regression tests and inspected all-day/font/RTL/DST screens |
| M2 calendars/ranges/sidebar | Partial: reads/preferences verified; sidebar/live filters verified locally | Stable identity, independent persisted filters, shared ranges/observer and coverage states |
| M3 planning views | Partial: all five views routed and tested; device/dense acceptance pending | Day/three-day/week/month/agenda, date navigation, leap/six-row/dense/RTL cases |
| M4 native details | Partial: live native context and guest-visibility rules verified locally | Complete readable event context, correct occurrence identity and meeting links |
| M5 native ordinary writes | Partial: native one-off create/edit/delete/duplicate, durable drafts and metadata verified locally | Batched create/edit/delete/duplicate, conflict/draft/uncertain-create and offline recovery |
| M6 recurrence | Partial: authored recurring creation, ALL edits/delete including bounded temporal/rule changes, THIS exception edits/cancel and FOLLOWING delete have local evidence; FOLLOWING edit/split and wider exception support pending | This/future/all scopes preserve exceptions/limits/zones and Google occurrence sets |
| M7 meetings | Partial: single and existing-exception/whole-series RSVP connected and tested; generated-exception response, guests/organizer flows and external verification pending | Invitation/RSVP/reschedule/recurrence-change/cancellation observed in second account |
| M8 search/Android entry points | Partial: settings, filtered search and agenda widget app flows wired/tested; public incoming intents and device/host acceptance pending | Filtered ranged search, settings, agenda widget and safe occurrence-preserving intents |
| M9 reminders | Partial: application runtime, setup/navigation and private recovery wired and tested; device acceptance pending | App-owned alerts/snooze and permission/background/Doze/reboot/timezone/upgrade recovery |
| Seven-day beta | Not started | Work/personal week including weekend/offline/remote edits/recurrence/reminders/upgrade |
| Durable APK release | Not started | Stable signed upgradeable APK, checksum, release notes and durable download |

## Completion policy

Mock/provider/unit evidence is distinct from device/cloud evidence. Never label a local save as a
verified server sync or sent invitation. No real private events are used for committed screenshots or
logs; device tests use dedicated authorized fixtures. Missing device/account/signing/beta access
leaves its gate pending while independent implementation continues.

Data loss, duplicate writes, incorrect recurrence scope, missed eligible reminders or failed meeting
propagation block release. The whole goal completes only when all required milestones and release
gates pass. Do not turn the audit totals into a percentage-complete estimate.

## Current next packets

- A2: merged via PR #13; durable drafts/journal and one-off writer verified locally; actual Google sync pending.
- B3: merged via PR #11; recurrence rules and pure edit plans are tested. Bounded supported series writes now have synthetic integrated evidence; full temporal/pattern/following edits remain pending.
- C2: merged via PR #12; month component and live month routing verified. C4/C5 agenda, three-day and week components and live routing now pass local integration tests.
- D2/D3: native editor and exact-command recovery now wired to ordinary one-off app actions; local tests pass. PR #23 adds frozen-command recovery; provider/server checks remain pending.
- Lead: shared read range/native-detail wiring, transition rendering, and contract integration.
- Physical Google, notification delivery, beta and release gates remain pending.

## Live read integration evidence

- A synthetic provider event opens native details and its exact meeting URL without a system-calendar
  handoff. Pager and sheet share one range query. Clearing selection removes the old details.
- Provider removal of overflow events no longer focuses a detached control on sheet dismissal.
- Two integrated day/detail screenshots recorded and inspected. Full tests, APK build and lint pass.
- Live Day/Month view switching and calendar drawer now share saved filters and preserve the separate
  creation default. Month day sheets survive adjacent-month range loading. Preference read failures
  block provider subscriptions until explicit retry; persisted explicit-none stays empty. Two new
  drawer/month screenshots recorded and inspected.
- DST rendering now uses transition segments, true-duration/offset descriptions and ambiguous-weather
  masking; current-time decoration sits below events. Five synthetic DST images and nine affected
  baseline images were inspected. Logical scroll anchors pass synthetic regression checks; device comparison remains pending.
  Day, 3 days, Week, Month and Agenda now share app filters; selected-date changes within
  multi-day/agenda ranges preserve the range and open the intended occurrence. Week windows use
  locale alignment. Logical wall-minute restoration passes a density-change regression.
  Native ordinary one-off editor routes pass local integration tests.
- Native details enable edit/delete only after fresh read-only writer eligibility. First Save/Delete
  requests WRITE permission, with no automatic retry after grant. Ask-each-time requires an explicit
  calendar choice. Live create and confirmed-delete journeys, denied eligibility, exact occurrence
  routes, and permission recovery pass synthetic tests. The integrated editor image was inspected.
- Proven retry-safe recovery is separate from unknown outcomes; typed original commands survive
  restart without rebasing tokens or patches. Unknown/legacy/corrupt outcomes remain locked.
- Invitation writes remain pending. Supported bounded recurrence writes now use the native scoped route. Durable draft discovery/recovery, duplication
  and availability/privacy editing now have focused integrated evidence; cross-calendar inherited
  visibility now requires explicit calendar-specific approval. Drag/resize, colour and server verification remain pending.
- Rich native context and explicit guest visibility now pass reader/UI tests; free/busy and writer
  eligibility preflights avoid restricted child reads. The real reminder source/SQL ledger/coordinator/adapters pass integrated restart, snooze and
  stale-action tests; runtime components and Application/private recovery wiring pass local checks; reminder setup/navigation and device delivery remain pending.

Latest local integration checkpoint: **1,018 tests**, debug APK and lint passed with
`./gradlew test :app:assembleDebug :app:lintDebug` (174 core, 827 app, 17 weather; no failures/skips).
The live Settings route applies first weekday, weekends, declined filtering, display zone, theme,
weather visibility/units and event defaults. Failed saves retain inputs; display-zone changes do not
change device-location weather. Timed FAB and all-day date creation use independent reminder defaults.
Three new settings/theme integration images and the updated drawer baseline were recorded/inspected.

The combined checkpoint includes the native scoped recurrence route: ALL non-temporal edits/delete,
THIS existing/new exception edits/cancellation, and FOLLOWING deletion at the original recurrence
identity. Synthetic UI→adapter→SQLite→transactional-provider tests cover fresh readback and recovery
without a second write; the fixture does not implement Google recurrence expansion or cloud sync.
Temporal/pattern changes, following edits and new recurring creation remain unfinished.

Search now uses the real bounded-range provider adapter, respects app filters/declined settings, and
opens exact results outside the visible day/week before native details. Timed results show dates/years;
changed query/range/calendar/zone context removes old private rows synchronously. Search/day-navigation
and recurring-following-deletion screenshots were recorded and inspected.

Creation commands, durable drafts, default-calendar preferences, frozen retries and journal receipts
are bound to calendar namespace identity. Reused IDs require explicit reselection; legacy unbound
submissions cannot retry. Missing UID metadata disables a retained pin, while cosmetic renames remain
valid. Duplicate inherited privacy approval is also namespace-bound. Focused backend/editor/preference
checks pass and the namespace review screenshot was inspected; independent F20 review found no concrete
remaining bypass in these paths. This does not prove identity across an indistinguishable provider reset.

Widget lifecycle receipts, queued-job ownership and configuration UI have component evidence. The
durable FIFO actor now passes file-backed persistence/cancellation/corruption checks; concrete host/
bootstrap proof, widget application wiring and device checks remain pending. No widget lifecycle
verification is restored from a stored row alone. Custom host markers have public-platform/reboot
limitations; they are not universal host-incarnation proof.

PRs #30–#41 are merged remotely, including live settings and weekday/Fahrenheit presentation. Newer local
packets are being published in bounded follow-ups. The APK is a debug build, not evidence of stable
signing, Google/Workspace sync, device delivery or beta acceptance.

Next implementation: row/cell/retained-payload bounds for normal reads, search and mutation source
snapshots (local enforcement now verified); full recurrence execution; widget host/bootstrap and
reminder configuration/navigation; invitations/RSVP and drag/resize. F17's large text/child/canonical
payload gaps are addressed by A16/A16a with bounds before materialization/token/journal and honest
truncated search. F23 found no additional concrete bypass in reviewed paths. These are app-side bounds,
not proof about the provider's first cell/CursorWindow allocation. F19 verified cache entries and
replay are removed after the last subscriber; payload bounds are a separate issue. Required account/
device, seven-day beta and release gates remain pending. Local save never means verified server sync.


## Latest reliability and Android integration evidence

The c0855c4 full checkpoint passed 1,018 tests, debug APK assembly and lint. It includes namespace-safe
creation/recovery, normal/search/mutation/private-JSON budgets, reminder UID projection checks, captured
series capability computation before token persistence, and the widget FIFO store. Two read-limit
screens (day/light and search/large-font RTL) were recorded and inspected; old private payload removal
passes native UI checks. The dedicated provider read budgets also cover reminder failure paths without
turning incomplete reads into authoritative event deletion.

The Application owns one reminder runtime/coordinator/ledger. Startup, resume and local changes use
fresh reminder settings, with private alarm/action/recovery receivers and a signature-protected job
service. Twenty focused runtime/pipeline/preference checks include the actual app job registration,
READ permission loss and disable recovery, plus two deterministic stale-configuration ordering
regressions. Setup remains disabled by default; reminder selection/permissions and notification-open
routing are not yet connected, so this is not an enabled end-to-end alert workflow or device proof.

Meeting API and atomic draft-store capability contracts have been added for subsequent RSVP work.
Atomic SQLite implementation, meeting writer/UI, existing-editor CAS migration and real two-account
propagation remain pending; an interface alone does not establish these capabilities.
