package dev.swapnilraj.calendar.events.meetings.people

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.input.ImeAction
import dev.swapnilraj.calendar.contacts.*
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.events.meetings.owned.OwnedFixture
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import dev.swapnilraj.calendar.ui.events.meetings.people.PhoneGuestPicker
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuestKeyboardAccessibilityTest {
    @get:Rule val compose=createComposeRule()
    @Test fun pickerImeDoneClearsFocusRetainsQueryAndNeverAddsFinishesOrRequestsPermission() {
        var added=0;var finished=0;var permissionRequests=0;var queries=0
        val source=DeviceContactSuggestionSource {_,_->queries++;DeviceContactSearchResult.Unavailable}
        compose.setContent {DaylightTheme {PhoneGuestPicker(emptyList(),true,source,true,{permissionRequests++},{_,_->added++},{finished++;it(true)},{})}}
        val input=compose.onNodeWithText("Name or email address")
        input.performTextReplacement(" partial@ ");input.assertIsFocused()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction,ImeAction.Done)).performImeAction()
        input.assertIsNotFocused();input.assertTextContains(" partial@ ")
        compose.onNodeWithText("Search device contacts").assertExists();compose.onNodeWithText("Done").assertExists()
        assertEquals(0,added);assertEquals(0,finished);assertEquals(0,permissionRequests);assertEquals(0,queries)
    }
    @Test fun pickerExposesNamedPaneAndHeading() {
        compose.setContent {DaylightTheme {PhoneGuestPicker(emptyList(),true,null,false,{}, {_,_->},{it(true)},{})}}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Add people")).assertExists()
        compose.onNode(hasText("Add people") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }
    @Test fun backAfterImeDoneRetainsExistingRawRowsAndDoesNotFinishDraft() {
        val rows=listOf(MeetingCreationGuestFields(email=" invalid@ ",name=" raw ",type="UNKNOWN"))
        var dispatcher:OnBackPressedDispatcher?=null;var open by mutableStateOf(true);var finishes=0
        compose.setContent {dispatcher=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            DaylightTheme {if(open) PhoneGuestPicker(rows,true,null,false,{}, {_,_->error("must not add")},{finishes++;it(true)},{open=false})}}
        compose.onNodeWithText("Name or email address").performTextReplacement("query@")
        compose.onNodeWithText("Name or email address").performImeAction()
        compose.runOnIdle {dispatcher!!.onBackPressed()};compose.waitForIdle()
        compose.onNodeWithText("Name or email address").assertDoesNotExist();assertFalse(open);assertEquals(0,finishes)
        assertEquals(" invalid@ ",rows.single().email);assertEquals("UNKNOWN",rows.single().type)
    }
    @Test fun createNewGuestImeDonePreservesInvalidRawAndNeverSubmitsMeeting() {
        val f=MeetingCreationFixture();val c=runBlocking {f.ready()};val before=c.state.fields!!.guests
        compose.setContent {DaylightTheme {PhoneMeetingCreateEditor(c,listOf(f.calendar),{}, {})}}
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        val input=compose.onNodeWithContentDescription("Person 2: Email address")
        input.performScrollTo().performTextReplacement(" partial@ ")
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction,ImeAction.Done)).performImeAction()
        input.assertIsNotFocused();compose.waitForIdle()
        assertEquals(before,c.state.fields!!.guests.dropLast(1));assertEquals(" partial@ ",c.state.fields!!.guests.last().email)
        assertEquals("",c.state.fields!!.guests.last().type);assertTrue(c.canEditRaw);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        val restored=f.controller(f.launch());runBlocking {restored.load()};assertEquals(c.state.fields!!.guests,restored.state.fields!!.guests)
    }
    @Test fun ownedNewGuestImeDonePreservesOpaqueChoicesAndRawWithoutSubmitting() {
        val f=OwnedFixture();val c=runBlocking {f.open()};val before=c.state.fields!!.guests
        compose.setContent {DaylightTheme {PhoneOwnedMeetingEditor(c,{}, {}, {})}}
        compose.onNodeWithText("Add person").performScrollTo().performClick()
        val input=compose.onNodeWithContentDescription("New person 1: Email address")
        input.performScrollTo().performTextReplacement(" invalid@ ")
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction,ImeAction.Done)).performImeAction()
        input.assertIsNotFocused();compose.waitForIdle()
        assertEquals(before,c.state.fields!!.guests);assertEquals(" invalid@ ",c.state.fields!!.added.single().email)
        assertEquals("",c.state.fields!!.added.single().type);assertTrue(c.canAddGuests);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        val restored=f.controller(f.launch());runBlocking {restored.load()};assertEquals(c.state.fields,restored.state.fields)
    }
}
