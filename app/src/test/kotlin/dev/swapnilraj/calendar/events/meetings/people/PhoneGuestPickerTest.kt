package dev.swapnilraj.calendar.events.meetings.people

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.contacts.*
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields
import dev.swapnilraj.calendar.ui.events.meetings.people.PhoneGuestPicker
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
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
class PhoneGuestPickerTest {
    @get:Rule val compose=createComposeRule()
    @Test fun manualOnlyPreservesPartialEmailAndDoesNotAssignRole() {
        val rows=mutableStateListOf(MeetingCreationGuestFields(email=" invalid ",name=" old ",type="UNKNOWN"));val original=rows.single()
        compose.setContent {DaylightTheme {PhoneGuestPicker(rows,true,null,false,{},
            {email,name->rows+=MeetingCreationGuestFields(email=email,name=name.orEmpty())},{it(true)},{})}}
        compose.onNodeWithText("Search device contacts").assertDoesNotExist()
        compose.onNodeWithText("Name or email address").performTextReplacement(" partial@ ")
        compose.onNodeWithText("Add email to draft").performClick();compose.waitForIdle()
        assertEquals(original,rows.first());assertEquals(" partial@ ",rows.last().email);assertEquals("",rows.last().type)
    }
    @Test fun grantAloneNeverQueriesAndOptInIsExplicit() {
        var calls=0;var requests=0
        val source=DeviceContactSuggestionSource {_,_->calls++;DeviceContactSearchResult.QueryRequired}
        compose.setContent {DaylightTheme {PhoneGuestPicker(emptyList(),true,source,true,{requests++},{_,_->},{it(true)},{})}}
        compose.onNodeWithText("Name or email address").performTextReplacement("Person");compose.waitForIdle()
        assertEquals(0,calls);assertEquals(0,requests)
        compose.onNodeWithText("Search device contacts").performClick();compose.waitUntil(5_000) {calls==1};assertEquals(0,requests)
    }
    @Test fun deniedContactsStillAllowsManualEntryAndNoSourceQuery() {
        var calls=0;var requests=0;var email:String?=null
        val source=DeviceContactSuggestionSource {_,_->calls++;DeviceContactSearchResult.Unavailable}
        compose.setContent {DaylightTheme {PhoneGuestPicker(emptyList(),true,source,false,{requests++},{e,_->email=e},{it(true)},{})}}
        compose.onNodeWithText("Search device contacts").performClick();compose.waitForIdle();assertEquals(1,requests);assertEquals(0,calls)
        compose.onNodeWithText("Name or email address").performTextReplacement("manual@example.test")
        compose.onNodeWithText("Add email to draft").performClick();assertEquals("manual@example.test",email);assertEquals(0,calls)
    }
    @Test fun failedDoneRetainsPickerAndRetryOnlyAcknowledgesRawFlush() {
        var attempts=0;var closed=0;var added=0
        compose.setContent {DaylightTheme {PhoneGuestPicker(emptyList(),true,null,false,{}, {_,_->added++},{callback->attempts++;callback(attempts>1)},{closed++})}}
        compose.onNodeWithText("Done").performClick();compose.waitForIdle();assertEquals(0,closed)
        compose.onNodeWithText("Latest entries could not be saved to the draft. Try Done again, or go back to review them.").assertExists()
        compose.onNodeWithText("Done").performClick();compose.waitForIdle();assertEquals(1,closed);assertEquals(2,attempts);assertEquals(0,added)
    }
    @Test fun backRetainsAddedRawRowsWithoutCallingDone() {
        val rows=mutableStateListOf<MeetingCreationGuestFields>();var closed=0;var flushes=0
        compose.setContent {DaylightTheme {PhoneGuestPicker(rows,true,null,false,{}, {e,n->rows+=MeetingCreationGuestFields(email=e,name=n.orEmpty())},{flushes++;it(true)},{closed++})}}
        compose.onNodeWithText("Name or email address").performTextReplacement("partial@")
        compose.onNodeWithText("Add email to draft").performClick();compose.onNodeWithText("Back").performClick()
        assertEquals(1,closed);assertEquals(0,flushes);assertEquals("partial@",rows.single().email)
    }
    @Test fun disabledPickerCannotRequestContactsAddOrFinish() {
        var effects=0
        compose.setContent {DaylightTheme {PhoneGuestPicker(emptyList(),false,DeviceContactSuggestionSource {_,_->effects++;DeviceContactSearchResult.Unavailable},false,{effects++},{_,_->effects++},{effects++;it(true)},{})}}
        compose.onNodeWithText("Search device contacts").assertIsNotEnabled();compose.onNodeWithText("Done").assertIsNotEnabled()
        compose.onNodeWithText("Add email to draft").assertIsNotEnabled();assertEquals(0,effects)
    }
    @Test fun exactTupleDuplicatesCollapseButDifferentNamesRemainSelectableAndRaw() {
        val picked=mutableListOf<Pair<String,String?>>()
        val a=DeviceContactSuggestion(" Raw@Example.test "," First ");val b=a.copy(name="Second")
        val source=DeviceContactSuggestionSource {_,_->DeviceContactSearchResult.Ready(listOf(a,a,b),false)}
        compose.setContent {DaylightTheme {PhoneGuestPicker(emptyList(),true,source,true,{}, {e,n->picked+=e to n},{it(true)},{})}}
        compose.onNodeWithText("Name or email address").performTextReplacement("Raw")
        compose.onNodeWithText("Search device contacts").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithText(" Raw@Example.test ").fetchSemanticsNodes().size==2}
        compose.onNodeWithText(" First ").performScrollTo().performClick();compose.onNodeWithText("Second").performScrollTo().performClick()
        assertEquals(listOf(a.email to a.name,b.email to b.name),picked)
    }
    @Test fun lateGrantAfterCloseDoesNotOptInReopenedPicker() {
        var open by mutableStateOf(true);var permission by mutableStateOf(false);var calls=0;var requests=0
        val source=DeviceContactSuggestionSource {_,_->calls++;DeviceContactSearchResult.QueryRequired}
        compose.setContent {DaylightTheme {if(open) PhoneGuestPicker(emptyList(),true,source,permission,{requests++},{_,_->},{it(true)},{open=false})}}
        compose.onNodeWithText("Name or email address").performTextReplacement("Raw")
        compose.onNodeWithText("Search device contacts").performClick();assertEquals(1,requests)
        compose.onNodeWithText("Back").performClick();compose.waitForIdle()
        compose.runOnIdle {permission=true;open=true};compose.waitForIdle()
        compose.onNodeWithText("Search device contacts").assertExists();assertEquals(0,calls)
    }

    @Test fun nullAndEmptyNamesStayTwoChoicesButAppendOnlyOneRawRow() {
        val rows=mutableStateListOf<MeetingCreationGuestFields>();val email="same@example.test"
        val source=DeviceContactSuggestionSource {_,_->DeviceContactSearchResult.Ready(listOf(DeviceContactSuggestion(email,null),DeviceContactSuggestion(email,"")),false)}
        compose.setContent {DaylightTheme {PhoneGuestPicker(rows,true,source,true,{}, {e,n->rows+=MeetingCreationGuestFields(email=e,name=n.orEmpty())},{it(true)},{})}}
        compose.onNodeWithText("Name or email address").performTextReplacement("same")
        compose.onNodeWithText("Search device contacts").performClick()
        val choices=hasText(email) and hasClickAction()
        compose.waitUntil(5_000) {compose.onAllNodes(choices).fetchSemanticsNodes().size==2}
        compose.onAllNodes(choices).onFirst().performClick();compose.waitForIdle()
        compose.onAllNodes(choices).assertCountEquals(2)
        compose.onAllNodes(choices).onFirst().assertIsNotEnabled();compose.onAllNodes(choices).onLast().assertIsNotEnabled()
        assertEquals(1,rows.size);assertEquals("",rows.single().name);assertEquals("",rows.single().type)
    }

}
