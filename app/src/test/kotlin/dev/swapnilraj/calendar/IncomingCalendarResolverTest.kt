package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IncomingCalendarResolverTest {
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val calendar = CalendarInfo(CalendarId(2), "Synthetic", 123, CalendarAccount("synthetic@example.invalid", "local"),
        "owner", null, 700, false, true, providerId = "calendar-A")
    private val ref = EventRef(calendar.id, 7, start, start.plusSeconds(3600))
    private val occurrence = EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic private title", 123)
    private val base = EventNavigationInfo(7, calendar.identity, "event-A", false, ref.instanceStart, ref.instanceEnd, false)
    private fun route(begin: Instant? = null, end: Instant? = null, allDay: Boolean? = null) = CalendarIntentRoute.OpenEvent(7, CalendarOccurrenceHints(begin, end, allDay))
    private fun seed() = CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), "Plain seed title", "Plain seed room", "Plain seed notes",
        listOf("guest@example.invalid"), "FREQ=UNSUPPORTED", null, 1, 2)
    private inner class Source : IncomingCalendarSource {
        var info = base
        var calendars = listOf(calendar)
        var rows = listOf(occurrence)
        var infoFailure: CalendarReadState<EventNavigationInfo?>? = null
        var snapshotFailure: CalendarReadState<CalendarSnapshot>? = null
        var detailsFailure: CalendarReadState<EventDetails>? = null
        var onSnapshot: (suspend () -> Unit)? = null
        var onDetails: (suspend () -> Unit)? = null
        val infos = AtomicInteger(); val snapshots = AtomicInteger(); val details = AtomicInteger()
        var request: CalendarRequest? = null
        override suspend fun readEventInfo(eventId: Long): CalendarReadState<EventNavigationInfo?> { infos.incrementAndGet(); return infoFailure ?: CalendarReadState.Ready(info) }
        override suspend fun readSnapshot(request: CalendarRequest): CalendarReadState<CalendarSnapshot> {
            snapshots.incrementAndGet(); this.request = request
            val result = snapshotFailure ?: CalendarReadState.Ready(CalendarSnapshot(request, calendars, rows))
            onSnapshot?.invoke(); return result
        }
        override suspend fun readDetails(ref: EventRef): CalendarReadState<EventDetails> {
            details.incrementAndGet()
            val result = detailsFailure ?: CalendarReadState.Ready(EventDetails(rows.single(), calendars.single(), description = "Synthetic private notes"))
            onDetails?.invoke(); return result
        }
    }

    @Test fun standaloneIdOnlyUsesProviderBoundsAndExactCanonicalSnapshotWithoutDisplayFilters() = runBlocking {
        val source = Source(); val session = CalendarEntrySession.create(route())
        val result = IncomingCalendarResolver(source).resolve(session, ZoneOffset.UTC) as CalendarEntryResolution.Event
        assertEquals(ref, result.ref); assertEquals(calendar.identity, result.binding.calendar)
        assertEquals(CalendarSelection.Explicit(setOf(calendar.id)), source.request!!.selection)
        assertEquals(CalendarDateRange.day(LocalDate.of(2026, 11, 18)), source.request!!.range)
        assertEquals(2, source.infos.get()); assertEquals(1, source.snapshots.get())
    }

    @Test fun longEventStillQueriesOneLocalDayRatherThanExpandingItsWholeDuration() = runBlocking {
        val source = Source(); val end = start.plusSeconds(365L * 86400)
        source.info = base.copy(endExclusive = end)
        source.rows = listOf(occurrence.copy(ref = ref.copy(instanceEnd = end), time = EventTime.Timed(start, end)))
        assertTrue(IncomingCalendarResolver(source).resolve(CalendarEntrySession.create(route()), ZoneId.of("America/New_York")) is CalendarEntryResolution.Event)
        assertEquals(1, java.time.temporal.ChronoUnit.DAYS.between(source.request!!.range.start, source.request!!.range.endExclusive))
    }

    @Test fun recurringOrDetachedIdWithoutBeginReturnsNeedsOccurrenceAndNeverFirstOrNow() = runBlocking {
        val source = Source(); source.info = base.copy(recurringOrDetached = true, endExclusive = null)
        val session = CalendarEntrySession.create(route())
        assertEquals(CalendarEntryResolution.NeedsOccurrence(7), IncomingCalendarResolver(source).resolve(session, ZoneOffset.UTC))
        assertEquals(0, source.snapshots.get()); assertNotNull(session.binding)
    }

    @Test fun hintedMasterResolvesMovedExceptionCanonicalIdentityFromReaderOnly() = runBlocking {
        val source = Source(); source.info = base.copy(recurringOrDetached = true, endExclusive = null)
        val exception = ref.copy(eventId = 42, recurrence = RecurrenceRef(7, start.minusSeconds(86400)))
        source.rows = listOf(occurrence.copy(ref = exception))
        val result = IncomingCalendarResolver(source).resolve(CalendarEntrySession.create(route(start, ref.instanceEnd)), ZoneOffset.UTC) as CalendarEntryResolution.Event
        assertEquals(exception, result.ref)
    }

    @Test fun missingCanonicalRecurrenceIdentityOrAmbiguousInstancesNeverFabricatesARef() = runBlocking {
        val source = Source(); source.info = base.copy(recurringOrDetached = true, endExclusive = null)
        val resolver = IncomingCalendarResolver(source)
        assertEquals(CalendarEntryResolution.NeedsOccurrence(7), resolver.resolve(CalendarEntrySession.create(route(start)), ZoneOffset.UTC))
        source.rows = listOf(occurrence.copy(ref = ref.copy(recurrence = RecurrenceRef(7, start.minusSeconds(86400)))),
            occurrence.copy(ref = ref.copy(recurrence = RecurrenceRef(7, start.minusSeconds(172800)))))
        assertEquals(CalendarEntryResolution.NeedsOccurrence(7), resolver.resolve(CalendarEntrySession.create(route(start)), ZoneOffset.UTC))
    }

    @Test fun staleBoundsAllDayMismatchAndMissingStandaloneEndDoNotGuess() = runBlocking {
        for (hints in listOf(route(start.plusSeconds(60)), route(start, ref.instanceEnd.plusSeconds(60)), route(start, ref.instanceEnd, true))) {
            assertEquals(CalendarEntryResolution.Stale, IncomingCalendarResolver(Source()).resolve(CalendarEntrySession.create(hints), ZoneOffset.UTC))
        }
        val source = Source(); source.info = base.copy(endExclusive = null)
        assertEquals(CalendarEntryResolution.Unavailable, IncomingCalendarResolver(source).resolve(CalendarEntrySession.create(route()), ZoneOffset.UTC))
        assertEquals(0, source.snapshots.get())
    }

    @Test fun allDayHintsUseUtcDateSemanticsAcrossDisplayZone() = runBlocking {
        val source = Source(); val begin = Instant.parse("2026-11-18T00:00:00Z"); val end = begin.plusSeconds(86400)
        val allDayRef = ref.copy(instanceStart = begin, instanceEnd = end)
        source.info = base.copy(allDay = true, start = begin, endExclusive = end)
        source.rows = listOf(occurrence.copy(ref = allDayRef, time = EventTime.AllDay(CalendarDateRange.day(LocalDate.of(2026, 11, 18)))))
        val result = IncomingCalendarResolver(source).resolve(CalendarEntrySession.create(route(begin.plusSeconds(3600), end.plusSeconds(3600), true)), ZoneId.of("America/Los_Angeles"))
        assertEquals(allDayRef, (result as CalendarEntryResolution.Event).ref)
        assertEquals(LocalDate.of(2026, 11, 18), source.request!!.range.start)
    }

    @Test fun firstNamespaceIsPinnedBeforeHeldSnapshotAndSurvivesCancellation() = runBlocking {
        val source = Source(); val entered = CompletableDeferred<Unit>()
        source.onSnapshot = { entered.complete(Unit); awaitCancellation() }
        val session = CalendarEntrySession.create(route()); val resolver = IncomingCalendarResolver(source)
        val running = async { resolver.resolve(session, ZoneOffset.UTC) }
        entered.await(); assertEquals(calendar.identity, session.binding!!.calendar)
        running.cancelAndJoin()
        source.onSnapshot = null; source.info = base.copy(calendar = calendar.identity.copy(account = CalendarAccount("replacement@example.invalid", "local")))
        assertEquals(CalendarEntryResolution.Stale, resolver.resolve(session, ZoneOffset.UTC))
        assertEquals(1, source.snapshots.get())
    }

    @Test fun metadataNamespaceUidOrTimeChangeAcrossSnapshotIsRejectedAndPinNotRebound() = runBlocking {
        for (changed in listOf(base.copy(providerUid = "replacement"), base.copy(start = start.plusSeconds(1)),
            base.copy(calendar = calendar.identity.copy(providerId = "calendar-B")))) {
            val source = Source(); source.onSnapshot = { source.info = changed }
            val session = CalendarEntrySession.create(route())
            assertEquals(CalendarEntryResolution.Stale, IncomingCalendarResolver(source).resolve(session, ZoneOffset.UTC))
            assertEquals("event-A", session.binding!!.providerUid)
        }
    }

    @Test fun knownUidEnrichesOnceButNeverDowngradesOrSwitches() = runBlocking {
        val source = Source(); source.info = base.copy(providerUid = null); source.onSnapshot = { source.info = base }
        val session = CalendarEntrySession.create(route()); val resolver = IncomingCalendarResolver(source)
        assertTrue(resolver.resolve(session, ZoneOffset.UTC) is CalendarEntryResolution.Event)
        assertEquals("event-A", session.binding!!.providerUid)
        source.onSnapshot = null; source.info = base.copy(providerUid = null)
        assertEquals(CalendarEntryResolution.Stale, resolver.resolve(session, ZoneOffset.UTC))
    }

    @Test fun permissionLimitAndAbsenceAreTypedAndNeverReadDetails() = runBlocking {
        val source = Source(); val resolver = IncomingCalendarResolver(source)
        source.infoFailure = CalendarReadState.PermissionRequired
        assertEquals(CalendarEntryResolution.PermissionRequired, resolver.resolve(CalendarEntrySession.create(route()), ZoneOffset.UTC))
        source.infoFailure = CalendarReadState.LimitExceeded
        assertEquals(CalendarEntryResolution.LimitExceeded, resolver.resolve(CalendarEntrySession.create(route()), ZoneOffset.UTC))
        source.infoFailure = CalendarReadState.Ready(null)
        assertEquals(CalendarEntryResolution.Unavailable, resolver.resolve(CalendarEntrySession.create(route()), ZoneOffset.UTC))
        assertEquals(0, source.snapshots.get()); assertEquals(0, source.details.get())
    }

    @Test fun detailsAreBracketedAndActualResultNamespaceIsChecked() = runBlocking {
        val source = Source(); val session = CalendarEntrySession.create(route()); val resolver = IncomingCalendarResolver(source)
        val resolved = resolver.resolve(session, ZoneOffset.UTC) as CalendarEntryResolution.Event
        source.onDetails = { source.info = base.copy(providerUid = "replacement") }
        assertEquals(CalendarReadState.Unavailable, resolver.readDetails(session, resolved, ZoneOffset.UTC))
        val next = Source(); val nextSession = CalendarEntrySession.create(route()); val nextResolver = IncomingCalendarResolver(next)
        val current = nextResolver.resolve(nextSession, ZoneOffset.UTC) as CalendarEntryResolution.Event
        next.detailsFailure = CalendarReadState.Ready(EventDetails(occurrence,
            calendar.copy(account = CalendarAccount("replacement@example.invalid", "local"))))
        assertEquals(CalendarReadState.Unavailable, nextResolver.readDetails(nextSession, current, ZoneOffset.UTC))
    }

    @Test fun dateLinksRecomputeFromInstantAndCreateSeedsRemainPlainNullableInputsWithoutProviderReads() = runBlocking {
        val source = Source(); val resolver = IncomingCalendarResolver(source)
        val instant = Instant.parse("1969-12-31T23:30:00Z")
        val date = resolver.resolve(CalendarEntrySession.create(CalendarIntentRoute.OpenDate(LocalDate.of(1969, 12, 31), instant)), ZoneId.of("Asia/Tokyo"))
        assertEquals(LocalDate.of(1970, 1, 1), (date as CalendarEntryResolution.Date).date)
        val raw = seed()
        assertEquals(CalendarEntryResolution.CreateSeed(raw), resolver.resolve(CalendarEntrySession.create(CalendarIntentRoute.CreateDraftSeed(raw)), ZoneOffset.UTC))
        assertEquals(0, source.infos.get()); assertEquals(0, source.snapshots.get()); assertEquals(0, source.details.get())
    }

    @Test fun editOverridesAreRetainedForReviewWithoutApplyingThemToReadOnlyDetails() = runBlocking {
        val source = Source(); val raw = seed(); val session = CalendarEntrySession.create(CalendarIntentRoute.EditEvent(7, raw))
        val resolved = IncomingCalendarResolver(source).resolve(session, ZoneOffset.UTC) as CalendarEntryResolution.Event
        assertEquals(raw, resolved.proposedEdit); assertEquals(ref, resolved.ref); assertEquals(0, source.details.get())
    }
}
