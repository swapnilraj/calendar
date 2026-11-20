package dev.swapnilraj.calendar

import android.app.Application
import android.content.ComponentName
import android.widget.RemoteViews
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetSetupContextTest {
    private class Fixture {
        val own = mutableSetOf(1, 2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fences = AtomicInteger()
        var fail = false
        var duringFence: (() -> Unit)? = null
        val context = RuntimeEnvironment.getApplication()
        val runtime = AgendaWidgetRuntime(AgendaWidgetSnapshotReader { CalendarReadState.Ready(CalendarSnapshot(it, emptyList(), emptyList())) },
            object : AgendaWidgetSettings {
                override suspend fun configuration(widgetId: Int) = AgendaWidgetConfigurationState.SetupRequired
                override suspend fun lifecycle(widgetId: Int) = WidgetLifecycleProof.SetupRequired
                override suspend fun environment() = CalendarReadState.Ready(AgendaWidgetEnvironment(CalendarSelection.ProviderVisible, ZoneOffset.UTC, false, true))
            }, object : AgendaWidgetConfigurationWriter {
                override fun enqueueRemoval(epoch: WidgetEpoch) = CompletableDeferred<WidgetRemovalReceipt>(WidgetRemovalReceipt.Committed(epoch))
                override fun enqueueSave(epoch: WidgetEpoch, configuration: AgendaWidgetConfiguration) = CompletableDeferred<WidgetSaveReceipt>(WidgetSaveReceipt.Unavailable)
            }, object : AgendaWidgetRefreshScheduler { override fun requestRefresh(widgetIds: Set<Int>) = true },
            object : AgendaWidgetHost {
                override fun widgetIds() = own.toSet()
                override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
                override fun publish(widgetId: Int, views: RemoteViews) = Unit
            }, AgendaWidgetRenderer(context, ComponentName(context.packageName, "SyntheticWidgetActivity")), scope,
            removalEpochs = { ids ->
                val serial = fences.incrementAndGet(); duringFence?.invoke()
                if (fail) error("Synthetic fence failure")
                ids.associateWith { WidgetEpoch(it, "fence-$it-$serial") }
            })
        fun close() = scope.cancel()
    }

    @Test fun refreshPreservesContextButKnownFenceResurrectionForeignRuntimeAndSecondClaimDoNot() = runBlocking {
        val f = Fixture(); val foreign = Fixture()
        try {
            val first = requireNotNull(f.runtime.captureSetupContext(1))
            val other = requireNotNull(f.runtime.captureSetupContext(2))
            f.runtime.invalidate(); f.runtime.refresh()
            assertTrue(f.runtime.isSetupContextCurrent(first))
            assertEquals(WidgetSetupClaim.Stale, foreign.runtime.claimSetup(first))
            f.runtime.removed(setOf(1)).join(); f.own.remove(1); f.own.add(1)
            assertEquals(WidgetSetupClaim.Stale, f.runtime.claimSetup(first)); assertEquals(1, f.fences.get())
            assertTrue(f.runtime.isSetupContextCurrent(other))
            val fresh = requireNotNull(f.runtime.captureSetupContext(1))
            assertTrue(f.runtime.claimSetup(fresh) is WidgetSetupClaim.Claimed)
            assertEquals(WidgetSetupClaim.Stale, f.runtime.claimSetup(fresh)); assertEquals(2, f.fences.get())
        } finally { f.close(); foreign.close() }
    }

    @Test fun failedKnownFenceStillInvalidatesTheOldFormWithoutGuessingAnEpoch() = runBlocking {
        val f = Fixture()
        try {
            val old = requireNotNull(f.runtime.captureSetupContext(1)); f.fail = true
            f.runtime.removed(setOf(1)).join()
            assertFalse(f.runtime.isSetupContextCurrent(old)); assertEquals(WidgetSetupClaim.Stale, f.runtime.claimSetup(old))
            val fresh = requireNotNull(f.runtime.captureSetupContext(1))
            assertEquals(WidgetSetupClaim.Unavailable, f.runtime.claimSetup(fresh))
        } finally { f.close() }
    }

    @Test fun simultaneousClaimsCannotBothCompareBeforeTheSameQuarantine() {
        val f = Fixture(); val workers = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val form = requireNotNull(f.runtime.captureSetupContext(1))
            f.duringFence = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            val first = workers.submit<WidgetSetupClaim> { f.runtime.claimSetup(form) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = workers.submit<WidgetSetupClaim> { f.runtime.claimSetup(form) }
            try { second.get(100, TimeUnit.MILLISECONDS); fail("Claim must wait for the publication/fence boundary") }
            catch (_: TimeoutException) { }
            assertEquals(1, f.fences.get())
            release.countDown()
            assertTrue(first.get(3, TimeUnit.SECONDS) is WidgetSetupClaim.Claimed)
            assertEquals(WidgetSetupClaim.Stale, second.get(3, TimeUnit.SECONDS)); assertEquals(1, f.fences.get())
        } finally { release.countDown(); workers.shutdownNow(); f.close() }
    }
}
