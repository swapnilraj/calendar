package dev.swapnilraj.calendar

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.widgets.WidgetSetupChoice
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** File-backed app services with an explicit synthetic host-marker boundary, not OS host proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgendaWidgetApplicationServicesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val calendar = CalendarInfo(CalendarId(2), "Synthetic", 123, CalendarAccount("synthetic@example.invalid", "local"),
        "synthetic@example.invalid", null, 700, true, true, providerId = "stable-provider")
    private val ref = EventRef(calendar.id, 7, now.plusSeconds(3600), now.plusSeconds(7200))
    private val event = EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic private event", 123)
    private val environment = AgendaWidgetEnvironment(CalendarSelection.Explicit(setOf(calendar.id)), ZoneOffset.UTC, false, true)
    @Before fun cleanSyntheticFiles() {
        context.noBackupFilesDir.deleteRecursively(); context.noBackupFilesDir.mkdirs()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
    }
    private inner class Fixture(bootstrapDispatcher: CoroutineDispatcher = Dispatchers.IO) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var own = mutableSetOf(1)
        val marker = mutableMapOf<Int, String>()
        var metadata = listOf(calendar)
        var env: CalendarReadState<AgendaWidgetEnvironment> = CalendarReadState.Ready(environment)
        var reads = 0
        var instance = event
        var afterRead: (suspend () -> Unit)? = null
        val views = java.util.Collections.synchronizedList(mutableListOf<RemoteViews>())
        val services = AgendaWidgetApplicationServices(context, scope, AgendaWidgetSnapshotReader { request ->
            reads++; val snapshot = CalendarSnapshot(request, metadata, listOf(instance)); afterRead?.invoke(); CalendarReadState.Ready(snapshot)
        }, { flowOf(CalendarReadState.Ready(metadata)) }, { CalendarReadState.Ready(metadata) }, { env },
            ComponentName(context.packageName, "MainActivity"), object : AgendaWidgetHost {
                override fun widgetIds() = own.toSet()
                override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
                override fun publish(widgetId: Int, views: RemoteViews) { this@Fixture.views += views }
            }, object : WidgetMarkerAccess {
                override fun isOwnWidget(id: Int) = id in own
                override fun marker(id: Int) = marker[id]
                override fun marker(id: Int, value: String) { marker[id] = value }
            }, Clock.fixed(now, ZoneOffset.UTC), bootstrapDispatcher)
        suspend fun configure(privacy: WidgetPrivacy = WidgetPrivacy.BUSY_ONLY): AgendaWidgetConfiguration {
            val result = services.configure(1, WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(calendar.id)), privacy), listOf(calendar))
            return (result as WidgetConfigurationSaveResult.Saved).configuration
        }
        suspend fun close() { services.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        fun text(): String {
            fun collect(view: View): String = buildString {
                if (view is TextView) append(view.text)
                if (view is ViewGroup) for (i in 0 until view.childCount) append(collect(view.getChildAt(i)))
            }
            return collect(views.last().apply(context, FrameLayout(context)))
        }
    }

    @Test fun configureRenderExactClickRefreshAndRemoveUseRealDurableBindings() = runBlocking {
        val f = Fixture()
        try {
            val config = f.configure()
            assertEquals(WidgetPrivacy.BUSY_ONLY, config.privacy)
            assertEquals(mapOf(calendar.id to calendar.identity), config.expectedCalendarIdentities)
            assertTrue(f.services.runtime.refresh()); assertTrue(f.text().contains("Busy")); assertFalse(f.text().contains(event.title))
            val route = AgendaWidgetRoute.OpenEvent(1, config.generation, ref, now.atZone(ZoneOffset.UTC).toLocalDate(), widgetCalendarFingerprint(calendar.identity))
            assertEquals(AgendaWidgetRouteResult.Ready(route, environment), f.services.validateRoute(route))
            assertEquals(AgendaWidgetRouteResult.Stale, f.services.validateRoute(route.copy(ref = ref.copy(eventId = 99))))
            f.services.runtime.removed(setOf(1)).join()
            assertEquals(AgendaWidgetRouteResult.SetupRequired(1), f.services.validateRoute(route))
            assertTrue(f.services.runtime.refresh()); assertFalse(f.text().contains(event.title))
        } finally { f.close() }
    }

    @Test fun namespaceChangedAfterSelectionOrGenerationChangedAfterLookupNeverAdopts() = runBlocking {
        val f = Fixture()
        try {
            f.metadata = listOf(calendar.copy(account = CalendarAccount("replacement@example.invalid", "local")))
            assertEquals(WidgetConfigurationSaveResult.Stale, f.services.configure(1,
                WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(calendar.id)), WidgetPrivacy.EVENT_TITLES), listOf(calendar)))
            assertTrue(f.marker.isEmpty())
            f.metadata = listOf(calendar)
            val first = f.configure(WidgetPrivacy.EVENT_TITLES)
            assertTrue(f.services.runtime.refresh()); assertTrue(f.text().contains(event.title))
            f.afterRead = { f.services.runtime.removed(setOf(1)).join() }
            val route = AgendaWidgetRoute.OpenEvent(1, first.generation, ref, now.atZone(ZoneOffset.UTC).toLocalDate(), widgetCalendarFingerprint(calendar.identity))
            assertEquals(AgendaWidgetRouteResult.Stale, f.services.validateRoute(route))
        } finally { f.close() }
    }

    @Test fun lostRebootMarkerIsSetupWithoutCalendarReadAndExplicitReconfigureRestores() = runBlocking {
        val f = Fixture()
        try {
            val first = f.configure(WidgetPrivacy.EVENT_TITLES)
            f.services.runtime.refresh(); val before = f.reads
            f.marker.clear() // Public custom options may be absent after a system reboot.
            assertTrue(f.services.runtime.refresh()); assertEquals(before, f.reads); assertFalse(f.text().contains(event.title))
            assertEquals(AgendaWidgetRouteResult.SetupRequired(1), f.services.validateRoute(AgendaWidgetRoute.Today(1, first.generation)))
            val fresh = f.configure()
            assertNotEquals(first.generation, fresh.generation); assertEquals(WidgetPrivacy.BUSY_ONLY, fresh.privacy)
            f.services.runtime.refresh(); assertTrue(f.text().contains("Busy"))
        } finally { f.close() }
    }

    @Test fun unavailableEnvironmentNeverDefaultsAndUnknownWidgetNeverWrites() = runBlocking {
        val f = Fixture()
        try {
            val config = f.configure()
            f.env = CalendarReadState.Unavailable
            assertFalse(f.services.runtime.refresh()); assertEquals(0, f.reads)
            assertEquals(AgendaWidgetRouteResult.Unavailable, f.services.validateRoute(AgendaWidgetRoute.Today(1, config.generation)))
            assertEquals(AgendaWidgetRouteResult.Invalid, f.services.validateRoute(AgendaWidgetRoute.Setup(99)))
            assertEquals(WidgetConfigurationSaveResult.Stale, f.services.configure(99, WidgetSetupChoice(), null))
        } finally { f.close() }
    }

    @Test fun missingAccountNamespaceAndFreeBusyTitleModeFailClosed() = runBlocking {
        val f = Fixture()
        try {
            for (account in listOf(CalendarAccount("", "local"), CalendarAccount("name", ""))) {
                val incomplete = calendar.copy(account = account)
                f.metadata = listOf(incomplete)
                assertEquals(WidgetConfigurationSaveResult.Unavailable, f.services.configure(1,
                    WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(calendar.id)), WidgetPrivacy.EVENT_TITLES), listOf(incomplete)))
                assertTrue(f.marker.isEmpty())
            }
            f.metadata = listOf(calendar.copy(accessLevel = 100))
            val result = f.services.configure(1, WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(calendar.id)), WidgetPrivacy.EVENT_TITLES), f.metadata)
                as WidgetConfigurationSaveResult.Saved
            f.services.runtime.refresh(); assertTrue(f.text().contains("Busy")); assertFalse(f.text().contains(event.title))
            assertEquals(AgendaWidgetRouteResult.Unavailable, f.services.validateRoute(AgendaWidgetRoute.OpenEvent(1,
                result.configuration.generation, ref, now.atZone(ZoneOffset.UTC).toLocalDate(), widgetCalendarFingerprint(calendar.identity))))
        } finally { f.close() }
    }

    @Test fun changedZoneCannotReuseTheOldWidgetRowDate() = runBlocking {
        val f = Fixture()
        try {
            val time = now.plusSeconds(14 * 3600 + 1800)
            val nextRef = ref.copy(instanceStart = time, instanceEnd = time.plusSeconds(3600))
            f.instance = event.copy(ref = nextRef, time = EventTime.Timed(nextRef.instanceStart, nextRef.instanceEnd))
            f.env = CalendarReadState.Ready(environment.copy(displayZone = ZoneId.of("Asia/Tokyo")))
            val config = f.configure()
            val route = AgendaWidgetRoute.OpenEvent(1, config.generation, nextRef, LocalDate.of(2026, 11, 19), widgetCalendarFingerprint(calendar.identity))
            assertTrue(f.services.validateRoute(route) is AgendaWidgetRouteResult.Ready)
            f.env = CalendarReadState.Ready(environment.copy(displayZone = ZoneId.of("Europe/London")))
            assertEquals(AgendaWidgetRouteResult.Stale, f.services.validateRoute(route))
        } finally { f.close() }
    }

    @Test @OptIn(ExperimentalCoroutinesApi::class)
    fun coldRemovalFencesOnDiskBeforeBootstrapAndSurvivesAnotherProcessRestart() = runTest {
        val previous = Fixture()
        previous.configure(WidgetPrivacy.EVENT_TITLES)
        previous.close()
        val retainedMarkers = previous.marker.toMutableMap()
        var reads = 0
        fun services(scope: CoroutineScope, dispatcher: CoroutineDispatcher) = AgendaWidgetApplicationServices(context, scope,
            AgendaWidgetSnapshotReader { reads++; CalendarReadState.Ready(CalendarSnapshot(it, listOf(calendar), listOf(event))) },
            { flowOf(CalendarReadState.Ready(listOf(calendar))) }, { CalendarReadState.Ready(listOf(calendar)) },
            { CalendarReadState.Ready(environment) }, ComponentName(context.packageName, "MainActivity"), object : AgendaWidgetHost {
                override fun widgetIds() = setOf(1)
                override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
                override fun publish(widgetId: Int, views: RemoteViews) = Unit
            }, object : WidgetMarkerAccess {
                override fun isOwnWidget(id: Int) = id == 1
                override fun marker(id: Int) = retainedMarkers[id]
                override fun marker(id: Int, value: String) { retainedMarkers[id] = value }
            }, Clock.fixed(now, ZoneOffset.UTC), dispatcher)
        val coldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cold = services(coldScope, StandardTestDispatcher(testScheduler))
        cold.runtime.removed(setOf(1)) // No bootstrap task has run, but the fence is synchronous.
        val disk = requireNotNull(WidgetBindingLedger.open(context.noBackupFilesDir)).snapshot()
        assertEquals(WidgetBindingPhase.QUARANTINED, disk.records.getValue(1).phase)
        assertFalse(retainedMarkers.getValue(1).contains(disk.records.getValue(1).epoch.token))
        coldScope.cancel(); runCurrent(); coldScope.coroutineContext[Job]!!.join()
        val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restarted = services(restartedScope, Dispatchers.IO)
        try {
            assertEquals(AgendaWidgetConfigurationState.SetupRequired, restarted.configuration(1))
            assertTrue(restarted.runtime.refresh()); assertEquals(0, reads)
        } finally { restarted.closeAndJoin(); restartedScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun retainedFollowAppClickCannotRebindToAReplacementCalendarNamespace() = runBlocking {
        val f = Fixture()
        try {
            val saved = f.services.configure(1, WidgetSetupChoice(), null) as WidgetConfigurationSaveResult.Saved
            val route = AgendaWidgetRoute.OpenEvent(1, saved.configuration.generation, ref,
                now.atZone(ZoneOffset.UTC).toLocalDate(), widgetCalendarFingerprint(calendar.identity))
            assertTrue(f.services.validateRoute(route) is AgendaWidgetRouteResult.Ready)
            f.metadata = listOf(calendar.copy(account = CalendarAccount("replacement@example.invalid", "local")))
            assertEquals(AgendaWidgetRouteResult.Stale, f.services.validateRoute(route))
            val fresh = route.copy(calendarFingerprint = widgetCalendarFingerprint(f.metadata.single().identity))
            assertTrue(f.services.validateRoute(fresh) is AgendaWidgetRouteResult.Ready)
            val reads = f.reads
            assertEquals(AgendaWidgetRouteResult.Stale, f.services.validateRoute(route.copy(calendarFingerprint = null)))
            assertEquals(reads, f.reads)
            val activity = ComponentName(context.packageName, "MainActivity")
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            val oldPending = android.app.PendingIntent.getActivity(context, 0, AgendaWidgetIntents.intent(context, activity, route), flags)
            val freshPending = android.app.PendingIntent.getActivity(context, 0, AgendaWidgetIntents.intent(context, activity, fresh), flags)
            assertNotEquals(oldPending, freshPending)
            assertEquals(route, AgendaWidgetIntents.decode(context, shadowOf(oldPending).savedIntent, activity))
        } finally { f.close() }
    }

    @Test @OptIn(ExperimentalCoroutinesApi::class)
    fun acceptedColdRemovalRegistersBeforeFreshConfigureAfterBootstrap() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val removed = f.services.runtime.removed(setOf(1))
            val firstEpoch = requireNotNull(WidgetBindingLedger.open(context.noBackupFilesDir)).snapshot().records.getValue(1).epoch
            runCurrent()
            val saved = f.configure()
            removed.join()
            val state = f.services.configuration(1) as AgendaWidgetConfigurationState.Ready
            assertEquals(saved, state.value); assertNotEquals(firstEpoch, state.epoch)
            assertTrue(f.marker.getValue(1).contains(state.epoch.token))
            assertTrue(f.services.runtime.refresh())
        } finally { f.close() }
    }

    @Test fun reconfigureCannotDowngradeKnownNamespaceFieldsDuringMissingMetadata() = runBlocking {
        val f = Fixture()
        try {
            val saved = f.configure(WidgetPrivacy.EVENT_TITLES)
            f.metadata = listOf(calendar.copy(owner = null, providerId = null))
            val result = f.services.configure(1, WidgetSetupChoice(WidgetCalendarSelection.Explicit(setOf(calendar.id)), WidgetPrivacy.EVENT_TITLES), f.metadata)
            assertEquals(WidgetConfigurationSaveResult.Stale, result)
            assertEquals(saved, (f.services.configuration(1) as AgendaWidgetConfigurationState.Ready).value)
        } finally { f.close() }
    }

    @Test fun configureLaunchDecodesOnlyExactActionsTypedPositiveIds() {
        val base = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 1)
        assertEquals(1, AgendaWidgetConfigureActivity.decodeWidgetId(base))
        assertEquals(1, AgendaWidgetConfigureActivity.decodeWidgetId(Intent(base).setAction(AgendaWidgetConfigureActivity.ACTION_RECONFIGURE)))
        for (value in listOf(Intent(base).setAction(Intent.ACTION_VIEW), Intent(base).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, "1"),
            Intent(base).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0), Intent(base).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)))
            assertNull(AgendaWidgetConfigureActivity.decodeWidgetId(value))
    }
}
