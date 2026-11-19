package dev.swapnilraj.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import java.time.Instant
import org.robolectric.RuntimeEnvironment

/** Real SQLite selections and non-yielding transactions, including CalendarProvider attendee-view
 * joined self status and attendee-item selection restriction. One synthetic ordinary invitation. */
internal class NativeRsvpJourneyProvider(start: Instant) : ContentProvider() {
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
        if (RuntimeEnvironment.getApplication().checkSelfPermission(Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("Synthetic READ denied")
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
            val fields = projection!!.map { when (it) { "event_id" -> "_id AS event_id"; "begin" -> "dtstart AS begin"; "end" -> "dtend AS end"; else -> project("events", arrayOf(it)).single() } }.toTypedArray()
            return db.query("events", fields, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort?.replace("begin", "dtstart")?.replace("event_id", "_id"))
        }
        val result = db.query(if (path == "attendees") "attendee_view" else path, projection?.let { project(if (path == "attendees") "attendee_view" else path, it) }, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
        // Materialize the fixture cursor before simulating a concurrent provider change.
        result.count
        afterQuery?.invoke(path, selection)
        return result
    }
    private fun project(table: String, fields: Array<out String>): Array<String> {
        val columns = db.rawQuery("SELECT * FROM $table LIMIT 0", null).use { it.columnNames.toSet() }
        return fields.map { if (it in columns) it else "NULL AS \"$it\"" }.toTypedArray()
    }
    fun snapshot(table: String): List<Map<String, String?>> = db.query(table, null, null, null, null, null, "_id ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.columnNames.associateWith { name -> cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getString(it) } }) }
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
