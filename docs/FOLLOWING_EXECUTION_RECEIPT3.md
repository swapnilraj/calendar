# Frozen exception-free Following execution

Status: the isolated implementation passes 69 focused host checks; aggregate integration verification
is pending. This protocol
changes no command bytes, core planner, schema or clone-profile registry. No production clone
profile is admitted. EXDATE/exception transfer remains outside this packet.

New operations use family version3 / `SERIES_FOLLOWING_EDIT_V3`, retaining kind
`SERIES_V1_FOLLOWING_EDIT` and the existing version1 selected/token/command fingerprint envelope.
The private receipt stores exact original source bytes/token, unchanged command bytes, source
selection, WHOLE/SPLIT ordinal, native prefix write/expected rows, child plans, independent tail
values/UID and frozen first-instance bounds. A version1 SHA-256 plan digest binds the operation,
command signature, complete source, native plan and indexed native result layout. It is separate
from the unchanged command fingerprint. Strict scalar/key/size/phase decoding never reconstructs
a snapshot, resolves a stored zone or invokes today's planner/validators.

The prefix RRULE update retains exact unchanged DTSTART, explicit NULL DTEND, raw DURATION,
timezone, all-day and original-instance context needed for native instance/raw-time refresh. It
never adds unrelated end-zone metadata. A split UID is generated only in initial preparation;
concurrent candidates adopt the durable winner's complete plan/UID. A SPLIT with neither issued
prefix UID nor sync identity requires review before journal or provider writes. WHOLE retains its
existing in-place eligibility and issued-null guards; this does not establish stable identity when
none was issued, and it never assigns a UID to an existing prefix.

Every assert/update/delete has an exact count ACK at its frozen index. Every insert has a canonical
positive URI ACK and an exact parent/table/child slot. Preserve raw bounded facts with the known
result in COMMITTED before child interpretation or private payload reads. Incomplete or malformed
facts never authorize retry or local success. Complete proof requires unique table-aware inserted
IDs, exact retained/inserted child membership and payload, native parent values, first-instance
bounds and final identity/namespace rechecks.

Known-commit READ-only recovery checks namespace/ACL and known prefix/tail identities before any
private payload, and remains independent of fresh clone admission. Reminder/attendee item reads
use native parent UID/calendar guards. Property reads on the unchanged WHOLE path retain the
existing in-place policy with issued parent checks before and after; no new joined property view
is assumed. Optional learned tail sync identity remains sticky and cannot replace generated UID. A sticky-sync
mismatch with the exact known ID/calendar/generated UID still bound retains known-result uncertainty,
but permits no payload read, success or proof rewrite. An observed namespace/UID loss suppresses the
known result; unavailable identity checks do not invent an observed replacement. Raw unissued empty
sync values are checked as observed and are never rewritten into a stable identity.
Dispatch still requires fresh clone admission for nonzero splits at all existing boundaries;
Unverified production policy continues to deny those writes.

Legacy version2 PREPARED/FAILED keeps its original bytes and retry protocol. No operation silently
upgrades to3. Retire/reissue explicitly to begin a new protocol. Legacy attempted/known commits
never gain invented complete ACK proof, replay or current-planner verification. New legacy tail
master ACKs must be canonical before a known ID is recorded. Historical known commits remain
uncertain even when their payload currently matches.

Preflight the whole source/native plan, 256KiB transaction, 64KiB ACK facts, complete verified
receipt and query/storage budgets before dispatch. Only definite provider transaction rollback
permits FAILED and an explicit exact retry. Permission/profile withdrawal, cancellation, retirement
and competing helpers retain the existing exact SQLite attempt/mark fences.

Required evidence includes strict codec/fingerprint controls; malformed/missing/reordered/duplicate
insert and count ACKs; source-free compiler-unavailable retry/recovery; privacy-cell guards on UID
or namespace replacement; concurrent prepare/retirement/cancellation; budget zero-batch controls;
and warmed prefix cache/native first-instance checks. Physical Google/Workspace sync, native
exception rebinding, installed-provider cache acceptance and production admission remain separate.
