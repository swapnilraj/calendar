package dev.swapnilraj.calendar

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import java.time.*

/** A26 native SQL/derived mirror fixture, with F41's typed temporary-SQL Instances filtering.
 * Real assertions/transactions/URI predicates; only bounded DAILY/WEEKLY synthetic expansion.
 * No opaque fields/date sets/exceptions: this is the backend's admitted owned clone profile,
 * not evidence of Google synchronization or Android CalendarProvider recurrence expansion. */
internal class NativeFollowingJourneyProvider : ContentProvider() {
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
        if(rejectEventUidProjection && path=="events" && fields?.contains("uid2445")==true)throw IllegalArgumentException("Synthetic missing uid2445 projection")
        if (path == "instances" && delayedInstances) return MatrixCursor(fields!!)
        if (path == "instances" && expandInstances) return expanded(uri, fields!!, selection, args, sort)
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
        val result = db.query(if (path == "instances") "instance_view" else path, effective, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
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
    private fun expanded(uri: Uri, fields: Array<out String>, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val low = uri.pathSegments[2].toLong(); val high = uri.pathSegments[3].toLong()
        val columnTypes = db.rawQuery("PRAGMA table_info(events)", null).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(2))
        } }
        val expanded = SQLiteDatabase.create(null)
        try {
            expanded.execSQL("CREATE TABLE occurrences (event_id INTEGER,begin INTEGER,end INTEGER," +
                columnTypes.entries.joinToString(",") { (key, type) -> "\"$key\" $type" } + ")")
            db.rawQuery("SELECT * FROM events WHERE deleted=0 AND (eventStatus IS NULL OR eventStatus != 2)", null).use { cursor -> while (cursor.moveToNext()) {
                val row = cursor.columnNames.indices.associate { column -> cursor.columnNames[column] to if (cursor.isNull(column)) null else cursor.getString(column) }
                val raw = row["rrule"] ?: continue
                val rule = raw.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
                val count = rule["COUNT"]?.toInt() ?: 10000
                val interval = rule["INTERVAL"]?.toLong() ?: 1
                val seed = Instant.ofEpochMilli(row.getValue("dtstart")!!.toLong()).atZone(ZoneId.of(row["eventTimezone"]))
                var emitted = 0
                for (day in 0L..70000L) {
                    val local = seed.plusDays(day)
                    val matches = when (rule["FREQ"]) {
                        "DAILY" -> day % interval == 0L
                        "WEEKLY" -> {
                            val week = java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
                            val weeks = java.time.temporal.ChronoUnit.WEEKS.between(seed.toLocalDate().with(week), local.toLocalDate().with(week))
                            weeks % interval == 0L && local.dayOfWeek.name.take(2) in (rule["BYDAY"]?.split(',') ?: listOf(seed.dayOfWeek.name.take(2)))
                        }
                        else -> error("Synthetic Instances only model DAILY/WEEKLY")
                    }
                    if (!matches) continue
                    val begin = local.toInstant().toEpochMilli() + beginDelta
                    val until = rule["UNTIL"]?.let { value -> if (value.length == 8) LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
                        else Instant.from(java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX").parse(value)).toEpochMilli() }
                    if (until != null && begin > until || ++emitted > count || begin >= high) break
                    val end = begin + row.getValue("native_duration_ms")!!.toLong() + endDelta
                    if (end <= low) continue
                    expanded.insertOrThrow("occurrences", null, ContentValues().apply {
                        row.forEach { (key, value) -> when {
                            value == null -> putNull(key)
                            columnTypes[key].equals("INTEGER", true) -> put(key, value.toLong())
                            else -> put(key, value)
                        } }
                        put("event_id", row.getValue("_id")!!.toLong()); put("begin", begin); put("end", end)
                    })
                }
            } }
            return expanded.query("occurrences", fields, selection, args, null, null, sort).use { cursor ->
                MatrixCursor(cursor.columnNames).apply { while (cursor.moveToNext()) addRow(cursor.columnNames.indices.map { index -> when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index); Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index); Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index); else -> null
                } }.toTypedArray()) }
            }
        } finally { expanded.close() }
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

    init {
        nativeMeetingMode = true; allowUpdates = true; expandInstances = true
        db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")
        setCalendar("calendar_timezone", "UTC")
    }
    fun seed(start: Instant, title: String, rule: String) {
        insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put("calendar_id", 2); put("dtstart", start.toEpochMilli()); putNull("dtend")
            put("duration", "PT3600S"); put("rrule", rule); put("allDay", 0)
            put("eventTimezone", "UTC"); put("eventEndTimezone", "UTC"); put("title", title)
            put("eventLocation", "Synthetic room"); put("description", "Synthetic notes")
            put("availability", 1); put("accessLevel", 2); put("hasAttendeeData", 0)
        })
        db.execSQL("UPDATE events SET _sync_id='prefix-sync',uid2445='prefix-public-uid'")
        insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply { put("event_id",1); put("minutes",10); put("method",1) })
        insertedEventValues.clear()
    }
    fun snapshot(table: String): List<Map<String, String?>> = db.rawQuery("SELECT * FROM $table ORDER BY _id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.columnNames.filter { it != "native_duration_ms" }.associateWith { key ->
            cursor.getColumnIndexOrThrow(key).let { if (cursor.isNull(it)) null else cursor.getString(it) }
        }) }
    }
}
