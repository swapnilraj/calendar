# This and following: bounded implementation design

Public-projection limitation (18 November 2026): references below to rejecting unknown fields
mean fields actually exposed by the provider. They do not establish complete physical-schema
visibility. New clone activation also requires the provider-profile gate in SPEC.md; see
[the observability audit](PROVIDER_CLONE_OBSERVABILITY_AUDIT.md). The gate is implemented; no production provider profile is admitted.

Current source status, 18 November 2026: the bounded exception-free executor is implemented in
`CalendarFollowingSeriesWriter`, routed through `CalendarSeriesWriter`, with component and actual-app
synthetic-provider checks. Nonzero independent tails remain gated by `ProviderClonePolicy.Unverified`
in production. The design below records its original implementation contract and extension work;
it is not Google compatibility evidence. Existing materialized exceptions, ALL and ordinal-zero
operations retain their separate eligibility.

`RecurrenceFollowingMetadata` supplies pure metadata-only partition plans, but the Android executor
does not consume it and still rejects exception transfer. Before expanding or admitting that executor,
a new versioned preparation/receipt protocol must freeze the winning native plan instead of
recompiling it during recovery, bind exact child insert acknowledgements, and check tail UID/calendar
authority before reading child payloads. Legacy receipts retain their existing conservative recovery;
new code must not reinterpret them or change frozen command/signature bytes. This is remaining source
work, separate from the provider-implementation and Google/Workspace acceptance gates.

## First packet and authority

Admit an owned, guest-free, same-kind series with one supported RRULE, a complete freshly read
master/children/membership graph, and a backend-issued edit token proving the selected native
instance and its ORIGINAL boundary. Calendar/account/provider namespace and write ownership must
remain exact. The boundary is a nominal recurrence key, not the visible event's date or position.

Initially require no exceptions, including cancelled exceptions, and no EXDATE, RDATE, or EXRULE.
Reject unknown or undecodable recurrence fields, incomplete source coverage, ambiguous ownership,
unsupported duration/zone, timed/all-day conversion, and opaque content that cannot be preserved.
Apply existing source, candidate, operation-count, and 256 KiB parcel budgets before dispatch;
exceeding a budget produces review with no actions. A selected original ordinal must be proved
within 10,000 candidates; a missing match is not permission to approximate it.

The first packet can support typed tail anchor, duration, pattern, and ordinary field changes when
the pure planner and existing master-template/provider-zone validators prove the complete result.
It must not reinterpret arbitrary raw RRULE text. Timed elapsed durations and all-day UTC date/day
durations retain their existing validation; timed nominal-day semantics remain review-only.
`selectedExceptionPatch` must be empty in this packet because no exceptions are admitted.

The editor receives backend-issued FOLLOWING recurrence capability only for this admitted graph.
It edits the selected nominal tail template; ALL edits continue to load the master template. Raw
editor and frozen-command persistence must retain the existing typed intent, original boundary,
token, namespace, and exact operation ID through process death. Old frozen payloads are not rebased.

## Rule partition and ordinal zero

For selected nominal zero-based ordinal `k > 0`, require exactly two semantic actions: prefix
`UpdateMaster` and `InsertTail`. The original master retains DTSTART, duration, UID, metadata, and
children. Only its RRULE limit changes. The tail starts at the selected nominal occurrence unless
the explicit tail-anchor change says otherwise. The preceding occurrence retains its full duration,
even if its end extends beyond the tail boundary.

* COUNT `N`: prefix COUNT is `k`, and a retained tail limit is COUNT `N-k`. These are generated
  nominal occurrences before exclusions, not visible Instances rows. Explicitly edited tail limits
  use the validated new limit instead.
* UNTIL or unbounded source: close the prefix inclusively at the preceding generated original key,
  using DATE for all-day and exact UTC instant for timed rules. Retained tail UNTIL stays exact;
  imported UTC cutoffs must not become inclusive local end-of-day cutoffs.
* `k == 0`: execute an ALL-equivalent single-master transformation under the original FOLLOWING
  command kind/signature. Do not insert a redundant tail, generate a new UID, or construct COUNT 0.

Add a bounded effective-tail-rule helper rather than applying the ALL effective-rule helper
unchanged. When every rule part is Keep, preserve literal source raw text except the necessary
COUNT partition, and prove it equivalent to the planned rule at the effective tail anchor. For
non-COUNT rules this can preserve the entire raw string. Dirty rule parts use the validated planned
replacement while retaining independent Keep COUNT/UNTIL semantics. Unsupported combinations,
unmappable ordinals, impossible end limits, gap/fold uncertainty, or disagreement between preserved
raw and planned semantics produce review before preparation. Never prepend an excluded DTSTART or
silently rewrite an imported limit to force a first occurrence.

## Independent tail identity and durable materialization

The new tail is an independent public series. Preserve the prefix UID; allocate a fresh random
public `UID_2445` for the tail, distinct from the source UID. Never copy source `_SYNC_ID`,
`SYNC_DATA*`, ORIGINAL links, or an exception's identity. ALL on the new tail addresses that tail;
there is no promised hidden cross-series family relationship.

No caller-controlled UID or new authority field is needed in `SeriesCommand`, `EventDraft`, or
the frozen command. Keep existing `SERIES_V1_FOLLOWING_EDIT` command serialization/signature bytes.
The backend computes the deterministic plan from the exact token and command, then atomically
prepares a versioned private journal payload containing the generated UID and expected provider
plan. Generated materialization data are separate from command identity.

For a missing journal row, allocate and persist this payload once. For a matching PREPARED/FAILED
row, reuse and validate the stored UID and plan; never generate a replacement during retry. A
concurrent prepare winner supplies the authoritative stored payload. Unknown, malformed, or legacy
missing preparation metadata is review/uncertainty, not permission to fill it in. Existing journal
kind/signature mismatch is a conflict. Add an explicit FollowingEdit recovery/receipt mode to the
writer's currently narrower recovery-kind allowlist, without reinterpreting older receipts.

## Public metadata and child copy policy

Use an audited public-column allowlist, never a full master-row clone. Derive tail DTSTART,
all-day flag, validated timezone fields, RRULE, DURATION, and fresh UID. Copy supported public
title, description, location, status, validated color fields, availability, access level, organizer,
and applicable guest-policy/attendee-data fields, then apply the explicit typed tail patch.
Require ownership before any child admission. Omit SELF_ATTENDEE_STATUS on insert so it does not
cause an implicit native self row; omit stored IS_ORGANIZER and verify its derived value instead.

The guest-free profile permits no attendees or exactly one fully understood, proven owner/self
organizer row. External guests, duplicate owner rows, delegation/alias ambiguity, and unknown
attendee identity metadata require review. If admitted, insert that row explicitly with a new ID;
never copy source child IDs. Prefix attendees remain untouched.

Clone supported reminder MINUTES/METHOD values exactly, including stored default sentinels and
non-alert methods. An explicit alert patch replaces only METHOD_ALERT entries. Unknown reminder
columns/method semantics require review rather than normalization. Nonempty ExtendedProperties,
or an inconsistent has-extended-properties flag, require review: a normal public app cannot create
those rows. Unknown nonnull OEM child columns or unclassified content-bearing event fields also
require review before any write.

Never write sync IDs/data, DIRTY, MUTATORS, LAST_SYNCED, derived LAST_DATE, raw-time helper rows,
joined Calendar columns, or computed alert state into the tail. Recognized `_SYNC_ID` is retained
only on the prefix. Populated opaque sync data are not universally proven bookkeeping: the
portable first profile refuses an unclassified populated payload rather than silently losing it
or copying server identity. This may exclude some imported/synced masters. A later explicitly
tested provider profile can classify additional bookkeeping without weakening the portable gate.

## One provider transaction and known-ID receipt

Use ordinary public collection URIs and linked batch operations, with no yield or allowed exception:

1. Assert the fresh canonical calendar/master/children source and empty exception membership,
   including both local ORIGINAL_ID and calendar-scoped ORIGINAL_SYNC_ID links.
2. Update the prefix RRULE with exact source guards and expected count one; preserve its other
   source fields and children. The ordinal-zero path instead applies its proved single-master edit.
3. Insert the tail through `Events.CONTENT_URI` with native recurring storage: DTSTART, RRULE,
   DURATION, and null DTEND. Supply the generated UID and admitted public metadata only.
4. Insert admitted children with new-event value back references. Assert the expected prefix,
   tail, parent links, and complete child sets in the same transaction using legal collection
   selections/count assertions and selection back references where needed. Inserts themselves do
   not support expected-count semantics.

Do not use `CONTENT_EXCEPTION_URI`'s RRULE split mode. In the pinned provider its following branch
can clone server identity and uses its own recurrence partitioning. A client-owned independent
tail needs explicit normal-insert semantics. Any failed pre/post assertion rolls back the batch.

Validate the acknowledged insert result as the expected normal Events URI with a positive new
tail ID distinct from the prefix. Persist known-ID COMMITTED immediately, before any provider
read. The versioned receipt binds original typed command/signature, source namespace/token,
prefix/tail IDs, UID, expected rules/templates/public payload and children. Do not invent an
EventRef from the old selected key.

After COMMITTED, verify exact native rows/children and bounded actual Instances expansion. The
returned identity is the new tail master, and any selected reference must come from a real native
instance with that parent and actual bounds. Missing/delayed expansion or remote drift is known-ID
uncertainty, not a failed insert. Only verified state can produce LocalSaved/VERIFIED. An empty
provider expansion must not be presented as a fabricated successful first occurrence.

## Recovery, discard, and reissue

Journal ATTEMPTED is durable before dispatch. Lost/malformed acknowledgement leaves uncertainty;
never search by title, time, or UID to infer the tail, replay the batch, or perform compensating
deletion. A known COMMITTED tail is recovered read-only by ID. Distinguish historical local commit
from current source drift; drift does not turn a committed operation into retryable work.

Use existing typed `OperationRetirement` with `RetirableOperation.Series` before discarding a
frozen request. Only confirmed `Retired` allows exact draft CAS deletion, retirement of the old
controller, and creation of a fresh operation/draft/source. The journal ABANDONED tombstone must
fence missing/PREPARED/FAILED rows and stale workers; ATTEMPTED and COMMITTED/VERIFIED cannot be
retired as safe retries. Unavailable retirement acknowledgement requires inspection of the same
typed command. It never authorizes a new operation. This fences app workers, not remote sync, and
does not claim an atomic transaction across provider and app-private journal storage.

## Extension and acceptance gates

EXDATE support next requires complete decoding and original-ordinal partition/remapping `k+i → i`,
with nominal COUNT unchanged by exclusions. Preserve DATE versus exact UTC identity and reject
unknown/floating/gap/fold forms. RDATE/EXRULE require separate set-algebra and duplicate/count/end
proofs; having a finite date codec alone is insufficient.

Moved/cancelled exceptions require complete payload/child preservation and original-key remapping,
not partitioning by actual moved date. Unsynced local rebinding needs separate provider tests.
Synced exceptions must not be reparented while retaining old server identity: a clone/tombstone
strategy needs adapter/device proof and may be impossible through public APIs for opaque metadata.
Unmappable, duplicate, orphaned, or incompletely read exceptions must refuse the whole split.

Required JVM/native-provider fixtures cover COUNT partitions and ordinal zero; exact UTC/DATE
UNTIL; month 31/leap/DST boundaries; raw Keep; forbidden-field copy refusal; batch rollback/count
guards; prepare races/UID reuse; ACK loss; delayed Instances; COMMITTED-before-read; retirement
races; and process recreation with exact frozen bytes. Fakes must enforce real URI/column rules.

Release remains gated on recorded physical Google personal and Workspace cases: synced source,
prefix/tail appearance and UID behavior after refresh, no duplicated series after process death
or lost ACK, Count/Until/DST fidelity, prefix metadata preservation, and permission/account changes.
Native local verification does not prove Google adapter acceptance, server identity retention,
invitation delivery, or cross-client behavior. No such result is currently claimed by this design.

## Source anchors

* Android 16 CalendarProvider [pinned source](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/77bed8f978646fc416a7d90f9e97485421961641/src/com/android/providers/calendar/CalendarProvider2.java):
  exception handling near lines 1914–2187, normal insert near 2387–2481, attendee mirror near
  3031–3054, and public-write restrictions near 4601–4644. The single-exception branch clears
  sync data; the following branch is a different path and must not be assumed equivalent.
* Same pin, [SQLiteContentProvider](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/77bed8f978646fc416a7d90f9e97485421961641/src/com/android/providers/calendar/SQLiteContentProvider.java):
  applyBatch transaction/no-yield behavior. Same pin's CalendarDatabaseHelper documents child copy
  fields; this is provider evidence, not a universal OEM/Google adapter contract.
* Android 16 [CalendarContract](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/provider/CalendarContract.java)
  and [ContentProviderOperation](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/content/ContentProviderOperation.java):
  public event/attendee fields, exception URI, batch back references and expected-count limits.
* Android 10 [provider comparison](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/f013f1c6428ebb1702543a184d6f6d356321d9ef/src/com/android/providers/calendar/CalendarProvider2.java).
  Device/OEM divergence remains an explicit compatibility gate.
