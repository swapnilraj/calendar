package dev.swapnilraj.calendar

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.widgets.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAgendaWidgetBindingsTest {
    @get:Rule val files = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private class Markers : WidgetMarkerAccess {
        val own = mutableSetOf(1, 2); val values = mutableMapOf<Int, String>()
        var writes = 0; var failWrite = false; var failRead = false; var mismatch = false
        var onWrite: (() -> Unit)? = null
        override fun isOwnWidget(id: Int) = id in own
        override fun marker(id: Int): String? { if (failRead) throw IOException("Synthetic marker read failure"); return if (mismatch) "foreign-marker" else values[id] }
        override fun marker(id: Int, value: String) {
            onWrite?.invoke(); if (failWrite) throw IOException("Synthetic marker write failure")
            writes++; values[id] = value
        }
    }
    private inner class Fixture(val directory: File, val markers: Markers = Markers(), wired: Boolean = true) {
        val ledger = requireNotNull(WidgetBindingLedger.open(directory))
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = diskScope) { File(directory, "agenda_widgets.preferences_pb") }
        val actor = WidgetConfigurationActor(store, ledger.snapshot().namespace, { CalendarReadState.Ready(AgendaWidgetEnvironment(
            CalendarSelection.Explicit(setOf(CalendarId(1))), java.time.ZoneOffset.UTC, false, true)) })
        val bindings = AndroidAgendaWidgetBindings(ledger, actor, markers, scope)
        var reads = 0
        val host = object : AgendaWidgetHost {
            override fun widgetIds() = markers.own.toSet()
            override fun size(widgetId: Int) = AgendaWidgetSize(240, 240)
            override fun publish(widgetId: Int, views: RemoteViews) = Unit
        }
        val runtime = AgendaWidgetRuntime(AgendaWidgetSnapshotReader { request ->
            reads++; CalendarReadState.Ready(CalendarSnapshot(request, emptyList(), emptyList()))
        }, bindings, bindings, object : AgendaWidgetRefreshScheduler {
            override fun requestRefresh(widgetIds: Set<Int>) = true
        }, host, AgendaWidgetRenderer(context, ComponentName(context.packageName, "SyntheticWidgetActivity")), scope,
            removalEpochs = if (wired) bindings::quarantineBatch else { ids -> ids.associateWith { WidgetEpoch(it, "unfenced-$it") } })
        suspend fun setupAndSave(): Pair<WidgetEpoch, AgendaWidgetConfiguration> {
            val prepared = bindings.prepareForSetup(1, runtime) as WidgetBindingSetupResult.Prepared
            val session = (runtime.beginConfiguration(1) as WidgetConfigurationSessionResult.Ready).session
            val saved = runtime.saveFreshConfiguration(session, WidgetConfigurationChoice()) as WidgetConfigurationSaveResult.Saved
            return prepared.epoch to saved.configuration
        }
        suspend fun close() { actor.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin(); diskScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun explicitSetupWritesCandidateOnlyAfterDurableTombstoneThenVerifiesActiveAndSavesBusy() = runBlocking {
        val f = Fixture(files.root)
        f.markers.onWrite = {
            val diskLedger = requireNotNull(WidgetBindingLedger.open(files.root)).snapshot()
            assertEquals(WidgetBindingPhase.CANDIDATE, diskLedger.records.getValue(1).phase)
            val document = runBlocking { f.store.data.first()[WidgetConfigurationDocuments.key] }
            val decoded = WidgetConfigurationDocuments.decode(document, diskLedger.namespace) as WidgetDocumentRead.Ready
            assertNull(decoded.document.rows.getValue(1).configuration)
            assertEquals(diskLedger.records.getValue(1).epoch, decoded.document.rows.getValue(1).epoch)
        }
        val (epoch, config) = f.setupAndSave()
        assertEquals(WidgetPrivacy.BUSY_ONLY, config.privacy)
        assertEquals(WidgetBindingPhase.ACTIVE, f.ledger.snapshot().records.getValue(1).phase)
        assertEquals(WidgetLifecycleProof.Verified(epoch), f.bindings.lifecycle(1))
        assertEquals(AgendaWidgetConfigurationState.Ready(config, epoch), f.bindings.configuration(1))
        assertEquals(0, f.reads)
        f.runtime.refresh(); assertEquals(1, f.reads)
        f.close()
    }

    @Test fun matchingHostMarkerCanResumeAfterAppRestartButMissingRebootMarkerCannotBeRehydrated() = runBlocking {
        val markers = Markers(); val first = Fixture(files.root, markers)
        val (epoch, config) = first.setupAndSave(); val namespace = first.ledger.snapshot().namespace; first.close()
        val restarted = Fixture(files.root, markers)
        assertEquals(WidgetLifecycleProof.SetupRequired, restarted.actor.lifecycle(1))
        assertEquals(namespace, restarted.ledger.snapshot().namespace)
        assertEquals(AgendaWidgetConfigurationState.Ready(config, epoch), restarted.bindings.configuration(1))
        restarted.close()
        markers.values.clear(); val writes = markers.writes
        val rebooted = Fixture(files.root, markers)
        assertEquals(AgendaWidgetConfigurationState.SetupRequired, rebooted.bindings.configuration(1))
        rebooted.runtime.refresh(); assertEquals(0, rebooted.reads); assertEquals(writes, markers.writes)
        val (fresh, freshConfig) = rebooted.setupAndSave()
        assertNotEquals(epoch, fresh); assertNotEquals(config.generation, freshConfig.generation)
        rebooted.close()
    }

    @Test fun interruptedCandidateOrWrongReadbackNeverConfirmsAndObservationNeverMints() = runBlocking {
        for (writeFailure in listOf(true, false)) {
            val f = Fixture(files.newFolder())
            f.markers.failWrite = writeFailure; f.markers.mismatch = !writeFailure
            assertTrue(f.bindings.prepareForSetup(1, f.runtime) !is WidgetBindingSetupResult.Prepared)
            assertEquals(WidgetBindingPhase.CANDIDATE, f.ledger.snapshot().records.getValue(1).phase)
            val snapshot = f.ledger.snapshot(); val writes = f.markers.writes
            assertEquals(WidgetBindingObservation.SetupRequired, f.bindings.observe(1))
            assertEquals(snapshot, f.ledger.snapshot()); assertEquals(writes, f.markers.writes)
            f.runtime.refresh(); assertEquals(0, f.reads)
            f.close()
        }
    }

    @Test fun wholeBatchRestoreFenceRevokesOldProofBeforeEitherPerIdCleanupCanStart() = runBlocking {
        val f = Fixture(files.root); f.setupAndSave()
        val epochs = f.bindings.quarantineBatch(setOf(1, 2))
        val reopened = WidgetBindingLedger.open(files.root)!!.snapshot()
        assertTrue(setOf(1, 2).all { reopened.records.getValue(it).phase == WidgetBindingPhase.QUARANTINED })
        assertEquals(WidgetLifecycleProof.SetupRequired, f.actor.lifecycle(1))
        assertEquals(WidgetBindingObservation.SetupRequired, f.bindings.observe(1))
        for (epoch in epochs.values) assertEquals(WidgetRemovalReceipt.Committed(epoch), f.bindings.enqueueRemoval(epoch).await())
        assertEquals(AgendaWidgetConfigurationState.SetupRequired, f.bindings.configuration(1))
        f.close()
    }

    @Test fun unknownForeignOrUnwiredSetupCannotInventBindingAuthority() = runBlocking {
        val f = Fixture(files.root, wired = false)
        assertEquals(WidgetBindingSetupResult.Stale, f.bindings.prepareForSetup(99, f.runtime))
        assertEquals(WidgetBindingSetupResult.Unavailable, f.bindings.prepareForSetup(1, f.runtime))
        assertTrue(f.ledger.snapshot().records.isEmpty()); assertEquals(0, f.markers.writes)
        f.close()
    }

    @Test fun markerLossAfterConfigurationRevokesActorAndBlocksSaveAndCalendarReads() = runBlocking {
        val f = Fixture(files.root); val (epoch, _) = f.setupAndSave()
        f.markers.values[1] = "copied-wrong-installation-marker"
        assertEquals(WidgetLifecycleProof.SetupRequired, f.bindings.lifecycle(1))
        assertEquals(WidgetLifecycleProof.SetupRequired, f.actor.lifecycle(1))
        assertEquals(WidgetSaveReceipt.Stale, f.bindings.enqueueSave(epoch, AgendaWidgetConfiguration("not-accepted")).await())
        f.runtime.refresh(); assertEquals(0, f.reads)
        f.markers.failRead = true
        assertEquals(WidgetLifecycleProof.Unavailable, f.bindings.lifecycle(1))
        f.close()
    }

    @Test fun failedActiveCommitLeavesDurableCandidateAndNeverConfirmsAfterHostWrite() = runBlocking {
        val directory = files.newFolder("active-failure"); val f = Fixture(directory)
        val moved = File(files.root, "active-saved")
        f.markers.onWrite = {
            assertTrue(directory.renameTo(moved)); directory.writeText("not a directory")
        }
        assertEquals(WidgetBindingSetupResult.Unavailable, f.bindings.prepareForSetup(1, f.runtime))
        assertEquals(WidgetBindingObservation.Unavailable, f.bindings.observe(1))
        assertEquals(WidgetLifecycleProof.SetupRequired, f.actor.lifecycle(1))
        f.close()
        assertTrue(directory.delete()); assertTrue(moved.renameTo(directory))
        assertEquals(WidgetBindingPhase.CANDIDATE, WidgetBindingLedger.open(directory)!!.snapshot().records.getValue(1).phase)
    }

    @Test fun failedWholeBatchFenceRevokesExistingActorProofAndCompletesWithoutAnyNewAuthority() = runBlocking {
        val directory = files.newFolder("fence-failure"); val f = Fixture(directory); f.setupAndSave()
        val moved = File(files.root, "fence-saved")
        assertTrue(directory.renameTo(moved)); directory.writeText("not a directory")
        var finished = 0
        f.runtime.removed(setOf(1, 2)) { finished++ }.join()
        assertEquals(1, finished)
        assertEquals(WidgetBindingObservation.Unavailable, f.bindings.observe(1))
        assertEquals(WidgetLifecycleProof.SetupRequired, f.actor.lifecycle(1))
        assertEquals(WidgetConfigurationSessionResult.Unavailable, f.runtime.beginConfiguration(1))
        f.runtime.refresh(); assertEquals(0, f.reads)
        f.close()
        assertTrue(directory.delete()); assertTrue(moved.renameTo(directory))
    }

    @Test fun nativeAdapterChecksBothProviderAndOwnIdsAndWritesOnlyNamespacedOption() {
        val manager = AppWidgetManager.getInstance(context); val shadow = shadowOf(manager)
        val id = shadow.createWidget(AgendaWidgetProvider::class.java, R.layout.widget_agenda)
        shadow.putWidgetInfo(id, AppWidgetProviderInfo().apply { provider = ComponentName(context, AgendaWidgetProvider::class.java) })
        val access = AndroidWidgetMarkerAccess(context, AndroidAgendaWidgetHost(context))
        assertTrue(access.isOwnWidget(id))
        manager.updateAppWidgetOptions(id, android.os.Bundle().apply { putInt("existing-size", 240) })
        access.marker(id, "namespaced-marker")
        assertEquals("namespaced-marker", access.marker(id))
        assertEquals(240, manager.getAppWidgetOptions(id).getInt("existing-size"))
        shadow.putWidgetInfo(id, AppWidgetProviderInfo().apply { provider = ComponentName(context.packageName, "ForeignProvider") })
        assertFalse(access.isOwnWidget(id))
    }
}
