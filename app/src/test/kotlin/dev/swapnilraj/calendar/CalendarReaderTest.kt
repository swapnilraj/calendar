package dev.swapnilraj.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.ProviderReadBudgets
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarReaderTest {
    private val request = CalendarRequest(CalendarDateRange.day(LocalDate.of(2026, 11, 17)), ZoneId.of("America/Los_Angeles"))
    private fun instant(value: String) = Instant.parse(value).toEpochMilli()

    private class Fixture : ContentProvider() {
        val calendars = mutableListOf<Map<String, Any?>>()
        val events = mutableListOf<Map<String, Any?>>()
        val metadataEvents = mutableListOf<Map<String, Any?>>()
        var eventQueries = 0
        var detailOverride: Map<String, Any?>? = null
        val attendees = mutableListOf<Map<String, Any?>>()
        val reminders = mutableListOf<Map<String, Any?>>()
        var attendeeQueries = 0
        var reminderQueries = 0
        var failure: RuntimeException? = null
        var nullCursor = false
        lateinit var ownerScope: CoroutineScope
        val instanceProjections = mutableListOf<List<String>>()
        var instanceQueries = 0
        var closed = 0
        var uri: Uri? = null
        var selection: String? = null
        var args: Array<out String>? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            failure?.let { throw it }
            if (nullCursor) return null
            val rows = when (uri.pathSegments.firstOrNull()) {
                "calendars" -> calendars
                "instances" -> { instanceProjections += projection!!.toList(); instanceQueries++; this.uri = uri; this.selection = selection; this.args = args; events }
                "events" -> { eventQueries++; detailOverride?.let { listOf(it) } ?: metadataEvents.takeIf { it.isNotEmpty() } ?: events }
                "attendees" -> { attendeeQueries++; attendees }
                "reminders" -> { reminderQueries++; reminders }
                else -> emptyList()
            }
            return object : MatrixCursor(projection!!) {
                override fun close() { super.close(); closed++ }
            }.apply { rows.forEach { row -> addRow(projection.map { row[it] }.toTypedArray()) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only adapter must not insert")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Read-only adapter must not delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Read-only adapter must not update")
    }

    private fun calendar(id: Long = 2, visible: Int = 1, sync: Int = 1, access: Int? = 700) = mapOf<String, Any?>(
        "_id" to id, "calendar_displayName" to "Fixture calendar", "calendar_color" to 0xff447766.toInt(),
        "account_name" to "fixture@example.invalid", "account_type" to "com.google", "ownerAccount" to "fixture@example.invalid",
        "calendar_timezone" to "Europe/London", "calendar_access_level" to access, "visible" to visible, "sync_events" to sync)
    private fun event(id: Long, begin: String, end: String, allDay: Int = 0, calendar: Long = 2) = mapOf<String, Any?>(
        "event_id" to id, "calendar_id" to calendar, "begin" to instant(begin), "end" to instant(end),
        "dtstart" to instant(begin), "dtend" to instant(end),
        "title" to "Synthetic event", "displayColor" to 0xff447766.toInt(), "allDay" to allDay, "selfAttendeeStatus" to 0)

    private suspend fun <T> Flow<CalendarReadState<T>>.ready(): T = withTimeout(10_000) {
        (first { it is CalendarReadState.Ready } as CalendarReadState.Ready<T>).value
    }
    private suspend fun fixture(readBudgets: ProviderReadBudgets = ProviderReadBudgets(), block: suspend (Fixture, CalendarReader, android.content.ContentResolver) -> Unit) {
        val provider = Fixture().apply { calendars += calendar() }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        provider.ownerScope = scope
        try { block(provider, CalendarReader(resolver, scope, stopTimeoutMillis = 0, readBudgets = readBudgets), resolver) }
        finally {
            scope.cancel()
            withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
            assertTrue(org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).isEmpty())
        }
    }

    @Test fun oneShotReadsObserveCurrentProviderWithoutNotificationOrReplay() = runBlocking {
        fixture { provider, reader, _ ->
            provider.events += event(1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z")
            val cached = CompletableDeferred<CalendarSnapshot>()
            val observing = launch { reader.observeEvents(request).collect { if (it is CalendarReadState.Ready) cached.complete(it.value) } }
            cached.await()
            provider.calendars[0] = calendar() + mapOf("_sync_id" to "new-namespace")
            provider.events[0] = provider.events[0] + mapOf("title" to "Fresh fixture")
            val snapshot = reader.readSnapshot(request) as CalendarReadState.Ready
            assertEquals("Fresh fixture", snapshot.value.occurrences.single().title)
            assertEquals("new-namespace", snapshot.value.calendars.single().providerId)
            val calendars = reader.readCalendars() as CalendarReadState.Ready
            assertEquals("new-namespace", calendars.value.single().providerId)
            observing.cancelAndJoin()
        }
    }

    @Test fun oneShotReadsRetainPermissionAndUnavailableStates() = runBlocking {
        fixture { provider, reader, _ ->
            provider.failure = SecurityException()
            assertEquals(CalendarReadState.PermissionRequired, reader.readCalendars())
            assertEquals(CalendarReadState.PermissionRequired, reader.readSnapshot(request))
            provider.failure = IllegalStateException()
            assertEquals(CalendarReadState.Unavailable, reader.readSnapshot(request))
        }
    }

    @Test fun calendarIdentityRetainsProviderUidAndNormalizesOnlyBlankOptionalIdentity() = runBlocking {
        fixture { provider, reader, _ ->
            provider.calendars.clear()
            provider.calendars += calendar() + mapOf("_sync_id" to "provider-calendar-A")
            val current = reader.observeCalendars().ready().single()
            assertEquals("provider-calendar-A", current.providerId)
            assertEquals(CalendarIdentity(CalendarId(2), CalendarAccount("fixture@example.invalid", "com.google"),
                "fixture@example.invalid", "provider-calendar-A"), current.identity)
            provider.calendars.clear()
            provider.calendars += calendar() + mapOf("ownerAccount" to " ", "_sync_id" to "", "account_name" to "", "account_type" to "")
            reader.refresh()
            val unknown = withTimeout(10_000) {
                (reader.observeCalendars().first { it is CalendarReadState.Ready && it.value.single().account.name.isEmpty() }
                    as CalendarReadState.Ready).value.single()
            }
            assertEquals(CalendarAccount("", ""), unknown.identity.account)
            assertNull(unknown.identity.owner)
            assertNull(unknown.identity.providerId)
        }
    }

    @Test fun oversizedRangeNeverPublishesACompletePrefix() = runBlocking {
        val defaults = ProviderReadBudgets()
        fixture(defaults.copy(range = defaults.range.copy(maxTextUnits = 40_000))) { p, reader, _ ->
            repeat(200) { id -> p.events += event(id.toLong(), "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") +
                mapOf("title" to "$id".padEnd(16_384, 'x')) }
            val result = withTimeout(10_000) { reader.observeEvents(request).first { it != CalendarReadState.Loading } }
            assertEquals(CalendarReadState.LimitExceeded, result)
            assertTrue(p.closed >= 2)
        }
    }

    @Test fun rawRowLimitIsAppliedBeforeIdentityPrepassEvenWhenNoRowsIntersect() = runBlocking {
        fixture(ProviderReadBudgets(maxRangeInstances = 2)) { p, reader, _ ->
            repeat(3) { p.events += event(it.toLong(), "2026-11-19T10:00:00Z", "2026-11-19T11:00:00Z") }
            val result = withTimeout(10_000) { reader.observeEvents(request).first { it != CalendarReadState.Loading } }
            assertEquals(CalendarReadState.LimitExceeded, result)
            assertEquals(0, p.eventQueries)
        }
    }

    @Test fun directDetailsPermitLongDescriptionWithinTheirLargerCellBudget() = runBlocking {
        fixture { p, reader, _ ->
            val row = event(1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") +
                mapOf("calendar_id" to 2L, "_id" to 1L, "description" to "d".repeat(100_000))
            p.events += row
            val ref = EventRef(CalendarId(2), 1, Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-11-17T11:00:00Z"))
            val result = reader.readDetails(ref) as CalendarReadState.Ready
            assertEquals(100_000, result.value.description.length)
        }
    }

    @Test fun rangeIncludesUtcAllDayAndUsesHalfOpenTimedBoundaries() = runBlocking {
        fixture { provider, reader, _ ->
            provider.events += event(1, "2026-11-17T00:00:00Z", "2026-11-18T00:00:00Z", allDay = 1)
            provider.events += event(2, "2026-11-17T07:00:00Z", "2026-11-17T08:00:00Z") // ends at local midnight
            provider.events += event(3, "2026-11-18T08:00:00Z", "2026-11-18T09:00:00Z") // starts at exclusive end
            provider.events += event(4, "2026-11-17T08:00:00Z", "2026-11-17T08:00:00Z")
            provider.events += provider.events.first() // duplicate rows must not duplicate occurrences
            val snapshot = reader.observeEvents(request).ready()
            assertEquals(listOf(1L, 4L), snapshot.occurrences.map { it.ref.eventId })
            assertTrue(snapshot.occurrences.first().time is EventTime.AllDay)
            assertTrue(provider.uri.toString().contains("${instant("2026-11-17T00:00:00Z")}/${instant("2026-11-18T08:00:00Z")}"))
            assertTrue(provider.closed >= 2)
        }
    }

    @Test fun explicitSelectionDoesNotFilterProviderVisibilityAndExplainsCoverage() = runBlocking {
        fixture { provider, reader, _ ->
            provider.calendars.clear()
            provider.calendars += calendar(2, visible = 0, sync = 0)
            provider.events += event(1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z")
            val snapshot = reader.observeEvents(request.copy(selection = CalendarSelection.Explicit(setOf(CalendarId(2), CalendarId(99))))).ready()
            assertEquals(1, snapshot.occurrences.size)
            assertFalse(provider.selection!!.contains("visible"))
            assertEquals(listOf("2"), provider.args!!.toList())
            assertEquals(setOf(CoverageReason.PROVIDER_HIDDEN, CoverageReason.SYNC_DISABLED, CoverageReason.MISSING_CALENDAR), snapshot.coverage.map { it.reason }.toSet())
        }
    }

    @Test fun explicitEmptySelectionDoesNotReadInstances() = runBlocking {
        fixture { provider, reader, _ ->
            val snapshot = reader.observeEvents(request.copy(selection = CalendarSelection.Explicit(emptySet()))).ready()
            assertTrue(snapshot.occurrences.isEmpty())
            assertEquals(0, provider.instanceQueries)
        }
    }

    @Test fun freeBusyRowsMaskTitlesAndUnknownMetadataDoesNotGrantWriteAccess() = runBlocking {
        fixture { provider, reader, _ ->
            provider.calendars.clear()
            provider.calendars += calendar(access = CalendarContract.Calendars.CAL_ACCESS_FREEBUSY)
            provider.events += event(1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z")
            assertEquals("Busy", reader.observeEvents(request).ready().occurrences.single().title)
            provider.calendars.clear()
            provider.calendars += calendar(access = null)
            assertEquals(AccessDecision.UNKNOWN, reader.observeCalendars().ready().single().capabilities.createEvents)
        }
    }

    @Test fun errorsAreTypedAndRefreshCanRecoverWithoutNewReader() = runBlocking {
        fixture { provider, reader, resolver ->
            val values = kotlinx.coroutines.channels.Channel<CalendarReadState<CalendarSnapshot>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val collector = launch { reader.observeEvents(request).collect { values.send(it) } }
            suspend fun next(predicate: (CalendarReadState<CalendarSnapshot>) -> Boolean): CalendarReadState<CalendarSnapshot> = withTimeout(10_000) {
                var value = values.receive()
                while (!predicate(value)) value = values.receive()
                value
            }
            try {
                next { it is CalendarReadState.Ready }
                provider.failure = SecurityException()
                resolver.notifyChange(CalendarContract.Events.CONTENT_URI, null)
                next { it == CalendarReadState.PermissionRequired }
                provider.failure = IllegalStateException()
                reader.refresh()
                next { it == CalendarReadState.Unavailable }
                provider.failure = null
                reader.refresh()
                next { it is CalendarReadState.Ready }
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun detailReadsPreserveOccurrenceTimeAndRejectStaleReferences() = runBlocking {
        fixture { provider, reader, _ ->
            provider.events += event(11, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf(
                "eventLocation" to "Synthetic room", "description" to "Fixture description")
            val ref = EventRef(CalendarId(2), 11, Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-11-17T11:00:00Z"))
            val details = (reader.readDetails(ref) as CalendarReadState.Ready).value
            assertEquals(ref, details.occurrence.ref)
            assertEquals("Synthetic room", details.location)
            assertEquals("Fixture description", details.description)
            // Even stale expanded Instances must not hide an Events-row reschedule.
            provider.detailOverride = provider.events.single() + mapOf("dtstart" to instant("2026-11-17T12:00:00Z"), "dtend" to instant("2026-11-17T13:00:00Z"))
            assertEquals(CalendarReadState.Unavailable, reader.readDetails(ref))
            provider.detailOverride = null
            provider.events.clear()
            provider.events += event(11, "2026-11-17T12:00:00Z", "2026-11-17T13:00:00Z")
            assertEquals(CalendarReadState.Unavailable, reader.readDetails(ref))
            provider.events.clear()
            assertEquals(CalendarReadState.Unavailable, reader.readDetails(ref))
        }
    }

    @Test fun guestListsRequireExplicitOrganizerOrGuestVisibilityFlags() = runBlocking {
        val cases = listOf(Triple(1, 0, true), Triple(0, 1, true), Triple(0, 0, false),
            Triple(null, null, false), Triple(2, 7, false))
        for ((isOrganizer, seeGuests, allowed) in cases) fixture { provider, reader, _ ->
            // Matching the account email must not itself grant organizer/guest-list authority.
            provider.events += event(11, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf(
                CalendarContract.Events.ORGANIZER to "fixture@example.invalid",
                CalendarContract.Events.IS_ORGANIZER to isOrganizer,
                CalendarContract.Events.GUESTS_CAN_SEE_GUESTS to seeGuests,
                CalendarContract.Events.SELF_ATTENDEE_STATUS to CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE)
            provider.attendees += mapOf(CalendarContract.Attendees.ATTENDEE_EMAIL to "guest@example.invalid",
                CalendarContract.Attendees.ATTENDEE_NAME to "Synthetic guest", CalendarContract.Attendees.ATTENDEE_STATUS to 1,
                CalendarContract.Attendees.ATTENDEE_RELATIONSHIP to 1, CalendarContract.Attendees.ATTENDEE_TYPE to 1)
            val ref = EventRef(CalendarId(2), 11, Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-11-17T11:00:00Z"))
            val details = (reader.readDetails(ref) as CalendarReadState.Ready).value
            assertEquals(if (allowed) 1 else 0, provider.attendeeQueries)
            assertEquals(if (allowed) 1 else 0, details.attendees.size)
            assertEquals(CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE, details.selfAttendeeStatus)
            assertEquals(when (isOrganizer) { 1 -> true; 0 -> false; else -> null }, details.isOrganizer)
            assertNull(details.availability)
            assertNull(details.privacy)
            assertNull(details.status)
        }
    }

    @Test fun optionalMetadataPreservesRawEnumsAndStrictGuestPolicyWithoutWriteAuthority() = runBlocking {
        fixture { provider, reader, _ ->
            provider.events += event(11, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf(
                CalendarContract.Events.AVAILABILITY to 713, CalendarContract.Events.ACCESS_LEVEL to 714,
                CalendarContract.Events.STATUS to 715, CalendarContract.Events.SELF_ATTENDEE_STATUS to 716,
                CalendarContract.Events.IS_ORGANIZER to 0, CalendarContract.Events.GUESTS_CAN_MODIFY to 1,
                CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS to 0, CalendarContract.Events.GUESTS_CAN_SEE_GUESTS to 2)
            val ref = EventRef(CalendarId(2), 11, Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-11-17T11:00:00Z"))
            val details = (reader.readDetails(ref) as CalendarReadState.Ready).value
            assertEquals(713, details.availability)
            assertEquals(714, details.privacy)
            assertEquals(715, details.status)
            assertEquals(716, details.selfAttendeeStatus)
            assertEquals(EventGuestPermissions(AccessDecision.ALLOWED, AccessDecision.DENIED, AccessDecision.UNKNOWN), details.guestPermissions)
            assertEquals(AccessDecision.UNKNOWN, details.canEdit)
            assertEquals(AccessDecision.UNKNOWN, details.canRespond)
            assertEquals(0, provider.attendeeQueries)
        }
    }

    @Test fun freeBusyDetailsScrubOptionalMetadataAndDoNotQueryGuestsOrReminders() = runBlocking {
        fixture { provider, reader, _ ->
            provider.calendars.clear()
            provider.calendars += calendar(access = CalendarContract.Calendars.CAL_ACCESS_FREEBUSY)
            provider.events += event(11, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf(
                CalendarContract.Events.TITLE to "Synthetic private title", CalendarContract.Events.DESCRIPTION to "Synthetic private notes",
                CalendarContract.Events.EVENT_LOCATION to "Synthetic private location", CalendarContract.Events.ORGANIZER to "organizer@example.invalid",
                CalendarContract.Events.EVENT_TIMEZONE to "Europe/London", CalendarContract.Events.DISPLAY_COLOR to 0xffaabbcc.toInt(),
                CalendarContract.Events.IS_ORGANIZER to 1, CalendarContract.Events.GUESTS_CAN_SEE_GUESTS to 1,
                CalendarContract.Events.GUESTS_CAN_MODIFY to 1, CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS to 1,
                CalendarContract.Events.AVAILABILITY to 1, CalendarContract.Events.ACCESS_LEVEL to 2,
                CalendarContract.Events.STATUS to 1, CalendarContract.Events.SELF_ATTENDEE_STATUS to CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
            val ref = EventRef(CalendarId(2), 11, Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-11-17T11:00:00Z"))
            val details = (reader.readDetails(ref) as CalendarReadState.Ready).value
            assertEquals("Busy", details.occurrence.title)
            assertFalse(details.occurrence.declined)
            assertEquals(0xff447766.toInt(), details.occurrence.color)
            assertEquals("", details.location)
            assertEquals("", details.description)
            assertNull(details.organizer)
            assertNull(details.eventZone)
            assertNull(details.recurrenceRule)
            assertNull(details.availability)
            assertNull(details.privacy)
            assertNull(details.status)
            assertNull(details.isOrganizer)
            assertNull(details.selfAttendeeStatus)
            assertEquals(EventGuestPermissions(), details.guestPermissions)
            assertTrue(details.attendees.isEmpty())
            assertTrue(details.reminders.isEmpty())
            assertEquals(AccessDecision.DENIED, details.canEdit)
            assertEquals(AccessDecision.DENIED, details.canRespond)
            assertEquals(0, provider.attendeeQueries)
            assertEquals(0, provider.reminderQueries)
        }
    }

    @Test fun completedWindowsReleaseTheirSharingJobs() = runBlocking {
        fixture { provider, reader, _ ->
            // Only the global invalidation and metadata-sharing jobs belong to the reader at rest.
            val baseline = provider.ownerScope.coroutineContext[Job]!!.children.count()
            repeat(25) { offset ->
                val date = request.range.start.plusDays(offset.toLong())
                reader.observeEvents(request.copy(range = CalendarDateRange.day(date))).ready()
            }
            withTimeout(10_000) {
                while (provider.ownerScope.coroutineContext[Job]!!.children.count() > baseline) yield()
            }
            assertEquals(baseline, provider.ownerScope.coroutineContext[Job]!!.children.count())
        }
    }

    @Test fun identicalActiveWindowsShareOneQueryAndRootObserver() = runBlocking {
        fixture { provider, reader, resolver ->
            val firstReady = CompletableDeferred<Unit>()
            val secondReady = CompletableDeferred<Unit>()
            val one = launch { reader.observeEvents(request).collect { if (it is CalendarReadState.Ready) firstReady.complete(Unit) } }
            val two = launch { reader.observeEvents(request).collect { if (it is CalendarReadState.Ready) secondReady.complete(Unit) } }
            try {
                withTimeout(10_000) { firstReady.await(); secondReady.await() }
                assertEquals(1, provider.instanceQueries)
                assertEquals(1, org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).size)
            } finally { one.cancelAndJoin(); two.cancelAndJoin() }
        }
    }

    @Test fun originalRecurrenceIdentityIsReadWithoutReplacingItWithMovedTime() = runBlocking {
        fixture { provider, reader, _ ->
            provider.events += event(11, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf(
                "original_id" to 10L, "originalInstanceTime" to instant("2026-11-16T10:00:00Z"))
            val ref = reader.observeEvents(request).ready().occurrences.single().ref
            assertEquals(RecurrenceRef(10, Instant.parse("2026-11-16T10:00:00Z")), ref.recurrence)
            assertEquals(Instant.parse("2026-11-17T10:00:00Z"), ref.instanceStart)
        }
    }

    @Test fun rDateOnlyMasterRetainsRecurrenceIdentityInListAndDetails() = runBlocking {
        fixture { provider, reader, _ ->
            provider.events += event(10, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf("rdate" to "20261117T100000Z")
            val occurrence = reader.observeEvents(request).ready().occurrences.single()
            assertEquals(RecurrenceRef(10, occurrence.ref.instanceStart), occurrence.ref.recurrence)
            provider.detailOverride = provider.events.single() + mapOf("dtstart" to instant("2026-11-16T10:00:00Z"), "dtend" to null)
            assertTrue(reader.readDetails(occurrence.ref) is CalendarReadState.Ready)
        }
    }

    @Test fun syncOnlyExceptionsResolveParentsOncePerBatchWithoutBecomingStandalone() = runBlocking {
        fixture { provider, reader, _ ->
            repeat(12) { index -> provider.events += event(100L + index, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") +
                mapOf("original_sync_id" to "opaque-master-A", "originalInstanceTime" to instant("2026-11-16T10:00:00Z")) }
            provider.metadataEvents += mapOf("_id" to 10L, "calendar_id" to 2L, "_sync_id" to "opaque-master-A", "deleted" to 0)
            val result = reader.observeEvents(request).ready()
            assertEquals(12, result.occurrences.size)
            assertTrue(result.occurrences.all { it.ref.recurrence == RecurrenceRef(10, Instant.parse("2026-11-16T10:00:00Z")) })
            assertEquals(1, provider.eventQueries)
        }
    }

    @Test fun ambiguousOrContradictorySyncParentIsUnavailableRatherThanGuessed() = runBlocking {
        fixture { provider, reader, _ ->
            provider.events += event(11, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") +
                mapOf("original_sync_id" to "opaque-A", "original_id" to 20L, "originalInstanceTime" to instant("2026-11-16T10:00:00Z"))
            provider.metadataEvents += mapOf("_id" to 10L, "calendar_id" to 2L, "_sync_id" to "opaque-A", "deleted" to 0)
            assertEquals(CalendarReadState.Unavailable, withTimeout(10_000) { reader.observeEvents(request).first { it != CalendarReadState.Loading } })
        }
        fixture { provider, reader, _ ->
            provider.events += event(11, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") +
                mapOf("original_sync_id" to "opaque-A", "originalInstanceTime" to instant("2026-11-16T10:00:00Z"))
            assertEquals(CalendarReadState.Unavailable, withTimeout(10_000) { reader.observeEvents(request).first { it != CalendarReadState.Loading } })
        }
    }

    @Test fun nullCursorIsUnavailableNotAnEmptyCalendar() = runBlocking {
        fixture { provider, reader, _ ->
            provider.nullCursor = true
            assertEquals(CalendarReadState.Unavailable, withTimeout(10_000) {
                reader.observeCalendars().first { it != CalendarReadState.Loading }
            })
        }
    }

    @Test fun pendingOnlyFiltersProvenInvitationsAndWarnsForUnknownEvidence() = runBlocking {
        fixture { p, reader, _ ->
            val evidence = listOf(false to 3, true to 0, false to 1, false to 2, false to 4, true to 3, false to 0, null to 3)
            evidence.forEachIndexed { index, (organizer, status) ->
                p.events += event(index.toLong() + 1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") +
                    mapOf("isOrganizer" to organizer?.let { if (it) 1 else 0 }, "selfAttendeeStatus" to status)
            }
            val all = (reader.readSnapshot(request) as CalendarReadState.Ready).value
            assertEquals(8, all.occurrences.size)
            assertTrue(all.occurrences.all { it.invitation == InvitationState.UNKNOWN })
            assertFalse(p.instanceProjections.single().contains("isOrganizer"))
            val pending = (reader.readSnapshot(request.copy(invitationFilter = InvitationFilter.PENDING_ONLY)) as CalendarReadState.Ready).value
            assertEquals(listOf(1L), pending.occurrences.map { it.ref.eventId })
            assertEquals(InvitationState.PENDING, pending.occurrences.single().invitation)
            assertEquals(listOf(CalendarCoverage(CalendarId(2), CoverageReason.INVITATION_CLASSIFICATION_UNKNOWN)), pending.coverage)
            assertTrue(p.instanceProjections.last().contains("isOrganizer"))
            p.events[0] = p.events[0] + ("selfAttendeeStatus" to 1)
            assertTrue((reader.readSnapshot(request.copy(invitationFilter = InvitationFilter.PENDING_ONLY)) as CalendarReadState.Ready).value.occurrences.isEmpty())
            assertEquals(0, p.attendeeQueries + p.reminderQueries + p.eventQueries)
        }
    }

    @Test fun pendingOnlyInvalidNumericEvidenceNeverWrapsIntoInvitations() = runBlocking {
        fixture { p, reader, _ ->
            val evidence = listOf(0 to 4_294_967_299L, 0 to 4_294_967_298L, 0 to "03", 0 to "+3", 0 to "3.0", 0 to null, "false" to 3, 2 to 3, null to 3, 0 to 3)
            evidence.forEachIndexed { index, (organizer, status) ->
                p.events += event(index.toLong() + 1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") +
                    mapOf("isOrganizer" to organizer, "selfAttendeeStatus" to status)
            }
            val result = (reader.readSnapshot(request.copy(invitationFilter = InvitationFilter.PENDING_ONLY)) as CalendarReadState.Ready).value
            assertEquals(listOf(10L), result.occurrences.map { it.ref.eventId })
            assertEquals(listOf(CalendarCoverage(CalendarId(2), CoverageReason.INVITATION_CLASSIFICATION_UNKNOWN)), result.coverage)
            assertEquals(0, p.attendeeQueries + p.reminderQueries)
        }
    }

    @Test fun pendingOnlyUnknownsOutsideRangeOrSelectionDoNotCreateCoverage() = runBlocking {
        fixture { p, reader, _ ->
            p.calendars += calendar(id = 3)
            p.events += event(1, "2026-11-16T10:00:00Z", "2026-11-16T11:00:00Z")
            p.events += event(2, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z", calendar = 3)
            p.events += event(3, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf("isOrganizer" to 0, "selfAttendeeStatus" to 1)
            val result = (reader.readSnapshot(request.copy(selection = CalendarSelection.Explicit(setOf(CalendarId(2))), invitationFilter = InvitationFilter.PENDING_ONLY)) as CalendarReadState.Ready).value
            assertTrue(result.occurrences.isEmpty())
            assertTrue(result.coverage.isEmpty())
        }
    }

    @Test fun pendingOnlyFreeBusySourcesStayRedactedAndReportInsufficientAccess() = runBlocking {
        fixture { p, reader, _ ->
            p.calendars[0] = calendar(access = 100)
            p.events += event(1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf("isOrganizer" to 0, "selfAttendeeStatus" to 3)
            val all = (reader.readSnapshot(request) as CalendarReadState.Ready).value
            assertEquals("Busy", all.occurrences.single().title)
            assertEquals(InvitationState.UNKNOWN, all.occurrences.single().invitation)
            val result = (reader.readSnapshot(request.copy(invitationFilter = InvitationFilter.PENDING_ONLY)) as CalendarReadState.Ready).value
            assertTrue(result.occurrences.isEmpty())
            assertEquals(listOf(CalendarCoverage(CalendarId(2), CoverageReason.INSUFFICIENT_ACCESS)), result.coverage)
            assertEquals(0, p.attendeeQueries + p.reminderQueries + p.eventQueries)
        }
    }

    @Test fun pendingOnlyRangeLimitNeverPublishesCompleteFilteredPrefix() = runBlocking {
        fixture(readBudgets = ProviderReadBudgets(maxRangeInstances = 1)) { p, reader, _ ->
            p.events += event(1, "2026-11-17T10:00:00Z", "2026-11-17T11:00:00Z") + mapOf("isOrganizer" to 0, "selfAttendeeStatus" to 1)
            p.events += event(2, "2026-11-17T12:00:00Z", "2026-11-17T13:00:00Z") + mapOf("isOrganizer" to 0, "selfAttendeeStatus" to 3)
            assertEquals(CalendarReadState.LimitExceeded, reader.readSnapshot(request.copy(invitationFilter = InvitationFilter.PENDING_ONLY)))
        }
    }
}
