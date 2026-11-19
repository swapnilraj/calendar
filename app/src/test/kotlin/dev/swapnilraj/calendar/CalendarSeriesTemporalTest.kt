package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentValues
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
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSeriesTemporalTest {
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val zone = ZoneId.of("Europe/London")
    private data class Fixture(val provider: ScopedSeriesSqlProvider, val writer: CalendarSeriesWriter,
        val store: CalendarLocalStore, val selected: EventRef, val permissions: MutableSet<String>)
    private suspend fun fixture(allDay: Boolean = false, block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = ScopedSeriesSqlProvider().apply { allowUpdates = true; expandInstances = true }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = CalendarLocalStore(context, "temporal-${UUID.randomUUID()}.db")
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val ordinary = CalendarWriter(context.contentResolver, reader, store, permissions::contains)
        val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A")
        val time = if (allDay) EventTime.AllDay(CalendarDateRange(LocalDate.of(2026,11,17), LocalDate.of(2026,11,19))) else EventTime.Timed(start, start.plusSeconds(3600))
        val created = ordinary.create("seed", EventDraft(CalendarId(2), time, if (allDay) null else zone, "Master", "Room", "Notes", listOf(10),
            targetIdentity = identity, recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(8)))) as MutationResult.LocalSaved
        val ref = created.ref.copy(instanceStart = created.ref.instanceStart.plusSeconds(2 * 86400), instanceEnd = created.ref.instanceEnd.plusSeconds(2 * 86400),
            recurrence = RecurrenceRef(created.ref.eventId, created.ref.instanceStart.plusSeconds(2 * 86400)))
        val source = CalendarSeriesReader(context.contentResolver, reader, store, permissions::contains)
        val writer = CalendarSeriesWriter(context.contentResolver, source, store, permissions::contains)
        provider.batchCount = 0; provider.committed = false; provider.queries.clear()
        try { block(Fixture(provider, writer, store, ref, permissions)) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); provider.db.close(); context.deleteDatabase(store.databaseName) }
    }
    private suspend fun Fixture.editable() = (writer.loadForEdit(selected) as SeriesReadResult.Ready).series
    private fun command(series: EditableSeries, newStart: FieldChange<RecurrenceStart> = FieldChange.Keep,
        duration: RecurrenceDurationChange = RecurrenceDurationChange.KeepRaw, rule: RecurrenceRuleChanges = RecurrenceRuleChanges(),
        fields: EventPatch = EventPatch()) = SeriesCommand.WholeEdit(RecurrenceEditIntent.WholeSeriesEdit(series.snapshot.start, fields, newStart, duration, rule))
    private fun set(p: ScopedSeriesSqlProvider, column: String, value: String?) = p.db.execSQL("UPDATE events SET $column = ?", arrayOf(value))

    @Test fun issuedAllRightsAndTimeEditPreserveUidUnknownMetadataChildrenAndRawRule() = runBlocking { fixture { f ->
        set(f.provider, "rrule", "COUNT=8;FREQ=DAILY;INTERVAL=1")
        set(f.provider, "_sync_id", "cloud-A"); set(f.provider, "uid2445", "uid-A")
        f.provider.db.execSQL("ALTER TABLE events ADD COLUMN opaque_oem TEXT DEFAULT 'preserve'")
        f.provider.db.execSQL("INSERT INTO reminders(event_id,minutes,method) VALUES(1,-1,0)")
        val edit = f.editable(); val rights = edit.scopes.getValue(RecurrenceScope.ALL)
        assertEquals(AccessDecision.ALLOWED, rights.recurrence); assertTrue(EditableField.TIME in rights.fields); assertTrue(EditableField.ZONE in rights.fields)
        val shifted = RecurrenceStart.Timed(LocalDateTime.of(2026,11,18,11,0), zone)
        val result = f.writer.mutate("shift", f.selected, edit.token, command(edit, FieldChange.Set(shifted), fields = EventPatch(title = FieldChange.Set("Changed")))) as SeriesMutationResult.LocalSaved
        assertEquals(Instant.parse("2026-11-20T11:00:00Z"), result.selected!!.instanceStart)
        assertEquals(result.selected!!.instanceStart, result.selected!!.recurrence!!.originalStart)
        assertEquals("COUNT=8;FREQ=DAILY;INTERVAL=1", f.provider.value("rrule")); assertEquals("PT3600S", f.provider.value("duration")); assertNull(f.provider.value("dtend"))
        assertEquals("cloud-A", f.provider.value("_sync_id")); assertEquals("uid-A", f.provider.value("uid2445")); assertEquals("preserve", f.provider.value("opaque_oem"))
        assertEquals(2, f.provider.count("reminders")); assertEquals("VERIFIED", f.store.operation("shift")!!.phase)
    } }

    @Test fun explicitDurationAndAlertEditKeepNonAlertRowsAndReadActualNewEnd() = runBlocking { fixture { f ->
        f.provider.db.execSQL("INSERT INTO reminders(event_id,minutes,method) VALUES(1,-1,0)")
        val edit = f.editable()
        val result = f.writer.mutate("duration", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofMinutes(90)),
            fields = EventPatch(alertMinutes = FieldChange.Set(listOf(15,45))))) as SeriesMutationResult.LocalSaved
        assertEquals(Duration.ofMinutes(90), Duration.between(result.selected!!.instanceStart, result.selected!!.instanceEnd))
        assertEquals("PT5400S", f.provider.value("duration")); assertEquals(3, f.provider.count("reminders"))
        f.provider.db.rawQuery("SELECT minutes FROM reminders WHERE method=0", null).use { it.moveToFirst(); assertEquals(-1, it.getInt(0)) }
    } }

    @Test fun weeklyPatternKeepsCountAndMapsOriginalOrdinalToRealResult() = runBlocking { fixture { f ->
        val edit = f.editable()
        val result = f.writer.mutate("pattern", f.selected, edit.token, command(edit, rule = RecurrenceRuleChanges(
            pattern = FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.TUESDAY)))))) as SeriesMutationResult.LocalSaved
        assertTrue(f.provider.value("rrule")!!.contains("COUNT=8")); assertEquals(Instant.parse("2026-12-01T09:00:00Z"), result.selected!!.instanceStart)
    } }

    @Test fun shorteningCountRemovesSelectedOrdinalWithoutReturningOldOccurrence() = runBlocking { fixture { f ->
        val edit = f.editable()
        val result = f.writer.mutate("short", f.selected, edit.token, command(edit, rule = RecurrenceRuleChanges(end = FieldChange.Set(RecurrenceEnd.Count(2))))) as SeriesMutationResult.LocalSaved
        assertNull(result.selected); assertTrue(f.provider.value("rrule")!!.contains("COUNT=2")); assertEquals("VERIFIED", f.store.operation("short")!!.phase)
    } }

    @Test fun importedExactUntilRawKeepIsNotRebasedByDurationEdit() = runBlocking { fixture { f ->
        val raw = "FREQ=DAILY;UNTIL=20261125T103017Z;WKST=SU"
        set(f.provider, "rrule", raw)
        val edit = f.editable()
        assertTrue((RecurrenceRules.parse(raw, edit.snapshot.start) as ParsedRecurrence.Supported).spec.end is RecurrenceEnd.ExactUntil)
        assertTrue(f.writer.mutate("until", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2)))) is SeriesMutationResult.LocalSaved)
        assertEquals(raw, f.provider.value("rrule"))
    } }

    @Test fun allDayStartAndDurationUseUtcNominalDaysAndSameKind() = runBlocking { fixture(allDay = true) { f ->
        val edit = f.editable()
        val result = f.writer.mutate("dates", f.selected, edit.token, command(edit,
            FieldChange.Set(RecurrenceStart.AllDay(LocalDate.of(2026,11,18))), RecurrenceDurationChange.AllDayDays(3))) as SeriesMutationResult.LocalSaved
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), result.selected!!.instanceStart)
        assertEquals(3 * 86400L, Duration.between(result.selected!!.instanceStart, result.selected!!.instanceEnd).seconds)
        assertEquals("UTC", f.provider.value("eventTimezone")); assertEquals("P3D", f.provider.value("duration")); assertNull(f.provider.value("dtend"))
    } }

    @Test fun zoneChangeRequiresFreshCalendarPolicyAndReturnsActualInstant() = runBlocking { fixture { f ->
        val edit = f.editable()
        val ny = ZoneId.of("America/New_York")
        val result = f.writer.mutate("zone", f.selected, edit.token, command(edit, FieldChange.Set(RecurrenceStart.Timed(LocalDateTime.of(2026,11,17,9,0), ny)))) as SeriesMutationResult.LocalSaved
        assertEquals(Instant.parse("2026-11-19T14:00:00Z"), result.selected!!.instanceStart); assertEquals(ny.id, f.provider.value("eventTimezone"))
    }; fixture { f ->
        f.provider.setCalendar("canModifyTimeZone", "0")
        val edit = f.editable(); assertFalse(EditableField.ZONE in edit.scopes.getValue(RecurrenceScope.ALL).fields)
        val result = f.writer.mutate("denied-zone", f.selected, edit.token, command(edit, FieldChange.Set(RecurrenceStart.Timed(LocalDateTime.of(2026,11,17,9,0), ZoneId.of("America/New_York")))))
        assertTrue(result is SeriesMutationResult.PlanningReview); assertEquals(0, f.provider.batchCount); assertNull(f.store.operation("denied-zone"))
    } }

    @Test fun nominalDurationAndDateSetsDoNotIssueTemporalOrRuleRights() = runBlocking {
        listOf("duration" to "P1D", "exdate" to "20261118T090000Z", "rdate" to "20261130T090000Z", "exrule" to "FREQ=WEEKLY;BYDAY=SU").forEach { (key,value) -> fixture { f ->
            set(f.provider, key, value)
            val edit = f.editable(); val rights = edit.scopes.getValue(RecurrenceScope.ALL)
            assertFalse(EditableField.TIME in rights.fields); assertEquals(AccessDecision.UNKNOWN, rights.recurrence)
            val result = f.writer.mutate("unsupported", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2))))
            assertTrue(result is SeriesMutationResult.Unsupported || result is SeriesMutationResult.PlanningReview)
            assertEquals(0, f.provider.batchCount); assertNull(f.store.operation("unsupported"))
        } }
    }

    @Test fun staleTokenAndNamespaceRaceNeverDispatchTemporalChanges() = runBlocking { fixture { f ->
        val edit = f.editable(); set(f.provider, "description", "External")
        val result = f.writer.mutate("stale", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2))))
        assertEquals(SeriesMutationResult.Common(MutationResult.Conflict(null)), result); assertEquals(0, f.provider.batchCount)
    }; fixture { f ->
        val edit = f.editable(); f.provider.beforeBatch = { f.provider.setCalendar("_sync_id", "replacement") }
        val result = f.writer.mutate("race", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2))))
        assertEquals(SeriesMutationResult.Common(MutationResult.Conflict(null)), result); assertEquals("PT3600S", f.provider.value("duration"))
    } }

    @Test fun lostAckNeverReplaysAndDelayedNativeExpansionKeepsKnownCommitUncertain() = runBlocking { fixture { f ->
        val edit = f.editable(); val frozen = command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2)))
        f.provider.lostAck = true
        assertTrue(f.writer.mutate("lost", f.selected, edit.token, frozen) is SeriesMutationResult.Common)
        assertEquals("ATTEMPTED", f.store.operation("lost")!!.phase); f.provider.lostAck = false
        assertEquals(SeriesMutationResult.Common(MutationResult.Uncertain("lost", null)), f.writer.recover("lost"))
        f.writer.mutate("lost", f.selected, edit.token, frozen); assertEquals(1, f.provider.batchCount)
    }; fixture { f ->
        val edit = f.editable(); f.provider.afterCommit = { f.provider.delayedInstances = true }
        val result = f.writer.mutate("delayed", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2))))
        assertEquals(SeriesMutationResult.Common(MutationResult.Uncertain("delayed", 1)), result); assertEquals("COMMITTED", f.store.operation("delayed")!!.phase)
        f.provider.afterCommit = null; f.provider.delayedInstances = false; f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertTrue(f.writer.recover("delayed") is SeriesMutationResult.LocalSaved); assertEquals(1, f.provider.batchCount)
        f.provider.setCalendar("_sync_id", "replacement"); f.provider.queries.clear()
        assertEquals(SeriesMutationResult.Common(MutationResult.Uncertain("delayed", 1)), f.writer.recover("delayed"))
        assertTrue(f.provider.queries.none { it.path in setOf("events","reminders","attendees","extendedproperties") })
    } }

    @Test fun kindConversionAndProviderIntOverflowFailBeforeJournal() = runBlocking { fixture { f ->
        val edit = f.editable()
        val kind = f.writer.mutate("kind", f.selected, edit.token, command(edit, FieldChange.Set(RecurrenceStart.AllDay(LocalDate.of(2026,11,17)))))
        assertTrue(kind is SeriesMutationResult.PlanningReview); assertNull(f.store.operation("kind"))
        val overflow = f.writer.mutate("overflow", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofSeconds(Int.MAX_VALUE.toLong()+1))))
        assertTrue(overflow is SeriesMutationResult.PlanningReview); assertNull(f.store.operation("overflow")); assertEquals(0, f.provider.batchCount)
    } }
    @Test fun wrongNativeEndKeepsDurableCommitUncertainAcrossSqlReopen() = runBlocking { fixture { f ->
        val edit = f.editable()
        f.provider.afterCommit = { f.provider.endDelta = 1000 }
        val result = f.writer.mutate("wrong-end", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2))))
        assertEquals(SeriesMutationResult.Common(MutationResult.Uncertain("wrong-end", 1)), result)
        assertEquals("COMMITTED", f.store.operation("wrong-end")!!.phase)
        f.provider.afterCommit = null; f.provider.endDelta = 0; f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        f.store.close()
        val recovered = f.writer.recover("wrong-end") as SeriesMutationResult.LocalSaved
        assertEquals(7200L, Duration.between(recovered.selected!!.instanceStart, recovered.selected!!.instanceEnd).seconds)
        assertEquals(1, f.provider.batchCount)
    } }

    @Test fun frozenCommandAndTemplateTamperingCannotAcknowledgeKnownCommit() = runBlocking { fixture { f ->
        val edit = f.editable()
        f.provider.afterCommit = { f.provider.delayedInstances = true }
        f.writer.mutate("proof", f.selected, edit.token, command(edit, duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2))))
        val original = f.store.operation("proof")!!.resultRef!!
        val changed = JSONObject(original).apply { getJSONObject("template").put("ordinal", 1) }
        f.store.forceOperationForTest("proof", "COMMITTED", changed.toString())
        f.provider.afterCommit = null; f.provider.delayedInstances = false
        assertEquals(SeriesMutationResult.Common(MutationResult.Uncertain("proof", 1)), f.writer.recover("proof"))
        f.store.forceOperationForTest("proof", "COMMITTED", original)
        assertTrue(f.writer.recover("proof") is SeriesMutationResult.LocalSaved)
        assertEquals(1, f.provider.batchCount)
    } }

    @Test fun explicitFoldAnchorRemainsExactAndRemovedOrdinalReturnsNull() = runBlocking { fixture { f ->
        val edit = f.editable()
        val fold = RecurrenceStart.Timed(LocalDateTime.of(2026,10,25,1,30), zone, ZoneOffset.UTC)
        val result = f.writer.mutate("fold", f.selected, edit.token, command(edit, FieldChange.Set(fold),
            rule = RecurrenceRuleChanges(end = FieldChange.Set(RecurrenceEnd.Count(1))))) as SeriesMutationResult.LocalSaved
        assertEquals(Instant.parse("2026-10-25T01:30:00Z").toEpochMilli().toString(), f.provider.value("dtstart"))
        assertNull(result.selected)
    } }

}
