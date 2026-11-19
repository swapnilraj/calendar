# Following metadata edits with EXDATE: implementation packet

Status: planned, after immutable Following execution and the NULL end-timezone correction.
This packet adds only exception-free, metadata-only Following edits with bounded EXDATE values.
It does not enable a production clone profile or certify Google/Workspace propagation.

`RecurrenceFollowingMetadata` already supplies nominal partition and literal-token preservation.
The Android adapter must derive this proof from a fresh complete source, freeze exact native
material, then execute and recover without invoking a current recurrence planner. Existing receipt
versions and prepared bytes remain on their issued protocol. A schema extension requires a separate
version/compatibility decision before implementation; it cannot relabel an old prepared operation.

## Ownership and sequence

1. Lead approves the bounded contract and receipt compatibility design.
2. A implements native source eligibility, initial proof, frozen material, asserted batch and recovery
   in an isolated packet after the current receipt3 packet. A owns provider/storage changes.
3. F independently exercises malformed source, nominal counts, exact selection, rollback and recovery.
4. Lead integrates and runs the complete test/debug APK/lint gate. Native and account acceptance
   remain separate and cannot be inferred from synthetic-provider results.

## Native source boundary

Keep the existing zero-exception requirement. RDATE, EXRULE, temporal/rule edits, moved/cancelled
exceptions and any exception graph stay refused. Excluded selected boundaries refuse. Input budgets,
full namespace/ACL, selected identity and native occurrence bounds remain mandatory.

The general `RecurrenceDateCodec` is not native serialization authority. In particular, the pure
helper's grouped `UTC;DATE` fixture manually provides decoded keys while the reader's broader codec
rejects that combination. For the first adapter packet, keep currently undecoded forms refused.
Adding a narrow decoder is a separate reviewed extension with provider evidence; never bypass an
incomplete source decode or infer support from a helper result alone.

## Required acceptance matrix

| Area | Required evidence |
| --- | --- |
| Decoding | Admitted timed UTC-Z and all-day DATE/UTC-midnight forms; grouped forms only when the reader independently decodes them. Malformed/unsupported forms do not prepare or write. |
| Literal preservation | Retain surviving token spelling, duplicates, order, group prefixes and trailing LF. Equivalent decoded keys with changed raw bytes invalidate stale preparation. |
| Empty values | Preserve NULL versus empty string. A nonempty list partition with no surviving tokens becomes NULL. Reject whitespace-only input, blank groups and trailing commas. |
| COUNT | Count nominal occurrences including excluded seed/earlier dates. Prefix COUNT is the boundary ordinal; tail COUNT is original minus ordinal. Include final-occurrence COUNT1. |
| UNTIL/unbounded | Retain the tail rule literally. Prefix UNTIL is the previous nominal occurrence with exact UTC-second or DATE identity. |
| Partition | Before-boundary values remain on prefix; boundary/later values remain on tail. Preserve off-pattern exclusions without requiring recurrence ordinals for them. |
| Selection | Retain original selected key and native bounds. Changed selection, unavailable native occurrence or incomplete coverage refuses. |
| Ordinal zero | Update existing master only, retain exact RRULE/EXDATE, allocate no tail UID and clone no children. |
| Native material | Carry exact prefix/tail EXDATE through assertions, insert/update and readback. Preserve unrelated raw metadata including NULL end zone, children and exact first tail bounds. |
| Durability | Frozen bytes survive reopen and READ-only recovery; no current planner/zone resolution on recovery. Exact ACK identities, membership, budgets, rollback and uncertainty/no replay checks remain required. |

Current integration gaps include hardcoded NULL tail EXDATE in writer assertions/readback and frozen
material that admits only an exception-free rule without exclusions. Replace those assumptions only
inside the newly versioned eligible path. Legacy preparation/retry/recovery must not be silently
rebased to a new partition or regenerated rule.

## Evidence still required

A source-only independent review identified this matrix on 19 November 2026. No new adapter, tests,
native run or account propagation was performed for this packet. Synthetic acceptance must be
followed by controlled native expansion/cache checks and dedicated personal/Workspace round-trips
before production clone admission can change.
