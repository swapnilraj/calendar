# Frozen-command retirement implementation packet

Status: local journal fencing and the ordinary editor feature are implemented with focused tests.
Host route integration has a separate verification gate; series and meeting retirement UI remain
separate work before safe replacement can be claimed for those flows.

## Problem and boundary

Deleting a draft cannot stop another controller that already read its frozen request from later
preparing or dispatching it. A missing journal is also insufficient: the old worker may not have
reached preparation yet. Confirm a permanent fence in the existing global journal before deleting
or replacing any draft containing a frozen backend command, even when `submitted` is false.

This retires a local command; it does not roll back a provider transaction. A recorded local commit
remains a commit when subsequent remote changes make current-row recovery uncertain.

## Shared contract

- `RetirableOperation` mirrors the existing backend arguments for Create, Update, Delete, Series,
  MeetingCreate and Meeting. Include the original operation ID, selected ref/token/command or draft
  and guests as appropriate. No UI `Frozen*` types, raw JSON or caller-supplied signature/kind.
- `OperationRetirement.retire(request)` writes a fence; `inspect(request)` is strictly local read-only.
  Each backend derives the same kind/signature through its existing command encoder. Do not duplicate
  the encoder or refresh/rebase a token, target identity, scope, default or field value.
- Results: exact-request `Retired`, inspect-only `NotRetired`, `Blocked` (possible dispatch or recorded
  local commit), `Conflict`, `Unsupported`, or `Unavailable`. Unknown acknowledgement is not retirement
  proof. Cancellation propagates and may also require subsequent inspection.
- Add a terminal `MutationResult.Retired(operationId)` for late workers; series/meeting outcomes wrap
  it as a common outcome. UI must stop the old controller and never interpret it as SafeToRetry.

## Journal transaction and allowed transitions

| Current state, matching kind/signature | Retirement result |
| --- | --- |
| Missing | Atomically insert `ABANDONED` with exact kind/signature |
| `PREPARED` or `FAILED` | CAS to `ABANDONED`, preserve opaque receipt bytes |
| `ABANDONED` | Idempotent durable readback confirmation |
| `ATTEMPTED` | Block; dispatch may have occurred |
| `COMMITTED` or `VERIFIED` | Block; a local commit is recorded |
| Different kind/signature | Conflict; no changes |
| Unknown phase/header or storage outcome | Unavailable; no assumptions |

`ABANDONED` is permanent: never prune, prepare, attempt, mark failed/committed, delete or reuse it.
`prepare` remains insert-if-absent; `attempt` already CASes only PREPARED/FAILED. Every `mark` must
also guard its expected prior phase, so delayed failure cleanup cannot resurrect a retired command.
ATTEMPTED can become FAILED only after definite no-dispatch/atomic rollback, or COMMITTED after a
provider acknowledgement. Valid readback permits COMMITTED to VERIFIED; terminal states stay terminal.
No schema migration is needed for the existing string phase column. A owns SQLite implementation.

## Controller sequence

1. Acquire single-flight action/storage ownership, stop autosave, and retain the complete exact old
   DraftRecord. Decode its original typed request once; unsupported legacy data stays review-only.
2. Obtain matching durable retirement proof. After an unknown acknowledgement, use typed local
   inspection. Never infer retirement from cancellation, joining a coroutine, missing provider rows,
   SafeToRetry, or ordinary recovery returning Uncertain.
3. As soon as retirement is confirmed, disable all old save/retry paths, even if draft cleanup fails.
   CAS-delete the exact old DraftRecord. Changed preserves its replacement. Unknown deletion requires
   readback: absent can complete; identical old data permits only conditional cleanup; unreadable or
   different data stops the operation.
4. Only after confirmed cleanup, permanently retire the old controller and prepare a fresh source
   with new draft/operation IDs and freshly issued authority. Unsubmitted drafts without any frozen
   request need only exact atomic deletion under the existing persist-before-dispatch invariant.

Acknowledging/archiving a committed draft after remote edits/deletion is a separate workflow. It
must explain the recorded commit and never authorize duplicate creation or ABANDONED from Uncertain.

## Required evidence

Use separate real SQLite helpers/writers, not only a shared coroutine mutex. Cover retirement before
prepare, retirement racing attempt, attempt winning first, stale mark attempts, kind/signature
conflicts, reopen, unknown commit acknowledgement, source deletion/permission revocation, and crash
between journal fence and exact draft deletion. Preserve replacements and immutable frozen values.
Verify all supported command families use their existing encoders, including Keep/null semantics,
calendar namespace and recurrence scope. Old launches cannot recreate missing drafts or execute an
ABANDONED ID. COMMITTED/VERIFIED remain blocked despite subsequent source mismatch.

## Contract checkpoint

The shared port is now `OperationRetirement.retire/inspect(RetirableOperation)` with exact typed
backend arguments. Only `Retired(operationId)` confirms a permanent fence; inspect-only `NotRetired`
is not no-dispatch or retry proof. Blocked results distinguish possible dispatch from a recorded
commit. A matching late `MutationResult.Retired` stops an ordinary editor without rewriting or
deleting its raw record; mismatched IDs remain uncertain. Backend/store fencing now includes exact
request comparison, permanent ABANDONED handling before provider access, guarded journal transitions,
and independent SQLite-helper race/reopen tests. Owned one-off meeting creation now supplies its
actual canonical command encoder to the same fence. Series/meeting retirement workflows remain pending.

The ordinary editor uses version 5 records (`ordinary-editor`, schema 1) with UUID generation and
revision. New preparation, raw autosave, freezing, explicit upgrade and cleanup use exact-record
`AtomicDraftStore.compareAndSet`; an unavailable acknowledgment is accepted only after readback
of the exact candidate revision (or confirmed absence for cleanup). A missing restored record is
never seeded again. Legacy versions 1 and 4 remain retained and read-only until explicit upgrade;
legacy edits without an original full calendar identity support discard-only, never automatic
source adoption. Frozen encoders 1 and 2 and their exact provider arguments remain unchanged.

Explicit retirement maps the retained frozen command directly to `RetirableOperation`. Only a
matching `Retired(operationId)` allows exact draft deletion; an unavailable retirement response
requires inspection. Possible dispatch and recorded commit retain distinct blocked states.
Replacement also requires exact cleanup, a newly prepared draft ID/generation/operation ID and a
host replacement callback. New creation drafts require fresh calendar selection. Edit replacement
checks the original persisted namespace before any deletion; recovery and discard-only need no
provider read or write permission. Committed local save acknowledgment cleans up its exact draft
without retirement or a second save.

Feature tests cover stale CAS revisions, uncertain acknowledgments, permission preflight,
immutable frozen recovery, blocked retirement, cleanup failure, namespace continuity, native
confirmation and replacement callbacks, plus separate real SQLite connections for retirement and
exact draft deletion. Global route generation and host composition have their own integration
checks; the broader series and meeting retirement user flows remain separate packets.
