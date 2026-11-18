package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarRecurringCreateTest {
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A")
    private val draft = EventDraft(CalendarId(2), EventTime.Timed(start, start.plusSeconds(3600)), ZoneId.of("Europe/London"), "Synthetic authored series",
        "Fixture room", "Fixture notes", listOf(10, 30), availability = 1, privacy = 2, targetIdentity = identity,
        recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(4)))
    /** SQLite enforces the native recurring master shape. First-instance bounds use AOSP's
     * fixed elapsed Duration arithmetic; this fixture does not claim cloud or full-rule expansion. */
    internal class Provider : ContentProvider() {
        var allowUpdates = false
        var nativeMeetingMode = false
        var malformedAttendeeAck = false
        var malformedLastEventAck = false
        var rejectEventUidProjection = false
        val insertedEventValues = mutableListOf<ContentValues>()
        var failAttendee = false
        var expandInstances = false
        var afterCommit: (() -> Unit)? = null
        var afterQuery: ((String) -> Unit)? = null
        var beforeQuery: ((String) -> Unit)? = null
        val db = SQLiteDatabase.create(null)
        data class Query(val path: String, val fields: List<String>?, val selection: String?)
        val queries = mutableListOf<Query>()
        var batchCount = 0
        var lostAck = false
        var malformedAck = false
        var failReminder = false
        var delayedInstances = false
        var endDelta = 0L
        var beginDelta = 0L
        var failReadsAfterCommit = false
        var committed = false
        var beforeBatch: (() -> Unit)? = null
        init {
            db.execSQL("""CREATE TABLE calendars (_id INTEGER PRIMARY KEY,account_name TEXT,account_type TEXT,ownerAccount TEXT,_sync_id TEXT,
                calendar_displayName TEXT,calendar_color INTEGER,calendar_timezone TEXT,calendar_access_level INTEGER,visible INTEGER,sync_events INTEGER,
                allowedReminders TEXT,maxReminders INTEGER,canModifyTimeZone INTEGER,allowedAvailability TEXT)""")
            db.execSQL("""INSERT INTO calendars VALUES (2,'owner@example.invalid','com.google','owner@example.invalid','calendar-A',
                'Synthetic calendar',123,'Europe/London',700,1,1,'0,1,2',5,1,'0,1,2')""")
            db.execSQL("""CREATE TABLE events (_id INTEGER PRIMARY KEY AUTOINCREMENT,calendar_id INTEGER NOT NULL,dtstart INTEGER NOT NULL,dtend INTEGER,duration TEXT,
                allDay INTEGER,eventTimezone TEXT,eventEndTimezone TEXT,title TEXT,eventLocation TEXT,description TEXT,rrule TEXT,rdate TEXT,exrule TEXT,exdate TEXT,
                hasAlarm INTEGER,availability INTEGER,accessLevel INTEGER,deleted INTEGER DEFAULT 0,eventStatus INTEGER DEFAULT 1,organizer TEXT DEFAULT 'owner@example.invalid',
                isOrganizer INTEGER DEFAULT 1,hasAttendeeData INTEGER DEFAULT 0,guestsCanSeeGuests INTEGER DEFAULT 1,guestsCanModify INTEGER DEFAULT 0,guestsCanInviteOthers INTEGER DEFAULT 0,
                selfAttendeeStatus INTEGER DEFAULT 0,displayColor INTEGER DEFAULT 123,original_id INTEGER,original_sync_id TEXT,originalInstanceTime INTEGER,originalAllDay INTEGER,_sync_id TEXT,uid2445 TEXT,
                customAppPackage TEXT,customAppUri TEXT,hasExtendedProperties INTEGER DEFAULT 0,native_duration_ms INTEGER DEFAULT 0,CHECK(rrule IS NULL OR (dtend IS NULL AND duration IS NOT NULL)))""")
            db.execSQL("CREATE TABLE reminders (_id INTEGER PRIMARY KEY AUTOINCREMENT,event_id INTEGER,minutes INTEGER,method INTEGER)")
            db.execSQL("CREATE TABLE attendees (_id INTEGER PRIMARY KEY AUTOINCREMENT,event_id INTEGER,attendeeName TEXT,attendeeEmail TEXT,attendeeStatus INTEGER,attendeeRelationship INTEGER,attendeeType INTEGER,attendeeIdentity TEXT,attendeeIdNamespace TEXT)")
            db.execSQL("CREATE TABLE extendedproperties (_id INTEGER PRIMARY KEY AUTOINCREMENT,event_id INTEGER,name TEXT,value TEXT)")
            db.execSQL("CREATE VIEW instance_view AS SELECT _id AS event_id,dtstart AS begin,COALESCE(dtend,dtstart+native_duration_ms) AS end,e.* FROM events e WHERE deleted=0")
        }
        fun setCalendar(column: String, value: String?) = db.update("calendars", ContentValues().apply { if (value == null) putNull(column) else put(column, value) }, "_id=2", null)
        fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
        fun value(column: String): String? = db.query("events", arrayOf(column), null, null, null, null, null).use { check(it.moveToFirst()); it.getString(0) }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (failReadsAfterCommit && committed) error("Synthetic post-commit read failure")
            val path = uri.pathSegments.first(); queries += Query(path, fields?.toList(), selection)
            beforeQuery?.invoke(path)
            if(rejectEventUidProjection && path=="events" && fields?.contains("uid2445")==true)throw IllegalArgumentException("Synthetic missing uid2445 projection")
            if (path == "instances" && delayedInstances) return MatrixCursor(fields!!)
            if (path == "instances" && expandInstances) return expanded(uri, fields!!, selection, args)
            val where = mutableListOf<String>(); val bind = mutableListOf<String>()
            if (path != "instances") uri.lastPathSegment?.toLongOrNull()?.let { where += "_id = ?"; bind += it.toString() }
            if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
            val projected = if (path == "instances") fields?.map { when (it) {
                "begin" -> "(begin+$beginDelta) AS begin"; "end" -> "(end+$endDelta) AS end"; else -> it
            } }?.toTypedArray() else fields ?: if (path == "events") db.rawQuery("PRAGMA table_info(events)", null).use { cursor -> buildList {
                while (cursor.moveToNext()) cursor.getString(1).takeIf { it != "native_duration_ms" }?.let(::add)
            }.toTypedArray() } else null
            if (path == "instances") { where += "(begin+$beginDelta) < CAST(? AS INTEGER) AND (end+$endDelta) > CAST(? AS INTEGER)"; bind += uri.pathSegments[3]; bind += uri.pathSegments[2] }
            val effective = if (nativeMeetingMode && path == "events") projected?.map { field ->
                if (field == "isOrganizer") "COALESCE(isOrganizer, organizer = (SELECT ownerAccount FROM calendars WHERE calendars._id = events.calendar_id)) AS isOrganizer" else field
            }?.toTypedArray() else projected
            val table = when {
                path == "instances" -> "instance_view"
                nativeMeetingMode && path in setOf("attendees", "reminders") && selection?.contains("calendar_id") == true ->
                    "(SELECT $path.*, events.calendar_id, events.organizer, events.uid2445 FROM $path JOIN events ON $path.event_id = events._id)"
                else -> path
            }
            val result = db.query(table, effective, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
            afterQuery?.invoke(path)
            return result
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val path = uri.pathSegments.first()
            if (path == "reminders" && failReminder) throw OperationApplicationException("Synthetic reminder rollback")
            check(path == "events" || path == "reminders" || nativeMeetingMode && path == "attendees")
            if(path == "attendees" && failAttendee) throw OperationApplicationException("Synthetic attendee rollback")
            val inserted = ContentValues(values)
            if(path == "events")insertedEventValues += ContentValues(values)
            if(nativeMeetingMode && path == "events") {
                if(!inserted.containsKey("organizer"))inserted.put("organizer", "owner@example.invalid")
                if(!inserted.containsKey("eventStatus"))inserted.putNull("eventStatus")
                if(!inserted.containsKey("isOrganizer"))inserted.putNull("isOrganizer")
                // Native normal insert strips caller HAS_ALARM and reminder rows later compute it.
                inserted.put("hasAlarm",0)
            }
            if (path == "events" && !inserted.getAsString("rrule").isNullOrBlank()) {
                val raw = inserted.getAsString("duration") ?: error("Recurring duration required")
                // Native Duration accumulates Int seconds. Only PTseconds/Pdays are authored here.
                val seconds = Regex("^PT([0-9]+)S$").matchEntire(raw)?.groupValues?.get(1)?.toLong()
                    ?: Regex("^P([0-9]+)D$").matchEntire(raw)?.groupValues?.get(1)?.toLong()?.let { Math.multiplyExact(it, 86400) }
                    ?: error("Unexpected authored duration")
                require(seconds in 1..Int.MAX_VALUE.toLong())
                inserted.put("native_duration_ms", Math.multiplyExact(seconds, 1000))
            }
            val id = db.insertOrThrow(path, null, inserted)
            if(nativeMeetingMode && path == "events" && values!!.containsKey("selfAttendeeStatus")) {
                db.insertOrThrow("attendees",null,ContentValues().apply {put("event_id",id);put("attendeeEmail","owner@example.invalid");put("attendeeStatus",values.getAsInteger("selfAttendeeStatus"));put("attendeeRelationship",1);put("attendeeType",0)})
            }
            if(nativeMeetingMode && path == "attendees" && inserted.getAsString("attendeeEmail") == "owner@example.invalid")
                db.execSQL("UPDATE events SET selfAttendeeStatus = ? WHERE _id = ?",arrayOf(inserted.getAsInteger("attendeeStatus") ?: if(inserted.getAsInteger("attendeeRelationship")==2)1 else 0,inserted.getAsLong("event_id")))
            if(nativeMeetingMode && path == "reminders")db.execSQL("UPDATE events SET hasAlarm=1 WHERE _id = ?",arrayOf(inserted.getAsLong("event_id")))
            return ContentUris.withAppendedId(uri, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            check(allowUpdates && uri.pathSegments.first() == "events")
            val authored = ContentValues(values)
            authored.getAsString("duration")?.let { raw ->
                val match = Regex("^PT([0-9]+)S$").matchEntire(raw)
                val hours = Regex("^PT([0-9]+)H$").matchEntire(raw)
                val days = Regex("^P([0-9]+)D$").matchEntire(raw)
                val seconds = match?.groupValues?.get(1)?.toLong() ?: hours?.groupValues?.get(1)?.toLong()?.times(3600)
                    ?: days?.groupValues?.get(1)?.toLong()?.times(86400) ?: error("Unsupported synthetic native duration")
                require(seconds in 1..Int.MAX_VALUE.toLong()); authored.put("native_duration_ms", seconds * 1000)
            }
            val id = requireNotNull(uri.lastPathSegment?.toLongOrNull())
            return db.update("events", authored, "_id = ?" + if (selection == null) "" else " AND ($selection)", arrayOf(id.toString(), *args.orEmpty()))
        }
        private fun expanded(uri: Uri, fields: Array<out String>, selection: String?, args: Array<out String>?): Cursor {
            val result = MatrixCursor(fields)
            val low = uri.pathSegments[2].toLong(); val high = uri.pathSegments[3].toLong()
            db.rawQuery("SELECT * FROM events WHERE deleted=0", null).use { cursor -> while (cursor.moveToNext()) {
                val row = cursor.columnNames.indices.associate { column -> cursor.columnNames[column] to if (cursor.isNull(column)) null else cursor.getString(column) }
                if (selection != null && args != null && (args[0] != row["_id"] || args.getOrNull(1)?.let { it != row["calendar_id"] } == true)) continue
                val raw = row["rrule"] ?: continue
                val rule = raw.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
                val count = rule["COUNT"]?.toInt() ?: 10000
                val interval = rule["INTERVAL"]?.toLong() ?: 1
                val zone = ZoneId.of(row["eventTimezone"])
                val seed = Instant.ofEpochMilli(row.getValue("dtstart")!!.toLong()).atZone(zone)
                var emitted = 0
                for (day in 0L..70000L) {
                    val local = seed.plusDays(day)
                    val matches = when (rule["FREQ"]) {
                        "DAILY" -> day % interval == 0L
                        "WEEKLY" -> {
                            val startWeek = seed.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            val weeks = java.time.temporal.ChronoUnit.WEEKS.between(startWeek, local.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                            weeks % interval == 0L && local.dayOfWeek.name.take(2) in (rule["BYDAY"]?.split(',') ?: listOf(seed.dayOfWeek.name.take(2)))
                        }
                        else -> error("Synthetic expanded oracle only models DAILY/WEEKLY")
                    }
                    if (!matches) continue
                    val begin = local.toInstant().toEpochMilli() + beginDelta
                    val until = rule["UNTIL"]
                    if (until != null) {
                        val endpoint = if (until.length == 8) LocalDate.parse(until, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
                            else Instant.from(java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX").parse(until)).toEpochMilli()
                        if (begin > endpoint) break
                    }
                    if (++emitted > count) break
                    val end = begin + row.getValue("native_duration_ms")!!.toLong() + endDelta
                    if (begin >= high) break
                    if (end <= low) continue
                    if (args?.getOrNull(2)?.toLongOrNull()?.let { it != begin } == true) continue
                    result.addRow(fields.map<String, Any?> { field -> when (field) { "event_id" -> row["_id"]; "begin" -> begin; "end" -> end; else -> row[field] } }.toTypedArray())
                }
            } }
            return result
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            check(allowUpdates && uri.pathSegments.first() == "reminders")
            return db.delete("reminders", selection, args)
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batchCount++; beforeBatch?.also { beforeBatch = null }?.invoke(); db.beginTransaction()
            val results = try { super.applyBatch(operations).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            committed = true
            afterCommit?.invoke()
            if (lostAck) error("Synthetic lost acknowledgement")
            if (malformedLastEventAck) results.indexOfLast { it.uri?.pathSegments?.firstOrNull()=="events" }.takeIf { it>=0 }?.let { results[it]=ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/events/not-an-id")) }
            if (malformedAttendeeAck) results[2] = ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/attendees/not-an-id"))
            if (malformedAck) results[1] = ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/events/not-an-id"))
            return results
        }
    }
    private data class Fixture(val p: Provider, val writer: CalendarWriter, val reader: CalendarReader, val store: CalendarLocalStore, val permissions: MutableSet<String>)
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication(); val p = Provider()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val name = "recurring-create-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name); val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarWriter(context.contentResolver, reader, store, permissions::contains)
        try { block(Fixture(p, writer, reader, store, permissions)) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); p.db.close(); context.deleteDatabase(name) }
    }

    @Test fun importedNullZoneTitleAndAlertsKeepRawTemporalAndUnknownFieldsInSqlite() = runBlocking {
        fixture { f ->
            f.p.allowUpdates = true
            val ref = (f.writer.create("seed-null-zone", draft.copy(recurrence = null)) as MutationResult.LocalSaved).ref
            f.p.db.execSQL("ALTER TABLE events ADD COLUMN opaque_imported TEXT")
            f.p.db.execSQL("UPDATE events SET eventTimezone=NULL,eventEndTimezone=NULL,opaque_imported='Synthetic opaque metadata' WHERE _id=?", arrayOf(ref.eventId))
            val before = f.p.db.rawQuery("SELECT dtstart,dtend,duration,allDay,rrule FROM events WHERE _id=?", arrayOf(ref.eventId.toString())).use { c ->
                check(c.moveToFirst()); List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }
            }
            val edit = (f.writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertNull(edit.details.eventZone)
            val outcome = f.writer.update("keep-null-zone", ref, edit.token, EventPatch(title = FieldChange.Set("Edited synthetic title"), alertMinutes = FieldChange.Set(listOf(15))))
            assertTrue(outcome.toString(), outcome is MutationResult.LocalSaved)
            f.p.db.rawQuery("SELECT dtstart,dtend,duration,allDay,rrule,eventTimezone,eventEndTimezone,opaque_imported,title FROM events WHERE _id=?", arrayOf(ref.eventId.toString())).use { c ->
                check(c.moveToFirst()); assertEquals(before, (0..4).map { if (c.isNull(it)) null else c.getString(it) })
                assertTrue(c.isNull(5)); assertTrue(c.isNull(6)); assertEquals("Synthetic opaque metadata", c.getString(7)); assertEquals("Edited synthetic title", c.getString(8))
            }
            f.p.db.rawQuery("SELECT minutes,method FROM reminders WHERE event_id=?", arrayOf(ref.eventId.toString())).use { c ->
                assertEquals(1, c.count); assertTrue(c.moveToFirst()); assertEquals(15, c.getInt(0)); assertEquals(CalendarContract.Reminders.METHOD_ALERT, c.getInt(1))
            }
        }
    }

    @Test fun dailyWeeklyMonthlyAndYearlyCreatesHaveNativeMasterShapeLinkedAlertsAndVerifiedInstance() = runBlocking {
        val specs = listOf(RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(4)),
            RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)), end = RecurrenceEnd.Count(5)),
            RecurrenceSpec(RecurrencePattern.MonthlyDate(17), end = RecurrenceEnd.Count(3)),
            RecurrenceSpec(RecurrencePattern.MonthlyWeekday(3, DayOfWeek.TUESDAY), end = RecurrenceEnd.Count(3)),
            RecurrenceSpec(RecurrencePattern.Yearly(MonthDay.of(11, 17)), end = RecurrenceEnd.Count(2)),
            RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ThroughDate(LocalDate.of(2026, 11, 30))))
        specs.forEach { spec -> fixture { f ->
            val outcome = f.writer.create("new-series", draft.copy(recurrence = spec))
            assertTrue(outcome is MutationResult.LocalSaved)
            val saved = outcome as MutationResult.LocalSaved
            assertNull(f.p.value("dtend")); assertEquals("PT3600S", f.p.value("duration")); assertNotNull(f.p.value("rrule"))
            assertEquals(start.toEpochMilli().toString(), f.p.value("dtstart")); assertEquals("Europe/London", f.p.value("eventTimezone"))
            assertEquals(start, saved.ref.instanceStart); assertEquals(start.plusSeconds(3600), saved.ref.instanceEnd)
            assertEquals(RecurrenceRef(saved.ref.eventId, start), saved.ref.recurrence)
            assertTrue(f.reader.readDetails(saved.ref) is CalendarReadState.Ready)
            val seriesReader = CalendarSeriesReader(RuntimeEnvironment.getApplication().contentResolver, f.reader, f.store, f.permissions::contains)
            assertTrue(seriesReader.loadForEdit(saved.ref) is SeriesReadResult.Ready)
            val operation = f.store.operation("new-series")!!
            assertEquals("VERIFIED", operation.phase)
            val receipt = JSONObject(operation.resultRef!!)
            assertEquals(3, receipt.getInt("version")); assertEquals(saved.ref.eventId, receipt.getLong("master"))
            assertTrue(receipt.has("firstRef")); assertFalse(receipt.has("ref"))
            assertEquals(2, f.p.count("reminders"))
            f.p.db.rawQuery("SELECT COUNT(*) FROM reminders WHERE event_id = ?", arrayOf(saved.ref.eventId.toString())).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            assertEquals(1, f.p.batchCount)
        } }
    }

    @Test fun allDayUsesUtcDateAndNominalWholeDaysWithoutDtend() = runBlocking {
        fixture { f ->
            val time = EventTime.AllDay(CalendarDateRange(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 20)))
            val saved = f.writer.create("all-day", draft.copy(time = time, eventZone = null)) as MutationResult.LocalSaved
            assertEquals("P3D", f.p.value("duration")); assertEquals("UTC", f.p.value("eventTimezone")); assertNull(f.p.value("dtend"))
            assertEquals(time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant(), saved.ref.instanceStart)
            assertEquals(time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant(), saved.ref.instanceEnd)
        }
    }

    @Test fun patternMismatchDstPreviewAndDurationOverflowRefuseBeforeJournalOrNativeInsert() = runBlocking {
        val invalid = listOf(draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.MONDAY)))),
            draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, interval = 1001)),
            draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(10001))),
            draft.copy(time = EventTime.Timed(start, start.plusSeconds(Int.MAX_VALUE.toLong() + 1))),
            draft.copy(time = EventTime.Timed(start.plusNanos(1), start.plusSeconds(3600))),
            draft.copy(time = EventTime.Timed(Instant.parse("2026-03-28T01:30:00Z"), Instant.parse("2026-03-28T02:30:00Z")),
                recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(3))))
        invalid.forEach { input -> fixture { f ->
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.RECURRENCE)), f.writer.create("invalid", input))
            assertNull(f.store.operation("invalid")); assertEquals(0, f.p.batchCount); assertEquals(0, f.p.count("events"))
        } }
    }

    @Test fun explicitFoldInstantIsPreservedForSingleOccurrenceRule() = runBlocking {
        fixture { f ->
            val late = Instant.parse("2026-10-25T01:30:00Z")
            val input = draft.copy(time = EventTime.Timed(late, late.plusSeconds(3600)), recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(1)))
            val saved = f.writer.create("fold", input) as MutationResult.LocalSaved
            assertEquals(late, saved.ref.instanceStart); assertEquals("PT3600S", f.p.value("duration"))
        }
    }

    @Test fun delayedExpansionAndWrongNativeEndStayKnownUncertainUntilReadOnlyVerification() = runBlocking {
        fixture { f ->
            f.p.delayedInstances = true
            val pending = f.writer.create("delayed", draft) as MutationResult.Uncertain
            assertNotNull(pending.knownEventId)
            val receipt = JSONObject(f.store.operation("delayed")!!.resultRef!!)
            assertEquals("COMMITTED", f.store.operation("delayed")!!.phase); assertFalse(receipt.has("firstRef")); assertFalse(receipt.has("ref"))
            f.p.delayedInstances = false; f.p.endDelta = 60_000
            assertTrue(f.writer.recover("delayed") is MutationResult.Uncertain)
            f.p.endDelta = 0; f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val saved = f.writer.recover("delayed") as MutationResult.LocalSaved
            assertEquals(pending.knownEventId, saved.ref.eventId); assertEquals(start.plusSeconds(3600), saved.ref.instanceEnd)
            assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun unexpectedNativeBeginCannotBecomeAFabricatedSeedReference() = runBlocking {
        fixture { f ->
            f.p.beginDelta = -60_000
            val result = f.writer.create("wrong-begin", draft) as MutationResult.Uncertain
            assertNotNull(result.knownEventId); assertEquals("COMMITTED", f.store.operation("wrong-begin")!!.phase)
            assertFalse(JSONObject(f.store.operation("wrong-begin")!!.resultRef!!).has("firstRef"))
            assertEquals(1, f.p.count("events"))
        }
    }

    @Test fun lostOrMalformedAcknowledgementNeverLooksUpMatchingFieldsOrReplaysCreate() = runBlocking {
        for (malformed in listOf(false, true)) fixture { f ->
            f.p.lostAck = !malformed; f.p.malformedAck = malformed
            assertEquals(MutationResult.Uncertain("lost", null), f.writer.create("lost", draft))
            assertEquals("ATTEMPTED", f.store.operation("lost")!!.phase)
            f.p.queries.clear()
            assertEquals(MutationResult.Uncertain("lost", null), f.writer.recover("lost"))
            assertEquals(MutationResult.Uncertain("lost", null), f.writer.create("lost", draft))
            assertTrue(f.p.queries.all { it.path == "calendars" })
            assertEquals(1, f.p.count("events")); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun knownCommittedProofSurvivesReopenAndNamespaceReplacementCannotAcknowledgeForeignCalendar() = runBlocking {
        fixture { f ->
            f.p.failReadsAfterCommit = true
            val pending = f.writer.create("committed", draft) as MutationResult.Uncertain
            assertNotNull(pending.knownEventId); assertEquals("COMMITTED", f.store.operation("committed")!!.phase)
            f.p.failReadsAfterCommit = false
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), f.store.databaseName)
            try {
                val other = CalendarWriter(RuntimeEnvironment.getApplication().contentResolver, f.reader, reopened, f.permissions::contains)
                f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
                assertTrue(other.recover("committed") is MutationResult.LocalSaved)
                f.p.setCalendar("_sync_id", "calendar-B"); f.p.queries.clear()
                assertTrue(other.recover("committed") is MutationResult.Uncertain)
                assertEquals(listOf("calendars"), f.p.queries.map { it.path })
                assertEquals(1, f.p.batchCount)
            } finally { reopened.close() }
        }
    }

    @Test fun typedSpecChangesConflictWhileReminderRollbackCanRetryOnlyOriginalCommand() = runBlocking {
        fixture { f ->
            f.p.failReminder = true
            assertEquals(MutationResult.Unavailable, f.writer.create("retry", draft))
            assertEquals(0, f.p.count("events")); assertEquals(0, f.p.count("reminders"))
            assertEquals(MutationResult.SafeToRetry("retry"), f.writer.recover("retry"))
            f.p.failReminder = false
            assertTrue(f.writer.create("retry", draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(5)))) is MutationResult.Conflict)
            assertTrue(f.writer.create("retry", draft.copy(recurrence = null)) is MutationResult.Conflict)
            assertTrue(f.writer.create("retry", draft) is MutationResult.LocalSaved)
            assertEquals(2, f.p.batchCount)
        }
    }

    @Test fun calendarAssertionsPermissionChecksAndNativeSqlConstraintPreventUnsafeCreates() = runBlocking {
        fixture { f ->
            f.p.beforeBatch = { f.p.setCalendar("account_name", "replacement@example.invalid") }
            assertEquals(MutationResult.Unavailable, f.writer.create("namespace-race", draft))
            assertEquals(0, f.p.count("events")); assertEquals("FAILED", f.store.operation("namespace-race")!!.phase)
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.CALENDAR)), f.writer.create("namespace-race", draft))
        }
        fixture { f ->
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.PermissionRequired, f.writer.create("permission", draft)); assertEquals(0, f.p.batchCount)
        }
        fixture { f ->
            try {
                f.p.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply { put("calendar_id", 2); put("dtstart", start.toEpochMilli()); put("dtend", start.plusSeconds(3600).toEpochMilli()); put("rrule", "FREQ=DAILY"); put("duration", "PT3600S") })
                fail("Native recurring row must not accept DTEND")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(0, f.p.count("events"))
        }
    }

    @Test fun nullRecurrenceKeepsLegacyJsonSignatureAndVersionTwoReceipt() = runBlocking {
        fixture { f ->
            val oneOff = draft.copy(recurrence = null)
            val saved = f.writer.create("oneoff", oneOff) as MutationResult.LocalSaved
            assertNull(saved.ref.recurrence)
            val receipt = JSONObject(f.store.operation("oneoff")!!.resultRef!!)
            assertEquals(2, receipt.getInt("version")); assertTrue(receipt.has("ref")); assertFalse(receipt.has("provider"))
            val payload = JSONObject().put("calendar", 2).put("event", 0).put("start", start.toString()).put("end", start.plusSeconds(3600).toString())
                .put("allDay", false).put("zone", "Europe/London").put("title", oneOff.title).put("location", oneOff.location).put("description", oneOff.description)
                .put("alerts", org.json.JSONArray(oneOff.alertMinutes)).put("availability", 1).put("privacy", 2)
                .put("targetIdentity", JSONObject().put("calendar", 2).put("name", identity.account.name).put("type", identity.account.type).put("owner", identity.owner).put("providerId", identity.providerId))
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(("CREATE" + payload.toString()).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(expected, f.store.operation("oneoff")!!.signature)
            assertEquals(payload.toString(), receipt.getJSONObject("command").toString())
        }
    }
}
