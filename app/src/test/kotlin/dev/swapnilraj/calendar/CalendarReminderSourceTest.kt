package dev.swapnilraj.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarReminderSource
import java.time.Instant
import java.time.ZoneId
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
class CalendarReminderSourceTest {
    private val now = Instant.parse("2026-11-17T09:00:00Z")
    private val window = InstantBounds(now, now.plusSeconds(86_400))
    private val calendar = CalendarId(2)
    private val info = CalendarInfo(calendar, "Synthetic", 123, CalendarAccount("fixture@example.invalid", "com.google"), null,
        null, 700, true, true)
    private val pin = CalendarReminderSource.calendarIdentity(info)!!
    private fun config(zone: String = "UTC", defaults: ReminderDefaults? = null) = ReminderConfiguration(true,
        setOf(calendar), ZoneId.of(zone), defaults?.let { mapOf(calendar to it) }.orEmpty(), mapOf(calendar to pin))
    private fun cal(id: Long = 2, access: Int? = 700, visible: Int? = 1, sync: Int? = 1, account: String? = "fixture@example.invalid") = mapOf<String, Any?>(
        "_id" to id, "account_name" to account, "account_type" to "com.google", "ownerAccount" to null,
        "calendar_access_level" to access, "visible" to visible, "sync_events" to sync, "calendar_color" to 123)
    private fun event(id: Long = 10, start: Instant = now.plusSeconds(600), allDay: Boolean = false,
        original: Pair<Long, Instant>? = null, recurring: Boolean = false, calId: Long = 2) = mapOf<String, Any?>(
        "_id" to id, "event_id" to id, "calendar_id" to calId, "dtstart" to start.toEpochMilli(),
        "begin" to start.toEpochMilli(), "end" to start.plusSeconds(if (allDay) 86_400 else 3_600).toEpochMilli(),
        "allDay" to if (allDay) 1 else 0, "eventStatus" to 1, "deleted" to 0, "selfAttendeeStatus" to 0,
        "original_id" to original?.first, "originalInstanceTime" to original?.second?.toEpochMilli(),
        "rrule" to if (recurring) "FREQ=DAILY" else null, "rdate" to null,
        "uid2445" to "fixture-uid-$id", "title" to "Private synthetic event", "displayColor" to 456)
    private fun reminder(id: Long = 10, minutes: Int? = 10, method: Int? = CalendarContract.Reminders.METHOD_ALERT) =
        mapOf<String, Any?>("event_id" to id, "minutes" to minutes, "method" to method)

    private class Provider : ContentProvider() {
        val calendars = mutableListOf<Map<String, Any?>>()
        val events = mutableListOf<Map<String, Any?>>()
        val reminders = mutableListOf<Map<String, Any?>>()
        val calls = mutableListOf<Call>()
        var failurePath: String? = null
        var nullPath: String? = null
        var partialPath: String? = null
        var rejectUid = false
        var afterQuery: (() -> Unit)? = null
        var signal: CancellationSignal? = null
        var blocked: CountDownLatch? = null
        var closed = 0
        data class Call(val path: String, val uri: Uri, val projection: List<String>, val selection: String?, val args: List<String>)
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? {
            signal = cancellationSignal
            blocked?.let { latch ->
                cancellationSignal?.setOnCancelListener { latch.countDown() }
                latch.await(5, TimeUnit.SECONDS)
                cancellationSignal?.throwIfCanceled()
            }
            return query(uri, projection, selection, selectionArgs, sortOrder)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val path = uri.pathSegments.first()
            val args = selectionArgs?.toList().orEmpty()
            val fields = projection!!.toList()
            calls += Call(path, uri, fields, selection, args)
            if (path == failurePath) error("Synthetic query failure")
            if (path == nullPath) return null
            if (path == "events" && rejectUid && "uid2445" in fields) throw IllegalArgumentException("Unsupported optional field")
            val rows = when (path) {
                "calendars" -> calendars.filter { it["_id"].toString() in args }
                "events" -> {
                    val count = selection!!.substringAfter("calendar_id IN (").substringBefore(')').count { it == '?' }
                    val calendarIds = args.take(count)
                    val ids = args.drop(count).let { it.take(it.size / 2) }
                    events.filter { row -> row["calendar_id"].toString() in calendarIds &&
                        (ids.isEmpty() || row["_id"].toString() in ids || row["original_id"].toString() in ids) }
                }
                "reminders" -> reminders.filter { it["event_id"].toString() in args }
                "instances" -> {
                    val from = uri.pathSegments[2].toLong()
                    val until = uri.pathSegments[3].toLong()
                    events.filter { row -> row["event_id"].toString() in args && (row["begin"] as Long) < until &&
                        ((row["end"] as Long) > from || (row["begin"] == row["end"] && (row["begin"] as Long) >= from)) }
                }
                else -> error("Unexpected private child query")
            }
            afterQuery?.invoke()
            return object : MatrixCursor(projection) {
                override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
                    if (path == partialPath && newPosition > 0) error("Synthetic partial cursor failure")
                    return super.onMove(oldPosition, newPosition)
                }
                override fun close() { super.close(); closed++ }
            }.apply { rows.forEach { row -> addRow(fields.map { row[it] }.toTypedArray()) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
    }
    private suspend fun fixture(block: suspend (Provider, CalendarReminderSource, () -> Unit) -> Unit) {
        val provider = Provider().apply { calendars += cal() }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        var allowed = true
        val source = CalendarReminderSource(RuntimeEnvironment.getApplication().contentResolver, { allowed })
        block(provider, source) { allowed = false }
    }
    private suspend fun CalendarReminderSource.load(config: ReminderConfiguration = config(), bounds: InstantBounds = window) =
        (loadDueWindow(bounds, config) as ReminderSourceResult.Ready).snapshot
    private suspend fun CalendarReminderSource.resolveOne(key: ReminderKey, config: ReminderConfiguration = config()) =
        (resolve(setOf(key), config) as ReminderResolutionResult.Ready).results.getValue(key)
    private fun key(id: Long = 10, start: Instant = now.plusSeconds(600), minutes: Int = 10) = ReminderKey(calendar, id, start, minutes)

    @Test fun timedDueWindowIsHalfOpenAndDuplicateStoredAlertsCoalesce() = runBlocking {
        fixture { p, source, _ ->
            p.events += event()
            p.events += event(11, now.plusSeconds(86_400 + 600))
            p.events += event(12, now.plusSeconds(599))
            p.reminders += listOf(reminder(), reminder(), reminder(11), reminder(12))
            val result = source.load()
            assertEquals(listOf(key()), result.candidates.map { it.key })
            assertEquals(now, result.candidates.single().due)
            assertEquals("Private synthetic event", result.candidates.single().occurrence.title)
            assertEquals(setOf(calendar), result.completeCalendars)
            assertEquals(1, p.calls.count { it.path == "instances" })
            assertFalse(p.calls.any { it.path == "attendees" })
            assertTrue(p.calls.first { it.path == "events" }.projection.none { it == "title" })
            assertEquals(p.calls.size, p.closed)
        }
    }

    @Test fun longAdvanceAndNegativeOffsetsQueryShiftedWindowsBeyondNinetyDayEventHorizon() = runBlocking {
        fixture { p, source, _ ->
            val distant = now.plusSeconds(200 * 86_400L)
            p.events += event(start = distant)
            p.events += event(11, now.minusSeconds(540 * 60L))
            p.reminders += reminder(minutes = 200 * 24 * 60)
            p.reminders += reminder(11, -540)
            val result = source.load(bounds = InstantBounds(now, now.plusSeconds(90 * 86_400L)))
            assertEquals(setOf(10L, 11L), result.candidates.map { it.occurrence.ref.eventId }.toSet())
            assertTrue(result.candidates.all { it.due == now })
            val starts = p.calls.filter { it.path == "instances" }.map { it.uri.pathSegments[2].toLong() }
            assertTrue(distant.toEpochMilli() in starts)
            assertEquals(2, starts.size)
        }
    }

    @Test fun allDayUsesDeviceWallClockAcrossDstAndDisplayZoneIsNotAnInput() = runBlocking {
        fixture { p, source, _ ->
            val start = Instant.parse("2026-03-09T00:00:00Z")
            p.events += event(start = start, allDay = true)
            p.reminders += reminder(minutes = 900)
            val due = Instant.parse("2026-03-08T13:00:00Z") // 09:00 New York after spring transition.
            val bounds = InstantBounds(due, due.plusSeconds(1))
            val ny = source.load(config("America/New_York"), bounds)
            assertEquals(due, ny.candidates.single().due)
            assertTrue(source.load(config("UTC"), bounds).candidates.isEmpty())
        }
    }

    @Test fun providerDefaultsResolveOnlyWithExplicitLocalMethodAndKnownOffsets() = runBlocking {
        fixture { p, source, _ ->
            p.events += event()
            p.reminders += reminder(minutes = -1, method = CalendarContract.Reminders.METHOD_DEFAULT)
            val unknown = source.load()
            assertTrue(unknown.candidates.isEmpty())
            assertTrue(unknown.failures.any { it.problem == ReminderSourceProblem.UNRESOLVED_METHOD })
            assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.UNRESOLVED_METHOD), source.resolveOne(key()))
            val known = config(defaults = ReminderDefaults(10, 900, ReminderDefaultMethod.LOCAL_ALERT))
            assertEquals(now, source.load(known).candidates.single().due)
            assertTrue(source.load(config(defaults = ReminderDefaults(defaultMethod = ReminderDefaultMethod.LOCAL_ALERT))).failures
                .any { it.problem == ReminderSourceProblem.UNRESOLVED_MINUTES })
            assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.UNRESOLVED_MINUTES), source.resolveOne(key(),
                config(defaults = ReminderDefaults(defaultMethod = ReminderDefaultMethod.LOCAL_ALERT))))
            val nonLocal = config(defaults = ReminderDefaults(10, 900, ReminderDefaultMethod.NON_LOCAL))
            assertTrue(source.load(nonLocal).candidates.isEmpty())
            assertTrue(source.load(nonLocal).failures.isEmpty())
        }
    }

    @Test fun absentReminderRowsAndEmailRowsNeverInventAppDefaultAlerts() = runBlocking {
        fixture { p, source, _ ->
            p.events += event()
            assertTrue(source.load(config(defaults = ReminderDefaults(10, 900, ReminderDefaultMethod.LOCAL_ALERT))).candidates.isEmpty())
            p.reminders += reminder(method = CalendarContract.Reminders.METHOD_EMAIL)
            assertEquals(ReminderResolution.GoneOrIneligible, source.resolveOne(key()))
        }
    }

    @Test fun allDayDefaultAndUnknownDefaultSentinelRemainDistinct() = runBlocking {
        fixture { p, source, _ ->
            val start = Instant.parse("2026-11-18T00:00:00Z")
            p.events += event(start = start, allDay = true)
            p.reminders += reminder(minutes = -1)
            val defaults = config(defaults = ReminderDefaults(10, 900, ReminderDefaultMethod.LOCAL_ALERT))
            assertEquals(900, source.load(defaults).candidates.single().key.resolvedMinutesBefore)
            assertTrue(source.load(config(defaults = ReminderDefaults(10, -1))).candidates.isEmpty())
        }
    }

    @Test fun hiddenAndSyncOffAreCoverageWarningsButPinnedReadableLocalRowsRemainEligible() = runBlocking {
        fixture { p, source, _ ->
            p.calendars[0] = cal(visible = 0, sync = 0)
            p.events += event(); p.reminders += reminder()
            val snapshot = source.load()
            assertEquals(1, snapshot.candidates.size)
            assertTrue(snapshot.completeCalendars.isEmpty())
            assertEquals(setOf(ReminderSourceProblem.PROVIDER_HIDDEN, ReminderSourceProblem.SYNC_DISABLED), snapshot.failures.map { it.problem }.toSet())
            assertTrue(source.resolveOne(key()) is ReminderResolution.Eligible)
        }
    }

    @Test fun freeBusyUnknownAclAndMissingIdentityNeverReadPrivateEventRows() = runBlocking {
        fixture { p, source, _ ->
            for (access in listOf(100, null)) {
                p.calls.clear(); p.calendars[0] = cal(access = access)
                assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.READ_ACCESS_UNAVAILABLE), source.resolveOne(key()))
                assertEquals(listOf("calendars"), p.calls.map { it.path })
            }
            p.calls.clear(); p.calendars[0] = cal(account = null)
            assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.IDENTITY_UNAVAILABLE), source.resolveOne(key()))
            assertEquals(listOf("calendars"), p.calls.map { it.path })
        }
    }

    @Test fun missingPinAndReplacementNamespaceReportObservedIdentityWithoutPrivateQueries() = runBlocking {
        fixture { p, source, _ ->
            val unpinned = source.load(config().copy(expectedCalendarIdentities = emptyMap()))
            assertEquals(pin, unpinned.observedCalendarIdentities[calendar])
            assertTrue(unpinned.failures.any { it.problem == ReminderSourceProblem.MISSING_EXPECTED_IDENTITY })
            p.calls.clear(); p.calendars[0] = cal(account = "replacement@example.invalid")
            val replacement = source.load()
            assertTrue(replacement.failures.any { it.problem == ReminderSourceProblem.IDENTITY_MISMATCH })
            assertNotEquals(pin, replacement.observedCalendarIdentities[calendar])
            assertEquals(listOf("calendars"), p.calls.map { it.path })
        }
    }

    @Test fun missingCalendarIsAuthoritativeOnlyAfterSuccessfulMetadataQuery() = runBlocking {
        fixture { p, source, _ ->
            p.calendars.clear()
            assertEquals(setOf(calendar), source.load().removedCalendars)
            assertEquals(ReminderResolution.GoneOrIneligible, source.resolveOne(key()))
            p.failurePath = "calendars"
            assertTrue(source.load().removedCalendars.isEmpty())
            assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.CALENDAR_UNAVAILABLE), source.resolveOne(key()))
        }
    }

    @Test fun removedEventReminderAndOneOffRescheduleAreExplicitGoneOnlyOnSuccessfulReads() = runBlocking {
        fixture { p, source, _ ->
            assertEquals(ReminderResolution.GoneOrIneligible, source.resolveOne(key()))
            p.events += event(); p.reminders += reminder()
            assertTrue(source.resolveOne(key()) is ReminderResolution.Eligible)
            p.reminders.clear()
            assertEquals(ReminderResolution.GoneOrIneligible, source.resolveOne(key()))
            p.reminders += reminder()
            p.events[0] = event(start = now.plusSeconds(200 * 86_400L))
            assertEquals(ReminderResolution.GoneOrIneligible, source.resolveOne(key()))
            p.failurePath = "events"
            assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.QUERY_FAILED), source.resolveOne(key()))
        }
    }

    @Test fun movedExceptionResolvesOriginalSemanticKeyFarOutsideDiscoveryWindow() = runBlocking {
        fixture { p, source, _ ->
            val original = now.plusSeconds(600)
            val moved = now.plusSeconds(200 * 86_400L)
            p.events += event(10, original, recurring = true) + mapOf("begin" to now.minusSeconds(86_400).toEpochMilli(), "end" to now.minusSeconds(80_000).toEpochMilli())
            p.events += event(99, moved, original = 10L to original)
            p.reminders += reminder(); p.reminders += reminder(99)
            val result = source.resolveOne(key()) as ReminderResolution.Eligible
            assertEquals(99L, result.candidate.occurrence.ref.eventId)
            assertEquals(RecurrenceRef(10, original), result.candidate.occurrence.ref.recurrence)
            assertEquals(moved.minusSeconds(600), result.candidate.due)
            assertTrue(source.load().candidates.isEmpty())
            assertEquals(2, p.calls.filter { it.path == "instances" }.take(2).size)
        }
    }

    @Test fun cancelledDeletedAndDeclinedEventsNeverAlert() = runBlocking {
        fixture { p, source, _ ->
            p.reminders += reminder()
            for (fields in listOf(mapOf("eventStatus" to 2), mapOf("deleted" to 1), mapOf("selfAttendeeStatus" to 2))) {
                p.events.clear(); p.events += event() + fields
                assertTrue(source.load().candidates.isEmpty())
                assertEquals(ReminderResolution.GoneOrIneligible, source.resolveOne(key()))
            }
        }
    }

    @Test fun partialNullAndFailedPrivateQueriesNeverClaimMissingKeysGone() = runBlocking {
        fixture { p, source, _ ->
            p.events += event(); p.reminders += reminder()
            for (path in listOf("events", "reminders", "instances")) {
                p.nullPath = path
                assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.QUERY_FAILED), source.resolveOne(key()))
                assertTrue(source.load().completeCalendars.isEmpty())
            }
            p.nullPath = null; p.partialPath = "reminders"
            p.reminders += reminder(minutes = 20)
            assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.QUERY_FAILED), source.resolveOne(key()))
        }
    }

    @Test fun missingTimeAndPartialOriginalIdentityAreDeferredInsteadOfDeclaredGone() = runBlocking {
        fixture { p, source, _ ->
            p.reminders += reminder()
            for (fields in listOf(mapOf("dtstart" to null), mapOf("original_id" to 20L))) {
                p.events.clear(); p.events += event() + fields
                assertTrue(source.load().candidates.isEmpty())
                assertEquals(ReminderResolution.Deferred(ReminderSourceProblem.INVALID_TIME), source.resolveOne(key()))
            }
        }
    }

    @Test fun optionalUidProjectionFallbackDoesNotInventIdentityChange() = runBlocking {
        fixture { p, source, _ ->
            p.events += event(); p.reminders += reminder()
            val initial = source.load().candidates.single()
            assertNotNull(initial.sourceEventUidFingerprint)
            p.rejectUid = true
            val unsupported = source.load().candidates.single()
            assertNull(unsupported.sourceEventUidFingerprint)
            assertEquals(initial.key, unsupported.key)
            assertEquals(pin, unsupported.sourceCalendarIdentity)
        }
    }

    @Test fun permissionRevocationIsFreshAndNeverReturnsCachedCandidates() = runBlocking {
        fixture { p, source, revoke ->
            p.events += event(); p.reminders += reminder()
            assertEquals(1, source.load().candidates.size)
            p.calls.clear(); revoke()
            assertEquals(ReminderSourceResult.PermissionRequired, source.loadDueWindow(window, config()))
            assertEquals(ReminderResolutionResult.PermissionRequired, source.resolve(setOf(key()), config()))
            assertTrue(p.calls.isEmpty())
        }
        fixture { p, source, revoke ->
            p.afterQuery = revoke
            assertEquals(ReminderSourceResult.PermissionRequired, source.loadDueWindow(window, config()))
            assertEquals(listOf("calendars"), p.calls.map { it.path })
            assertEquals(1, p.closed)
        }
    }

    @Test fun batchQueriesShareOffsetsAndBoundBindArguments() = runBlocking {
        fixture { p, source, _ ->
            repeat(405) { index -> p.events += event(10L + index); p.reminders += reminder(10L + index) }
            val result = source.load()
            assertEquals(405, result.candidates.size)
            assertEquals(3, p.calls.count { it.path == "reminders" })
            assertEquals(3, p.calls.count { it.path == "instances" })
            p.calls.clear()
            val resolved = source.resolve(result.candidates.map { it.key }.toSet(), config()) as ReminderResolutionResult.Ready
            assertEquals(405, resolved.results.size)
            assertTrue(resolved.results.values.all { it is ReminderResolution.Eligible })
            assertEquals(3, p.calls.count { it.path == "instances" })
            assertTrue(p.calls.all { it.args.size <= 600 })
        }
    }

    @Test fun disabledAndEmptyReminderPreferencesNeverSubscribeToAllCalendars() = runBlocking {
        fixture { p, source, _ ->
            assertTrue(source.load(config().copy(enabledCalendars = emptySet())).candidates.isEmpty())
            assertTrue(source.load(config().copy(enabled = false)).candidates.isEmpty())
            assertEquals(ReminderResolution.GoneOrIneligible, source.resolveOne(key(), config().copy(enabled = false)))
            assertTrue(p.calls.isEmpty())
        }
    }

    @Test fun canonicalIdentityDistinguishesNullEmptyOwnerAndIgnoresPresentation() {
        assertEquals(pin, CalendarReminderSource.calendarIdentity(info.copy(displayName = "Renamed", providerVisible = false, color = 999)))
        assertNotEquals(pin, CalendarReminderSource.calendarIdentity(info.copy(owner = "")))
        assertNull(CalendarReminderSource.calendarIdentity(info.copy(account = CalendarAccount("", "com.google"))))
    }

    @Test fun cancellationPropagatesToBlockedResolverQuery() = runBlocking {
        fixture { p, source, _ ->
            p.blocked = CountDownLatch(1)
            val job = launch(Dispatchers.Default) { source.loadDueWindow(window, config()) }
            withTimeout(5_000) { while (p.signal == null) yield() }
            job.cancelAndJoin()
            assertTrue(p.signal!!.isCanceled)
        }
    }
}
