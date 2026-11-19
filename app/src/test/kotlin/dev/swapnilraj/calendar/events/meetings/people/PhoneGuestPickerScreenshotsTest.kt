package dev.swapnilraj.calendar.events.meetings.people

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.contacts.*
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields
import dev.swapnilraj.calendar.ui.events.meetings.people.PhoneGuestPicker
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic source renders, not installed-app captures or Google runtime parity evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneGuestPickerScreenshotsTest {
    @get:Rule val compose=createComposeRule()
    @After fun resetFontScale() {RuntimeEnvironment.setFontScale(1f)}
    private fun show(rows:MutableList<MeetingCreationGuestFields>,source:DeviceContactSuggestionSource?=null,
        permission:Boolean=false,dark:Boolean=false,largeRtl:Boolean=false) {
        RuntimeEnvironment.setFontScale(if(largeRtl) 2f else 1f)
        compose.setContent {CompositionLocalProvider(LocalLayoutDirection provides if(largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            DaylightTheme(dark=dark) {
                Dialog(onDismissRequest={},properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {
                    PhoneGuestPicker(rows,true,source,permission,{},
                        {email,name->rows+=MeetingCreationGuestFields(email=email,name=name.orEmpty())},{it(true)},{})
                }
            }
        }}
        compose.waitForIdle()
    }
    @Test fun guestPickerManualOnlyLight() {
        val rows=mutableStateListOf(MeetingCreationGuestFields(email="partial@",name="First draft entry",type="UNKNOWN"),
            MeetingCreationGuestFields(email="second@example.test",name="Second draft entry"))
        show(rows)
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("Search device contacts").assertDoesNotExist()
        compose.onNodeWithText("partial@").assertIsDisplayed()
        compose.onNodeWithText("second@example.test").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_guest_picker_manual_light.png")
    }
    @Test fun guestPickerOptedInResultsAndSelectionLight() {
        val rows=mutableStateListOf<MeetingCreationGuestFields>()
        val source=DeviceContactSuggestionSource {_,_->DeviceContactSearchResult.Ready(listOf(
            DeviceContactSuggestion("alex.one@example.test","Alex One"),DeviceContactSuggestion("alex.two@example.test","Alex Two")),false)}
        show(rows,source,permission=true)
        compose.onNodeWithText("Name or email address").performTextReplacement("Alex")
        compose.onNodeWithText("Search device contacts").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithText("Alex One").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Alex One").performClick();compose.waitForIdle()
        assertEquals("alex.one@example.test",rows.single().email);assertEquals("",rows.single().type)
        compose.onNodeWithText("Already in this draft").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_guest_picker_results_light.png")
    }
    @Test fun guestPickerDeniedContactsDark() {
        val rows=mutableStateListOf(MeetingCreationGuestFields(email="manual@example.test",name="Manual entry"))
        var queries=0
        val source=DeviceContactSuggestionSource {_,_->queries++;DeviceContactSearchResult.Unavailable}
        show(rows,source,dark=true)
        compose.onNodeWithText("Name or email address").performTextReplacement("Alex")
        compose.onNodeWithText("Search device contacts").performClick();compose.waitForIdle()
        compose.onNodeWithText("Contacts access is unavailable. You can still enter email addresses.").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed();assertEquals(0,queries)
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_guest_picker_denied_dark.png")
    }
    @Test fun guestPickerLongRawSelectionLargeRtl() {
        val longEmail="long.recipient.address+work.personal@example.test"
        val rows=mutableStateListOf(MeetingCreationGuestFields(email="partial@",name="First draft entry",type="UNKNOWN"),
            MeetingCreationGuestFields(email=longEmail,name="Second draft entry"))
        val ids=rows.map {it.rowId}
        show(rows,largeRtl=true)
        compose.onNodeWithText(longEmail).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        assertEquals(ids,rows.map {it.rowId});assertEquals(listOf("partial@",longEmail),rows.map {it.email})
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/phone_guest_picker_rtl_large.png")
    }
}
