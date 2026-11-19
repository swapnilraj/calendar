package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SeriesReviewNoopTest {
    private suspend fun withScopes(test:suspend (SeriesEditorFixture,SeriesEventEditorController,RecurrenceScope)->Unit) {
        for(scope in listOf(RecurrenceScope.ALL,RecurrenceScope.FOLLOWING)) {
            val f=SeriesEditorFixture()
            try {
                val source=if(scope==RecurrenceScope.ALL) f.temporalSource() else f.followingEditSource()
                val launch=f.prepare(scope,source=source)
                val c=SeriesEventEditorController(launch,source,f.port,f.store,f.scope,requestWritePermission={true})
                test(f,c,scope)
            } finally {f.scope.cancel()}
        }
    }
    private fun review(c:SeriesEventEditorController,scope:RecurrenceScope):SeriesCommand? =
        if(scope==RecurrenceScope.ALL) c.state.wholeReview?.command else c.state.followingReview?.command
    private fun confirm(c:SeriesEventEditorController,scope:RecurrenceScope) {
        if(scope==RecurrenceScope.ALL) c.confirmWholeReview() else c.confirmFollowingReview()
    }
    @Test fun exactNoopRetainsWholeAndFollowingReviewedCommandAndRevisionUntilExplicitConfirm() = runBlocking {
        withScopes {f,c,scope ->
            c.change {it.copy(temporal=it.temporal!!.copy(durationMode="ELAPSED",hours="2"))}; c.save()
            val command=review(c,scope)!!; val whole=c.state.wholeReview; val following=c.state.followingReview
            val record=f.store.records.getValue("series")
            c.change {it.copy(title=it.title,temporal=it.temporal!!.copy())}
            c.changeRule(c.state.fields.recurrence!!.values.copy())
            delay(350)
            assertEquals(record,f.store.records.getValue("series")); assertSame(whole,c.state.wholeReview); assertSame(following,c.state.followingReview)
            assertTrue(f.calls.isEmpty()); confirm(c,scope); assertEquals(command,f.calls.single().command)
        }
    }
    @Test fun clampedUnauthorizedZoneCallbackIsNoopAfterRightsAndOffsetNormalization() = runBlocking {
        for(scope in listOf(RecurrenceScope.ALL,RecurrenceScope.FOLLOWING)) {
            val f=SeriesEditorFixture()
            try {
                val original=if(scope==RecurrenceScope.ALL) f.temporalSource() else f.followingEditSource()
                val rights=original.scopes.getValue(scope).copy(fields=original.scopes.getValue(scope).fields-EditableField.ZONE)
                val source=original.copy(allowedFields=original.allowedFields-EditableField.ZONE,scopes=original.scopes+(scope to rights))
                val c=SeriesEventEditorController(f.prepare(scope,source=source),source,f.port,f.store,f.scope,requestWritePermission={true})
                c.change {it.copy(temporal=it.temporal!!.copy(hours="2",durationMode="ELAPSED"))}; c.save()
                val command=review(c,scope)!!; val record=f.store.records.getValue("series"); val fields=c.state.fields
                c.change {it.copy(temporal=it.temporal!!.copy(zone="Asia/Tokyo"))}; delay(350)
                assertEquals(fields,c.state.fields); assertEquals(command,review(c,scope)); assertEquals(record,f.store.records.getValue("series"))
                confirm(c,scope); assertEquals(command,f.calls.single().command)
            } finally {f.scope.cancel()}
        }
    }
    @Test fun realFieldChangesInvalidateWholeAndFollowingReviewAndCannotConfirmOldCommand() = runBlocking {
        withScopes {f,c,scope ->
            c.change {it.copy(temporal=it.temporal!!.copy(durationMode="ELAPSED",hours="2"))}; c.save(); assertNotNull(review(c,scope))
            c.change {it.copy(title="Actual change after review")}; assertNull(review(c,scope)); confirm(c,scope); assertTrue(f.calls.isEmpty())
            c.save(); assertNotNull(review(c,scope)); confirm(c,scope)
            val command=f.calls.single().command
            val title=when(command) {is SeriesCommand.WholeEdit -> command.intent.fields.title; is SeriesCommand.FollowingEdit -> command.intent.fields.title; else -> error("Unexpected command")}
            assertEquals(FieldChange.Set("Actual change after review"),title)
        }
    }
    @Test fun retainedNoopReviewDoesNotAuthorizeChangedLeaseAtConfirmation() = runBlocking {
        withScopes {f,c,scope ->
            c.change {it.copy(temporal=it.temporal!!.copy(durationMode="ELAPSED",hours="2"))}; c.save()
            val successor=f.store.records.getValue("series").let {it.copy(updatedAt=it.updatedAt.plusSeconds(1))}
            f.store.records["series"]=successor
            c.change {it}; assertNotNull(review(c,scope)); confirm(c,scope)
            assertTrue(f.calls.isEmpty()); assertEquals(successor,f.store.records.getValue("series")); assertEquals(SeriesEditorProblem.DRAFT_UNAVAILABLE,c.state.problem)
        }
    }
}
