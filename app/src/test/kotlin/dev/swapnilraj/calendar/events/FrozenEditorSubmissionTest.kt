package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.FrozenEditorSubmission
import java.time.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FrozenEditorSubmissionTest {
    private val start = Instant.parse("2026-10-25T00:45:00Z")
    private val end = Instant.parse("2026-10-25T01:15:00Z")
    private val ref = EventRef(CalendarId(2), 7, start, end)

    @Test fun timedAndAllDayCreateRequestsRoundTripWithoutReinterpretingDates() {
        val timed = EventDraft(CalendarId(2), EventTime.Timed(start, end), ZoneId.of("Europe/London"),
            "Title \"quoted\"", "Studio", "Notes\nsecond line", listOf(0, 10, 30))
        val allDay = timed.copy(time = EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-02-29"), LocalDate.parse("2028-03-02"))), eventZone = null)
        for (draft in listOf(timed, allDay)) {
            val request = FrozenEditorSubmission.Create("stable-operation", draft)
            assertEquals(request, FrozenEditorSubmission.decode(request.encode()))
        }
    }
    @Test fun updatePreservesKeepVersusExplicitEmptyNullAndOriginalIdentity() {
        val recurringIdentity = ref.copy(recurrence = RecurrenceRef(5, start.minusSeconds(86400)))
        val request = FrozenEditorSubmission.Update("stable-operation", recurringIdentity, EditToken("original-token"), EventPatch(
            title = FieldChange.Set(""), eventZone = FieldChange.Set(null), alertMinutes = FieldChange.Set(emptyList())))
        val decoded = FrozenEditorSubmission.decode(request.encode()) as FrozenEditorSubmission.Update
        assertEquals(request, decoded)
        assertEquals(FieldChange.Keep, decoded.patch.time)
        assertEquals(FieldChange.Keep, decoded.patch.description)
        assertEquals(FieldChange.Set(null), decoded.patch.eventZone)
    }
    @Test fun deleteRemainsDeleteWithTheOriginalTokenAndRef() {
        val request = FrozenEditorSubmission.Delete("stable-operation", ref, EditToken("original-token"))
        assertEquals(request, FrozenEditorSubmission.decode(request.encode()))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownCommandVersionIsRejected() {
        val json = FrozenEditorSubmission.Delete("stable-operation", ref, EditToken("original-token")).encode().put("version", 99)
        FrozenEditorSubmission.decode(json)
    }
    @Test(expected = IllegalArgumentException::class) fun partialRecurrenceIdentityIsRejected() {
        val json = FrozenEditorSubmission.Delete("stable-operation", ref, EditToken("original-token")).encode()
        json.getJSONObject("ref").put("master", 5)
        FrozenEditorSubmission.decode(json)
    }
}
