package dev.swapnilraj.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.*
import java.time.Instant
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
class EventNavigationReaderTest {
    private class Provider : ContentProvider() {
        var row: Map<String, Any?>? = mapOf("_id" to 9, "calendar_id" to 2,
            "uid2445" to "synthetic-uid", "deleted" to 0, "eventStatus" to 1, "allDay" to 0,
            "dtstart" to Instant.parse("2026-11-17T10:00:00Z").toEpochMilli(),
            "dtend" to Instant.parse("2026-11-17T11:00:00Z").toEpochMilli(), "eventTimezone" to "Europe/London")
        var calendar = mapOf<String, Any?>("_id" to 2, "calendar_displayName" to "Synthetic hidden calendar",
            "calendar_color" to 0, "account_name" to "fixture@example.invalid", "account_type" to "com.google",
            "ownerAccount" to "fixture@example.invalid", "_sync_id" to "calendar-A",
            "calendar_timezone" to "Europe/London", "calendar_access_level" to 700, "visible" to 0, "sync_events" to 0)
        var beforeQuery: (String, Int) -> Unit = { _, _ -> }
        val counts = mutableMapOf<String, Int>()
        val projections = mutableListOf<List<String>>()
        var closed = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val table = uri.pathSegments.first()
            val count = (counts[table] ?: 0) + 1
            counts[table] = count
            beforeQuery(table, count)
            require(table in setOf("events", "calendars")) { "Navigation metadata must not query instances or private children" }
            if (table == "events") assertEquals(listOf("events", "9"), uri.pathSegments)
            val fields = projection!!.toList()
            projections += fields
            val values = if (table == "events") row else calendar
            return object : MatrixCursor(projection) { override fun close() { closed++; super.close() } }.apply {
                values?.let { addRow(fields.map(it::get).toTypedArray()) }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Read only")
        fun change(vararg fields: Pair<String, Any?>) { row = row!! + fields }
    }
    private fun fixture(budgets: ProviderReadBudgets = ProviderReadBudgets(), run: suspend (Provider, CalendarReader) -> Unit) = runBlocking {
        val provider = Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { run(provider, CalendarReader(RuntimeEnvironment.getApplication().contentResolver, scope, readBudgets = budgets)) }
        finally { scope.cancel() }
    }
    private suspend fun CalendarReader.value() = (readEventNavigationInfo(9) as CalendarReadState.Ready).value!!

    @Test fun hiddenCalendarMetadataPinsFullNamespaceAndUidWithoutPrivateFieldsOrInstances() = fixture { p, r ->
        val value = r.value()
        assertEquals(CalendarIdentity(CalendarId(2), CalendarAccount("fixture@example.invalid", "com.google"), "fixture@example.invalid", "calendar-A"), value.calendar)
        assertEquals("synthetic-uid", value.providerUid)
        assertEquals(Instant.parse("2026-11-17T11:00:00Z"), value.endExclusive)
        assertFalse(value.recurringOrDetached)
        assertEquals(mapOf("events" to 2, "calendars" to 2), p.counts)
        assertEquals(4, p.closed)
        assertTrue(p.projections.flatten().none { it in setOf("title", "description", "eventLocation", "attendeeEmail") })
    }
    @Test fun recurringMasterDoesNotInventEndOrExpandedOccurrence() = fixture { p, r ->
        p.change("rrule" to "FREQ=DAILY;COUNT=5", "dtend" to null, "duration" to "PT3600S")
        val value = r.value()
        assertTrue(value.recurringOrDetached)
        assertNull(value.endExclusive)
    }
    @Test fun detachedSyncOnlyRowIsNeverMistakenForStandalone() = fixture { p, r ->
        p.change("original_sync_id" to "parent", "originalInstanceTime" to 1794909600000L)
        assertTrue(r.value().recurringOrDetached)
    }
    @Test fun standaloneDurationUsesEventZoneAndKeepsElapsedTimeAcrossClockChange() = fixture { p, r ->
        p.change("dtstart" to Instant.parse("2026-10-24T09:00:00Z").toEpochMilli(), "dtend" to null, "duration" to "PT90000S")
        assertEquals(Instant.parse("2026-10-25T10:00:00Z"), r.value().endExclusive)
    }
    @Test fun allDayDurationUsesUtcDatesAndExclusiveEnd() = fixture { p, r ->
        p.change("allDay" to 1, "dtstart" to Instant.parse("2026-11-17T00:00:00Z").toEpochMilli(), "dtend" to null, "duration" to "P2D", "eventTimezone" to "Pacific/Kiritimati")
        val value = r.value()
        assertTrue(value.allDay)
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"), value.endExclusive)
    }
    @Test fun timedNominalOrOverflowDurationNeverGuessesAProviderEnd() = fixture { p, r ->
        for (raw in listOf("P1D", "PT2147483648S", "PT0S")) {
            p.change("dtend" to null, "duration" to raw)
            assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(9))
        }
    }
    @Test fun nonMidnightAllDayAndUnknownKindFailClosed() = fixture { p, r ->
        p.change("allDay" to 1)
        assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(9))
        p.change("allDay" to 2)
        assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(9))
    }
    @Test fun missingDeletedOrCanceledRowsAreAbsent() = fixture { p, r ->
        val original = p.row
        p.row = null
        assertEquals(CalendarReadState.Ready(null), r.readEventNavigationInfo(9))
        p.row = original
        p.change("deleted" to 1)
        assertEquals(CalendarReadState.Ready(null), r.readEventNavigationInfo(9))
        p.change("deleted" to 0, "eventStatus" to 2)
        assertEquals(CalendarReadState.Ready(null), r.readEventNavigationInfo(9))
    }
    @Test fun reusedNumericCalendarIdIsRejectedAcrossRead() = fixture { p, r ->
        p.beforeQuery = { table, count -> if (table == "calendars" && count == 2) p.calendar = p.calendar + ("_sync_id" to "calendar-B") }
        assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(9))
    }
    @Test fun eventUidOrTimeReplacementIsRejectedBeforeReturningMetadata() = fixture { p, r ->
        p.beforeQuery = { table, count -> if (table == "events" && count == 2) p.change("uid2445" to "replacement") }
        assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(9))
    }
    @Test fun freeBusyAndUnknownCalendarRightsDoNotExposeNavigationMetadata() = fixture { p, r ->
        for (access in listOf(100, null, 999)) {
            p.calendar = p.calendar + ("calendar_access_level" to access)
            assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(9))
        }
    }
    @Test fun wrongItemIdentityAndInvalidIdFailClosed() = fixture { p, r ->
        p.change("_id" to 10)
        assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(9))
        val count = p.counts.toMap()
        assertEquals(CalendarReadState.Unavailable, r.readEventNavigationInfo(-1))
        assertEquals(count, p.counts)
    }
    @Test fun revokedReadPermissionAndReadBudgetsArePreserved() = fixture { p, r ->
        p.beforeQuery = { _, _ -> throw SecurityException() }
        assertEquals(CalendarReadState.PermissionRequired, r.readEventNavigationInfo(9))
    }
    @Test fun oversizedMetadataReturnsLimitExceededWithoutPartialAuthority() = fixture(
        ProviderReadBudgets(details = ProviderReadLimits(100, 1000, 1000, maxCellUnits = 32))) { p, r ->
        p.change("uid2445" to "x".repeat(33))
        assertEquals(CalendarReadState.LimitExceeded, r.readEventNavigationInfo(9))
    }
}
