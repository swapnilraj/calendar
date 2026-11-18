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
import dev.swapnilraj.calendar.core.*
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

    @Test fun agendaKeepsOneRangeWhenAnotherDateOpensAndRetainsFilteredNavigation() {
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val context = RuntimeEnvironment.getApplication()
        val settings = dev.swapnilraj.calendar.data.Settings(context)
        runBlocking {
            settings.setCalendarSelection(CalendarSelection.ProviderVisible)
            settings.setCalendarView("AGENDA")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible,
                    ZonedDateTime.parse("2026-11-16T09:20:00Z[Europe/London]"), true, 64f, {}, {}, settings = settings)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/integration_live_agenda.png")
            assertEquals(1, provider.rangeQueries)
            compose.onNodeWithText("Fixture meeting").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Join meeting").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, provider.rangeQueries) // Selecting November17 must not slide the November16 window.
            val detailsRoot = isRoot() and hasAnyDescendant(hasText("Join meeting"))
            compose.onNode(hasText("Close") and hasAnyAncestor(detailsRoot)).performScrollTo().performClick()
            compose.onNodeWithText("Later").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No events in the displayed range.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, provider.rangeQueries)
            compose.onNodeWithContentDescription("Go to today").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(3, provider.rangeQueries)
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.onNodeWithText("Select none").performClick()
            compose.onNodeWithText("Close").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isEmpty() }
            assertEquals("AGENDA", runBlocking { settings.calendarView.first() })
            assertEquals(CalendarSelection.Explicit(emptySet()), runBlocking { settings.calendarSelection.first() })
        } finally {
            scope.cancel()
            runBlocking { settings.setCalendarSelection(CalendarSelection.ProviderVisible); settings.setCalendarView("DAY") }
        }
    }

    @Test fun liveCreateActionOpensEditorAndReturnsToCalendarAfterOneLocalSave() {
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, FixtureProvider())
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var creates = 0
        var saved: EventDraft? = null
        val writer = object : CalendarMutations {
            override suspend fun loadForEdit(ref: EventRef): EditReadResult = EditReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT)
            override suspend fun create(operationId: String, draft: EventDraft): MutationResult {
                creates++; saved = draft
                val dates = (draft.time as EventTime.AllDay).dates
                return MutationResult.LocalSaved(EventRef(draft.calendarId, 99,
                    dates.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                    dates.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
            }
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("Unexpected update")
            override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("Unexpected delete")
            override suspend fun recover(operationId: String): MutationResult = MutationResult.Uncertain(operationId, null)
        }
        val store = dev.swapnilraj.calendar.events.EventEditorControllerTest.Store()
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible,
                    ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, {},
                    mutations = writer, drafts = store)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Create event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create event").performClick()
            compose.onNodeWithText("New event").assertIsDisplayed()
            compose.onAllNodesWithText("2026-11-17").assertCountEquals(2)
            assertEquals(0, creates)
            compose.onNodeWithText("Fixture calendar · owner@example.invalid").performScrollTo().performClick()
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New event").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, creates)
            assertEquals(CalendarId(3), saved?.calendarId)
            assertEquals(EventTime.AllDay(CalendarDateRange.day(java.time.LocalDate.parse("2026-11-17"))), saved?.time)
            compose.onNodeWithText("Create event").assertIsDisplayed()
        } finally { scope.cancel() }
    }

    @Test fun threeDayAndLocaleWeekKeepTheirRangesWhenOpeningAnotherColumn() {
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val context = RuntimeEnvironment.getApplication()
        val settings = dev.swapnilraj.calendar.data.Settings(context)
        runBlocking { settings.setCalendarSelection(CalendarSelection.ProviderVisible); settings.setCalendarView("THREE_DAY") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible,
                    ZonedDateTime.parse("2026-11-16T09:20:00Z[Europe/London]"), true, 64f, {}, {}, settings = settings)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, provider.rangeQueries)
            compose.onNodeWithText("Fixture meeting").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Join meeting").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, provider.rangeQueries)
            val detailsRoot = isRoot() and hasAnyDescendant(hasText("Join meeting"))
            compose.onNode(hasText("Close") and hasAnyAncestor(detailsRoot)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/integration_live_three_day.png")
            compose.onNodeWithContentDescription("Change view. Current view: 3 days").performClick()
            compose.onNodeWithText("Week").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("multiday_header_2026-11-15").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("multiday_header_2026-11-21").assertExists()
            assertEquals(2, provider.rangeQueries)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/integration_live_week.png")
            compose.onNodeWithText("Later").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("multiday_header_2026-11-22").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(3, provider.rangeQueries)
            compose.onNodeWithText("Today").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(4, provider.rangeQueries)
            assertEquals("WEEK", runBlocking { settings.calendarView.first() })
        } finally {
            scope.cancel()
            runBlocking { settings.setCalendarSelection(CalendarSelection.ProviderVisible); settings.setCalendarView("DAY") }
        }
    }

    @Test fun nativeDetailsRequireFreshEligibilityAndDeletionRequiresConfirmation() {
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, FixtureProvider())
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var eligible = false
        var deletes = 0
        val ref = EventRef(CalendarId(3), 7, Instant.parse("2026-11-17T09:00:00Z"), Instant.parse("2026-11-17T10:00:00Z"))
        val source = EditableEvent(EventDetails(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Fixture meeting", 1),
            null, eventZone = java.time.ZoneId.of("Europe/London")), EditToken("fixture-token"), EditableField.entries.toSet())
        val writer = object : CalendarMutations {
            override suspend fun loadForEdit(ref: EventRef): EditReadResult = if (eligible) EditReadResult.Ready(source) else EditReadResult.Denied(MutationDenial.OWNERSHIP_UNKNOWN)
            override suspend fun create(operationId: String, draft: EventDraft): MutationResult = error("Unexpected create")
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("Unexpected update")
            override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult {
                assertEquals(source.token, token); assertEquals(source.details.occurrence.ref, ref)
                deletes++; return MutationResult.LocalDeleted(ref)
            }
            override suspend fun recover(operationId: String): MutationResult = MutationResult.Uncertain(operationId, null)
        }
        val store = dev.swapnilraj.calendar.events.EventEditorControllerTest.Store()
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible,
                    ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, {}, mutations = writer, drafts = store)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fixture meeting").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Join meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit event").assertDoesNotExist()
            compose.runOnIdle { eligible = true; reader.refresh() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Edit event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Delete event").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete this event?").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, deletes)
            val confirmation = isRoot() and hasAnyDescendant(hasText("Delete this event?"))
            compose.onNode(hasText("Delete event") and hasAnyAncestor(confirmation)).performClick()
            compose.waitUntil(10_000) { deletes == 1 }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete this event?").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Create event").assertIsDisplayed()
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
