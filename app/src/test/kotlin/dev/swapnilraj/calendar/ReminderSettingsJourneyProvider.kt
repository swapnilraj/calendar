package dev.swapnilraj.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.time.Instant

/** Dedicated SQLite fixture; actual predicates/ID URI constraints and optional NULL projections.
 * One explicit nonrecurring Instances row; not a sync server or recurrence engine. */
internal class ReminderSettingsJourneyProvider(start: Instant, title: String) : ContentProvider() {
    private val db = SQLiteDatabase.create(null)
    private val columns = mutableMapOf<String, Set<String>>()
    @Volatile var detailReads = 0
    @Volatile var writeAttempts = 0
    init {
        table("calendars", mapOf("_id" to 3L, "account_name" to "fixture@example.invalid", "account_type" to "com.google", "ownerAccount" to "fixture@example.invalid",
            "_sync_id" to "fixture-calendar-A", "calendar_access_level" to 700L, "visible" to 1L, "sync_events" to 1L,
            "calendar_displayName" to "Synthetic reminder calendar", "calendar_color" to 123L, "calendar_timezone" to "UTC"))
        table("events", mapOf("_id" to 7L, "calendar_id" to 3L, "dtstart" to start.toEpochMilli(), "dtend" to start.plusSeconds(3600).toEpochMilli(),
            "title" to title, "description" to "Old synthetic notes", "eventLocation" to "Synthetic room", "allDay" to 0L, "eventTimezone" to "UTC",
            "eventEndTimezone" to "UTC", "eventStatus" to 1L, "deleted" to 0L, "selfAttendeeStatus" to 0L, "displayColor" to 456L,
            "rrule" to null, "rdate" to null, "exrule" to null, "exdate" to null, "original_id" to null, "original_sync_id" to null,
            "originalInstanceTime" to null, "uid2445" to "fixture-event-uid", "isOrganizer" to 1L, "organizer" to "fixture@example.invalid",
            "hasAttendeeData" to 0L, "guestsCanSeeGuests" to 1L, "accessLevel" to 2L, "availability" to 0L))
        table("reminders", mapOf("_id" to 30L, "event_id" to 7L, "minutes" to 10L, "method" to 1L))
        table("attendees", mapOf("_id" to null, "event_id" to null, "attendeeEmail" to null)); db.delete("attendees", null, null)
    }
    private fun table(name: String, values: Map<String, Any?>) {
        columns[name] = values.keys
        val numeric = setOf("original_id", "originalInstanceTime", "event_id", "_id")
        db.execSQL("CREATE TABLE $name (" + values.entries.joinToString(",") { (key, value) -> "\"$key\" " + if (value is Number || key in numeric) "INTEGER" else "TEXT" } + ")")
        db.insertOrThrow(name, null, ContentValues().apply { values.forEach { (key, value) -> when (value) {
            null -> putNull(key); is Number -> put(key, value.toLong()); else -> put(key, value.toString())
        } } })
    }
    fun setEvent(key: String, value: String) { db.update("events", ContentValues().apply { put(key, value) }, "_id = 7", null) }
    fun replaceNamespace() { db.update("calendars", ContentValues().apply {
        put("_sync_id", "fixture-calendar-B"); put("account_name", "replacement@example.invalid"); put("ownerAccount", "replacement@example.invalid")
    }, "_id = 3", null) }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        if (org.robolectric.RuntimeEnvironment.getApplication().checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("Synthetic READ denial")
        val path = uri.pathSegments.first()
        val instance = path == "instances"
        val known = if (instance) columns.getValue("events") + setOf("event_id", "begin", "end") else columns.getValue(path)
        val projected = projection?.map { if (it in known) "\"$it\"" else "NULL AS \"$it\"" }?.toTypedArray()
        val table = if (instance) "(SELECT *, _id AS event_id, dtstart AS begin, dtend AS end FROM events)" else path
        val where = mutableListOf<String>(); val bind = mutableListOf<String>()
        if (instance) {
            where += "begin < ? AND (end > ? OR (begin = end AND begin >= ?)) AND deleted = 0 AND eventStatus != 2"
            bind += listOf(uri.pathSegments[3], uri.pathSegments[2], uri.pathSegments[2])
        } else uri.lastPathSegment?.toLongOrNull()?.let { id ->
            where += "_id = ?"; bind += "$id"
            if (path == "events") detailReads++
        }
        if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
        return db.query(table, projected, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = refusedWrite()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = refusedWrite()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = refusedWrite()
    private fun refusedWrite(): Nothing { writeAttempts++; error("Settings must never mutate provider data") }
    fun close() = db.close()
}
