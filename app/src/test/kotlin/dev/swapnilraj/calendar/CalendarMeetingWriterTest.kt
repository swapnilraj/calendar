package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarMeetingWriterTest {
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val selected = EventRef(CalendarId(2), 10, start, start.plusSeconds(3600))
    private class Provider(start: Instant) : ContentProvider() {
        val db = SQLiteDatabase.create(null)
        var batchCount = 0
        var lostAck = false
        var failAfterCommit = false
        var committed = false
        var failUpdate = false
        var beforeBatch: (() -> Unit)? = null
        val queries = mutableListOf<String>()
        init {
            table("calendars", mapOf("_id" to 2L, "account_name" to "actor@example.invalid", "account_type" to "com.google", "ownerAccount" to "actor@example.invalid",
                "_sync_id" to "calendar-A", "calendar_access_level" to 300L, "visible" to 1L, "sync_events" to 1L,
                "calendar_displayName" to "Synthetic invitation calendar", "calendar_color" to 123L, "calendar_timezone" to "UTC"))
            val event = mapOf("_id" to 10L, "calendar_id" to 2L, "dtstart" to start.toEpochMilli(), "dtend" to start.plusSeconds(3600).toEpochMilli(),
                "allDay" to 0L, "eventTimezone" to "UTC", "eventEndTimezone" to "UTC", "title" to "Synthetic work invitation", "eventLocation" to "Fixture room",
                "description" to "Fixture notes", "displayColor" to 456L, "selfAttendeeStatus" to 3L, "eventStatus" to 1L, "deleted" to 0L,
                "organizer" to "organizer@example.invalid", "isOrganizer" to 0L, "hasAttendeeData" to 1L, "guestsCanSeeGuests" to 1L,
                "guestsCanInviteOthers" to 0L, "guestsCanModify" to 0L, "rrule" to null, "rdate" to null, "exrule" to null, "exdate" to null,
                "original_id" to null, "original_sync_id" to null, "originalInstanceTime" to null, "customAppPackage" to null, "customAppUri" to null,
                "availability" to 1L, "accessLevel" to 2L, "opaque_event" to "keep event metadata")
            table("events", event)
            table("attendees", mapOf("_id" to 20L, "event_id" to 10L, "attendeeEmail" to "actor@example.invalid", "attendeeName" to "Actor",
                "attendeeStatus" to 3L, "attendeeRelationship" to 1L, "attendeeType" to 1L, "attendeeIdentity" to "actor-opaque", "attendeeIdNamespace" to "fixture",
                "opaque_child" to "keep self metadata"))
            db.insertOrThrow("attendees", null, ContentValues().apply {
                put("_id", 21); put("event_id", 10); put("attendeeEmail", "other@example.invalid"); put("attendeeName", "Other")
                put("attendeeStatus", 1); put("attendeeRelationship", 1); put("attendeeType", 2); put("opaque_child", "keep guest metadata")
            })
            table("reminders", mapOf("_id" to 30L, "event_id" to 10L, "minutes" to -1L, "method" to 0L))
        }
        private fun table(name: String, row: Map<String, Any?>) {
            val numbers = setOf("original_id", "originalInstanceTime")
            db.execSQL("CREATE TABLE $name (" + row.entries.joinToString(",") { (key, value) -> "\"$key\" " + if (value is Number || key in numbers) "INTEGER" else "TEXT" } + ")")
            db.insertOrThrow(name, null, ContentValues().apply { row.forEach { (key, value) -> when (value) {
                null -> putNull(key); is Number -> put(key, value.toLong()); else -> put(key, value.toString())
            } } })
        }
        fun set(table: String, key: String, value: String?, id: Long = if (table == "calendars") 2 else if (table == "attendees") 20 else 10) {
            db.update(table, ContentValues().apply { if (value == null) putNull(key) else put(key, value) }, "_id = ?", arrayOf(id.toString()))
        }
        fun value(table: String, key: String, id: Long = if (table == "attendees") 20 else 10): String? =
            db.query(table, arrayOf(key), "_id = ?", arrayOf(id.toString()), null, null, null).use { check(it.moveToFirst()); it.getString(0) }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (failAfterCommit && committed) error("Synthetic postcommit unavailability")
            val path = uri.pathSegments.first()
            queries += path
            val where = mutableListOf<String>()
            val bind = mutableListOf<String>()
            if (path != "instances") uri.lastPathSegment?.toLongOrNull()?.let { where += "_id = ?"; bind += it.toString() }
            if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
            if (path == "instances") {
                val fields = projection!!.map { when (it) { "event_id" -> "_id AS event_id"; "begin" -> "dtstart AS begin"; "end" -> "dtend AS end"; else -> it } }.toTypedArray()
                return db.query("events", fields, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort?.replace("begin", "dtstart")?.replace("event_id", "_id"))
            }
            return db.query(path, projection, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("RSVP must not insert")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("RSVP must not delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            if (failUpdate) throw OperationApplicationException("Synthetic batch rollback")
            check(uri.pathSegments.first() == "attendees") { "Self response must use Attendees.STATUS" }
            check(values!!.keySet() == setOf(CalendarContract.Attendees.ATTENDEE_STATUS))
            val id = ContentUris.parseId(uri)
            val where = "_id = ?" + (selection?.let { " AND ($it)" } ?: "")
            val count = db.update("attendees", values, where, arrayOf(id.toString(), *args.orEmpty()))
            if (count == 1 && value("attendees", "attendeeEmail", id) == "actor@example.invalid")
                db.update("events", ContentValues().apply { put("selfAttendeeStatus", values.getAsInteger("attendeeStatus")) }, "_id = 10", null)
            return count
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batchCount++
            beforeBatch?.also { beforeBatch = null }?.invoke()
            db.beginTransaction()
            val result = try { super.applyBatch(operations).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            committed = true
            if (lostAck) error("Synthetic lost acknowledgement")
            return result
        }
    }
    private data class Fixture(val provider: Provider, val writer: CalendarMeetingWriter, val reader: CalendarReader,
        val store: CalendarLocalStore, val permissions: MutableSet<String>)
    private suspend fun fixture(budgets: ProviderReadBudgets = ProviderReadBudgets(), block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val p = Provider(start)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val name = "meeting-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name, readBudgets = budgets)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarMeetingWriter(context.contentResolver, reader, store, permissions::contains, readBudgets = budgets)
        try { block(Fixture(p, writer, reader, store, permissions)) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); p.db.close(); context.deleteDatabase(name) }
    }
    private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
    private fun respond(value: MeetingResponse = MeetingResponse.YES) = MeetingCommand.Respond(RecurrenceScope.THIS, value)
    private suspend fun Fixture.editable(): EditableMeeting = (writer.loadForEdit(selected) as MeetingReadResult.Ready).value

    @Test fun readOnlyEligibilityAndAllThreeResponsesPreserveGuestsMetadataRemindersAndEventStatus() = runBlocking {
        fixture { f ->
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val editable = f.editable()
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.THIS).respond)
            assertFalse(editable.scopes.containsKey(RecurrenceScope.ALL))
            assertEquals(common(MutationResult.PermissionRequired), f.writer.mutate("read-only", selected, editable.token, respond()))
            assertEquals(0, f.provider.batchCount)
            f.permissions += Manifest.permission.WRITE_CALENDAR
            listOf(MeetingResponse.YES to "1", MeetingResponse.MAYBE to "4", MeetingResponse.NO to "2").forEachIndexed { index, (value, status) ->
                val current = f.editable()
                assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("response-$index", selected, current.token, respond(value)))
                assertEquals(status, f.provider.value("attendees", "attendeeStatus"))
                assertEquals(status, f.provider.value("events", "selfAttendeeStatus"))
                assertEquals("1", f.provider.value("events", "eventStatus"))
                assertEquals("1", f.provider.value("attendees", "attendeeStatus", 21))
                assertEquals("keep self metadata", f.provider.value("attendees", "opaque_child"))
                assertEquals("keep guest metadata", f.provider.value("attendees", "opaque_child", 21))
                assertEquals("keep event metadata", f.provider.value("events", "opaque_event"))
                assertEquals("-1", f.provider.value("reminders", "minutes", 30))
            }
        }
    }

    @Test fun unsupportedCommandsAndScopesNeverReadOrJournalOrFallBackToOrdinaryGuestWrites() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            f.provider.queries.clear()
            val commands = listOf<MeetingCommand>(MeetingCommand.Respond(RecurrenceScope.ALL, MeetingResponse.YES), MeetingCommand.CancelOwned(RecurrenceScope.THIS),
                MeetingCommand.EditOwned(RecurrenceScope.THIS, EventPatch(title = FieldChange.Set("no"))))
            commands.forEachIndexed { index, command ->
                assertTrue(f.writer.mutate("unsupported-$index", selected, editable.token, command) is MeetingMutationResult.Unsupported)
                assertNull(f.store.operation("unsupported-$index"))
            }
            val draft = EventDraft(CalendarId(2), EventTime.Timed(start, start.plusSeconds(3600)), java.time.ZoneOffset.UTC, "no")
            assertTrue(f.writer.create("create", editable.token, draft, emptyList()) is MeetingMutationResult.Unsupported)
            assertTrue(f.writer.loadForCreate(editable.details.calendar!!.identity) is MeetingReadResult.Denied)
            assertTrue(f.provider.queries.isEmpty())
            assertEquals(0, f.provider.batchCount)
        }
    }

    @Test fun unknownAclOrNamespaceAndHiddenGuestsFailBeforeRestrictedQueries() = runBlocking {
        fixture { f ->
            f.provider.set("calendars", "calendar_access_level", "200")
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.Denied)
            assertEquals(listOf("calendars"), f.provider.queries)
        }
        fixture { f ->
            f.provider.set("calendars", "ownerAccount", "alias@example.invalid")
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.Denied)
            assertEquals(listOf("calendars"), f.provider.queries)
        }
        listOf<String?>("0", null).forEach { visibility -> fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", visibility)
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview)
            assertFalse(f.provider.queries.contains("attendees"))
            assertEquals(0, f.provider.batchCount)
        } }
    }

    @Test fun duplicateSelfAliasAndUnknownRowPolicyCannotIssueAnEditToken() = runBlocking {
        fixture { f ->
            f.provider.set("attendees", "attendeeEmail", "ACTOR@example.invalid", 21)
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview)
            f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        listOf("attendeeRelationship" to "2", "attendeeType" to "99", "attendeeStatus" to "99", "attendeeIdNamespace" to null).forEach { (column, value) -> fixture { f ->
            f.provider.set("attendees", column, value)
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview)
            assertEquals(0, f.provider.batchCount)
        } }
    }

    @Test fun changedEventAndBatchRaceRejectWithoutOverwritingAnotherResponseOrGuest() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            f.provider.set("events", "description", "External edit")
            assertEquals(common(MutationResult.Conflict(null)), f.writer.mutate("stale", selected, token, respond()))
            assertNull(f.store.operation("stale"))
            val fresh = f.editable().token
            f.provider.beforeBatch = { f.provider.set("attendees", "attendeeType", "1", 21) }
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("race", selected, fresh, respond()))
            assertEquals("3", f.provider.value("attendees", "attendeeStatus"))
            assertEquals("FAILED", f.store.operation("race")!!.phase)
            assertEquals(common(MutationResult.SafeToRetry("race")), f.writer.recover("race"))
            assertEquals(common(MutationResult.Conflict(null)), f.writer.mutate("race", selected, fresh, respond()))
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun knownCommitRecoveryIsReadOnlyAndRejectsReusedCalendarBeforePrivateReads() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            f.provider.failAfterCommit = true
            assertTrue(f.writer.mutate("committed", selected, token, respond()) is MeetingMutationResult.Common)
            assertEquals("COMMITTED", f.store.operation("committed")!!.phase)
            f.provider.failAfterCommit = false
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.recover("committed"))
            f.provider.set("calendars", "_sync_id", "calendar-B")
            f.provider.queries.clear()
            assertTrue((f.writer.recover("committed") as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals(listOf("calendars"), f.provider.queries)
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun lostAcknowledgementNeverReplaysEvenWhenResponseFieldsMatchAndStoreReopens() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            f.provider.lostAck = true
            assertTrue((f.writer.mutate("lost", selected, token, respond()) as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals("1", f.provider.value("attendees", "attendeeStatus"))
            assertEquals("ATTEMPTED", f.store.operation("lost")!!.phase)
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), f.store.databaseName)
            try {
                val other = CalendarMeetingWriter(RuntimeEnvironment.getApplication().contentResolver, f.reader, reopened, f.permissions::contains)
                assertEquals(common(MutationResult.Uncertain("lost", null)), other.recover("lost"))
                assertEquals(common(MutationResult.Uncertain("lost", null)), other.mutate("lost", selected, token, respond()))
                assertEquals(1, f.provider.batchCount)
            } finally { reopened.close() }
        }
    }

    @Test fun preparedProofRetriesOnlyExactCommandAndGlobalJournalKindsCannotCollide() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            f.store.writableDatabase.execSQL("CREATE TRIGGER interrupt_rsvp BEFORE UPDATE OF phase ON mutation_journal WHEN NEW.phase = 'ATTEMPTED' BEGIN SELECT RAISE(FAIL, 'Synthetic interruption'); END")
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("prepared", selected, token, respond()))
            assertEquals(common(MutationResult.SafeToRetry("prepared")), f.writer.recover("prepared"))
            f.store.writableDatabase.execSQL("DROP TRIGGER interrupt_rsvp")
            assertEquals(common(MutationResult.Conflict(null)), f.writer.mutate("prepared", selected, token, respond(MeetingResponse.NO)))
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("prepared", selected, token, respond()))
            f.store.prepare("ordinary", "other-signature", "UPDATE")
            assertEquals(common(MutationResult.Conflict(null)), f.writer.mutate("ordinary", selected, token, respond()))
            assertEquals(common(MutationResult.Uncertain("ordinary", null)), f.writer.recover("ordinary"))
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun definiteBatchRollbackCanRetryTheSameFrozenResponseWithoutChangingOtherRows() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            f.provider.failUpdate = true
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("rollback", selected, token, respond(MeetingResponse.NO)))
            assertEquals("3", f.provider.value("attendees", "attendeeStatus"))
            assertEquals("3", f.provider.value("events", "selfAttendeeStatus"))
            assertEquals(common(MutationResult.SafeToRetry("rollback")), f.writer.recover("rollback"))
            f.provider.failUpdate = false
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("rollback", selected, token, respond(MeetingResponse.NO)))
            assertEquals("1", f.provider.value("events", "eventStatus"))
            assertEquals(2, f.provider.batchCount)
        }
    }

    @Test fun corruptedOrUnboundReceiptNeverAcknowledgesOrOffersRetry() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            assertTrue(f.writer.mutate("saved", selected, token, respond()) is MeetingMutationResult.LocalSaved)
            val operation = f.store.operation("saved")!!
            val receipt = org.json.JSONObject(operation.resultRef!!).put("self", "21")
            f.store.mark("saved", "COMMITTED", receipt.toString())
            f.provider.queries.clear()
            assertEquals(common(MutationResult.Uncertain("saved", null)), f.writer.recover("saved"))
            f.store.prepare("legacy", "missing-proof", operation.kind)
            assertEquals(common(MutationResult.Uncertain("legacy", null)), f.writer.recover("legacy"))
            assertTrue(f.provider.queries.isEmpty())
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun permissionsAndReadBudgetsFailWithoutSnapshotsJournalsOrBatches() = runBlocking {
        fixture { f ->
            f.permissions.clear()
            assertEquals(MeetingReadResult.PermissionRequired, f.writer.loadForEdit(selected))
            assertEquals(common(MutationResult.PermissionRequired), f.writer.recover("missing"))
            assertEquals(common(MutationResult.PermissionRequired), f.writer.mutate("no-read", selected, EditToken("none"), respond()))
            assertTrue(f.provider.queries.isEmpty())
        }
        fixture { f ->
            f.provider.set("events", "description", "x".repeat(70_000))
            assertEquals(MeetingReadResult.LimitExceeded, f.writer.loadForEdit(selected))
            assertNull(f.store.operation("oversized"))
            f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            assertEquals(0, f.provider.batchCount)
        }
    }
}
