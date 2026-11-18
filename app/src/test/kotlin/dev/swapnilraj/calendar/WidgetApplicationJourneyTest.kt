package dev.swapnilraj.calendar

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferenceState
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowToast

/** Actual CalendarApp owner, Android host shadow, configuration UI, durable bindings, real reader
 * and private details. Host/device lifecycle is synthetic; SQLite has one explicit Instances row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetApplicationJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic widget private appointment"

    @Test fun configureRenderRowTapOpensFreshPrivateDetailsAndStoppedNamespaceChangeScrubs() = fixture { f ->
        val config = f.configure(capture = true)
        assertEquals(WidgetPrivacy.EVENT_TITLES, config.privacy)
        assertEquals(setOf(CalendarId(3)), config.expectedCalendarIdentities.keys)
        assertTrue(text(f.widget()).contains(title))
        val click = f.click(R.id.widget_row)
        val route = AgendaWidgetIntents.decode(f.app, click, ComponentName(f.app, MainActivity::class.java)) as AgendaWidgetRoute.OpenEvent
        assertEquals(config.generation, route.generation)
        assertNotNull(route.calendarFingerprint)
        f.provider.setEvent("description", "Fresh widget context https://meet.google.com/aaa-bbbb-ccc")
        f.launchMain(click)
        val destination = f.nextActivity()
        assertEquals(ComponentName(f.app, WidgetOpenActivity::class.java), destination.component)
        assertFalse(f.app.packageManager.getActivityInfo(destination.component!!, 0).exported)
        compose.runOnUiThread { f.details = Robolectric.buildActivity(WidgetOpenActivity::class.java, destination).setup().visible() }
        shown(title)
        // Stable title subtree only: the real sheet also contains wall-clock-relative labels.
        compose.onNodeWithText(title).captureToImage().asAndroidBitmap()
            .captureRoboImage("src/test/screenshots/integration_widget_private_details_title.png")
        compose.onNodeWithText("Join meeting").performScrollTo().performClick()
        assertEquals("https://meet.google.com/aaa-bbbb-ccc", shadowOf(f.details!!.get()).nextStartedActivity.dataString)
        compose.onNodeWithText("Edit event").assertDoesNotExist()
        f.assertNoneAndNoWrites()
        compose.runOnUiThread { f.details!!.pause().stop() }
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
        f.provider.replaceNamespace(); f.provider.detailReads = 0
        compose.runOnUiThread { f.details!!.restart().start().resume() }
        shown(f.app.getString(R.string.events_unavailable))
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(0, f.provider.detailReads)
        f.assertNoneAndNoWrites()
    }

    @Test fun oldRenderedGenerationCannotOpenEventAfterDurableReconfiguration() = fixture { f ->
        val before = f.configure()
        val oldClick = f.click(R.id.widget_row)
        val after = f.configure()
        assertNotEquals(before.generation, after.generation)
        f.provider.detailReads = 0
        f.launchMain(oldClick)
        compose.waitUntil(15_000) { compose.waitForIdle(); ShadowToast.getTextOfLatestToast() == f.app.getString(R.string.navigation_unavailable) }
        assertNull(shadowOf(f.main!!.get()).nextStartedActivity)
        assertEquals(0, f.provider.detailReads)
        compose.onNodeWithText(title).assertDoesNotExist()
        f.assertNoneAndNoWrites()
    }

    @Test fun widgetCreateOpensDurableUnsavedDraftAndCannotReplaceOpenEditorOrReplayOnRecreation() = fixture { f ->
        f.configure()
        val create = f.click(R.id.widget_create)
        f.launchMain(create)
        shown("Save")
        compose.onNode(hasSetTextAction() and hasText("Title")).performTextReplacement("Retained widget draft")
        compose.waitUntil(15_000) { runBlocking { f.app.calendarLocalStore.list() }.any { it.payload.contains("Retained widget draft") } }
        val before = runBlocking { f.app.calendarLocalStore.list() }.associate { it.id to it.payload }
        assertEquals(1, before.size)
        compose.runOnUiThread { f.main!!.newIntent(create) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("Retained widget draft")).assertExists()
        assertEquals(before, runBlocking { f.app.calendarLocalStore.list() }.associate { it.id to it.payload })
        compose.runOnUiThread { f.main!!.recreate() }
        shown("Retained widget draft")
        assertEquals(before.keys, runBlocking { f.app.calendarLocalStore.list() }.map { it.id }.toSet())
        f.assertNoneAndNoWrites()
    }

    @Test fun widgetMoreSelectsAgendaWithoutChangingDisplayFiltersOrCreatingDraftOnRecreation() = fixture { f ->
        f.configure()
        val before = runBlocking { f.app.calendarLocalStore.list() }.associate { it.id to it.payload }
        f.launchMain(f.click(R.id.widget_more))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("agenda_list").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("AGENDA", runBlocking { f.app.settings.calendarPreferences.first { it is CalendarPreferenceState.Ready } }.let { (it as CalendarPreferenceState.Ready).value.view })
        f.assertNoneAndNoWrites()
        compose.runOnUiThread { f.main!!.recreate() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("agenda_list").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, runBlocking { f.app.calendarLocalStore.list() }.associate { it.id to it.payload })
        f.assertNoneAndNoWrites()
    }

    @Test fun startedPrivateDetailsClearsAfterReconfigurationAndRemovalWithoutProviderChange() = fixture { f ->
        f.configure()
        f.launchMain(f.click(R.id.widget_row))
        val initial = f.nextActivity()
        compose.runOnUiThread { f.details = Robolectric.buildActivity(WidgetOpenActivity::class.java, initial).setup().visible() }
        shown(title)
        f.configure() // Do not stop the old details owner or notify the provider.
        shown(f.app.getString(R.string.events_unavailable))
        compose.onNodeWithText(title).assertDoesNotExist()
        val fresh = f.click(R.id.widget_row)
        compose.runOnUiThread { f.main!!.newIntent(fresh) }
        val reopened = f.nextActivity()
        compose.runOnUiThread { f.details!!.newIntent(reopened) }
        shown(title)
        runBlocking { f.app.agendaWidgetRuntime.removed(setOf(f.id)).join() }
        shown(f.app.getString(R.string.events_unavailable))
        compose.onNodeWithText(title).assertDoesNotExist()
        f.assertNoneAndNoWrites()
    }

    @Test fun actualAppMissingReadPreservesPermissionStateAndConfigurationRequiresExplicitGrant() = fixture { f ->
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR)
        assertEquals(CalendarReadState.PermissionRequired, runBlocking { f.app.agendaWidgetServices.environment() })
        val intent = Intent(f.app, AgendaWidgetConfigureActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, f.id)
        compose.runOnUiThread { f.configuration = Robolectric.buildActivity(AgendaWidgetConfigureActivity::class.java, intent).setup().visible() }
        shown(f.app.getString(R.string.widget_setup_allow))
        assertNull(shadowOf(f.configuration!!.get()).lastRequestedPermission)
        compose.onNodeWithText(f.app.getString(R.string.widget_setup_allow)).performClick()
        assertArrayEquals(arrayOf(Manifest.permission.READ_CALENDAR), shadowOf(f.configuration!!.get()).lastRequestedPermission.requestedPermissions)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(f.configuration!!.get()).resultCode)
        assertFalse(f.configuration!!.get().isFinishing)
        shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        compose.runOnUiThread { f.configuration!!.pause().stop().restart().start().resume() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("widget_setup_independent").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(AgendaWidgetConfigurationState.SetupRequired, runBlocking { f.app.agendaWidgetServices.configuration(f.id) })
        f.assertNoneAndNoWrites()
    }

    private fun shown(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun field(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun text(view: View): String = buildString {
        if (view is TextView) append(view.text)
        if (view is ViewGroup) repeat(view.childCount) { append(text(view.getChildAt(it))) }
    }
    private inner class Fixture(val app: CalendarApp, val provider: ReminderSettingsJourneyProvider, val manager: AppWidgetManager, val id: Int) {
        var configuration: ActivityController<AgendaWidgetConfigureActivity>? = null
        var main: ActivityController<MainActivity>? = null
        var details: ActivityController<WidgetOpenActivity>? = null
        fun configure(capture: Boolean = false): AgendaWidgetConfiguration {
            val intent = Intent(app, AgendaWidgetConfigureActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            compose.runOnUiThread { configuration = Robolectric.buildActivity(AgendaWidgetConfigureActivity::class.java, intent).setup().visible() }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("widget_setup_independent").fetchSemanticsNodes().isNotEmpty() }
            if (capture) compose.onNodeWithText(app.getString(R.string.widget_setup_title)).captureToImage().asAndroidBitmap()
                .captureRoboImage("src/test/screenshots/integration_widget_configuration_heading.png")
            field("widget_setup_independent").performClick()
            val calendar = field("widget_calendar_3")
            if (calendar.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] != ToggleableState.On) calendar.performClick()
            field("widget_privacy_titles").performClick()
            compose.onNodeWithTag("widget_setup_save").performClick()
            compose.waitUntil(15_000) {
                val failed = compose.onAllNodesWithText(app.getString(R.string.widget_config_save_failed)).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(app.getString(R.string.widget_config_stale)).fetchSemanticsNodes().isNotEmpty()
                configuration!!.get().isFinishing || failed
            }
            assertTrue("Save must finish only after durable acknowledgment", configuration!!.get().isFinishing)
            assertEquals(Activity.RESULT_OK, shadowOf(configuration!!.get()).resultCode)
            assertEquals(id, shadowOf(configuration!!.get()).resultIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            val saved = runBlocking { app.agendaWidgetServices.configuration(id) } as AgendaWidgetConfigurationState.Ready
            assertTrue(saved.epoch.token.isNotBlank())
            compose.runOnUiThread { configuration!!.pause().stop().destroy() }; configuration = null
            assertTrue(runBlocking { app.agendaWidgetRuntime.refresh() })
            assertNoneAndNoWrites()
            return saved.value
        }
        fun widget(): View = shadowOf(manager).getViewFor(id)
        fun click(viewId: Int): Intent {
            compose.runOnUiThread { assertTrue(widget().findViewById<View>(viewId).performClick()) }
            return requireNotNull(shadowOf(app).nextStartedActivity)
        }
        fun launchMain(intent: Intent) { compose.runOnUiThread { main = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible() } }
        fun nextActivity(): Intent {
            var result: Intent? = null
            compose.waitUntil(15_000) { compose.waitForIdle(); result = shadowOf(main!!.get()).nextStartedActivity; result != null }
            return requireNotNull(result)
        }
        fun assertNoneAndNoWrites() {
            val state = runBlocking { app.settings.calendarPreferences.first { it is CalendarPreferenceState.Ready } } as CalendarPreferenceState.Ready
            assertEquals(CalendarSelection.Explicit(emptySet()), state.value.selection)
            assertEquals(0, provider.writeAttempts)
        }
        fun close() {
            compose.runOnUiThread {
                details?.pause()?.stop()?.destroy()
                main?.pause()?.stop()?.destroy()
                configuration?.pause()?.stop()?.destroy()
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun fixture(block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val provider = ReminderSettingsJourneyProvider(Instant.now().plusSeconds(3600), title)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false))
            val metadata = (app.calendarReader.readCalendars() as CalendarReadState.Ready).value
            val prior = metadata.single().copy(id = CalendarId(999), account = CalendarAccount("prior-fixture@example.invalid", "fixture"))
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(prior.id)), listOf(prior)))
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(emptySet()), metadata))
        }
        val manager = AppWidgetManager.getInstance(app)
        val id = shadowOf(manager).createWidget(AgendaWidgetProvider::class.java, R.layout.widget_agenda)
        shadowOf(manager).putWidgetInfo(id, AppWidgetProviderInfo().apply { this.provider = ComponentName(app, AgendaWidgetProvider::class.java) })
        manager.updateAppWidgetOptions(id, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320); putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 320) })
        val f = Fixture(app, provider, manager, id)
        try { block(f) }
        finally { f.close(); provider.close() }
    }
}
