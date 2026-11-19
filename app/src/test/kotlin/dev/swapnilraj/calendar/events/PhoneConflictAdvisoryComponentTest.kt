package dev.swapnilraj.calendar.events

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneConflictAdvisoryComponentTest {
    @get:Rule val compose=createComposeRule()
    private fun show(state:EditorConflictAdvisoryState) {
        compose.setContent {DaylightTheme {Surface {PhoneConflictAdvisory(state)}}}
    }
    @Test fun currentCompleteEmptyUsesQualifiedDeviceLocalClearWording() {
        show(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED,complete=true))
        compose.onNodeWithText("No overlapping local events on the selected calendars.").assertIsDisplayed()
        compose.onNodeWithText("Based on events on this device. This does not check other people’s availability.").assertIsDisplayed()
    }
    @Test fun knownAndUnknownCountsCoexistWithoutClearWording() {
        show(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED,2,1,true))
        compose.onNodeWithText("2 overlapping local events.").assertIsDisplayed()
        compose.onNodeWithText("1 possible overlap with unknown availability.").assertIsDisplayed()
        compose.onNodeWithText("No overlapping local events on the selected calendars.").assertDoesNotExist()
    }
    @Test fun partialEmptyNeverCertifiesNoOverlaps() {
        show(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED))
        compose.onNodeWithText("Availability is incomplete for some selected calendars.").assertIsDisplayed()
        compose.onNodeWithText("No overlapping local events on the selected calendars.").assertDoesNotExist()
    }
    @Test fun noSelectionHasPassiveGuidanceAndNoSaveOrPermissionAction() {
        show(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.NO_CALENDARS_SELECTED))
        compose.onNodeWithText("Choose calendars in the sidebar to check for overlaps.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
    @Test fun firstOccurrenceResultAlwaysQualifiesRepeatingCreation() {
        show(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED,complete=true,firstOccurrenceOnly=true))
        compose.onNodeWithText("Only the first occurrence is checked. Repeating events may have other overlaps.").assertIsDisplayed()
    }
    @Test fun renderHelperMasksKnownCountsBeforeControllerUpdateEffect() {
        val feed=ConflictAdvisoryFeed();val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Unconfined)
        val c=EditorConflictAdvisoryController(feed,scope)
        try {
            kotlinx.coroutines.runBlocking {
                val current=ConflictAdvisoryFixture.input();c.update(current)
                feed.emit(0,dev.swapnilraj.calendar.core.CalendarReadState.Ready(feed.snapshot(0,candidates=listOf(ConflictAdvisoryFixture.candidate()))))
                val held=c.state.value
                val changed=ConflictAdvisoryFixture.input(permission=false)
                show(c.presentation(changed,held))
            }
            compose.onNodeWithText("Calendar access is needed to check for overlaps.").assertIsDisplayed()
            compose.onNodeWithText("1 overlapping local event.").assertDoesNotExist()
        } finally {c.close();scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()}
    }
}
