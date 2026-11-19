# Following EXDATE receipt 4 — approved implementation contract

Status: implemented in the local adapter;195 publication and105 aggregate provider checks pass.
Complete aggregate/native/account acceptance remains pending.
This is the exception-free metadata bridge described in the aggregate FOLLOWING_EXDATE_ADAPTER.md.
Moved/cancelled exception graph transfer remains separate. No new provider profile is admitted.

## Routing and compatibility

Use family version 4 / `SERIES_FOLLOWING_EDIT_V4`, with existing
`SERIES_V1_FOLLOWING_EDIT` operation kind and unchanged selected/token/command fingerprint.
Only NEW metadata-only candidates use 4: TIME/ZONE/start/duration/RRULE Keep, selectedPatch empty,
complete source, no exceptions. Include NULL/empty EXDATE so those exact states are frozen too.
Nonmetadata new operations with empty exclusions retain 3. Existing 2/3 operations in every phase
retain issued protocol/bytes: no retry/recovery upgrades, relaxed parsers, current replanning or
invented acknowledgement proof. Old end-zone, namespace, retirement, draft and uncertain-result
rules remain in force. SPLIT still needs issued stable prefix UID/sync and the existing verified
clone admission; WHOLE remains in place. Default Unverified admits no new clone-dependent scope.

## Frozen authority

Retain all 3 fields/binding rules. Plan version 2 adds strict `exclusions`:

- `version`: 1;
- `sourceRaw`, `prefixRaw`, `tailRaw`: null or exact string;
- `boundary`: tagged date or instant; `previousNominal`: null or the same tagged key kind;
- `groups`: ordered objects containing `prefix` (empty string or `UTC;`) and ordered `tokens`, each
  with exact `raw` literal and tagged date/instant `key`;
- `trailingLf`: boolean.

Freeze native `prefixWrite.exdate`, `prefix.exdate` and `tail.exdate` explicitly. WHOLE retains exact
source RRULE/EXDATE, tailRaw null, no tail UID. SPLIT freezes prefix context/RRULE/EXDATE and literal
partitions. Digest binds the full witness, native plan and indexed layout under Feature4/planVersion2.
Strict decoding verifies schema/types, token grammar/key equality, order/duplicates/group prefixes/LF,
partition/raw NULL-vs-empty, literal rule transformation and COUNT arithmetic from frozen ordinal and
previous nominal key. New4 also binds first bounds to the issued selected native instance, retains
raw duration/allDay/start-zone/end-zone, and keeps every WHOLE context field unchanged. It must not
call ZoneId.of, current recurrence planner/compiler or DateCodec.
Nominal membership is established only during fresh initial RecurrenceFollowingMetadata.resolve;
this private issued witness is not caller execution authority. Retry/recovery consumes the winning
frozen 4 without reauthoring. Complete source/ACL/child/namespace assertions still fence dispatch.

## Admission and native formatting

Fresh reader must independently issue exDatesDecoded=true, COMPLETE coverage, validated selected
native bounds, no exceptions, RDATE/EXRULE null-or-empty. Invoke the existing metadata helper with
unchanged budgets; never manufacture snapshot keys or flip decoded flags. Admit only the intersection
of reader and native helper: timed uppercase UTC-Z; all-day DATE or UTC-midnight-Z; optional UTC;
groups only where the reader independently supports that exact form. UTC;DATE remains refused.
Refuse CR, whitespace, blank groups, trailing commas, property prefixes, TZID, floating/offset tokens,
non-midnight all-day values and undecoded forms.

Preserve raw duplicate/order/group/LF representation. Empty surviving partition of a nonempty source
becomes NULL; source NULL vs empty stays exact. Off-pattern EXDATE keys partition by key, never consume
nominal COUNT. Excluded selection refuses. COUNT prefix=ordinal, tail=old-count-minus-ordinal;
UNTIL/unbounded tail remains literal, prefix uses previous nominal key; final COUNT1 must work.
Ordinal zero preserves source exactly, with no tail/UID/clone. Frozen first bounds remain actual
validated selected template bounds; do not infer them by subtracting exclusions.

## Writes, recovery and verification

Reuse complete indexed ACK validation and durable master acknowledgement before child interpretation;
known identity binding precedes private payload. New 4 prefix/tail readback uses its frozen EXDATE
fields, removing hardcoded NULL only for 4. Legacy 2/3 behavior stays unchanged. Incomplete/ambiguous
ACK, identity drift, conflict, rollback or uncertain result never causes blind replay.

Allowed production paths: CalendarFollowingSeriesWriter.kt, FollowingExecutionReceipt.kt,
FollowingAcknowledgments.kt, new FollowingExdateAuthority.kt. Reader/core/store/schema/owner wiring,
provider policy, meeting and UI files remain unchanged. Bounded SQL fixture EXDATE filtering may be
added without changing nominal COUNT emission. Focused acceptance:36 new authority/execution tests
plus existing69 Following and90 scoped/retirement regressions (195 total). First194/1 fixture failure
and corrected195 pass are retained;105 aggregate provider checks pass. Three UI journeys are queued
separately after correcting a nonmatching test filter.
Native Google/Workspace propagation and exception graph transfer remain unverified and unimplemented
by this contract. See audit/evidence/following-exdate4-2026-11-19.json for the bounded execution evidence.
