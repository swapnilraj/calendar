package dev.swapnilraj.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
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
        var detailOverride: Map<String, Any?>? = null
        var failure: RuntimeException? = null
        var nullCursor = false
        lateinit var ownerScope: CoroutineScope
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
                "instances" -> { instanceQueries++; this.uri = uri; this.selection = selection; this.args = args; events }
                "events" -> detailOverride?.let { listOf(it) } ?: events
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
    private suspend fun fixture(block: suspend (Fixture, CalendarReader, android.content.ContentResolver) -> Unit) {
        val provider = Fixture().apply { calendars += calendar() }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        provider.ownerScope = scope
        try { block(provider, CalendarReader(resolver, scope, stopTimeoutMillis = 0), resolver) }
        finally {
            scope.cancel()
            withTimeout(10_000) { scope.coroutineContext[Job]!!.join() }
            assertTrue(org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).isEmpty())
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

    @Test fun nullCursorIsUnavailableNotAnEmptyCalendar() = runBlocking {
        fixture { provider, reader, _ ->
            provider.nullCursor = true
            assertEquals(CalendarReadState.Unavailable, withTimeout(10_000) {
                reader.observeCalendars().first { it != CalendarReadState.Loading }
            })
        }
    }
}
