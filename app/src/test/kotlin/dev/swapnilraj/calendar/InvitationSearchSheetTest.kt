package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.search.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InvitationSearchSheetTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 11, 17)
    private val calendar = CalendarRequest(defaultSearchRange(date), ZoneId.of("UTC"), invitationFilter = InvitationFilter.PENDING_ONLY)
    private val info = CalendarInfo(CalendarId(1), "Private account", 0, CalendarAccount("private@example.test", "com.google"), null, null, 700, true, true)
    private fun state(invitation: InvitationState, metadata: Boolean = true): SearchUiState {
        val start = date.atTime(9, 0).toInstant(ZoneOffset.UTC)
        val ref = EventRef(info.id, 1, start, start.plusSeconds(3600))
        val hit = CalendarSearchHit(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Private title", 0,
            invitation = invitation), "Private room", "Private notes")
        return SearchUiState("private", calendar, status = SearchStatus.RESULTS,
            snapshot = CalendarSearchSnapshot(CalendarSearchRequest("private", calendar), if (metadata) listOf(info) else emptyList(), listOf(hit), false))
    }
    @Test fun unknownStatusHasQualifiedEmptyMessageAndCoverageWithoutPrivateEventRows() {
        compose.setContent { DaylightTheme { SearchSheet(state(InvitationState.UNKNOWN), {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("No matching pending invitations could be confirmed in the available results."))
        compose.onNodeWithText("No matching pending invitations could be confirmed in the available results.").assertExists()
        compose.onNodeWithText("Private title").assertDoesNotExist()
        compose.onNodeWithText("Private room").assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/search_pending_unknown_light.png")
    }
    @Test fun missingMetadataStillDescribesUnknownClassification() {
        compose.setContent { DaylightTheme { SearchSheet(state(InvitationState.UNKNOWN, false), {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("Some invitation statuses are unavailable."))
        compose.onNodeWithText("Some invitation statuses are unavailable.").assertExists()
    }
    @Test fun staleClickIsBlockedAfterFilterMismatchAndPermissionLoss() {
        var shown by mutableStateOf(state(InvitationState.PENDING))
        var opens = 0
        compose.setContent { DaylightTheme { SearchSheet(shown, {}, {}, {}, {}, {}, { opens++ }, {}) } }
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag("search_hit_1_1"))
        val rendered = shown
        val hit = rendered.hits.single()
        val click = { openCurrentSearchHit(rendered, shown, hit) { opens++ } }
        compose.onNodeWithTag("search_hit_1_1").performClick()
        assertEquals(1, opens)
        opens = 0
        compose.runOnIdle { shown = shown.copy(calendar = calendar.copy(invitationFilter = InvitationFilter.ALL)) }
        compose.onNodeWithText("Private title").assertDoesNotExist()
        compose.runOnIdle { click() }
        assertEquals(0, opens)
        compose.runOnIdle { shown = state(InvitationState.PENDING).copy(status = SearchStatus.PERMISSION_REQUIRED) }
        compose.onNodeWithText("private@example.test", substring = true).assertDoesNotExist()
        compose.runOnIdle { click() }
        assertEquals(0, opens)
    }
}
