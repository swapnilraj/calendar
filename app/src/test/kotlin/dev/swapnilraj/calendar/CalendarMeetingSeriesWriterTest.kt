package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.SyntheticCloneProviderPolicy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarMeetingSeriesWriterTest {
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val generated = EventRef(CalendarId(2), 10, start.plusSeconds(86_400), start.plusSeconds(90_000), RecurrenceRef(10, start.plusSeconds(86_400)))
    private val moved = EventRef(CalendarId(2), 11, start.plusSeconds(20 * 86_400), start.plusSeconds(20 * 86_400 + 3_600), RecurrenceRef(10, start.plusSeconds(2 * 86_400)))
    internal class Provider(val start: Instant) : ContentProvider() {
        val db = SQLiteDatabase.create(null)
        data class Query(val path: String, val fields: List<String>?, val where: String?, val args: List<String>)
        val queries = mutableListOf<Query>()
        var generatedMode = false
        var nextGeneratedId = 1000L
        var failCorrection = false
        var generatedEndDelta = 0L
        var generatedAck: ((Uri) -> Uri)? = null
        var delayGeneratedInstance = false
        val correctionValues = mutableListOf<ContentValues>()
        var batchCount = 0
        var updateCount = 0
        var failUpdateAt: Int? = null
        var lostAck = false
        var unavailableAfterCommit = false
        var committed = false
        var beforeBatch: (() -> Unit)? = null
        var failPath: String? = null
        init {
            table("calendars", mapOf("_id" to 2L, "account_name" to "actor@example.invalid", "account_type" to "com.google", "ownerAccount" to "actor@example.invalid",
                "_sync_id" to "calendar-A", "calendar_access_level" to 300L, "visible" to 1L, "sync_events" to 1L,
                "calendar_displayName" to "Synthetic work calendar", "calendar_color" to 123L, "calendar_timezone" to "UTC"))
            table("events", event(10))
            table("instances", mapOf("event_id" to 10L, "begin" to start.plusSeconds(86_400).toEpochMilli(), "end" to start.plusSeconds(90_000).toEpochMilli()))
            table("attendees", own(10))
            table("reminders", mapOf("_id" to 30L, "event_id" to 10L, "minutes" to -1L, "method" to 0L))
            for ((id, original, actual) in listOf(Triple(11L, 2L, 20L), Triple(12L, 3L, 3L), Triple(13L, 0L, 50L), Triple(14L, 4L, 4L))) addException(id, original, actual, cancelled = id == 12L, deleted = id == 14L)
            db.execSQL("CREATE VIEW instance_view AS SELECT i.event_id, i.begin, i.end, e.* FROM instances i JOIN events e ON i.event_id=e._id")
            // AOSP attendee collection/item views expose joined event columns. SDK9-only readers
            // must not capture them as child metadata or read private nonself guest payloads.
            db.execSQL("CREATE VIEW attendee_view AS SELECT a.*, e.selfAttendeeStatus AS selfAttendeeStatus, e.rrule AS rrule, e.eventStatus AS eventStatus FROM attendees a JOIN events e ON a.event_id=e._id")
            addGuest(10, 999)
        }
        fun enableGenerated() {
            generatedMode = true
            db.execSQL("ALTER TABLE events ADD COLUMN hasExtendedProperties INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE events ADD COLUMN hasAlarm INTEGER DEFAULT 0")
            (1..10).forEach { db.execSQL("ALTER TABLE events ADD COLUMN sync_data$it TEXT") }
            db.execSQL("UPDATE events SET opaque_event=NULL")
            db.execSQL("UPDATE attendees SET opaque_child=NULL")
            db.execSQL("UPDATE events SET guestsCanSeeGuests=1,hasAlarm=1 WHERE _id=10")
            db.execSQL("CREATE TABLE extendedproperties (_id INTEGER,event_id INTEGER,name TEXT,value TEXT)")
            db.execSQL("DROP VIEW attendee_view")
            db.execSQL("CREATE VIEW attendee_view AS SELECT a.*, e.selfAttendeeStatus,e.rrule,e.eventStatus,e.guestsCanSeeGuests,e.dtstart,e.dtend FROM attendees a JOIN events e ON a.event_id=e._id")
            db.execSQL("DROP VIEW instance_view")
            db.execSQL("""CREATE VIEW instance_view AS SELECT i.event_id,i.begin,i.end,e.* FROM instances i JOIN events e ON i.event_id=e._id
                WHERE e.original_id IS NOT NULL OR NOT EXISTS (SELECT 1 FROM events x WHERE x.original_id=e._id AND x.originalInstanceTime=i.begin AND x.deleted=0)""")
        }
        private fun event(id: Long, original: Long? = null, actual: Long = 0, cancelled: Boolean = false, deleted: Boolean = false): Map<String, Any?> = mapOf(
            "_id" to id, "calendar_id" to 2L, "_sync_id" to if (id == 10L) "master-A" else "exception-$id", "uid2445" to "uid-$id",
            "dtstart" to start.plusSeconds(actual * 86_400).toEpochMilli(), "dtend" to if (id == 10L) null else start.plusSeconds(actual * 86_400 + 3_600).toEpochMilli(),
            "duration" to if (id == 10L) "PT1H" else null, "allDay" to 0L, "eventTimezone" to "UTC", "eventEndTimezone" to "UTC",
            "title" to "Synthetic invitation $id", "eventLocation" to "Fixture room", "description" to "Raw notes", "displayColor" to 456L,
            "selfAttendeeStatus" to if (cancelled) 1L else 3L, "eventStatus" to if (cancelled) 2L else 1L, "deleted" to if (deleted) 1L else 0L,
            "organizer" to "organizer@example.invalid", "isOrganizer" to 0L, "hasAttendeeData" to 1L, "guestsCanSeeGuests" to 0L, "guestsCanModify" to 0L, "guestsCanInviteOthers" to 0L,
            "rrule" to if (id == 10L) "FREQ=DAILY;COUNT=100" else null, "rdate" to null, "exrule" to null, "exdate" to null,
            "original_id" to if (original == null) null else 10L, "original_sync_id" to if (original == null) null else "master-A",
            "originalInstanceTime" to original?.let { start.plusSeconds(it * 86_400).toEpochMilli() }, "originalAllDay" to if (original == null) null else 0L,
            "customAppPackage" to null, "customAppUri" to null, "availability" to 77L, "accessLevel" to 2L,
            "dirty" to 0L, "mutators" to null, "lastSynced" to 0L, "opaque_event" to "keep event $id")
        private fun own(event: Long, cancelled: Boolean = false) = mapOf<String, Any?>("_id" to 100L + event, "event_id" to event, "attendeeEmail" to "actor@example.invalid", "attendeeName" to "Actor",
            "attendeeStatus" to if (cancelled) 1L else 3L, "attendeeRelationship" to 1L, "attendeeType" to 1L, "attendeeIdentity" to "actor-id", "attendeeIdNamespace" to "fixture", "opaque_child" to "keep self $event")
        fun addException(id: Long, original: Long, actual: Long, cancelled: Boolean = false, deleted: Boolean = false) {
            insertRow("events", event(id, original, actual, cancelled, deleted)); insertRow("attendees", own(id, cancelled))
            if (!cancelled && !deleted) insertRow("instances", mapOf("event_id" to id, "begin" to start.plusSeconds(actual * 86_400).toEpochMilli(), "end" to start.plusSeconds(actual * 86_400 + 3_600).toEpochMilli()))
        }
        fun addGuest(event: Long, id: Long, email: String = "other@example.invalid") = insertRow("attendees", own(event).toMutableMap().apply {
            put("_id", id); put("attendeeEmail", email); put("attendeeStatus", 1L); put("opaque_child", "keep other guest")
        })
        private fun table(name: String, row: Map<String, Any?>) {
            val numeric = setOf("dtend", "original_id", "originalInstanceTime", "originalAllDay")
            db.execSQL("CREATE TABLE $name (" + row.entries.joinToString(",") { (key, value) -> "\"$key\" " + if (value is Number || key in numeric) "INTEGER" else "TEXT" } + ")")
            insertRow(name, row)
        }
        private fun insertRow(table: String, row: Map<String, Any?>) = db.insertOrThrow(table, null, ContentValues().apply { row.forEach { (key, value) -> when (value) {
            null -> putNull(key); is Number -> put(key, value.toLong()); else -> put(key, value.toString())
        } } })
        fun set(table: String, column: String, value: String?, id: Long) = db.update(table, ContentValues().apply { if (value == null) putNull(column) else put(column, value) }, "_id = ?", arrayOf(id.toString()))
        fun value(table: String, column: String, id: Long) = db.query(table, arrayOf(column), "_id = ?", arrayOf(id.toString()), null, null, null).use { check(it.moveToFirst()); it.getString(0) }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val path = uri.pathSegments.first()
            if (failPath == path || unavailableAfterCommit && committed) error("Synthetic provider unavailable")
            queries += Query(path, fields?.toList(), selection, args?.toList().orEmpty())
            val where = mutableListOf<String>(); val bind = mutableListOf<String>()
            val item = if (path == "instances") null else uri.lastPathSegment?.toLongOrNull()
            if (item != null) { where += "_id = ?"; bind += item.toString() }
            if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
            if (path == "instances") { where += "begin < ? AND end > ?"; bind += uri.pathSegments[3]; bind += uri.pathSegments[2] }
            if (path == "attendees") {
                val explicitlyVisible = selection?.startsWith("event_id = ?") == true && args?.firstOrNull()?.toLongOrNull()?.let { value("events", "guestsCanSeeGuests", it) == "1" } == true
                check(fields != null || generatedMode && explicitlyVisible) { "No raw hidden joined attendee payload" }
                check(item != null || selection?.startsWith("_id IN") == true || generatedMode && selection?.startsWith("_id = ? AND event_id = ?") == true || selection?.contains("TRIM(attendeeEmail") == true || explicitlyVisible || generatedMode && db.inTransaction()) { "No complete hidden guest list" }
            }
            val table = when (path) { "attendees" -> "attendee_view"; "instances" -> "instance_view"; else -> path }
            return db.query(table, fields, where.joinToString(" AND ").takeIf(String::isNotEmpty), bind.toTypedArray(), null, null, sort)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            check(generatedMode && uri == ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, 10))
            val supplied = requireNotNull(values)
            val allowed = setOf("originalInstanceTime","selfAttendeeStatus","dtstart","duration","eventTimezone","allDay","eventStatus")
            check(supplied.keySet().all { it in allowed }) { "Native exception whitelist rejects DTEND/RRULE" }
            val master = db.query("events", null, "_id=10", null, null, null, null).use { c -> check(c.moveToFirst()); ContentValues().apply {
                c.columnNames.forEachIndexed { i, key -> if (c.isNull(i)) putNull(key) else put(key, c.getString(i)) }
            } }
            val id = nextGeneratedId++
            master.put("_id",id); master.putNull("_sync_id"); (1..10).forEach { master.putNull("sync_data$it") }
            listOf("rrule","rdate","exrule","exdate","duration").forEach(master::putNull)
            master.put("original_id",10); master.put("original_sync_id",value("events","_sync_id",10)); master.put("originalAllDay",supplied.getAsInteger("allDay"))
            master.putAll(supplied); master.putNull("duration")
            val duration = supplied.getAsString("duration")
            val seconds = Regex("PT([0-9]+)S").matchEntire(duration)?.groupValues?.get(1)?.toLong()
                ?: Regex("P([0-9]+)D").matchEntire(duration)!!.groupValues[1].toLong().times(86400)
            check(seconds in 1..Int.MAX_VALUE.toLong())
            master.put("dtend",Math.addExact(supplied.getAsLong("dtstart"),Math.multiplyExact(seconds,1000)))
            db.insertOrThrow("events",null,master)
            fun clone(table: String, fields: Array<String>) {
                val rows = db.query(table,fields,"event_id=10",null,null,null,null).use { c -> buildList {
                    while(c.moveToNext())add(ContentValues().apply { fields.forEachIndexed { i,k -> if(c.isNull(i))putNull(k)else put(k,c.getString(i)) } })
                } }
                for(row in rows) { row.put("_id",nextGeneratedId++);row.put("event_id",id)
                    if(table=="attendees" && row.getAsString("attendeeEmail")=="actor@example.invalid")row.put("attendeeStatus",supplied.getAsInteger("selfAttendeeStatus"))
                    db.insertOrThrow(table,null,row)
                }
            }
            clone("attendees", CalendarMeetingWriter.attendeeFields.filter { it !in setOf("_id","event_id") }.toTypedArray())
            clone("reminders",arrayOf("minutes","method"));clone("extendedproperties",arrayOf("name","value"))
            if(!delayGeneratedInstance)insertRow("instances",mapOf("event_id" to id,"begin" to supplied.getAsLong("dtstart"),"end" to master.getAsLong("dtend")))
            val result=ContentUris.withAppendedId(uri,id)
            return generatedAck?.invoke(result) ?: result
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Existing RSVP must not delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            if(generatedMode && uri == CalendarContract.Events.CONTENT_URI) {
                if(failCorrection)throw OperationApplicationException("Synthetic correction rollback")
                check(values!!.keySet()==setOf("dtstart","dtend","eventTimezone","allDay","originalInstanceTime","originalAllDay"))
                correctionValues+=ContentValues(values)
                val id=db.query("events",arrayOf("_id"),selection,args,null,null,null).use { c -> if(c.moveToFirst())c.getLong(0)else null }
                val count=db.update("events",values,selection,args)
                if(id!=null)db.update("instances",ContentValues().apply {put("end",values.getAsLong("dtend")+generatedEndDelta)},"event_id=?",arrayOf(id.toString()))
                return count
            }
            check(uri == CalendarContract.Attendees.CONTENT_URI) { "No attendee item URI selection or event response update" }
            check(values!!.keySet() == setOf("attendeeStatus"))
            updateCount++
            if (updateCount == failUpdateAt) throw OperationApplicationException("Synthetic atomic response rollback")
            val rows = db.query("attendees", arrayOf("event_id", "attendeeEmail"), selection, args, null, null, null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
            }
            val count = db.update("attendees", values, selection, args)
            rows.filter { it.second == "actor@example.invalid" }.forEach { (event, _) ->
                db.update("events", ContentValues().apply {
                    put("selfAttendeeStatus", values.getAsInteger("attendeeStatus")); put("dirty", 1); put("mutators", "fixture")
                }, "_id = ?", arrayOf(event.toString()))
            }
            return count
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batchCount++; beforeBatch?.also { beforeBatch = null }?.invoke()
            db.beginTransaction()
            val result = try { super.applyBatch(operations).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            committed = true
            if (lostAck) error("Synthetic lost batch acknowledgement")
            return result
        }
    }
    private data class Fixture(val p: Provider, val writer: CalendarMeetingWriter, val reader: CalendarReader, val store: CalendarLocalStore, val permissions: MutableSet<String>)
    private suspend fun fixture(planning: RecurrencePlanningBudget = RecurrencePlanningBudget(), budgets: ProviderReadBudgets = ProviderReadBudgets(), block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication(); val p = Provider(start)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val name = "meeting-series-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name); val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarMeetingWriter(context.contentResolver, reader, store, permissions::contains, readBudgets = budgets, planningBudget = planning, providerClonePolicy = SyntheticCloneProviderPolicy)
        try { block(Fixture(p, writer, reader, store, permissions)) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); p.db.close(); context.deleteDatabase(name) }
    }
    private fun respond(scope: RecurrenceScope, response: MeetingResponse = MeetingResponse.YES) = MeetingCommand.Respond(scope, response)
    private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
    private suspend fun Fixture.editable(ref: EventRef = generated) = (writer.loadForEdit(ref) as MeetingReadResult.Ready).value

    @Test fun existingThisChangesOnlyMovedExceptionAndAllChangesEveryActiveSelfLeavingCancellationsAndGuests() = runBlocking {
        fixture { f ->
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val editable = f.editable(moved)
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.THIS).respond)
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.ALL).respond)
            assertEquals(common(MutationResult.PermissionRequired), f.writer.mutate("no-write", moved, editable.token, respond(RecurrenceScope.THIS)))
            f.permissions += Manifest.permission.WRITE_CALENDAR
            assertEquals(MeetingMutationResult.LocalSaved(moved, RecurrenceScope.THIS), f.writer.mutate("this", moved, editable.token, respond(RecurrenceScope.THIS, MeetingResponse.MAYBE)))
            assertEquals("4", f.p.value("attendees", "attendeeStatus", 111)); assertEquals("3", f.p.value("attendees", "attendeeStatus", 110))
            assertEquals("1", f.p.value("attendees", "attendeeStatus", 112))
            val all = f.editable()
            assertEquals(MeetingMutationResult.LocalSaved(generated, RecurrenceScope.ALL), f.writer.mutate("all", generated, all.token, respond(RecurrenceScope.ALL, MeetingResponse.NO)))
            listOf(10L, 11L, 13L).forEach { assertEquals("2", f.p.value("attendees", "attendeeStatus", 100 + it)); assertEquals("2", f.p.value("events", "selfAttendeeStatus", it)) }
            assertEquals("1", f.p.value("attendees", "attendeeStatus", 112)); assertEquals("3", f.p.value("attendees", "attendeeStatus", 114))
            assertEquals("2", f.p.value("events", "eventStatus", 12)); assertEquals("1", f.p.value("events", "eventStatus", 10))
            assertEquals("1", f.p.value("attendees", "attendeeStatus", 999)); assertEquals("keep other guest", f.p.value("attendees", "opaque_child", 999))
            assertEquals("keep self 11", f.p.value("attendees", "opaque_child", 111)); assertEquals("keep event 13", f.p.value("events", "opaque_event", 13))
            assertTrue(f.p.queries.filter { it.path == "attendees" }.all { it.fields != null })
        }
    }

    @Test fun authorizedSelectedDetailsStayTruthfulButPrivateSeriesSnapshotHasOnlySelfRows() = runBlocking {
        fixture { f ->
            f.p.set("events", "guestsCanSeeGuests", "1", 10)
            val editable = f.editable()
            assertEquals(2, editable.details.attendees.size)
            val raw = f.store.edit(editable.token.value)!!
            assertFalse(raw.contains("other@example.invalid"))
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.ALL).respond)
        }
    }

    @Test fun generatedThisFollowingAndOwnedCommandsNeverNarrowOrDispatch() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            assertEquals(AccessDecision.DENIED, editable.scopes.getValue(RecurrenceScope.THIS).respond)
            listOf<MeetingCommand>(respond(RecurrenceScope.THIS), respond(RecurrenceScope.FOLLOWING), MeetingCommand.CancelOwned(RecurrenceScope.ALL), MeetingCommand.EditOwned(RecurrenceScope.ALL, EventPatch(title = FieldChange.Set("No")))).forEachIndexed { i, command ->
                assertTrue(f.writer.mutate("unsupported-$i", generated, editable.token, command) is MeetingMutationResult.Unsupported)
                assertNull(f.store.operation("unsupported-$i"))
            }
            assertEquals(0, f.p.batchCount)
        }
    }

    @Test fun ambiguousOtherMemberDeniesAllButKeepsProvenExistingThis() = runBlocking {
        fixture { f ->
            f.p.addGuest(13, 888, "ACTOR@example.invalid")
            val editable = f.editable(moved)
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.THIS).respond)
            assertEquals(AccessDecision.DENIED, editable.scopes.getValue(RecurrenceScope.ALL).respond)
            assertTrue(f.writer.mutate("not-all", moved, editable.token, respond(RecurrenceScope.ALL)) is MeetingMutationResult.NeedsReview)
            assertNull(f.store.operation("not-all"))
            assertEquals(MeetingMutationResult.LocalSaved(moved, RecurrenceScope.THIS), f.writer.mutate("only-existing", moved, editable.token, respond(RecurrenceScope.THIS)))
            assertEquals("3", f.p.value("attendees", "attendeeStatus", 113))
        }
    }

    @Test fun syncOnlyMovedIdentityRawRdateAndDurationOnlyOtherMemberArePreserved() = runBlocking {
        fixture { f ->
            f.p.set("events", "original_id", null, 11)
            f.p.set("events", "dtend", null, 13); f.p.set("events", "duration", "P1D", 13)
            f.p.set("events", "rrule", null, 10); f.p.set("events", "rdate", "20261119T090000Z,20261120T090000Z", 10)
            val editable = f.editable(moved)
            assertEquals(MeetingMutationResult.LocalSaved(moved, RecurrenceScope.ALL), f.writer.mutate("rdate-all", moved, editable.token, respond(RecurrenceScope.ALL)))
            assertNull(f.p.value("events", "dtend", 13)); assertEquals("P1D", f.p.value("events", "duration", 13))
            assertEquals("20261119T090000Z,20261120T090000Z", f.p.value("events", "rdate", 10))
            assertEquals(start.plusSeconds(2 * 86_400).toEpochMilli().toString(), f.p.value("events", "originalInstanceTime", 11))
        }
    }

    @Test fun allDayOriginalDatesAndRawNominalDurationRemainUntouchedByAllResponse() = runBlocking {
        fixture { f ->
            val midnight = Instant.parse("2026-11-18T00:00:00Z")
            val originalDays = mapOf(11L to 2L, 12L to 3L, 13L to 0L, 14L to 4L)
            val actualDays = mapOf(10L to 0L, 11L to 20L, 12L to 3L, 13L to 50L, 14L to 4L)
            actualDays.forEach { (id, actual) ->
                f.p.set("events", "allDay", "1", id)
                f.p.set("events", "dtstart", midnight.plusSeconds(actual * 86_400).toEpochMilli().toString(), id)
                if (id == 10L) f.p.set("events", "duration", "P1D", id)
                else {
                    f.p.set("events", "dtend", midnight.plusSeconds((actual + 1) * 86_400).toEpochMilli().toString(), id)
                    f.p.set("events", "originalAllDay", "1", id)
                    f.p.set("events", "originalInstanceTime", midnight.plusSeconds(originalDays.getValue(id) * 86_400).toEpochMilli().toString(), id)
                }
                val instanceDay = if (id == 10L) 1L else actual
                f.p.db.update("instances", ContentValues().apply { put("begin", midnight.plusSeconds(instanceDay * 86_400).toEpochMilli()); put("end", midnight.plusSeconds((instanceDay + 1) * 86_400).toEpochMilli()) },
                    "event_id = ?", arrayOf(id.toString()))
            }
            val selected = EventRef(CalendarId(2), 10, midnight.plusSeconds(86_400), midnight.plusSeconds(2 * 86_400), RecurrenceRef(10, midnight.plusSeconds(86_400)))
            val editable = f.editable(selected)
            assertTrue(editable.details.occurrence.time is EventTime.AllDay)
            assertEquals(MeetingMutationResult.LocalSaved(selected, RecurrenceScope.ALL), f.writer.mutate("all-day", selected, editable.token, respond(RecurrenceScope.ALL)))
            assertEquals("P1D", f.p.value("events", "duration", 10))
            assertEquals("1", f.p.value("events", "originalAllDay", 11))
            assertEquals(midnight.plusSeconds(2 * 86_400).toEpochMilli().toString(), f.p.value("events", "originalInstanceTime", 11))
        }
    }

    @Test fun foreignOrContradictoryGraphFailsBeforeOwnRowsAndPartialReadsNeverIssueCapabilities() = runBlocking {
        fixture { f ->
            f.p.set("events", "calendar_id", "3", 13)
            assertTrue(f.writer.loadForEdit(moved) is MeetingReadResult.NeedsReview)
            assertTrue(f.p.queries.none { it.path == "attendees" || it.path == "events" && it.fields == null })
        }
        fixture { f ->
            f.p.set("events", "original_sync_id", "other-master", 11)
            assertTrue(f.writer.loadForEdit(moved) is MeetingReadResult.NeedsReview)
            assertTrue(f.p.queries.none { it.path == "attendees" })
        }
        fixture { f ->
            f.p.failPath = "attendees"
            assertEquals(MeetingReadResult.Unavailable, f.writer.loadForEdit(moved))
            f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun newExceptionAtDispatchAndMidBatchFailureRollBackEveryResponse() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            f.p.beforeBatch = { f.p.addException(15, 5, 5) }
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("phantom", generated, token, respond(RecurrenceScope.ALL)))
            listOf(110L, 111L, 113L).forEach { assertEquals("3", f.p.value("attendees", "attendeeStatus", it)) }
            assertEquals("FAILED", f.store.operation("phantom")!!.phase)
            assertEquals(common(MutationResult.Conflict(null)), f.writer.mutate("phantom", generated, token, respond(RecurrenceScope.ALL)))
            assertEquals(1, f.p.batchCount)
        }
        fixture { f ->
            val token = f.editable().token
            f.p.failUpdateAt = 2
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("rollback", generated, token, respond(RecurrenceScope.ALL)))
            listOf(110L, 111L, 113L).forEach { assertEquals("3", f.p.value("attendees", "attendeeStatus", it)) }
            assertEquals(common(MutationResult.SafeToRetry("rollback")), f.writer.recover("rollback"))
            f.p.failUpdateAt = null
            assertEquals(MeetingMutationResult.LocalSaved(generated, RecurrenceScope.ALL), f.writer.mutate("rollback", generated, token, respond(RecurrenceScope.ALL)))
        }
    }

    @Test fun knownAllReceiptReopensReadOnlyAndRejectsNamespaceReuseBeforePrivateQueries() = runBlocking {
        fixture { f ->
            val token = f.editable().token
            f.p.unavailableAfterCommit = true
            assertTrue((f.writer.mutate("commit", generated, token, respond(RecurrenceScope.ALL)) as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            f.p.unavailableAfterCommit = false
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), f.store.databaseName)
            try {
                val other = CalendarMeetingWriter(RuntimeEnvironment.getApplication().contentResolver, f.reader, reopened, f.permissions::contains)
                f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
                assertEquals(MeetingMutationResult.LocalSaved(generated, RecurrenceScope.ALL), other.recover("commit"))
                f.p.set("calendars", "_sync_id", "calendar-B", 2); f.p.queries.clear()
                assertTrue((other.recover("commit") as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
                assertEquals(listOf("calendars"), f.p.queries.map { it.path })
                assertEquals(1, f.p.batchCount)
            } finally { reopened.close() }
        }
    }

    @Test fun lostAckNeverReplaysAndPreparedProofRequiresExactScopeAndPayload() = runBlocking {
        fixture { f ->
            val token = f.editable(moved).token
            f.p.lostAck = true
            assertTrue((f.writer.mutate("lost", moved, token, respond(RecurrenceScope.THIS)) as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.recover("lost"))
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.mutate("lost", moved, token, respond(RecurrenceScope.THIS)))
            assertEquals(1, f.p.batchCount)
        }
        fixture { f ->
            val token = f.editable(moved).token
            f.store.writableDatabase.execSQL("CREATE TRIGGER interrupt_series BEFORE UPDATE OF phase ON mutation_journal WHEN NEW.phase='ATTEMPTED' BEGIN SELECT RAISE(FAIL, 'Synthetic interruption'); END")
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("prepared", moved, token, respond(RecurrenceScope.THIS)))
            assertEquals(common(MutationResult.SafeToRetry("prepared")), f.writer.recover("prepared"))
            f.store.writableDatabase.execSQL("DROP TRIGGER interrupt_series")
            assertEquals(common(MutationResult.Conflict(null)), f.writer.mutate("prepared", moved, token, respond(RecurrenceScope.ALL)))
            assertEquals(MeetingMutationResult.LocalSaved(moved, RecurrenceScope.THIS), f.writer.mutate("prepared", moved, token, respond(RecurrenceScope.THIS)))
        }
    }

    @Test fun boundedGraphAndTextLimitsRefuseBeforeSnapshotJournalOrBatch() = runBlocking {
        fixture(RecurrencePlanningBudget(maxExceptions = 1)) { f ->
            assertTrue(f.writer.loadForEdit(moved) is MeetingReadResult.LimitExceeded)
            assertEquals(0, f.p.batchCount)
            f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        fixture { f ->
            f.p.set("events", "description", "x".repeat(70_000), 13)
            assertEquals(MeetingReadResult.LimitExceeded, f.writer.loadForEdit(moved))
            assertEquals(0, f.p.batchCount)
        }
    }

    @Test fun ownRowsUseTwoHundredIdChunksInsteadOfPerMemberDetailReads() = runBlocking {
        fixture { f ->
            repeat(201) { index -> f.p.addException(1000L + index, 100L + index, 100L + index) }
            val source = CalendarMeetingSeriesReader(RuntimeEnvironment.getApplication().contentResolver, f.reader, f.permissions::contains,
                ProviderReadBudgets(), RecurrencePlanningBudget())
            withReadBudget(ProviderReadBudgets().mutation) {
                val capture = source.capture(generated)
                assertEquals(204, capture.eligible.size)
            }
            val candidates = f.p.queries.filter { it.path == "attendees" && it.where?.contains("TRIM(attendeeEmail") == true }
            val payloads = f.p.queries.filter { it.path == "attendees" && it.where?.startsWith("_id IN") == true }
            assertEquals(2, candidates.size); assertEquals(2, payloads.size)
            assertTrue(candidates.all { it.fields == listOf("_id", "event_id", "attendeeEmail") })
            assertEquals(1, f.p.queries.count { it.path == "instances" })
        }
    }
}
