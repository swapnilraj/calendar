package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun SeriesEditorFixture.followingSource(): EditableSeries = source.copy(scopes = source.scopes +
    (RecurrenceScope.FOLLOWING to SeriesScopeCapabilities(delete = AccessDecision.ALLOWED)))

internal fun SeriesEditorFixture.allDayFollowingSource(): EditableSeries {
    val original = Instant.parse("2028-06-19T00:00:00Z"); val actual = Instant.parse("2028-06-20T00:00:00Z")
    val selected = selected.copy(instanceStart = actual, instanceEnd = actual.plusSeconds(86400), recurrence = RecurrenceRef(identity.masterEventId, original))
    val ready = followingSource()
    return ready.copy(selectedDetails = ready.selectedDetails.copy(occurrence = ready.selectedDetails.occurrence.copy(ref = selected,
        time = EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-20"), LocalDate.parse("2028-06-21")))), eventZone = ZoneId.of("Pacific/Auckland")),
        snapshot = ready.snapshot.copy(start = RecurrenceStart.AllDay(LocalDate.parse("2028-06-05")), validatedSelection = selected,
            exceptions = listOf(SeriesException(selected, RecurrenceKey.Date(LocalDate.parse("2028-06-19"))))))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesFollowingDeleteTest {
    private val f = SeriesEditorFixture()
    @After fun stop() { f.scope.cancel() }
    private suspend fun prepare(source: EditableSeries = f.followingSource()) = f.prepare(RecurrenceScope.FOLLOWING, SeriesEditorAction.DELETE, source)
    @Test fun followingDeleteRequiresIssuedAllowanceAndFollowingEditStaysUnsupported() = runBlocking {
        assertFalse(canPrepareSeriesScope(f.source, RecurrenceScope.FOLLOWING, SeriesEditorAction.DELETE))
        val ready = f.followingSource(); assertTrue(canPrepareSeriesScope(ready, RecurrenceScope.FOLLOWING, SeriesEditorAction.DELETE))
        val future = ready.copy(scopes = ready.scopes + (RecurrenceScope.FOLLOWING to f.rights))
        assertFalse(canPrepareSeriesScope(future, RecurrenceScope.FOLLOWING, SeriesEditorAction.EDIT))
        assertEquals(SeriesDraftPreparationResult.Unavailable, prepareSeriesDraft(future, RecurrenceScope.FOLLOWING, SeriesEditorAction.EDIT, "edit", f.store))
        assertTrue(f.calls.isEmpty())
    }
    @Test fun movedActualDateNeverBecomesFollowingDeleteBoundaryOrBrowserDate() = runBlocking {
        val source = f.followingSource(); val launch = prepare(source); val editor = f.controller(launch, source)
        assertEquals(RecurrenceKey.Timed(f.original), editor.deletionBoundary)
        assertEquals("2028-06-19", launch.rawDate); assertEquals(f.actual, launch.selected.instanceStart)
        editor.change { it.copy(title = "Forbidden field edit") }; editor.save(); assertEquals("Override title", editor.state.fields.title)
        assertTrue(f.calls.isEmpty()); editor.confirmDelete(); assertTrue(f.calls.isEmpty())
        f.next = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.FOLLOWING, f.selected)
        editor.requestDelete(); editor.confirmDelete()
        assertEquals(SeriesCommand.Delete(RecurrenceScope.FOLLOWING), f.calls.single().command)
        assertEquals(f.selected, f.calls.single().selected); assertEquals(f.source.token, f.calls.single().token)
    }
    @Test fun typedOriginalBoundaryPreservesSecondsWithoutTruncatingToDisplayMinute() = runBlocking {
        val ready = f.followingSource(); val original = f.original.plusSeconds(45)
        val selected = f.selected.copy(recurrence = f.selected.recurrence!!.copy(originalStart = original))
        val source = ready.copy(selectedDetails = ready.selectedDetails.copy(occurrence = ready.selectedDetails.occurrence.copy(ref = selected)),
            snapshot = ready.snapshot.copy(start = (ready.snapshot.start as RecurrenceStart.Timed).copy(localDateTime = f.start.localDateTime.plusSeconds(45)),
                validatedSelection = selected, exceptions = listOf(SeriesException(selected, RecurrenceKey.Timed(original)))))
        val controller = f.controller(prepare(source), source)
        assertEquals(RecurrenceKey.Timed(original), controller.deletionBoundary)
        f.next = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.FOLLOWING, selected)
        controller.requestDelete(); controller.confirmDelete()
        assertEquals(original, f.calls.single().selected.recurrence!!.originalStart)
    }
    @Test fun allDayBoundaryUsesOriginalUtcDateAndNotProviderOrDeviceZone() = runBlocking {
        val source = f.allDayFollowingSource(); val launch = prepare(source); val editor = f.controller(launch, source)
        assertEquals(RecurrenceKey.Date(LocalDate.parse("2028-06-19")), editor.deletionBoundary)
        assertEquals("2028-06-19", launch.rawDate)
        assertEquals("2028-06-20", (source.selectedDetails.occurrence.time as EventTime.AllDay).dates.start.toString())
        assertEquals(ZoneId.of("Pacific/Auckland"), source.selectedDetails.eventZone); assertTrue(f.calls.isEmpty())
    }
    @Test fun absentSubsecondOrNonMidnightIdentityNeverFallsBackToActualDate() = runBlocking {
        val ready = f.followingSource()
        val noOriginal = ready.copy(selectedDetails = ready.selectedDetails.copy(occurrence = ready.selectedDetails.occurrence.copy(ref = f.selected.copy(recurrence = null))))
        val nanos = ready.copy(selectedDetails = ready.selectedDetails.copy(occurrence = ready.selectedDetails.occurrence.copy(ref = f.selected.copy(recurrence = f.selected.recurrence!!.copy(originalStart = f.original.plusNanos(1))))))
        val allDay = f.allDayFollowingSource()
        val midnight = allDay.selectedDetails.occurrence.ref
        val wrongMidnight = allDay.copy(selectedDetails = allDay.selectedDetails.copy(occurrence = allDay.selectedDetails.occurrence.copy(ref = midnight.copy(recurrence = midnight.recurrence!!.copy(originalStart = midnight.recurrence!!.originalStart.plusSeconds(60))))))
        for (source in listOf(noOriginal, nanos, wrongMidnight)) {
            assertFalse(canPrepareSeriesScope(source, RecurrenceScope.FOLLOWING, SeriesEditorAction.DELETE))
            assertEquals(SeriesDraftPreparationResult.Unavailable, prepareSeriesDraft(source, RecurrenceScope.FOLLOWING, SeriesEditorAction.DELETE, "invalid", f.store))
        }
        assertTrue(f.calls.isEmpty())
    }
    @Test fun pendingFollowingDeleteRoundTripsAndResolvesWithoutLiveSource() = runBlocking {
        val source = f.followingSource(); val editor = f.controller(prepare(source), source)
        f.next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", f.selected.eventId))
        editor.requestDelete(); editor.confirmDelete(); val original = f.store.records.getValue("series")
        val launch = SeriesDraftCodec.describe(original)!!; assertTrue(launch.needsRecovery); assertEquals(RecurrenceScope.FOLLOWING, launch.scope)
        val restored = f.controller(launch, null); assertEquals(RecurrenceKey.Timed(f.original), restored.deletionBoundary)
        restored.requestDelete(); restored.confirmDelete(); restored.save(); assertEquals(1, f.calls.size)
        f.recovered = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.FOLLOWING, f.selected); restored.recover()
        assertEquals(SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.FOLLOWING, f.selected), restored.state.result)
        assertEquals(0, f.reads); assertTrue(f.store.records.isEmpty()); assertEquals(1, f.calls.size)
    }
    @Test fun proofGatedRetryPreservesFollowingScopeTokenAndOriginalBoundary() = runBlocking {
        val source = f.followingSource(); val editor = f.controller(prepare(source), source)
        f.next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", f.selected.eventId)); editor.requestDelete(); editor.confirmDelete()
        val saved = f.store.records.getValue("series"); val original = f.calls.single()
        val restored = f.controller(SeriesDraftCodec.describe(saved)!!, null); f.proveSafe = true; restored.recover()
        assertTrue(restored.state.retryReady); f.next = SeriesMutationResult.Common(MutationResult.PermissionRequired); restored.retryOriginal()
        assertEquals(listOf(original, original), f.calls); assertEquals(saved, f.store.records.getValue("series"))
        assertEquals(RecurrenceKey.Timed(f.original), restored.deletionBoundary)
    }
    @Test fun wrongScopeSuccessCannotCloseFollowingDraftEvenIfBackendDeletedWholeSeriesAtFirstOrdinal() = runBlocking {
        val source = f.followingSource(); val editor = f.controller(prepare(source), source)
        f.next = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.ALL, f.selected)
        editor.requestDelete(); editor.confirmDelete(); assertTrue(editor.state.recoveryRequired)
        assertTrue(f.store.records.isNotEmpty())
        f.recovered = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.FOLLOWING, f.selected); editor.recover()
        assertTrue(editor.state.result is SeriesMutationResult.LocalDeleted); assertTrue(f.store.records.isEmpty())
    }
    @Test fun submittedFollowingDeleteRejectsDriftedOrMissingFrozenZoneContext() = runBlocking {
        val source = f.followingSource(); val controller = f.controller(prepare(source), source)
        f.next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", f.selected.eventId)); controller.requestDelete(); controller.confirmDelete()
        val saved = f.store.records.getValue("series")
        val drifted = JSONObject(saved.payload).apply { getJSONObject("masterStart").put("zone", "Asia/Tokyo").put("offset", "+09:00") }
        val missing = JSONObject(saved.payload).apply { getJSONObject("frozen").remove("masterStart") }
        assertNull(SeriesDraftCodec.describe(saved.copy(payload = drifted.toString())))
        assertNull(SeriesDraftCodec.describe(saved.copy(payload = missing.toString())))
        assertEquals(saved, f.store.records.getValue("series")); assertEquals(1, f.calls.size)
    }
    @Test fun followingEditFutureFieldsAndUnknownSchemaRemainNonlaunchableWithoutDeletion() = runBlocking {
        prepare(); val original = f.store.records.getValue("series")
        val edits = listOf(JSONObject(original.payload).put("action", "EDIT"),
            JSONObject(original.payload).put("futureTemporalIntent", JSONObject()),
            JSONObject(original.payload).apply { getJSONObject("fields").put("newTailStart", "2030-01-01") },
            JSONObject(original.payload).apply { getJSONObject("rights").put("futurePermission", "ALLOWED") },
            JSONObject(original.payload).put("schema", 3),
            JSONObject(original.payload).apply { getJSONObject("rights").put("delete", "UNKNOWN") })
        edits.forEach { assertNull(SeriesDraftCodec.describe(original.copy(payload = it.toString()))) }
        assertEquals(original, f.store.records.getValue("series")); assertTrue(f.calls.isEmpty())
    }
}
