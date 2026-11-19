# Daily-driver implementation progress

Updated 19 November 2026. Source of truth: [SPEC.md](SPEC.md); execution: [TASKS.md](TASKS.md).

## Latest local integration checkpoint

Google Android reference remains only partially observed: Settings/General are accessible, but account
setup stops in GMS's ErrorActivity. A fresh observed Back press returned to the launcher rather than
the calendar. The same SDK screenshot/input method works for both apps; main/event UX parity is
still unverified. [Observed evidence](audit/evidence/google-reference-back-check-2026-11-19.json).


The complete default gate at **`563dea70ed360f766162712c674f9b92dfd2e363` passes 2,407 tests**
(app 2,133/core 257/weather 17), debug APK assembly, lint and release-manifest processing with zero
failures/errors/skips in 7m9s. Normal debug/release retain `CalendarApp`, 18/16 enabled components
and no probe-only queries. The added regression checks that legacy ordinary drafts with foreign or
malformed family tags stay retained and unreadable. See the
[complete checkpoint evidence](audit/evidence/legacy-family-default-full-gate-2026-11-19.json).

[Verified source](https://github.com/swapnilraj/calendar/tree/codex/verified-2407-20261119) is backed up
separately from publication. Frozen developer APK 48,252,332 bytes SHA-256
`efe90cccfe4207dd59d7b956205737dec8c5e870735bcf8e422afcf197d88e01`;
ZIP 14,569,165 bytes SHA-256 `ec5ebb89f73f324dd19ec5090e9251dae953600fe9e62c1269c9e5e24d9e2af4`.
Files `/tmp/daylight-verified-2407-563dea7/`; signer 90936f83… verified. The first attempt at the same
source failed because disk space ran out; its failure and partial XML remain preserved. Removing only
ignored inactive compiler intermediates freed approximately 6 GiB before the successful fresh run.
PR81/82/83 merged after full CI as `4e0ed50`/`d0e3dc2`/`1d8af3a`. Meeting application routes,
widget application integration and incoming-intent publication still need bounded integration.
Stable owner signing and required account/device/beta acceptance remain pending.

The receipt4 precision correction is integrated at `bb7e88a`: authored non-millisecond times return
TIME before dispatch, and invalid frozen original/result identities cannot authorize retry or acknowledgement.
The corrected bounded publication passes152 checks, including25 precision subcases; the source-derived
blocker was reproduced in13 cases before correction. The prior compile failure and the single recurrence
error-classification fixture failure remain separate in the
[evidence](audit/evidence/ordinary-edit-precision-2026-11-19.json). [PR82](https://github.com/swapnilraj/calendar/pull/82)
is merged. The later complete default gate at `563dea7` passes 2,407 checks.

The exact frozen `e0ce17a` reminder pair passed native SDK identity/capability admission on the owned
API35 emulator: UID10213, all default component overrides,18 components (4 reminder entry points enabled),
one attributed recovery callback settled with its retry refused, zero jobs/posts/channels and exact PID
exit. The initial host attempt stopped before device access because adb was not on PATH; a fresh attempt
used the explicit SDK path. Only the two freshly installed fixture packages were subsequently removed
from user0 after fresh UID/hash/process checks. This is **identity-only**, with all calendar/notification/
exact-alarm permissions still false and no provider/store data or delivery exercised. See
[native proof](audit/evidence/native-reminder-identity-schema2-2026-11-19.json). The13 reviewed harness paths
are integrated at `4f9ebe5`; its ordinary debug/release identity is confirmed by the later2,406-check default gate.

Organizer presentation [PR81](https://github.com/swapnilraj/calendar/pull/81) passed52 focused checks,
three inspected captures and full CI (6m44s). All captures match the aggregate app. See
[evidence](audit/evidence/owned-meeting-publication-2026-11-19.json). Production meeting/contact routing,
controlled Google/Workspace propagation, actual device reminder delivery/recovery, TalkBack/upgrade,
seven-day work/personal beta and stable owner signing remain pending.

The earlier full attempt at `ee2a89d` ran2,383 tests with one failure (zero errors/skips) in5m2s:
the recurring-meeting creation journey could not find its guest row immediately after Add person.
That result is preserved; it does not replace the last passing full checkpoint. A narrow fixture
readiness check at `bea2d36` now passes all four recurring-meeting journeys: it waits for an enabled
Add Person action, persisted guest and enabled email field. No product behavior, timeout or provider
assertion changed; this does not prove the historical disabled state. See
[focused evidence](audit/evidence/recurring-meeting-guest-readiness-2026-11-19.json). The integrated receipt4 budget correction passed66 focused
checks, including eight rejected overflow cases and four exact-fit positive controls. Its corrected
before-fix fixture reproduced a provider write followed by ATTEMPTED/Uncertain in both new/adopted
cases; the earlier ASCII fixture did not reproduce and remains recorded separately. See
[bounded before/after evidence](audit/evidence/ordinary-edit-budget-2026-11-19.json).

At aggregate source `6658805`, RSVP summaries now receive the selected clock convention and display
zone from the main route. The separate bounded publication passed60 focused tests; the root passed65,
including both browser cases and three native-provider synthetic journeys. One component baseline
was recorded and inspected in each worktree (identical bytes); both native heading images remained
unchanged. [PR80](https://github.com/swapnilraj/calendar/pull/80) passed CI in6m32s at `f0c3d05` and merged
as `b618cf9`.
[Evidence](audit/evidence/rsvp-publication-2026-11-19.json) distinguishes publication from aggregate
routing; a new complete root gate and device/account response acceptance remain pending.

The separate isolated reminder probe at `9221e27` passed15 focused checks, including the unchanged
actual-package/Application/recovery-receiver regression: zero framework jobs versus one retry job
in its preserved before-fix run. Earlier14-test omissions and a configuration failure remain recorded.
This is Robolectric evidence, not a new emulator run. Independent review also found a possible
transient snapshot false refusal; the reviewed bounded settlement correction at `e0ce17a` passes22
focused checks. The frozen probe pair now has a separate identity-only native pass; it does not establish delivery. See
[regression evidence](audit/evidence/reminder-prebind-regression-2026-11-19.json).

Subsequent bounded publication PR77–79 passed full CI and merged: injectable meeting creation/local
guest picker (`b4a9cf6`), native meeting adapters preserving ordinary protocol boundaries (`b37308c`),
and connected filtered search (`73444f5`). Focused checks passed92/200/74 respectively; ten meeting/
picker and nine search/toolbar captures were inspected. Independent review found no blocking UI or
adapter publication regression. The combined integration push CI44487581843 passed at `73444f5`.
PR3's first CI attempt failed resolving the unchanged lib-recur0.17.1 dependency before compilation;
the exact retry passed. Its cause remains unproven; no dependency version or source was changed.
Production meeting routing/contact permission composition remains a separate publication packet.

The isolated reminder branch's **default** application at `25712a0` passed2,375 tests
(app2,101/core257/weather17), APK/lint and release-manifest processing. Its opt-in18-component pair
compiled and passed lint at `da755d6`. The first native metadata capture correctly refused startup
after an unsolicited runtime entry produced unavailable configuration and one own retry job. It did
not prove quiet startup or alarm delivery. The
[preserved attempt](audit/evidence/native-reminder-isolation-first-attempt-2026-11-19.json) records
UID10211/PID9847, later read-only ownership/PID-absence proof and canonical removal of only the two
new fixture packages. No calendar data, production package or older bf8 fixture was targeted.
A stricter pre-bind no-scheduling guard and attributed-callback schema2 are in progress. This does
not supersede the exact root checkpoint below or complete native/account/device/beta/signing gates.

At source `6d3148fb64d2e48d3b59934c54a95f16e9aa636c`, the complete required gate passed **2,369 tests**
(app2,095/core257/weather17), debug assembly and lint with zero failures/errors/skips in5m55s.
Schedule retains loaded-range navigation under the phone header and presents accessible date/card
groups. All recurring-create review hosts now honor their selected clock convention. The
[evidence](audit/evidence/schedule-recurring-full-gate-2026-11-19.json) preserves the earlier2,367-test
attempt's three intended clock-format screenshot mismatches,13 passing host checks, three inspected
baseline updates and the successful full rerun. Six Schedule and one new phone review images were
also inspected; the publication's ordinary editor baselines remained unchanged.

[Verified source](https://github.com/swapnilraj/calendar/tree/codex/verified-2369-20261119) is backed up
separately from publication. Frozen APK46,108,176 bytes, SHA-256
`f470bd89caefa78ad3c83afae45267776181aae82088173c62fe25e89cc8f117`;
ZIP14,552,425 bytes, SHA-256 `58e6ce259d6ce03d8591b322a87e94e9df502073bd4c1581e0ec5719e15b6630`.
Files `/tmp/daylight-verified-2369-6d3148f/`; debug signer verified. This is not a stable owner release.

Bounded publication PR74 (Schedule), PR75 (authored phone recurrence), and PR76 (unwired local contact
source) all passed full CI and merged as `307781e`, `a9e8a98`, and `c9dc763` respectively. CI runs
44477885979/44479149066/44479500133 passed in7m6s/7m9s/5m19s. PR75 retained its first83/86 fixture run,
corrected86/86, one new inspected capture and three unchanged ordinary baselines. PR76 preserves the
existing20 adapter checks and adds no picker or permission prompt. Artifact attachment calls were
attempted for all three but timed out without confirmation. Native Google/Workspace, physical/beta
and stable signing gates remain pending; the goal is active.

At source `31c003915cffc8ccfe324f1fd6e94e09b8bdbf04`, the complete gate **passed all 2,359 tests**
(app 2,085 + core257 + weather17), debug APK assembly and lint, zero failures/errors/skips, in6m11s.
This includes the adaptive Month overview and receipt4 EXDATE metadata bridge, all original Following
UI journeys and unchanged large-font RTL baselines. [Tested source](https://github.com/swapnilraj/calendar/tree/codex/verified-2359-20261119)
is preserved independently from the publication branch. Frozen APK44,482,972 bytes, SHA-256
`f033bfd3297b0faa52f225e2702247d378d5211935ea43c184108e52bfb535c3`;
ZIP14,540,174 bytes, SHA-256 `6f80a06d4268a487779413190d98ad14473c8ba2d5bb58a29ce269edce24f075`.
Files `/tmp/daylight-verified-2359-31c0039/`; Android debug signer verified. This remains a debug preview,
not stable owner signing or completed Google/Workspace/physical/beta acceptance.

The bounded Month publication [PR73](https://github.com/swapnilraj/calendar/pull/73) passed35 focused
checks without re-recording its baselines, then full CI run44473153061 passed in5m24s. It merged as
`c669ce56d6de7e7a81d75ca7fbe09d0f73cee1fd`. The root aggregate above and the publication branch
remain separate histories. Authored recurring-create phone publication and Schedule endpoint/presentation
work are now isolated source packets; neither has new acceptance evidence yet.


At source `f0018fbe47379944c6c041145fd2dfba9eaa59f8`, the complete gate **passed all 2,320 tests**
(app 2,046 + core 257 + weather 17), debug APK assembly and lint, zero failures/errors/skips, in6m47s.
The [tested source checkpoint](https://github.com/swapnilraj/calendar/tree/codex/verified-2320-20261119)
is preserved separately from the bounded publication branch. This includes the connected phone
Settings/General flow, retained-input/independent-choice journeys, ten inspected images, Following
private serializer equivalence and native query-sort diagnostics. See [evidence](audit/evidence/phone-settings-2026-11-19.json).
Frozen APK:44,450,608 bytes, SHA-256 `c8d77b9c7dd275bfc26440eb9e8fde745c7b111e262e9c47c7e063c5de1feb26`.
ZIP:14,526,478 bytes, SHA-256 `eb197703ff121ba6e47783b04a4fa51f91c7461945e53072e00450d8798bd3d7`.
Files: `/tmp/daylight-verified-2320-f0018fb/`. The Android debug signer was verified; this is not a
stable owner release. Native/account/beta/signing acceptance remains pending.


At source `289727df1bc870ec4719b6b813703cfc0eb87a18`, the complete rerun **passed all 2,289 tests**
(app 2,015 + core 257 + weather 17), debug APK assembly and lint, with zero failures/errors/skips.
`./gradlew test :app:assembleDebug :app:lintDebug` completed in 3m58s. The
[exact checkpoint](https://github.com/swapnilraj/calendar/tree/codex/verified-2289-20261119)
is backed up independently of the bounded publication branch. The frozen debug APK is 45,386,828 bytes,
SHA-256 `26e06ef3a24aa7c91c945b1292fb12328c646ccb0bfce32d83db74cb1bb84460`;
the ZIP is 14,526,036 bytes, SHA-256
`8d1f4593bd0e380c5aae232178555a76e3cee2eddff559cb4cb1369914f47d1c`.
Both are retained under `/tmp/daylight-verified-2289-289727d/`; this is debug signing, not an owner
release. Existing screenshots are unchanged. The failed attempt below remains in the evidence.


The subsequent Following3 aggregate at `8ef9258` ran2,289 tests and failed one existing meeting
journey at its immediate Save lookup after repeat input closed. All Following3/guarded-read tests
passed. Test-only durable-repeat/UI-ready waits are integrated at `f8f88d1`; the same4 meeting
journeys pass without product changes, retries or longer timeouts. The [attempt and focused
hardening](audit/evidence/following3-aggregate-checks-2026-11-19.json) are retained; the complete rerun above passed. The independent worker did not execute that extra task due tool screening, so this
fixture hardening is lead-verified only.

The complete verified source checkpoint `57fe2ad` is also preserved on the separate
[GitHub checkpoint branch](https://github.com/swapnilraj/calendar/tree/codex/verified-2246-20261119).
This preserves the tested aggregate source without overwriting the bounded publication branch; it
is not a signed release or approval of subsequent source changes.

At source checkpoint `57fe2ad`, the required complete gate **passed: 2,246 tests**
(app1,972 + core257 + weather17), debug APK assembly and lint, with zero failures/errors/skips.
Command: `./gradlew test :app:assembleDebug :app:lintDebug`; elapsed3m50s, log
`/tmp/daylight-following-end-zone-readiness-full-gate.log`. Debug APK45,206,668 bytes, SHA-256
`ce03a929c068c37388f92aa98ba3259c91d72f65354fae46fec7e8b2d1165e5f`.
The timezone correction, test-only journey synchronization and native harness source are included.
Existing screenshots verified unchanged. Native execution, Google delivery and stable release
acceptance remain pending.

Previous source checkpoint `97c3f3f`, the required full gate **passed: 2,243 tests**
(core 257, app 1,969, weather 17), debug APK assembly and lint, with zero failures, errors or skipped
tests. Command: `./gradlew test :app:assembleDebug :app:lintDebug`; log
`/tmp/daylight-receipt6-full-gate.log`. Completed in 6m3s. Debug APK 45,203,980 bytes, SHA-256:
`1fca3bfbeb9b1ceed348c1ddfcfb5c5d77688f2275f85a5ab76345254a2ef45a`.
This remains a debug-signed local build, not a stable release or daily-driver certification.

The subsequent integrated end-zone correction at `1a0fd3d` passed29 focused checks, but its
complete aggregate attempt **failed1 of2,246 tests** in4m31s: an existing ALL-edit journey timed out
waiting for its initial event title before any edit/write. No Following regression failed. The
[failing attempt and focused correction evidence](audit/evidence/following-null-end-zone-fixed-2026-11-19.json)
are retained. Test-only idle checkpoints and bounded non-private readiness counters were added
without changing assertions or timeouts; the two focused journeys passed and the complete `57fe2ad`
rerun above passed. The historical timeout cause remains unproven.

Following receipt3 source is integrated after the passing checkpoint as `408857b`, with69
isolated focused checks passing. Independent review found a
pre-dispatch raw child-collection read race. The [guarded correction](audit/evidence/following-prepared-child-binding-fixed-2026-11-19.json)
is integrated as `97cccf7`, with72 focused checks passing, including the unchanged3-case independent
before/after proof. The complete 2,289-test gate above includes this correction. Recovery ACK/identity controls and this source status do not
establish installed-provider or Google acceptance.

[PR66](https://github.com/swapnilraj/calendar/pull/66) publishes the already integrated pure
Following ordinal/rule/metadata helpers and explicit UNKNOWN-default rule capability from remote
`53017c2`. All44 focused helper tests passed6s; full CI passed at `336c77f`, and PR66 merged as `6ea48fe`. The task attachment is confirmed. This adds no Android adapter or provider admission.

[PR65](https://github.com/swapnilraj/calendar/pull/65) publishes the bounded native-tooling closure
from remote `e536d54`, including opt-in runner wiring and19 host guards. Its local opt-in
AndroidTest assembly passed29s. Full CI passed at `5db9c60`, including host guards, test/debug/lint
and opt-in AndroidTest assembly; PR65 merged as `53017c2`. The task attachment is confirmed. CI now checks host guards and compiles the probe without
starting an emulator or granting permissions.

Fresh [owned-emulator identity capture](audit/evidence/native-app-writer-identity-api35-2026-11-19.json)
now passes with the reviewed `e609b4f` pair on API35/user0/boot `f14d0429-80d1-4b93-9eeb-da5f4f2046ac`.
The complete descriptor exactly matches the unchanged historical18-case SDK report: provider35,
base APK `a1facdbe…`, no splits, same signer/history/build/patch. The one capture completed naturally
in386.5s, with no calendar grants or fixtures. A verified zombie-PID lock was moved intact to its
startup record before the unchanged owned AVD could boot; config bytes were unchanged.

The first app-create/recovery protocol `cf21909f-024d-4ccd-b4df-69b2cbabc057` stopped before
permissions/fixtures because the host rejected Toybox's documented exit 1 for an absent exact PID.
The host-only correction `a9044b9` retains strict general command handling and passes 27 host tests.
A new run `bf8af411-bef6-4a2d-a923-0e5a98bb44df` passed that preflight, created its dedicated local
calendar, then failed the first app-create case with a platform IllegalArgumentException. Cleanup was
withheld; no recovery stage ran. Read-only copies of its exact private state show CREATING, calendar 1,
five attempted queries, zero batch assertions/cases and no exact operation journal row; WAL/SHM were
absent and the rollback journal empty. Frozen-source review identifies a harness defect: its null-only
sort guard rejects CalendarWriter's `_id ASC` calendar-item query before forwarding. The writer returns
Unavailable; a later harness missing-journal assertion masks it as IllegalArgumentException. Together
with the pre-forwarding journal/assertion requirements, this supports no app event batch forwarded on
this controlled path. It does not establish global provider/event absence. The separately created
fixture calendar remains retained. A narrow harness correction and safe cleanup are pending; no blind
replay, failed-run adoption or production admission.

The [native app-writer harness](audit/evidence/native-app-writer-source-review-2026-11-19.json)
is now integrated with19 passing host checks, successful AndroidTest compilation/pair assembly,
and independently verified frozen APK hashes/signatures. The pair binds production base `1a0fd3d`.
At that source-only review, current boot/provider pins and native phases were NOT_RUN. The fresh
identity capture above supersedes the identity gap; exact-PID absence and app-mode acceptance remain
pending. Old pairs remain unapproved, and compilation alone establishes no native acceptance.

Navigation fixture transition hardening from the already published PR62 is now selectively
integrated as `f895cfb`: journeys wait for displayed controls and a closed drawer after preference
commit, and explicitly verify Activity replacement. All8 navigation tests passed in17s
(`/tmp/daylight-phone-navigation-visible-transitions.log`). This is test-only; no screenshots or
production UI changed.

The next bounded [EXDATE adapter packet](FOLLOWING_EXDATE_ADAPTER.md) now records the
independent acceptance matrix and conservative native decoder boundary. It is planned source work,
not implemented exclusion transfer or a production clone admission.

Independent [Following metadata regression evidence](audit/evidence/following-null-end-zone-before-2026-11-19.json)
subsequently exposed a title-only split changing an admitted SQL NULL end timezone to the start zone.
The synthetic adapter returned LocalSaved with one batch while preserving the prefix except RRULE.
Distinct nonblank end zones remain rejected before mutation. The corrective source is integrated with29 focused checks and the complete2,246-test rerun passing. Production clone admission remains disabled. The passing checkpoint above predates this new test.

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


### Current bounded publication and phone-settings packets — 19 November

[PR68](https://github.com/swapnilraj/calendar/pull/68) passed full CI (host guards, test/debug/lint and
opt-in probe compilation) in 5m36s at `230321c`, then merged as `a1f22df`. The aggregate already contains
its host-only fix. Attachment was requested, but the task artifact endpoint did not return; confirmation
remains pending. [PR69](https://github.com/swapnilraj/calendar/pull/69) is a bounded legacy scoped-series
backend publication with 90 focused checks passing after a test-fixture known-reminder join correction;
its first 76-pass/14-failure fixture attempt is retained. Full CI passed in 5m16s at `a1fa471`,
including test/debug/lint and opt-in probe compilation; PR69 merged as `6262011`.
It deliberately leaves FollowingEdit and clone admission closed and adds no UI wiring. Its temporary
Following-disable variant must not replace the aggregate's Following3 implementation.

Field-only phone-preference persistence is integrated as `ec8664f`: 18 focused checks pass in 14s,
including independent concurrent edits, exact preservation of unrelated unknown/malformed values,
compound timezone/reminder changes, invalid input and IO/cancellation behavior. The subsequent connected Settings/General packet below passes screenshots and the complete2,320 gate.


### Phone Settings/General integrated — complete gate passed

The production drawer now opens Settings → General with focused in-place editors and explicit
field-only saves. Supported choices, actual default-calendar/reminder actions and Daylight weather
preferences are present; unsupported Google controls are omitted. Loading/read failure masks controls
and system Back while retaining unfinished input. Save failure retains the proposal; explicit retry
preserves a newer independently changed view/zoom and existing calendar filters.

[Retained evidence](audit/evidence/phone-settings-2026-11-19.json):18 persistence checks,12 component/state
checks and11 final recording/host checks pass. The original host attempt stopped at the expected
changed screenshot; after recording, its complete retry/display/filter assertions pass. Ten images
were inspected; two RTL large-font images were corrected/reinspected for logical-start alignment.
This is synthetic UI/provider evidence. Exact Google selectors/persistence, spoken accessibility,
physical-device/account/beta and stable release acceptance remain pending. The complete2,320 aggregate gate at `f0018fb` passed; the earlier2,289 checkpoint remains preserved as historical evidence.

The native query-sort/diagnostic correction is integrated as `49cab71`, with5 JVM regressions and27
host guards passing. A real CalendarWriter regression reaches VERIFIED receipt6 through the same sort
policy in one batch. Native AndroidTest compilation/execution and retained-fixture diagnosis remain
pending; no event, journal or failed-run cleanup was replayed. [PR70](https://github.com/swapnilraj/calendar/pull/70)
publishes the corrected Following3 backend with159 focused tests passing. Full CI passed7m20 at `7bbbc88`; PR70 merged as `8ccdf74`. Only private
legacy serializer localization was selectively ported into the aggregate (`659c0e9`); wire coercion,
source/command fingerprints and receipt semantics are unchanged. Its attachment request remains
unconfirmed while the task artifact endpoint is unavailable.


The separate readonly retained-fixture inspection packet `671dfea` has34 mocked host tests passing
and a reviewed three-query/no-cleanup branch. Its native pair compiled in36s; the later bounded read-only execution below passed, without cleanup. The connected28-file phone-settings
publication is prepared on merged `8ccdf74`, with selective Live wiring/test changes rather than
copying the aggregate tree; its own52-check publication run passed53s at `39c17b5` with existing PNGs
verified and all12 original Live tests retained. Two zero-test environment failures (incomplete system
JDK, then missing SDK environment) are preserved; complete JDK helper plus explicit ANDROID_HOME fixed
them without code changes. [PR71](https://github.com/swapnilraj/calendar/pull/71) passed full CI6m49 at `39c17b5`, including
opt-in probe compilation, and merged as `ae5bb38`. Its attachment request is unconfirmed because the artifact endpoint did not respond.


### Retained native fixture read-only capture passed

The [owned API-35 inspection](audit/evidence/native-abandoned-fixture-readonly-api35-2026-11-19.json)
completed in119.677s using reviewed/frozen `671dfea` APKs. Both guarded updates, all7 outer device
checks, exact old/new PID absence proofs and provider descriptor checks passed. Exact calendar1's
namespace matched and Events membership was0. Three direct queries left the reviewed manifest/DB
hashes unchanged, no WAL/SHM and an empty journal. Typed native capabilities include owner700,
alert method1/max2, timezone modification1, availability0/1/2, UTC, visible0 and syncEvents1.
No permission commands, event writes, cleanup, replay, adoption or kill occurred. The fixture remains
retained. PASS means completed read-only capture, not six-case app-writer, Google/Workspace or release
acceptance. A fresh identity/six-case protocol is being planned with new fixture/operation IDs.

Receipt4 EXDATE bridge source is integrated as `789896b` on its approved lead contract.
Lead review added strict metadata clock/zone/WHOLE-context invariants and metadata-only capabilities
for nonempty exclusions. Its195-check focus passed20s after a retained194/1 fixture setup failure;105 aggregate provider checks passed23s. A nonmatching UI class filter omitted three original
Following journeys; all three subsequently passed31s in their own exact-class run. There are108
distinct focused checks across the two runs, not a single108-test run. The complete2,359 gate passed; native/account acceptance of this EXDATE bridge remains pending.
See audit/evidence/following-exdate4-2026-11-19.json. Moved/cancelled exception graph transfer remains separate.


### Adaptive Month overview integrated — combined gate passed

Source `8ed8a8e` fits all six weeks/seven columns into normal360×640 and393×851 phone viewports using
measured line/space budgets and at least48dp date targets. Crowded dates keep fixed row height;
plain summaries/counts belong to a single date action that opens every exact event in the existing
list. Detailed/wide/large-font scrolling remains. Source date/provider/weather/DST algorithms are
unchanged. [Evidence](audit/evidence/month-overview-2026-11-19.json):39 functional checks pass35s;
first37/2 title-semantics failure is retained and corrected without dropping assertions. Four recording
checks pass25s; all four images inspected. Full combined test/debug/lint and unchanged RTL baseline verification passed at the2,359 checkpoint
above. The prior2,320 snapshot remains preserved.


### Native ordinary create/READ-only recovery passed — local SDK35 only

The fresh [six-case protocol](audit/evidence/native-app-writer-six-case-api35-2026-11-19.json) passed
in248.982s on reviewed source/pair `671dfea`, run `0201b63e-2c69-47d6-9504-f094ec2f847c`. Four ordinary
one-off/COUNT3 timed/all-day cases verified; two deliberate post-ACK read faults retained known commits.
All six then recovered twice with READ granted, WRITE denied, stable UID/frozen plans, authoring trap
intact and zero native/forbidden write calls. Successful-new-run cleanup acknowledged deletion and
verified all six tracked IDs globally absent plus exact new private files absent. Four target-process
exit proofs passed. Totals206 queries/7 assertions. The oldbf8 empty fixture was not targeted and remains
retained; no adoption, replay or timeout kill occurred.

Lead re-executed all three pure phase validators and verified seven ordinary writer/receipt/store/read
source files are byte-identical to the current aggregate. This is actual local OS-adapter evidence,
not Google/Workspace sync/invitation, physical-device, cloned-tail or release certification. Production
clone admission remains NONE. Fresh identity artifact685da233… binds the exact pair and unchanged full
provider descriptor. Required dedicated signed-in account/device setup is requested asynchronously.

[PR72](https://github.com/swapnilraj/calendar/pull/72) publishes the EXDATE metadata bridge: full CI
passed5m8 at `48920ea`, merged as `d9452e4`. Attachment request remains unconfirmed because the task
artifact endpoint did not respond. Month publication is the next bounded UI packet.
