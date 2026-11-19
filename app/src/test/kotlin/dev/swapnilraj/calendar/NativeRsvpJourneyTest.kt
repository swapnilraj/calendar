package dev.swapnilraj.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.meetings.MeetingResponseDraftCodec
import dev.swapnilraj.calendar.ui.settings.CalendarSettingsOptions
import java.time.Instant
import java.time.temporal.ChronoUnit
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

/** Actual MainActivity/native response route, AtomicDraftStore and CalendarMeetingWriter. The SQL
 * fixture models provider transaction and joined attendee fields, not Google invitation delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRsvpJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic work invitation"

    @Test fun nativeNoChangesOnlyOwnResponseAndNeverCancelsOrganizerOrOtherGuests() = fixture { f ->
        openResponse(capture = true)
        // Stable native response-sheet heading only; its real-clock date is not snapshotted.
        compose.onNodeWithText("Respond to invitation").captureToImage().asAndroidBitmap()
            .captureRoboImage("src/test/screenshots/integration_native_rsvp_response.png")
        choose("No")
        save()
        awaitSaved(f)
        assertEquals(1, f.provider.batchCount)
        assertEquals("2", f.provider.value("attendees", "attendeeStatus"))
        assertEquals("2", f.provider.value("events", "selfAttendeeStatus"))
        assertEquals("1", f.provider.value("events", "eventStatus"))
        assertEquals("1", f.provider.value("attendees", "attendeeStatus", 21))
        assertProtected(f)
        assertTrue(f.provider.mutations.all { it.uri == CalendarContract.Attendees.CONTENT_URI && it.selection?.startsWith("_id = ?") == true })
        val selected = (runBlocking { f.app.calendarMeetings.loadForEdit(f.ref) } as MeetingReadResult.Ready).value
        assertEquals(f.ref, selected.details.occurrence.ref)
        assertEquals(2, selected.details.selfAttendeeStatus)
    }

    @Test fun firstSaveWithoutWriteRetainsChoicesAndGrantRequiresAnotherExplicitSave() = fixture(writeAllowed = false) { f ->
        openResponse()
        choose("Yes")
        save()
        shown(f.app.getString(R.string.meeting_response_permission))
        assertEquals(0, f.provider.batchCount)
        val draft = responseRecord(f)
        assertEquals("YES", org.json.JSONObject(draft.payload).getString("response"))
        assertFalse(org.json.JSONObject(draft.payload).has("frozen"))
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { f.activity.pause().stop().restart().start().resume() }
        compose.waitForIdle()
        assertEquals(0, f.provider.batchCount) // A grant/resume is never submission authority.
        compose.onNodeWithText("Yes").assertIsSelected()
        save()
        awaitSaved(f)
        assertEquals(1, f.provider.batchCount)
        assertEquals("1", f.provider.value("attendees", "attendeeStatus"))
        assertProtected(f)
    }

    @Test fun knownCommitWithFailedReadbackReopensFrozenDraftAndRecoversWithoutWritePermissionOrReplay() = fixture { f ->
        openResponse()
        choose("Maybe")
        f.provider.failAfterCommit = true
        save()
        shown(f.app.getString(R.string.meeting_response_uncertain))
        assertEquals(1, f.provider.batchCount)
        assertEquals("4", f.provider.value("attendees", "attendeeStatus"))
        val frozen = responseRecord(f)
        val launch = requireNotNull(MeetingResponseDraftCodec.describe(frozen))
        assertTrue(launch.needsRecovery)
        val operationId = org.json.JSONObject(frozen.payload).getString("operationId")
        assertEquals("COMMITTED", f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT phase FROM mutation_journal WHERE id = ?", arrayOf(operationId)).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        })
        compose.onNode(hasText("Close") and hasClickAction() and hasAnyAncestor(isRoot() and hasAnyDescendant(hasText("Respond to invitation")))).performScrollTo().performClick()
        f.provider.failAfterCommit = false
        shadowOf(f.app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Drafts").performClick()
        shown("Invitation response draft")
        compose.onNodeWithText("Recover operation").performClick()
        shown("Respond to invitation")
        compose.onNodeWithText("Save response on this device").assertIsNotEnabled()
        compose.onNodeWithText("Review original operation").performScrollTo().performClick()
        awaitSaved(f)
        assertEquals(1, f.provider.batchCount)
        assertEquals("4", f.provider.value("attendees", "attendeeStatus"))
        assertProtected(f)
    }

    private fun openResponse(capture: Boolean = false) {
        shown(title)
        compose.onNodeWithText(title).performClick()
        shown("Respond to invitation")
        compose.onNodeWithText("Respond to invitation").performScrollTo()
        if (capture) compose.onNodeWithText("Respond to invitation").captureToImage().asAndroidBitmap()
            .captureRoboImage("src/test/screenshots/integration_native_rsvp_respond.png")
        compose.onNodeWithText("Respond to invitation").performClick()
        shown("This event")
        compose.onNodeWithText("Entire series").assertIsNotEnabled()
    }
    private fun choose(response: String) {
        compose.onNodeWithText("This event").performScrollTo().performClick()
        compose.onNodeWithText(response).performScrollTo().performClick()
    }
    private fun save() = compose.onNodeWithText("Save response on this device").performScrollTo().performClick()
    private fun shown(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun responseRecord(f: Fixture) = runBlocking { f.app.calendarLocalStore.list() }.single { MeetingResponseDraftCodec.describe(it) != null }
    private fun awaitSaved(f: Fixture) {
        compose.waitUntil(15_000) { runBlocking { f.app.calendarLocalStore.list() }.none { MeetingResponseDraftCodec.describe(it) != null } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Save response on this device").fetchSemanticsNodes().isEmpty() }
    }
    private fun assertProtected(f: Fixture) {
        assertEquals(f.eventBefore.filterKeys { it != "selfAttendeeStatus" }, f.provider.snapshot("events").single().filterKeys { it != "selfAttendeeStatus" })
        assertEquals(f.guestsBefore.filter { it["_id"] != "20" }, f.provider.snapshot("attendees").filter { it["_id"] != "20" })
        assertEquals(f.guestsBefore.single { it["_id"] == "20" }.filterKeys { it != "attendeeStatus" }, f.provider.snapshot("attendees").single { it["_id"] == "20" }.filterKeys { it != "attendeeStatus" })
        assertEquals(f.remindersBefore, f.provider.snapshot("reminders"))
    }
    private class Fixture(val app: CalendarApp, val provider: NativeRsvpJourneyProvider, val activity: ActivityController<MainActivity>, val ref: EventRef) {
        val eventBefore = provider.snapshot("events").single()
        val guestsBefore = provider.snapshot("attendees")
        val remindersBefore = provider.snapshot("reminders")
    }
    private fun fixture(writeAllowed: Boolean = true, block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        if (writeAllowed) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val start = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
        val provider = NativeRsvpJourneyProvider(start)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings())
            app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false))
            val metadata = app.calendarReader.observeCalendars().first { it is CalendarReadState.Ready } as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value))
        }
        lateinit var activity: ActivityController<MainActivity>
        compose.runOnUiThread { activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible() }
        val f = Fixture(app, provider, activity, EventRef(CalendarId(2), 10, start, start.plusSeconds(3600)))
        try { block(f) }
        finally {
            compose.runOnUiThread { activity.pause().stop().destroy() }
            provider.db.close()
        }
    }
}
