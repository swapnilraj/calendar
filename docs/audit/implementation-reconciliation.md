# Connected implementation reconciliation at bf2ef3f

This snapshot reconciles all **151 historical inventory IDs**, in their original order, against
integrated source at **bf2ef3f**. The companion [CSV](implementation-reconciliation.csv) records
bounded behavior, source/test evidence and remaining acceptance. The original
[feature inventory](feature-inventory.csv), baseline audit/probes and physical `NOT_RUN` evidence
are unchanged. This is a current reconciliation, not a rewrite of the audited `23808c1` baseline.

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

## Connected application behavior

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

## Backend work and remaining interfaces

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
