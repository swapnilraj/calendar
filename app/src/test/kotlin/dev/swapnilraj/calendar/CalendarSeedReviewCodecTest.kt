package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.navigation.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarSeedReviewCodecTest {
    private val empty = CalendarSeedReviewRequest("bd644434-671f-4ec2-b2e4-3b77db826c12",
        CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), null, null, null, emptyList(), null, null, null, null), ZoneId.of("Europe/London"))

    @Test fun missingInputsRemainAbsentAndStableSessionSelectsOneDraft() {
        assertEquals(empty, CalendarSeedReviewCodec.decode(CalendarSeedReviewCodec.encode(empty)))
        assertEquals("incoming:${empty.sessionId}", empty.draftId)
    }

    @Test fun callerTextGuestsRuleZoneAndMetadataRoundTripUnchanged() {
        val request = empty.copy(seed = empty.seed.copy(
            occurrence = CalendarOccurrenceHints(Instant.parse("2026-11-18T23:00:00Z"), Instant.parse("2026-11-19T01:00:00Z"), false),
            title = "Title", location = "Room", description = "Notes\nSecond line", guests = listOf("person@example.test"),
            recurrenceRule = "FREQ=DAILY;COUNT=3", timeZone = ZoneId.of("Asia/Tokyo"), availability = 1, accessLevel = 2))
        assertEquals(request, CalendarSeedReviewCodec.decode(CalendarSeedReviewCodec.encode(request)))
    }

    @Test fun malformedFutureDuplicateAndUnknownFieldsNeverBecomeFreshSession() {
        val raw = CalendarSeedReviewCodec.encode(empty)
        assertNull(CalendarSeedReviewCodec.decode(null))
        assertNull(CalendarSeedReviewCodec.decode(raw.replace("\"version\":1", "\"version\":2")))
        assertNull(CalendarSeedReviewCodec.decode(raw.replace("\"version\":1", "\"version\":1,\"version\":1")))
        assertNull(CalendarSeedReviewCodec.decode(raw.dropLast(1) + ",\"eventId\":5}"))
        assertNull(CalendarSeedReviewCodec.decode(raw + "{}"))
    }

    @Test fun wrongTypesAndInvalidSessionAreRejected() {
        val raw = CalendarSeedReviewCodec.encode(empty)
        assertNull(CalendarSeedReviewCodec.decode(raw.replace("\"version\":1", "\"version\":\"1\"")))
        assertNull(CalendarSeedReviewCodec.decode(raw.replace(empty.sessionId, "not-a-session")))
        assertNull(CalendarSeedReviewCodec.decode(raw.replace("\"guests\":[]", "\"guests\":[1]")))
        assertNull(CalendarSeedReviewCodec.decode(raw.replace("\"allDay\":null", "\"allDay\":1")))
    }

    @Test fun boundedPayloadNeverTruncatesIntoDifferentSeed() {
        assertNull(CalendarSeedReviewCodec.decode(" ".repeat(65_537)))
        assertThrows(IllegalArgumentException::class.java) { CalendarSeedReviewCodec.encode(empty.copy(seed = empty.seed.copy(description = "x".repeat(16_385)))) }
        assertThrows(IllegalArgumentException::class.java) { CalendarSeedReviewCodec.encode(empty.copy(seed = empty.seed.copy(guests = List(101) { "a@example.test" }))) }
    }

    @Test fun backwardsIntervalAndInvalidMetadataFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { CalendarSeedReviewCodec.encode(empty.copy(seed = empty.seed.copy(
            occurrence = CalendarOccurrenceHints(Instant.ofEpochMilli(10), Instant.ofEpochMilli(0), false)))) }
        assertThrows(IllegalArgumentException::class.java) { CalendarSeedReviewCodec.encode(empty.copy(seed = empty.seed.copy(accessLevel = 99))) }
    }
}
