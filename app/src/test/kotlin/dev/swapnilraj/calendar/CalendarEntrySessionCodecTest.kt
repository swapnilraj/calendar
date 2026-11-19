package dev.swapnilraj.calendar

import android.app.Application
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo
import java.time.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalendarEntrySessionCodecTest {
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val route = CalendarIntentRoute.OpenEvent(7, CalendarOccurrenceHints(start, start.plusSeconds(3600), false))
    private val calendar = CalendarIdentity(CalendarId(2), CalendarAccount("synthetic@example.invalid", "local"), "owner", "calendar-A")
    private fun pinned(): CalendarEntrySession = CalendarEntrySession.create(route).also {
        assertTrue(it.bind(EventNavigationInfo(7, calendar, "event-A", false, start, start.plusSeconds(3600), false)))
    }
    @Test fun knownFirstPinAndNonceSurviveRecreationWithoutAnyDetailsPayload() {
        val before = pinned(); val encoded = CalendarEntrySessionCodec.encode(before)
        val after = (CalendarEntrySessionCodec.restore(route, encoded) as CalendarEntryRestore.Ready).session
        assertEquals(before.id, after.id); assertEquals(before.binding, after.binding)
        assertFalse(after.bind(EventNavigationInfo(7, calendar.copy(providerId = "calendar-B"), "event-A", false, start, start.plusSeconds(3600), false)))
        assertFalse(after.bind(EventNavigationInfo(7, calendar, null, false, start, start.plusSeconds(3600), false)))
        assertEquals(before.binding, after.binding)
    }
    @Test fun malformedFutureDuplicateMissingCoercedAndWrongRequestNeverStartAFreshSession() {
        val value = CalendarEntrySessionCodec.encode(pinned())
        for (bad in listOf(null, "", "{}", value + "{}", value.replace("\"version\":1", "\"version\":2"),
            value.replace("\"version\":1", "\"version\":1,\"version\":1"), value.replace("\"event\":7", "\"event\":\"7\""),
            value.replace("\"event\":7", "\"event\":8"), value.replace("\"calendar\":2", "\"calendar\":-1"),
            value.replace("synthetic@example.invalid", ""), value.replace("\"eventUid\":\"event-A\"", "\"eventUid\":false"),
            value.replace("\"version\":1", "\"version\":1,\"future\":true")))
            assertEquals(CalendarEntryRestore.Unavailable, CalendarEntrySessionCodec.restore(route, bad))
        assertEquals(CalendarEntryRestore.Unavailable, CalendarEntrySessionCodec.restore(route.copy(eventId = 8), value))
    }
    @Test fun rawCallerSeedFieldsAreDigestedNotSavedAndChangedInputsCannotReuseNonce() {
        val seed = CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), "Private synthetic seed", "Seed location", "Seed description",
            listOf("guest@example.invalid"), "FREQ=ADVANCED", ZoneOffset.UTC, 1, 2)
        val before = CalendarEntrySession.create(CalendarIntentRoute.CreateDraftSeed(seed))
        val encoded = CalendarEntrySessionCodec.encode(before)
        for (plain in listOf(seed.title!!, seed.location!!, seed.description!!, seed.guests.single(), seed.recurrenceRule!!)) assertFalse(encoded.contains(plain))
        assertTrue(CalendarEntrySessionCodec.restore(before.route, encoded) is CalendarEntryRestore.Ready)
        assertEquals(CalendarEntryRestore.Unavailable, CalendarEntrySessionCodec.restore(CalendarIntentRoute.CreateDraftSeed(seed.copy(title = "Changed")), encoded))
    }
    @Test fun absoluteDateRequestCanRestoreAfterDisplayZoneChange() {
        val before = CalendarEntrySession.create(CalendarIntentRoute.OpenDate(LocalDate.of(2026, 11, 18), start))
        val changedLocalDate = CalendarIntentRoute.OpenDate(LocalDate.of(2026, 11, 19), start)
        assertTrue(CalendarEntrySessionCodec.restore(changedLocalDate, CalendarEntrySessionCodec.encode(before)) is CalendarEntryRestore.Ready)
    }
}
