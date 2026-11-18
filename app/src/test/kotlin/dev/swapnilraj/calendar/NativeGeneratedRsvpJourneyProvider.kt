package dev.swapnilraj.calendar

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import dev.swapnilraj.calendar.data.calendar.CalendarMeetingSeriesWriterTest
import java.time.Instant
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Reuses the existing synthetic native exception/SQLite transaction model. Projection fallback
 * supports the real app's optional UI columns; raw audit projections remain completely unchanged. */
internal class NativeGeneratedRsvpJourneyProvider(start: Instant) : ContentProvider() {
    val native = CalendarMeetingSeriesWriterTest.Provider(start).apply { enableGenerated() }
    val db get() = native.db
    val batchCount get() = native.batchCount
    var revokeReadAfterCommit = false
    private fun requirePermission(permission: String) {
        if (RuntimeEnvironment.getApplication().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Synthetic permission denied")
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        requirePermission(Manifest.permission.READ_CALENDAR)
        val table = when (uri.pathSegments.first()) { "instances" -> "instance_view"; "attendees" -> "attendee_view"; else -> uri.pathSegments.first() }
        val columns = db.rawQuery("SELECT * FROM $table LIMIT 0", null).use { it.columnNames.toSet() }
        val compatible = projection?.map { if (it in columns) it else "NULL AS \"$it\"" }?.toTypedArray()
        return native.query(uri, compatible, selection, args, sort)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        return native.insert(uri, values)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        return native.update(uri, values, selection, args)
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        return native.delete(uri, selection, args)
    }
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        requirePermission(Manifest.permission.READ_CALENDAR)
        requirePermission(Manifest.permission.WRITE_CALENDAR)
        val result = native.applyBatch(operations)
        if (revokeReadAfterCommit) shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)
        return result
    }
    fun snapshot(table: String) = db.rawQuery("SELECT * FROM $table ORDER BY " + if (table == "instances") "event_id,begin" else "_id", null).use { c ->
        buildList { while (c.moveToNext()) add(c.columnNames.associateWith { name -> c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) } }) }
    }
}
