package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private application data only. Provider rows remain the source of calendar events. */
class CalendarLocalStore(
    context: Context,
    databaseName: String = "daylight-local.db",
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 2), AtomicDraftStore {
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
        createReminderSchema(db)
    }

    private fun createReminderSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE reminder_ledger (
            record_key TEXT PRIMARY KEY, calendar_id INTEGER NOT NULL,
            source_fingerprint TEXT NOT NULL, source_provider_uid TEXT,
            payload TEXT NOT NULL, state TEXT NOT NULL,
            effective_seconds INTEGER NOT NULL, effective_nanos INTEGER NOT NULL,
            planned_seconds INTEGER NOT NULL, planned_nanos INTEGER NOT NULL,
            last_posted_seconds INTEGER, notification_nonce TEXT NOT NULL UNIQUE)""")
        db.execSQL("CREATE INDEX reminder_due ON reminder_ledger (state, effective_seconds, effective_nanos, record_key)")
        db.execSQL("CREATE INDEX reminder_calendar ON reminder_ledger (calendar_id, source_fingerprint)")
        db.execSQL("CREATE INDEX reminder_history ON reminder_ledger (planned_seconds, planned_nanos)")
        // Retain consumed aliases even after a generation is replaced, so no stale action can
        // become current again through an accidentally reused nonce.
        db.execSQL("CREATE TABLE reminder_nonce_registry (nonce TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) createReminderSchema(db)
        else error("No migration from $oldVersion to $newVersion")
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

    override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult = withContext(io) {
        require(expected != null || replacement != null)
        require(expected == null || expected.id == id)
        require(replacement == null || replacement.id == id)
        try {
            currentCoroutineContext().ensureActive()
            val db = writableDatabase
            db.beginTransaction()
            try {
                val current = db.query("drafts", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val timestamp = Instant.parse(cursor.getString(cursor.getColumnIndexOrThrow("updated_at")))
                        check(cursor.getLong(cursor.getColumnIndexOrThrow("updated_epoch")) == timestamp.epochSecond &&
                            cursor.getInt(cursor.getColumnIndexOrThrow("updated_nano")) == timestamp.nano)
                        DraftRecord(cursor.getString(cursor.getColumnIndexOrThrow("id")), cursor.getInt(cursor.getColumnIndexOrThrow("version")),
                            cursor.getString(cursor.getColumnIndexOrThrow("payload")), timestamp)
                    }
                }
                val result = if (current != expected) DraftChangeResult.Changed else {
                    if (replacement == null) check(db.delete("drafts", "id = ?", arrayOf(id)) == 1)
                    else {
                        val values = ContentValues().apply {
                            put("id", replacement.id); put("version", replacement.version); put("payload", replacement.payload)
                            put("updated_at", replacement.updatedAt.toString()); put("updated_epoch", replacement.updatedAt.epochSecond)
                            put("updated_nano", replacement.updatedAt.nano)
                        }
                        if (current == null) db.insertOrThrow("drafts", null, values)
                        else check(db.update("drafts", values, "id = ?", arrayOf(id)) == 1)
                    }
                    DraftChangeResult.Applied
                }
                currentCoroutineContext().ensureActive()
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DraftChangeResult.Unavailable }
    }

    internal fun saveEdit(token: String, snapshot: String) {
        checkStoredJson(snapshot, readBudgets.maxCanonicalBytes)
        writableDatabase.insertWithOnConflict("edit_snapshots", null, ContentValues().apply {
            put("token", token); put("snapshot", snapshot)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun checkStoredLength(table: String, column: String, key: String, id: String, maxBytes: Int = readBudgets.maxCanonicalBytes) {
        readableDatabase.query(table, arrayOf("length(CAST($column AS BLOB))"), "$key = ?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst() && !it.isNull(0) && it.getLong(0) > maxBytes) throw ProviderReadLimitExceeded()
        }
    }

    internal fun edit(token: String): String? {
        checkStoredLength("edit_snapshots", "snapshot", "token", token)
        return readableDatabase.query("edit_snapshots", arrayOf("snapshot"), "token = ?", arrayOf(token), null, null, null).use {
            if (!it.moveToFirst()) null else it.getString(0).also { value -> checkStoredJson(value, readBudgets.maxCanonicalBytes) }
        }
    }

    internal data class Operation(val id: String, val signature: String, val kind: String, val phase: String, val resultRef: String?)

    internal fun operation(id: String): Operation? {
        checkStoredLength("mutation_journal", "CASE WHEN phase = 'ABANDONED' THEN NULL ELSE result_ref END", "id", id)
        listOf("signature", "kind", "phase").forEach { checkStoredLength("mutation_journal", it, "id", id, readBudgets.mutation.maxCellUnits) }
        return readableDatabase.query("mutation_journal", arrayOf("id", "signature", "kind", "phase", "CASE WHEN phase = 'ABANDONED' THEN NULL ELSE result_ref END AS result_ref"),
        "id = ?", arrayOf(id), null, null, null).use { cursor ->
        if (!cursor.moveToFirst()) null else Operation(id, cursor.getString(cursor.getColumnIndexOrThrow("signature")),
            cursor.getString(cursor.getColumnIndexOrThrow("kind")), cursor.getString(cursor.getColumnIndexOrThrow("phase")),
            cursor.getString(cursor.getColumnIndexOrThrow("result_ref"))?.also { checkStoredJson(it, readBudgets.maxCanonicalBytes) })
        }
    }

    internal fun prepare(id: String, signature: String, kind: String, metadata: String? = null) {
        metadata?.let { checkStoredJson(it, readBudgets.maxCanonicalBytes) }
        writableDatabase.insertWithOnConflict("mutation_journal", null, ContentValues().apply {
            put("id", id); put("signature", signature); put("kind", kind); put("phase", "PREPARED")
            metadata?.let { put("result_ref", it) }
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    internal data class OperationHeader(val id: String, val signature: String, val kind: String, val phase: String)
    /** Retirement/fence checks never materialize or validate the opaque provider receipt. */
    internal fun operationHeader(id: String): OperationHeader? {
        listOf("signature", "kind", "phase").forEach { checkStoredLength("mutation_journal", it, "id", id, readBudgets.mutation.maxCellUnits) }
        return readableDatabase.query("mutation_journal", arrayOf("signature", "kind", "phase"), "id = ?", arrayOf(id), null, null, null).use {
            if (!it.moveToFirst()) null else OperationHeader(id, it.getString(0), it.getString(1), it.getString(2))
        }
    }

    /** Exact request and prior phase are required across independent writer/helper instances. */
    internal fun attempt(expected: Operation): Boolean {
        if (expected.phase !in setOf("PREPARED", "FAILED")) return false
        return writableDatabase.update("mutation_journal", ContentValues().apply { put("phase", "ATTEMPTED") },
            "id = ? AND signature = ? AND kind = ? AND phase = ? AND result_ref IS ?",
            arrayOf(expected.id, expected.signature, expected.kind, expected.phase, expected.resultRef)) == 1
    }

    /** ABANDONED is permanent. A stale failure/verification can never resurrect any terminal row. */
    internal fun mark(expected: Operation, phase: String, resultRef: String? = null): Boolean {
        resultRef?.let { checkStoredJson(it, readBudgets.maxCanonicalBytes) }
        val allowed = when (expected.phase) {
            "PREPARED" -> phase == "FAILED"
            "ATTEMPTED" -> phase == "FAILED" || phase == "COMMITTED"
            "COMMITTED" -> phase == "VERIFIED"
            "VERIFIED" -> phase == "VERIFIED"
            else -> false
        }
        if (!allowed) return false
        return writableDatabase.update("mutation_journal", ContentValues().apply {
            put("phase", phase); if (resultRef != null) put("result_ref", resultRef)
        }, "id = ? AND signature = ? AND kind = ? AND phase = ? AND result_ref IS ?",
            arrayOf(expected.id, expected.signature, expected.kind, expected.phase, expected.resultRef)) == 1
    }

    internal fun retirement(id: String, kind: String, signature: String, write: Boolean): OperationRetirementResult {
        require(id.isNotBlank() && id.length <= readBudgets.mutation.maxCellUnits)
        fun classify(header: OperationHeader?): OperationRetirementResult {
            if (header == null) return OperationRetirementResult.NotRetired(id)
            if (header.phase !in setOf("PREPARED", "FAILED", "ATTEMPTED", "COMMITTED", "VERIFIED", "ABANDONED") ||
                header.kind.isBlank() || !header.signature.matches(Regex("[0-9a-f]{64}"))) return OperationRetirementResult.Unavailable
            if (header.kind != kind || header.signature != signature) return OperationRetirementResult.Conflict(id)
            return when (header.phase) {
                "ABANDONED" -> OperationRetirementResult.Retired(id)
                "ATTEMPTED" -> OperationRetirementResult.Blocked(id, RetirementBlockReason.MAY_HAVE_DISPATCHED)
                "COMMITTED", "VERIFIED" -> OperationRetirementResult.Blocked(id, RetirementBlockReason.RECORDED_COMMIT)
                else -> OperationRetirementResult.NotRetired(id)
            }
        }
        if (!write) return classify(operationHeader(id))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val initial = operationHeader(id)
            val status = classify(initial)
            if (status is OperationRetirementResult.NotRetired) {
                if (initial == null) {
                    db.insertWithOnConflict("mutation_journal", null, ContentValues().apply {
                        put("id", id); put("kind", kind); put("signature", signature); put("phase", "ABANDONED")
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                } else {
                    db.update("mutation_journal", ContentValues().apply { put("phase", "ABANDONED") },
                        "id = ? AND kind = ? AND signature = ? AND phase = ?", arrayOf(id, kind, signature, initial.phase))
                }
            }
            val result = classify(operationHeader(id))
            if (result is OperationRetirementResult.NotRetired) return OperationRetirementResult.Unavailable
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    internal fun unresolved(): List<Operation> = readableDatabase.query("mutation_journal", arrayOf("id"),
        "phase IN (?, ?)", arrayOf("ATTEMPTED", "COMMITTED"), null, null, null).use { cursor ->
        buildList { while (cursor.moveToNext()) operation(cursor.getString(0))?.let(::add) }
    }
}
