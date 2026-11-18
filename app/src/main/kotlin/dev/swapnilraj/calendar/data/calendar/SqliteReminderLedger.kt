package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Scheduling identity only; never serialize an EventOccurrence or its title, colour, or guests. */
class SqliteReminderLedger(
    private val store: CalendarLocalStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
) : ReminderLedger {
    private suspend fun <T> transaction(block: (SQLiteDatabase) -> T): T = withContext(io) {
        val db = store.writableDatabase
        db.beginTransaction()
        try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    override suspend fun trackedForRevalidation(window: InstantBounds): List<ReminderRecord> = withContext(io) {
        require(window.endExclusive >= window.start)
        read(store.readableDatabase, "state IN ($pendingSql) OR (state != ? AND (state IN (?, ?) OR last_posted_seconds IS NOT NULL) AND " +
            "(planned_seconds > ? OR (planned_seconds = ? AND planned_nanos >= ?)) AND " +
            "(planned_seconds < ? OR (planned_seconds = ? AND planned_nanos < ?)))",
            pendingArgs + arrayOf(ReminderDeliveryState.RETIRED.name, ReminderDeliveryState.POSTED.name, ReminderDeliveryState.DISMISSED.name,
                window.start.epochSecond.toString(), window.start.epochSecond.toString(), window.start.nano.toString(),
                window.endExclusive.epochSecond.toString(), window.endExclusive.epochSecond.toString(), window.endExclusive.nano.toString()))
    }

    override suspend fun reconcile(snapshot: ReminderSourceSnapshot, enabledCalendars: Set<CalendarId>,
        resolutions: Map<ReminderKey, ReminderResolution>, now: Instant): ReminderPlanChanges = transaction { db ->
        val cancellations = mutableSetOf<ReminderNotificationAlias>()
        // A user disabling a calendar is authority to stop pending work, not proof of deletion.
        read(db, "state IN ($pendingSql)", pendingArgs).filter { it.key.calendarId !in enabledCalendars }.forEach { old ->
            write(db, old.copy(state = ReminderDeliveryState.CANCELLED, revision = next(old.revision), claimToken = null,
                snoozedUntil = null, effectiveDue = old.plannedDue))
            cancellations += old.notificationAlias
        }
        // Metadata can prove a replaced/removed namespace even if event discovery failed.
        snapshot.removedCalendars.forEach { id ->
            read(db, "calendar_id = ? AND state != ?", arrayOf(id.value.toString(), ReminderDeliveryState.RETIRED.name))
                .forEach { retire(db, it, cancellations) }
        }
        snapshot.observedCalendarIdentities.forEach { (id, identity) ->
            val clause = if (identity.providerUidFingerprint == null) "source_fingerprint != ?"
                else "(source_fingerprint != ? OR (source_provider_uid IS NOT NULL AND source_provider_uid != ?))"
            val args = mutableListOf(id.value.toString(), ReminderDeliveryState.RETIRED.name, identity.fingerprint)
            identity.providerUidFingerprint?.let(args::add)
            read(db, "calendar_id = ? AND state != ? AND $clause", args.toTypedArray()).forEach { retire(db, it, cancellations) }
        }

        val discoveries = snapshot.candidates.associateBy { it.key }
        val keys = discoveries.keys + resolutions.keys
        val existing = readKeys(db, keys).associateBy { it.key }
        for (key in keys) {
            if (key.calendarId !in enabledCalendars || key.calendarId in snapshot.removedCalendars) continue
            val old = existing[key]
            val resolution = resolutions[key]
            if (resolution == ReminderResolution.GoneOrIneligible) {
                old?.let { retire(db, it, cancellations) }
                continue
            }
            if (resolution is ReminderResolution.Deferred) {
                if (old?.state == ReminderDeliveryState.SCHEDULED || old?.state == ReminderDeliveryState.SNOOZED)
                    write(db, old.copy(state = ReminderDeliveryState.DEFERRED, revision = next(old.revision)))
                continue
            }
            val candidate = (resolution as? ReminderResolution.Eligible)?.candidate ?: discoveries[key] ?: continue
            require(candidate.key == key) { "Reminder resolution key mismatch" }
            val observed = snapshot.observedCalendarIdentities[key.calendarId]
            // Contradictory snapshots can race account changes. Wait for consistent fresh evidence.
            if (observed?.isKnownDifferentFrom(candidate.sourceCalendarIdentity) == true) continue
            val allDay = candidate.occurrence.time is EventTime.AllDay
            val namespaceChanged = old?.sourceCalendarIdentity?.isKnownDifferentFrom(candidate.sourceCalendarIdentity) == true
            val uidChanged = old?.sourceEventUidFingerprint != null && candidate.sourceEventUidFingerprint != null &&
                old.sourceEventUidFingerprint != candidate.sourceEventUidFingerprint
            val startChanged = old != null && old.currentRef.instanceStart != candidate.occurrence.ref.instanceStart
            val modeChanged = old != null && old.allDay != allDay
            val fresh = old == null || old.state == ReminderDeliveryState.RETIRED || namespaceChanged || uidChanged || startChanged || modeChanged ||
                (old.state == ReminderDeliveryState.CANCELLED && old.lastPostedAt == null)
            val identity = if (namespaceChanged) candidate.sourceCalendarIdentity else mergeIdentity(old?.sourceCalendarIdentity, candidate.sourceCalendarIdentity)
            val eventUid = candidate.sourceEventUidFingerprint ?: old?.sourceEventUidFingerprint.takeUnless { namespaceChanged }
            val updated = if (fresh) {
                old?.let { cancellations += it.notificationAlias }
                ReminderRecord(key, identity, candidate.occurrence.ref, allDay, candidate.due, candidate.due,
                    ReminderNotificationAlias(candidate.occurrence.ref, candidate.due, allocateNonce(db)),
                    ReminderDeliveryState.SCHEDULED, old?.let { next(it.generation) } ?: 0,
                    old?.let { next(it.revision) } ?: 0, sourceEventUidFingerprint = eventUid)
            } else {
                checkNotNull(old)
                val state = when (old.state) {
                    ReminderDeliveryState.DEFERRED -> if (old.snoozedUntil != null) ReminderDeliveryState.SNOOZED
                        else if (old.lastPostedAt == null) ReminderDeliveryState.SCHEDULED else old.state
                    ReminderDeliveryState.EXPIRED -> if (old.lastPostedAt == null && candidate.due != old.plannedDue && candidate.due >= now)
                        ReminderDeliveryState.SCHEDULED else old.state
                    else -> old.state
                }
                val effective = old.snoozedUntil ?: if (state in historyStates) old.effectiveDue else candidate.due
                old.copy(sourceCalendarIdentity = identity, currentRef = candidate.occurrence.ref, allDay = allDay,
                    plannedDue = candidate.due, effectiveDue = effective, state = state, sourceEventUidFingerprint = eventUid)
                    .let { if (it == old) it else it.copy(revision = next(old.revision)) }
            }
            if (updated != old) write(db, updated)
        }
        ReminderPlanChanges(cancellations)
    }

    override suspend fun claimDue(now: Instant, limit: Int): List<ReminderClaim> = transaction { db ->
        require(limit > 0)
        read(db, "state IN (?, ?) AND (effective_seconds < ? OR (effective_seconds = ? AND effective_nanos <= ?))",
            arrayOf(ReminderDeliveryState.SCHEDULED.name, ReminderDeliveryState.SNOOZED.name,
                now.epochSecond.toString(), now.epochSecond.toString(), now.nano.toString()), limit).map { old ->
            val token = UUID.randomUUID().toString()
            val record = old.copy(state = ReminderDeliveryState.POSTING, claimToken = token, postToken = token, revision = next(old.revision))
            write(db, record)
            ReminderClaim(record)
        }
    }

    override suspend fun acknowledge(claim: ReminderClaim, outcome: ReminderDeliveryOutcome): Boolean = transaction { db ->
        val current = find(db, claim.record.key) ?: return@transaction false
        if (current.version != claim.record.version || current.claimToken != claim.record.claimToken ||
            current.state !in setOf(ReminderDeliveryState.POSTING, ReminderDeliveryState.UNCERTAIN)) return@transaction false
        val state = when (outcome) {
            is ReminderDeliveryOutcome.Posted -> ReminderDeliveryState.POSTED
            ReminderDeliveryOutcome.Deferred -> ReminderDeliveryState.DEFERRED
            ReminderDeliveryOutcome.Cancelled -> ReminderDeliveryState.CANCELLED
            ReminderDeliveryOutcome.Expired -> ReminderDeliveryState.EXPIRED
            ReminderDeliveryOutcome.Uncertain -> ReminderDeliveryState.UNCERTAIN
        }
        write(db, current.copy(state = state, revision = next(current.revision),
            claimToken = if (state == ReminderDeliveryState.UNCERTAIN) current.claimToken else null,
            lastPostedAt = (outcome as? ReminderDeliveryOutcome.Posted)?.at ?: current.lastPostedAt,
            snoozedUntil = if (state == ReminderDeliveryState.POSTED || state == ReminderDeliveryState.CANCELLED || state == ReminderDeliveryState.EXPIRED)
                null else current.snoozedUntil))
        true
    }

    override suspend fun findByAlias(alias: ReminderNotificationAlias): ReminderRecord? = withContext(io) {
        read(store.readableDatabase, "notification_nonce = ?", arrayOf(alias.nonce)).singleOrNull()?.takeIf { it.notificationAlias == alias }
    }

    override suspend fun snooze(expected: ReminderVersion, postToken: String, minutes: Int, now: Instant): ReminderRecord? = transaction { db ->
        require(minutes in listOf(5, 10, 30))
        val old = find(db, expected.key) ?: return@transaction null
        if (old.version != expected || old.state != ReminderDeliveryState.POSTED || old.postToken != postToken || postToken.isBlank()) return@transaction null
        val until = now.plusSeconds(minutes * 60L)
        old.copy(state = ReminderDeliveryState.SNOOZED, effectiveDue = until, snoozedUntil = until,
            revision = next(old.revision)).also { write(db, it) }
    }

    override suspend fun dismiss(expected: ReminderVersion, postToken: String): Boolean = transaction { db ->
        val old = find(db, expected.key) ?: return@transaction false
        if (old.version != expected || old.state != ReminderDeliveryState.POSTED || old.postToken != postToken || postToken.isBlank()) return@transaction false
        write(db, old.copy(state = ReminderDeliveryState.DISMISSED, revision = next(old.revision), snoozedUntil = null))
        true
    }

    override suspend fun expireBefore(cutoff: Instant): ReminderPlanChanges = transaction { db ->
        val rows = read(db, "state IN (?, ?, ?) AND (effective_seconds < ? OR (effective_seconds = ? AND effective_nanos < ?))",
            arrayOf(ReminderDeliveryState.SCHEDULED.name, ReminderDeliveryState.SNOOZED.name, ReminderDeliveryState.DEFERRED.name,
                cutoff.epochSecond.toString(), cutoff.epochSecond.toString(), cutoff.nano.toString()))
        rows.forEach { write(db, it.copy(state = ReminderDeliveryState.EXPIRED, revision = next(it.revision), snoozedUntil = null)) }
        ReminderPlanChanges(rows.map { it.notificationAlias }.toSet())
    }

    override suspend fun nextScheduled(): ReminderRecord? = withContext(io) {
        read(store.readableDatabase, "state IN (?, ?)", arrayOf(ReminderDeliveryState.SCHEDULED.name, ReminderDeliveryState.SNOOZED.name), 1).firstOrNull()
    }

    override suspend fun interruptedDeliveries(): List<ReminderClaim> = withContext(io) {
        read(store.readableDatabase, "state IN (?, ?)", arrayOf(ReminderDeliveryState.POSTING.name, ReminderDeliveryState.UNCERTAIN.name)).map(::ReminderClaim)
    }

    private fun retire(db: SQLiteDatabase, old: ReminderRecord, cancellations: MutableSet<ReminderNotificationAlias>) {
        if (old.state == ReminderDeliveryState.RETIRED) return
        write(db, old.copy(state = ReminderDeliveryState.RETIRED, revision = next(old.revision), claimToken = null, snoozedUntil = null))
        cancellations += old.notificationAlias
    }
    private fun find(db: SQLiteDatabase, key: ReminderKey) = read(db, "record_key = ?", arrayOf(keyText(key))).singleOrNull()
    private fun readKeys(db: SQLiteDatabase, keys: Set<ReminderKey>): List<ReminderRecord> = keys.toList().chunked(400).flatMap { chunk ->
        read(db, "record_key IN (${chunk.joinToString(",") { "?" }})", chunk.map(::keyText).toTypedArray())
    }
    private fun read(db: SQLiteDatabase, where: String, args: Array<String>, limit: Int? = null): List<ReminderRecord> =
        db.query("reminder_ledger", arrayOf("record_key", "payload"), where, args, null, null,
            "effective_seconds ASC, effective_nanos ASC, record_key ASC", limit?.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val record = decode(JSONObject(cursor.getString(1)))
                check(keyText(record.key) == cursor.getString(0)) { "Reminder key is inconsistent" }
                add(record)
            } }
        }
    private fun write(db: SQLiteDatabase, record: ReminderRecord) {
        val values = ContentValues().apply {
            put("record_key", keyText(record.key)); put("calendar_id", record.key.calendarId.value)
            put("source_fingerprint", record.sourceCalendarIdentity.fingerprint)
            put("source_provider_uid", record.sourceCalendarIdentity.providerUidFingerprint)
            put("payload", encode(record).toString()); put("state", record.state.name)
            put("effective_seconds", record.effectiveDue.epochSecond); put("effective_nanos", record.effectiveDue.nano)
            put("planned_seconds", record.plannedDue.epochSecond); put("planned_nanos", record.plannedDue.nano)
            record.lastPostedAt?.let { put("last_posted_seconds", it.epochSecond) } ?: putNull("last_posted_seconds")
            put("notification_nonce", record.notificationAlias.nonce)
        }
        // REPLACE would also delete another key on a nonce collision. Fail/roll back instead.
        if (db.update("reminder_ledger", values, "record_key = ?", arrayOf(keyText(record.key))) == 0)
            db.insertOrThrow("reminder_ledger", null, values)
    }
    private fun allocateNonce(db: SQLiteDatabase): String {
        repeat(32) {
            val nonce = nonceFactory()
            require(nonce.isNotBlank())
            if (db.insertWithOnConflict("reminder_nonce_registry", null, ContentValues().apply { put("nonce", nonce) },
                    SQLiteDatabase.CONFLICT_IGNORE) != -1L) return nonce
        }
        error("Could not allocate a fresh reminder identity")
    }

    companion object {
        private val pending = listOf(ReminderDeliveryState.SCHEDULED, ReminderDeliveryState.SNOOZED, ReminderDeliveryState.POSTING,
            ReminderDeliveryState.UNCERTAIN, ReminderDeliveryState.DEFERRED)
        private val pendingSql = pending.joinToString(",") { "?" }
        private val pendingArgs = pending.map { it.name }.toTypedArray()
        private val historyStates = setOf(ReminderDeliveryState.POSTED, ReminderDeliveryState.DISMISSED, ReminderDeliveryState.CANCELLED, ReminderDeliveryState.EXPIRED)
        private fun next(value: Long) = Math.incrementExact(value)
        private fun mergeIdentity(old: ReminderCalendarIdentity?, incoming: ReminderCalendarIdentity) = ReminderCalendarIdentity(
            incoming.fingerprint, incoming.providerUidFingerprint ?: old?.providerUidFingerprint.takeIf { old?.fingerprint == incoming.fingerprint })
        private fun keyText(key: ReminderKey) = JSONArray().put("v1").put(key.calendarId.value).put(key.masterOrEventId)
            .put(key.originalOrInstanceStart.toString()).put(key.resolvedMinutesBefore).toString()
        private fun refJson(ref: EventRef) = JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId)
            .put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString()).apply {
                ref.recurrence?.let { put("master", it.masterEventId); put("original", it.originalStart.toString()) }
            }
        private fun ref(json: JSONObject) = EventRef(CalendarId(json.getLong("calendar")), json.getLong("event"),
            Instant.parse(json.getString("start")), Instant.parse(json.getString("end")),
            if (json.has("master")) RecurrenceRef(json.getLong("master"), Instant.parse(json.getString("original"))) else null)
        private fun JSONObject.optional(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
        private fun encode(record: ReminderRecord) = JSONObject().put("key", keyText(record.key))
            .put("identity", record.sourceCalendarIdentity.fingerprint)
            .put("calendarUid", record.sourceCalendarIdentity.providerUidFingerprint ?: JSONObject.NULL)
            .put("eventUid", record.sourceEventUidFingerprint ?: JSONObject.NULL)
            .put("ref", refJson(record.currentRef)).put("allDay", record.allDay)
            .put("planned", record.plannedDue.toString()).put("effective", record.effectiveDue.toString())
            .put("aliasRef", refJson(record.notificationAlias.ref)).put("aliasDue", record.notificationAlias.anchorDue.toString())
            .put("nonce", record.notificationAlias.nonce).put("state", record.state.name)
            .put("generation", record.generation).put("revision", record.revision)
            .put("posted", record.lastPostedAt?.toString() ?: JSONObject.NULL)
            .put("snoozed", record.snoozedUntil?.toString() ?: JSONObject.NULL).put("claim", record.claimToken ?: JSONObject.NULL)
            .put("postToken", record.postToken ?: JSONObject.NULL)
        private fun decode(json: JSONObject): ReminderRecord {
            val key = JSONArray(json.getString("key"))
            check(key.getString(0) == "v1") { "Unsupported reminder encoding" }
            return ReminderRecord(ReminderKey(CalendarId(key.getLong(1)), key.getLong(2), Instant.parse(key.getString(3)), key.getInt(4)),
                ReminderCalendarIdentity(json.getString("identity"), json.optional("calendarUid")), ref(json.getJSONObject("ref")), json.getBoolean("allDay"),
                Instant.parse(json.getString("planned")), Instant.parse(json.getString("effective")),
                ReminderNotificationAlias(ref(json.getJSONObject("aliasRef")), Instant.parse(json.getString("aliasDue")), json.getString("nonce")),
                ReminderDeliveryState.valueOf(json.getString("state")), json.getLong("generation"), json.getLong("revision"),
                json.optional("posted")?.let(Instant::parse), json.optional("snoozed")?.let(Instant::parse), json.optional("claim"), json.optional("eventUid"), json.optional("postToken"))
        }
    }
}
