package dev.swapnilraj.calendar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.RemoteViews
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
class AgendaWidgetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val activity get() = ComponentName(context.packageName, "WidgetActivity")
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val today = LocalDate.of(2026, 11, 18)
    private val config = AgendaWidgetConfiguration("configuration-generation")
    private fun calendar(id: Long) = CalendarInfo(CalendarId(id), "Synthetic calendar", 123,
        CalendarAccount("synthetic@example.invalid", "local"), null, null, 700, true, true)
    private val epoch = WidgetEpoch(1, "verified-initial-binding")
    private fun ready(value: AgendaWidgetConfiguration) = AgendaWidgetConfigurationState.Ready(value, epoch)
    private val environment = AgendaWidgetEnvironment(CalendarSelection.Explicit(setOf(CalendarId(1))), ZoneOffset.UTC, false, true)
    private fun event(id: Long = 7, calendar: Long = 1, start: Instant = now.plusSeconds(3600), end: Instant = start.plusSeconds(3600),
        title: String = "Synthetic confidential title", declined: Boolean = false) = EventOccurrence(
        EventRef(CalendarId(calendar), id, start, end), EventTime.Timed(start, end), title, 0xff123456.toInt(), declined)
    private fun inflate(views: RemoteViews): View = views.apply(context, FrameLayout(context))
    private fun text(view: View): String = buildString {
        if (view is TextView) append(view.text)
        view.contentDescription?.let { append(it) }
        if (view is ViewGroup) for (i in 0 until view.childCount) append(text(view.getChildAt(i)))
    }
    private inner class Host : AgendaWidgetHost {
        var ids = setOf(1)
        var size = AgendaWidgetSize(240, 240)
        val views = mutableListOf<RemoteViews>()
        override fun widgetIds() = ids
        override fun size(widgetId: Int) = size
        override fun publish(widgetId: Int, views: RemoteViews) { this.views += views }
        fun last() = inflate(views.last())
    }
    private inner class Fixture {
        val host = Host()
        var configuration: AgendaWidgetConfigurationState = ready(config)
        var proof: WidgetLifecycleProof = WidgetLifecycleProof.Verified(epoch)
        var env: CalendarReadState<AgendaWidgetEnvironment> = CalendarReadState.Ready(environment)
        var requested: CalendarRequest? = null
        var reads = 0; var scheduled = 0; var deletes = 0
        var rows = listOf(event())
        var calendars = listOf(calendar(1), calendar(2))
        var failure: CalendarReadState<CalendarSnapshot>? = null
        var duringRead: (suspend () -> Unit)? = null
        var deleteFailure = false
        var clockInstant = now
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = AgendaWidgetRuntime(AgendaWidgetSnapshotReader { request ->
            reads++; requested = request; duringRead?.invoke()
            failure ?: CalendarReadState.Ready(CalendarSnapshot(request, calendars, rows))
        }, object : AgendaWidgetSettings {
            override suspend fun configuration(widgetId: Int) = configuration
            override suspend fun environment() = env
            override suspend fun lifecycle(widgetId: Int) = proof
        }, object : AgendaWidgetConfigurationWriter {
            override fun enqueueRemoval(epoch: WidgetEpoch): Deferred<WidgetRemovalReceipt> {
                deletes++
                if (deleteFailure) return CompletableDeferred(WidgetRemovalReceipt.Unavailable)
                proof = WidgetLifecycleProof.Verified(epoch)
                configuration = AgendaWidgetConfigurationState.SetupRequired
                return CompletableDeferred(WidgetRemovalReceipt.Committed(epoch))
            }
            override fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration): Deferred<WidgetSaveReceipt> = error("Not used")
        }, object : AgendaWidgetRefreshScheduler {
            override fun requestRefresh(widgetIds: Set<Int>): Boolean { scheduled++; return true }
        }, host, AgendaWidgetRenderer(context, activity), scope, object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant() = clockInstant
        }, 50)
        fun close() = scope.cancel()
    }

    @Test fun explicitLegacyOrReplacedNamespacesNeverRenderTitles() = runBlocking {
        val f = Fixture()
        val explicit = config.copy(selection = WidgetCalendarSelection.Explicit(setOf(CalendarId(1))), privacy = WidgetPrivacy.EVENT_TITLES)
        f.configuration = ready(explicit)
        f.runtime.refresh(); assertEquals(0, f.reads); assertFalse(text(f.host.last()).contains("confidential"))
        f.configuration = ready(explicit.copy(expectedCalendarIdentities = mapOf(CalendarId(1) to calendar(1).identity)))
        f.runtime.refresh(); assertTrue(text(f.host.last()).contains("Synthetic confidential title"))
        f.calendars = listOf(calendar(1).copy(account = CalendarAccount("replacement@example.invalid", "local")))
        f.runtime.refresh(); assertFalse(text(f.host.last()).contains("confidential"))
        f.close()
    }

    @Test fun availabilityOnlyAndUnknownAclAlwaysMaskSourceTitlesAndColors() = runBlocking {
        for (access in listOf(100, null)) {
            val f = Fixture(); f.configuration = ready(config.copy(privacy = WidgetPrivacy.EVENT_TITLES))
            f.calendars = listOf(calendar(1).copy(accessLevel = access))
            f.runtime.refresh(); assertFalse(text(f.host.last()).contains("confidential")); assertTrue(text(f.host.last()).contains("Busy"))
            val row = f.host.last().findViewById<LinearLayout>(R.id.widget_rows).getChildAt(0)
            assertEquals(0xff7d857f.toInt(), (row.findViewById<View>(R.id.widget_color).background as android.graphics.drawable.ColorDrawable).color)
            f.close()
        }
    }

    @Test fun busyOnlyIsDefaultAndRenderedTextAccessibilityAndColorRevealNoTitle() = runBlocking {
        val f = Fixture(); f.runtime.refresh()
        val view = f.host.last()
        assertTrue(text(view).contains("Busy"))
        assertFalse(text(view).contains("Synthetic confidential title"))
        val row = view.findViewById<LinearLayout>(R.id.widget_rows).getChildAt(0)
        assertFalse(row.contentDescription.contains("confidential"))
        assertEquals(0xff7d857f.toInt(), (row.findViewById<View>(R.id.widget_color).background as android.graphics.drawable.ColorDrawable).color)
        f.close()
    }

    @Test fun titleModeUsesExplicitChoiceAndPrivacyFailureReapplyRemovesExistingRows() = runBlocking {
        val f = Fixture()
        f.configuration = ready(config.copy(privacy = WidgetPrivacy.EVENT_TITLES))
        f.runtime.refresh()
        val original = f.host.last()
        assertTrue(text(original).contains("Synthetic confidential title"))
        f.configuration = AgendaWidgetConfigurationState.Unavailable
        assertFalse(f.runtime.refresh())
        f.host.views.last().reapply(context, original)
        assertFalse(text(original).contains("Synthetic confidential title"))
        assertEquals(0, original.findViewById<LinearLayout>(R.id.widget_rows).childCount)
        assertTrue(text(original).contains("Agenda unavailable"))
        f.close()
    }

    @Test fun setupAndUnavailablePreferencesNeverQueryOrBecomeDefaultCalendarSelection() = runBlocking {
        val f = Fixture(); f.configuration = AgendaWidgetConfigurationState.SetupRequired
        f.runtime.refresh(); assertEquals(0, f.reads)
        assertTrue(text(f.host.last()).contains("Choose calendars"))
        f.configuration = ready(config)
        f.env = CalendarReadState.Unavailable
        assertFalse(f.runtime.refresh()); assertEquals(0, f.reads)
        f.close()
    }

    @Test fun followAppAndExplicitOverrideAreIndependentAndEmptyNeverBroadens() = runBlocking {
        val f = Fixture(); f.runtime.refresh()
        assertEquals(environment.appSelection, f.requested!!.selection)
        f.configuration = ready(config.copy(selection = WidgetCalendarSelection.Explicit(setOf(CalendarId(2))), expectedCalendarIdentities = mapOf(CalendarId(2) to calendar(2).identity)))
        f.rows = listOf(event(calendar = 2)); f.runtime.refresh()
        assertEquals(CalendarSelection.Explicit(setOf(CalendarId(2))), f.requested!!.selection)
        assertTrue(text(f.host.last()).contains("Busy"))
        f.configuration = ready(config.copy(selection = WidgetCalendarSelection.Explicit(emptySet())))
        val reads = f.reads; f.runtime.refresh()
        assertEquals(reads, f.reads)
        assertTrue(text(f.host.last()).contains("No calendars selected"))
        f.close()
    }

    @Test fun sourcePermissionAndFailureClearStaleTextAndHaveDistinctRecoveryStates() = runBlocking {
        val f = Fixture(); f.runtime.refresh()
        f.failure = CalendarReadState.PermissionRequired
        f.runtime.refresh()
        assertTrue(text(f.host.last()).contains("allow calendar access"))
        assertFalse(text(f.host.last()).contains("Busy"))
        f.failure = CalendarReadState.Unavailable
        assertFalse(f.runtime.refresh())
        assertTrue(text(f.host.last()).contains("Agenda unavailable"))
        f.close()
    }

    @Test fun changedConfigurationOrEnvironmentDuringReadNeverPublishesStaleTitles() = runBlocking {
        val f = Fixture()
        f.configuration = ready(config.copy(privacy = WidgetPrivacy.EVENT_TITLES))
        f.duringRead = { f.configuration = ready(config.copy(generation = "changed")) }
        assertFalse(f.runtime.refresh())
        assertFalse(text(f.host.last()).contains("confidential"))
        assertEquals(1, f.scheduled)
        f.close()
    }

    @Test fun invalidationDuringQueryWinsOverAnOlderSnapshotAndClearsTextImmediately() = runBlocking {
        val f = Fixture()
        f.configuration = ready(config.copy(privacy = WidgetPrivacy.EVENT_TITLES))
        f.runtime.refresh(); assertTrue(text(f.host.last()).contains("confidential"))
        f.duringRead = { f.runtime.invalidate(); assertFalse(text(f.host.last()).contains("confidential")) }
        assertFalse(f.runtime.refresh())
        assertFalse(text(f.host.last()).contains("confidential"))
        f.close()
    }

    @Test fun sevenCalendarDaysUseZoneAndOngoingEventsAllDayDatesAndDeclinesAreHandled() = runBlocking {
        val f = Fixture()
        f.rows = listOf(event(id = 1, start = now.minusSeconds(7200), end = now.plusSeconds(3600)),
            event(id = 2, start = now.minusSeconds(7200), end = now), event(id = 3, declined = true),
            event(id = 4).copy(time = EventTime.AllDay(CalendarDateRange(today.minusDays(1), today.plusDays(2)))))
        f.runtime.refresh()
        assertEquals(CalendarDateRange(today, today.plusDays(7)), f.requested!!.range)
        val view = f.host.last()
        assertEquals(2, view.findViewById<LinearLayout>(R.id.widget_rows).childCount)
        assertTrue(text(view).contains("All day"))
        f.close()
    }

    @Test fun rowCountsAreBoundedAtThreeFiveEightAndMoreAlwaysReachesApp() = runBlocking {
        val f = Fixture(); f.rows = (1L..20L).map { event(id = it) }
        for ((height, expected) in listOf(240 to 3, 336 to 5, 480 to 8)) {
            f.host.size = AgendaWidgetSize(240, height); f.runtime.refresh()
            val view = f.host.last()
            assertEquals(expected, view.findViewById<LinearLayout>(R.id.widget_rows).childCount)
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_more).visibility)
        }
        f.close()
    }

    @Test fun deletedOrRestoredConfigurationRequiresFreshSetupAndFailureNeverInheritsTitles() = runBlocking {
        val f = Fixture(); f.runtime.refresh()
        var finished = 0
        f.runtime.removed(setOf(1)) { finished++ }.join()
        f.runtime.refresh()
        assertEquals(1, f.deletes); assertEquals(1, finished)
        assertTrue(text(f.host.last()).contains("Choose calendars"))
        f.deleteFailure = true
        f.configuration = ready(config.copy(privacy = WidgetPrivacy.EVENT_TITLES))
        f.runtime.removed(setOf(1)) { finished++ }.join()
        assertFalse(f.runtime.refresh())
        assertFalse(text(f.host.last()).contains("confidential"))
        assertEquals(2, finished)
        f.close()
    }

    @Test fun fixedStackClickUsesImmutableCompleteOccurrenceIdentityIncludingRecurrence() = runBlocking {
        val f = Fixture()
        val recurring = event().copy(ref = event().ref.copy(recurrence = RecurrenceRef(99, now.minusSeconds(86400))))
        f.rows = listOf(recurring); f.runtime.refresh()
        val view = f.host.last()
        val row = view.findViewById<LinearLayout>(R.id.widget_rows).getChildAt(0)
        row.performClick()
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        val decoded = AgendaWidgetIntents.decode(context, started, activity) as AgendaWidgetRoute.OpenEvent
        val pending = android.app.PendingIntent.getActivity(context, 0, started,
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(pending)
        assertTrue(shadowOf(pending).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(recurring.ref, decoded.ref)
        assertEquals(widgetCalendarFingerprint(calendar(1).identity), decoded.calendarFingerprint)
        assertEquals(config.generation, decoded.generation)
        assertFalse(started.extras!!.keySet().any { it.contains("title") })
        f.close()
    }

    @Test fun sevenDayWindowUsesCalendarDatesAcrossDstAndMidnightRejectsOldResults() = runBlocking {
        val f = Fixture()
        f.clockInstant = Instant.parse("2026-10-31T23:00:00Z")
        f.env = CalendarReadState.Ready(environment.copy(displayZone = ZoneId.of("America/New_York")))
        f.runtime.refresh()
        val bounds = f.requested!!.range.instantBounds(f.requested!!.displayZone)
        assertEquals(169, Duration.between(bounds.start, bounds.endExclusive).toHours())
        f.duringRead = { f.clockInstant = f.clockInstant.plusSeconds(86400) }
        assertFalse(f.runtime.refresh())
        assertTrue(text(f.host.last()).contains("Agenda unavailable"))
        assertEquals(1, f.scheduled)
        f.close()
    }

    @Test fun timedOutSnapshotAndCancelledRemovalNeverLeavePrivateTextOrUnfinishedCallback() = runBlocking {
        val f = Fixture()
        f.configuration = ready(config.copy(privacy = WidgetPrivacy.EVENT_TITLES))
        f.runtime.refresh()
        f.duringRead = { awaitCancellation() }
        assertFalse(f.runtime.refresh())
        assertFalse(text(f.host.last()).contains("confidential"))
        f.close()
        var finished = 0
        f.runtime.removed(setOf(1)) { finished++ }.join()
        assertEquals(1, finished)
    }

    @Test fun widgetClickDecoderRejectsSpoofsMalformedTypesAndTamperedIdentityWithoutCrash() {
        val route = AgendaWidgetRoute.OpenEvent(1, "generation", event().ref, today, widgetCalendarFingerprint(calendar(1).identity))
        val valid = AgendaWidgetIntents.intent(context, activity, route)
        assertEquals(route, AgendaWidgetIntents.decode(context, valid, activity))
        assertNull(AgendaWidgetIntents.decode(context, Intent(valid).setData(android.net.Uri.parse("https://example.com")), activity))
        assertNull(AgendaWidgetIntents.decode(context, Intent(valid).putExtra("agenda_widget.event", "7"), activity))
        assertNull(AgendaWidgetIntents.decode(context, Intent(valid).putExtra("agenda_widget.calendar_fingerprint", 1), activity))
        assertNull(AgendaWidgetIntents.decode(context, Intent(valid).putExtra("agenda_widget.calendar_fingerprint", "different"), activity))
        assertNull(AgendaWidgetIntents.decode(context, Intent(valid).putExtra("agenda_widget.generation", "different"), activity))
        assertNull(AgendaWidgetIntents.decode(context, Intent(valid).setComponent(ComponentName("outside", "Activity")), activity))
        assertNull(AgendaWidgetIntents.decode(context, Intent(valid).setData(valid.data!!.buildUpon().appendPath("extra").build()), activity))
        assertNull(AgendaWidgetIntents.decode(context, null, activity))
    }
}
