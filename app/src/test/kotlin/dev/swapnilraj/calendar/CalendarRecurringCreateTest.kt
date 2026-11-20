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
        val db = SQLiteDatabase.create(null)
        data class Query(val path: String, val fields: List<String>?, val selection: String?)
        val queries = mutableListOf<Query>()
        var beforeQuery: ((String) -> Unit)? = null
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
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (failReadsAfterCommit && committed) error("Synthetic post-commit read failure")
            val path = uri.pathSegments.first(); queries += Query(path, fields?.toList(), selection)
            beforeQuery?.invoke(path)
            if (path == "instances" && delayedInstances) return MatrixCursor(fields!!)
            val where = mutableListOf<String>(); val bind = mutableListOf<String>()
            if (path != "instances") uri.lastPathSegment?.toLongOrNull()?.let { where += "_id = ?"; bind += it.toString() }
            if (selection != null) { where += "($selection)"; bind += args.orEmpty() }
            val projected = if (path == "instances") fields?.map { when (it) {
                "begin" -> "(begin+$beginDelta) AS begin"; "end" -> "(end+$endDelta) AS end"; else -> it
            } }?.toTypedArray() else fields
            if (path == "instances") { where += "(begin+$beginDelta) < CAST(? AS INTEGER) AND (end+$endDelta) > CAST(? AS INTEGER)"; bind += uri.pathSegments[3]; bind += uri.pathSegments[2] }
            val table = when {
                path == "instances" -> "instance_view"
                path == "reminders" && uri.pathSegments.size == 2 ->
                    "(SELECT reminders.*, events.calendar_id, events.uid2445 FROM reminders JOIN events ON reminders.event_id = events._id)"
                else -> path
            }
            return db.query(table, projected, where.joinToString(" AND ").takeIf { it.isNotEmpty() }, bind.toTypedArray(), null, null, sort)
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
            batchCount++; beforeBatch?.also { beforeBatch = null }?.invoke(); db.beginTransaction()
            val results = try { super.applyBatch(operations).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            committed = true
            if (lostAck) error("Synthetic lost acknowledgement")
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
            // This publication ships authored CREATE, not the separate scoped-edit backend.
            assertEquals(EditReadResult.Denied(MutationDenial.RECURRENCE), f.writer.loadForEdit(saved.ref))
            val operation = f.store.operation("new-series")!!
            assertEquals("VERIFIED", operation.phase)
            val receipt = JSONObject(operation.resultRef!!)
            assertEquals(6, receipt.getInt("version")); assertEquals(saved.ref.eventId, receipt.getLong("master"))
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
        val invalid = listOf(
            draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.MONDAY)))) to InvalidEventField.RECURRENCE,
            draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, interval = 1001)) to InvalidEventField.RECURRENCE,
            draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(10001))) to InvalidEventField.RECURRENCE,
            draft.copy(time = EventTime.Timed(start, start.plusSeconds(Int.MAX_VALUE.toLong() + 1))) to InvalidEventField.RECURRENCE,
            draft.copy(time = EventTime.Timed(start.plusNanos(1), start.plusSeconds(3600))) to InvalidEventField.TIME,
            draft.copy(time = EventTime.Timed(Instant.parse("2026-03-28T01:30:00Z"), Instant.parse("2026-03-28T02:30:00Z")),
                recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(3))) to InvalidEventField.RECURRENCE)
        invalid.forEach { (input, expectedField) -> fixture { f ->
            assertEquals(MutationResult.ValidationFailed(setOf(expectedField)), f.writer.create("invalid", input))
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
            val frozen = f.store.operation("namespace-race")!!
            assertEquals(MutationResult.Conflict(null), f.writer.create("namespace-race", draft))
            assertEquals(frozen, f.store.operation("namespace-race"))
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

    @Test fun nullRecurrenceKeepsLegacyCommandAndSignatureWithNewImmutableReceipt() = runBlocking {
        fixture { f ->
            val oneOff = draft.copy(recurrence = null)
            val saved = f.writer.create("oneoff", oneOff) as MutationResult.LocalSaved
            assertNull(saved.ref.recurrence)
            val receipt = JSONObject(f.store.operation("oneoff")!!.resultRef!!)
            assertEquals(6, receipt.getInt("version")); assertTrue(receipt.has("firstRef")); assertFalse(receipt.has("provider")); assertNotNull(f.p.value("uid2445"))
            val payload = JSONObject().put("calendar", 2).put("event", 0).put("start", start.toString()).put("end", start.plusSeconds(3600).toString())
                .put("allDay", false).put("zone", "Europe/London").put("title", oneOff.title).put("location", oneOff.location).put("description", oneOff.description)
                .put("alerts", org.json.JSONArray(oneOff.alertMinutes)).put("availability", 1).put("privacy", 2)
                .put("targetIdentity", JSONObject().put("calendar", 2).put("name", identity.account.name).put("type", identity.account.type).put("owner", identity.owner).put("providerId", identity.providerId))
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(("CREATE" + payload.toString()).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(expected, f.store.operation("oneoff")!!.signature)
            assertEquals(payload.toString(), receipt.getJSONObject("command").toString())
        }
    }
    /** Reconstruct the actual legacy wire shape; relabeling receipt6 would be contradictory data. */
    private fun legacyRecurringProof(operation: CalendarLocalStore.Operation): JSONObject {
        val (raw, frozen) = OrdinaryCreateReceipt.parse(operation, 1024 * 1024)
        val plan = frozen.plan
        val provider = JSONObject().put("dtstart", Instant.ofEpochMilli(plan.event.getValue("dtstart")!!.toLong()).toString())
            .put("allDay", plan.allDay).put("zone", plan.event["eventTimezone"]).put("rrule", plan.event["rrule"])
            .put("duration", plan.event["duration"]).put("firstStart", plan.firstStart.toString()).put("firstEnd", plan.firstEnd.toString())
        return JSONObject().put("version", 3).put("stage", raw.getString("stage")).put("kind", "CREATE").put("signature", operation.signature)
            .put("command", raw.getJSONObject("command")).put("identity", raw.getJSONObject("identity")).put("provider", provider).apply {
                if (raw.has("master")) put("master", raw.getLong("master"))
                if (raw.has("firstRef")) put("firstRef", raw.getJSONObject("firstRef"))
            }
    }

    @Test fun legacyPreparedAndFailedReceiptsOfferOnlyExactExplicitRetryAndEarnNewAcknowledgmentProof() = runBlocking {
        for (phase in listOf("PREPARED", "FAILED")) fixture { f ->
            f.store.writableDatabase.execSQL("""CREATE TRIGGER interrupt_dispatch BEFORE UPDATE OF phase ON mutation_journal
                WHEN NEW.phase = 'ATTEMPTED' BEGIN SELECT RAISE(FAIL, 'Synthetic pre-dispatch interruption'); END""")
            assertEquals(MutationResult.Unavailable, f.writer.create("legacy", draft))
            val initial = requireNotNull(f.store.operation("legacy"))
            val old = legacyRecurringProof(initial).toString()
            f.store.forceOperationForTest("legacy", phase, old)
            assertEquals(MutationResult.SafeToRetry("legacy"), f.writer.recover("legacy"))
            assertEquals(MutationResult.Conflict(null), f.writer.create("legacy", draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(8)))))
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.PermissionRequired, f.writer.create("legacy", draft))
            assertEquals(old, f.store.operation("legacy")!!.resultRef); assertEquals(0, f.p.batchCount)
            f.permissions += Manifest.permission.WRITE_CALENDAR
            f.store.writableDatabase.execSQL("DROP TRIGGER interrupt_dispatch")
            val saved = f.writer.create("legacy", draft) as MutationResult.LocalSaved
            val committed = requireNotNull(f.store.operation("legacy"))
            val proof = JSONObject(committed.resultRef!!)
            assertEquals(5, proof.getInt("version")); assertTrue(proof.getBoolean("ackComplete"))
            assertEquals(initial.signature, committed.signature)
            assertEquals(JSONObject(old).getJSONObject("command").toString(), proof.getJSONObject("command").toString())
            assertEquals(2, proof.getJSONArray("reminderIds").length())
            assertEquals(1, f.p.batchCount); assertEquals(1, f.p.count("events"))
            assertEquals(saved, f.writer.recover("legacy")); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun legacyAttemptedAndCommittedReceiptsNeverUpgradeReplayOrInventChildAcknowledgments() = runBlocking {
        for (phase in listOf("ATTEMPTED", "COMMITTED", "VERIFIED")) fixture { f ->
            val original = f.writer.create("legacy", draft) as MutationResult.LocalSaved
            val op = requireNotNull(f.store.operation("legacy"))
            val old = legacyRecurringProof(op).apply {
                remove("layout"); remove("ackFacts"); remove("ackComplete"); remove("reminderIds")
                if (phase != "VERIFIED") remove("firstRef")
                if (phase == "ATTEMPTED") { put("stage", "prepared"); remove("master") }
            }.toString()
            f.store.forceOperationForTest("legacy", phase, old)
            val expected = MutationResult.Uncertain("legacy", if (phase == "ATTEMPTED") null else original.ref.eventId)
            f.p.queries.clear()
            assertEquals(expected, f.writer.recover("legacy")); assertEquals(expected, f.writer.create("legacy", draft))
            assertEquals(old, f.store.operation("legacy")!!.resultRef); assertEquals(phase, f.store.operation("legacy")!!.phase)
            assertEquals(1, f.p.batchCount); assertTrue(f.p.queries.all { it.path == "calendars" })
        }
    }

    @Test fun contradictoryReceiptFivePhaseAndStageNeverOfferRetryOrProviderDispatch() = runBlocking {
        fixture { f ->
            f.writer.create("paired", draft) as MutationResult.LocalSaved
            val raw = requireNotNull(f.store.operation("paired")).resultRef!!
            for (phase in listOf("PREPARED", "FAILED", "COMMITTED", "VERIFIED")) {
                val malformed = JSONObject(raw).apply {
                    if (phase in setOf("PREPARED", "FAILED")) { put("ackComplete", false); remove("firstRef"); remove("reminderIds") }
                    else { put("stage", "prepared"); remove("master"); remove("ackComplete"); remove("ackFacts"); remove("reminderIds"); remove("firstRef") }
                }.toString()
                f.store.forceOperationForTest("paired", phase, malformed)
                assertEquals(MutationResult.Uncertain("paired", null), f.writer.recover("paired"))
                assertEquals(MutationResult.Uncertain("paired", null), f.writer.create("paired", draft))
                assertEquals(malformed, f.store.operation("paired")!!.resultRef); assertEquals(1, f.p.batchCount)
            }
        }
    }

}
