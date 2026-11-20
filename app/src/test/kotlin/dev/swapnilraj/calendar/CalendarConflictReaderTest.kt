package dev.swapnilraj.calendar.data.calendar

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class CalendarConflictReaderTest {
    private val start = Instant.parse("2026-06-01T10:00:00Z")
    private val identity = CalendarIdentity(CalendarId(2), CalendarAccount("synthetic", "local"), null)
    private fun request(time: EventTime = EventTime.Timed(start, start.plusSeconds(3600)), zone: String = "UTC",
        calendars: Set<CalendarIdentity> = setOf(identity), exclude: EventRef? = null,
        calendarScope: ConflictCalendarScope = ConflictCalendarScope.EXACT_IDENTITIES) =
        ConflictReadRequest(ConflictDraftRevision("draft", 1), time, ZoneId.of(zone), calendars, exclude, calendarScope)
    private fun calendar(id: Long = 2) = mutableMapOf<String, Any?>("_id" to id, "calendar_access_level" to 700,
        "account_name" to "synthetic", "account_type" to "local", "ownerAccount" to null,
        "calendar_displayName" to "Synthetic", "calendar_color" to 123, "calendar_timezone" to "UTC", "visible" to 1, "sync_events" to 1)
    private fun event(id: Long = 10, begin: Instant = start, end: Instant = start.plusSeconds(3600)) = mutableMapOf<String, Any?>(
        "_id" to id, "event_id" to id, "calendar_id" to 2L, "begin" to begin.toEpochMilli(), "end" to end.toEpochMilli(),
        "dtstart" to begin.toEpochMilli(), "dtend" to end.toEpochMilli(), "allDay" to 0, "displayColor" to 123,
        "selfAttendeeStatus" to 1, "eventStatus" to 1, "availability" to 0, "isOrganizer" to 0, "hasAttendeeData" to 1, "deleted" to 0)
    private class Provider : ContentProvider() {
        val calendars = mutableListOf<MutableMap<String, Any?>>()
        val events = mutableListOf<MutableMap<String, Any?>>()
        val instances = mutableListOf<MutableMap<String, Any?>>()
        data class Call(val path: String, val fields: List<String>, val args: List<String>)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())
        var after: ((String, Int) -> Unit)? = null
        var before: ((String, CancellationSignal?) -> Unit)? = null
        var instancesRead = 0
        var metadataRead = 0
        var duplicateMetadata = false
        var ignoreMetadataSelection = false
        var nullPath: String? = null
        var closed = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, signal: CancellationSignal?): Cursor? {
            before?.invoke(uri.pathSegments.first(), signal)
            signal?.throwIfCanceled()
            return query(uri, projection, selection, args, sort)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            val path = uri.pathSegments.first()
            val fields = projection!!.toList()
            val arguments = args?.toList().orEmpty()
            calls += Call(path, fields, arguments)
            if (path == nullPath) return null
            val rows: List<Map<String, Any?>> = when (path) {
                "calendars" -> if (selection == null) calendars else calendars.filter { it["_id"].toString() in arguments }
                "instances" -> {
                    instancesRead++
                    val from = uri.pathSegments[2].toLong(); val to = uri.pathSegments[3].toLong()
                    instances.filter { it["calendar_id"].toString() in arguments &&
                        (it["begin"] as Long) < to && (it["end"] as Long) >= from }
                }
                "events" -> {
                    check("availability" in fields) { "Unexpected recurrence-parent lookup in this fixture" }
                    metadataRead++
                    val idCount = selection!!.substringAfter("_id IN (").substringBefore(')').count { it == '?' }
                    val chosen = events.filter { ignoreMetadataSelection ||
                        (it["_id"].toString() in arguments.take(idCount) && it["calendar_id"].toString() in arguments.drop(idCount)) }
                    if (duplicateMetadata && chosen.isNotEmpty()) chosen + chosen.first() else chosen
                }
                else -> error("Occupancy must not query children")
            }
            val cursor = object : MatrixCursor(projection) { override fun close() { super.close(); closed++ } }
                .apply { rows.forEach { row -> addRow(fields.map { row[it] }.toTypedArray()) } }
            after?.invoke(path, if (path == "instances") instancesRead else metadataRead)
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Read only")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Read only")
        fun add(row: MutableMap<String, Any?>) { events += row; instances += row.toMutableMap() }
    }
    private suspend fun fixture(budgets: ProviderReadBudgets = ProviderReadBudgets(), io: CoroutineDispatcher = Dispatchers.IO,
        block: suspend (Provider, CalendarConflictReader, CalendarReader, () -> Unit) -> Unit) {
        val provider = Provider().apply { calendars += calendar() }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reader = CalendarReader(resolver, scope, stopTimeoutMillis = 0)
        var allowed = true
        val source = CalendarConflictReader(resolver, reader, { allowed }, io, budgets)
        try { block(provider, source, reader) { allowed = false } }
        finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            assertTrue(org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).isEmpty())
        }
    }
    private suspend fun CalendarConflictReader.result(request: ConflictReadRequest = request()) = withTimeout(10000) {
        observe(request).first { it != CalendarReadState.Loading }
    }
    private suspend fun CalendarConflictReader.ready(request: ConflictReadRequest = request()) =
        (result(request) as CalendarReadState.Ready).value
    private fun assessment(snapshot: ConflictSnapshot) = SchedulingConflicts.assess(snapshot.context, snapshot) as ConflictAssessment.Assessed

    @Test fun providerVisibleScopeRejectsOmittedNewVisibleCalendar() = runBlocking {
        val observed = mutableListOf<String>()
        for (change in listOf("insert", "make-visible")) fixture { p, source, _, _ ->
            val later = calendar(3).apply { put("visible", 0) }
            if (change == "make-visible") p.calendars += later
            // The host resolved A while B was absent/hidden; the provider is newer than that replay.
            val staleRequest = request(calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE)
            if (change == "insert") p.calendars += later
            later["visible"] = 1
            p.add(event(30).apply { put("calendar_id", 3L) })
            val actual = source.result(staleRequest)
            val clear = (actual as? CalendarReadState.Ready)?.value?.let { assessment(it).canReportNoConflict } == true
            observed += "$change:${actual.javaClass.simpleName}:$clear"
        }
        println("Dynamic visibility stale host observations=$observed")
        assertEquals(listOf("insert:Unavailable:false", "make-visible:Unavailable:false"), observed)
    }

    @Test fun providerVisibleScopeRejectsCalendarBecomingVisibleDuringOccupancyJoin() = runBlocking {
        fixture { p, source, _, _ ->
            val later = calendar(3).apply { put("visible", 0) }
            p.calendars += later
            p.add(event(30).apply { put("calendar_id", 3L) })
            p.after = { path, count -> if (path == "instances" && count == 1) later["visible"] = 1 }
            val actual = source.result(request(calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE))
            val clear = (actual as? CalendarReadState.Ready)?.value?.let { assessment(it).canReportNoConflict } == true
            println("Dynamic visibility mid-join observation=${actual.javaClass.simpleName}:$clear instances=${p.instancesRead}")
            assertEquals(CalendarReadState.Unavailable, actual)
        }
    }

    @Test fun providerVisibleEmptyScopeStillAttestsCurrentVisibleCalendars() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event())
            val actual = source.result(request(calendars = emptySet(), calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE))
            println("Dynamic visibility empty observation=${actual.javaClass.simpleName} queries=${p.calls.size}")
            assertEquals(CalendarReadState.Unavailable, actual)
        }
    }

    @Test fun providerVisibleScopeRejectsUnknownVisibilityOutsideRequestedIdentities() = runBlocking {
        val observed = mutableListOf<String>()
        for (raw in listOf<String?>(null, "foo", "", "01")) fixture { p, source, _, _ ->
            p.calendars += calendar(3).apply { put("visible", raw) }
            p.add(event(30).apply { put("calendar_id", 3L) })
            val actual = source.result(request(calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE))
            val clear = (actual as? CalendarReadState.Ready)?.value?.let { assessment(it).canReportNoConflict } == true
            observed += "${raw ?: "null"}:${actual.javaClass.simpleName}:$clear"
        }
        println("Dynamic visibility unknown raw observations=$observed")
        assertEquals(listOf("null:Unavailable:false", "foo:Unavailable:false", ":Unavailable:false", "01:Unavailable:false"), observed)
    }

    @Test fun providerVisibleHiddenUnselectedUnknownAccessDoesNotDisableClear() = runBlocking {
        fixture { p, source, _, _ ->
            p.calendars += calendar(3).apply { put("visible", 0); put("calendar_access_level", "unknown") }
            val snapshot = source.ready(request(calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE))
            assertTrue(assessment(snapshot).canReportNoConflict)
        }
    }

    @Test fun explicitScopeMayRemainClearWhenUnselectedCalendarAppears() = runBlocking {
        fixture { p, source, _, _ ->
            p.calendars += calendar(3)
            p.add(event(30).apply { put("calendar_id", 3L) })
            val snapshot = source.ready(request())
            assertEquals(ConflictCalendarScope.EXACT_IDENTITIES, snapshot.context.calendarScope)
            assertTrue(assessment(snapshot).canReportNoConflict)
        }
    }

    @Test fun providerVisibleExactFreshSetFindsOtherCalendarConflict() = runBlocking {
        fixture { p, source, _, _ ->
            p.calendars += calendar(3)
            p.add(event(30).apply { put("calendar_id", 3L) })
            val other = identity.copy(calendarId = CalendarId(3))
            val snapshot = source.ready(request(calendars = setOf(identity, other), calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE))
            assertEquals(ConflictCalendarScope.PROVIDER_VISIBLE, snapshot.context.calendarScope)
            assertEquals(CalendarId(3), assessment(snapshot).conflicts.single().calendarId)
        }
    }

    @Test fun providerVisibleTrulyEmptySetReturnsNoSelectedCalendarsAfterFreshAttestation() = runBlocking {
        fixture { p, source, _, _ ->
            p.calendars.single()["visible"] = 0
            val snapshot = source.ready(request(calendars = emptySet(), calendarScope = ConflictCalendarScope.PROVIDER_VISIBLE))
            assertTrue(SchedulingConflicts.assess(snapshot.context, snapshot) is ConflictAssessment.NoCalendarsSelected)
        }
    }

    @Test fun repeatedCalendarMaterializationConsumesWholeAttemptRowBudget() = runBlocking {
        val defaults = ProviderReadBudgets()
        fixture(budgets = defaults.copy(search = defaults.search.copy(maxRows = 6))) { p, source, _, _ ->
            // No Events or Instances rows: six materializations plus six raw-policy rows exceed six.
            val actual = source.result()
            assertTrue(p.events.isEmpty() && p.instances.isEmpty())
            assertEquals(CalendarReadState.LimitExceeded, actual)
        }
    }
    @Test fun readsAllOccupancyWithoutPrivateTextOrChildrenAndIncludesDeclined() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event()); p.add(event(11).apply { put("selfAttendeeStatus", 2) })
            val snapshot = source.ready()
            assertEquals(2, snapshot.candidates.size)
            assertEquals(ConflictParticipation.DECLINED, snapshot.candidates.last().participation)
            assertEquals(1, assessment(snapshot).conflicts.size)
            assertTrue(p.calls.all { it.path in setOf("calendars", "instances", "events") })
            assertTrue(p.calls.all { call -> call.fields.none { it in setOf("title", "description", "eventLocation", "organizer") } })
        }
    }
    @Test fun knownPoliciesAndUnknownMetadataRemainConservative() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event(10).apply { put("availability", 1) })
            p.add(event(11).apply { put("eventStatus", 2) })
            p.add(event(12).apply { put("selfAttendeeStatus", 2) })
            p.add(event(13).apply { put("availability", null) })
            val actual = assessment(source.ready())
            assertTrue(actual.conflicts.isEmpty()); assertEquals(1, actual.possibleConflicts.size)
            assertFalse(actual.canReportNoConflict)
        }
    }
    @Test fun malformedAndWideNumbersCannotNarrowIntoKnownFreeOrDeclinedValues() = runBlocking {
        fixture { p, source, _, _ ->
            for ((i, raw) in listOf<Any>(4294967297L, "01", "+1", " 1", "9223372036854775808").withIndex())
                p.add(event(i + 10L).apply { put("availability", raw); put("selfAttendeeStatus", 4294967298L) })
            val snapshot = source.ready()
            assertTrue(snapshot.candidates.all { it.availability == ConflictAvailability.UNKNOWN && it.participation == ConflictParticipation.UNKNOWN })
            val actual = assessment(snapshot)
            assertEquals(5, actual.possibleConflicts.size); assertFalse(actual.canReportNoConflict)
        }
    }
    @Test fun personalRequiresAllThreeCanonicalObservations() = runBlocking {
        fixture { p, source, _, _ ->
            for (i in 0..3) p.add(event(i + 10L).apply {
                put("selfAttendeeStatus", 0); put("isOrganizer", if (i == 2) "01" else 1)
                put("hasAttendeeData", when (i) { 0, 2 -> 0; 1 -> 1; else -> "00" })
            })
            val snapshot = source.ready()
            assertEquals(1, snapshot.candidates.count { it.participation == ConflictParticipation.PERSONAL })
            assertEquals(3, assessment(snapshot).possibleConflicts.size)
        }
    }
    @Test fun batchesMetadataInsteadOfNPlusOneAndUsesTwoPasses() = runBlocking {
        fixture { p, source, _, _ ->
            repeat(201) { p.add(event(it + 1L)) }
            assertEquals(201, source.ready().candidates.size)
            assertEquals(4, p.calls.count { it.path == "events" })
            assertEquals(2, p.instancesRead)
        }
    }
    @Test fun exactOccurrenceExclusionNeverDropsSameMasterCandidatePayloads() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event(10).apply { put("rrule", "FREQ=DAILY") })
            val second = event(10, start.plusSeconds(60), start.plusSeconds(120)).apply { put("rrule", "FREQ=DAILY") }
            p.instances += second
            val excluded = EventRef(CalendarId(2), 10, start, start.plusSeconds(3600), RecurrenceRef(10, start))
            val snapshot = source.ready(request(exclude = excluded))
            assertEquals(2, snapshot.candidates.size)
            assertEquals(1, assessment(snapshot).conflicts.size)
        }
    }
    @Test fun dateEncodingIsRetainedWhileMixedOverlapUsesChosenZone() = runBlocking {
        fixture { p, source, _, _ ->
            val from = Instant.parse("2026-06-02T00:00:00Z"); val to = from.plusSeconds(86400)
            p.add(event(10, from, to).apply { put("allDay", 1) })
            val proposed = EventTime.Timed(Instant.parse("2026-06-01T12:30:00Z"), Instant.parse("2026-06-01T13:00:00Z"))
            val snapshot = source.ready(request(proposed, "Pacific/Kiritimati"))
            assertEquals(EventTime.AllDay(CalendarDateRange.day(LocalDate.of(2026, 6, 2))), snapshot.candidates.single().time)
            assertEquals(from, snapshot.candidates.single().ref.instanceStart)
            assertEquals(1, assessment(snapshot).conflicts.size)
        }
    }
    @Test fun midnightGapAndFoldUseDeterministicDateSemantics() = runBlocking {
        fixture { p, source, _, _ ->
            for ((date, zone) in listOf("2018-11-04" to "America/Sao_Paulo", "2026-11-01" to "America/Havana")) {
                p.events.clear(); p.instances.clear()
                val day = LocalDate.parse(date)
                val from = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                p.add(event(10, from, day.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()).apply { put("allDay", 1) })
                assertEquals(1, assessment(source.ready(request(EventTime.AllDay(CalendarDateRange.day(day)), zone))).conflicts.size)
            }
        }
    }
    @Test fun skippedDateAndOversizedRangeReturnExplicitUnsupported() = runBlocking {
        fixture { _, source, _, _ ->
            for (request in listOf(request(EventTime.AllDay(CalendarDateRange.day(LocalDate.of(2011, 12, 30))), "Pacific/Apia"),
                request(EventTime.Timed(start, start.plusSeconds(367L * 86400))))) {
                val snapshot = source.ready(request)
                assertEquals(ConflictUnavailableReason.UNSUPPORTED_TIME_RANGE, (snapshot.coverage as ConflictCoverage.Unavailable).reason)
            }
        }
    }
    @Test fun instantToDateOverflowReturnsUnsupported() = runBlocking {
        fixture { _, source, _, _ ->
            val snapshot = source.ready(request(EventTime.Timed(Instant.MAX.minusSeconds(3600), Instant.MAX)))
            assertTrue(snapshot.coverage is ConflictCoverage.Unavailable)
        }
    }
    @Test fun missingHiddenSyncAndAccessLimitsArePartialRatherThanClear() = runBlocking {
        fixture { p, source, _, _ ->
            p.calendars.single()["visible"] = 0; p.calendars.single()["sync_events"] = 0
            p.calendars.single()["calendar_access_level"] = 100
            val missing = identity.copy(calendarId = CalendarId(3))
            val snapshot = source.ready(request(calendars = setOf(identity, missing)))
            assertEquals(setOf(ConflictCoverageProblem.MISSING_CALENDAR, ConflictCoverageProblem.HIDDEN_CALENDAR,
                ConflictCoverageProblem.SYNC_DISABLED, ConflictCoverageProblem.ACCESS_LIMITED),
                (snapshot.coverage as ConflictCoverage.Partial).reasons)
            assertFalse(assessment(snapshot).canReportNoConflict)
        }
    }
    @Test fun rawCalendarPolicyCannotWrapOrUseNoncanonicalFlagsToAuthorizeMetadata() = runBlocking {
        for ((field, raw) in listOf("calendar_access_level" to 4294967996L, "calendar_access_level" to "0700",
            "visible" to "01", "sync_events" to "01")) {
            fixture { p, source, _, _ ->
                p.calendars.single()[field] = raw
                p.add(event())
                assertEquals(CalendarReadState.Unavailable, source.result())
                assertTrue(p.calls.none { it.path == "events" })
            }
        }
    }
    @Test fun wideAndMalformedStatusIsUnknownNotCancelled() = runBlocking {
        fixture { p, source, _, _ ->
            for ((i, raw) in listOf<Any>(4294967298L, "02", "+2").withIndex())
                p.add(event(i + 10L).apply { put("eventStatus", raw) })
            val snapshot = source.ready()
            assertTrue(snapshot.candidates.all { it.status == ConflictEventStatus.UNKNOWN })
            assertEquals(3, assessment(snapshot).possibleConflicts.size)
        }
    }
    @Test fun fullNamespaceMismatchNeverRebindsCalendarNumber() = runBlocking {
        fixture { p, source, _, _ ->
            p.calendars.single()["account_name"] = "replacement"
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun calendarReplacementDuringJoinIsUnavailable() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event())
            p.after = { path, _ -> if (path == "instances") p.calendars.single()["_sync_id"] = "replacement" }
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun appearingMissingCalendarCannotJoinEarlierPartialGeneration() = runBlocking {
        fixture { p, source, _, _ ->
            val missing = identity.copy(calendarId = CalendarId(3))
            p.after = { path, _ -> if (path == "instances" && p.calendars.size == 1) p.calendars += calendar(3) }
            assertEquals(CalendarReadState.Unavailable, source.result(request(calendars = setOf(identity, missing))))
        }
    }
    @Test fun duplicateMissingDeletedAndUnexpectedMetadataNeverBecomeFree() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event()); p.duplicateMetadata = true
            assertEquals(CalendarReadState.Unavailable, source.result())
            p.duplicateMetadata = false; p.events.clear()
            assertEquals(CalendarReadState.Unavailable, source.result())
            p.events += event().apply { put("deleted", 1) }
            assertEquals(CalendarReadState.Unavailable, source.result())
            p.events.clear(); p.events += event(99); p.ignoreMetadataSelection = true
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun noncanonicalIdentityAndDeletedFlagsRejectMetadata() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event().apply { put("deleted", "00") })
            assertEquals(CalendarReadState.Unavailable, source.result())
            p.events.single()["deleted"] = 0; p.events.single()["_id"] = "010"; p.ignoreMetadataSelection = true
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun phantomBusyInsertionBetweenRangePassesIsUnavailableEvenFromEmptyRead() = runBlocking {
        fixture { p, source, _, _ ->
            p.after = { path, count -> if (path == "instances" && count == 1) p.add(event()) }
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun temporalAndOriginalIdentityReplacementIsUnavailable() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event())
            p.after = { path, count -> if (path == "events" && count == 1) p.instances.single()["original_id"] = 99L.also {
                p.instances.single()["originalInstanceTime"] = start.minusSeconds(86400).toEpochMilli()
            } }
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun changedOccupancyBetweenMetadataPassesIsUnavailable() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event())
            p.after = { path, count -> if (path == "events" && count == 1) p.events.single()["availability"] = 1 }
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun permissionLossBeforeAndDuringQueryNeverReturnsReady() = runBlocking {
        fixture { p, source, _, revoke ->
            p.add(event()); p.after = { path, _ -> if (path == "events") revoke() }
            assertEquals(CalendarReadState.PermissionRequired, source.result())
            assertEquals(CalendarReadState.PermissionRequired, source.result())
        }
    }
    @Test fun budgetsAndTruncationNeverReturnComplete() = runBlocking {
        val defaults = ProviderReadBudgets()
        fixture(budgets = defaults.copy(search = defaults.search.copy(maxRows = 1))) { p, source, _, _ ->
            p.add(event()); p.add(event(11))
            assertEquals(CalendarReadState.LimitExceeded, source.result())
        }
        fixture { p, source, _, _ ->
            repeat(10001) { p.add(event(it + 1L)) }
            assertEquals(CalendarReadState.LimitExceeded, source.result())
        }
    }
    @Test fun privateFingerprintRetentionIsBoundedAndNullQueriesUnavailable() = runBlocking {
        fixture(budgets = ProviderReadBudgets(maxSearchRetainedUnits = 20)) { p, source, _, _ ->
            p.add(event()); assertEquals(CalendarReadState.LimitExceeded, source.result())
        }
        fixture { p, source, _, _ ->
            p.add(event()); p.nullPath = "events"
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }
    @Test fun explicitEmptySelectionAndFreshAdapterIssuedGenerations() = runBlocking {
        fixture { p, source, _, _ ->
            val empty = source.ready(request(calendars = emptySet()))
            assertTrue(SchedulingConflicts.assess(empty.context, empty) is ConflictAssessment.NoCalendarsSelected)
            assertTrue(p.calls.isEmpty())
            val next = source.ready()
            assertEquals(empty.context.sourceRevision.namespace, next.context.sourceRevision.namespace)
            assertTrue(next.context.sourceRevision.generation > empty.context.sourceRevision.generation)
        }
    }
    @Test fun invalidationMasksPreviousResultBeforeHeldAdapterIo() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            fixture(io = dispatcher) { p, source, reader, _ ->
                p.add(event())
                val results = Channel<CalendarReadState<ConflictSnapshot>>(Channel.UNLIMITED)
                val collector = launch(Dispatchers.Default) { source.observe(request()).collect { results.send(it) } }
                try {
                    withTimeout(10000) { while (results.receive() !is CalendarReadState.Ready) {} }
                    // Hold the next IO dispatch, not the observer or downstream collector.
                    val holder = launch(dispatcher) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    reader.refresh()
                    assertEquals(CalendarReadState.Loading, withTimeout(5000) { results.receive() })
                    assertTrue(results.tryReceive().isFailure)
                    release.countDown(); holder.join()
                    assertTrue(withTimeout(10000) { results.receive() } is CalendarReadState.Ready)
                } finally { release.countDown(); collector.cancelAndJoin() }
            }
        } finally { release.countDown(); dispatcher.close() }
    }
    @Test fun twoSubscribersShareOneRootObserver() = runBlocking {
        fixture { p, source, _, _ ->
            val entered = CountDownLatch(2); val released = CountDownLatch(1)
            p.before = { path, signal -> if (path == "instances") {
                signal?.setOnCancelListener { released.countDown() }; entered.countDown()
                check(released.await(10, TimeUnit.SECONDS)); signal?.throwIfCanceled()
            } }
            val one = launch(Dispatchers.Default) { source.observe(request()).collect() }
            val two = launch(Dispatchers.Default) { source.observe(request()).collect() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val resolver = RuntimeEnvironment.getApplication().contentResolver
                assertEquals(1, org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).size)
            } finally { one.cancel(); two.cancel(); released.countDown(); one.join(); two.join() }
        }
    }
    @Test fun cancellationSignalsBlockedProvider() = runBlocking {
        fixture { p, source, _, _ ->
            val entered = CountDownLatch(1); val released = CountDownLatch(1)
            var captured: CancellationSignal? = null
            p.before = { path, signal -> if (path == "instances") {
                captured = signal; signal?.setOnCancelListener { released.countDown() }; entered.countDown()
                check(released.await(10, TimeUnit.SECONDS)); signal?.throwIfCanceled()
            } }
            val first = launch(Dispatchers.Default) { source.observe(request()).collect() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val resolver = RuntimeEnvironment.getApplication().contentResolver
            assertEquals(1, org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).size)
            first.cancelAndJoin()
            assertTrue(captured?.isCanceled == true)
            assertTrue(released.await(1, TimeUnit.SECONDS))
        }
    }
}
