package dev.swapnilraj.calendar.events.meetings.people

import dev.swapnilraj.calendar.contacts.*
import dev.swapnilraj.calendar.ui.events.meetings.people.GuestPickerSearchSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestPickerSearchSessionTest {
    private val suggestion=DeviceContactSuggestion(" Raw@Example.test "," Name ")
    @Test fun permissionAlreadyGrantedDoesNotOptInOrQuery()=runTest {
        var calls=0
        val s=GuestPickerSearchSession(DeviceContactSuggestionSource {_,_->calls++;DeviceContactSearchResult.Ready(listOf(suggestion),false)},this)
        s.observePermission(true);s.changeQuery("Raw");advanceUntilIdle()
        assertFalse(s.optedIn);assertEquals(0,calls);assertEquals(DeviceContactSearchResult.Disabled,s.result)
    }
    @Test fun optInWithoutPermissionDoesNotQueryButGrantResumesReadOnlySearch()=runTest {
        val calls=mutableListOf<Pair<String,Boolean>>()
        val s=GuestPickerSearchSession(DeviceContactSuggestionSource {q,e->calls+=q to e;DeviceContactSearchResult.Ready(listOf(suggestion),false)},this)
        s.changeQuery("Raw");s.optIn();advanceUntilIdle();assertTrue(calls.isEmpty());assertEquals(DeviceContactSearchResult.PermissionRequired,s.result)
        s.observePermission(true);advanceUntilIdle();assertEquals(listOf("Raw" to true),calls)
        assertEquals(listOf(suggestion),(s.result as DeviceContactSearchResult.Ready).items)
    }
    @Test fun debounceOnlyQueriesLatestTextAndPreservesRawSuggestionTuples()=runTest {
        val calls=mutableListOf<String>()
        val renamed=suggestion.copy(name="Other")
        val s=GuestPickerSearchSession(DeviceContactSuggestionSource {q,_->calls+=q;DeviceContactSearchResult.Ready(listOf(suggestion,suggestion,renamed),true)},this)
        s.observePermission(true);s.optIn();s.changeQuery("Ra");advanceTimeBy(200);s.changeQuery("Raw");advanceTimeBy(349);runCurrent();assertTrue(calls.isEmpty())
        advanceTimeBy(1);runCurrent();assertEquals(listOf("Raw"),calls)
        assertEquals(DeviceContactSearchResult.Ready(listOf(suggestion,renamed),true),s.result)
    }
    @Test fun nonCooperativeOldQueryCannotReplaceLatestResult()=runTest {
        val old=CompletableDeferred<Unit>()
        val s=GuestPickerSearchSession(DeviceContactSuggestionSource {q,_->
            if(q=="old") withContext(NonCancellable) {old.await()}
            DeviceContactSearchResult.Ready(listOf(DeviceContactSuggestion(q,null)),false)
        },this)
        s.observePermission(true);s.optIn();s.changeQuery("old");advanceTimeBy(350);runCurrent();assertTrue(s.loading)
        s.changeQuery("new");advanceTimeBy(350);runCurrent();assertEquals("new",(s.result as DeviceContactSearchResult.Ready).items.single().email)
        old.complete(Unit);advanceUntilIdle();assertEquals("new",(s.result as DeviceContactSearchResult.Ready).items.single().email)
    }
    @Test fun permissionLossImmediatelyClearsResultsAndCancelsInFlight()=runTest {
        val entered=CompletableDeferred<Unit>();var cancelled=false
        val s=GuestPickerSearchSession(DeviceContactSuggestionSource {_,_->try {entered.complete(Unit);awaitCancellation()} finally {cancelled=true}},this)
        s.observePermission(true);s.optIn();s.changeQuery("Raw");advanceTimeBy(350);runCurrent();assertTrue(entered.isCompleted)
        s.observePermission(false);assertFalse(s.loading);assertEquals(DeviceContactSearchResult.PermissionRequired,s.result);runCurrent();assertTrue(cancelled)
    }
    @Test fun closeFencesLateGrantAndNewSessionRequiresNewOptIn()=runTest {
        var calls=0;val source=DeviceContactSuggestionSource {_,_->calls++;DeviceContactSearchResult.QueryRequired}
        val s=GuestPickerSearchSession(source,this);s.changeQuery("Raw");s.optIn();s.close();s.observePermission(true);s.optIn();advanceUntilIdle()
        assertEquals(0,calls);assertEquals("",s.query);assertFalse(s.optedIn)
        val reopened=GuestPickerSearchSession(source,this);reopened.observePermission(true);reopened.changeQuery("Raw");advanceUntilIdle();assertEquals(0,calls)
    }
    @Test fun closeCancelsQueryAndDropsNonCooperativeResult()=runTest {
        val release=CompletableDeferred<Unit>()
        val s=GuestPickerSearchSession(DeviceContactSuggestionSource {_,_->withContext(NonCancellable) {release.await()};DeviceContactSearchResult.Ready(listOf(suggestion),false)},this)
        s.observePermission(true);s.optIn();s.changeQuery("Raw");advanceTimeBy(350);runCurrent();s.close();release.complete(Unit);advanceUntilIdle()
        assertEquals(DeviceContactSearchResult.Disabled,s.result);assertFalse(s.loading);assertEquals("",s.query)
    }
    @Test fun sourceFailureIsTruthfulAndManualQueryStillEditable()=runTest {
        val s=GuestPickerSearchSession(DeviceContactSuggestionSource {_,_->error("unavailable")},this)
        s.observePermission(true);s.optIn();s.changeQuery("Raw");advanceUntilIdle();assertEquals(DeviceContactSearchResult.Unavailable,s.result)
        s.changeQuery("partial@");assertEquals("partial@",s.query);s.close()
    }
    @Test fun absentSourceNeverOptsInOrQueries()=runTest {
        val s=GuestPickerSearchSession(null,this);s.observePermission(true);s.optIn();s.changeQuery("partial@");advanceUntilIdle()
        assertFalse(s.optedIn);assertEquals(DeviceContactSearchResult.Disabled,s.result);assertEquals("partial@",s.query)
    }
}
