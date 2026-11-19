package dev.swapnilraj.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.LiveCalendarDay
import dev.swapnilraj.calendar.ui.ReminderSettingsWorkflow
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.settings.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.time.*

/** Actual MainActivity journeys use real settings, reader, search and SQL storage. Stable screenshots
 * separately use the complete production LiveCalendarDay host's existing fixed-now input, with real
 * app services. Robolectric compositions are not emulator/device or Google visual-parity proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCalendarNavigationJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixedNow = ZonedDateTime.parse("2026-11-17T19:20:00Z[UTC]")
    private val zone = ZoneId.of("Pacific/Kiritimati")
    private val none = CalendarSelection.Explicit(emptySet())
    private val views = listOf("AGENDA", "DAY", "THREE_DAY", "WEEK", "MONTH")

    @Test fun actualDrawerOrdersFiveViewsAndPreservesSelectedDateAndExplicitNoneAcrossRecreation() = mainFixture { f ->
        val month = YearMonth.now(zone).plusMonths(1)
        expand(); compose.onNodeWithContentDescription("Next month").performClick()
        choose(month.atDay(15))
        for (view in views) {
            drawer()
            val bounds = views.map { compose.onNodeWithTag("calendar_drawer_view_$it").fetchSemanticsNode().boundsInRoot }
            assertTrue(bounds.zipWithNext().all { (a, b) -> a.top < b.top })
            compose.onNodeWithTag("calendar_drawer_view_$view").performClick()
            awaitView(f, view)
            expand("after selecting $view"); date(month.atDay(15)).assertIsSelected(); collapseBack(f.activity)
            assertNone(f)
            compose.onAllNodesWithTag("calendar_phone_toolbar").assertCountEquals(1)
            compose.onAllNodesWithContentDescription("Change view. Current view:", substring = true).assertCountEquals(0)
        }
        val previousActivity = f.activity.get()
        compose.runOnUiThread { f.activity.recreate() }
        assertNotSame("Recreation must replace the MainActivity instance", previousActivity, f.activity.get())
        awaitView(f, "MONTH")
        expand("after Activity recreation"); date(month.atDay(15)).assertIsSelected(); assertNone(f)
    }

    @Test fun actualTodayUsesFixedDisplayZoneAndBackCollapsesOnlyTheMonthStrip() = mainFixture { f ->
        val current = LocalDate.now(zone)
        assertNotEquals("Fixture device date must differ from fixed display zone", LocalDate.now(), current)
        expand(); compose.onNodeWithContentDescription("Next month").performClick()
        choose(YearMonth.from(current).plusMonths(1).atDay(10))
        compose.onNodeWithTag("calendar_phone_today").performClick()
        expand(); date(current).assertIsSelected()
        collapseBack(f.activity)
        compose.onNodeWithTag("calendar_month_navigator").assertDoesNotExist()
        assertFalse(f.activity.get().isFinishing)
        assertNone(f)
    }

    @Test fun actualToolbarSearchUsesRealProviderAndNeverChangesExplicitNone() = mainFixture { f ->
        compose.onNodeWithContentDescription("Search events").performClick()
        shownTag("search_query")
        compose.onNodeWithTag("search_query").performTextReplacement("Synthetic phone")
        compose.onNodeWithTag("search_sheet").assertExists()
        compose.onAllNodesWithText("Synthetic phone appointment").assertCountEquals(0)
        assertNone(f)
        compose.runOnUiThread { f.activity.get().onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        runBlocking {
            val metadata = f.app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(f.app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(3))), metadata.value))
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search events").performClick()
        shownTag("search_query")
        compose.onNodeWithTag("search_query").performTextReplacement("Synthetic phone")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Synthetic phone appointment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic phone appointment").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Synthetic room").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, f.provider.writeAttempts)
        assertTrue(runBlocking { f.app.calendarLocalStore.list() }.isEmpty())
        f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun completeLiveHostLightPhoneNavigation() = liveImage("integration_phone_live_light.png", false, 1f, LayoutDirection.Ltr)
    @Test fun completeLiveHostDarkPhoneNavigation() = liveImage("integration_phone_live_dark.png", true, 1f, LayoutDirection.Ltr)
    @Test fun completeLiveHostLargeFontRtlPhoneNavigation() = liveImage("integration_phone_live_large_rtl.png", false, 2f, LayoutDirection.Rtl)

    @Test fun phoneExpansionAbaCancelsPendingRealWriterPreflight() = pendingGrid(cas = false)
    @Test fun phoneExpansionAbaCancelsPendingRealSqlDraftAcknowledgment() = pendingGrid(cas = true)

    private fun pendingGrid(cas: Boolean) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = NativeRecurringCreateJourneyProvider(allowUpdates = true)
        val now = ZonedDateTime.parse("2026-11-17T09:20:00Z[UTC]")
        provider.seedOneOff(now.toLocalDate().atTime(9, 0).toInstant(ZoneOffset.UTC),
            now.toLocalDate().atTime(10, 0).toInstant(ZoneOffset.UTC), "Pending navigation appointment")
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, displayZone = DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value)
        }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val settled = CompletableDeferred<Unit>()
        val mutations = object : CalendarMutations by app.calendarWriter {
            override suspend fun loadForEdit(ref: EventRef): EditReadResult {
                val value = app.calendarWriter.loadForEdit(ref)
                if (!cas && !entered.isCompleted) {
                    assertTrue("Real native writer must first prove this source editable", value is EditReadResult.Ready)
                    entered.complete(Unit)
                    try { release.await() } finally { settled.complete(Unit) }
                }
                return value
            }
        }
        val drafts = object : AtomicDraftStore, DraftStore by app.calendarLocalStore {
            override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
                if (cas && expected == null && replacement != null && !entered.isCompleted) { entered.complete(Unit); release.await() }
                return app.calendarLocalStore.compareAndSet(id, expected, replacement).also { if (expected == null && replacement != null) settled.complete(Unit) }
            }
        }
        lateinit var activity: ActivityController<ComponentActivity>
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            activity.get().setContent { DaylightTheme {
                LiveCalendarDay(app.calendarReader, CalendarSelection.ProviderVisible, now, true, 64f, {}, {},
                    settings = app.settings, mutations = mutations, drafts = drafts, search = app.calendarSearch,
                    requestMeetingWritePermission = { true }, operationRetirement = app.operationRetirement)
            } }
        }
        try {
            shownTag("calendar_phone_month")
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Pending navigation appointment").fetchSemanticsNodes().isNotEmpty() }
            val target = if (cas) compose.onNodeWithTag("timed_grid_viewport_2026-11-17") else compose.onNodeWithText("Pending navigation appointment")
            val event = compose.onNodeWithText("Pending navigation appointment").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("timed_grid_viewport_2026-11-17").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            target.performTouchInput { down(if (cas) androidx.compose.ui.geometry.Offset(280f, event.top - viewport.top + 128f) else center) }
            compose.mainClock.advanceTimeBy(700)
            if (cas) { target.performTouchInput { up() }; compose.mainClock.autoAdvance = true }
            compose.waitUntil(15_000) { compose.mainClock.advanceTimeBy(16); entered.isCompleted }
            // Return to the same visible context; the navigation epoch must still reject late work.
            compose.onNodeWithTag("calendar_phone_month").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(48)
            shownTag("calendar_month_navigator")
            compose.onNodeWithTag("calendar_phone_month").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(48)
            release.complete(Unit)
            if (!cas) target.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(15_000) { compose.waitForIdle(); settled.isCompleted && runBlocking { app.calendarLocalStore.list() }.isEmpty() }
            compose.onNodeWithText("New event").assertDoesNotExist()
            compose.onNodeWithText("Edit event").assertDoesNotExist()
            assertTrue(runBlocking { app.calendarLocalStore.list() }.isEmpty())
            assertEquals(0, provider.batchCount)
        } finally {
            release.complete(Unit); compose.mainClock.autoAdvance = true
            compose.runOnUiThread { activity.pause().stop().destroy() }; provider.db.close()
        }
    }

    private fun liveImage(path: String, dark: Boolean, font: Float, direction: LayoutDirection) {
        val (app, provider) = prepare(if (dark) SettingsTheme.DARK else SettingsTheme.LIGHT,
            fixedNow.withZoneSameInstant(zone).toLocalDate())
        runBlocking {
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(3))), metadata.value))
        }
        lateinit var activity: ActivityController<ComponentActivity>
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            activity.get().setContent {
                DaylightTheme(dark) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font), LocalLayoutDirection provides direction) {
                        val scope = rememberCoroutineScope()
                        var remindersOpen by remember { mutableStateOf(false) }
                        LiveCalendarDay(app.calendarReader, none, fixedNow, true, 64f,
                            { height -> scope.launch { app.settings.setHourHeight(height) } },
                            { link -> app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }, settings = app.settings,
                            mutations = app.calendarWriter, seriesMutations = app.calendarSeriesWriter, drafts = app.calendarLocalStore,
                            search = app.calendarSearch, meetings = app.calendarMeetings, requestMeetingWritePermission = { true },
                            operationRetirement = app.operationRetirement, recurringCreationSupported = true, onOpenReminders = { remindersOpen = true })
                        if (remindersOpen) ReminderSettingsWorkflow(app) { remindersOpen = false }
                    }
                }
            }
        }
        try {
            shownTag("calendar_phone_month"); expand()
            date(LocalDate.of(2026, 11, 18)).assertIsSelected()
            compose.onAllNodesWithTag("calendar_phone_toolbar").assertCountEquals(1)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("src/test/screenshots/$path")
            drawer()
            views.forEach { compose.onNodeWithTag("calendar_drawer_view_$it").assertExists() }
            assertEquals(0, provider.writeAttempts)
        } finally { compose.runOnUiThread { activity.pause().stop().destroy() }; provider.close() }
    }

    private fun expand(context: String = "calendar navigation") {
        shownTag("calendar_phone_month", "$context: month control")
        compose.onNodeWithTag("calendar_phone_month").performClick()
        shownTag("calendar_month_navigator", "$context: expanded month")
    }
    private fun date(date: LocalDate) = compose.onNodeWithTag("calendar_month_date_${YearMonth.from(date)}_$date")
    private fun choose(date: LocalDate) { date(date).performClick(); compose.waitForIdle(); compose.onNodeWithTag("calendar_month_navigator").assertDoesNotExist() }
    private fun drawer() {
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        shownTag("calendar_drawer_views", "navigation drawer opened before selecting a view")
    }
    private fun displayedTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 &&
        compose.onNodeWithTag(tag).isDisplayed()
    private fun shownTag(tag: String, context: String = tag) = compose.waitUntil("Displayed $tag: $context", 15_000) {
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(16) else compose.waitForIdle()
        displayedTag(tag)
    }
    private fun collapseBack(activity: ActivityController<MainActivity>) { compose.runOnUiThread { activity.get().onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun awaitView(f: Fixture, view: String) {
        // The DataStore commit can precede both recomposition and the drawer close animation.
        compose.waitUntil("View $view persisted, drawer closed and phone toolbar displayed", 15_000) {
            compose.waitForIdle()
            runBlocking { f.app.settings.calendarView.first() } == view &&
                !displayedTag("calendar_drawer_views") && displayedTag("calendar_phone_toolbar")
        }
    }
    private fun assertNone(f: Fixture) {
        assertEquals(none, runBlocking { f.app.settings.calendarSelection.first() })
        assertEquals(0, f.provider.writeAttempts)
        assertTrue(runBlocking { f.app.calendarLocalStore.list() }.isEmpty())
        f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    private class Fixture(val app: CalendarApp, val provider: ReminderSettingsJourneyProvider, val activity: ActivityController<MainActivity>)
    private fun prepare(theme: SettingsTheme = SettingsTheme.LIGHT, eventDate: LocalDate = LocalDate.now(zone)): Pair<CalendarApp, ReminderSettingsJourneyProvider> {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = ReminderSettingsJourneyProvider(eventDate.atTime(9, 0).atZone(zone).toInstant(), "Synthetic phone appointment")
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, theme = theme,
                displayZone = DisplayZoneOption.Fixed(zone), firstWeekday = FirstWeekdayOption.MONDAY))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(none, metadata.value))
        }
        return app to provider
    }
    private fun mainFixture(block: (Fixture) -> Unit) {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))
        try {
            val (app, provider) = prepare()
            var activity: ActivityController<MainActivity>? = null
            try {
                compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
                shownTag("calendar_phone_month"); block(Fixture(app, provider, requireNotNull(activity)))
            } finally {
                activity?.let { controller -> compose.runOnUiThread { controller.pause().stop().destroy() } }
                provider.close()
            }
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }
}
