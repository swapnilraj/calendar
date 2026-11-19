package dev.swapnilraj.calendar

import android.app.Application
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.*
import dev.swapnilraj.calendar.probe.*
import java.io.IOException
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeWriterProbeDiagnosticsTest {
    @Test fun exactSortMatrixNeverAcceptsArbitraryOrdering() {
        for (scope in NativeProbeQueryScope.entries) {
            assertEquals(scope != NativeProbeQueryScope.OWNED_CALENDAR_ITEM, nativeProbeSortAllowed(scope, null))
            assertEquals(scope == NativeProbeQueryScope.OWNED_CALENDAR_ITEM, nativeProbeSortAllowed(scope, "_id ASC"))
            for (sort in listOf("", "_id DESC", "title ASC", "_id ASC, title ASC", "_id ASC; DELETE FROM events", " _id ASC")) {
                assertFalse(nativeProbeSortAllowed(scope, sort))
            }
        }
    }

    @Test fun realWriterReachesDurableJournalAndSingleDispatchThroughCalendarSortPolicy() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val base = CalendarRecurringCreateTest.Provider()
        val calendarSorts = mutableListOf<String?>()
        val forwarding = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Nonbatch write forbidden")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Nonbatch write forbidden")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Nonbatch write forbidden")
            override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                check(uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY && uri.query == null && uri.fragment == null)
                check(!fields.isNullOrEmpty() && fields.size <= 128)
                val scope = when {
                    uri.pathSegments == listOf("calendars", "2") -> NativeProbeQueryScope.OWNED_CALENDAR_ITEM
                    uri.pathSegments == listOf("events") -> NativeProbeQueryScope.GUARDED_EVENTS
                    uri.pathSegments == listOf("reminders") -> NativeProbeQueryScope.REMINDER_MEMBERSHIP
                    uri.pathSegments.size == 2 && uri.pathSegments[0] == "reminders" -> NativeProbeQueryScope.GUARDED_REMINDER_ITEM
                    uri.pathSegments.size == 4 && uri.pathSegments.take(2) == listOf("instances", "when") -> NativeProbeQueryScope.BOUNDED_INSTANCE
                    else -> error("Unowned query")
                }
                check(nativeProbeSortAllowed(scope, sort))
                if (scope == NativeProbeQueryScope.OWNED_CALENDAR_ITEM) calendarSorts += sort
                return base.query(uri, fields, selection, args, sort)
            }
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = base.applyBatch(operations)
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, forwarding)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val name = "native-diagnostics-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarWriter(context.contentResolver, reader, store, { true })
        try {
            val start = Instant.parse("2028-01-03T09:00:00Z")
            val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A")
            val draft = EventDraft(CalendarId(2), EventTime.Timed(start, start.plusSeconds(3600)), ZoneId.of("UTC"),
                "Synthetic fixture", alertMinutes = listOf(10, 30), targetIdentity = identity)
            assertTrue(writer.create("probe-sort-regression", draft) is MutationResult.LocalSaved)
            assertTrue(calendarSorts.isNotEmpty())
            assertTrue(calendarSorts.all { it == "_id ASC" })
            val operation = requireNotNull(store.operation("probe-sort-regression"))
            assertEquals("VERIFIED", operation.phase)
            assertEquals(6, JSONObject(operation.resultRef!!).getInt("version"))
            assertEquals(1, base.batchCount)
            assertEquals(1, base.count("events")); assertEquals(2, base.count("reminders"))
            val forwardedBefore = base.queries.size
            assertThrows(IllegalStateException::class.java) {
                forwarding.query(CalendarContract.Calendars.CONTENT_URI.buildUpon().appendPath("2").build(), arrayOf("_id"), null, null, "title ASC")
            }
            assertEquals(forwardedBefore, base.queries.size)
            assertEquals(1, base.batchCount)
        } finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); base.db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun resultFactsWhitelistEnumsAndExcludeIdentifiers() {
        val cases = listOf(
            MutationResult.PermissionRequired to "PERMISSION_REQUIRED", MutationResult.Unavailable to "UNAVAILABLE",
            MutationResult.Conflict(null) to "CONFLICT", MutationResult.SafeToRetry("private-operation") to "SAFE_TO_RETRY",
            MutationResult.Retired("private-operation") to "RETIRED", MutationResult.Uncertain("private-operation", 991) to "UNCERTAIN",
        )
        for ((result, kind) in cases) assertEquals(mapOf("kind" to kind), nativeProbeMutationFacts(result).safeFields())
        assertEquals(mapOf("kind" to "DENIED", "denial" to "OWNERSHIP_UNKNOWN"), nativeProbeMutationFacts(MutationResult.Denied(MutationDenial.OWNERSHIP_UNKNOWN)).safeFields())
        assertEquals(mapOf("kind" to "VALIDATION_FAILED", "fields" to listOf("ALERTS", "TIME")),
            nativeProbeMutationFacts(MutationResult.ValidationFailed(setOf(InvalidEventField.TIME, InvalidEventField.ALERTS))).safeFields())
    }

    @Test fun returnedUnavailableIsPublishedBeforeMissingJournalPostcondition() {
        val facts = mutableListOf<Map<String, Any>>()
        assertThrows(IllegalArgumentException::class.java) {
            withNativeProbeMutationFacts(MutationResult.Unavailable, { facts += it.safeFields() }) { requireNotNull(null) }
        }
        assertEquals(listOf(mapOf("kind" to "UNAVAILABLE")), facts)
    }

    @Test fun failureTaxonomyNeverIncludesExceptionMessages() {
        val failures = listOf(SecurityException("private URI") to NativeProbeQueryFailureKind.SDK_PERMISSION,
            IllegalArgumentException("private title") to NativeProbeQueryFailureKind.SDK_ARGUMENT,
            IOException("private location") to NativeProbeQueryFailureKind.SDK_IO,
            java.util.concurrent.CancellationException("private guest") to NativeProbeQueryFailureKind.SDK_CANCELLATION,
            IllegalStateException("private provider payload") to NativeProbeQueryFailureKind.SDK_OTHER)
        failures.forEach { (error, expected) ->
            assertEquals(expected, nativeProbeQueryFailure(error))
            assertEquals(mapOf("kind" to expected.name, "stage" to "DELEGATE_QUERY", "scope" to "GUARDED_EVENTS"),
                NativeProbeQueryFacts(expected, NativeProbeQueryStage.DELEGATE_QUERY, NativeProbeQueryScope.GUARDED_EVENTS).safeFields())
        }
    }
}
