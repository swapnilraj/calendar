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
import kotlinx.coroutines.flow.filterIsInstance
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
                val time = draft.time as EventTime.Timed
                return MutationResult.LocalSaved(EventRef(draft.calendarId, 99, time.start, time.end))
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
            assertEquals(EventTime.Timed(Instant.parse("2026-11-17T09:30:00Z"), Instant.parse("2026-11-17T10:30:00Z")), saved?.time)
            assertEquals(listOf(10), saved?.alertMinutes)
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
            CalendarInfo(CalendarId(3), "Fixture calendar", 0xff397d70.toInt(), CalendarAccount("owner@example.invalid", "com.google"),
                "owner@example.invalid", java.time.ZoneId.of("Europe/London"), CalendarContract.Calendars.CAL_ACCESS_OWNER, true, true),
            eventZone = java.time.ZoneId.of("Europe/London")), EditToken("fixture-token"), EditableField.entries.toSet())
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

    @Test fun drawerReopensRetainedDraftEvenWhenProviderMetadataIsUnavailable() {
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val context = RuntimeEnvironment.getApplication()
        val settings = dev.swapnilraj.calendar.data.Settings(context)
        runBlocking { settings.setCalendarSelection(CalendarSelection.ProviderVisible); settings.setCalendarView("DAY") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = dev.swapnilraj.calendar.events.EventEditorControllerTest.Store()
        var writes = 0
        val writer = object : CalendarMutations {
            override suspend fun loadForEdit(ref: EventRef): EditReadResult = EditReadResult.Unavailable
            override suspend fun create(operationId: String, draft: EventDraft): MutationResult { writes++; error("No save requested") }
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("No update requested")
            override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("No delete requested")
            override suspend fun recover(operationId: String): MutationResult = MutationResult.Uncertain(operationId, null)
        }
        val clock = java.time.Clock.fixed(Instant.parse("2026-11-17T09:20:00Z"), java.time.ZoneOffset.UTC)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, ZonedDateTime.now(clock), true, 64f, {}, {},
                    settings = settings, mutations = writer, drafts = store, draftClock = clock)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Create event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create event").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction() and hasText("Title")).performTextInput("Retained fixture draft")
            compose.onNodeWithText("Cancel").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New event").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { provider.failCalendars = true; reader.refresh() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Calendars couldn’t be read").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.onNodeWithText("Drafts").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retained fixture draft").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNode(isRoot() and hasAnyDescendant(hasText("Open draft")))
                .captureRoboImage("src/test/screenshots/integration_drafts_browser.png")
            compose.onNodeWithText("Open draft").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Retained fixture draft").assertIsDisplayed()
            compose.onAllNodesWithText("2026-11-17").assertCountEquals(2)
            assertEquals(0, writes)
            assertEquals(1, store.records.size)
        } finally {
            scope.cancel()
            runBlocking { settings.setCalendarSelection(CalendarSelection.ProviderVisible); settings.setCalendarView("DAY") }
        }
    }

    @Test fun browserRecoversSubmittedDeletionWithoutReloadingMissingSourceOrDeletingAgain() {
        val provider = FixtureProvider().apply { failCalendars = true }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val context = RuntimeEnvironment.getApplication()
        val settings = dev.swapnilraj.calendar.data.Settings(context)
        runBlocking { settings.setCalendarSelection(CalendarSelection.ProviderVisible); settings.setCalendarView("DAY") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = dev.swapnilraj.calendar.events.EventEditorControllerTest.Store()
        val ref = EventRef(CalendarId(3), 7, Instant.parse("2026-11-17T09:00:00Z"), Instant.parse("2026-11-17T10:00:00Z"))
        val initial = EventDraft(ref.calendarId, EventTime.Timed(ref.instanceStart, ref.instanceEnd), java.time.ZoneOffset.UTC, "Pending deletion fixture")
        val existing = EditableEvent(EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), null, eventZone = initial.eventZone),
            EditToken("fixture-original-token"), EditableField.entries.toSet())
        var deletes = 0
        var recoveries = 0
        var sourceLoads = 0
        val writer = object : CalendarMutations {
            override suspend fun loadForEdit(ref: EventRef): EditReadResult { sourceLoads++; return EditReadResult.Unavailable }
            override suspend fun create(operationId: String, draft: EventDraft): MutationResult = error("No create requested")
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("No update requested")
            override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult {
                deletes++; return MutationResult.Uncertain(operationId, ref.eventId)
            }
            override suspend fun recover(operationId: String): MutationResult { recoveries++; return MutationResult.LocalDeleted(ref) }
        }
        val controller = dev.swapnilraj.calendar.ui.events.EventEditorController(initial, existing, "pending-delete", writer, store, preparationScope)
        controller.requestDelete(); controller.confirmDelete()
        runBlocking { assertTrue(controller.flushDraft()) }
        assertEquals(1, deletes)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, ZonedDateTime.parse("2026-11-17T09:20:00Z"), true, 64f, {}, {},
                    settings = settings, mutations = writer, drafts = store)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Calendars").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.onNodeWithText("Drafts").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Recover operation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recover operation").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.events_editor_recover)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.events_editor_recover)).performScrollTo().performClick()
            compose.waitUntil(10_000) { recoveries == 1 && store.records.isEmpty() }
            assertEquals(1, deletes)
            assertEquals(0, sourceLoads)
        } finally {
            preparationScope.cancel(); scope.cancel()
            runBlocking { settings.setCalendarSelection(CalendarSelection.ProviderVisible); settings.setCalendarView("DAY") }
        }
    }

    @Test fun duplicatingPrivateReadOnlyEventCreatesOnlyAfterSaveAndPreservesPrivacy() {
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,
            FixtureProvider().apply { eventPrivacy = 2; eventAvailability = 1 })
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = dev.swapnilraj.calendar.events.EventEditorControllerTest.Store()
        var creates = 0
        var sourceLoads = 0
        var saved: EventDraft? = null
        val writer = object : CalendarMutations {
            override suspend fun loadForEdit(ref: EventRef): EditReadResult {
                sourceLoads++; return EditReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT)
            }
            override suspend fun create(operationId: String, draft: EventDraft): MutationResult {
                creates++; saved = draft
                val time = draft.time as EventTime.Timed
                return MutationResult.LocalSaved(EventRef(draft.calendarId, 99, time.start, time.end))
            }
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("Source must remain unchanged")
            override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("Source must remain unchanged")
            override suspend fun recover(operationId: String): MutationResult = error("No submitted operation")
        }
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible,
                    ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, {},
                    mutations = writer, drafts = store)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fixture meeting").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Duplicate as draft").fetchSemanticsNodes().isNotEmpty() }
            val eligibilityLoads = sourceLoads
            compose.onNodeWithText("Duplicate as draft").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New event").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, creates)
            assertEquals(eligibilityLoads, sourceLoads)
            assertEquals(1, store.records.size)
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { creates == 1 && store.records.isEmpty() }
            assertEquals(2, saved?.privacy)
            assertEquals(1, saved?.availability)
            assertEquals("Fixture meeting", saved?.title)
            assertEquals(EventTime.Timed(Instant.parse("2026-11-17T09:00:00Z"), Instant.parse("2026-11-17T10:00:00Z")), saved?.time)
            assertEquals(CalendarId(3), saved?.calendarId)
        } finally { scope.cancel() }
    }

    @Test fun settingsSaveChangesDisplayAndDefaultsWithoutLosingFiltersOrFailedInputs() {
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, FixtureProvider().apply { declined = true })
        val context = RuntimeEnvironment.getApplication()
        val values = kotlinx.coroutines.flow.MutableStateFlow<androidx.datastore.preferences.core.Preferences>(androidx.datastore.preferences.core.emptyPreferences())
        var failWrites = false
        val dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                if (failWrites) throw java.io.IOException("Synthetic write failure")
                return transform(values.value).also { values.value = it }
            }
        }
        val settings = dev.swapnilraj.calendar.data.Settings(context, dataStore)
        val options = dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions(
            defaultView = dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.WEEK,
            firstWeekday = dev.swapnilraj.calendar.ui.settings.FirstWeekdayOption.MONDAY,
            showWeekends = false, showDeclined = false,
            displayZone = dev.swapnilraj.calendar.ui.settings.DisplayZoneOption.Fixed(java.time.ZoneId.of("America/Los_Angeles")),
            weatherVisible = false, timedReminderMinutes = 30)
        runBlocking { settings.setDisplaySettings(options); settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(3)))) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, ZonedDateTime.parse("2026-11-17T00:20:00Z"), true, 64f, {}, {}, settings = settings)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("multiday_header_2026-11-16").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("multiday_header_2026-11-21").assertDoesNotExist()
            compose.onNodeWithText("Fixture meeting").assertDoesNotExist()
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.onNodeWithText("Settings").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings_duration").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings_duration").performTextReplacement("75")
            compose.onNodeWithTag("settings_fields").performScrollToNode(hasContentDescription("Show declined events"))
            compose.onNodeWithContentDescription("Show declined events").performClick()
            failWrites = true
            compose.onNodeWithTag("settings_save").performClick()
            compose.onNodeWithText(context.getString(R.string.navigation_preferences_unavailable)).assertIsDisplayed()
            compose.onNodeWithTag("settings_duration").performScrollTo().assertTextContains("75")
            assertEquals(options, runBlocking { settings.calendarPreferences.filterIsInstance<dev.swapnilraj.calendar.data.CalendarPreferenceState.Ready>().first().value.options })
            compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("settings_sheet")))
                .captureRoboImage("src/test/screenshots/integration_settings_retry.png")
            failWrites = false
            compose.onNodeWithTag("settings_save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings_sheet").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            val saved = runBlocking { settings.calendarPreferences.filterIsInstance<dev.swapnilraj.calendar.data.CalendarPreferenceState.Ready>().first().value }
            assertEquals(75, saved.options.defaultDurationMinutes)
            assertTrue(saved.options.showDeclined)
            assertFalse(saved.options.showWeekends)
            assertEquals(CalendarSelection.Explicit(setOf(CalendarId(3))), saved.selection)
            assertEquals("WEEK", saved.view)
        } finally { scope.cancel() }
    }

    @Test fun recurringDetailsOpenScopeThenMasterEditorWithoutUsingOrdinaryWriter() {
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, FixtureProvider().apply { recurring = true })
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = dev.swapnilraj.calendar.events.SeriesAtomicStore()
        val start = Instant.parse("2026-11-17T09:00:00Z")
        val ref = EventRef(CalendarId(3), 7, start, start.plusSeconds(3600), RecurrenceRef(7, start))
        val fields = setOf(EditableField.TITLE, EditableField.LOCATION, EditableField.DESCRIPTION, EditableField.ALERTS, EditableField.AVAILABILITY, EditableField.PRIVACY)
        val source = EditableSeries(EventDetails(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Fixture meeting", 1),
            CalendarInfo(CalendarId(3), "Fixture calendar", 0xff397d70.toInt(), CalendarAccount("owner@example.invalid", "com.google"),
                "owner@example.invalid", java.time.ZoneId.of("Europe/London"), CalendarContract.Calendars.CAL_ACCESS_OWNER, true, true,
                CalendarCapabilities(AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED)),
            eventZone = java.time.ZoneId.of("Europe/London")), SeriesMasterFields("Weekly template", privacy = 2),
            RecurrenceSeriesSnapshot(SeriesIdentity(ref.calendarId, 7), RecurrenceStart.Timed(java.time.LocalDateTime.parse("2026-11-17T09:00:00"), java.time.ZoneId.of("Europe/London"), java.time.ZoneOffset.UTC),
                "FREQ=WEEKLY", durationRaw = "PT1H", coverage = SeriesCoverage.COMPLETE, validatedSelection = ref), EditToken("series-source-token"), fields,
            mapOf(RecurrenceScope.ALL to SeriesScopeCapabilities(AccessDecision.ALLOWED, fields, AccessDecision.ALLOWED)))
        var reads = 0
        val submitted = mutableListOf<SeriesCommand>()
        val series = object : CalendarSeriesMutations {
            override suspend fun loadForEdit(selected: EventRef): SeriesReadResult { assertEquals(ref, selected); reads++; return SeriesReadResult.Ready(source) }
            override suspend fun mutate(operationId: String, selected: EventRef, token: EditToken, command: SeriesCommand): SeriesMutationResult {
                assertEquals(ref, selected); assertEquals(source.token, token); submitted += command
                return SeriesMutationResult.LocalSaved(source.snapshot.identity, ref)
            }
            override suspend fun recover(operationId: String): SeriesMutationResult = error("No recovery requested")
        }
        val ordinary = object : CalendarMutations {
            override suspend fun loadForEdit(ref: EventRef): EditReadResult = error("Recurring events must use scoped eligibility")
            override suspend fun create(operationId: String, draft: EventDraft): MutationResult = error("No create requested")
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("No ordinary update requested")
            override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("No ordinary delete requested")
            override suspend fun recover(operationId: String): MutationResult = error("No ordinary recovery requested")
        }
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, {},
                    mutations = ordinary, seriesMutations = series, drafts = store, requestMeetingWritePermission = { true })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fixture meeting").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Edit event").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit event").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Entire series").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("This occurrence").assertIsNotEnabled()
            compose.onNodeWithText("This and following occurrences").assertIsNotEnabled()
            compose.onNodeWithText("Entire series").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Weekly template").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, store.records.size)
            assertTrue(submitted.isEmpty())
            compose.onNodeWithText("Weekly template").performTextReplacement("Updated weekly template")
            compose.waitForIdle()
            compose.onNodeWithText("Updated weekly template").assertExists()
            compose.onNode(isRoot() and hasAnyDescendant(hasText("Edit recurring event")))
                .captureRoboImage("src/test/screenshots/integration_series_editor.png")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { submitted.size == 1 && store.records.isEmpty() }
            val command = submitted.single() as SeriesCommand.WholeEdit
            assertEquals(FieldChange.Set("Updated weekly template"), command.intent.fields.title)
            assertEquals(FieldChange.Keep, command.intent.fields.time)
            assertEquals(FieldChange.Keep, command.intent.fields.eventZone)
            assertTrue(reads >= 3)
            assertEquals(0, store.legacyCalls)
        } finally { scope.cancel() }
    }

    private fun isolatedSearchSettings(view: dev.swapnilraj.calendar.ui.calendars.CalendarViewOption = dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.WEEK): dev.swapnilraj.calendar.data.Settings {
        val values = kotlinx.coroutines.flow.MutableStateFlow<androidx.datastore.preferences.core.Preferences>(androidx.datastore.preferences.core.emptyPreferences())
        val dataStore = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                transform(values.value).also { values.value = it }
        }
        return dev.swapnilraj.calendar.data.Settings(RuntimeEnvironment.getApplication(), dataStore).also { settings -> runBlocking {
            settings.setDisplaySettings(dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions(
                defaultView = view,
                firstWeekday = dev.swapnilraj.calendar.ui.settings.FirstWeekdayOption.MONDAY, weatherVisible = false))
        } }
    }

    @Test fun realSearchOpensNativeDetailsOutsideTheVisibleWeekWithoutLosingItsOccurrence() =
        searchNavigationJourney(dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.WEEK)

    @Test fun daySearchMovesPagerToResultDateAfterClosingNativeDetails() =
        searchNavigationJourney(dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.DAY)

    private fun searchNavigationJourney(view: dev.swapnilraj.calendar.ui.calendars.CalendarViewOption) {
        val provider = FixtureProvider().apply {
            canonicalSearchMetadata = true
            start = Instant.parse("2027-01-01T09:00:00Z").toEpochMilli()
            end = Instant.parse("2027-01-01T10:00:00Z").toEpochMilli()
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = isolatedSearchSettings(view)
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            val search = dev.swapnilraj.calendar.data.calendar.CalendarSearchReader(context.contentResolver, reader, { true })
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, {},
                    settings = settings, search = search)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Calendars").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fixture meeting").assertDoesNotExist()
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.onNodeWithText("Search events").performClick()
            compose.onNodeWithTag("search_query").performTextReplacement("Fixture")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("search_results").performScrollToNode(hasText("Fixture meeting"))
            compose.onNodeWithText("Jan 1, 2027", substring = true).assertExists()
            compose.waitForIdle()
            if (view == dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.WEEK)
                compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("search_sheet")))
                    .captureRoboImage("src/test/screenshots/integration_live_search.png")
            compose.onNodeWithText("Fixture meeting").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Join meeting").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("search_sheet").assertDoesNotExist()
            val detailsRoot = isRoot() and hasAnyDescendant(hasText("Join meeting"))
            compose.onNode(hasText("Close") and hasAnyAncestor(detailsRoot)).performScrollTo().performClick()
            if (view == dev.swapnilraj.calendar.ui.calendars.CalendarViewOption.DAY) {
                compose.onNodeWithContentDescription("Day 2027-01-01").assertIsDisplayed()
                compose.onNodeWithContentDescription("Day 2026-11-17").assertDoesNotExist()
                compose.waitForIdle()
                compose.onRoot().captureRoboImage("src/test/screenshots/integration_search_day_result.png")
            } else {
                compose.onNodeWithTag("multiday_header_2026-12-28").assertExists()
                compose.onNodeWithTag("multiday_header_2027-01-01").assertExists()
            }
            assertTrue(provider.detailQueries > 0)
        } finally { scope.cancel() }
    }

    @Test fun searchRetainsQueryAcrossCalendarChoiceAndEmptySelectionNeverStartsARead() {
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, FixtureProvider().apply { canonicalSearchMetadata = true })
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = isolatedSearchSettings()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            val delegate = dev.swapnilraj.calendar.data.calendar.CalendarSearchReader(context.contentResolver, reader, { true })
            val search = object : CalendarSearch {
                override fun observe(request: CalendarSearchRequest): kotlinx.coroutines.flow.Flow<CalendarReadState<CalendarSearchSnapshot>> {
                    calls.incrementAndGet(); return delegate.observe(request)
                }
            }
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, ZonedDateTime.parse("2026-11-17T09:20:00Z[Europe/London]"), true, 64f, {}, {}, settings = settings, search = search)
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Calendars").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Calendars").performClick()
            compose.onNodeWithText("Search events").performClick()
            compose.onNodeWithTag("search_query").performTextReplacement("Fixture")
            compose.waitUntil(10_000) { calls.get() > 0 }
            compose.onNodeWithText("Choose calendars").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Select none").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Select none").performClick()
            compose.waitUntil(10_000) { runBlocking { settings.calendarSelection.first() } == CalendarSelection.Explicit(emptySet()) }
            val before = calls.get()
            compose.onNodeWithText("Search events").performClick()
            compose.onNodeWithTag("search_query").assertTextContains("Fixture")
            compose.onNodeWithText(context.getString(R.string.search_no_selection)).assertExists()
            compose.waitForIdle()
            assertEquals(before, calls.get())
        } finally { scope.cancel() }
    }

    private class FixtureProvider : ContentProvider() {
        @Volatile var failCalendars = false
        var canonicalSearchMetadata = false
        var recurring = false
        var declined = false
        var eventPrivacy: Int? = null
        var eventAvailability: Int? = null
        val queries = java.util.concurrent.atomic.AtomicInteger()
        private val ranges = java.util.concurrent.atomic.AtomicInteger()
        private val details = java.util.concurrent.atomic.AtomicInteger()
        val rangeQueries get() = ranges.get()
        val detailQueries get() = details.get()
        var start = Instant.parse("2026-11-17T09:00:00Z").toEpochMilli()
        var end = Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, order: String?): Cursor {
            queries.incrementAndGet()
            val fields = requireNotNull(projection)
            val table = uri.pathSegments.first()
            if (table == "calendars" && failCalendars) error("Synthetic unavailable calendar metadata")
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
                        "rrule" -> if (recurring) "FREQ=WEEKLY" else null
                        "title" -> "Fixture meeting"
                        "description" -> "https://meet.google.com/aaa-bbbb-ccc"
                        "accessLevel" -> eventPrivacy
                        "availability" -> eventAvailability
                        "selfAttendeeStatus" -> if (declined) CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED else 0
                        "allDay" -> 0
                        "deleted" -> if (canonicalSearchMetadata) 0 else null
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
