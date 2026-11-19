package dev.swapnilraj.calendar

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
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

class CalendarEntryTestApplication : Application(), CalendarEntryOwner {
    override lateinit var calendarEntryServices: CalendarEntryServices
    override val calendarEntryContent = CalendarEntryContent { state, actions -> DaylightTheme { BasicCalendarEntryScreen(state, actions) } }
    val commands = mutableListOf<CalendarEntryReviewCommand>()
    override val calendarEntryReviewHandler = CalendarEntryReviewHandler { _, command -> commands += command; true }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CalendarEntryTestApplication::class, qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarEntryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as CalendarEntryTestApplication
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val calendar = CalendarInfo(CalendarId(2), "Synthetic", 123, CalendarAccount("synthetic@example.invalid", "local"), "owner", null, 700, true, true, providerId = "calendar-A")
    private val ref = EventRef(calendar.id, 7, start, start.plusSeconds(3600))
    private inner class Source : IncomingCalendarSource {
        var info = EventNavigationInfo(7, calendar.identity, "event-A", false, ref.instanceStart, ref.instanceEnd, false)
        val reads = AtomicInteger(); val snapshots = AtomicInteger()
        override suspend fun readEventInfo(eventId: Long): CalendarReadState<EventNavigationInfo?> {
            reads.incrementAndGet()
            return if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) CalendarReadState.PermissionRequired
                else CalendarReadState.Ready(info)
        }
        override suspend fun readSnapshot(request: CalendarRequest): CalendarReadState<CalendarSnapshot> {
            snapshots.incrementAndGet()
            return CalendarReadState.Ready(CalendarSnapshot(request, listOf(calendar), listOf(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic linked appointment", 123))))
        }
        override suspend fun readDetails(ref: EventRef): CalendarReadState<EventDetails> = CalendarReadState.Ready(EventDetails(
            EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic linked appointment", 123), calendar))
    }
    private fun setup(): Source {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR); app.commands.clear()
        return Source().also { app.calendarEntryServices = CalendarEntryServices(it, { CalendarReadState.Ready(ZoneOffset.UTC) }, MutableSharedFlow(extraBufferCapacity = 8)) }
    }
    private fun view() = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/events/7"))
        .setClass(app, CalendarEntryActivity::class.java)
    private fun open(intent: Intent, saved: Bundle? = null): ActivityController<CalendarEntryActivity> {
        lateinit var activity: ActivityController<CalendarEntryActivity>
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(CalendarEntryActivity::class.java, intent)
            if (saved == null) activity.setup().visible() else activity.create(saved).start().resume().visible()
        }
        return activity
    }
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun close(activity: ActivityController<CalendarEntryActivity>) { compose.runOnUiThread { activity.pause().stop().destroy() } }

    @Test fun readOnlyViewScrubsOnStopAndSavedPinRejectsNamespaceReplacementOnRecreation() {
        val source = setup(); val activity = open(view())
        val saved = Bundle()
        shown("Synthetic linked appointment")
        compose.runOnUiThread { activity.saveInstanceState(saved).pause().stop() }
        compose.waitForIdle(); compose.onNodeWithText("Synthetic linked appointment").assertDoesNotExist()
        compose.runOnUiThread { activity.destroy() }
        val before = source.snapshots.get()
        source.info = source.info.copy(calendar = calendar.identity.copy(account = CalendarAccount("replacement@example.invalid", "local")))
        val recreated = open(view(), saved)
        try {
            shown(app.getString(R.string.calendar_entry_stale))
            assertEquals(before, source.snapshots.get()); assertTrue(app.commands.isEmpty())
        } finally { close(recreated) }
    }

    @Test fun missingReadIsExplicitAndRetryCannotForgetKnownPin() {
        val source = setup(); val activity = open(view())
        try {
            shown("Synthetic linked appointment")
            compose.runOnUiThread { activity.pause().stop(); shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR); activity.restart().start().resume() }
            shown(app.getString(R.string.events_permission_required))
            compose.onNodeWithText("Synthetic linked appointment").assertDoesNotExist()
            assertNull(shadowOf(activity.get()).lastRequestedPermission)
            compose.onNodeWithText(app.getString(R.string.widget_setup_allow)).performClick()
            assertArrayEquals(arrayOf(Manifest.permission.READ_CALENDAR), shadowOf(activity.get()).lastRequestedPermission.requestedPermissions)
            source.info = source.info.copy(providerUid = "replacement")
            shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
            compose.onNodeWithText(app.getString(R.string.widget_setup_retry)).performClick()
            shown(app.getString(R.string.calendar_entry_stale)); assertTrue(app.commands.isEmpty())
        } finally { close(activity) }
    }

    @Test fun insertArrivalPreservesIncompleteInputsAndOnlyExplicitReviewInvokesRootOnce() {
        val source = setup()
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).setClass(app, CalendarEntryActivity::class.java)
            .putExtra(CalendarContract.Events.TITLE, "Unscheduled synthetic seed")
            .putExtra(CalendarContract.Events.RRULE, "FREQ=ADVANCED")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("guest@example.invalid"))
        val activity = open(intent)
        try {
            shown("Unscheduled synthetic seed"); assertEquals(0, source.reads.get()); assertTrue(app.commands.isEmpty())
            compose.onNodeWithText(app.getString(R.string.calendar_entry_continue)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(isRoot()).fetchSemanticsNodes(); activity.get().isFinishing }
            assertEquals(1, app.commands.size)
            val seed = (app.commands.single().resolution as CalendarEntryResolution.CreateSeed).seed
            assertNull(seed.occurrence.begin); assertNull(seed.occurrence.end); assertEquals("FREQ=ADVANCED", seed.recurrenceRule)
            assertEquals(listOf("guest@example.invalid"), seed.guests); assertEquals(0, source.reads.get())
        } finally { close(activity) }
    }

    @Test fun malformedSavedAuthorityDoesNotRebindOnRetryButNewIntentCanStartANewSession() {
        val source = setup(); val saved = Bundle().apply { putString(CalendarEntryActivity.SESSION, "malformed") }
        val activity = open(view(), saved)
        try {
            shown(app.getString(R.string.navigation_unavailable)); assertEquals(0, source.reads.get())
            compose.onNodeWithText(app.getString(R.string.widget_setup_retry)).performClick(); compose.waitForIdle()
            assertEquals(0, source.reads.get())
            compose.runOnUiThread { activity.newIntent(view()) }
            shown("Synthetic linked appointment")
            assertTrue(source.reads.get() > 0); assertTrue(app.commands.isEmpty())
        } finally { close(activity) }
    }

    @Test fun recurringIdOnlyShowsOccurrenceRequirementWithoutGuessingTheFirstInstance() {
        val source = setup(); source.info = source.info.copy(recurringOrDetached = true, endExclusive = null)
        val activity = open(view())
        try {
            shown(app.getString(R.string.calendar_entry_occurrence))
            assertEquals(0, source.snapshots.get()); assertTrue(app.commands.isEmpty())
            compose.onNodeWithText(app.getString(R.string.calendar_entry_continue)).assertDoesNotExist()
        } finally { close(activity) }
    }
}
