# Daily-driver remaining implementation — 19 November 2026

This is a source audit of aggregate commit `5aac1b14902465b491160193abbcc4056ffa4041`,
not a new build, test, device or account acceptance result. Publication was independently checked
against `origin/codex/daily-driver` at `f010d564`; these are different trees. Existing root code,
published code, bounded fixture evidence and external acceptance must remain separate.

The accepted contract is [SPEC](../SPEC.md), M0–M9 at the line anchors below. The historical
[151-row reconciliation](implementation-reconciliation.csv) remains a snapshot at `bf2ef3f`.
Its counts and phrases such as “backend only” must not be presented as the current aggregate status.
This report changes neither that historical CSV nor the release scope.

## Follow-up at the accepted b45a89d aggregate

The pending-invitation display/filter implementation is now integrated: General selection persists,
main views and search use the same choice, UNKNOWN classifications retain qualified coverage, and
reminder/widget/direct-link reads remain independent. The full2,526-test/debug/lint gate passed with
inspected screenshot verification. Exact fixture restoration regressions were reproduced and fixed;
the earlier five-failure aggregate is retained, not counted as a pass. See
[accepted checkpoint evidence](evidence/aggregate-2526-checkpoint-2026-11-19.json).

Six feature families therefore remain beyond publication: selected-calendar editor conflict
advisories, event colours, organizer guest permissions, verified calendar moves, full exception/date-set
recurrence transformations, and recurring organizer edits/cancellation. The pure conflict calculation
is in the accepted aggregate; its separately tested native reader and editor bindings do not yet
activate an advisory. The new duration-graph contract/receipt has19 focused checks passing but no
native executor in this checkpoint; current graph/rule/profile guards still apply.

Additional malformed-provider-input hardening is tracked separately: the shared calendar reader's
access-level Long-to-Int narrowing can wrap unknown values. The staged conflict adapter independently
attests raw policy and does not rely on that narrowing; global reader hardening still needs its own
bounded regression and fix. The retained native reminder failure is unchanged. New isolated v2
verification foundations are source work, not a new device attempt or reminder-delivery pass.

Google/Workspace, physical accessibility/upgrade/reminder recovery, seven-day beta, stable owner
signing and accepted durable release remain pending. The following table retains its original
5aac1b1 source references and is historical where this follow-up supersedes it.

## Original source audit result

Finishing publication is necessary but does not finish the agreed daily-driver implementation.
Concrete remaining source work includes exception-aware recurrence transformations, verified
cross-calendar moves, event-colour editing, organizer guest-permission controls, recurring organizer
editing/cancellation, a pending-invitation filter and selected-calendar scheduling-conflict checks.
Existing conservative gates must not be
disabled to make these features appear available.

COUNT/date/no-end controls, the 90-day reminder planning horizon with long advance offsets,
filtered search, sidebar selection and the planning views already have implementations. They need
their outstanding integration/external evidence, rather than being counted again as absent features.

## M0–M9 audit

SPEC line numbers below refer to `5aac1b1`.

| Requirement | Aggregate implementation and source anchors | Concrete remaining implementation | Evidence/publication distinction |
|---|---|---|---|
| M0, SPEC:374, Google compatibility | Controlled matrix and dedicated-fixture procedures in [GOOGLE_COMPATIBILITY](../verification/GOOGLE_COMPATIBILITY.md). | No local substitute can implement remote synchronization or employer-policy access. A failed required workflow must be resolved or explicitly rescope the release. | Personal/Workspace two-account mutations, offline/reconnect, invitations, RSVP, recurrence and reminder round trips remain required external gates. |
| M1, SPEC:382, reliable rendering | `ui/DayHeader.kt`, `DayGrid.kt`, `EventLayout.kt`, `EventBlock.kt` and core temporal geometry implement overflow, full semantics, focus restoration, large-text sizing, RTL and DST bands. | No additional general rendering feature is established as absent by this audit. Defects found in real accessibility/density use still require source fixes. | Component/layout tests and synthetic images do not establish physical TalkBack, keyboard, tablet or Google visual parity. |
| M2, SPEC:400, calendars/range reads | `data/calendar/CalendarReader.kt`, shared invalidation/budget helpers, `data/CalendarPreferences.kt`, sidebar/settings sources implement metadata, conservative rights, range reads, persisted filters/defaults and coverage states. | No new general sidebar/filter implementation gap found. Scheduling-conflict and invitation filters below are distinct missing features. | Real provider coverage, namespace changes, dense-calendar performance and observer lifecycle remain verification gates. |
| M3, SPEC:417, navigation/views | Root `ui/LiveCalendarDay.kt` connects day/three-day/week/month/agenda and shared date state; dedicated view/date/navigation sources exist. | Publication of the reviewed grid/series/incoming interfaces is separate work, not absence of these root implementations. | Device task-stack/date restoration and exact Google gestures/arrangement remain unverified. |
| M4, SPEC:428, details | Native details expose authorized event/context fields, guest responses and safe existing links; specialized/read-only events retain conservative actions. | No general details gap found within this bounded contract. Event-colour display is not colour editing. | Google link behavior, physical accessibility and account rights still need actual checks. |
| M5, SPEC:437, ordinary management | `EventEditorController`, native phone editor, atomic drafts, receipt4 edits and receipt6 creates implement bounded authoring/recovery. Grid editor preparation and duplicate-as-draft exist. | **Event colour and verified cross-calendar move are missing.** `EventDraft`/`EventPatch`/`EditableField` in `calendar-core/.../CalendarMutations.kt:7–41` have no colour or destination-change command. The editor cannot turn an existing event into a verified copy/delete move. | A drag that changes event time is not a calendar move. Ordinary create+delete manually performed by a user is not the required move protocol. Current guard/recovery proofs do not certify new field/protocol versions. |
| M6, SPEC:469, recurrence | Presets/custom interval/day/month patterns and COUNT/date/never limits exist in `RecurrenceEditorControls.kt` and core recurrence sources. Native bounded THIS/ALL/FOLLOWING execution exists. | **Full explicit/moved-exception transfer and re-anchoring remain incomplete.** `CalendarSeriesWriter.kt:243–248` rejects temporal/repeat changes with overrides/date sets; `CalendarFollowingSeriesWriter.kt:410–413` requires an empty exception graph for the EXDATE metadata bridge; `RecurrenceFollowingRules.kt:34–36` rejects exception/date-set shapes for temporal tail edits. | Pure transfer/mapping plans in `RecurrenceEditing.kt:94–160` are not complete native execution. Default clone admission remains Unverified; generated THIS/new exceptions and nonzero tail clones must stay closed pending an admitted provider profile and evidence. |
| M7, SPEC:483, work meetings | Guest entry/type/removal, eligible RSVP, one-off organizer reschedule/cancel, recurring meeting creation and dedicated frozen meeting drafts are implemented through separate meeting ports. | **Editable guest-permission flags, recurring organizer editing/cancellation, pending-invitation filtering and selected-calendar time-conflict checking are missing.** `MeetingCommand.EditOwned` exposes event patches/guest deltas, not permission flags; settings expose `showDeclined`, not pending-only filtering. No selected-calendar conflict-preview port/UI was found. See the exact recurring organizer boundary below. | Journal/source `Conflict` outcomes are not scheduling conflicts. Coworker availability is excluded, but conflicts against the user's selected calendars are explicitly required. Local guest writes do not prove invitation/update delivery. |
| M8, SPEC:599, search/preferences/entry points | `SearchController.kt:14–23,45–81` provides default previous12/next24-month ranges, expansion, 300ms debounce/cancellation and context masking. Settings/widget/private incoming review implementations exist in root. | Complete reviewed public activation and quarantine wiring still needs publication/integration. A proposal alone is not source acceptance of its shared-file patch. | Search/filter functionality is not absent. Public intent resolution, widget host behavior, physical keyboard navigation and Android lifecycle proof remain separate gates. |
| M9, SPEC:639, reminders | `android/reminders/ReminderCoordinator.kt:35` uses a 90-day horizon. `CalendarReminderSource.kt:36–65` reverses each reminder offset to query event windows beyond the ordinary horizon. Runtime/jobs/alarms/ledger/snooze/setup sources exist. | Investigate and resolve the retained actual smoke failure described below; do not infer that a failed fixture setup proves an alarm-delivery defect or successful delivery. No missing advance-window algorithm found. | Actual alarm/snooze/dismiss/Doze/reboot/upgrade acceptance is not passed. SDK metadata preflight and host-controller tests do not pass reminder delivery. |

### Important non-gaps and bounded evidence

- `NativeSeriesTemporalJourneyTest.kt:42,59,78` covers raw COUNT retention, explicit rule/count
  changes and exact imported UNTIL retention. This is bounded synthetic provider evidence; complete
  exception-aware transforms and Google occurrence sets remain separate.
- `CalendarReminderSourceTest.longAdvanceAndNegativeOffsetsQueryShiftedWindowsBeyondNinetyDayEventHorizon`
  uses an event 200 days away with an advance reminder due now and a negative-offset control. This
  directly addresses the advance-window requirement; it was not re-executed for this audit.
- Selection/range/context scrubbing is implemented in search. A pending-only invitation filter
  must not be approximated by `showDeclined=false` or by changing provider visibility/sync settings.
- Accessible labels, focus restoration, live regions and editor alternatives exist. A complete
  physical TalkBack/keyboard/multi-window assessment is still evidence work; absence of a global
  shortcut binding alone does not prove that Compose keyboard focus/navigation is absent.
- Historical optional/excluded rows—month widget, manual city override, alternate-calendar overlay,
  full interface translations, response notes, alternative-time proposals and quick communications—
  must not silently become additional M0–M9 blockers. Their historical status remains explicit.
  Event colour, moves, guest controls and selected-calendar conflicts are included by the contract.

### Actual reminder smoke status at report preparation

The source checkpoint above predates this later session evidence. The actual guarded smoke report
`/tmp/daylight-smoke-guarded-sdk-e132aaa0-53c2-4325-a671-ccf3992eb8fd/09-real-smoke.json`
is **FAIL**, state `FAILED_RETAINED`, category `SMOKE_UNAVAILABLE_RETAINED`; calendar/event IDs
are absent. Its exact-owned-manifest diagnostic `10-failure-manifest-diagnostic.json` records
`failedAt=CALENDAR_ATTEMPTED`. This is an unknown native outcome, not proof that no calendar was
created and not an alarm/snooze PASS. Retain custody, attempt, manifest and reports. No retry,
reinstallation, clear-data, broad lookup/adoption or cleanup follows from this audit.

The exact cause and any recovery require separate reviewed source/evidence and bounded authority.
The earlier old-UID metadata/readiness/removal and later fresh-UID run must not be conflated.

### Recurring organizer operations are missing in both layers

This is not merely an unwired complete backend. `OwnedBaseline.eligible` in
`ui/events/meetings/owned/OwnedMeetingDraft.kt:48–51` requires no recurrence reference, no recurrence
rule, no master template and THIS-only scopes. `LiveCalendarDay.kt:714–717` skips organizer-owned
recurring meetings when loading meeting actions; its owned editor action at line748 requires that
one-off eligibility. The ordinary series route does not fill the gap:
`CalendarSeriesReader.kt:195–197` rejects guest rows that are not the calendar's own account.

`CalendarMeetingWriter.kt:90` dispatches recurring references to `CalendarMeetingSeriesWriter`.
That executor at `CalendarMeetingSeriesWriter.kt:71–72` accepts only `Respond` for existing THIS
and complete ALL scopes, rejecting organizer edit/cancel commands. `CalendarOwnedMeetingSource.kt:40`
also rejects recurring references. Declaring scope-bearing `EditOwned`/`CancelOwned` command types
does not establish their execution. Recurring creation and attendee RSVP are distinct implemented
features; they do not implement organizer changes to the created recurring meeting.

## Smallest bounded contract/provider plans

These are proposals for lead-owned contract review, not new API declarations or authorization to
write provider data. Provider/storage work belongs to A after that review; UI/durable feature drafts
belong to D; independent fixtures and verification belong to F. Use separate packets and preserve
current opaque journal/draft formats, legacy fingerprints and known uncertain-operation behavior.
No database DDL is currently justified: the existing opaque operation store can hold versioned
plans. If a proven requirement needs an index/table, review its migration separately before coding.

### P1 — Pending-invitation filter: read-only first

Lead first defines a default-All filter and a conservative pending classification. Use exposed
self-response/meeting-role evidence; unknown, malformed or ambiguous role/status is not a fabricated
pending invitation or a confirmed non-invitation. Define how unknown coverage is communicated.

A then threads that contract through the existing bounded reader/search request, shared invalidation
and returned coverage. Apply it consistently to views/search; never mutate provider visibility,
subscription or response. Settings needs one independently updated optional DataStore field,
defaulting to All when absent; do not overload `showDeclined` or replace unrelated settings.
Every request/cache/context key must include the filter so old rows are masked immediately.

Tests must distinguish invited/accepted/tentative/declined/personal/unknown rows, calendar selection,
permission/namespace loss, settings races, cancelled searches and old-context result rejection.

### P2 — Selected-calendar time-conflict preview: read-only

Lead defines a bounded advisory result containing context, exact overlapping occurrences and
complete/partial/unavailable coverage. Reuse the shared range engine; no coworker directory/free-busy
service or network API is implied. Exclude only the exact occurrence being edited, not every row
with the same master ID. Use half-open instant overlap for timed events. Interpret all-day UTC date
encoding as dates in the explicitly chosen planning zone before comparing timed intervals.

Define declined/cancelled/FREE/unknown availability policy. Incomplete reads must not report “free”.
The context contains selected calendar identities, planning interval/zone and current draft revision;
stale results are masked/cancelled. A pending-only display filter must not silently hide accepted
busy appointments from conflict checking. No journal or SQLite schema change is needed.

Tests cover touching boundaries, same-master different occurrences, all-day dates across zones/DST,
FREE/declined/unknown rows, partial coverage, source/selection changes and permission cancellation.

### P3 — Event colour: provider palette and explicit authoring

Lead defines Keep, an explicit calendar-colour reset, and a provider-issued palette selection.
Keep is distinct from reset. Bind selection to the issuing account/calendar namespace, current
palette and edit/create rights; do not accept arbitrary RGB, a label or an unverified string key.
A bounded Colours reader must classify unsupported/unavailable/unknown palettes conservatively.
Review the exact native key/reset encoding against the targeted provider before permitting writes.
The current general detail model exposes resolved `occurrence.color`, not the raw event colour/key;
add reviewed raw source evidence rather than treating the resolved calendar colour as an event override.

Preserve raw imported colour/key and every unrelated field on Keep. Extend event source authority,
the authoring model and UI only through reviewed contract changes. New writes must freeze their
palette-bound native plan and verify the resulting raw key/effective display colour as appropriate.
Do not extend a frozen receipt4/6 or legacy command's interpretation in place. Review an explicitly
versioned authored-field command/receipt branch and its retry/retirement fingerprints; old requests
remain on their issued protocol. The existing opaque journal should require no DDL.
Lead must allocate family/schema/wire versions: frozen ordinary version2 already represents recurring
CREATE, and ordinary draft family6 would collide with meeting-create family6. No local version guess
or blanket ordinary VERSION increment is part of this plan.

Start with guest-free one-off events. Series/meeting colour editing requires separately defined
scope and metadata-preservation rules. Meaningful tests include palette/account changes, reused IDs,
unsupported palette/reset, opaque metadata sentinels, denied rights, exact ACK/post-read proof,
uncertain recovery, unchanged legacy bytes and typed retirement.

### P4 — Organizer guest permissions: narrow owned one-off edit

Lead defines independent Keep/Set changes and backend-issued rights for modify-event,
invite-others and see-guests flags. Unknown source values remain unchanged on Keep; never coerce
unknown to false. Start with a freshly verified owned one-off meeting. Delegated ownership and
THIS/ALL inheritance remain unsupported until their own preservation contract is reviewed.
`EventGuestPermissions` describes observed policy, including UNKNOWN; it is not an issued right to
change that policy. Current owned family9/protocol1 baseline/rights/command keys are strict, and its
source stamp lacks direct policy-flag coverage. A new versioned baseline/right/fingerprint must bind
those flags; adding optional UI booleans to protocol1 is insufficient.

Use the dedicated meeting writer, not the ordinary guest-free writer. Recheck namespace, UID,
organizer/source token and each field's current right before an asserted native update. Preserve
attendee IDs, responses, aliases, reminders and unrelated fields. Changing flags must not replace
the guest list, manufacture self responses, or imply suppressible attendee updates.

New fields need a reviewed versioned meeting command/draft/receipt branch; existing frozen commands
and legacy bytes remain unchanged. Freeze intent before dispatch and retain read-only recovery.
No new table is indicated. Extend meeting creation separately after its eligibility/fixed-policy
contract is reviewed. Tests cover individual rights/unknown values, role/UID changes, same-value
choices, opaque attendee preservation, uncertain writes and legacy recovery. Remote guest-visible
effects remain M0 evidence.

### P5 — Verified cross-calendar move: preservation and journal contract first

This is the highest-risk missing ordinary feature. No concrete cross-calendar writes are approved
by this plan. Root must choose and review the preservation, conflict, phase and recovery semantics
before an executor is implemented. Limit initial scope to guest-free non-recurring events with
fresh source/delete and destination/create authority. A null public projection cannot certify all
hidden provider metadata; retain Unverified clone refusal until an appropriate preservation profile
is admitted. Cross-account sync effects need separate evidence.

The recommended design candidate is a staged **copy, verify, then delete**, with one durable parent
operation and exact phase identities. Freeze the destination UID/native plan once. An uncertain
copy must retain the original and never blindly insert again or search/adopt by title/time/UID.
Only exact acknowledged destination/child proof plus fresh source/namespace checks may authorize
the source-deletion phase. Recheck the verified copy and original together in the delete transaction.
Source changes, incomplete preservation or ambiguous ACKs retain both known states for explicit review.

This candidate deliberately permits a visible incomplete move rather than silently losing the
original. The product must distinguish copy retained/source kept, move completed and unknown outcome.
Returning LocalSaved for the destination alone is not a completed move. Recovery reads known IDs and
the frozen plan; a separately authorized continuation may advance a proven next phase but must not
replay an uncertain phase. Automatic compensation/deletion is not implied.

Review one parent/child fencing model so retirement cannot race a late copy/delete worker. Define
reservation for worst-case receipts before the first mutation, phase ACK layout, exact source and
destination assertions, child membership, both cleanup outcomes and failure after each durable step.
Use the opaque operation store if it can represent these invariants; propose DDL only if a concrete
atomic-index/phase requirement proves it necessary. No temporary facade or reuse of ordinary
retirement to erase an already-created copy is acceptable.

Tests need every phase boundary, actual rollback versus unknown commit, lost/partial/malformed ACKs,
source/destination UID/namespace/permission changes, concurrent remote edits, receipt overflow,
duplicate actions, late workers, restart recovery and exact retained-child preservation. Native and
Google/Workspace move verification follow source/fixture acceptance; neither is assumed.

### P6 — Finish full exception-aware recurrence without weakening current gates

Reuse existing pure ordinal/exclusion/exception mapping plans, then add bounded provider materializers
for the missing native shapes. Preserve original occurrence identity, moved overrides, cancellation,
attendees/reminders/opaque metadata and lexical COUNT/UNTIL/date-set semantics. Unmappable overrides
remain explicit review. Extend immutable execution/ACK/retirement variants only after their contracts
are reviewed; existing2/3/4 operations retain issued bytes and recovery.

Separate exception-free metadata, temporal/rule changes, EXDATE, RDATE/EXRULE and explicit exception
graphs into reviewed admission families. Passing the existing EXDATE metadata bridge does not enable
the rest. Keep clone-dependent paths closed until provider preservation evidence permits them.
Compare actual native and Google occurrence sets, including month ends, leap days and DST, after
the bounded implementation and adversarial fixtures pass.

### P7 — Recurring organizer edit/cancel workflow and executor

Lead first specifies issued per-scope organizer rights and source/graph coverage for recurring
guest-bearing meetings. Keep attendee `Respond` separate from organizer changes and from ordinary
guest-free series operations. Begin with the smallest proven scope, such as complete ALL metadata
updates/cancellation, only after its exception/guest mapping and attendee consequences are defined;
do not infer those semantics from the existing RSVP executor or from scope-bearing command types.

A then implements a separate versioned frozen meeting execution family with exact master/exception
UIDs, source tokens, guest selectors/response preservation, native plan and complete ACK membership.
Unsupported/incomplete graphs and clone-dependent generated THIS/FOLLOWING stay closed. Temporal,
rule and guest-list changes need their own reviewed admission rather than a broad “all editable”
capability. Unknown attempted outcomes remain read-only recovery, never automatic replay.

D needs a dedicated scope-aware organizer workflow, durable draft/browser family and controller
owner/uncertain-write retention. Do not relax `OwnedBaseline.eligible` to pass recurring rows into the
one-off editor, or route guest-bearing meetings through `CalendarSeriesWriter`. New families must
preserve current one-off owned drafts, recurring attendee responses and their frozen bytes.
Tests must show each admitted organizer scope, refusal for missing guest/exception coverage,
master/exception namespace changes, partial ACKs, source-loss recovery, retirement/late-worker races
and no duplicate provider or attendee updates. Two-account propagation remains a separate gate.

## Completion and handoff order

1. Lead reviews read-only filter/conflict contracts; A implements bounded reads/settings storage,
   D/C connect controls/previews, F tests context and coverage failures.
2. Review palette/colour and one-off organizer-permission contracts and explicit new protocol
   versions; implement and verify each independently. Do not modify current frozen variants.
3. Review move preservation/phase/fencing semantics before provider code; advance exception-aware
   recurrence and recurring organizer operations through separately reviewed native families and
   scope-aware UI, preserving existing attendee RSVP and one-off owned protocols.
4. Finish bounded publication/activation of already implemented root work while retaining current
   owner locks, private incoming quarantine and widget defaults. Re-review exact shared-file patches.
5. Run the required integrated gate on the actual assembled candidate, then the controlled M0/device
   matrices, physical accessibility/performance checks, seven-day beta and stable signed release.

This sequence does not waive the outstanding account/device, actual reminder, beta, stable-key or
durable-release gates. No Google UX parity, full matrix pass or source completeness is claimed.
