package dev.swapnilraj.calendar

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.android.navigation.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarIntentDecoderTest {
    private val decoder = CalendarIntentDecoder(ZoneOffset.UTC)
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val end = start.plusSeconds(3600)
    private fun view(path: String) = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar$path"))
    private fun create() = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
    private fun Intent.bounds() = putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())

    @Test fun dateLinksUseDisplayZoneAndKeepNegativeEpochTimes() {
        val instant = Instant.ofEpochMilli(-1)
        val route = decoder.decode(view("/time/-1")) as CalendarIntentRoute.OpenDate
        assertEquals(instant, route.instant)
        assertEquals(LocalDate.of(1969, 12, 31), route.date)
        val la = CalendarIntentDecoder(ZoneId.of("America/Los_Angeles"))
        val midnight = Instant.parse("2026-11-18T00:00:00Z")
        assertEquals(LocalDate.of(2026, 11, 17),
            (la.decode(view("/time/${midnight.toEpochMilli()}")) as CalendarIntentRoute.OpenDate).date)
    }

    @Test fun eventLinksPreserveLocalIdAndOccurrenceHintsWithoutInventingCalendarIdentity() {
        val route = decoder.decode(view("/events/7").bounds()) as CalendarIntentRoute.OpenEvent
        assertEquals(7L, route.eventId)
        assertEquals(start, route.occurrence.begin)
        assertEquals(end, route.occurrence.end)
        assertNull(route.occurrence.allDay)
        val unqualified = decoder.decode(view("/events/7")) as CalendarIntentRoute.OpenEvent
        assertNull(unqualified.occurrence.begin)
        assertNull(unqualified.occurrence.end)
    }

    @Test fun eventLinkMayHaveOnlyAStartHint() {
        val route = decoder.decode(view("/events/7")
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())) as CalendarIntentRoute.OpenEvent
        assertEquals(start, route.occurrence.begin)
        assertNull(route.occurrence.end)
    }

    @Test fun invalidAndSpoofedUrisNeverBecomeRoutesToAnotherAuthority() {
        for (uri in listOf(
            "https://com.android.calendar/events/7", "file:///events/7", "content://evil.example/events/7",
            "content://com.android.calendar.evil/events/7", "content://user@com.android.calendar/events/7",
            "content://com.android.calendar:443/events/7", "content://%63om.android.calendar/events/7",
            "content://com.android.calendar/events/7?caller_is_syncadapter=true",
            "content://com.android.calendar/events/7#other", "content:events/7",
        )) assertTrue(uri, decoder.decode(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) is CalendarIntentRoute.Invalid)
    }

    @Test fun pathShapeAndNumericIdsAreStrict() {
        for (path in listOf("/events", "/events/-1", "/events/7/", "/events//7", "//events/7",
            "/events/7/events/8", "/events/7/8", "/events/%37", "/events/7%2F8", "/events/+7",
            "/events/١", "/events/9223372036854775808", "/time", "/time/nope", "/time/1/2")) {
            assertTrue(path, decoder.decode(view(path)) is CalendarIntentRoute.Invalid)
        }
        assertEquals(Long.MAX_VALUE, (decoder.decode(view("/events/${Long.MAX_VALUE}")) as CalendarIntentRoute.OpenEvent).eventId)
    }

    @Test fun outOfRangeInstantsAreRejectedBeforeTheyReachDatePickers() {
        for (millis in listOf(Long.MIN_VALUE, Long.MAX_VALUE,
            Instant.parse("0000-12-31T23:59:59Z").toEpochMilli(), Instant.parse("+10000-01-01T00:00:00Z").toEpochMilli())) {
            assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.OUT_OF_RANGE_TIME), decoder.decode(view("/time/$millis")))
            assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.OUT_OF_RANGE_TIME), decoder.decode(create()
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis)))
        }
        for (text in listOf("0001-01-01T00:00:00Z", "9999-12-31T23:59:59Z")) {
            assertTrue(decoder.decode(view("/time/${Instant.parse(text).toEpochMilli()}")) is CalendarIntentRoute.OpenDate)
        }
        assertTrue(decoder.decode(view("/time/99999999999999999999999")) is CalendarIntentRoute.Invalid)
        assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.OUT_OF_RANGE_TIME), decoder.decode(create()
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, Instant.parse("9999-12-31T23:59:59Z").toEpochMilli())
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, "+18:00")))
    }

    @Test fun typedExtrasRejectCoercionsAndMalformedCollections() {
        for (intent in listOf(
            create().putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, "123"),
            create().putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 123),
            create().putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, "true"),
            create().putExtra(CalendarContract.Events.TITLE, 5),
            create().putExtra(CalendarContract.Events.DESCRIPTION, false),
            create().putExtra(CalendarContract.Events.AVAILABILITY, 1L),
            create().putExtra(CalendarContract.Events.RRULE, 3),
            create().putExtra(Intent.EXTRA_EMAIL, arrayOf<Any>("guest@example.test", 42)),
            view("/events/3").putExtra(CalendarContract.EXTRA_EVENT_END_TIME, "wrong"),
        )) assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.INVALID_EXTRA_TYPE), decoder.decode(intent))
    }

    @Test fun backwardsIntervalsAndOrphanEndTimesAreRejected() {
        assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.INVALID_INTERVAL), decoder.decode(create()
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, end.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.toEpochMilli())))
        assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.MISSING_BEGIN), decoder.decode(view("/events/7")
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())))
        val point = decoder.decode(create().putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.toEpochMilli())) as CalendarIntentRoute.CreateDraftSeed
        assertEquals(point.seed.occurrence.begin, point.seed.occurrence.end)
    }

    @Test fun allDayHintsUseUtcDatesAndExclusiveEndIndependentlyOfDisplayZone() {
        val midnight = Instant.parse("2026-11-18T00:00:00Z")
        val route = CalendarIntentDecoder(ZoneId.of("America/Los_Angeles")).decode(create()
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, midnight.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, midnight.plusSeconds(172800).toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)) as CalendarIntentRoute.CreateDraftSeed
        assertEquals(LocalDate.of(2026, 11, 18), route.seed.occurrence.allDayStartDate)
        assertEquals(LocalDate.of(2026, 11, 20), route.seed.occurrence.allDayEndDateExclusive)
        assertTrue(route.seed.occurrence.allDay == true)
        assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.INVALID_INTERVAL), decoder.decode(create()
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, midnight.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, midnight.plusSeconds(3600).toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)))
    }

    @Test fun createSeedsPreservePlainInputsAndNeverMutateTheIncomingIntent() {
        val intent = create().bounds()
            .putExtra(CalendarContract.Events.TITLE, "<script>plain input</script>")
            .putExtra(CalendarContract.Events.EVENT_LOCATION, "Office")
            .putExtra(CalendarContract.Events.DESCRIPTION, "Notes and https://example.test/meeting")
            .putExtra(Intent.EXTRA_EMAIL, "one@example.test, two@example.test;three@example.test")
            .putExtra(CalendarContract.Events.RRULE, "FREQ=WEEKLY;BYDAY=WE")
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, "Europe/London")
            .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
            .putExtra(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_PRIVATE)
        val beforeKeys = intent.extras!!.keySet().toSet()
        val route = decoder.decode(intent) as CalendarIntentRoute.CreateDraftSeed
        assertEquals("<script>plain input</script>", route.seed.title)
        assertEquals("Office", route.seed.location)
        assertEquals("Notes and https://example.test/meeting", route.seed.description)
        assertEquals(listOf("one@example.test", "two@example.test", "three@example.test"), route.seed.guests)
        assertEquals("FREQ=WEEKLY;BYDAY=WE", route.seed.recurrenceRule)
        assertEquals(ZoneId.of("Europe/London"), route.seed.timeZone)
        assertEquals(CalendarContract.Events.AVAILABILITY_FREE, route.seed.availability)
        assertEquals(CalendarContract.Events.ACCESS_PRIVATE, route.seed.accessLevel)
        // Decoding only constructs a value. A caller may discard it, and decoding again does not save/mutate anything.
        assertEquals(route, decoder.decode(intent))
        assertEquals(beforeKeys, intent.extras!!.keySet())
        assertEquals("<script>plain input</script>", intent.getStringExtra(CalendarContract.Events.TITLE))
    }

    @Test fun emailArraysAndMimeOnlyCreationAreSupportedWithoutSupplyingDefaultDates() {
        val route = decoder.decode(Intent(Intent.ACTION_INSERT).setType(CalendarIntentDecoder.EVENTS_MIME_TYPE)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("one@example.test", "two@example.test"))) as CalendarIntentRoute.CreateDraftSeed
        assertEquals(listOf("one@example.test", "two@example.test"), route.seed.guests)
        assertNull(route.seed.occurrence.begin)
        assertNull(route.seed.occurrence.end)
        assertNull(route.seed.occurrence.allDay)
        assertTrue(decoder.decode(Intent(Intent.ACTION_INSERT).setType(CalendarIntentDecoder.EVENT_MIME_TYPE))
            is CalendarIntentRoute.CreateDraftSeed)
        val list = create().putStringArrayListExtra(Intent.EXTRA_EMAIL, arrayListOf("one@example.test"))
        assertEquals(listOf("one@example.test"), (decoder.decode(list) as CalendarIntentRoute.CreateDraftSeed).seed.guests)
    }

    @Test fun editRetainsEventIdentityAndOptionalOccurrenceBoundsForLaterResolution() {
        val route = decoder.decode(Intent(Intent.ACTION_EDIT, Uri.parse("content://com.android.calendar/events/42"))
            .bounds().putExtra(CalendarContract.Events.TITLE, "Review proposed title")) as CalendarIntentRoute.EditEvent
        assertEquals(42L, route.eventId)
        assertEquals(start, route.seed.occurrence.begin)
        assertEquals(end, route.seed.occurrence.end)
        assertEquals("Review proposed title", route.seed.title)
        assertTrue(decoder.decode(Intent(Intent.ACTION_EDIT, CalendarContract.Events.CONTENT_URI)) is CalendarIntentRoute.Invalid)
    }

    @Test fun unknownActionsAndUrisNeverLaunchAnything() {
        assertEquals(CalendarIntentRoute.Unsupported, decoder.decode(null))
        assertEquals(CalendarIntentRoute.Unsupported, decoder.decode(Intent()))
        assertEquals(CalendarIntentRoute.Unsupported, decoder.decode(Intent(Intent.ACTION_SEND, Uri.parse("https://example.test"))))
        assertTrue(decoder.decode(view("/unknown/7")) is CalendarIntentRoute.Invalid)
        assertTrue(decoder.decode(Intent(Intent.ACTION_INSERT)) is CalendarIntentRoute.Invalid)
        assertEquals(CalendarIntentRoute.Unsupported, decoder.decode(create().setDataAndType(CalendarContract.Events.CONTENT_URI, "text/plain")))
    }

    @Test fun invalidFormValuesAreRejectedWithoutExposingTheirContent() {
        for (intent in listOf(create().putExtra(CalendarContract.Events.EVENT_TIMEZONE, "Not/AZone"),
            create().putExtra(CalendarContract.Events.AVAILABILITY, 999),
            create().putExtra(CalendarContract.Events.ACCESS_LEVEL, -1))) {
            assertEquals(CalendarIntentRoute.Invalid(CalendarIntentError.INVALID_SEED_VALUE), decoder.decode(intent))
        }
    }
}
