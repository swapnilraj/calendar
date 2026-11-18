package dev.swapnilraj.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import dev.swapnilraj.calendar.ui.LiveCalendarDay
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveCalendarDayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerEventOpensNativeDetailsAndKeepsItsMeetingLink() {
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var opened: String? = null
        val selection = androidx.compose.runtime.mutableStateOf<CalendarSelection>(CalendarSelection.ProviderVisible)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent {
                DaylightTheme {
                    LiveCalendarDay(reader, selection.value,
                        ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, { opened = it })
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/integration_live_day.png")
            compose.onNodeWithText("Fixture meeting").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Join meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNode(isRoot() and hasAnyDescendant(hasText("Join meeting")))
                .captureRoboImage("src/test/screenshots/integration_live_details.png")
            compose.onNodeWithText("Join meeting").performClick()
            assertEquals("https://meet.google.com/aaa-bbbb-ccc", opened)
            assertTrue(provider.detailQueries > 0)
            // Initial pager pages and detail overlay all subscribe to the same three-day window.
            assertEquals(1, provider.rangeQueries)
            compose.runOnIdle { selection.value = CalendarSelection.Explicit(emptySet()) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Join meeting").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Join meeting").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test fun closingOverflowAfterProviderRemovesEventsDoesNotFocusDetachedButton() {
        val date = java.time.LocalDate.of(2026, 11, 17)
        val start = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val events = androidx.compose.runtime.mutableStateOf((1..5).map { id ->
            dev.swapnilraj.calendar.data.CalEvent(id.toLong(), start, start.plusSeconds(86400),
                "All day $id", 0xff397d70.toInt(), allDay = true)
        })
        compose.setContent {
            DaylightTheme {
                dev.swapnilraj.calendar.ui.DayHeader(date, date, events.value, java.time.ZoneOffset.UTC, true, {})
            }
        }
        compose.onNodeWithText("+2 more events").performClick()
        compose.runOnIdle { events.value = events.value.take(2) }
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("All day 1").assertIsDisplayed()
    }

    @Test fun calendarDrawerPersistsFiltersAndMonthUsesOneLiveRange() {
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val context = RuntimeEnvironment.getApplication()
        val settings = dev.swapnilraj.calendar.data.Settings(context)
        runBlocking {
            settings.setCalendarSelection(CalendarSelection.ProviderVisible)
            settings.setCalendarView("DAY")
            settings.setDefaultCalendar(null)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent {
                val selected = settings.calendarSelection.collectAsState(initial = CalendarSelection.ProviderVisible)
                DaylightTheme {
                    LiveCalendarDay(reader, selected.value, ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"),
                        true, 64f, {}, {}, settings = settings)
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Select none").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/integration_calendar_drawer.png")
            compose.onNodeWithText("Select none").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isEmpty() }
            assertEquals(CalendarSelection.Explicit(emptySet()), runBlocking { settings.calendarSelection.first() })
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithContentDescription("Change view. Current view: Day").performClick()
            compose.onNodeWithText("Month").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("month_grid").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("MONTH", runBlocking { settings.calendarView.first() })
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.onNodeWithText("Select all").performClick()
            compose.onNodeWithText("Close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/integration_live_month.png")
            assertEquals(2, provider.rangeQueries) // One day window, then one whole-month window.
            compose.onNodeWithTag("month_date_2026-11-18").performSemanticsAction(SemanticsActions.OnClick) { it() }
            val dateDialog = isRoot() and hasAnyDescendant(hasTestTag("month_day_events"))
            compose.onNode(hasText("Close") and hasAnyAncestor(dateDialog)).performClick()
            compose.onNodeWithText("Today").performClick()
            compose.onNodeWithTag("month_date_2026-11-17").assertIsSelected()
            compose.onNodeWithTag("month_date_2026-12-01").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("month_day_events").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("December 1, 2026", substring = true) and hasAnyAncestor(dateDialog)).assertIsDisplayed()
            compose.onNode(hasText("Close") and hasAnyAncestor(dateDialog)).performClick()
            compose.onNodeWithText("Today").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("month_date_2026-11-17").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNode(hasText("Fixture meeting") and hasAnyAncestor(hasTestTag("month_day_events"))).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Join meeting").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(4, provider.rangeQueries) // Two month navigations add one query each.
        } finally {
            scope.cancel()
            runBlocking {
                settings.setCalendarSelection(CalendarSelection.ProviderVisible)
                settings.setCalendarView("DAY")
            }
        }
    }

    @Test fun failedPreferencesDoNotSubscribeToProviderAndRetryKeepsExplicitNone() {
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val restored = kotlinx.coroutines.flow.MutableStateFlow(false)
        var persisted = androidx.datastore.preferences.core.preferencesOf(
            androidx.datastore.preferences.core.stringSetPreferencesKey("selected_calendar_ids") to emptySet<String>())
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow {
                if (!restored.value) throw java.io.IOException("Synthetic preference failure")
                emit(persisted)
            }
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences)
                = transform(persisted).also { persisted = it }
        }
        val context = RuntimeEnvironment.getApplication()
        val settings = dev.swapnilraj.calendar.data.Settings(context, store)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent {
                DaylightTheme {
                    LiveCalendarDay(reader, CalendarSelection.ProviderVisible,
                        ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, {}, settings = settings)
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                assertEquals(0, provider.queries.get())
                restored.value = true
            }
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Calendars").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("Fixture meeting").assertDoesNotExist()
            assertEquals(CalendarSelection.Explicit(emptySet()), runBlocking { settings.calendarSelection.first() })
        } finally { scope.cancel() }
    }

    private class FixtureProvider : ContentProvider() {
        val queries = java.util.concurrent.atomic.AtomicInteger()
        private val ranges = java.util.concurrent.atomic.AtomicInteger()
        private val details = java.util.concurrent.atomic.AtomicInteger()
        val rangeQueries get() = ranges.get()
        val detailQueries get() = details.get()
        private val start = Instant.parse("2026-11-17T09:00:00Z").toEpochMilli()
        private val end = Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, order: String?): Cursor {
            queries.incrementAndGet()
            val fields = requireNotNull(projection)
            val table = uri.pathSegments.first()
            if (table == "instances" && selection?.contains("event_id") != true) ranges.incrementAndGet()
            if (table == "events") details.incrementAndGet()
            return MatrixCursor(fields).apply {
                if (table !in setOf("attendees", "reminders")) addRow(fields.map<String, Any?> { field ->
                    when (field) {
                        "_id" -> if (table == "calendars") 3L else 7L
                        "calendar_id" -> 3L
                        "event_id" -> 7L
                        "calendar_displayName" -> "Fixture calendar"
                        "calendar_color", "displayColor" -> 0xff397d70.toInt()
                        "account_name", "ownerAccount", "organizer" -> "owner@example.invalid"
                        "account_type" -> "com.google"
                        "calendar_timezone", "eventTimezone" -> "Europe/London"
                        "calendar_access_level" -> CalendarContract.Calendars.CAL_ACCESS_OWNER
                        "visible", "sync_events" -> 1
                        "begin", "dtstart" -> start
                        "end", "dtend" -> end
                        "title" -> "Fixture meeting"
                        "description" -> "https://meet.google.com/aaa-bbbb-ccc"
                        "allDay", "selfAttendeeStatus" -> 0
                        else -> null
                    }
                }.toTypedArray())
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Read-only fixture")
    }
}
