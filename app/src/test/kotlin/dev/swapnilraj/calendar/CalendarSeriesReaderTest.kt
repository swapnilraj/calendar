package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSeriesReaderTest {
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val selected = EventRef(CalendarId(2), 10, start.plusSeconds(86_400), start.plusSeconds(90_000), RecurrenceRef(10, start.plusSeconds(86_400)))
    private fun master() = mutableMapOf<String, String?>("_id" to "10", "calendar_id" to "2", "dtstart" to start.toEpochMilli().toString(),
        "dtend" to null, "duration" to "PT1H", "allDay" to "0", "eventTimezone" to "Europe/London", "eventEndTimezone" to "Europe/London",
        "title" to "Master synthetic template", "eventLocation" to "Synthetic office", "description" to "Synthetic notes",
        "rrule" to "FREQ=DAILY;COUNT=5", "rdate" to null, "exrule" to null, "exdate" to null,
        "original_id" to null, "originalInstanceTime" to null, "originalAllDay" to null,
        "deleted" to "0", "eventStatus" to "1", "organizer" to "owner@example.invalid", "isOrganizer" to "1",
        "hasAttendeeData" to "0", "guestsCanSeeGuests" to "1", "availability" to "77", "accessLevel" to "2", "customAppPackage" to null)
    private fun exception(id: Long, originalDays: Long, movedDays: Long = originalDays, cancelled: Boolean = false) = master().apply {
        put("_id", id.toString()); put("rrule", null); put("duration", null)
        put("dtstart", start.plusSeconds(movedDays * 86_400).toEpochMilli().toString())
        put("dtend", start.plusSeconds(movedDays * 86_400 + 3_600).toEpochMilli().toString())
        put("original_id", "10"); put("originalInstanceTime", start.plusSeconds(originalDays * 86_400).toEpochMilli().toString())
        put("originalAllDay", "0"); put("eventStatus", if (cancelled) "2" else "1")
    }
    private fun instance(ref: EventRef = selected) = master().apply {
        put("event_id", ref.eventId.toString()); put("begin", ref.instanceStart.toEpochMilli().toString()); put("end", ref.instanceEnd.toEpochMilli().toString())
        put("displayColor", "123"); put("selfAttendeeStatus", "0")
        if (ref.eventId != 10L) {
            put("rrule", null); put("original_id", "10"); put("originalInstanceTime", ref.recurrence!!.originalStart.toEpochMilli().toString())
        }
    }
    private class Provider : ContentProvider() {
        val calendar = mutableMapOf<String, String?>("_id" to "2", "calendar_access_level" to "700", "account_name" to "owner@example.invalid",
            "account_type" to "com.google", "ownerAccount" to "owner@example.invalid", "calendar_timezone" to "Europe/London", "calendar_color" to "123",
            "calendar_displayName" to "Synthetic", "visible" to "1", "sync_events" to "1", "canModifyTimeZone" to "1", "allowedAvailability" to "0,1,2")
        val events = mutableListOf<MutableMap<String, String?>>()
        val instances = mutableListOf<MutableMap<String, String?>>()
        val reminders = mutableListOf<MutableMap<String, String?>>()
        val attendees = mutableListOf<MutableMap<String, String?>>()
        data class Call(val path: String, val fields: List<String>?, val selection: String?, val args: List<String>)
        val calls = mutableListOf<Call>()
        var partialPath: String? = null
        var failPath: String? = null
        var nullPath: String? = null
        var afterQuery: ((String) -> Unit)? = null
        var signal: CancellationSignal? = null
        var blocked: CountDownLatch? = null
        var closes = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, cancellationSignal: CancellationSignal?): Cursor? {
            signal = cancellationSignal
            blocked?.let { latch -> cancellationSignal?.setOnCancelListener { latch.countDown() }; latch.await(5, TimeUnit.SECONDS); cancellationSignal?.throwIfCanceled() }
            return query(uri, projection, selection, args, sort)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            val path = uri.pathSegments.first()
            calls += Call(path, projection?.toList(), selection, args?.toList().orEmpty())
            if (path == failPath) error("Synthetic source failure")
            if (path == nullPath) return null
            val id = uri.lastPathSegment?.toLongOrNull()
            val rows: List<Map<String, String?>> = when (path) {
                "calendars" -> listOf(calendar).filter { id == null || it["_id"] == id.toString() }
                "events" -> events.filter { row -> when {
                    id != null -> row["_id"] == id.toString()
                    selection?.contains("original_id") == true -> (row["_id"] == args!!.last() || row["original_id"] == args.last()) &&
                        (!selection.startsWith("calendar_id") || row["calendar_id"] == args.first())
                    else -> true
                } }
                "instances" -> instances.filter { row ->
                    row["event_id"] == args!![0] && row["calendar_id"] == args[1] && row["begin"]!!.toLong() < uri.pathSegments[3].toLong() &&
                        row["end"]!!.toLong() > uri.pathSegments[2].toLong()
                }
                "reminders", "attendees" -> (if (path == "reminders") reminders else attendees).filter { row ->
                    if (id != null) row["_id"] == id.toString() else row["event_id"] in args.orEmpty()
                }
                else -> error("Unexpected table")
            }
            afterQuery?.invoke(path)
            val fields = projection ?: rows.flatMap { it.keys }.toSortedSet().toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("_id", "event_id")
            return object : MatrixCursor(fields) {
                override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
                    if (path == partialPath && newPosition > 0) error("Synthetic partial source")
                    return super.onMove(oldPosition, newPosition)
                }
                override fun close() { super.close(); closes++ }
            }.apply { rows.forEach { row -> addRow(fields.map { row[it] }.toTypedArray()) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Series inspection must not write provider")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Series inspection must not write provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Series inspection must not write provider")
    }
    private suspend fun fixture(budget: RecurrencePlanningBudget = RecurrencePlanningBudget(),
        block: suspend (Provider, CalendarSeriesReader, CalendarLocalStore, MutableSet<String>) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider().apply { events += master(); instances += instance() }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val store = CalendarLocalStore(context, "series-reader-${UUID.randomUUID()}.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR)
        val service = CalendarSeriesReader(context.contentResolver, CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0), store,
            permissions::contains, budget = budget)
        try { block(provider, service, store, permissions) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); context.deleteDatabase(store.databaseName) }
    }
    private suspend fun CalendarSeriesReader.ready(ref: EventRef = selected) = (loadForEdit(ref) as SeriesReadResult.Ready).series
    private fun Provider.childReads() = calls.count { it.path == "attendees" || it.path == "reminders" }

    @Test fun completeGraphIncludesCancelledMovedAndOutsideDisplayExceptionsWithReadPermissionOnly() = runBlocking {
        fixture { p, source, store, permissions ->
            p.events += exception(11, 2, 200)
            p.events += exception(12, 3, cancelled = true)
            p.events += exception(13, 4).apply { put("deleted", "1") }
            p.reminders += mutableMapOf("_id" to "51", "event_id" to "10", "minutes" to "-1", "method" to "0", "unknown_column" to "raw-child")
            p.events[0]["future_private_column"] = "raw-provider-value"
            assertFalse(Manifest.permission.WRITE_CALENDAR in permissions)
            val result = source.ready()
            assertEquals(SeriesCoverage.COMPLETE, result.snapshot.coverage)
            assertEquals(selected, result.snapshot.validatedSelection)
            assertEquals(setOf(11L, 12L), result.snapshot.exceptions.map { it.ref.eventId }.toSet())
            assertTrue(result.snapshot.exceptions.single { it.ref.eventId == 12L }.cancelled)
            assertEquals(start.plusSeconds(200 * 86_400), result.snapshot.exceptions.single { it.ref.eventId == 11L }.ref.instanceStart)
            assertEquals(listOf(EventReminder(-1, 0)), result.masterFields.reminders)
            assertEquals(77, result.masterFields.availability)
            assertEquals(2, result.masterFields.privacy)
            assertTrue(store.edit(result.token.value)!!.contains("future_private_column"))
            assertTrue(store.edit(result.token.value)!!.contains("raw-child"))
            assertEquals(p.calls.size, p.closes)
        }
    }

    @Test fun masterTemplateDoesNotFabricateExcludedFirstOccurrenceOrUseSelectedDates() = runBlocking {
        fixture { p, source, _, _ ->
            p.events[0]["exdate"] = "20261117T090000Z"
            p.events[0]["duration"] = "provider-future-duration"
            val result = source.ready()
            assertEquals(selected, result.selectedDetails.occurrence.ref)
            assertEquals(start, (result.snapshot.start as RecurrenceStart.Timed).instant)
            assertEquals("provider-future-duration", result.snapshot.durationRaw)
            assertTrue(result.snapshot.exDatesDecoded)
            assertEquals(setOf(RecurrenceKey.Timed(start)), result.snapshot.exDates)
            assertEquals("Master synthetic template", result.masterFields.title)
            assertNotEquals((result.snapshot.start as RecurrenceStart.Timed).instant, result.selectedDetails.occurrence.ref.instanceStart)
        }
    }

    @Test fun unsupportedDateSyntaxIsKeptRawWithoutInventingPartialExclusions() = runBlocking {
        fixture { p, source, _, _ ->
            for (raw in listOf("20261117T090000Z,20261118T090000", "RDATE:20261117T090000Z")) {
                p.events[0]["exdate"] = raw
                val result = source.ready()
                assertEquals(raw, result.snapshot.exDatesRaw)
                assertFalse(result.snapshot.exDatesDecoded)
                assertTrue(result.snapshot.exDates.isEmpty())
            }
        }
    }

    @Test fun unsupportedRecurrenceRawTextSurvivesNonPatternPlanning() = runBlocking {
        fixture { p, source, _, _ ->
            p.events[0]["rrule"] = "FREQ=HOURLY;INTERVAL=3"
            val result = source.ready()
            assertEquals("FREQ=HOURLY;INTERVAL=3", result.snapshot.ruleRaw)
            assertTrue(RecurrencePlanning.plan(result.snapshot, selected, RecurrenceScope.ALL,
                RecurrenceMutation.Edit(EventPatch(title = FieldChange.Set("Only title")))) is RecurrencePlanningResult.Ready)
        }
    }

    @Test fun sourceTokensAreStableButBindActualSelectionAndAllPrivateChildren() = runBlocking {
        fixture { p, source, _, _ ->
            p.events += exception(11, 4)
            val original = source.ready()
            assertEquals(original.token, source.ready().token)
            val other = selected.copy(instanceStart = selected.instanceStart.plusSeconds(86_400), instanceEnd = selected.instanceEnd.plusSeconds(86_400),
                recurrence = RecurrenceRef(10, selected.instanceStart.plusSeconds(86_400)))
            p.instances += instance(other)
            assertNotEquals(original.token, source.ready(other).token)
            try { source.checkedSource(other, original.token); fail("Selection must be token-bound") } catch (_: SeriesSourceConflict) { }
            p.reminders += mutableMapOf("_id" to "50", "event_id" to "11", "minutes" to "15", "method" to "2")
            try { source.checkedSource(selected, original.token); fail("Exception child change must conflict") } catch (_: SeriesSourceConflict) { }
        }
    }

    @Test fun concurrentSourceChangeBetweenReadsNeverPersistsAnEditSnapshot() = runBlocking {
        fixture { p, source, store, _ ->
            var instanceReads = 0
            p.afterQuery = { path -> if (path == "instances" && ++instanceReads == 1) p.events[0]["description"] = "Concurrent raw edit" }
            assertEquals(SeriesReadResult.Unavailable, source.loadForEdit(selected))
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test fun freeBusyAndUnknownCalendarAccessStopBeforeAnyEventPayload() = runBlocking {
        for (access in listOf<String?>("100", null, "999")) fixture { p, source, _, _ ->
            p.calendar["calendar_access_level"] = access
            assertEquals(SeriesReadResult.Denied(MutationDenial.CALENDAR_READ_ONLY), source.loadForEdit(selected))
            assertEquals(listOf("calendars"), p.calls.map { it.path })
        }
    }

    @Test fun everyExceptionMustProveGuestVisibilityBeforeAnyPrivateChildRead() = runBlocking {
        fixture { p, source, _, _ ->
            p.events += exception(11, 2).apply { put("isOrganizer", null); put("guestsCanSeeGuests", null); put("hasAttendeeData", "0") }
            assertEquals(SeriesReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT), source.loadForEdit(selected))
            assertEquals(0, p.childReads())
        }
    }

    @Test fun invitationAndSpecializedExceptionsKeepWholeSeriesReadOnly() = runBlocking {
        fixture { p, source, _, _ ->
            p.events += exception(11, 2)
            p.attendees += mutableMapOf("_id" to "30", "event_id" to "11", "attendeeEmail" to "guest@example.invalid")
            assertEquals(SeriesReadResult.Denied(MutationDenial.INVITATION), source.loadForEdit(selected))
        }
        fixture { p, source, _, _ ->
            p.events += exception(11, 2).apply { put("customAppPackage", "specialized.fixture") }
            assertEquals(SeriesReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT), source.loadForEdit(selected))
            assertEquals(0, p.childReads())
        }
    }

    @Test fun foreignCalendarExceptionIsDetectedBeforeItsPrivatePayloadIsRead() = runBlocking {
        fixture { p, source, _, _ ->
            p.events += exception(11, 2).apply { put("calendar_id", "999") }
            assertTrue(source.loadForEdit(selected) is SeriesReadResult.NeedsReview)
            assertEquals(0, p.calls.count { it.path == "events" && it.fields == null })
            assertEquals(0, p.childReads())
        }
    }

    @Test fun duplicateOriginalIdentityAndIncompleteExceptionTimesRequireReview() = runBlocking {
        fixture { p, source, _, _ ->
            p.events += exception(11, 2); p.events += exception(12, 2, 100)
            assertTrue(source.loadForEdit(selected) is SeriesReadResult.NeedsReview)
        }
        fixture { p, source, _, _ ->
            p.events += exception(11, 2).apply { put("dtend", null); put("duration", "P1D") }
            assertTrue(source.loadForEdit(selected) is SeriesReadResult.NeedsReview)
        }
    }

    @Test fun completeMeansEntireCursorAndBudgetNeverAnAcceptedPrefix() = runBlocking {
        fixture(RecurrencePlanningBudget(maxExceptions = 1)) { p, source, _, _ ->
            p.events += exception(11, 2); p.events += exception(12, 3)
            assertTrue(source.loadForEdit(selected) is SeriesReadResult.NeedsReview)
            assertEquals(0, p.childReads())
        }
        fixture { p, source, _, _ ->
            p.events += exception(11, 2); p.partialPath = "events"
            assertEquals(SeriesReadResult.Unavailable, source.loadForEdit(selected))
        }
    }

    @Test fun failedAndNullQueriesNeverProduceCompleteSource() = runBlocking {
        for (path in listOf("events", "reminders", "attendees", "instances")) fixture { p, source, _, _ ->
            p.failPath = path
            assertEquals(SeriesReadResult.Unavailable, source.loadForEdit(selected))
            p.failPath = null; p.nullPath = path
            assertEquals(SeriesReadResult.Unavailable, source.loadForEdit(selected))
        }
    }

    @Test fun forgedOriginalIdentityOrExternallyMovedSelectionIsRejected() = runBlocking {
        fixture { p, source, _, _ ->
            assertEquals(SeriesReadResult.Unavailable, source.loadForEdit(selected.copy(recurrence = RecurrenceRef(10, start))))
            p.instances.clear(); p.instances += instance(selected.copy(instanceStart = selected.instanceStart.plusSeconds(600), instanceEnd = selected.instanceEnd.plusSeconds(600)))
            assertEquals(SeriesReadResult.Unavailable, source.loadForEdit(selected))
        }
    }

    @Test fun movedSelectedExceptionKeepsOriginalBoundaryAndMasterTemplate() = runBlocking {
        fixture { p, source, _, _ ->
            val moved = EventRef(CalendarId(2), 11, start.plusSeconds(200 * 86_400), start.plusSeconds(200 * 86_400 + 3_600), RecurrenceRef(10, start.plusSeconds(2 * 86_400)))
            p.events += exception(11, 2, 200)
            p.instances.clear(); p.instances += instance(moved)
            val result = source.ready(moved)
            assertEquals(moved, result.snapshot.validatedSelection)
            assertEquals(start, (result.snapshot.start as RecurrenceStart.Timed).instant)
            assertEquals(RecurrenceKey.Timed(moved.recurrence!!.originalStart), result.snapshot.exceptions.single().original)
        }
    }

    @Test fun allDayIdentityRequiresUtcMidnightAndZonePermissionIsStrict() = runBlocking {
        fixture { p, source, _, _ ->
            val midnight = Instant.parse("2026-11-17T00:00:00Z")
            val allDay = selected.copy(instanceStart = midnight.plusSeconds(86_400), instanceEnd = midnight.plusSeconds(2 * 86_400),
                recurrence = RecurrenceRef(10, midnight.plusSeconds(86_400)))
            p.events[0].apply { put("dtstart", midnight.toEpochMilli().toString()); put("allDay", "1"); put("duration", "P1D"); put("eventTimezone", "UTC"); put("eventEndTimezone", "UTC") }
            p.events[0]["exdate"] = "20261117T000000Z"
            p.instances.clear(); p.instances += instance(allDay).apply { put("allDay", "1") }
            val result = source.ready(allDay)
            assertEquals(RecurrenceStart.AllDay(LocalDate.of(2026, 11, 17)), result.snapshot.start)
            assertEquals(setOf(RecurrenceKey.Date(LocalDate.of(2026, 11, 17))), result.snapshot.exDates)
            assertFalse(EditableField.ZONE in result.allowedFields)
            p.events[0]["dtstart"] = midnight.plusSeconds(1).toEpochMilli().toString()
            assertTrue(source.loadForEdit(allDay) is SeriesReadResult.NeedsReview)
        }
        for (flag in listOf<String?>(null, "0", "2")) fixture { p, source, _, _ ->
            p.calendar["canModifyTimeZone"] = flag
            assertFalse(EditableField.ZONE in source.ready().allowedFields)
        }
    }

    @Test fun timedFoldPreservesActualOffsetAndUnknownAvailabilityPolicyIsNotAdvertised() = runBlocking {
        fixture { p, source, _, _ ->
            val fold = Instant.parse("2026-11-01T06:30:00Z")
            p.events[0].apply { put("dtstart", fold.toEpochMilli().toString()); put("eventTimezone", "America/New_York"); put("eventEndTimezone", "America/New_York") }
            p.calendar["allowedAvailability"] = null
            val result = source.ready()
            val timed = result.snapshot.start as RecurrenceStart.Timed
            assertEquals(fold, timed.instant)
            assertEquals(ZoneOffset.ofHours(-5), timed.preferredOffset)
            assertFalse(EditableField.AVAILABILITY in result.allowedFields)
        }
    }

    @Test fun runtimeReadPermissionIsFreshAndWriteGrantIsNotNeededForInspection() = runBlocking {
        fixture { p, source, _, permissions ->
            assertTrue(source.loadForEdit(selected) is SeriesReadResult.Ready)
            p.calls.clear(); permissions.clear()
            assertEquals(SeriesReadResult.PermissionRequired, source.loadForEdit(selected))
            assertTrue(p.calls.isEmpty())
        }
        fixture { p, source, _, permissions ->
            p.afterQuery = { permissions.clear() }
            assertEquals(SeriesReadResult.PermissionRequired, source.loadForEdit(selected))
            assertEquals(listOf("calendars"), p.calls.map { it.path })
            assertEquals(1, p.closes)
        }
    }

    @Test fun cancellationAbortsBlockedContentResolverQuery() = runBlocking {
        fixture { p, source, _, _ ->
            p.blocked = CountDownLatch(1)
            val job = launch(Dispatchers.Default) { source.loadForEdit(selected) }
            withTimeout(5_000) { while (p.signal == null) yield() }
            job.cancelAndJoin()
            assertTrue(p.signal!!.isCanceled)
        }
    }
}
