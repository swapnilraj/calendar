package dev.swapnilraj.calendar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/** Real review activities, provider writer and SQLite. Only the post-commit cleanup acknowledgment
 * is delayed, modeling a completed local transaction whose caller has not received its result. */
class ReviewCleanupLifecycleApplication : CalendarApp() {
    internal val cleanup by lazy { ReviewCleanupGate(calendarLocalStore) }
    override val calendarSeedReviewServices by lazy {
        val real=createCalendarSeedReviewServices()
        CalendarSeedReviewServices(cleanup,real.mutations,real.retirement,real.readEnvironment,real.changes)
    }
    override val calendarEditReviewServices by lazy {
        val real=createCalendarEditReviewServices()
        CalendarEditReviewServices(cleanup,real.mutations,real.retirement,ReaderIncomingCalendarSource(calendarReader),real.readEnvironment,real.changes)
    }
}

internal class ReviewCleanupGate(private val delegate:AtomicDraftStore):AtomicDraftStore by delegate {
    val deleted=CompletableDeferred<DraftRecord>()
    val release=CompletableDeferred<Unit>()
    var armed=false
    var cleanupCalls=0
    override suspend fun compareAndSet(id:String,expected:DraftRecord?,replacement:DraftRecord?):DraftChangeResult {
        if(!armed || expected==null || replacement!=null ||
            !(id.startsWith("incoming:") || id.startsWith("incoming-edit:"))) return delegate.compareAndSet(id,expected,replacement)
        return withContext(NonCancellable) {
            cleanupCalls++
            val result=delegate.compareAndSet(id,expected,null)
            check(result==DraftChangeResult.Applied)
            armed=false
            deleted.complete(expected)
            release.await()
            result
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=ReviewCleanupLifecycleApplication::class,qualifiers="en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateReviewCleanupLifecycleJourneyTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val start=Instant.parse("2028-01-03T09:00:00Z")
    private val title="Synthetic lifecycle review appointment"

    @Test fun createCleanupAppliedBeforeStopStillAcknowledgesItsKnownSaveAfterResume()=fixture {f->
        verifyCleanupAcrossStop(f,edit=false,successor=false)
    }
    @Test fun createCleanupCannotDeleteASuccessorCreatedWhileItsAcknowledgmentIsDelayed()=fixture {f->
        verifyCleanupAcrossStop(f,edit=false,successor=true)
    }
    @Test fun editCleanupAppliedBeforeStopStillAcknowledgesItsKnownSaveAfterResume()=fixture(seed=true) {f->
        verifyCleanupAcrossStop(f,edit=true,successor=false)
    }
    @Test fun editCleanupCannotDeleteASuccessorCreatedWhileItsAcknowledgmentIsDelayed()=fixture(seed=true) {f->
        verifyCleanupAcrossStop(f,edit=true,successor=true)
    }

    private fun verifyCleanupAcrossStop(f:Fixture,edit:Boolean,successor:Boolean) {
        val host:ActivityController<out ComponentActivity>
        if(edit) {
            val info=runBlocking {f.app.calendarReader.readEventNavigationInfo(7)} as CalendarReadState.Ready
            val value=requireNotNull(info.value)
            val request=CalendarEditReviewRequest(UUID.randomUUID().toString(),EventRef(CalendarId(2),7,start,start.plusSeconds(3600)),
                CalendarEntryBinding(7,value.calendar,value.providerUid),seed(),ZoneId.of("UTC"))
            host=launch(f,CalendarEditReviewActivity::class.java,CalendarEditReviewActivity.intent(f.app,request))
            shown(f.app.getString(R.string.incoming_edit_review_apply))
            compose.onNodeWithTag("incoming_edit_apply").performScrollTo().performClick()
            shown(f.app.getString(R.string.events_editor_edit))
        } else {
            host=launch(f,CalendarSeedReviewActivity::class.java,CalendarSeedReviewActivity.intent(f.app,UUID.randomUUID().toString(),seed(),ZoneId.of("UTC")))
            shown(f.app.getString(R.string.events_editor_create))
        }
        val enabledSave=hasText("Save") and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) {compose.onAllNodes(enabledSave).fetchSemanticsNodes().size==1}
        assertEquals(0,f.provider.batchCount)
        f.app.cleanup.armed=true
        compose.onNode(enabledSave).performClick()
        compose.waitUntil(15_000) {f.app.cleanup.deleted.isCompleted}
        val deleted=runBlocking {f.app.cleanup.deleted.await()}
        val document=OrdinaryDraftDocument.decode(deleted)
        assertTrue(document.hasRequest)
        assertEquals(1,f.provider.batchCount)
        assertNull(runBlocking {f.app.calendarLocalStore.load(deleted.id)})
        assertFalse(host.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED,shadowOf(host.get()).resultCode)
        val replacement=if(successor) document.copy(generation=UUID.randomUUID().toString()).record(deleted.updatedAt.plusNanos(1)) else null
        replacement?.let {assertEquals(DraftChangeResult.Applied,runBlocking {f.app.calendarLocalStore.compareAndSet(it.id,null,it)})}
        // Both transitions happen while the exact local deletion acknowledgment is still gated.
        compose.runOnUiThread {host.pause().stop()}
        compose.waitForIdle()
        compose.runOnUiThread {host.restart().start().resume()}
        compose.waitForIdle()
        assertEquals(replacement,runBlocking {f.app.calendarLocalStore.load(deleted.id)})
        assertFalse(host.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED,shadowOf(host.get()).resultCode)
        assertEquals(1,f.provider.batchCount)
        f.app.cleanup.release.complete(Unit)
        compose.waitUntil(15_000) {host.get().isFinishing}
        assertEquals(Activity.RESULT_OK,shadowOf(host.get()).resultCode)
        assertEquals(replacement,runBlocking {f.app.calendarLocalStore.load(deleted.id)})
        assertEquals(1,f.app.cleanup.cleanupCalls)
        assertEquals(1,f.provider.batchCount)
        assertEquals("VERIFIED",f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT phase FROM mutation_journal WHERE id=?",arrayOf(document.operationId)).use {assertTrue(it.moveToFirst());it.getString(0)})
    }
    private fun seed()=CalendarDraftSeed(CalendarOccurrenceHints(start,start.plusSeconds(3600),false),title,"Synthetic room","Synthetic notes",emptyList(),null,ZoneId.of("UTC"),null,null)
    private fun shown(text:String)=compose.waitUntil(15_000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun <A:ComponentActivity> launch(f:Fixture,type:Class<A>,intent:Intent)=Robolectric.buildActivity(type,intent).also {compose.runOnUiThread {it.setup().visible()};f.activities+=it}
    private fun stop(host:ActivityController<out ComponentActivity>) {compose.runOnUiThread {host.pause().stop().destroy()}}
    private class Fixture(val app:ReviewCleanupLifecycleApplication,val provider:NativeRecurringCreateJourneyProvider) {val activities=mutableListOf<ActivityController<out ComponentActivity>>()}
    private fun fixture(seed:Boolean=false,block:(Fixture)->Unit) {
        val app=RuntimeEnvironment.getApplication() as ReviewCleanupLifecycleApplication
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val provider=NativeRecurringCreateJourneyProvider(allowUpdates=true)
        if(seed)provider.seedOneOff(start,start.plusSeconds(3600),"Synthetic original lifecycle source")
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(),SavedReminderSettings());app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible=false,theme=SettingsTheme.LIGHT,displayZone=DisplayZoneOption.Fixed(ZoneId.of("UTC"))))
            val calendars=(app.calendarReader.readCalendars() as CalendarReadState.Ready).value
            assertTrue(app.settings.setDefaultCalendar(CalendarId(2),calendars))
        }
        val f=Fixture(app,provider)
        try {block(f)}finally {app.cleanup.release.complete(Unit);f.activities.asReversed().forEach {if(!it.get().isDestroyed)stop(it)};provider.db.close()}
    }
}
