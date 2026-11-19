# Daily-driver implementation progress

Updated 19 November 2026. Source of truth: [SPEC.md](SPEC.md); execution: [TASKS.md](TASKS.md).

## Latest local integration checkpoint

At source checkpoint `97c3f3f`, the required full gate **passed: 2,243 tests**
(core 257, app 1,969, weather 17), debug APK assembly and lint, with zero failures, errors or skipped
tests. Command: `./gradlew test :app:assembleDebug :app:lintDebug`; log
`/tmp/daylight-receipt6-full-gate.log`. Completed in 6m3s. Debug APK 45,203,980 bytes, SHA-256:
`1fca3bfbeb9b1ceed348c1ddfcfb5c5d77688f2275f85a5ab76345254a2ef45a`.
This remains a debug-signed local build, not a stable release or daily-driver certification.

Independent [Following metadata regression evidence](audit/evidence/following-null-end-zone-before-2026-11-19.json)
subsequently exposed a title-only split changing an admitted SQL NULL end timezone to the start zone.
The synthetic adapter returned LocalSaved with one batch while preserving the prefix except RRULE.
Distinct nonblank end zones remain rejected before mutation. The corrective packet is pending;
production clone admission remains disabled. The passing checkpoint above predates this new test.

The shared Add people picker is connected to meeting creation and owned one-off editing. Optional
local DEFAULT-directory contact search requires an explicit opt-in on each opening; typing, opening
an editor and an existing OS grant never enable it automatically. Manual email entry remains usable
without contacts permission. Raw suggestions and existing guest rows retain their values and roles;
selection and permission results do not save an event. Twenty adapter checks, 24 picker/parent checks,
five actual-app journeys and four new screenshot checks passed. The actual-app journeys cover late
grants, recreation, owned guest preservation and zero calendar writes. Light/dark and large-font RTL
picker images, affected parent images and the integrated meeting editor capture were inspected.
Physical keyboard, contacts-provider and TalkBack behavior still need runtime acceptance.

Guest-entry email keyboards and non-submitting IME Done passed 57 focused checks. Stable
editor errors/outcomes now announce politely and focused pickers expose named panes/headings;
83 focused checks passed, including unchanged screenshot verification and masked payload removal.
Ordinary autosave DRAFT_UNAVAILABLE stays readable but non-live to prevent repeated announcements
while typing. These packets add 16 tests and are included in this complete aggregate gate. Native
keyboard and TalkBack spoken behavior remain unverified.

Independent review reproduced ten false local-save results from hostile create acknowledgements.
The [before/after evidence](audit/evidence/create-acknowledgement-regressions-2026-11-19.json)
records those failures and 15 passing ACK controls after correction. The earlier recurring receipt5 stores
the canonical known master before interpreting bounded raw child ACK facts, then requires exact
child IDs at their authored alert indices and complete row/instance proof. Incomplete child evidence
stays known-ID uncertainty; malformed master evidence never supplies a guessed ID or retry.
Legacy receipt3 commits remain conservative; explicit proven retries retain old prepared bytes.
The correction passed 99 publication-focused checks, an independent 84-check aggregate verification,
and this full gate, including the updated actual-app recurring journey. Receipt4 edit recovery and
its exact native-plan helpers are retained. Source-free immutable CREATE plans and stable generated
UID binding are now implemented for both new create modes through receipt6 below; legacy receipt5
still recompiles recurrence during proof/recovery.

Subsequent independent [one-off ACK probes](audit/evidence/oneoff-create-ack-before-receipt6-2026-11-19.json)
reproduced three false local-save results in the then-active receipt2 creation path (malformed,
missing and reordered reminder ACKs). Those new cases were not covered by the prior 2,211-test checkpoint.
Receipt6 is now integrated at `97c3f3f` for both new creation modes. Independent clean-checkout
verification passed 127 checks, including the unchanged three ACK regressions and six UID/private-read
controls. Twenty selected actual-app synthetic journeys also passed. The codec now has 11 checks,
including strict all-day zone types and historical-zone decode without current lookup. New source
freezes the winning native plan/generated UID and verifies complete child proof; legacy operations
remain byte-preserving. The complete 2,243-test/debug APK/lint gate now passes, including all existing
screenshots. No new UI image was recorded for this backend change.

Incoming public EDIT review now supports an explicit proposed zone for eligible NULL-zone timed
sources, including the displayed zone. Exact Apply/CAS and issued ZONE rights are required; absent-zone
temporal changes stay under review and prepared/frozen records resume unchanged. Its 92 combined
checks passed. Details also scrub stale payload/links after writer-side READ denial and require exact
fresh occurrence/calendar authority. These changes and numeric subtitle RTL corrections passed the
prior 2,124-test full checkpoint at `0a122bc` and remain covered by this gate.

The guarded native API35 LOCAL experiment passed 18 cases with verified fixture cleanup; the
[report](audit/evidence/native-local-provider-api35-2026-11-18.json) and
[runbook](NATIVE_PROVIDER_PROBE.md) preserve pins and limitations. This does not certify the complete
Daylight series executor, physical-schema completeness or Google/Workspace round trips. Production
clone admission remains NONE. Advanced exception-aware FOLLOWING execution remains source work.

Google Calendar 2026.44 launched in the emulator but stopped before the main calendar with a Google
communication error and system freezes. Main navigation/editor/guest/repeat interactions and exact
UX parity remain UNVERIFIED. [APK resource observations](GOOGLE_CALENDAR_UX_PARITY.md) are reference
candidates, not live-flow evidence; no Google graphics are committed.

[PR58](https://github.com/swapnilraj/calendar/pull/58) (signed draft-release pipeline) and
[PR60](https://github.com/swapnilraj/calendar/pull/60) (ordinary phone editor/details) passed CI and
merged into `codex/daily-driver`, following PR53–57. The CI-only default-branch workflow bootstrap
[PR59](https://github.com/swapnilraj/calendar/pull/59) also passed and merged; GitHub reports manual
release dispatch active. No release dispatch or production application merge into main occurred;
PR3 remains draft. Owner-key provisioning is pending; fixture signing tests are mechanism evidence
only. Stable signing, same-key upgrade and a durable published APK remain unfulfilled.

[PR61](https://github.com/swapnilraj/calendar/pull/61) passed corrected-head full CI and merged
into the integration branch as `a9bdfc6`. [PR62](https://github.com/swapnilraj/calendar/pull/62)
publishes shared phone navigation; its first full CI had one navigation-test timeout. A fixture-only
visible/closed-drawer synchronization fix passed 43 focused checks on the current integration base
and fresh full CI at `7dfefc8`. Assertions/timeouts were retained. PR62 merged as `af6b3b9`;
the aggregate worktree was not overwritten with this older publication tree.

[PR63](https://github.com/swapnilraj/calendar/pull/63) preserves JUnit, HTML, Roborazzi and lint
diagnostics after CI failure, with attempt-specific artifact names. YAML/path validation and full CI
passed at `d71b4b6`; GitHub created both diagnostics and debug APK artifacts. PR63 merged as `a2d48dc`.
It changes no application code.

[PR64](https://github.com/swapnilraj/calendar/pull/64) publishes the bounded receipt6 backend closure.
Its 117 focused checks, independent source review and full CI passed at `8b4e103`.
It merged as `e536d54` on 19 November 2026. The publication preserves initial timed-zone compatibility without
importing receipt4 or changing legacy mutation bodies. All four PR61–64 attachments are confirmed.

The aggregate source remains ahead of bounded publication. PR55–62 artifact attachments are now
confirmed in the task; earlier hanging calls were reconciled against the attachment inventory before
retrying missing entries. Controlled personal/Workspace organizer-attendee,
offline/conflict/recurrence tests, physical reminders/Doze/reboot/timezone/TalkBack/upgrade acceptance
and the seven-day work/personal beta remain pending. Immutable Following execution, exception preservation, guarded native receipt6 verification and
remaining publication are continuing source packets. The goal is active and is not
marked complete.

The prior `12917f1` gate passed 2,211 tests, debug assembly and lint in 5m55s; log
`/tmp/daylight-ack-and-accessibility-full-gate.log`, debug APK SHA-256
`5a59b37b653e3b6093a0417c629775977be734b35992ce5dee0b08d6276ec2a8`.

The prior `6ee8f6e` full checkpoint passed 2,177 tests with debug assembly and lint in 6m10s;
its log is `/tmp/daylight-contact-picker-full-gate.log`. Its debug APK SHA-256 was
`3414f68d178bf29ae4cd41f5f3e7bc497a202833f08fa481985f59ae84ca6d5f`.

## Prior local checkpoint: 2,102 tests

At code checkpoint `b2e4dd1` (subsequent changes are documentation only), the required full gate
**passed: 2,102 tests** (core257, app1,828, weather17), debug APK assembly and lint, with zero
failures, errors or skipped tests. Command: `./gradlew test :app:assembleDebug :app:lintDebug`;
log `/tmp/daylight-private-owner-final-full-gate.log` (6m11s). Debug APK44,731,753 bytes, SHA-256:
`b3a1c2fa2591edb0a2956e3f0164eddeb2865030f1257593affcfe4053e57952`.
This is a debug-signed local checkpoint, not a stable production release or daily-driver certification.

Organizer one-off editing is connected to details and exact-generation draft-browser reopening.
Its59 focused component/backend checks, eight actual-app journeys and light/dark/2x RTL images
passed. Sparse guest additions/removals/roles preserve other responses and opaque metadata;
confirmed cancellation excludes unsaved field/guest edits. The global READ gate retains an open
masked organizer workflow, covers the underlying calendar and removes its accessibility content.
Actual lifecycle, permission-no-autosave, namespace conflict and snapshot-purged READ-only recovery
checks passed using synthetic native/SQLite fixtures, not Google delivery.

New ordinary UPDATE/DELETE receipt4 freezes the issued source and exact native plan before dispatch,
so READ-only recovery survives edit-snapshot removal.72 focused checks and137 affected fixture
regressions passed. Native result/parent/child/namespace drift remains uncertain; missing child
acknowledgments never replay. Legacy receipt2 edits and historical one-off meeting receipt1 retain
their snapshot dependency. Newly assigned/changed NULL UID/sync identity is conservatively uncertain.

Explicitly choosing the current renderer for an ordinary NULL-zone edit now records separate authored
zone intent: zone-only emits ZONE.Set/TIME.Keep; subsequent time edits emit both.68 focused checks
passed, including actual picker-to-receipt4 writer journeys. Existing frozen/legacy/meeting formats
remain unchanged. Incoming EDIT review's corresponding explicit-Apply extension is a separate
in-progress isolated packet; its existing guard is not yet removed in this checkpoint.

Four independent controlled CREATE/EDIT regressions exposed a real private-activity cleanup race:
after exact deletion applied, stop/resume discarded its owner before the acknowledgment returned.
The fix retains accepted write/known-outcome owners, refreshes/masks authority without remounting,
and preserves newer successor generations. All four unchanged before/after regressions now pass
with exactly one provider batch and one cleanup CAS.56 related host/phone checks passed; the new
private authority mask image was recorded, inspected and verified. Earlier full attempts failed five
inaccurate native-child fixture queries (corrected without weakening assertions), then one public
create test that granted READ before its denied IO recovery completed. That test now asserts the
completed permission outcome and unchanged frozen record before granting READ; its seven journeys
and the final full gate pass. Logs preserve both failed attempts.

The guarded native API35 LOCAL experiment **passed all18 cases**. All six RRULE-only controls
reproduced stale prefix8+tail5=13 instances with5 duplicates; six exact-context cases produced
prefix3+tail5=8 with0 duplicates; six final-assertion rollbacks retained the original8. The owned
synthetic calendar was verified absent after cleanup. The original report checked all30 tracked
event IDs within that calendar; it did not establish global absence of those IDs if moved or reused.
The historical report remains unchanged. The new app-writer harness requires a bounded global
tracked-ID absence check before cleanup can pass. The complete bounded
[report](audit/evidence/native-local-provider-api35-2026-11-18.json) and
[runbook](NATIVE_PROVIDER_PROBE.md) record exact pins/guards. This direct SDK experiment does not
certify Daylight's complete series writer, Google sync, hidden metadata or a production clone profile.
Google/Workspace round-trips remain NOT_RUN and production profile activation remains NONE.

Google Calendar2026.44 was launched in the emulator: splash → Checking info → communication error,
with system/launcher ANRs also observed; Back returned to the launcher. No accounts/credentials were
added. Main navigation/editor/guests/repeat and exact parity remain UNVERIFIED. Read-only APK
resources identify candidate sheet/header/contact-search/RSVP differences, not active phone flow
proof. See [UX evidence](GOOGLE_CALENDAR_UX_PARITY.md); reference graphics remain outside Git.

The owner-signed draft-release pipeline is prepared in PR58, including increasing versions,
certificate pinning, temporary-secret cleanup, checksums and draft-only GitHub releases. Six signing
mechanism controls passed; release assembly code2/name0.2.0-beta.1 was non-debuggable and its
unsigned APK passed temporary fixture-key signing/alignment. Fixture keys/APKs were deleted.
Stable owner-key provisioning remains pending (GitHub Actions secrets access returned403). GitHub
manual dispatch also requires the workflow on the default branch; integration publication alone
is not that bootstrap. No stable signed APK or published production release is claimed.

PR53–57 passed full CI and merged into `codex/daily-driver`; PR58 passed CI before a SPEC-only merge
with PR57 and is awaiting its new merged-tree CI. Artifact attachment requests for55–58 have not
returned confirmation;54 was confirmed in the task inventory. Aggregate source remains ahead of
bounded publication; no aggregate overwrite or merge of draft PR3 into main was performed.

Remaining source/admission work includes incoming explicit-zone review, advanced exception transfer
and production clone admission, guest/contact-search and other UX reconciliation, plus remaining
bounded publication. Controlled personal/Workspace organizer-attendee/offline/conflict checks,
physical reminder/Doze/reboot/timezone/TalkBack/upgrade acceptance, seven-day work/personal beta,
default-branch release bootstrap and stable owner signing/release are still pending. The entire goal
remains active and is not marked complete.

Earlier checkpoint and packet history:

At `21d53d1`, the full required gate passed2,019 tests (core257, app1,745, weather17), debug APK
and lint in5m48s; log `/tmp/daylight-meeting-edit-recurring-gate.log`. That prior debug APK SHA-256
was `3e59e8cc563fdebdac0b1ef743c449c80c7e1ab14eab3fbdf28fa907dffb36d0`.



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
