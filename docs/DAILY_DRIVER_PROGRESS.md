# Daily-driver implementation progress

Updated 18 November 2026. Source of truth: [SPEC.md](SPEC.md); execution: [TASKS.md](TASKS.md).

## Verified starting point

- Audited baseline: `23808c1` on `codex/calendar-feature-audit`; existing app is a read-only day view
  with weather. The audit describes 151 checks and five reproduced defects, not completed parity.
- The goal was activated after planning. **No daily-driver implementation had occurred before this
  Wave 0 session.** The earlier two agents reviewed dependencies/ownership only.
- Implementation branch: `codex/daily-driver`. Initial read-contract/module bootstrap and scope docs are implemented;
  mutation and persistence ports will be frozen before their dependent worker packets. A new module or contract alone does not establish native editing or new views.
- Baseline evidence remains in [the audit](GOOGLE_CALENDAR_AUDIT.md). Local verification: `./gradlew test :app:assembleDebug :app:lintDebug` passed (378 tests), including
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
| M5 native ordinary writes | Partial: native one-off create/edit/confirmed-delete and permission/recovery verified locally | Batched create/edit/delete/duplicate, conflict/draft/uncertain-create and offline recovery |
| M6 recurrence | Partial: rule engine and bounded scope planner verified; provider series mutations pending | This/future/all scopes preserve exceptions/limits/zones and Google occurrence sets |
| M7 meetings | Planned | Invitation/RSVP/reschedule/recurrence-change/cancellation observed in second account |
| M8 search/Android entry points | Planned | Filtered ranged search, settings, agenda widget and safe occurrence-preserving intents |
| M9 reminders | Planned | App-owned alerts/snooze and permission/background/Doze/reboot/timezone/upgrade recovery |
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
- B3: merged via PR #11; 21 recurrence rule tests. Series writes remain pending.
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
  baseline images were inspected. Logical scroll anchors and device comparison remain pending.
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
- No invitation or recurrence write path is enabled yet. Draft discovery after lost navigation,
  duplicate/drag/resize, editable availability/privacy/colour, and server verification remain pending.
- Rich native context and explicit guest visibility now pass reader/UI tests; free/busy and writer
  eligibility preflights avoid restricted child reads. Reminder coordination/Android adapters have
  focused worker evidence; SQL ledger/source/background wiring remain pending.

Latest local integration checkpoint: `378` tests, debug APK and lint passed with all five live views,
logical-minute anchors, richer native details and restricted-read preflight. The new integrated agenda,
three-day and week images and updated details/multi-day baselines were inspected. PRs #16–#29 are
merged remotely. PR #30 (multi-day component) and subsequent routing/hardening publication remain
in progress.
Local save is not verified Google/Workspace sync. Draft discovery/recovery after lost navigation,
recurrence provider execution, meetings, search/settings, background delivery and release gates remain.
