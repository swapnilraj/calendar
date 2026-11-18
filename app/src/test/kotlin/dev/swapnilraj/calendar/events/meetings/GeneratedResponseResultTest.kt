package dev.swapnilraj.calendar.events.meetings

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.meetings.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeneratedResponseResultTest {
    private val f = MeetingResponseFixture()
    @After fun stop() { f.scope.cancel() }
    private fun generated(): EditableMeeting {
        val selected = f.selected.copy(recurrence = RecurrenceRef(f.selected.eventId, f.selected.instanceStart))
        return f.source.copy(details = f.source.details.copy(occurrence = f.source.details.occurrence.copy(ref = selected)),
            scopes = f.source.scopes + (RecurrenceScope.ALL to MeetingScopeAccess(respond = AccessDecision.ALLOWED)))
    }
    private suspend fun controller(source: EditableMeeting = generated()): MeetingResponseController = f.controller(f.prepare(source), source)
    private fun saved(source: EditableMeeting = generated(), id: Long = 99, scope: RecurrenceScope = RecurrenceScope.THIS) =
        MeetingMutationResult.LocalSaved(source.details.occurrence.ref.copy(eventId = id), scope)

    @Test fun generatedThisAcceptsOnlyNewExceptionForExactFrozenOccurrence() = runBlocking {
        val source = generated(); val controller = controller(source); f.choose(controller)
        val result = saved(source); f.next = result; controller.save()
        assertEquals(result, controller.state.result); assertEquals(result, controller.state.localOutcome)
        assertTrue(f.store.rows.isEmpty()); assertEquals(1, f.calls.size)
        assertEquals(source.details.occurrence.ref, f.calls.single().selected)
        assertEquals(MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.YES), f.calls.single().command)
    }

    @Test fun generatedThisRejectsForeignParentOriginalBoundsOrNonExceptionIdentity() = runBlocking {
        val source = generated(); val ref = saved(source).selected
        val invalid = listOf(ref.copy(calendarId = CalendarId(3)), ref.copy(recurrence = RecurrenceRef(43, f.start)),
            ref.copy(recurrence = RecurrenceRef(42, f.start.plusSeconds(1))), ref.copy(recurrence = null),
            ref.copy(instanceStart = ref.instanceStart.plusSeconds(1)), ref.copy(instanceEnd = ref.instanceEnd.plusSeconds(1)),
            ref.copy(eventId = 0), ref.copy(eventId = 42))
        for (selected in invalid) {
            f.store.rows.clear(); f.calls.clear()
            val controller = controller(source); f.choose(controller)
            f.next = MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS); controller.save()
            assertNull(controller.state.result); assertNull(controller.state.localOutcome)
            assertTrue(controller.state.recoveryRequired); assertEquals(MeetingResponseProblem.UNCERTAIN, controller.state.problem)
            assertTrue(f.store.rows.isNotEmpty()); assertEquals(1, f.calls.size)
        }
    }

    @Test fun materializedThisKeepsStrictIdentityAndNeverAcceptsAnotherException() = runBlocking {
        val generated = generated()
        val selected = generated.details.occurrence.ref.copy(eventId = 57)
        val source = generated.copy(details = generated.details.copy(occurrence = generated.details.occurrence.copy(ref = selected)))
        val controller = controller(source); f.choose(controller); f.next = saved(source); controller.save()
        assertNull(controller.state.result); assertTrue(f.store.rows.isNotEmpty())
        f.store.rows.clear()
        val exact = controller(source); f.choose(exact)
        val result = MeetingMutationResult.LocalSaved(selected, RecurrenceScope.THIS); f.next = result; exact.save()
        assertEquals(result, exact.state.result); assertTrue(f.store.rows.isEmpty())
    }

    @Test fun allScopeKeepsStrictIdentityEvenWhenSelectionIsGenerated() = runBlocking {
        val source = generated(); val controller = controller(source); f.choose(controller, scope = RecurrenceScope.ALL)
        f.next = saved(source, scope = RecurrenceScope.ALL); controller.save()
        assertNull(controller.state.result); assertTrue(f.store.rows.isNotEmpty())
        f.store.rows.clear()
        val exact = controller(source); f.choose(exact, scope = RecurrenceScope.ALL)
        val result = MeetingMutationResult.LocalSaved(source.details.occurrence.ref, RecurrenceScope.ALL); f.next = result; exact.save()
        assertEquals(result, exact.state.result); assertTrue(f.store.rows.isEmpty())
    }

    @Test fun sourceFreeGeneratedRecoveryAcceptsOnlySameFrozenOccurrence() = runBlocking {
        val source = generated(); val controller = controller(source); f.choose(controller)
        f.next = MeetingMutationResult.Common(MutationResult.Uncertain("ignored", null)); controller.save()
        val original = f.record(); val operation = controller.operationId
        val restored = f.controller(f.launch(), null)
        f.recoverResult = saved(source).let { it.copy(selected = it.selected.copy(instanceEnd = it.selected.instanceEnd.plusSeconds(1))) }
        restored.recover(); assertNull(restored.state.result); assertEquals(original, f.record())
        val result = saved(source); f.recoverResult = result; restored.recover()
        assertEquals(result, restored.state.result); assertTrue(f.store.rows.isEmpty())
        assertEquals(listOf(operation, operation), f.recoveries); assertEquals(1, f.calls.size)
    }

    @Test fun missingDurableNamespaceDoesNotBroadenLegacyFrozenSuccess() = runBlocking {
        val source = generated(); val controller = controller(source); f.choose(controller)
        f.next = MeetingMutationResult.Common(MutationResult.Uncertain("ignored", null)); controller.save()
        val original = f.record()
        val legacy = original.copy(payload = JSONObject(original.payload).put("sourceCalendarIdentity", JSONObject.NULL).toString())
        f.store.rows[original.id] = legacy
        val restored = f.controller(f.launch(), null); f.recoverResult = saved(source); restored.recover()
        assertNull(restored.state.result); assertEquals(legacy, f.record()); assertTrue(restored.state.recoveryRequired)
    }

    @Test fun generatedAllDayResponseKeepsExactUtcDateBounds() = runBlocking {
        val date = LocalDate.of(2028, 6, 15); val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val ref = EventRef(CalendarId(2), 42, start, start.plusSeconds(86400), RecurrenceRef(42, start))
        val source = generated().let { it.copy(details = it.details.copy(occurrence = it.details.occurrence.copy(ref = ref,
            time = EventTime.AllDay(CalendarDateRange(date, date.plusDays(1)))), eventZone = ZoneId.of("Pacific/Honolulu"))) }
        val controller = controller(source); f.choose(controller)
        val result = saved(source); f.next = result; controller.save()
        assertEquals(result, controller.state.result); assertTrue(f.store.rows.isEmpty())
    }
}
