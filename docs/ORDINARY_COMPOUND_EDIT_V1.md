# Unified ordinary editor and colour: allocated V1 contract

Lead allocation on 20 November 2026, after independent contract review. This supersedes the separate colour-only editor/subflow in
`/tmp/daylight-event-color-existing-row-native-proposal.md`. The normal phone editor retains all
ordinary raw input and colour choice, and one explicit Save submits one new compound operation.
The identifiers below are reserved for this independent family. Implementation and activation remain pending; allocation does not admit any installed provider or authorize native effects.

## Lead-allocated identifiers

| Concern | New identifier | Allocated version |
|---|---|---|
| Pure command family | ORDINARY_COMPOUND_EDIT_V1 | command1 |
| Canonical source family | ORDINARY_COMPOUND_EDIT_SOURCE_V1 | source1 |
| Source token prefix | ordinary-compound-edit-source-v1: | prefix fixed with source1 |
| Command fingerprint prefix | ordinary-compound-edit-command-v1: | fingerprint1 |
| Native receipt family | ORDINARY_COMPOUND_EDIT_RECEIPT_V1 | receipt1 |
| Native effect policy | AOSP_EXISTING_ROW_COMPOUND_EDIT_V1 | effect1, default UNKNOWN |
| Retained draft feature | ordinary-compound-edit | DraftRecord.version10, envelope schema1 |
| Frozen submission family | ordinary-compound-edit-submission | frozen schema1 |
| Raw intent fingerprint prefix | ordinary-compound-edit-raw-v1: | fingerprint1 |

DraftRecord version10 is reserved for ordinary-compound-edit only. Existing parsers, source formats and frozen submissions remain unchanged until their separately reviewed implementation lands.
A owns native source/receipt/SQLite storage integration and must confirm its namespaces; D owns the
new retained editor payload/controller; lead owns routing/browser/composition/retirement dispatch.
No changes to old EventPatch, EventDraft, EditableField, old fingerprints, old frozen commands,
ordinary draft1/4/5, sourceV1, receipt4/6 or meeting/series families. No SQLite schema bump expected.

## Exact proposed pure API surface

New file `calendar-core/.../OrdinaryCompoundEdits.kt`, tests in its own new file:

- `CompoundEditSourceToken(value: String)`: distinct bounded opaque source token, never an EditToken.
- `CompoundEditRevision(namespace: String, generation: Long)`: adapter-issued read generation.
- `CompoundEditableEvent`: immutable full CalendarIdentity, exact guest-free non-recurring EventRef,
  EventDetails/display baseline, immutable allowed ordinary EditableField set, new source token and
  ordinary revision, and optional colour observation/rights. A colour observation that cannot fit
  its own bounded model is unavailable for colour authoring; the native raw source still preserves
  its exact cells for ordinary Keep and does not disable ordinary editing. Optional palette observations use their OWN
  separately versioned read result/revision; they do not participate in ordinary source validity.
  Private/native source bytes remain adapter-owned.
  Factory preflights row counts, scalar and aggregate units BEFORE copying/hashing, then deeply
  snapshots EventDetails.attendees/reminders, allowedFields and every Set(alertMinutes) into
  unmodifiable collections. Nested attendee/reminder immutable values are retained exactly. It
  validates details/calendar/ref/context identity agreement.
  The factory does not grant native rights. Colour capability UNKNOWN need not disable independent
  ordinary editing; a missing palette/colour projection is not fabricated as empty/default.
- `CompoundEditDraftBinding(draftId, generation, rawRevision, sourceToken)`: bounded canonical IDs;
  binds an acknowledged pre-freeze raw draft to the service source. This is local intent context,
  not provider permission. Existing source ref and full namespace remain in the issued source.
- `CompoundEditIntentV1`: internal-constructor immutable value containing binding, exact source
  identity/revision/ref/token, a snapshotted existing `EventPatch`, and `EventColorChoice`. Use
  @ConsistentCopyVisibility. Its public factory accepts the current issued CompoundEditableEvent,
  exact binding, sparse EventPatch and choice; it deeply snapshots Set(alertMinutes) and every
  retained list/set before validation/fingerprinting. It does not expose native ContentValues.
- `CompoundPaletteObservation`: Loading/PermissionRequired/Unavailable/LimitExceeded/Unsupported or
  Ready with an immutable independently issued palette revision plus full EventColorPalette. Its
  EventPaletteContext.source is the PALETTE revision; the ordinary source token/revision is separate.
  A named choice retains that exact palette context/stamp/revision. No palette result is an ordinary
  mutation capability or a reason to erase ordinary source validity.
- `CompoundEditPlanResult`: NoChange; Review with typed reasons; Ready with immutable ordinary sparse
  patch plus a semantic optional native colour-key change. Ready is not dispatch authority and
  contains no provider URI/operation. Empty colour key means explicit calendar default.
- `CompoundEditPlans.resolve(current, expectedBinding, intent)`: expectedBinding is an EXPLICIT
  separate argument representing the caller's currently acknowledged draft binding. Compare it
  exactly with intent.binding before inspecting choices or planning. Recompute full namespace/ref/
  ordinary-source-token/revision agreement, existing ordinary field rights, and colour choice
  against the current separate palette observation only when Named. No Android, JSON, clock,
  current timezone, storage lease or provider access. This is a consistency function over supplied
  values, NOT proof that expectedBinding is the actual live lease. The controller separately
  reloads/exact-CAS-checks the live record/generation/raw revision before freeze and dispatch; the
  adapter separately verifies its private source token/current provider and frozen native plan.
  The native adapter never trusts a caller-supplied Ready value or supplied expectedBinding as
  provider authority.
- `CalendarCompoundEdits`: suspend loadForEdit(ref) -> new read result; update(operationId,intent)
  -> existing MutationResult; recover(operationId) -> existing MutationResult. Read result types
  distinguish Ready, PermissionRequired, Denied, LimitExceeded and Unavailable. update and recover
  are a separate port so the old CalendarMutations cannot reinterpret or dispatch this family.

The pure implementation also exposes explicit `readPalette(newSourceToken)` as an optional read,
and immutable adapter-issued `CompoundEditAuthoringRules`: allowed availability values, known ALERT
method support, maximum reminders and an explicitly known count of preserved non-ALERT rows. Unknown
constraints never grant an authored change. Keep bypasses those authoring checks; empty ALERT
replacement can preserve other methods without claiming nonempty ALERT capacity. Changed TIME/ZONE
requires positive, exact-millisecond, year1–9999 bounds and a provider-compatible timed zone; unrelated
edits preserve imported point events, precision and unknown zones. These pure checks are consistency
checks only; native source, permissions, leases, receipt compilation and execution remain separate.

Factories bind ordinary intent to its independently issued ordinary token/revision, full namespace,
ref and explicit draft binding. Named additionally retains the independently issued palette context/
stamp/revision. It must match the CURRENT palette observation at resolve; callers cannot substitute
an old or independently foreign palette into a choice. The colour-only EventColors helper may be
used with an explicitly synthesized current context: Named uses the palette revision in its
EventColorChoiceContext.source, while Reset uses the ordinary revision and no palette. Both retain
the same exact compound ordinary token/namespace/target/draft binding. The new resolver compares
ordinary binding itself first and never infers ordinary validity from a palette revision.
`EventColorChoice.Keep` preserves missing/unknown/raw cells, and does not require palette availability.
CalendarDefault requires complete projected observations plus reset ALLOWED, but does not query,
require or inspect a named palette. Named alone requires a fresh full account/type-scoped palette,
independent palette context/revision/stamp/key/ARGB match, and choose ALLOWED. A stale/failed palette
disables Named only; it does not invalidate ordinary editing or ordinary+Keep. The read service and
adapter MUST NOT query Colors at all for an ordinary+Keep or Reset execution/recovery path. Palette
observation is an optional read requested by the picker/Named authoring path, never a prerequisite
for loading or saving the ordinary baseline.
A default or key choice remains explicit even if its preview matches the old display colour: raw
key/raw override semantics may differ. Colour Keep is NoChange only for the colour portion.

NoChange is considered ONLY AFTER valid ordinary identity/context and exact expectedBinding
comparison. It requires every ordinary field Keep AND colour Keep. A colour-only NoChange must not erase
other sparse fields. An explicit Set equal to the source may be kept as explicit authored intent;
normal editor diffing may omit it before constructing the command. No silent fallback from invalid
colour choice to Keep, no erasing an invalid raw ordinary field because colour alone is valid, and
no inferred ordinary changes from display text/renderer provenance/rounding unknown metadata.

## Canonical command/source and bounds contract

New command JSON has exact keys family/version/operation-independent binding/source/ref/calendar/
ordinary/color. operationId belongs to the frozen submission/receipt and its signature domain.
The ordinary object has exactly the old EventPatch field names, each encoded as explicit Keep or
Set with typed value; never Kotlin toString. Time is timed instant bounds or UTC-date boundaries;
zone Set(null), availability/privacy Set(null) and Keep are distinct. Alerts are bounded integer
arrays in exact authored order, unique/nonnegative under existing validation. The color object is
Keep, CalendarDefault, or Named with complete issued palette context/key/expected raw ARGB bits.
No arbitrary raw colour integer/key is accepted without matching the issued current palette.

New ordinary source encoding independently freezes the exact old native baseline rows PLUS the
four ColourCell states, full namespace, exact selected bounds, ordinary/colour rights and provider
effect policy. Its token and ordinary generation DO NOT encode, depend on, or change with Colors
availability, palette rows, palette stamps or palette observation generation. Colours can change
while an ordinary+Keep source remains valid, subject to the ordinary event-row source checks.

A Named command separately freezes the exact validated independently issued palette context/revision/
stamp and chosen key/ARGB evidence; its private palette proof is a distinct source component, with
its own bounds and freshness checks. Keep and Reset commands carry no palette snapshot or query
requirement. The receipt includes the palette proof only for Named. The adapter rechecks that Named
proof immediately before attempt/dispatch independently of ordinary source validity. Missing or
failed optional palette evidence never turns an ordinary source token into an unknown token. It may embed the old canonical baseline as
an exact opaque string but must not modify old encoding/token bytes or accept an old token in place
of the new source token. Missing, SQL NULL and exact Text are tagged separately, not collapsed.
Palette bounds4096/scalar,256rows,65536aggregate including palette context apply to COLOUR inputs
only; they do not cap ordinary imported notes/descriptions/location or make Keep re-author them.
Use an explicit pure `CompoundEditLimits` argument with bounded defaults, without importing
app ProviderReadBudgets: source/details cell262144 UTF-16 units, authored ordinary cell65536 units,
source aggregate1048576 units, ordinary intent aggregate1048576 units, attendees2000/reminders2000,
authored alert inputs4000 and at most EditableField.entries.size fields. Caller-supplied limits
may tighten defaults, never raise them past these hard ceilings. Palette uses the existing hard
limits separately. Every processed/repeated scalar and collection entry is charged with Long-safe
subtraction checks before defensive copy/hash; malformed collection iterators cannot evade bounds.
A large imported description within source/details bounds remains byte-exact under Keep; explicitly
authoring Set must meet the authored limit. Do not trim, truncate or normalize to make it fit.

The native source/command/receipt UTF-8/canonical budget (currently1MiB), escaped worst committed ACK
envelope and Parcel sizes are A-owned additional limits. Pure bounds are not proof that native JSON
or the escaped ACK fits; both layers must pass. Native operations and worst committed envelope must
fit before PREPARED, not only the small draft. Defensive snapshots and preflight bounds precede
copy/hash/canonical expansion; reject unknown keys, wrong types, noncanonical numeric fields and
version coercions. UUID validation applies only to fields whose current formats promise UUIDs
(new generation/revision/operationId). Draft IDs may be accepted opaque legacy strings such as
edit:...; opaque namespaces/source tokens/keys are bounded and compared exact, never parsed as UUID
or normalized. Migration preserves those accepted identifiers without inventing new semantics.

The receipt freezes the compiled union of ordinary native values and optional EVENT_COLOR_KEY
change in ONE Events update, plus the existing supported replacement-alert operations, assertions,
result ref and native effects. Never call old CalendarMutations.update followed by a colour update.
The ordinary compiler is fixed to this new family after freeze; recovery validates retained native
values/postimage and does not invoke a newer authoring validator or load the mutable draft.
Pure contract code does not implement native compilation/receipt/SQLite; A owns those files.

## Explicit same-record draft transition and recovery

Colour becomes available in the existing normal editor when an explicitly chosen colour action
(or explicit upgrade control for old retained drafts) requests a transition. Opening/restoring a
legacy draft alone never rewrites it. On an acknowledged EDIT-only unfrozen draft with no operation
payload/kind/submitted/frozen/local outcome, stop input, flush exact raw pending input and acquire
the storage/action lease. Require the retained ordinary source namespace and canonical original
baseline still match a freshly issued compound ORDINARY source. Palette availability is irrelevant to this check.
Do not rebase the raw ordinary fields.

Build a new version10 record at the SAME draftId, new generation/revision/operationId, copied exact
raw ordinary fields/notices/zoneAuthored/render-provenance and new ordinary source context. Do not migrate an optional palette failure into an ordinary
source conflict. Start colour Keep
unless the explicit initiating colour action is valid for that exact newly issued source. If the
source changed, preserve the old record and return review; never adopt newer private event values
as though the user authored them. Incomplete raw fields survive transition and simply block Save.

Use AtomicDraftStore.compareAndSet(old exact DraftRecord,new compound record). Once CAS starts,
the old controller remains closed and cannot autosave or submit. Publish the new launch only after
exact replacement readback; an Unavailable ACK may be reconciled by exact readback, never a second
insert. Changed/unknown outcome leaves the browser to restore whichever exact record survived;
no duplicate draft, blind CAS retry, old controller reactivation or implicit overwrite is allowed.
Pending stale callbacks are fenced by feature+generation and render raw epoch.

Unfrozen legacy1/4 records first need their existing source identity/original proof made explicit;
proposal conservatively transitions only current version5 after explicit existing upgrade. A direct
1/4->new transition requires a separately reviewed equivalent proof, not guessed generation fields.
Frozen/submitted drafts of ANY old version remain entirely on old parser/service/recovery/retirement
and never transition. The new family has its own frozen raw fingerprint and strict browser dispatch.
Frozen new commands survive palette removal/read/write permission loss as recovery-only; grants,
recreation, opening, picker actions and dismiss never submit. Known committed recovery needs READ,
not WRITE or a new authoring palette; ATTEMPTED never replays without exact known-no-write proof.

## First scope and tests requested after contract approval

First activation: existing owned guest-free one-off ordinary EDIT, including compound title/time/
zone/notes/location/privacy/availability/alerts+colour. Do not expose unsupported colour on new
create/duplicate/meetings/series or change old Save routing until their own family is designed.
New empty colour control is not inserted into those forms as a misleading disabled promise.

Pure tests must cover: ordinary+Keep with Loading/Unavailable/foreign/stale/oversized palette
  observations must remain independent and must not even inspect palette values; Reset must not
  inspect palette either; only Named uses current palette evidence; sparse ordinary changes survive colourKeep/colour-onlyNoChange; every raw
colour state; source/binding/ref/fullnamespace mismatch including an intent copied from another currently
  acknowledged draft binding; forged/stale issued selection; null/default/
key0/whitespace; deep snapshots against caller mutation; explicit nullable patch intent; preservation
of imported point/millisecond/timezone provenance under unrelated edits; budget boundaries and
invalid context before hash/copy. Do not duplicate old field validation tests without a new boundary.

D/lead later verify: typing then colour transition preserves every raw field; in-flight/failed CAS;
stale callback/input; frozen old draft no migration; oldnewbrowser discrimination; one explicit Save
one atomic batch; one verified compound readback; colour invalid blocks entire Save without writing
ordinary fields; palette change during draft/save; READ/WRITE revoke; committed read-only recovery;
strict ACK/uncertain fences; injected failure leaves no split ordinary-versus-colour outcome.

Implementation ownership: B implements the new pure contract and meaningful tests; A owns native source, receipts and provider execution. D and lead own subsequent retained editor and routing integration. Native effect policy remains default UNKNOWN. No native acceptance or app activation is claimed by this allocation.
