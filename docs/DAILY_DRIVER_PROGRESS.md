# Daily-driver implementation progress

Updated 18 November 2026. Source of truth: [SPEC.md](SPEC.md); execution: [TASKS.md](TASKS.md).

## Verified starting point

- Audited baseline: `23808c1` on `codex/calendar-feature-audit`; existing app is a read-only day view
  with weather. The audit describes 151 checks and five reproduced defects, not completed parity.
- The goal was activated after planning. **No daily-driver implementation had occurred before this
  Wave 0 session.** The earlier two agents reviewed dependencies/ownership only.
- Implementation branch: `codex/daily-driver`. Initial read-contract/module bootstrap and scope docs are implemented;
  mutation and persistence ports will be frozen before their dependent worker packets. A new module or contract alone does not establish native editing or new views.
- Baseline evidence remains in [the audit](GOOGLE_CALENDAR_AUDIT.md). Local verification: `./gradlew test :app:assembleDebug :app:lintDebug` passed (67 tests), including
  core contracts, merged day geometry, and persistent calendar preferences. No device/cloud/beta result is claimed.

## Active implementation packets

Foundation commit: `7ebdfd9`; draft integration PR: https://github.com/swapnilraj/calendar/pull/3.
Local tests, APK assembly and lint passed after the bootstrap; GitHub integration CI is running.

Six workers have isolated worktrees under `/workspace/daylight-workers/`, based on the foundation:

| Packet | Owner/worktree | Current deliverable |
|---|---|---|
| A1 | Provider | Live metadata, range and detail reader with synthetic-provider tests |
| B1 | Temporal | Merged via PR #4; 14 geometry tests passed; renderer wiring pending |
| C1 | Calendar | All-day overflow/semantics, font sizing and RTL fixes |
| D1 | Events | Native details component with safe link actions and UI tests |
| E1 | Android | Reminder health/setup and notification components |
| F1 | Verification | Merged via PR #5; readiness and M0 matrix; actual device cases NOT_RUN |

Remaining packets are **in progress**, not integrated user-facing features. App-local selection and
creation-default persistence now pass a round-trip regression, but their sidebar/editor UI is pending. The lead owns application
wiring and integration; workers do not overwrite shared contracts or each other's files.

## Milestone acceptance ledger

| Milestone | Status | Required acceptance evidence |
|---|---|---|
| Wave 0 bootstrap | In progress | Core module/contracts, preserved baseline, tests/build/lint verified |
| M0 Google compatibility | Pending external verification | Dedicated personal/Workspace two-account/provider/device round-trips |
| M1 rendering/accessibility | Planned | Correct D1–D5 regression tests and inspected all-day/font/RTL/DST screens |
| M2 calendars/ranges/sidebar | Planned | Stable identity, independent persisted filters, shared ranges/observer and coverage states |
| M3 planning views | Planned | Day/three-day/week/month/agenda, date navigation, leap/six-row/dense/RTL cases |
| M4 native details | Planned | Complete readable event context, correct occurrence identity and meeting links |
| M5 native ordinary writes | Planned | Batched create/edit/delete/duplicate, conflict/draft/uncertain-create and offline recovery |
| M6 recurrence | Planned | This/future/all scopes preserve exceptions/limits/zones and Google occurrence sets |
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
