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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
class CalendarSearchReaderTest {
    private val date = LocalDate.of(2026, 11, 17)
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val range = CalendarRequest(CalendarDateRange.day(date), ZoneId.of("UTC"), CalendarSelection.Explicit(setOf(CalendarId(2))))
    private fun request(query: String = "match", calendar: CalendarRequest = range, limit: Int = 200, declined: Boolean = true) = CalendarSearchRequest(query, calendar, limit, declined)
    private fun calendar(id: Long = 2, access: Int? = 700, visible: Int = 1, sync: Int = 1) = mutableMapOf<String, Any?>("_id" to id,
        "calendar_access_level" to access, "account_name" to "account-$id@example.invalid", "account_type" to "com.google", "ownerAccount" to null,
        "calendar_displayName" to "Calendar $id", "calendar_color" to 123, "calendar_timezone" to "UTC", "visible" to visible, "sync_events" to sync)
    private fun event(id: Long = 10, time: Instant = start, title: String = "Match", location: String = "", description: String = "", cal: Long = 2, declined: Boolean = false) = mutableMapOf<String, Any?>(
        "_id" to id, "event_id" to id, "calendar_id" to cal, "begin" to time.toEpochMilli(), "end" to time.plusSeconds(3_600).toEpochMilli(),
        "title" to title, "eventLocation" to location, "description" to description, "displayColor" to 123, "allDay" to 0,
        "selfAttendeeStatus" to if (declined) 2 else 0, "eventStatus" to 1, "deleted" to 0)
    private class Provider : ContentProvider() {
        val calendars = mutableListOf<MutableMap<String, Any?>>()
        val events = mutableListOf<MutableMap<String, Any?>>()
        val instances = mutableListOf<MutableMap<String, Any?>>()
        data class Call(val path: String, val fields: List<String>, val args: List<String>)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())
        var instanceQueries = 0
        var instanceMoves = 0
        var closes = 0
        var nullPath: String? = null
        var failInstanceNumber: Int? = null
        var failPrivateMetadata = false
        var ignoreInstanceSelection = false
        var ignoreEventSelection = false
        var afterQuery: ((String) -> Unit)? = null
        var blockPath: String? = null
        var blockedSignal: CancellationSignal? = null
        var blocked: CountDownLatch? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, signal: CancellationSignal?): Cursor? {
            if (uri.pathSegments.first() == blockPath) {
                blockedSignal = signal
                blocked?.let { latch -> signal?.setOnCancelListener { latch.countDown() }; latch.await(5, TimeUnit.SECONDS); signal?.throwIfCanceled() }
            }
            return query(uri, projection, selection, args, sort)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            val path = uri.pathSegments.first()
            val fields = projection!!.toList()
            val arguments = args?.toList().orEmpty()
            calls += Call(path, fields, arguments)
            if (path == nullPath) return null
            val rows: List<Map<String, Any?>> = when (path) {
                "calendars" -> calendars
                "instances" -> {
                    instanceQueries++
                    if (instanceQueries == failInstanceNumber) error("Synthetic later-page failure")
                    val lower = uri.pathSegments[2].toLong(); val upper = uri.pathSegments[3].toLong()
                    instances.filter { row ->
                        val begin = row["begin"] as Long; val end = row["end"] as Long
                        (ignoreInstanceSelection || row["calendar_id"].toString() in arguments) && begin < upper && (end > lower || (begin == end && begin >= lower))
                    }.sortedWith(compareBy<Map<String, Any?>> { it["begin"] as Long }.thenBy { it["event_id"] as Long })
                }
                "events" -> {
                    val private = "description" in fields
                    if (private && failPrivateMetadata) error("Synthetic private query failure")
                    if (private) {
                        val idCount = selection!!.substringAfter("_id IN (").substringBefore(')').count { it == '?' }
                        events.filter { ignoreEventSelection || (it["_id"].toString() in arguments.take(idCount) && it["calendar_id"].toString() in arguments.drop(idCount)) }
                    } else {
                        val calCount = selection!!.substringAfter("calendar_id IN (").substringBefore(')').count { it == '?' }
                        events.filter { it["calendar_id"].toString() in arguments.take(calCount) && it["_sync_id"].toString() in arguments.drop(calCount) }
                    }
                }
                else -> error("Search must not query details children")
            }
            afterQuery?.invoke(path)
            return object : MatrixCursor(projection) {
                override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
                    if (path == "instances") instanceMoves++
                    return super.onMove(oldPosition, newPosition)
                }
                override fun close() { super.close(); closes++ }
            }.apply { rows.forEach { row -> addRow(fields.map { row[it] }.toTypedArray()) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Search is read-only")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Search is read-only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Search is read-only")
    }
    private suspend fun fixture(total: Int = 50_000, pageRows: Int = 5_000, days: Long = 31, readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
        block: suspend (Provider, CalendarSearchReader, CalendarReader, () -> Unit) -> Unit) {
        val p = Provider().apply { calendars += calendar() }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reader = CalendarReader(resolver, scope, stopTimeoutMillis = 0)
        var allowed = true
        val source = CalendarSearchReader(resolver, reader, { allowed }, maxScannedInstances = total, maxPageRows = pageRows, pageDays = days, readBudgets = readBudgets)
        try { block(p, source, reader) { allowed = false } }
        finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            assertTrue(org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).isEmpty())
        }
    }
    private fun Provider.add(row: MutableMap<String, Any?>) { events += row; instances += row.toMutableMap() }
    private suspend fun CalendarSearchReader.result(request: CalendarSearchRequest = request()): CalendarReadState<CalendarSearchSnapshot> =
        withTimeout(10_000) { observe(request).first { it != CalendarReadState.Loading } }
    private suspend fun CalendarSearchReader.ready(request: CalendarSearchRequest = request()) = (result(request) as CalendarReadState.Ready).value
    private fun Provider.privateCalls() = calls.filter { it.path == "events" && "description" in it.fields }

    @Test fun textBudgetTruncatesEvenWithZeroMatchesAndDropsIncompleteChunk() = runBlocking {
        val defaults = ProviderReadBudgets()
        fixture(readBudgets = defaults.copy(search = defaults.search.copy(maxTextUnits = 40_000))) { p, source, _, _ ->
            repeat(200) { id -> p.add(event((id + 1).toLong()).apply { put("description", "$id".padEnd(16_384, 'x')); put("title", "unrelated") }) }
            val result = source.result(request().copy(query = "never matches")) as CalendarReadState.Ready
            assertTrue(result.value.truncated)
            assertTrue(result.value.hits.isEmpty())
        }
    }

    @Test fun literalTrimmedCaseInsensitiveTitleLocationDescriptionMatchingNeverUsesSqlWildcards() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event(10, title = "Literal %_ '[")); p.add(event(11, title = "Anything"))
            p.add(event(12, title = "Other", location = "Office MATCH")); p.add(event(13, title = "Other", description = "Notes mAtCh"))
            assertEquals(listOf(10L), source.ready(request("  %_ '[  ")).hits.map { it.occurrence.ref.eventId })
            assertEquals(listOf(12L, 13L), source.ready().hits.map { it.occurrence.ref.eventId })
            assertTrue(p.privateCalls().all { it.args.none { argument -> "match" in argument.lowercase() || '%' in argument } })
            assertFalse(p.calls.any { it.path == "attendees" || it.path == "reminders" })
        }
    }

    @Test fun plainSnippetHasNoControlCharactersAndAtMost160UnicodeCodePoints() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event(description = "😀".repeat(180) + "\n<b>match</b>\t" + "tail".repeat(80)))
            val snippet = source.ready().hits.single().snippet!!
            assertTrue(snippet.contains("<b>match</b>"))
            assertFalse(snippet.any(Char::isISOControl))
            assertTrue(snippet.codePointCount(0, snippet.length) <= 160)
        }
    }

    @Test fun declinedAndCancelledRowsAreFilteredBeforeResultLimit() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event(10, start, declined = true)); p.add(event(11, start.plusSeconds(3_600)))
            p.add(event(12, start.plusSeconds(7_200)).apply { put("eventStatus", 2) })
            val result = source.ready(request(limit = 1, declined = false))
            assertEquals(listOf(11L), result.hits.map { it.occurrence.ref.eventId })
            assertFalse(result.truncated)
            val included = source.ready(request(limit = 1))
            assertEquals(listOf(10L), included.hits.map { it.occurrence.ref.eventId })
            assertTrue(included.truncated)
        }
    }

    @Test fun legitimateNullableStatusRemainsSearchableWithoutBecomingCancelledOrDefaulted() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event(10).apply { put("eventStatus", null) })
            p.add(event(11, start.plusSeconds(60)).apply { put("eventStatus", 2) })
            val result = source.ready()
            assertEquals(listOf(10L), result.hits.map { it.occurrence.ref.eventId })
            assertFalse(result.truncated)
            assertNull(p.events[0]["eventStatus"])
        }
    }

    @Test fun explicitEmptyMissingAndProviderVisibleSelectionNeverFailOpen() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event()); p.calendars[0]["visible"] = 0
            assertTrue(source.ready(request(calendar = range.copy(selection = CalendarSelection.ProviderVisible))).hits.isEmpty())
            assertTrue(source.ready(request(calendar = range.copy(selection = CalendarSelection.Explicit(emptySet())))).hits.isEmpty())
            assertEquals(0, p.instanceQueries)
            val explicit = source.ready()
            assertEquals(1, explicit.hits.size)
            assertTrue(explicit.coverage.any { it.reason == CoverageReason.PROVIDER_HIDDEN })
            val missing = source.ready(request(calendar = range.copy(selection = CalendarSelection.Explicit(setOf(CalendarId(999))))))
            assertEquals(listOf(CalendarCoverage(CalendarId(999), CoverageReason.MISSING_CALENDAR)), missing.coverage)
        }
    }

    @Test fun freeBusyAndUnknownAclOmitPrivateDataWithExplicitCoverage() = runBlocking {
        for (access in listOf<Int?>(100, null)) fixture { p, source, _, _ ->
            p.calendars[0]["calendar_access_level"] = access
            p.add(event(description = "Private match"))
            val result = source.ready()
            assertTrue(result.hits.isEmpty())
            assertEquals(listOf(CalendarCoverage(CalendarId(2), CoverageReason.INSUFFICIENT_ACCESS)), result.coverage)
            assertEquals(listOf("calendars"), p.calls.map { it.path })
        }
    }

    @Test fun hiddenSyncOffCalendarStillSearchesExplicitReadableLocalRowsWithWarnings() = runBlocking {
        fixture { p, source, _, _ ->
            p.calendars[0]["visible"] = 0; p.calendars[0]["sync_events"] = 0
            p.add(event())
            val result = source.ready()
            assertEquals(1, result.hits.size)
            assertEquals(setOf(CoverageReason.PROVIDER_HIDDEN, CoverageReason.SYNC_DISABLED), result.coverage.map { it.reason }.toSet())
        }
    }

    @Test fun pageBudgetTruncatesBeforeIdentityPrepassEvenWithZeroMatches() = runBlocking {
        fixture(pageRows = 2) { p, source, _, _ ->
            repeat(3) { index ->
                p.add(event(10L + index, title = "Unrelated").apply { put("original_sync_id", "parent-$index"); put("originalInstanceTime", start.minusSeconds(86_400).toEpochMilli()) })
                p.events += event(100L + index, title = "Master").apply { put("_sync_id", "parent-$index") }
            }
            val result = source.ready(request("unfindable"))
            assertTrue(result.hits.isEmpty()); assertTrue(result.truncated)
            assertEquals(3, p.instanceMoves)
            val identityCall = p.calls.single { it.path == "events" && "_sync_id" in it.fields }
            assertEquals(setOf("parent-0", "parent-1"), identityCall.args.filter { it.startsWith("parent-") }.toSet())
        }
    }

    @Test fun globalBudgetCutsRemainingPagesButExactFinalBoundaryIsComplete() = runBlocking {
        fixture(total = 3, days = 1) { p, source, _, _ ->
            repeat(4) { p.add(event(10L + it, start.plusSeconds(it * 86_400L))) }
            val fourDays = range.copy(range = CalendarDateRange(date, date.plusDays(4)))
            assertTrue(source.ready(request(calendar = fourDays)).truncated)
            assertEquals(3, p.instanceQueries)
            p.instanceQueries = 0
            val exact = source.ready(request(calendar = fourDays.copy(range = CalendarDateRange(date, date.plusDays(3)))))
            assertFalse(exact.truncated); assertEquals(3, exact.hits.size)
        }
    }

    @Test fun exactPageRowBoundaryAndFullyScannedNoMatchDoNotClaimTruncation() = runBlocking {
        fixture(pageRows = 2) { p, source, _, _ ->
            p.add(event(10)); p.add(event(11))
            assertFalse(source.ready().truncated)
            val absent = source.ready(request("unfindable"))
            assertTrue(absent.hits.isEmpty()); assertFalse(absent.truncated)
        }
    }

    @Test fun recurrenceAcrossPagesSharesMetadataAndDeduplicatesLongOverlappingRefs() = runBlocking {
        fixture(days = 31) { p, source, _, _ ->
            p.add(event().apply { put("rrule", "FREQ=DAILY") })
            p.instances += event(time = start.plusSeconds(40 * 86_400L)).apply { put("rrule", "FREQ=DAILY") }
            p.instances += event(time = start.plusSeconds(70 * 86_400L)).apply { put("rrule", "FREQ=DAILY") }
            p.add(event(20, start, description = "Match long").apply { put("end", start.plusSeconds(90 * 86_400L).toEpochMilli()) })
            val result = source.ready(request(calendar = range.copy(range = CalendarDateRange(date, date.plusDays(93)))))
            assertEquals(4, result.hits.size)
            assertEquals(1, p.privateCalls().size)
            assertEquals(3, result.hits.count { it.occurrence.ref.recurrence?.masterEventId == 10L })
            assertEquals(1, result.hits.count { it.occurrence.ref.eventId == 20L })
        }
    }

    @Test fun exactMovedExceptionIdentityAndAllDayUtcBoundariesSurviveSearch() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event(11).apply { put("original_id", 10L); put("originalInstanceTime", start.minusSeconds(86_400).toEpochMilli()) })
            p.add(event(12, Instant.parse("2026-11-17T00:00:00Z")).apply { put("allDay", 1); put("end", Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()) })
            p.add(event(13, Instant.parse("2026-11-17T07:00:00Z"))) // ends at Los Angeles midnight.
            val result = source.ready(request(calendar = range.copy(displayZone = ZoneId.of("America/Los_Angeles"))))
            assertEquals(setOf(11L, 12L), result.hits.map { it.occurrence.ref.eventId }.toSet())
            assertEquals(RecurrenceRef(10, start.minusSeconds(86_400)), result.hits.single { it.occurrence.ref.eventId == 11L }.occurrence.ref.recurrence)
            assertTrue(result.hits.single { it.occurrence.ref.eventId == 12L }.occurrence.time is EventTime.AllDay)
        }
    }

    @Test fun metadataIsBatchedIn200IdsAndHitsAreDeterministicallyOrdered() = runBlocking {
        fixture { p, source, _, _ ->
            repeat(405) { p.add(event(500L - it)) }
            val result = source.ready(request(limit = 1000))
            assertEquals(405, result.hits.size)
            assertEquals(result.hits.map { it.occurrence.ref.eventId }.sorted(), result.hits.map { it.occurrence.ref.eventId })
            assertEquals(3, p.privateCalls().size)
            assertTrue(p.privateCalls().all { it.args.size <= 400 })
        }
    }

    @Test fun accountNamespaceChangeDropsPrefixBeforeAnyForeignMetadataCanJoin() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event())
            p.afterQuery = { path -> if (path == "instances") p.calendars[0]["account_name"] = "replacement@example.invalid" }
            assertEquals(CalendarReadState.Unavailable, source.result())
            assertTrue(p.privateCalls().isEmpty())
        }
        fixture { p, source, _, _ ->
            p.add(event())
            p.afterQuery = { path -> if (path == "events") p.calendars[0]["account_name"] = "replacement@example.invalid" }
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }

    @Test fun ignoredInstanceSelectionConsumesBudgetWithoutMaterializingForeignIdentityOrText() = runBlocking {
        fixture(pageRows = 1) { p, source, _, _ ->
            p.calendars += calendar(3, access = 100)
            p.add(event(11, start.minusSeconds(60), cal = 3).apply { put("original_sync_id", "private-parent"); put("originalInstanceTime", start.toEpochMilli()) })
            p.add(event(12))
            p.ignoreInstanceSelection = true
            val result = source.ready()
            assertTrue(result.hits.isEmpty()); assertTrue(result.truncated)
            assertTrue(p.calls.none { it.path == "events" })
        }
    }

    @Test fun eventMovedCalendarAndProviderIgnoringPredicatesNeverJoinForeignText() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event())
            p.afterQuery = { path -> if (path == "instances") p.events[0]["calendar_id"] = 999L }
            assertEquals(CalendarReadState.Unavailable, source.result())
            p.ignoreEventSelection = true
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }

    @Test fun defaultSelectionChangingDuringScanDoesNotPublishFalseComplete() = runBlocking {
        fixture { p, source, _, _ ->
            p.add(event())
            var added = false
            p.afterQuery = { path -> if (path == "instances" && !added) { p.calendars += calendar(3); added = true } }
            assertEquals(CalendarReadState.Unavailable, source.result(request(calendar = range.copy(selection = CalendarSelection.ProviderVisible))))
        }
    }

    @Test fun laterPageAndMetadataFailureNeverEmitReadyPrefix() = runBlocking {
        fixture(days = 1) { p, source, _, _ ->
            p.add(event()); p.add(event(11, start.plusSeconds(86_400)))
            p.failInstanceNumber = 2
            assertEquals(CalendarReadState.Unavailable, source.result(request(calendar = range.copy(range = CalendarDateRange(date, date.plusDays(2))))))
        }
        fixture { p, source, _, _ ->
            p.add(event()); p.failPrivateMetadata = true
            assertEquals(CalendarReadState.Unavailable, source.result())
        }
    }

    @Test fun permissionIsRecheckedWithoutCachedResultsAndCancellationAbortsResolver() = runBlocking {
        fixture { p, source, _, revoke ->
            p.add(event()); assertEquals(1, source.ready().hits.size)
            revoke(); p.calls.clear()
            assertEquals(CalendarReadState.PermissionRequired, source.result())
            assertTrue(p.calls.isEmpty())
        }
        fixture { p, source, _, _ ->
            p.blockPath = "instances"; p.blocked = CountDownLatch(1)
            val job = launch(Dispatchers.Default) { source.result() }
            withTimeout(5_000) { while (p.blockedSignal == null) yield() }
            job.cancelAndJoin(); assertTrue(p.blockedSignal!!.isCanceled)
        }
    }

    @Test fun sharedObserverRefreshesSearchAndIsCleanedUpAfterCollectorsLeave() = runBlocking {
        fixture { p, source, reader, _ ->
            p.add(event(title = "Unrelated"))
            val results = Channel<CalendarSearchSnapshot>(Channel.UNLIMITED)
            val jobs = List(2) { launch { source.observe(request()).collect { if (it is CalendarReadState.Ready) results.send(it.value) } } }
            repeat(2) { assertTrue(withTimeout(10_000) { results.receive() }.hits.isEmpty()) }
            val resolver = RuntimeEnvironment.getApplication().contentResolver
            assertEquals(1, org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).size)
            p.events[0]["description"] = "New match"
            reader.refresh()
            repeat(2) { assertEquals(1, withTimeout(10_000) { results.receive() }.hits.size) }
            jobs.forEach { it.cancelAndJoin() }
            withTimeout(10_000) { while (org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).isNotEmpty()) yield() }
        }
    }
}
