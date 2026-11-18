# Daily-driver implementation progress

Updated 18 November 2026. Source of truth: [SPEC.md](SPEC.md); execution: [TASKS.md](TASKS.md).

## Verified starting point

- Audited baseline: `23808c1` on `codex/calendar-feature-audit`; existing app is a read-only day view
  with weather. The audit describes 151 checks and five reproduced defects, not completed parity.
- The goal was activated after planning. **No daily-driver implementation had occurred before this
  Wave 0 session.** The earlier two agents reviewed dependencies/ownership only.
- Implementation branch: `codex/daily-driver`. Initial read-contract/module bootstrap and scope docs are implemented;
  mutation and persistence ports will be frozen before their dependent worker packets. A new module or contract alone does not establish native editing or new views.
- Baseline evidence remains in [the audit](GOOGLE_CALENDAR_AUDIT.md). Local verification: `./gradlew test :app:assembleDebug :app:lintDebug` passed (183 tests), including
  merged provider reads, native detail components, day fixes, reminder components and preferences. No device/cloud/beta result is claimed.

## Active implementation packets

Foundation commit: `7ebdfd9`; draft integration PR: https://github.com/swapnilraj/calendar/pull/3.
Local tests, APK assembly and lint passed after the bootstrap; GitHub integration CI is running.

Six workers have isolated worktrees under `/workspace/daylight-workers/`, based on the foundation:

| Packet | Owner/worktree | Current deliverable |
|---|---|---|
| A1 | Provider | Merged via PR #6; 10 provider tests; live three-day pager/details wired |
| B1 | Temporal | Merged via PR #4; 14 geometry tests passed; renderer wiring pending |
| C1 | Calendar | Merged via PR #10; D1/D3/D4/D5 automated checks and inspected screens pass |
| D1 | Events | Merged via PR #8; live native details wired; remaining M4 metadata/actions pending |
| E1 | Android | Merged via PR #9; component tests pass; scheduler/delivery pending |
| F1 | Verification | Merged via PR #5; readiness and M0 matrix; actual device cases NOT_RUN |

Remaining packets are **in progress**, not integrated user-facing features. App-local selection and
creation-default persistence now pass a round-trip regression, but their sidebar/editor UI is pending. The lead owns application
wiring and integration; workers do not overwrite shared contracts or each other's files.

## Milestone acceptance ledger

| Milestone | Status | Required acceptance evidence |
|---|---|---|
| Wave 0 bootstrap | In progress | Core module/contracts, preserved baseline, tests/build/lint verified |
| M0 Google compatibility | Pending external verification | Dedicated personal/Workspace two-account/provider/device round-trips |
| M1 rendering/accessibility | Partial: D1/D3/D4/D5 automated checks pass; D2 renderer pending | Correct D1–D5 regression tests and inspected all-day/font/RTL/DST screens |
| M2 calendars/ranges/sidebar | Partial: reads/preferences verified; sidebar wiring pending | Stable identity, independent persisted filters, shared ranges/observer and coverage states |
| M3 planning views | Partial: month component verified; app routing/other views pending | Day/three-day/week/month/agenda, date navigation, leap/six-row/dense/RTL cases |
| M4 native details | Partial: live native read flow verified locally | Complete readable event context, correct occurrence identity and meeting links |
| M5 native ordinary writes | Partial: writer/journal verified; editor/permission wiring pending | Batched create/edit/delete/duplicate, conflict/draft/uncertain-create and offline recovery |
| M6 recurrence | Partial: rule engine verified; series mutations pending | This/future/all scopes preserve exceptions/limits/zones and Google occurrence sets |
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
- C2: merged via PR #12; 15 month tests and three inspected baselines. App month routing pending.
- D2: PR #14; native editor/recovery controller verified in worker, integration CI pending.
- Lead: shared read range/native-detail wiring, transition rendering, and contract integration.
- Physical Google, notification delivery, beta and release gates remain pending.

## Live read integration evidence

- A synthetic provider event opens native details and its exact meeting URL without a system-calendar
  handoff. Pager and sheet share one range query. Clearing selection removes the old details.
- Provider removal of overflow events no longer focuses a detached control on sheet dismissal.
- Two integrated day/detail screenshots recorded and inspected. Full tests, APK build and lint pass.
- Next UI integration: tested month view and calendar drawer; next rendering work: D2 transition
  segments/weather masking, logical scroll anchors, and current-time line below event text.
- Before write UI completion: separate read-only edit eligibility from WRITE permission, and expose
  definitely safe journal retry separately from unknown recovery failure so interrupted drafts cannot
  become permanently locked or be blindly resubmitted.

Latest merged checkpoint: `183` integrated tests, debug APK and lint passed after PRs #11–#13.
