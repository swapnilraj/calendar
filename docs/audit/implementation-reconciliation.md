# Implementation reconciliation at c0855c4

This reconciles all **151 historical inventory IDs**, in their original order, against the
integrated repository at **c0855c4**. The companion
[implementation-reconciliation.csv](implementation-reconciliation.csv) records current bounded
behavior, source/test paths, evidence level and remaining acceptance for each ID. The historical
[feature-inventory.csv](feature-inventory.csv), audit probes and screenshots remain unchanged.
Historical statuses describe their original baseline; this packet does not overwrite that evidence.

The lead's checkpoint reports `./gradlew test :app:assembleDebug :app:lintDebug` passing **1,018 tests**
(174 core, 827 app, 17 weather), debug APK and lint, with no failures or skips. This reconciliation
inspected source and existing tests; it did not rerun Gradle or perform device/account checks.
New C14/D14 worker components and later unintegrated work are not counted. The proposed
`AtomicDraftStore` interface is API-only work, not a durable atomic implementation or closed race.

## How to read the statuses

| Status | Meaning |
| --- | --- |
| `verified-locally` | The bounded capability has integrated local source/tests. This is not certification of physical behavior or Google cloud synchronization. Read the evidence level and remaining acceptance. |
| `partial` | A useful subset exists, or only planning/backend/presentation components exist. The full inventory acceptance is not satisfied. |
| `not-implemented` | No enabled implementation of the named action exists. A related model, displayed value or contract does not count. |
| `external-pending` | The relevant acceptance explicitly needs device/account/provider evidence that has not been run; synthetic support is identified separately. |
| `excluded` | Outside the accepted Android daily-driver release. It is neither implemented nor a remaining release obligation in this scope. |

`live-synthetic` means the current application route is exercised with synthetic data/ports.
`live-real-adapters-synthetic-provider` additionally exercises actual adapters, SQL and provider-batch
operations; the provider remains synthetic. `component`, `pure` and `contract-only` labels never mean
a feature is discoverable or enabled in the app. Source/test paths establish the bounded behavior,
not every remaining clause of the original Google Calendar comparison.

## What the integrated app currently supports

The native app has day, three-day, week, month and agenda routes, a calendar drawer, independent
persisted filters/default destination, selected-date navigation, native details and filtered ranged
search. Live settings control first weekday/weekends, declined entries, theme, display zone, event
defaults and weather visibility/units. Synthetic tests cover date continuity, exact occurrence
selection, DST rendering, all-day overflow, density/large text and permission/error recovery.

Ordinary guest-free create/edit/delete/duplicate actions use durable drafts, explicit write-permission
requests, fresh eligibility, conflict detection and journaled exact-command recovery. Creation targets,
creation defaults, duplicate inherited privacy approval, frozen commands and journal receipts bind
calendar namespace identity. Reused numeric IDs do not authorize a replacement account. Legacy
unbound submissions are read-only recovery, not permission to retry. Outstanding atomic draft-store
and concurrent discard/write fencing must still be implemented and verified.

The recurring route supports bounded ALL non-temporal edits/delete, THIS existing/new exception
edits/cancellation, and FOLLOWING deletion. Local integrated tests cover native UI → real adapters →
SQLite journal → transactional synthetic provider and fresh readback/recovery without replay. The
fixture has explicit Instances projections; it does **not** expand Google recurrence sets. Temporal or
pattern changes, following edits, full exception transfer and new repeating-event creation remain
unfinished. Pure recurrence planning tests do not close those execution gaps.

A16 bounds app-side provider row/cell/text materialization, search retention, mutation raw children
and canonical/stored JSON. Search reports partial/limit coverage instead of claiming complete empty
results; unsupported oversized mutation sources fail before dispatch. Reminder-source budget failures
are unavailable/deferred/incomplete and must not erase delivery history. These are local application
bounds, not a guarantee about the provider's first CursorWindow allocation or real-device latency.

## Components that must not be presented as finished features

- **Reminders:** the real Application singleton, DataStore settings boundary, SQL ledger, coordinator,
  private receivers, jobs, alarm/notification adapters and ordering regressions have local evidence.
  Runtime wiring exists, but user-facing setup/test-alert enablement and exact notification open
  routing are unfinished. No actual delivery, sound, vibration, Doze, reboot or duplicate-alert-device
  claim is made. The setup screen alone does not enable reminders.
- **Widgets:** agenda renderer/configuration/framework/runtime components are tested. Durable FIFO
  configuration/host-bootstrap proof and live application/manifest/navigation integration remain
  unfinished. The launcher cannot be called an enabled schedule widget. No month widget exists.
- **Meetings:** exposed organizers, guests, responses and existing links are readable. Dedicated
  rights/token/guest-delta contracts exist, but no enabled invitation, RSVP, guest-management or
  organizer-cancellation journey exists. A local save never proves invitation email delivery.
- **Entry points and gestures:** validated external intent decoders and timed-grid planning exist.
  Incoming view/date/create/edit routing, global keyboard shortcuts and live slot/drag/resize actions
  remain incomplete. MainActivity currently exposes the launcher entry point.
- **Weather:** timed-grid layers, header summaries and visibility/unit settings are live. Hour readout
  presentation is a component without a live hour-tap route; manual city selection and month/agenda
  daily summaries remain unfinished.

The accepted release excludes Microsoft certification, managed-profile aggregation, Tasks, direct
Google service APIs, conference generation, room booking, coworker availability and sharing
administration. Contacts/Gmail-specific management and other cloud-only/desktop service workflows
are separated from display of ordinary provider-supplied events. Existing exposed conference links
remain supported. Historical optional capabilities absent from the live app are retained explicitly
in the CSV rather than silently counted as implemented.

## Acceptance still required beyond the inventory

| Gate | Evidence at this checkpoint | Required completion |
| --- | --- | --- |
| Google personal + Workspace compatibility | `NOT_RUN` physical/account matrix | Dedicated authorized calendars and second-account create/update/offline/reconnect/recurrence/invite/RSVP/cancel/reminder round trips; compare remote results. |
| Device usability and background operation | `NOT_RUN` physical matrix | TalkBack, keyboard, dense/large calendars, tablet/landscape/multi-window, notification permissions/channels/Doze/reboot/timezone and widget host lifecycle. |
| Seven-day work/personal beta | Not started | A full week including weekend, offline use, remote edits, recurring changes, reminders and upgrade; no data loss, duplicate writes or missed eligible alerts. |
| Durable APK release | Pending | Stable signing and upgrade/install verification, checksum, accurate limitations/release notes and a durable downloadable APK. A debug APK is not this gate. |

Implementation priority follows dependencies: atomic durable draft/fencing and remaining recurrence
execution; meeting backend/drafts/native UI; reminder setup/open routing and widget actor/bootstrap/
application integration; slot/drag/resize, external intents and remaining settings/weather behaviors.
Account/device gates proceed only with dedicated authorized fixtures. A blocked external gate does
not turn a component test into device proof or prevent independent implementation work.
