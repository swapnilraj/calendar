# Implementation reconciliation at da9af95

This reconciles all **151 historical inventory IDs**, in their original order, against the
integrated repository at **da9af95**. The companion
[implementation-reconciliation.csv](implementation-reconciliation.csv) records current bounded
behavior, source/test paths, evidence level and remaining acceptance for each ID. The historical
[feature-inventory.csv](feature-inventory.csv), audit probes and screenshots remain unchanged.
Historical statuses describe their original baseline; this packet does not overwrite that evidence.

At this source checkpoint, focused checks cover the integrated features described below. The
latest full gate is **not yet green**: the lead reports one failure in a 1,275-test run, isolated to
an older draft-browser test fixture that lacks the newly required destination namespace. The fixture
correction is tracked separately; no corrected full-suite pass is claimed in this snapshot. APK assembly and lint completed
in that attempt. This reconciliation inspects source and existing tests; it does not rerun Gradle
or perform device/account checks. Later feature packets, including subsequent widget application integration, are not counted in this
explicit source cutoff.

SQLite `AtomicDraftStore` compare-and-set is now implemented, with generation/revision fencing in
meeting response drafts and atomic new-grid draft preparation. This is stronger than the previous
API-only snapshot, but remaining ordinary/series draft paths are not thereby all migrated or proven
race-free. Authored recurrence controls/drafts exist behind a production-disabled creation capability.

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
unbound submissions are read-only recovery, not permission to retry. SQLite atomic compare-and-set
is implemented and used by the dedicated meeting workflow/grid preparation; remaining ordinary/
series persistence and unresolved-operation abandonment fencing still need completion and verification.
Day-view empty-slot creation and nonrecurring move/resize now prepare the native durable editor after
read-only preflight and fresh context/source checks. DST ambiguity requires explicit review and a
namespace change locks retained fields. These UI pointer journeys use synthetic mutation ports;
separate backend tests do not turn them into a full actual-app/provider gesture journey. Three-day/
week gesture hookup and physical interaction acceptance remain pending.

The recurring route supports bounded ALL non-temporal edits/delete, THIS existing/new exception
edits/cancellation, and FOLLOWING deletion. Local integrated tests cover native UI → real adapters →
SQLite journal → transactional synthetic provider and fresh readback/recovery without replay. The
fixture has explicit Instances projections; it does **not** expand Google recurrence sets. Temporal or
pattern changes, following edits, full exception transfer and new repeating-event creation remain
unfinished. Authored recurrence UI/validation/review and durable serialization now exist, but the
production creation capability stays disabled until A22's verified provider executor is integrated.
Pure rule plans and hidden controls do not create repeating events in the live app.

Eligible invitations now expose native Respond actions. Ordinary Yes/Maybe/No journeys use the real
Application, atomic response draft, meeting writer, SQLite journal and transactional synthetic provider.
Tests prove a decline changes only the user's status, WRITE denial retains choices and requires another
explicit Save after grant, and known-commit frozen recovery reopens through Drafts without WRITE or
replay. Existing recurring THIS exceptions and complete ALL master/active-exception responses have
bounded backend fixtures, including hidden-own row proofs; generated THIS response insertion remains
unsupported. No Google RSVP propagation or email delivery is established by these local checks.

A16 bounds app-side provider row/cell/text materialization, search retention, mutation raw children
and canonical/stored JSON. Search reports partial/limit coverage instead of claiming complete empty
results; unsupported oversized mutation sources fail before dispatch. Reminder-source budget failures
are unavailable/deferred/incomplete and must not erase delivery history. These are local application
bounds, not a guarantee about the provider's first CursorWindow allocation or real-device latency.

## Components that must not be presented as finished features

- **Reminders:** live Event reminders setup is now connected to real DataStore CAS, namespace pins,
  independent calendar/default selection, permissions and generic test notifications. The Application
  singleton, source/SQL ledger/coordinator, private receivers/jobs and alarm/notification adapters are
  wired. Actual-app synthetic journeys post through NotificationManager, keep display-none unchanged,
  retain raw choices through permission changes, reject stale settings and open current native details
  from private post-token PendingIntents. Unpinning removes active own alerts without deleting delivery
  history. NotificationManager acceptance is **not** proof of sound, vibration, Doze/reboot or physical
  delivery; duplicate-alert coexistence and other device gates remain unverified.
- **Widgets:** agenda renderer/configuration/framework/runtime, durable FIFO actor and binding-ledger
  components are tested. Concrete application services/bootstrap and live application/manifest/
  navigation integration remain unfinished at this checkpoint. No enabled launcher schedule widget
  or month widget is claimed; E17 worker code is not counted as integrated application behavior.
- **Meetings:** native bounded RSVP is connected as described above. Invitation creation, organizer
  guest edits/permission controls, cancellation and attendee removal are still unfinished. Supported
  existing recurring response scopes do not implement all recurring meeting edits or generated THIS.
- **Entry points and gestures:** private notification-open routing and Day-grid creation/move/resize
  editor preparation are live. Public view/date/create/edit intent handling, global keyboard shortcuts,
  multi-day gesture hookup and recurring drag/resize remain incomplete. MainActivity's public manifest
  filter remains launcher-only; the notification destination is a separate private Activity.
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

Implementation priority follows dependencies: finish ordinary/series atomic-draft migration and
recurring creation/temporal/pattern/following execution; expand organizer/guest meeting workflows;
complete widget application/bootstrap/routes; add multi-day gesture/public intent/keyboard handling
and remaining settings/weather behaviors. Reminder and RSVP device/account verification still gate
release even though their bounded local setup/response journeys are connected.
Account/device gates proceed only with dedicated authorized fixtures. A blocked external gate does
not turn a component test into device proof or prevent independent implementation work.
