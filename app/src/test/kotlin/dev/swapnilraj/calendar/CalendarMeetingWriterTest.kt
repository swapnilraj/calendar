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
        data class Read(val path: String, val rowId: Long?, val fields: List<String>?, val selection: String?)
        val reads = mutableListOf<Read>()
        var guardHiddenGuests = false
        var rejectOwnPredicate = false
        var afterQuery: ((String, String?) -> Unit)? = null
        data class Mutation(val uri: Uri, val selection: String?, val args: List<String>)
        val mutations = mutableListOf<Mutation>()
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
            // CalendarProvider's attendee projection map includes joined Events fields.
            // Changing the owner's RSVP changes this derived field for EVERY guest row.
            db.execSQL("CREATE VIEW attendee_view AS SELECT a.*, e.selfAttendeeStatus AS selfAttendeeStatus, e.eventStatus AS eventStatus FROM attendees a JOIN events e ON a.event_id = e._id")
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
            val itemId = uri.lastPathSegment?.toLongOrNull()
            val rowId = itemId ?: if (selection?.startsWith("_id = ?") == true) args?.firstOrNull()?.toLongOrNull() else null
            reads += Read(path, rowId, projection?.toList(), selection)
            if (path == "attendees" && rowId == null) {
                if (selection?.contains("TRIM(attendeeEmail") == true) {
                    if (rejectOwnPredicate) throw IllegalArgumentException("Synthetic unsupported private filter")
                } else check(!guardHiddenGuests) { "A hidden guest list must not be queried" }
            }
            val where = mutableListOf<String>()
            val bind = mutableListOf<String>()
            if (path != "instances") uri.lastPathSegment?.toLongOrNull()?.let { where += "_id = ?"; bind += it.toString() }
            if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
            if (path == "instances") {
                val fields = projection!!.map { when (it) { "event_id" -> "_id AS event_id"; "begin" -> "dtstart AS begin"; "end" -> "dtend AS end"; else -> it } }.toTypedArray()
                return db.query("events", fields, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort?.replace("begin", "dtstart")?.replace("event_id", "_id"))
            }
            val result = db.query(if (path == "attendees") "attendee_view" else path, projection, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
            // Materialize the fixture cursor before simulating a concurrent provider change.
            result.count
            afterQuery?.invoke(path, selection)
            return result
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("RSVP must not insert")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("RSVP must not delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            if (failUpdate) throw OperationApplicationException("Synthetic batch rollback")
            check(uri.pathSegments.first() == "attendees") { "Self response must use Attendees.STATUS" }
            check(values!!.keySet() == setOf(CalendarContract.Attendees.ATTENDEE_STATUS))
            val itemId = uri.lastPathSegment?.toLongOrNull()
            if (itemId != null && !selection.isNullOrEmpty()) throw IllegalArgumentException("Selection is not permitted for an attendee item mutation")
            mutations += Mutation(uri, selection, args?.toList().orEmpty())
            val id = itemId ?: args?.firstOrNull()?.toLongOrNull() ?: error("Exact attendee ID guard required")
            val where = if (itemId == null) selection else "_id = ?"
            val bind = if (itemId == null) args else arrayOf(id.toString())
            val count = db.update("attendees", values, where, bind)
            if (count == 1 && value("attendees", "attendeeEmail", id) == value("calendars", "ownerAccount", 2))
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
            assertTrue(f.provider.reads.filter { it.path == "attendees" }.all { it.fields != null })
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

    @Test fun unknownAclOrNamespaceFailBeforeRestrictedQueries() = runBlocking {
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

    @Test fun hiddenAndUnknownGuestVisibilityRespondWithoutReadingAnyOtherGuestPayload() = runBlocking {
        listOf<String?>("0", null, "99").forEachIndexed { index, visibility -> fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", visibility)
            f.provider.guardHiddenGuests = true
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val editable = f.editable()
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.THIS).respond)
            assertTrue(editable.details.attendees.isEmpty())
            val raw = org.json.JSONObject(f.store.edit(editable.token.value)!!)
            assertEquals("SELF", raw.getString("attendeeCoverage"))
            assertEquals(1, raw.getJSONArray("attendees").length())
            f.permissions += Manifest.permission.WRITE_CALENDAR
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("hidden-$index", selected, editable.token, respond()))
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.recover("hidden-$index"))
            assertEquals("1", f.provider.value("attendees", "attendeeStatus", 21))
            assertEquals("keep guest metadata", f.provider.value("attendees", "opaque_child", 21))
            f.provider.reads.filter { it.path == "attendees" }.forEach { read ->
                if (read.rowId == null) {
                    assertTrue(read.selection!!.contains("TRIM(attendeeEmail"))
                    assertNotNull(read.fields)
                    assertTrue(read.fields!!.all { it in setOf("_id", "event_id", "attendeeEmail") })
                } else assertEquals(20L, read.rowId)
            }
        } }
    }

    @Test fun privateLookupRejectsAsciiUnicodeCaseAndWhitespaceDuplicateAliases() = runBlocking {
        val aliases = listOf("actor@example.invalid" to "ACTOR@example.invalid",
            "actor@example.invalid" to "\u2007actor@example.invalid\t", "actor@example.invalid" to "actor@example.ınvalid",
            "actor@example.invalid" to "actor@example.İnvalid", "user@example.invalid" to "uſer@example.invalid", "k@example.invalid" to "K@example.invalid")
        aliases.forEach { (owner, alias) -> fixture { f ->
            f.provider.set("calendars", "account_name", owner); f.provider.set("calendars", "ownerAccount", owner)
            f.provider.set("attendees", "attendeeEmail", owner)
            f.provider.set("attendees", "attendeeEmail", alias, 21)
            f.provider.set("events", "guestsCanSeeGuests", "0")
            f.provider.guardHiddenGuests = true
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview)
            assertTrue(f.provider.reads.filter { it.path == "attendees" }.all { it.rowId == null && it.fields == listOf("_id", "event_id", "attendeeEmail") })
            f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            assertEquals(0, f.provider.batchCount)
        } }
    }

    @Test fun caseVariantWithoutAnExactSelfRowNeverLoadsItsPrivatePayload() = runBlocking {
        fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", "0"); f.provider.guardHiddenGuests = true
            f.provider.set("attendees", "attendeeEmail", "ACTOR@example.invalid")
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview)
            assertTrue(f.provider.reads.filter { it.path == "attendees" }.all { it.rowId == null && it.fields == listOf("_id", "event_id", "attendeeEmail") })
            assertEquals(0, f.provider.batchCount)
        }
    }

    @Test fun privateSourceReceiptsSurviveReopenAndNeverReplayALostAcknowledgement() = runBlocking {
        fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", "0"); f.provider.guardHiddenGuests = true
            val token = f.editable().token
            f.provider.failAfterCommit = true
            assertTrue((f.writer.mutate("private-commit", selected, token, respond()) as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            f.provider.failAfterCommit = false
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), f.store.databaseName)
            try {
                val other = CalendarMeetingWriter(RuntimeEnvironment.getApplication().contentResolver, f.reader, reopened, f.permissions::contains)
                f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
                assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), other.recover("private-commit"))
                f.permissions += Manifest.permission.WRITE_CALENDAR
                val current = (other.loadForEdit(selected) as MeetingReadResult.Ready).value
                f.provider.lostAck = true
                assertTrue((other.mutate("private-lost", selected, current.token, respond(MeetingResponse.NO)) as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
                assertEquals(common(MutationResult.Uncertain("private-lost", null)), other.recover("private-lost"))
                assertEquals(common(MutationResult.Uncertain("private-lost", null)), other.mutate("private-lost", selected, current.token, respond(MeetingResponse.NO)))
                assertEquals(2, f.provider.batchCount)
            } finally { reopened.close() }
        }
    }

    @Test fun privateLookupEscapesGlobMetacharactersAndDoesNotExpandStoredSourceMode() = runBlocking {
        fixture { f ->
            val owner = "a*b?c[d]@example.invalid"
            f.provider.set("calendars", "account_name", owner); f.provider.set("calendars", "ownerAccount", owner)
            f.provider.set("attendees", "attendeeEmail", owner)
            f.provider.set("attendees", "attendeeEmail", "axbycd@example.invalid", 21)
            f.provider.set("events", "guestsCanSeeGuests", "0")
            f.provider.guardHiddenGuests = true
            val editable = f.editable()
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("literal", selected, editable.token, respond()))
            f.provider.set("events", "guestsCanSeeGuests", "1")
            f.provider.reads.clear()
            // An external metadata change makes exact recovery uncertain; the SELF read mode
            // remains narrow even when the current provider would allow a full list.
            assertTrue((f.writer.recover("literal") as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            assertTrue(f.provider.reads.filter { it.path == "attendees" && it.rowId == null }.all { it.selection!!.contains("TRIM(attendeeEmail") })
        }
    }

    @Test fun unsupportedPrivatePredicateAndNonAsciiActorRemainExplicitReviewWithoutFullFallback() = runBlocking {
        fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", "0")
            f.provider.guardHiddenGuests = true; f.provider.rejectOwnPredicate = true
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview)
            assertEquals(1, f.provider.reads.count { it.path == "attendees" })
        }
        fixture { f ->
            val owner = "áctór@example.invalid"
            f.provider.set("calendars", "account_name", owner); f.provider.set("calendars", "ownerAccount", owner)
            f.provider.set("attendees", "attendeeEmail", owner)
            f.provider.set("events", "guestsCanSeeGuests", "0")
            f.provider.guardHiddenGuests = true
            assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview)
            assertTrue(f.provider.reads.none { it.path == "attendees" })
        }
    }

    @Test fun duplicateAtDispatchRollsBackButUnobservedOtherGuestChangesDoNotBroadenTheWrite() = runBlocking {
        fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", "0"); f.provider.guardHiddenGuests = true
            val token = f.editable().token
            f.provider.beforeBatch = { f.provider.set("attendees", "attendeeEmail", "ACTOR@example.invalid", 21) }
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("duplicate-race", selected, token, respond()))
            assertEquals("3", f.provider.value("attendees", "attendeeStatus"))
            assertEquals("3", f.provider.value("events", "selfAttendeeStatus"))
            assertEquals("FAILED", f.store.operation("duplicate-race")!!.phase)
        }
        fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", "0"); f.provider.guardHiddenGuests = true
            val token = f.editable().token
            f.provider.beforeBatch = { f.provider.set("attendees", "attendeeStatus", "2", 21) }
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("other-guest", selected, token, respond()))
            assertEquals("2", f.provider.value("attendees", "attendeeStatus", 21))
            assertEquals("keep guest metadata", f.provider.value("attendees", "opaque_child", 21))
        }
    }

    @Test fun visibilityChangesBeforeTokenAndOldCompleteSourceBecomingHiddenNeverReadAFullList() = runBlocking {
        fixture { f ->
            f.provider.set("events", "guestsCanSeeGuests", "0"); f.provider.guardHiddenGuests = true
            var changed = false
            f.provider.afterQuery = { path, selection ->
                if (!changed && path == "attendees" && selection?.contains("TRIM(attendeeEmail") == true) {
                    changed = true; f.provider.set("events", "guestsCanSeeGuests", null)
                }
            }
            assertEquals(MeetingReadResult.Unavailable, f.writer.loadForEdit(selected))
            f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        fixture { f ->
            val editable = f.editable()
            assertFalse(org.json.JSONObject(f.store.edit(editable.token.value)!!).has("attendeeCoverage"))
            assertTrue(f.writer.mutate("all-source", selected, editable.token, respond()) is MeetingMutationResult.LocalSaved)
            f.provider.set("events", "guestsCanSeeGuests", "0"); f.provider.guardHiddenGuests = true
            f.provider.reads.clear()
            assertTrue((f.writer.recover("all-source") as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            assertTrue(f.provider.reads.none { it.path == "attendees" })
        }
    }

    @Test fun nativeAttendeeItemMutationSelectionIsRejectedAndWriterUsesTableIdentityGuards() = runBlocking {
        fixture { f ->
            try {
                f.provider.update(ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, 20),
                    ContentValues().apply { put(CalendarContract.Attendees.ATTENDEE_STATUS, 1) }, "event_id = ?", arrayOf("10"))
                fail("AOSP attendee item URI must reject a supplied mutation selection")
            } catch (_: IllegalArgumentException) { }
            assertEquals("3", f.provider.value("attendees", "attendeeStatus"))
            f.provider.set("events", "guestsCanSeeGuests", "0"); f.provider.guardHiddenGuests = true
            val editable = f.editable()
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("table-guarded", selected, editable.token, respond()))
            val write = f.provider.mutations.single()
            assertEquals(CalendarContract.Attendees.CONTENT_URI, write.uri)
            assertEquals("_id = ? AND event_id = ? AND attendeeEmail = ? AND attendeeStatus = ?", write.selection)
            assertEquals(listOf("20", "10", "actor@example.invalid", "3"), write.args)
            assertTrue(f.provider.reads.any { it.path == "attendees" && it.fields?.contains("attendeeIdentity") == true && it.selection == "_id = ? AND event_id = ? AND attendeeEmail = ?" })
            assertEquals("1", f.provider.value("attendees", "attendeeStatus"))
        }
    }

    @Test fun joinedAttendeeProjectionChangesEveryDerivedSelfStatusAndOldPostAssertionRollsBack() = runBlocking {
        fixture { f ->
            fun rawGuests(): List<Map<String, String?>> = f.provider.query(CalendarContract.Attendees.CONTENT_URI, null, "event_id = ?", arrayOf("10"), "_id ASC").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }) }
            }
            val before = rawGuests()
            assertEquals(listOf("3", "3"), before.map { it["selfAttendeeStatus"] })
            val change = ContentValues().apply { put(CalendarContract.Attendees.ATTENDEE_STATUS, 1) }
            val guard = "_id = ? AND event_id = ? AND attendeeEmail = ? AND attendeeStatus = ?"
            assertEquals(1, f.provider.update(CalendarContract.Attendees.CONTENT_URI, change, guard, arrayOf("20", "10", "actor@example.invalid", "3")))
            assertEquals(listOf("1", "1"), rawGuests().map { it["selfAttendeeStatus"] })
            f.provider.update(CalendarContract.Attendees.CONTENT_URI, ContentValues().apply { put(CalendarContract.Attendees.ATTENDEE_STATUS, 3) },
                guard, arrayOf("20", "10", "actor@example.invalid", "1"))
            val oldExpected = before.first() + (CalendarContract.Attendees.ATTENDEE_STATUS to "1")
            val oldPostAssert = ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, 20))
                .withValues(ContentValues().apply { oldExpected.forEach { (key, value) -> if (value == null) putNull(key) else put(key, value) } })
                .withExpectedCount(1).withYieldAllowed(false).build()
            val update = ContentProviderOperation.newUpdate(CalendarContract.Attendees.CONTENT_URI).withValues(change)
                .withSelection(guard, arrayOf("20", "10", "actor@example.invalid", "3")).withExpectedCount(1).withYieldAllowed(false).build()
            try { f.provider.applyBatch(arrayListOf(update, oldPostAssert)); fail("Old null-projection postassert must fail on the joined view") }
            catch (_: OperationApplicationException) { }
            assertEquals("3", f.provider.value("attendees", "attendeeStatus"))
            assertEquals("3", f.provider.value("events", "selfAttendeeStatus"))
            val editable = f.editable()
            val captured = org.json.JSONObject(f.store.edit(editable.token.value)!!).getJSONArray("attendees")
            repeat(captured.length()) { index ->
                assertFalse(captured.getJSONObject(index).has("selfAttendeeStatus"))
                assertFalse(captured.getJSONObject(index).has("opaque_child"))
            }
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS), f.writer.mutate("base-columns", selected, editable.token, respond()))
            assertEquals("keep self metadata", f.provider.value("attendees", "opaque_child"))
            assertEquals("keep guest metadata", f.provider.value("attendees", "opaque_child", 21))
        }
    }

    @Test fun legacyExtraAttendeeColumnsAreNeverSilentlyDroppedForDispatchOrCommittedRecovery() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val source = org.json.JSONObject(f.store.edit(editable.token.value)!!)
            val guests = source.getJSONArray("attendees")
            repeat(guests.length()) { index ->
                val guest = guests.getJSONObject(index)
                guest.put("selfAttendeeStatus", "3").put("opaque_child", if (guest.getString("_id") == "20") "keep self metadata" else "keep guest metadata")
                val sorted = org.json.JSONObject()
                guest.keys().asSequence().toList().sorted().forEach { key -> sorted.put(key, guest.get(key)) }
                guests.put(index, sorted)
            }
            val legacyRaw = source.toString()
            fun hash(raw: String) = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val legacyToken = EditToken("meeting-edit-v1:" + hash("MEETING_EDIT_V1" + legacyRaw))
            f.store.saveEdit(legacyToken.value, legacyRaw)
            assertEquals(common(MutationResult.Conflict(null)), f.writer.mutate("legacy-dispatch", selected, legacyToken, respond()))
            assertNull(f.store.operation("legacy-dispatch"))
            assertEquals(0, f.provider.batchCount)
            assertTrue(f.writer.mutate("new-save", selected, editable.token, respond()) is MeetingMutationResult.LocalSaved)
            val operation = f.store.operation("new-save")!!
            val receipt = org.json.JSONObject(operation.resultRef!!)
            receipt.getJSONObject("command").put("token", legacyToken.value)
            val signature = hash("MEETING_COMMAND_V1" + receipt.getJSONObject("command").toString())
            receipt.put("signature", signature)
            f.store.prepare("legacy-commit", signature, operation.kind)
            f.store.mark("legacy-commit", "COMMITTED", receipt.toString())
            assertTrue((f.writer.recover("legacy-commit") as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
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
