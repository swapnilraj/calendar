# Generated-occurrence THIS RSVP

Status: bounded next-packet design, not implemented or Google-device verified. Existing recurring
RSVP supports materialized THIS and complete ALL self-attendee updates. Generated THIS is rejected.
This packet adds response-exception creation through the public provider API; it does not add
FOLLOWING response, organizer changes, guest editing, recurrence changes, or invitation delivery.

## Admission and privacy

Keep the existing `MeetingCommand.Respond(THIS)` caller contract and exact frozen-command identity.
Issue backend THIS capability for generated occurrences only after fresh READ/WRITE permission,
calendar namespace and ACL >= RESPOND checks, exact owner/account identity, and invited-master
proof. The actor is not the organizer; organizer identity and invitation status must be consistent.
Require one exact owner attendee with supported identity fields and status matching the event's
self status. `CAN_ORGANIZER_RESPOND` does not grant invited-guest authority, and guest-policy flags
do not substitute for ownership or calendar ACL.

The selected reference must be an actual native generated Instance of the same master, with exact
actual start/end and nominal ORIGINAL key. Reject an existing linked active or deleted exception
at that key. Read and assert both local ORIGINAL_ID and calendar-scoped ORIGINAL_SYNC_ID membership.
No guessed occurrence, local-time rounding, moved-date substitution, or synthetic EventRef is proof.

The native exception path requires nonempty RRULE; RDATE-only sources remain review-only. This is
a response operation, so it does not rebuild COUNT, UNTIL, EXDATE, or other recurrence text. Keep
the master and unrelated exceptions unchanged. Fresh Instances plus membership proof establish
the selected occurrence; unsupported recurrence transformations are not needed for this operation.

The first portable profile requires a complete, legitimately visible and audited child snapshot.
Do not expand a self-only lookup into a full guest-list read when visibility is hidden or uncertain.
Hidden guests, incomplete coverage, unknown nonnull OEM child fields, unclassified metadata, or
oversized source/batch data produce review before preparation. Existing self-only materialized
THIS/ALL paths remain available independently. Do not claim that inspecting AOSP's clone routine
proves hidden-guest preservation on every provider. A later provider-specific profile needs its
own evidence and capability gate.

## Public exception insertion

Pinned Android 10 and 16 providers support SELF_ATTENDEE_STATUS in the exception-insert whitelist.
For a single exception they clone the master, clear RRULE/RDATE/EXRULE/EXDATE, replace DURATION with
DTEND, copy Attendees/Reminders/ExtendedProperties internally, and update the cloned attendee whose
email exactly equals calendar OWNER_ACCOUNT. The source organizer and public UID are inherited.
The new exception has no copied `_SYNC_ID` or SYNC_DATA1..10; ORIGINAL_ID and ORIGINAL_SYNC_ID refer
to the source master. Do not supply sync-adapter query parameters or sync-only values.

Use one no-yield public `applyBatch`:

1. Assert the fresh calendar/master/linked membership and selected-key absence. Assert the complete
   admitted source children and exact unique owner row. Revalidate the original native occurrence
   immediately before dispatch under the existing read budgets; the provider itself has a TODO
   rather than validating that ORIGINAL_INSTANCE_TIME denotes a generated occurrence.
2. Insert `Events.CONTENT_EXCEPTION_URI/<masterId>` with exact ORIGINAL_INSTANCE_TIME, requested
   SELF_ATTENDEE_STATUS, authoritative actual DTSTART, validated all-day/timezone context, and a
   provisional positive DURATION derived from the actual selected interval. Use the A14 bounded
   whole-second PTseconds / all-day PnD policy; unsupported precision, zero/negative intervals,
   parser overflow, or inconsistent all-day UTC dates require review. Explicitly retain source
   event status, including null. Do not send DTEND or RRULE on this insert.
3. Apply A14's ordinary Events collection corrective update with a selection back reference to the
   inserted ID, exact calendar/master/original/start/all-day guards, and expected count one. Supply
   actual DTEND plus full unchanged DTSTART/timezone/all-day/original-time context. A DTEND-only
   update can rebuild incomplete raw-time helper data; do not use it. This correction preserves the
   authoritative actual interval across P1D/PT24H/DST differences without changing master duration.
4. Assert the exact resulting event, original links, inherited known UID, unchanged organizer and
   guest policies, cleared recurrence/duration/sync fields, and actual interval. Assert cloned child
   multisets using new-parent back references; only the unique owner attendee status changes.
   Assert master and unrelated exceptions unchanged, and linked membership increased by exactly
   the one expected exception. A failed assertion rolls back the entire batch.

Use collection URIs for updates with explicit selections. Never update ordinary Events
SELF_ATTENDEE_STATUS (the provider rejects it), insert duplicate self rows, rewrite non-owner
attendee responses, or use event cancellation to express a decline. The native clone mirror
accepts one or two matching owner rows and throws for other counts; our source/post assertions
must require exactly one rather than accepting the native duplicate-two allowance.

Clone verification covers documented attendee name/email/status/relationship/type/identity/namespace,
reminder MINUTES/METHOD, and extended-property name/value. Preserve raw reminder defaults and methods.
ExtendedProperties are copied by the provider, not written by the app; ordinary apps cannot write
that table. Admit populated properties only when their full public clone state and semantics are
understood and verifiable; otherwise refuse. Never silently discard unknown metadata or repair a
clone by issuing privileged writes. Known provider-computed fields need an explicit comparison
policy rather than blanket exclusions. Check existing canonical/read limits and 256 KiB batch
parcel budget before any attempt.

## ACK validation and existing hardening finding

Both pinned implementations return `ContentUris.withAppendedId(inputUri, newId)`. With exception
input the ACK is `content://com.android.calendar/exception/<masterId>/<newId>`, not an Events URI.
Validate the expected content scheme/authority, exact path segment count and literal `exception`,
canonical expected master segment, positive distinct new exception ID, and absence of unexpected
query/fragment data. Do not accept another master, arbitrary authority, trailing extra segments,
or merely any URI whose final segment parses as a number. Back references identify the batch's
insert, not an arbitrary external row.

Current A14 `CalendarSeriesWriter` obtains the created ID with `ContentUris.parseId` alone. That
is a concrete ACK-shape hardening finding, not complete validation. Address it in a separately
owned production change when scheduled; this design packet does not modify the writer. Do not
reuse the normal recurring-create Events-URI validator unchanged for exception insertion.

## Durable result and source-free recovery

Keep typed operation ID/kind/signature authority and global journal retirement fencing. Introduce
an explicit generated-THIS preparation/receipt version, leaving existing command bytes and old
materialized-response receipts unchanged. The prepared payload must bind exact original command,
calendar namespace, source master identity and known UID, nominal original key, actual interval,
requested status, and expected resulting public event/child state. Recompute and verify this plan
from the frozen token before dispatch; unknown versions or mismatches cannot be adopted.

ATTEMPTED is durable before dispatch. After a valid ACK, persist known-ID COMMITTED immediately,
before any provider read. Receipt data must be sufficient for recovery even after the old edit
snapshot has been pruned. Existing `CalendarMeetingSeriesWriter.proof()` depends on
`store.edit(token)`; do not copy that dependency into the new receipt. Persist the bounded expected
state or equivalent independently verifiable proof in the committed receipt, with exact command
fingerprint and original context. Add a regression that deletes the old edit snapshot before
recovery. Private journal data are not source authority for a new write or new token.

Recovery is read-only: check current calendar namespace, known master UID/server-link context,
known new exception ID, exact original parent/key, known inherited UID, actual interval, unique
own attendee response, and admitted clone state. Obtain the actual native instance reference for
the exception before LocalSaved. A positive numeric ID or matching response alone is insufficient.
Namespace replacement, source/master removal, changed UID/links, remote edits, or delayed Instances
remain known-ID Uncertain; they do not authorize rebasing or replay. Distinguish a recorded local
commit from current verifiability. Only fully verified state becomes VERIFIED/LocalSaved; this
does not imply that an invitation response reached its organizer.

Missing/malformed/lost ACK leaves ATTEMPTED with unknown new ID. Never locate a candidate by
ORIGINAL key/title/time to infer success, replay insertion, or delete a candidate as compensation.
An unavailable COMMITTED journal acknowledgement likewise requires readback, not another batch.
Typed retirement can fence only proven never-dispatched missing/PREPARED/FAILED operations. It
cannot turn ATTEMPTED or recorded COMMITTED work into a safe retry. Preserve exact frozen recovery
through process death, and do not prune the receipt while its durable draft still references it.

## Tests and external acceptance

Next backend packet should include native-rule-enforcing fixtures for exact ACK parent/path;
one/two/zero owner rows; hidden-list refusal without full read; RDATE-only refusal; native cloned
guest/identity/reminder/property state; raw COUNT/UNTIL/EXDATE preservation; actual interval/DST
correction; atomic rollback and source races; source-free recovery after snapshot pruning;
COMMITTED-before-read; delayed expansion; lost ACK/no replay; namespace/UID replacement; and
retirement races. Existing materialized THIS and ALL response behavior must remain covered.

Physical two-account Google tests are a separate release gate: personal and Workspace invited
timed/all-day series; THIS Yes/Maybe/No visible to the organizer; unchanged master/other-instance
responses; guest-list preservation; COUNT/UNTIL and DST boundaries; process death and lost ACK
without duplicate exceptions; account/permission changes; and refresh from both clients. Hidden
guest cases require explicit additional evidence before relaxing the portable refusal. The Google
sync adapter is not this app's code and is not established by AOSP source. No server delivery,
notification suppression, or cross-client acceptance is currently claimed.

## Pinned evidence

* [Android 16 CalendarProvider2](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/77bed8f978646fc416a7d90f9e97485421961641/src/com/android/providers/calendar/CalendarProvider2.java):
  exception whitelist near 396; `handleInsertException` 1914–2260; owner mirror 2199 onward;
  ACK construction 2665; ordinary self-status update rejection near 4153; write restrictions 4601 onward.
* [Android 10 CalendarProvider2](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/f013f1c6428ebb1702543a184d6f6d356321d9ef/src/com/android/providers/calendar/CalendarProvider2.java):
  equivalent clone/mirror path near 2155–2183 and ACK construction 2620.
* [Android 16 CalendarDatabaseHelper](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/77bed8f978646fc416a7d90f9e97485421961641/src/com/android/providers/calendar/CalendarDatabaseHelper.java):
  clone columns 122–139 and `copyEventRelatedTables` near 3417.
* [Android 16 CalendarContract](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/provider/CalendarContract.java):
  public exception URI near 1805. This documents URI availability, not universal OEM child-clone fidelity.
