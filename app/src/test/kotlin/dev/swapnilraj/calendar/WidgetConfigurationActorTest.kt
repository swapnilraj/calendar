package dev.swapnilraj.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.data.widgets.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetConfigurationActorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = WidgetEpoch(1, "confirmed-binding")
    private val config = AgendaWidgetConfiguration("fresh-generation", privacy = WidgetPrivacy.EVENT_TITLES)
    private class Gate(private val actual: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = actual.data
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var gated = true; var fail = false; var updates = 0
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            updates++
            if (gated) { entered.complete(Unit); release.await() }
            if (fail) throw IOException("Synthetic storage failure")
            return actual.updateData(transform)
        }
    }
    private class Disk(val file: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        suspend fun close() { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private fun disk(name: String = "widgets") = Disk(File(temporary.root, "$name.preferences_pb"))
    private suspend fun WidgetConfigurationActor.seed() {
        supplyValidatedEpoch(epoch, true)
        assertEquals(WidgetRemovalReceipt.Committed(epoch), enqueueRemoval(epoch).await())
        assertEquals(WidgetSaveReceipt.Committed(epoch, config.generation), enqueueSave(epoch, config).await())
    }

    @Test fun actualFileReopenRequiresNewExplicitValidationBeforeShowingSavedTitles() = runBlocking {
        val first = disk()
        first.store.edit { it[stringPreferencesKey("unrelated")] = "preserved" }
        val actor = WidgetConfigurationActor(first.store, "install")
        actor.seed(); assertTrue(first.file.exists())
        actor.closeAndJoin(); first.close()
        val reopened = Disk(first.file); val reader = WidgetConfigurationActor(reopened.store, "install")
        assertEquals(WidgetLifecycleProof.SetupRequired, reader.lifecycle(1))
        assertEquals(AgendaWidgetConfigurationState.SetupRequired, reader.configuration(1))
        reader.supplyValidatedEpoch(epoch, true)
        assertEquals(WidgetLifecycleProof.Verified(epoch), reader.lifecycle(1))
        assertEquals(AgendaWidgetConfigurationState.Ready(config, epoch), reader.configuration(1))
        assertEquals("preserved", reopened.store.data.first()[stringPreferencesKey("unrelated")])
        reader.closeAndJoin(); reopened.close()
    }

    @Test fun synchronousFifoAcceptanceSurvivesCallerCancellationAndDelayedIo() = runBlocking {
        val disk = disk(); val gate = Gate(disk.store); val actor = WidgetConfigurationActor(gate, "install")
        actor.supplyValidatedEpoch(epoch, true)
        val removal = actor.enqueueRemoval(epoch)
        gate.entered.await()
        val saved = actor.enqueueSave(epoch, config)
        val caller = launch { saved.await() }; yield(); caller.cancelAndJoin()
        assertFalse(saved.isCancelled); assertFalse(removal.isCompleted)
        assertEquals(AgendaWidgetConfigurationState.Unavailable, actor.configuration(1))
        gate.release.complete(Unit)
        assertEquals(WidgetRemovalReceipt.Committed(epoch), removal.await())
        assertEquals(WidgetSaveReceipt.Committed(epoch, config.generation), saved.await())
        assertEquals(AgendaWidgetConfigurationState.Ready(config, epoch), actor.configuration(1))
        actor.closeAndJoin(); disk.close()
    }

    @Test fun replayedRemovalPreservesLaterSaveAndStaleEpochCannotOverwriteReplacement() = runBlocking {
        val disk = disk(); val actor = WidgetConfigurationActor(disk.store, "install"); actor.seed()
        assertEquals(WidgetRemovalReceipt.Committed(epoch), actor.enqueueRemoval(epoch).await())
        assertEquals(WidgetSaveReceipt.Stale, actor.enqueueSave(epoch, config.copy(privacy = WidgetPrivacy.BUSY_ONLY)).await())
        assertEquals(AgendaWidgetConfigurationState.Ready(config, epoch), actor.configuration(1))
        val replacement = WidgetEpoch(1, "restored-new-binding")
        actor.supplyValidatedEpoch(replacement, true)
        assertEquals(WidgetRemovalReceipt.Superseded, actor.enqueueRemoval(epoch).await())
        assertEquals(WidgetSaveReceipt.Stale, actor.enqueueSave(epoch, config).await())
        assertEquals(WidgetRemovalReceipt.Committed(replacement), actor.enqueueRemoval(replacement).await())
        assertEquals(AgendaWidgetConfigurationState.SetupRequired, actor.configuration(1))
        val fresh = AgendaWidgetConfiguration("restored-fresh-generation")
        actor.enqueueSave(replacement, fresh).await()
        assertEquals(AgendaWidgetConfigurationState.Ready(fresh, replacement), actor.configuration(1))
        actor.closeAndJoin(); disk.close()
    }

    @Test fun failedWriteLeavesPriorFileUntouchedAndReceiptNeverClaimsCommitted() = runBlocking {
        val disk = disk(); val seeded = WidgetConfigurationActor(disk.store, "install"); seeded.seed(); seeded.closeAndJoin()
        val before = disk.file.readBytes(); val gate = Gate(disk.store).apply { gated = false; fail = true }
        val actor = WidgetConfigurationActor(gate, "install"); actor.supplyValidatedEpoch(epoch, true)
        assertEquals(WidgetSaveReceipt.Unavailable, actor.enqueueSave(epoch, AgendaWidgetConfiguration("new")).await())
        assertArrayEquals(before, disk.file.readBytes())
        actor.closeAndJoin(); disk.close()
    }

    @Test fun namespaceMismatchAndCorruptFutureDocumentNeverRepairOrAdoptStoredValues() = runBlocking {
        val disk = disk(); val actor = WidgetConfigurationActor(disk.store, "install"); actor.seed(); actor.closeAndJoin()
        val before = disk.file.readBytes()
        val wrong = WidgetConfigurationActor(disk.store, "other-install"); wrong.supplyValidatedEpoch(epoch, true)
        assertEquals(WidgetLifecycleProof.Unavailable, wrong.lifecycle(1))
        assertEquals(WidgetRemovalReceipt.Unavailable, wrong.enqueueRemoval(epoch).await())
        assertArrayEquals(before, disk.file.readBytes()); wrong.closeAndJoin()
        disk.store.edit { it[WidgetConfigurationDocuments.key] = """{"version":2,"namespace":"install","rows":[]}""" }
        val corrupt = WidgetConfigurationActor(disk.store, "install"); corrupt.supplyValidatedEpoch(epoch, true)
        assertEquals(WidgetSaveReceipt.Unavailable, corrupt.enqueueSave(epoch, config).await())
        assertEquals(AgendaWidgetConfigurationState.Unavailable, corrupt.configuration(1))
        assertTrue(disk.file.readBytes().isNotEmpty())
        corrupt.closeAndJoin(); disk.close()
    }

    @Test fun changedBindingWhileIoIsPausedRejectsOldSaveBeforeDurableReplacement() = runBlocking {
        val disk = disk(); val seeded = WidgetConfigurationActor(disk.store, "install"); seeded.seed(); seeded.closeAndJoin()
        val gate = Gate(disk.store); val actor = WidgetConfigurationActor(gate, "install")
        actor.supplyValidatedEpoch(epoch, true)
        val old = actor.enqueueSave(epoch, AgendaWidgetConfiguration("old-pending")); gate.entered.await()
        val replacement = WidgetEpoch(1, "confirmed-replacement")
        actor.supplyValidatedEpoch(replacement, true)
        val reset = actor.enqueueRemoval(replacement)
        val fresh = AgendaWidgetConfiguration("new-generation")
        val saved = actor.enqueueSave(replacement, fresh)
        assertEquals(AgendaWidgetConfigurationState.Unavailable, actor.configuration(1))
        gate.release.complete(Unit)
        assertEquals(WidgetSaveReceipt.Stale, old.await())
        assertEquals(WidgetRemovalReceipt.Committed(replacement), reset.await())
        assertEquals(WidgetSaveReceipt.Committed(replacement, fresh.generation), saved.await())
        assertEquals(AgendaWidgetConfigurationState.Ready(fresh, replacement), actor.configuration(1))
        actor.closeAndJoin(); disk.close()
    }

    @Test fun corruptProtobufFileRemainsUnavailableWithoutAutomaticRepair() = runBlocking {
        val disk = disk(); val actor = WidgetConfigurationActor(disk.store, "install"); actor.seed(); actor.closeAndJoin(); disk.close()
        val corruptBytes = ByteArray(20) { 0xff.toByte() }; disk.file.writeBytes(corruptBytes)
        val reopened = Disk(disk.file); val reader = WidgetConfigurationActor(reopened.store, "install")
        reader.supplyValidatedEpoch(epoch, true)
        assertEquals(AgendaWidgetConfigurationState.Unavailable, reader.configuration(1))
        assertEquals(WidgetRemovalReceipt.Unavailable, reader.enqueueRemoval(epoch).await())
        assertArrayEquals(corruptBytes, disk.file.readBytes())
        reader.closeAndJoin(); reopened.close()
    }

    @Test fun inactiveOrRevokedBindingNeverProducesLifecycleProofOrAllowsSave() = runBlocking {
        val disk = disk(); val actor = WidgetConfigurationActor(disk.store, "install")
        actor.supplyValidatedEpoch(epoch, false)
        assertEquals(WidgetRemovalReceipt.Committed(epoch), actor.enqueueRemoval(epoch).await())
        assertEquals(WidgetSaveReceipt.Stale, actor.enqueueSave(epoch, config).await())
        assertEquals(WidgetLifecycleProof.SetupRequired, actor.lifecycle(1))
        actor.supplyValidatedEpoch(epoch, true); actor.enqueueSave(epoch, config).await()
        actor.revokeValidation(1)
        assertEquals(AgendaWidgetConfigurationState.SetupRequired, actor.configuration(1))
        actor.closeAndJoin(); disk.close()
    }

    @Test fun closeDrainsAcceptedCommandsAndFactoryUsesNoBackupDirectory() = runBlocking {
        val disk = disk(); val gate = Gate(disk.store); val actor = WidgetConfigurationActor(gate, "install")
        actor.supplyValidatedEpoch(epoch, true)
        val receipt = actor.enqueueRemoval(epoch); gate.entered.await()
        val closing = launch { actor.closeAndJoin() }; yield(); assertFalse(closing.isCompleted)
        gate.release.complete(Unit); closing.join()
        assertEquals(WidgetRemovalReceipt.Committed(epoch), receipt.await())
        assertEquals(WidgetSaveReceipt.Unavailable, actor.enqueueSave(epoch, config).await())
        disk.close()
        val context: Context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val privateStore = widgetConfigurationDataStore(context, scope)
        privateStore.edit { it[WidgetConfigurationDocuments.key] = "owned" }
        assertTrue(File(context.noBackupFilesDir, "agenda_widgets.preferences_pb").exists())
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
