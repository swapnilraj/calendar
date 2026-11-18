package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.swapnilraj.calendar.core.DraftRecord
import dev.swapnilraj.calendar.core.DraftStore
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private application data only. Provider rows remain the source of calendar events. */
class CalendarLocalStore(
    context: Context,
    databaseName: String = "daylight-local.db",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1), DraftStore {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // ATTEMPTED must be durably committed before a separate provider transaction begins.
        // Future WAL/pool reconfiguration must preserve this setting. SQLite's crash guarantees
        // cannot compensate for hardware or filesystems that do not honour flush requests.
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE drafts (id TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL, updated_epoch INTEGER NOT NULL, updated_nano INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE edit_snapshots (token TEXT PRIMARY KEY, snapshot TEXT NOT NULL)")
        db.execSQL("CREATE TABLE mutation_journal (id TEXT PRIMARY KEY, signature TEXT NOT NULL, kind TEXT NOT NULL, phase TEXT NOT NULL, result_ref TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive fallback: future schema versions must provide explicit migrations.
        error("No migration from $oldVersion to $newVersion")
    }

    override suspend fun load(id: String): DraftRecord? = withContext(io) {
        readableDatabase.query("drafts", null, "id = ?", arrayOf(id), null, null, null).use {
            if (!it.moveToFirst()) null else DraftRecord(it.getString(it.getColumnIndexOrThrow("id")),
                it.getInt(it.getColumnIndexOrThrow("version")), it.getString(it.getColumnIndexOrThrow("payload")),
                Instant.parse(it.getString(it.getColumnIndexOrThrow("updated_at"))))
        }
    }

    override suspend fun list(): List<DraftRecord> = withContext(io) {
        readableDatabase.query("drafts", null, null, null, null, null, "updated_epoch DESC, updated_nano DESC, id ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(DraftRecord(cursor.getString(cursor.getColumnIndexOrThrow("id")),
                cursor.getInt(cursor.getColumnIndexOrThrow("version")), cursor.getString(cursor.getColumnIndexOrThrow("payload")),
                Instant.parse(cursor.getString(cursor.getColumnIndexOrThrow("updated_at"))))) }
        }
    }

    override suspend fun upsert(record: DraftRecord) = withContext(io) {
        writableDatabase.insertWithOnConflict("drafts", null, ContentValues().apply {
            put("id", record.id); put("version", record.version); put("payload", record.payload)
            put("updated_at", record.updatedAt.toString()); put("updated_epoch", record.updatedAt.epochSecond); put("updated_nano", record.updatedAt.nano)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        Unit
    }

    override suspend fun delete(id: String) = withContext(io) {
        writableDatabase.delete("drafts", "id = ?", arrayOf(id))
        Unit
    }

    internal fun saveEdit(token: String, snapshot: String) {
        writableDatabase.insertWithOnConflict("edit_snapshots", null, ContentValues().apply {
            put("token", token); put("snapshot", snapshot)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    internal fun edit(token: String): String? = readableDatabase.query("edit_snapshots", arrayOf("snapshot"),
        "token = ?", arrayOf(token), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }

    internal data class Operation(val id: String, val signature: String, val kind: String, val phase: String, val resultRef: String?)

    internal fun operation(id: String): Operation? = readableDatabase.query("mutation_journal", null,
        "id = ?", arrayOf(id), null, null, null).use { cursor ->
        if (!cursor.moveToFirst()) null else Operation(id, cursor.getString(cursor.getColumnIndexOrThrow("signature")),
            cursor.getString(cursor.getColumnIndexOrThrow("kind")), cursor.getString(cursor.getColumnIndexOrThrow("phase")),
            cursor.getString(cursor.getColumnIndexOrThrow("result_ref")))
    }

    internal fun prepare(id: String, signature: String, kind: String) {
        writableDatabase.insertWithOnConflict("mutation_journal", null, ContentValues().apply {
            put("id", id); put("signature", signature); put("kind", kind); put("phase", "PREPARED")
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Compare-and-set means two writer instances cannot both submit the same operation. */
    internal fun attempt(id: String): Boolean = writableDatabase.update("mutation_journal", ContentValues().apply {
        put("phase", "ATTEMPTED")
    }, "id = ? AND phase IN (?, ?)", arrayOf(id, "PREPARED", "FAILED")) == 1

    internal fun mark(id: String, phase: String, resultRef: String? = null) {
        check(writableDatabase.update("mutation_journal", ContentValues().apply {
            put("phase", phase); if (resultRef != null) put("result_ref", resultRef)
        }, "id = ?", arrayOf(id)) == 1)
    }

    internal fun unresolved(): List<Operation> = readableDatabase.query("mutation_journal", arrayOf("id"),
        "phase IN (?, ?)", arrayOf("ATTEMPTED", "COMMITTED"), null, null, null).use { cursor ->
        buildList { while (cursor.moveToNext()) operation(cursor.getString(0))?.let(::add) }
    }
}
