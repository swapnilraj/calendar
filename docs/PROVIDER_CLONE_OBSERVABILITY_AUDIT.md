# Provider clone observability audit

Audit date: 2026-11-18. Scope: generated-occurrence RSVP (A28), independent FOLLOWING tail
creation (A26), and the older guest-free single-exception path (A14). This is a static source
and fixture audit. It does not establish Android-device or Google/Workspace compatibility.

## Finding

A null ContentResolver projection is not evidence that the cursor contains every physical
provider column. Current clone admission rejects unknown populated fields **returned by the
provider**. It cannot prove that unreturned physical columns are absent or empty.

Consequently the current broad claim “unknown nonnull OEM metadata always causes review” is
unproved. The implemented check is useful, but only for exposed metadata. Fake providers that
return all SQLite columns for a null projection test that narrower check; they do not prove
physical-schema completeness on an Android CalendarProvider.

## Evidence

Pinned provider: [Android 16 CalendarProvider2, 77bed8f978646fc416a7d90f9e97485421961641](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/77bed8f978646fc416a7d90f9e97485421961641/src/com/android/providers/calendar/CalendarProvider2.java).

* Events collection and item queries set `sEventsProjectionMap` (lines 1039 and 1046).
* Attendees collection and item queries set `sAttendeesProjectionMap` (1169 and 1174).
* The Attendees map copies an initial, fixed Events map, then adds documented attendee columns
  (5046–5091, 5224–5234). It is not a physical schema enumeration.
* Reminders **collection** queries use the raw Reminders table without a projection map (1178–1180).
  Reminder item queries use a joined projection map (1181–1186). The distinction matters: the
  admission collection query can see extra physical reminder columns on this pinned provider.
* Public Events queries use a view and a projection map. Independently, native exception creation
  reads `mDb.query(Tables.EVENTS, null, ...)` (1943–1946), and copies that physical row with
  `DatabaseUtils.cursorRowToContentValues` (2004–2006). These are different reads.

[Framework SQLiteQueryBuilder](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/database/sqlite/SQLiteQueryBuilder.java),
`computeProjection` (local audited source lines 1089–1115), returns the projection map's values
when the caller supplies no projection and a map exists. Only without a map does it leave a null
projection for SQL `*`. An explicit projection is also translated/validated through the map;
requesting a guessed OEM column is not a portable discovery mechanism.

Pinned [CalendarDatabaseHelper](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/77bed8f978646fc416a7d90f9e97485421961641/src/com/android/providers/calendar/CalendarDatabaseHelper.java)
defines fixed clone column lists (122–139) and uses them in `copyEventRelatedTables` (3417–3440):
reminders copy MINUTES/METHOD; attendees copy name/email/status/relationship/type/identity/namespace;
extended properties copy NAME/VALUE. Fresh child IDs and event IDs are generated. This helper
does not enumerate arbitrary physical child columns. Android 10's pinned corresponding provider
has the same single-exception architecture; that is implementation evidence, not an OEM promise.

## Actual app reads and supported guarantees

| Path | Source observation | What is actually protected |
| --- | --- | --- |
| Generated THIS | `CalendarMeetingSeriesReader` captures Events with null projection; generated audit captures visible Attendees and Reminders with null projection, filters only exact documented joined aliases, and rejects other returned nonnull child cells | All admitted, exposed event and SDK child payloads are asserted. Complete authorized guest rows and exact own identity are required. Returned unknown populated fields refuse cloning. No ExtendedProperties rows are admitted. |
| FOLLOWING tail | `CalendarSeriesReader` captures Events and child collections with null projection; `CalendarFollowingSeriesWriter.copyPublic/copyChildren` rejects returned populated fields outside its classification | The prefix remains in place. The new independent tail receives only admitted public event fields and admitted child fields, with a fresh UID. Exposed unclassified content is refused. |
| Guest-free new THIS | The same series reader supplies exposed event/child maps; native exception insertion clones its internal physical event row and fixed child subsets | Assertions protect the admitted exposed clone state. Returned unknown child cells cause review. Hidden physical child fields remain unobservable. |
| In-place RSVP/edit | Existing row IDs receive narrowly specified updates; no replacement/cloned child or tail is constructed | Unrepresented columns are not fetched or replayed by the app. These paths do not need a claim that all physical fields were enumerated. |

The app's own source read budgets, before/after checks, canonical tokens, transaction assertions
and receipts remain necessary. None converts projection-map coverage into physical-schema proof.

## Concrete risk by clone path

For generated THIS and guest-free new THIS, native code can copy an unexposed physical event
column that the app could neither classify nor assert. An OEM-specific identity field could
therefore be copied without an approved identity policy. Conversely, a hidden attendee payload
may be omitted by a fixed child clone list, even though every exposed SDK attendee assertion
passes. A successful local batch is insufficient to detect that loss.

For FOLLOWING, an unexposed event or attendee payload is omitted from the app-authored tail.
All public assertions can pass while that payload remains only on the prefix. Refusing nonempty
ExtendedProperties and exposed sync data does not close this separate visibility gap.

The pinned raw Reminders collection happens to expose physical reminder columns, so a synthetic
unknown reminder column is a faithful check of that pinned read behavior. It must not be generalized
to Events/Attendees or to another provider's projection policy.

## Recommended admission policy before activation

Do not issue or execute clone capabilities on an unverified provider implementation merely
because authority is `com.android.calendar`, SDK level is supported, public cursor columns match,
or the provider uses an Android/Google signing certificate. Those identifiers do not prove the
physical schema, native clone list, triggers, or adapter behavior.

Use an explicit, narrowly admitted provider profile. The profile evidence should bind the actual
provider package/binary (including split APKs as applicable), signing lineage/version and Android
build/framework behavior to a reviewed schema/clone policy and fixture/device compatibility run.
A profile must state which inaccessible physical fields are absent, intentionally retained,
regenerated or omitted, and why. Source-compatible AOSP 10/16 is a candidate profile supported
by source evidence; matching a package name alone does not establish it on a device. The current
work has not established a production Google provider profile.

Until an implementation is admitted, default clone access to denied/review for:

* Generated THIS RSVP that must insert a new exception.
* Guest-free THIS edit/cancel that must insert a new exception.
* FOLLOWING edits that construct an independent tail.

Enforce the same fresh gate before journal preparation/dispatch, including explicit retries;
hiding an enabled UI control alone is insufficient. Keep existing materialized THIS, ALL
in-place edits/RSVP/deletes, FOLLOWING delete, and ordinal-zero ALL-equivalent edits available
when their independent policies permit them. Do not unnecessarily gate authoring a wholly new
meeting/event on a promise to preserve an imported source row. Keep known-commit recovery
read-only, retirement available, and ATTEMPTED outcomes non-replayable even when profile admission
changes. Profile plumbing and user-visible limitations need a separate approved implementation.

This recommendation limits an actual preservation claim. It is not evidence that every OEM
has extra columns or that any specific real event has lost data.

## Required correction to test claims

Rename/document the existing unknown-field cases as **exposed unknown metadata rejection**.
The current A28 SQLite fixture returns `a.*` and raw Events rows, exposing its added physical
columns. That verifies rejection once a column reaches the cursor, not universal discovery.
The same limitation applies to FOLLOWING's fake raw-row fixtures.

A follow-up compatibility fixture should model a fixed public Events/Attendees projection map
while storing a hidden physical payload. It should demonstrate that cursor-only admission cannot
discover that payload, and that an unverified-profile gate refuses the clone with zero journal
and zero batch. A verified SDK-only profile fixture should separately prove admitted SDK clone
state. Existing in-place unknown-column preservation tests remain useful and need not be recast
as clone completeness tests. No such new gate or test is implemented by this audit document.

## Separate A14 exception acknowledgement gap

`CalendarSeriesWriter` currently parses only the last numeric URI segment and accepts zero.
It does not bind the ACK authority or `/exception/<master>` parent path. Its fake provider also
returns an Events URI, whereas the pinned native insert appends the new ID to its input exception
URI (Android 16 CalendarProvider2 line 2665; Android 10 line 2620).

The bounded fix is a private shared parser requiring `content`, exact CalendarContract authority,
no query/fragment, exact canonical `/exception/<expected master>/<positive new ID>` path, and a
new ID distinct from every existing source event. No Events-URI compatibility fallback is safe.
A malformed ACK must leave ATTEMPTED/Uncertain, with no field lookup, re-insert or SafeToRetry.
Correct the fixture ACK and test valid edits/cancellations plus hostile authority/parent/path,
zero/negative/duplicate/encoded IDs, query and fragment cases. This changes no frozen command or
receipt encoding and does not solve the separate provider-profile limitation above.
