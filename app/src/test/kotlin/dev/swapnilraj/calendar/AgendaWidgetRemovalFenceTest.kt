package dev.swapnilraj.calendar

import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgendaWidgetRemovalFenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private inner class Fixture(var hook: (Set<Int>) -> Map<Int, WidgetEpoch>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val enqueued = mutableListOf<WidgetEpoch>(); var reads = 0
        val runtime = AgendaWidgetRuntime(AgendaWidgetSnapshotReader { reads++; CalendarReadState.Unavailable },
            object : AgendaWidgetSettings {
                override suspend fun configuration(widgetId: Int) = AgendaWidgetConfigurationState.Ready(AgendaWidgetConfiguration("old"), WidgetEpoch(widgetId, "old-$widgetId"))
                override suspend fun lifecycle(widgetId: Int) = WidgetLifecycleProof.Verified(WidgetEpoch(widgetId, "old-$widgetId"))
                override suspend fun environment(): CalendarReadState<AgendaWidgetEnvironment> = CalendarReadState.Unavailable
            }, object : AgendaWidgetConfigurationWriter {
                override fun enqueueRemoval(epoch: WidgetEpoch): Deferred<WidgetRemovalReceipt> {
                    enqueued += epoch; return CompletableDeferred(WidgetRemovalReceipt.Committed(epoch))
                }
                override fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration): Deferred<WidgetSaveReceipt> = error("No saves")
            }, object : AgendaWidgetRefreshScheduler { override fun requestRefresh(widgetIds: Set<Int>) = true },
            object : AgendaWidgetHost {
                override fun widgetIds() = setOf(1, 2)
                override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
                override fun publish(widgetId: Int, views: RemoteViews) = Unit
            }, AgendaWidgetRenderer(context, ComponentName(context.packageName, "SyntheticWidgetActivity")), scope,
            removalEpochs = { hook(it) })
    }
    @Test fun fenceCalledOnceForWholeSetAndOnlyItsExactEpochsAreEnqueued() = runBlocking {
        var calls = 0
        val issued = mapOf(1 to WidgetEpoch(1, "first"), 2 to WidgetEpoch(2, "second"))
        val f = Fixture { ids -> calls++; assertEquals(setOf(1, 2), ids); issued }
        var finished = 0; f.runtime.removed(setOf(1, 2)) { finished++ }.join()
        assertEquals(1, calls); assertEquals(issued.values.toSet(), f.enqueued.toSet()); assertEquals(1, finished)
        f.scope.cancel()
    }
    @Test fun failedPartialOrMismatchedFenceBlocksWholeBatchAndNeverAdoptsOldReadProof() = runBlocking {
        val cases: List<(Set<Int>) -> Map<Int, WidgetEpoch>> = listOf(
            { error("Failed persistence") }, { mapOf(1 to WidgetEpoch(1, "one")) },
            { mapOf(1 to WidgetEpoch(2, "one"), 2 to WidgetEpoch(2, "two")) },
            { mapOf(1 to WidgetEpoch(1, "duplicate"), 2 to WidgetEpoch(2, "duplicate")) })
        for (hook in cases) {
            val f = Fixture(hook); var finished = 0
            f.runtime.removed(setOf(1, 2)) { finished++ }.join()
            assertEquals(1, finished); assertTrue(f.enqueued.isEmpty())
            for (id in setOf(1, 2)) assertEquals(WidgetConfigurationSessionResult.Unavailable, f.runtime.beginConfiguration(id))
            assertFalse(f.runtime.refresh()); assertEquals(0, f.reads)
            f.scope.cancel()
        }
    }
    @Test fun oldKnownEpochRejectedAndSuccessfulNewBatchCanRecoverEvenWithCancelledObserver() = runBlocking {
        val f = Fixture { ids -> ids.associateWith { WidgetEpoch(it, "old-$it") } }
        f.runtime.beginConfiguration(1); f.runtime.beginConfiguration(2)
        f.runtime.removed(setOf(1, 2)).join(); assertTrue(f.enqueued.isEmpty())
        f.hook = { ids -> ids.associateWith { WidgetEpoch(it, "fresh-$it") } }
        f.scope.cancel(); var finished = 0
        f.runtime.removed(setOf(1, 2)) { finished++ }.join()
        assertEquals(1, finished); assertEquals(2, f.enqueued.size)
        assertTrue(f.runtime.beginConfiguration(1) is WidgetConfigurationSessionResult.Ready)
    }
}
