package dev.swapnilraj.calendar

import android.Manifest
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarWriterTest
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.calendars.CalendarViewOption
import dev.swapnilraj.calendar.ui.events.FrozenEditorSubmission
import dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument
import dev.swapnilraj.calendar.ui.settings.*
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Actual application host, failed native transaction, durable retirement and replacement route.
 * The provider/Activity lifecycle is synthetic; no real account/process-kill or server claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeOrdinaryRetirementJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic retained ordinary appointment"

    @Test fun failedFrozenCreationRetiresBeforeReplacementAndOnlyFreshSaveCreatesAfterActivityRecreation() {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = CalendarWriterTest.Provider().apply { failReminder = true }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings()); app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, timedReminderMinutes = 10, displayZone = DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            assertTrue(app.settings.setCalendarView(CalendarViewOption.DAY.name))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), metadata.value))
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java)
        compose.runOnUiThread { activity.setup().visible() }
        try {
            shown("Create event"); compose.onNodeWithText("Create event").performClick(); shown("Save")
            val titleField = hasSetTextAction() and hasContentDescription(app.getString(R.string.phone_event_title))
            compose.waitUntil(15_000) { compose.onAllNodes(titleField and isEnabled()).fetchSemanticsNodes().size == 1 }
            compose.onNode(titleField).performScrollTo(); compose.waitForIdle()
            compose.onNode(titleField).performTextReplacement(title)
            chooseCalendar(app)
            compose.onNodeWithText("Save").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) {
                compose.waitForIdle()
                val doc = runBlocking { app.calendarLocalStore.list() }.singleOrNull()?.let(OrdinaryDraftDocument::decode)
                doc?.submitted == true && app.calendarLocalStore.operation(doc.operationId)?.phase == "FAILED"
            }
            val oldRecord = runBlocking { app.calendarLocalStore.list() }.single()
            val old = OrdinaryDraftDocument.decode(oldRecord)
            val frozen = old.frozen as FrozenEditorSubmission.Create
            assertEquals(1, provider.batchCount); assertEquals(0, provider.events.count { it["deleted"] != "1" }); assertEquals(0, provider.reminders.size)
            compose.onNodeWithText(app.getString(R.string.phone_event_replace)).performScrollTo().performClick()
            compose.onNodeWithText("Confirm").performClick()
            compose.waitUntil(15_000) {
                compose.waitForIdle()
                app.calendarLocalStore.operationHeader(old.operationId)?.phase == "ABANDONED" &&
                    runBlocking { app.calendarLocalStore.list() }.singleOrNull()?.id?.let { it != old.id } == true
            }
            val replacement = OrdinaryDraftDocument.decode(runBlocking { app.calendarLocalStore.list() }.single())
            assertNotEquals(old.generation, replacement.generation); assertNotEquals(old.operationId, replacement.operationId)
            assertFalse(replacement.submitted); assertEquals("", replacement.fields.calendarId); assertNull(replacement.fields.targetIdentity)
            assertEquals(title, replacement.fields.title); assertEquals(1, provider.batchCount)
            // Durable replacement creation can precede its IO continuation and host route update.
            // Rotate only after the user can see/edit the new generation, not the retired form.
            compose.waitUntil(15_000) {
                compose.onAllNodes(titleField and hasText(title) and isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText(app.getString(R.string.phone_event_choose_calendar)).assertExists()
            assertEquals(OperationRetirementResult.Retired(old.operationId), runBlocking { app.operationRetirement.inspect(frozen.retirementRequest()) })
            shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.Retired(old.operationId), runBlocking { app.calendarWriter.create(old.operationId, frozen.draft) })
            assertEquals(1, provider.batchCount)
            compose.runOnUiThread { activity.recreate() }; shown(title)
            val restored = OrdinaryDraftDocument.decode(runBlocking { app.calendarLocalStore.list() }.single())
            assertEquals(replacement.generation, restored.generation); assertEquals(replacement.operationId, restored.operationId)
            compose.onNodeWithText("Save").assertIsDisplayed().assertIsNotEnabled()
            provider.failReminder = false; shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
            chooseCalendar(app)
            assertEquals(1, provider.batchCount) // A grant, restoration and selection never submit.
            compose.onNodeWithText("Save").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { compose.waitForIdle(); app.calendarLocalStore.operation(replacement.operationId)?.phase == "VERIFIED" }
            compose.waitUntil(15_000) { runBlocking { app.calendarLocalStore.list() }.isEmpty() }
            assertEquals(2, provider.batchCount); assertEquals(1, provider.events.count { it["deleted"] != "1" }); assertEquals(1, provider.reminders.size)
            assertEquals("ABANDONED", app.calendarLocalStore.operationHeader(old.operationId)!!.phase)
            val saved = runBlocking { app.calendarWriter.recover(replacement.operationId) } as MutationResult.LocalSaved
            val detail = runBlocking { app.calendarReader.readDetails(saved.ref) } as CalendarReadState.Ready
            assertEquals(title, detail.value.occurrence.title); assertEquals(2, provider.batchCount)
        } finally { compose.runOnUiThread { activity.pause().stop().destroy() } }
    }
    private fun chooseCalendar(app: CalendarApp) {
        val label = app.getString(R.string.phone_event_choose_calendar)
        val chooser = hasText(label) and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            compose.onAllNodes(chooser).fetchSemanticsNodes().size == 1
        }
        compose.onNode(chooser).performScrollTo().performClick()
        val option = hasText("Synthetic") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            hasAnyAncestor(isDialog()) and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) {
            compose.waitForIdle()
            compose.onAllNodes(option).fetchSemanticsNodes().size == 1
        }
        compose.onNode(option).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty() }
    }
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}
