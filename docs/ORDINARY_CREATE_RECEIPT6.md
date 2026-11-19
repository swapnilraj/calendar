# Immutable ordinary CREATE recovery

Status: approved implementation packet, not yet activated in the aggregate app. Current ordinary
one-off creation uses receipt2; new recurring creation uses receipt5. Receipt6 is being implemented
in an isolated branch. It does not admit provider clone profiles or establish Google/Workspace sync.

## Contract

Use a separate ordinary CREATE handler for new one-off and recurring operations. Keep operation
kind `CREATE`, existing command JSON, command fingerprints, frozen-editor commands and retirement
fingerprints unchanged. Generate a canonical random UUID followed by `@daylight.invalid` only in
initial journal preparation. UID is never a caller argument or a discovery query.

Prepared receipt fields:

| Field | Meaning |
| --- | --- |
| `version`, `feature`, `kind`, `stage` | `6`, `ORDINARY_CREATE_RECEIPT_V6`, `CREATE`, `prepared` |
| `signature`, `command` | Unchanged CREATE fingerprint and exact existing command JSON |
| `identity`, `calendarSnapshot` | Full destination namespace and asserted calendar/capability fields |
| `plan.version`, `plan.mode`, `plan.uid` | Plan version 1, ONE_OFF or RECURRING, generated UID |
| `plan.event`, `plan.reminders` | Exact native event values including UID, and ordered minute/method rows |
| `plan.firstStart`, `plan.firstEnd`, `plan.allDay` | Frozen UTC bounds and event kind |
| `plan.recurrenceFacts` | Null or frozen local/offset witnesses for recurring arithmetic |
| `layout` | Deterministic calendar assertion, event insert and reminder result positions |
| `planDigestVersion`, `planDigest` | Versioned SHA-256 binding the journal operation key, command/signature, namespace, calendar snapshot, plan and layout |

The plan digest is separate from the command fingerprint. Its version must define deterministic
field ordering and scalar encoding. Strict decoding rejects unknown fields, coercions, non-exact
integers, contradictory phase/stage combinations, invalid null/omission forms, digest mismatch and
budget excess. It must not call `draftFromJson`, `ZoneId.of`, current recurrence compilation,
authoring validation or display normalization during retry/recovery. Frozen local/offset/UNTIL
witnesses validate the stored protocol arithmetically, following the meeting frozen-plan convention.

## Preparation and dispatch

Inspect the journal before fresh authoring validation or WRITE checks. An existing receipt6 must
match the caller's unchanged fingerprint and supply its immutable plan. An absent operation is
validated once, then a candidate UID and native plan are prepared. Reload the winner after
`store.prepare`: concurrent candidates can have different unused UUIDs, but only the durable
winner's UID, values, bounds and digest may dispatch. Explicit retries never regenerate them.

Before attempt, re-read the destination namespace and writable ACL/capabilities and assert the
frozen relevant calendar snapshot. Drift preserves the plan and blocks dispatch. Check cancellation
and permissions, then claim the exact winning operation through existing `store.attempt` CAS.
Retirement/another attempt fences dispatch permanently. No new SQLite schema or transition API is
required. Avoid nested or inverted handler/writer mutex acquisition when routing legacy operations.

Use receipt5's canonical ACK rules for both new modes: persist the canonical positive master and
bounded raw result facts as COMMITTED before interpreting child identities or reading payloads.
Missing, malformed, contradictory or oversized child facts retain known-master uncertainty.
Invalid/lost master ACK stays ATTEMPTED with no guessed ID. No title/time/UID search, compensating
delete, automatic replay or operation replacement follows an ambiguous attempt.

## READ-only recovery

1. Verify current calendar namespace/read ACL. Read minimal binding fields using the known ID,
   calendar and generated UID before descriptions or child payloads. Use guarded Events collection
   queries; Events item URIs cannot carry a selection.
2. Verify exact authored parent values and frozen bounds. Read raw Reminders collection IDs and
   membership only. Read payload only at acknowledged reminder item IDs, guarded by parent ID,
   calendar and UID; match each to its planned alert index.
3. Verify the bounded first instance, then recheck parent binding, membership, namespace and READ
   permission before VERIFIED. Persist complete ACK proof and the verified ref. Any optional learned
   sync ID must be consistently observed and cannot replace UID authority.

UID stripping/change, namespace replacement, drift, corruption or incomplete proof yields
uncertainty. Only definite native transaction rollback permits FAILED and a fresh explicit exact
retry with the same stored UID/values. Row absence never proves rollback. Provider/account behavior
that rewrites generated UIDs remains an explicit device/account acceptance concern.

## Legacy handling and limits

Receipt4 edits remain unchanged. Receipt5 retains exact ACK proof and its current-compilation
limitation. Receipt3 retains conservative commit recovery and its explicit retry behavior. Legacy
receipt2 one-off creates never stored child ACK identity or generated UID; do not invent that proof.
Legacy PREPARED/FAILED material is not silently rewritten to add UID. Existing retries stay on their
original protocol; separately retired/replaced operations can start receipt6. ATTEMPTED/known commits
never migrate or replay through recovery.

Reuse mutation read/storage budgets, the 256KiB transaction bound and receipt5 ACK caps. Preflight
prepared, minimal committed and complete verified receipts, including digest, UID, ACK facts/IDs
and optional sync-ID bounds. Estimate verification query/row work before dispatch. Unexpected
oversized readback stays uncertain rather than producing partial success. Do not log private fields.

## Required evidence

- Before/after one-off malformed/missing/reordered child ACK cases, equivalent recurring controls
  and canonical zero-alert success. Keep new UID/frozen-plan guarantees separate from ACK defect evidence.
- Recreation/store reopen, snapshot purge, WRITE revocation and throwing compiler/authoring seams
  prove recovery does not depend on current authoring rules.
- Competing candidates adopt one UID/plan and produce one batch. Exact retirement, cancellation,
  recover-vs-create and PREPARED/FAILED retry retain existing CAS fences.
- ATTEMPTED never retries or performs UID-only discovery. Same-ID/calendar UID replacement and
  namespace changes permit zero private payload reads before binding.
- Parent/child drift, membership changes, strict decoding corruption, phase contradictions and
  budget overflow cannot acknowledge success.
- Golden command/fingerprint compatibility, full legacy 2/3/4/5 coverage, and actual-app one-off and
  recurring recovery without a second provider batch.

These are backend acceptance criteria. Physical Google/Workspace round trips, offline behavior,
upgrade, reminders/accessibility, beta and stable signed release remain separate required gates.
