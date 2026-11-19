package dev.swapnilraj.calendar.data.calendar

import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Shared authoring/retirement boundaries; no native/account acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeetingPublicationSeamTest {
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val time = EventTime.Timed(start, start.plusSeconds(3600))
    private val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A")
    private val draft = EventDraft(CalendarId(2), time, ZoneId.of("Europe/London"), "Synthetic meeting seam", targetIdentity = identity)
    private val ref = EventRef(CalendarId(2), 10, time.start, time.end)
    private val token = EditToken("synthetic-opaque-source-token")
    private data class Fixture(val provider: CalendarWriterTest.Provider, val author: CalendarWriter,
        val meetings: CalendarMeetingWriter, val store: CalendarLocalStore, val retirement: CalendarOperationRetirement)
    private suspend fun fixture(granted: Boolean = false, block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = CalendarWriterTest.Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val name = "meeting-publication-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        try {
            block(Fixture(provider, CalendarWriter(context.contentResolver, reader, store, { granted }),
                CalendarMeetingWriter(context.contentResolver, reader, store, { granted }), store, CalendarOperationRetirement(store)))
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); context.deleteDatabase(name) }
    }
    private fun invalid(block: () -> Unit): Set<InvalidEventField> {
        try { block(); fail("Expected authored validation failure") }
        catch (value: CalendarWriter.Invalid) { return value.fields }
        error("No authored validation failure")
    }

    @Test fun meetingOnlyAuthoredZoneCheckMatchesBothHelpersWithoutReconstructingRawKeepFields() = runBlocking { fixture { f ->
        val bad = draft.copy(eventZone = ZoneId.of("+05:30"), availability = 99)
        assertEquals(setOf(InvalidEventField.ZONE), invalid { f.author.meetingOneOffValues(bad, f.provider.calendar) })
        assertEquals(setOf(InvalidEventField.ZONE), invalid {
            f.author.meetingOneOffPatchValues(CalendarId(2), time, null, mapOf("eventTimezone" to null), f.provider.calendar,
                emptyList(), EventPatch(time = FieldChange.Set(time), eventZone = FieldChange.Set(bad.eventZone)))
        })
        val sparse = f.author.meetingOneOffPatchValues(CalendarId(2), time, null,
            mapOf("eventTimezone" to null, "eventEndTimezone" to "unknown-imported-zone"), f.provider.calendar,
            emptyList(), EventPatch(title = FieldChange.Set("Changed")))
        assertEquals(setOf("title"), sparse.keySet()); assertEquals("Changed", sparse.getAsString("title"))
        assertTrue(f.provider.queryCounts.isEmpty()); assertEquals(0, f.provider.batchCount)
    } }

    @Test fun ordinaryAndMeetingAuthoringValidateNativeZoneBeforeMetadata() = runBlocking { fixture(granted = true) { f ->
        val bad = draft.copy(eventZone = ZoneId.of("+05:30"), availability = 99)
        assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE)), f.author.create("ordinary-invalid", bad))
        assertEquals(setOf(InvalidEventField.ZONE), invalid { f.author.meetingOneOffValues(bad, f.provider.calendar) })
        assertNull(f.store.operationHeader("ordinary-invalid")); assertEquals(0, f.provider.batchCount)
    } }

    @Test fun retiredOneoffResponseFencesLateWorkersAndChangedResponseWithoutPermissionsOrPrivateReads() = runBlocking { fixture { f ->
        val command = MeetingCommand.Respond(RecurrenceScope.THIS, MeetingResponse.YES)
        val request = RetirableOperation.Meeting("oneoff", ref, token, command)
        assertEquals(OperationRetirementResult.Retired("oneoff"), f.retirement.retire(request))
        assertEquals(MeetingMutationResult.Common(MutationResult.Retired("oneoff")), f.meetings.mutate("oneoff", ref, token, command))
        assertEquals(MeetingMutationResult.Common(MutationResult.Retired("oneoff")), f.meetings.recover("oneoff"))
        val changed = command.copy(response = MeetingResponse.NO)
        assertEquals(OperationRetirementResult.Conflict("oneoff"), f.retirement.inspect(request.copy(command = changed)))
        assertEquals(MeetingMutationResult.Common(MutationResult.Conflict(null)), f.meetings.mutate("oneoff", ref, token, changed))
        assertTrue(f.provider.queryCounts.isEmpty()); assertEquals(0, f.provider.batchCount)
    } }

    @Test fun retiredAllAndExistingThisResponsesKeepExactOccurrenceIdentityWithoutCloneAdmission() = runBlocking { fixture { f ->
        val generated = ref.copy(instanceStart = start.plusSeconds(86400), instanceEnd = time.end.plusSeconds(86400),
            recurrence = RecurrenceRef(10, start.plusSeconds(86400)))
        val moved = ref.copy(eventId = 11, instanceStart = start.plusSeconds(20 * 86400), instanceEnd = time.end.plusSeconds(20 * 86400),
            recurrence = RecurrenceRef(10, start.plusSeconds(2 * 86400)))
        for ((index, pair) in listOf(generated to RecurrenceScope.ALL, moved to RecurrenceScope.THIS).withIndex()) {
            val (selected, scope) = pair
            val command = MeetingCommand.Respond(scope, MeetingResponse.MAYBE)
            val request = RetirableOperation.Meeting("series-$index", selected, token, command)
            assertEquals(OperationRetirementResult.Retired(request.operationId), f.retirement.retire(request))
            val expected = MeetingMutationResult.Common(MutationResult.Retired(request.operationId))
            assertEquals(expected, f.meetings.mutate(request.operationId, selected, token, command))
            assertEquals(expected, f.meetings.recover(request.operationId))
            assertEquals(OperationRetirementResult.Conflict(request.operationId),
                f.retirement.inspect(request.copy(selected = selected.copy(instanceEnd = selected.instanceEnd.plusSeconds(1)))))
        }
        assertTrue(f.provider.queryCounts.isEmpty()); assertEquals(0, f.provider.batchCount)
    } }
}
