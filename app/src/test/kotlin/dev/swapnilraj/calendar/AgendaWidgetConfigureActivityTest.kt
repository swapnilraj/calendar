package dev.swapnilraj.calendar

import android.Manifest
import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class WidgetConfigurationTestApplication : Application(), AgendaWidgetApplicationOwner {
    override lateinit var agendaWidgetServices: AgendaWidgetApplicationServices
    override val agendaWidgetRuntime get() = agendaWidgetServices.runtime
    override val agendaWidgetFramework get() = agendaWidgetServices.framework
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WidgetConfigurationTestApplication::class, qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgendaWidgetConfigureActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = (RuntimeEnvironment.getApplication() as WidgetConfigurationTestApplication)
    private class Markers : WidgetMarkerAccess {
        val values = mutableMapOf<Int, String>()
        override fun isOwnWidget(id: Int) = id == 41
        override fun marker(id: Int) = values[id]
        override fun marker(id: Int, value: String) { values[id] = value }
    }
    private fun services(scope: CoroutineScope, markers: Markers,
        metadata: MutableStateFlow<CalendarReadState<List<CalendarInfo>>> = MutableStateFlow(CalendarReadState.Ready(emptyList())),
        beforeObserve: suspend () -> Unit = {},
        requireEnvironmentPermission: Boolean = false,
        observeChanged: (Boolean) -> Unit = {}): AgendaWidgetApplicationServices {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.noBackupFilesDir.deleteRecursively(); app.noBackupFilesDir.mkdirs()
        return AgendaWidgetApplicationServices(app, scope,
            AgendaWidgetSnapshotReader { CalendarReadState.Ready(CalendarSnapshot(it, emptyList(), emptyList())) },
            { flow {
                beforeObserve(); observeChanged(true)
                try { emitAll(metadata) } finally { observeChanged(false) }
            } }, { metadata.value },
            {
                if (requireEnvironmentPermission && app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    CalendarReadState.PermissionRequired
                else CalendarReadState.Ready(AgendaWidgetEnvironment(CalendarSelection.ProviderVisible, ZoneOffset.UTC, true, true))
            },
            ComponentName(app, MainActivity::class.java), object : AgendaWidgetHost {
                override fun widgetIds() = setOf(41)
                override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
                override fun publish(widgetId: Int, views: RemoteViews) = Unit
            }, markers)
    }
    private fun open(): ActivityController<AgendaWidgetConfigureActivity> {
        lateinit var controller: ActivityController<AgendaWidgetConfigureActivity>
        compose.runOnUiThread {
            controller = Robolectric.buildActivity(AgendaWidgetConfigureActivity::class.java,
                Intent(app, AgendaWidgetConfigureActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 41)).setup().visible()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("widget_setup_save").fetchSemanticsNodes().isNotEmpty() }
        return controller
    }
    @Test fun cancelReturnsCanceledAndDoesNotMintBindingOrSaveChoices() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val markers = Markers()
        val services = services(scope, markers); app.agendaWidgetServices = services
        val activity = open()
        try {
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity.get()).resultCode)
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil { activity.get().isFinishing }
            assertTrue(markers.values.isEmpty())
            assertEquals(AgendaWidgetConfigurationState.SetupRequired, runBlocking { services.configuration(41) })
        } finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            runBlocking { services.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
    @Test fun stoppedThenRevokedPermissionScrubsOldNamesBeforeDelayedMetadataReturns() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val markers = Markers()
        val calendar = CalendarInfo(CalendarId(1), "Sensitive synthetic calendar", 123,
            CalendarAccount("sensitive@example.invalid", "local"), null, null, 700, true, true)
        val metadata = MutableStateFlow<CalendarReadState<List<CalendarInfo>>>(CalendarReadState.Ready(listOf(calendar)))
        var gate: CompletableDeferred<Unit>? = null
        val services = services(scope, markers, metadata, beforeObserve = { gate?.await() }); app.agendaWidgetServices = services
        val activity = open()
        try {
            compose.waitUntil { compose.onAllNodesWithText(calendar.displayName).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { activity.pause().stop(); shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR); gate = CompletableDeferred(); activity.start().resume().visible() }
            compose.waitForIdle()
            compose.onAllNodesWithText(calendar.displayName).assertCountEquals(0)
            compose.onAllNodesWithText(calendar.account.name).assertCountEquals(0)
            assertTrue(markers.values.isEmpty())
        } finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            runBlocking { services.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun knownNamespaceDriftMakesFormStaleRatherThanSavingReplacement() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val markers = Markers()
        val calendar = CalendarInfo(CalendarId(1), "Original synthetic", 123,
            CalendarAccount("original@example.invalid", "local"), null, null, 700, true, true)
        val metadata = MutableStateFlow<CalendarReadState<List<CalendarInfo>>>(CalendarReadState.Ready(listOf(calendar)))
        val services = services(scope, markers, metadata); app.agendaWidgetServices = services
        val activity = open()
        try {
            compose.waitUntil { compose.onAllNodesWithText(calendar.displayName).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasTestTag("widget_setup_independent"))
            compose.onNodeWithTag("widget_setup_independent").performClick()
            compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasTestTag("widget_calendar_1"))
            compose.onNodeWithTag("widget_calendar_1").performClick()
            compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasTestTag("widget_privacy_titles"))
            compose.onNodeWithTag("widget_privacy_titles").performClick()
            metadata.value = CalendarReadState.Ready(listOf(calendar.copy(account = CalendarAccount("replacement@example.invalid", "local"))))
            compose.waitUntil { compose.onAllNodesWithText(app.getString(R.string.widget_config_stale)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("widget_setup_save").performClick(); compose.waitForIdle()
            assertFalse(activity.get().isFinishing); assertTrue(markers.values.isEmpty())
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity.get()).resultCode)
            compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasText("Retry"))
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil { compose.onAllNodesWithTag("widget_setup_follow").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasTestTag("widget_setup_follow"))
            compose.onNodeWithTag("widget_setup_follow").assertIsSelected()
            compose.onNodeWithTag("widget_setup_fields").performScrollToNode(hasTestTag("widget_privacy_busy"))
            compose.onNodeWithTag("widget_privacy_busy").assertIsSelected()
        } finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            runBlocking { services.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun unavailableCalendarEnvironmentOffersOnlyExplicitReadPermissionRequest() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val markers = Markers()
        val services = services(scope, markers, requireEnvironmentPermission = true); app.agendaWidgetServices = services
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        lateinit var activity: ActivityController<AgendaWidgetConfigureActivity>
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(AgendaWidgetConfigureActivity::class.java,
                Intent(app, AgendaWidgetConfigureActivity::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 41)).setup().visible()
        }
        try {
            compose.waitUntil { compose.onAllNodesWithText(app.getString(R.string.widget_setup_allow)).fetchSemanticsNodes().isNotEmpty() }
            assertNull(shadowOf(activity.get()).lastRequestedPermission)
            compose.onNodeWithText(app.getString(R.string.widget_setup_allow)).performClick()
            assertArrayEquals(arrayOf(Manifest.permission.READ_CALENDAR), shadowOf(activity.get()).lastRequestedPermission.requestedPermissions)
            assertTrue(markers.values.isEmpty()); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity.get()).resultCode)
        } finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            runBlocking { services.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun retriesAndRepeatedResumeKeepExactlyOneStartedMetadataCollector() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val markers = Markers()
        val metadata = MutableStateFlow<CalendarReadState<List<CalendarInfo>>>(CalendarReadState.Ready(emptyList()))
        var active = 0; var peak = 0; var starts = 0
        val services = services(scope, markers, metadata, observeChanged = { started ->
            if (started) { active++; starts++; peak = maxOf(peak, active) } else active--
        }); app.agendaWidgetServices = services
        val activity = open()
        try {
            compose.waitForIdle(); assertEquals(1, active)
            repeat(2) {
                metadata.value = CalendarReadState.Unavailable
                compose.waitUntil { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Retry").performClick(); compose.waitForIdle()
                assertEquals(1, active)
                compose.runOnUiThread { activity.pause().stop() }; compose.waitForIdle()
                assertEquals(0, active)
                compose.runOnUiThread { activity.restart().start().resume().visible() }; compose.waitForIdle()
                assertEquals(1, active)
            }
            assertEquals(1, peak); assertTrue(starts >= 3); assertTrue(markers.values.isEmpty())
        } finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }; compose.waitForIdle()
            assertEquals(0, active)
            runBlocking { services.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun resultOkOnlyFollowsDurableVerifiedPrivateSaveWithoutNotificationPermission() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val markers = Markers()
        val services = services(scope, markers); app.agendaWidgetServices = services
        val activity = open()
        try {
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity.get()).resultCode)
            compose.onNodeWithTag("widget_setup_save").performClick()
            compose.waitUntil(20_000) { activity.get().isFinishing || compose.onAllNodesWithText(app.getString(R.string.widget_config_save_failed)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Save did not finish with a durable acknowledgment", activity.get().isFinishing)
            assertEquals(Activity.RESULT_OK, shadowOf(activity.get()).resultCode)
            assertEquals(41, shadowOf(activity.get()).resultIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            val saved = runBlocking { services.configuration(41) } as AgendaWidgetConfigurationState.Ready
            assertEquals(WidgetPrivacy.BUSY_ONLY, saved.value.privacy)
            assertEquals(WidgetCalendarSelection.FollowApp, saved.value.selection)
            assertTrue(markers.values.getValue(41).contains(saved.epoch.token))
        } finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            runBlocking { services.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
}
