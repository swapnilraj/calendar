package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.navigation.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarEditReviewCodecTest {
    private val fixture = EditReviewFixture()
    @Test fun rawSuggestionsAndSeparateOriginalAuthorityRoundTripWithoutNormalizingGuestsOrRule() {
        val request = fixture.request(fixture.seed().copy(title = "", description = "Raw\nnotes", guests = listOf("not an address", "guest@example.invalid"),
            recurrenceRule = "FREQ=INVALID;X-CUSTOM=yes", timeZone = ZoneId.of("Asia/Tokyo"), availability = 1, accessLevel = 0,
            occurrence = CalendarOccurrenceHints(Instant.parse("2030-03-02T09:00:00Z"), Instant.parse("2029-03-02T08:00:00Z"), false)))
        assertEquals(request, CalendarEditReviewCodec.decode(CalendarEditReviewCodec.encode(request)))
        assertEquals("incoming-edit:${request.sessionId}", request.nativeId)
        assertFalse(request.nativeId.startsWith("incoming:"))
    }
    @Test fun missingProposedFieldsStayAbsentRatherThanBecomingBaselineOrNow() {
        val request = fixture.request(fixture.seed().copy(title = null))
        assertEquals(request, CalendarEditReviewCodec.decode(CalendarEditReviewCodec.encode(request)))
        assertNull(request.proposed.occurrence.begin); assertNull(request.proposed.occurrence.end)
    }
    @Test fun wrongTypesDuplicateUnknownAndFutureSchemaFailClosed() {
        val raw = CalendarEditReviewCodec.encode(fixture.request())
        assertNull(CalendarEditReviewCodec.decode(null))
        assertNull(CalendarEditReviewCodec.decode(raw.replace("\"schema\":1", "\"schema\":2")))
        assertNull(CalendarEditReviewCodec.decode(raw.replace("\"schema\":1", "\"schema\":\"1\"")))
        assertNull(CalendarEditReviewCodec.decode(raw.replace("\"schema\":1", "\"schema\":1,\"schema\":1")))
        assertNull(CalendarEditReviewCodec.decode(raw.dropLast(1) + ",\"writeNow\":true}"))
        assertNull(CalendarEditReviewCodec.decode(raw.replace("\"guests\":[]", "\"guests\":[17]")))
        assertNull(CalendarEditReviewCodec.decode(raw + "{}"))
    }
    @Test fun mismatchedTargetAndBindingCannotAuthorizeAnotherCalendarOrEvent() {
        val request = fixture.request()
        assertThrows(IllegalArgumentException::class.java) { CalendarEditReviewCodec.encode(request.copy(binding = request.binding.copy(eventId = 99))) }
        assertNull(CalendarEditReviewCodec.decode(CalendarEditReviewCodec.encode(request).replace(request.sessionId, "not-a-session")))
    }
    @Test fun oversizedInputsAreRejectedWholeNeverTruncated() {
        assertNull(CalendarEditReviewCodec.decode(" ".repeat(65_537)))
        assertThrows(IllegalArgumentException::class.java) { CalendarEditReviewCodec.encode(fixture.request(fixture.seed().copy(description = "x".repeat(16_385)))) }
    }
}
