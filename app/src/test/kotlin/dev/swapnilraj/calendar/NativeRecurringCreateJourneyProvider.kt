package dev.swapnilraj.calendar

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract

/** Copied from A22's native SQL fixture: real URI predicates/transaction assertions and native
 * DTEND-null/DURATION constraint. Instances projects only the first occurrence using elapsed
 * duration; this is not Google sync or a full recurrence expansion engine. */
internal class NativeRecurringCreateJourneyProvider : ContentProvider() {
    val db = SQLiteDatabase.create(null)
    data class Query(val path: String, val fields: List<String>?, val selection: String?)
    val queries = java.util.Collections.synchronizedList(mutableListOf<Query>())
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
        db.execSQL("CREATE TABLE attendees (_id INTEGER,event_id INTEGER,attendeeName TEXT,attendeeEmail TEXT,attendeeStatus INTEGER,attendeeRelationship INTEGER,attendeeType INTEGER,attendeeIdentity TEXT,attendeeIdNamespace TEXT)")
        db.execSQL("CREATE TABLE extendedproperties (_id INTEGER,event_id INTEGER,name TEXT,value TEXT)")
        db.execSQL("CREATE VIEW instance_view AS SELECT _id AS event_id,dtstart AS begin,COALESCE(dtend,dtstart+native_duration_ms) AS end,e.* FROM events e WHERE deleted=0")
    }
    fun setCalendar(column: String, value: String?) = db.update("calendars", ContentValues().apply { if (value == null) putNull(column) else put(column, value) }, "_id=2", null)
    fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    fun value(column: String): String? = db.query("events", arrayOf(column), null, null, null, null, null).use { check(it.moveToFirst()); it.getString(0) }
    private fun requirePermission(permission: String) {
        if (org.robolectric.RuntimeEnvironment.getApplication().checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("Synthetic permission denial")
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        if (failReadsAfterCommit && committed) error("Synthetic post-commit read failure")
        requirePermission(android.Manifest.permission.READ_CALENDAR)
        val path = uri.pathSegments.first(); queries += Query(path, fields?.toList(), selection)
        if (path == "instances" && delayedInstances) return MatrixCursor(fields!!)
        val where = mutableListOf<String>(); val bind = mutableListOf<String>()
        if (path != "instances") uri.lastPathSegment?.toLongOrNull()?.let { where += "_id = ?"; bind += it.toString() }
        if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
        val projected = if (path == "instances") fields?.map { when (it) {
            "begin" -> "(begin+$beginDelta) AS begin"; "end" -> "(end+$endDelta) AS end"; else -> it
        } }?.toTypedArray() else fields
        if (path == "instances") { where += "(begin+$beginDelta) < CAST(? AS INTEGER) AND (end+$endDelta) > CAST(? AS INTEGER)"; bind += uri.pathSegments[3]; bind += uri.pathSegments[2] }
        return db.query(if (path == "instances") "instance_view" else path, projected, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val path = uri.pathSegments.first()
        if (path == "reminders" && failReminder) throw OperationApplicationException("Synthetic reminder rollback")
        check(path == "events" || path == "reminders")
        val inserted = ContentValues(values)
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
        return ContentUris.withAppendedId(uri, id)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Create/recover must not update")
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Create/recover must not delete")
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        requirePermission(android.Manifest.permission.WRITE_CALENDAR)
        batchCount++; beforeBatch?.also { beforeBatch = null }?.invoke(); db.beginTransaction()
        val results = try { super.applyBatch(operations).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
        committed = true
        if (lostAck) error("Synthetic lost acknowledgement")
        if (malformedAck) results[1] = ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/events/not-an-id"))
        return results
    }
}
