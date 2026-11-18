package dev.swapnilraj.calendar

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgendaWidgetConfigurationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val initial = WidgetEpoch(1, "verified-binding-before-restore")
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val original = AgendaWidgetConfiguration("old-config-generation", privacy = WidgetPrivacy.EVENT_TITLES)
    private class Paused : CoroutineDispatcher() {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks += block }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private sealed class Operation {
        class Remove(val epoch: WidgetEpoch, val receipt: CompletableDeferred<WidgetRemovalReceipt> = CompletableDeferred()) : Operation()
        class Save(val epoch: WidgetEpoch, val config: AgendaWidgetConfiguration,
            val receipt: CompletableDeferred<WidgetSaveReceipt> = CompletableDeferred()) : Operation()
    }
    /** Controllable actor-port fixture, not a DataStore implementation or durability proof. */
    private inner class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
        var row: AgendaWidgetConfigurationState = AgendaWidgetConfigurationState.Ready(original, initial)
        var proof: WidgetLifecycleProof = WidgetLifecycleProof.Verified(initial)
        var durableEpoch = initial
        val operations = mutableListOf<Operation>()
        var reads = 0
        val views = mutableListOf<RemoteViews>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val runtime = AgendaWidgetRuntime(AgendaWidgetSnapshotReader { request ->
            reads++
            val ref = EventRef(CalendarId(1), 2, now.plusSeconds(60), now.plusSeconds(3600))
            CalendarReadState.Ready(CalendarSnapshot(request, emptyList(), listOf(
                EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Old account synthetic secret", 0xff123456.toInt()))))
        }, object : AgendaWidgetSettings {
            override suspend fun configuration(widgetId: Int) = row
            override suspend fun environment() = CalendarReadState.Ready(AgendaWidgetEnvironment(
                CalendarSelection.Explicit(setOf(CalendarId(1))), ZoneOffset.UTC, false, true))
            override suspend fun lifecycle(widgetId: Int) = proof
        }, object : AgendaWidgetConfigurationWriter {
            override fun enqueueRemoval(epoch: WidgetEpoch): Deferred<WidgetRemovalReceipt> {
                proof = WidgetLifecycleProof.Verified(epoch)
                return Operation.Remove(epoch).also { operations += it }.receipt
            }
            override fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration): Deferred<WidgetSaveReceipt> =
                Operation.Save(epoch, configuration).also { operations += it }.receipt
        }, object : AgendaWidgetRefreshScheduler {
            override fun requestRefresh(widgetIds: Set<Int>) = true
        }, object : AgendaWidgetHost {
            override fun widgetIds() = setOf(1)
            override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
            override fun publish(widgetId: Int, views: RemoteViews) { this@Fixture.views += views }
        }, AgendaWidgetRenderer(context, ComponentName(context.packageName, "SyntheticWidgetActivity")), scope,
            Clock.fixed(now, ZoneOffset.UTC), 100)
        suspend fun session() = (runtime.beginConfiguration(1) as WidgetConfigurationSessionResult.Ready).session
        fun remove(index: Int = operations.lastIndex): Operation.Remove = operations[index] as Operation.Remove
        fun save(index: Int = operations.lastIndex): Operation.Save = operations[index] as Operation.Save
        fun commitRemoval(index: Int = operations.lastIndex) {
            val op = remove(index)
            durableEpoch = op.epoch; row = AgendaWidgetConfigurationState.SetupRequired
            op.receipt.complete(WidgetRemovalReceipt.Committed(op.epoch))
        }
        fun commitSave(index: Int = operations.lastIndex, deliver: Boolean = true) {
            val op = save(index)
            if (durableEpoch != op.epoch || proof != WidgetLifecycleProof.Verified(op.epoch)) {
                op.receipt.complete(WidgetSaveReceipt.Stale); return
            }
            row = AgendaWidgetConfigurationState.Ready(op.config, op.epoch)
            if (deliver) op.receipt.complete(WidgetSaveReceipt.Committed(op.epoch, op.config.generation))
        }
        fun text(): String {
            val root = views.last().apply(context, FrameLayout(context))
            fun collect(view: View): String = buildString {
                if (view is TextView) append(view.text)
                if (view is ViewGroup) for (i in 0 until view.childCount) append(collect(view.getChildAt(i)))
            }
            return collect(root)
        }
        fun close() = scope.cancel()
    }

    @Test fun delayedCleanupIsEnqueuedBeforeObserverLaunchAndSaveCannotOvertakeIt() = runBlocking {
        val paused = Paused(); val f = Fixture(paused)
        f.runtime.removed(setOf(1))
        assertEquals(1, f.operations.size)
        val session = f.session()
        val saving = async { f.runtime.saveFreshConfiguration(session, WidgetConfigurationChoice()) }
        yield(); assertEquals(1, f.operations.size)
        f.commitRemoval(0); yield()
        assertEquals(2, f.operations.size)
        f.commitSave(1)
        val saved = saving.await() as WidgetConfigurationSaveResult.Saved
        assertEquals(WidgetPrivacy.BUSY_ONLY, saved.configuration.privacy)
        assertNotEquals(original.generation, saved.configuration.generation)
        f.runtime.refresh(); assertTrue(f.text().contains("Busy"))
        paused.runAll() // The old observer cannot replace this accepted save with a setup screen.
        assertTrue(f.text().contains("Busy")); assertFalse(f.text().contains("synthetic secret"))
        f.close()
    }

    @Test fun timeoutFinishesBroadcastButSharedNoncooperativeCleanupStillBlocksFreshWrite() = runBlocking {
        val f = Fixture(); var finished = 0
        f.runtime.removed(setOf(1)) { finished++ }
        shadowOf(Looper.getMainLooper()).idleFor(8_001, TimeUnit.MILLISECONDS)
        assertEquals(1, finished)
        val removal = f.remove(0)
        assertFalse(removal.receipt.isCompleted); assertFalse(removal.receipt.isCancelled)
        val session = f.session()
        assertEquals(WidgetConfigurationSaveResult.Unavailable, f.runtime.saveFreshConfiguration(session, WidgetConfigurationChoice()))
        assertEquals(1, f.operations.size); assertFalse(removal.receipt.isCancelled)
        f.commitRemoval(0)
        val saving = async { f.runtime.saveFreshConfiguration(session, WidgetConfigurationChoice()) }
        yield(); f.commitSave(1)
        assertTrue(saving.await() is WidgetConfigurationSaveResult.Saved)
        assertEquals(1, finished)
        f.close()
    }

    @Test fun restoredSameIdRequiresNewEpochAndOldSessionOrCleanupCannotAdoptTitles() = runBlocking {
        val f = Fixture()
        f.runtime.removed(setOf(1)); val oldSession = f.session()
        f.runtime.removed(setOf(1)); val current = f.session()
        assertNotEquals(oldSession.epoch, current.epoch)
        assertEquals(WidgetConfigurationSaveResult.Stale, f.runtime.saveFreshConfiguration(oldSession, WidgetConfigurationChoice(privacy = WidgetPrivacy.EVENT_TITLES)))
        f.commitRemoval(0); f.runtime.refresh()
        assertTrue(f.text().contains("Choose calendars")); assertEquals(0, f.reads)
        f.commitRemoval(1)
        val saving = async { f.runtime.saveFreshConfiguration(current, WidgetConfigurationChoice()) }
        yield(); f.commitSave(2)
        assertTrue(saving.await() is WidgetConfigurationSaveResult.Saved)
        f.runtime.refresh(); assertTrue(f.text().contains("Busy"))
        f.close()
    }

    @Test fun newerCleanupReceiptCannotLetSaveSkipAnOlderUnsettledBarrier() = runBlocking {
        val f = Fixture()
        f.runtime.removed(setOf(1)); f.runtime.removed(setOf(1))
        f.commitRemoval(1)
        val saving = async { f.runtime.saveFreshConfiguration(f.session(), WidgetConfigurationChoice()) }
        yield(); assertEquals(2, f.operations.size)
        // Older IO has settled, but its completion delivery is delayed behind the newer receipt.
        f.remove(0).receipt.complete(WidgetRemovalReceipt.Committed(f.remove(0).epoch))
        yield(); assertEquals(3, f.operations.size)
        f.commitSave(2)
        assertTrue(saving.await() is WidgetConfigurationSaveResult.Saved)
        f.runtime.refresh(); assertTrue(f.text().contains("Busy"))
        f.close()
    }

    @Test fun removalAfterDurableSaveBeforeReceiptDeliveryMakesSaveStaleAndKeepsQuarantine() = runBlocking {
        val f = Fixture(); val session = f.session()
        val saving = async { f.runtime.saveFreshConfiguration(session, WidgetConfigurationChoice(privacy = WidgetPrivacy.EVENT_TITLES)) }
        yield(); val op = f.save(0)
        f.commitSave(0, deliver = false)
        f.runtime.removed(setOf(1))
        op.receipt.complete(WidgetSaveReceipt.Committed(op.epoch, op.config.generation))
        assertEquals(WidgetConfigurationSaveResult.Stale, saving.await())
        f.runtime.refresh(); assertFalse(f.text().contains("synthetic secret")); assertEquals(0, f.reads)
        f.commitRemoval(1); f.runtime.refresh()
        assertTrue(f.text().contains("Choose calendars"))
        f.close()
    }

    @Test fun overlappingSaveRetriesRejectOldReceiptEvenWithinSameLifecycleEpoch() = runBlocking {
        val f = Fixture(); val session = f.session()
        val first = async { f.runtime.saveFreshConfiguration(session, WidgetConfigurationChoice(privacy = WidgetPrivacy.EVENT_TITLES)) }
        yield()
        val second = async { f.runtime.saveFreshConfiguration(session, WidgetConfigurationChoice()) }
        yield(); f.commitSave(0)
        assertEquals(WidgetConfigurationSaveResult.Stale, first.await())
        f.runtime.refresh(); assertFalse(f.text().contains("synthetic secret"))
        f.commitSave(1)
        assertTrue(second.await() is WidgetConfigurationSaveResult.Saved)
        f.runtime.refresh(); assertTrue(f.text().contains("Busy"))
        f.close()
    }

    @Test fun forgedOrUnprovenSaveReceiptNeverClearsRemovalQuarantine() = runBlocking {
        val f = Fixture(); f.runtime.removed(setOf(1)); f.commitRemoval(0)
        val saving = async { f.runtime.saveFreshConfiguration(f.session(), WidgetConfigurationChoice(privacy = WidgetPrivacy.EVENT_TITLES)) }
        yield(); val op = f.save(1)
        // Receipt matches, but the store does not contain that committed configuration.
        op.receipt.complete(WidgetSaveReceipt.Committed(op.epoch, op.config.generation))
        assertEquals(WidgetConfigurationSaveResult.Unavailable, saving.await())
        f.runtime.refresh(); assertEquals(0, f.reads)
        assertFalse(f.text().contains("synthetic secret"))
        f.close()
    }

    @Test fun missingMismatchedOrUnavailableLifecycleProofNeverReadsStoredTitles() = runBlocking {
        for (proof in listOf(WidgetLifecycleProof.SetupRequired, WidgetLifecycleProof.Verified(WidgetEpoch(1, "replacement-binding")), WidgetLifecycleProof.Unavailable)) {
            val f = Fixture(); f.proof = proof
            f.runtime.refresh(); assertEquals(0, f.reads); assertFalse(f.text().contains("synthetic secret"))
            f.close()
        }
    }

    @Test fun cleanupFailureOrCancelledObserverDoesNotAuthorizeSaveOrCancelActorReceipt() = runBlocking {
        val f = Fixture()
        val observing = f.runtime.removed(setOf(1)); val op = f.remove(0)
        observing.cancel(); observing.join()
        assertFalse(op.receipt.isCancelled)
        op.receipt.complete(WidgetRemovalReceipt.Unavailable)
        assertEquals(WidgetConfigurationSaveResult.Unavailable, f.runtime.saveFreshConfiguration(f.session(), WidgetConfigurationChoice()))
        assertEquals(1, f.operations.size)
        f.close()
    }
}
