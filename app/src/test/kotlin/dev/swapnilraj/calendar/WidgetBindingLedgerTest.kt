package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.widgets.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetBindingLedgerTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun singleNamespaceAndWholeBatchQuarantineSurviveReopen() {
        val ledger = requireNotNull(WidgetBindingLedger.open(files.root))
        val namespace = ledger.snapshot().namespace
        val epochs = ledger.quarantine(setOf(1, 2))
        assertEquals(setOf(1, 2), epochs.keys)
        val reopened = requireNotNull(WidgetBindingLedger.open(files.root))
        assertEquals(namespace, reopened.snapshot().namespace)
        assertTrue(reopened.snapshot().records.values.all { it.phase == WidgetBindingPhase.QUARANTINED })
        assertEquals(epochs, reopened.snapshot().records.mapValues { it.value.epoch })
        assertNotEquals(epochs[1], reopened.quarantine(setOf(1))[1])
    }
    @Test fun missingLedgerWithExistingConfigurationArtifactsNeverCreatesAnotherNamespace() {
        for (suffix in listOf("", ".tmp", ".bak")) {
            val directory = files.newFolder()
            File(directory, "agenda_widgets.preferences_pb$suffix").writeText("existing")
            assertNull(WidgetBindingLedger.open(directory))
            assertFalse(File(directory, WidgetBindingLedger.FILE_NAME).exists())
        }
    }
    @Test fun malformedFutureOrDuplicateLedgerNeverRepairsIdentity() {
        for (text in listOf("", "{}", "null", """{"version":2,"namespace":"old","records":[]}""",
            """{"version":1,"namespace":"old","records":[{"id":1,"epoch":"a","phase":"ACTIVE"},{"id":1,"epoch":"b","phase":"ACTIVE"}]}""")) {
            val directory = files.newFolder(); val file = File(directory, WidgetBindingLedger.FILE_NAME); file.writeText(text)
            assertNull(WidgetBindingLedger.open(directory)); assertEquals(text, file.readText())
        }
    }
    @Test fun candidateAndActiveAreDurableAndTransitionCannotUpgradeAnotherEpoch() {
        val ledger = requireNotNull(WidgetBindingLedger.open(files.root)); val epoch = ledger.quarantine(setOf(1)).getValue(1)
        ledger.transition(epoch, WidgetBindingPhase.QUARANTINED, WidgetBindingPhase.CANDIDATE)
        assertEquals(WidgetBindingPhase.CANDIDATE, WidgetBindingLedger.open(files.root)!!.snapshot().records.getValue(1).phase)
        ledger.transition(epoch, WidgetBindingPhase.CANDIDATE, WidgetBindingPhase.ACTIVE)
        assertEquals(WidgetBindingPhase.ACTIVE, WidgetBindingLedger.open(files.root)!!.snapshot().records.getValue(1).phase)
        try { ledger.transition(WidgetEpoch(1, "stale"), WidgetBindingPhase.ACTIVE, WidgetBindingPhase.CANDIDATE); fail() }
        catch (_: IllegalStateException) { }
    }
    @Test fun failedAtomicFencePoisonsInstanceAndNeverReturnsIssuedEpochs() {
        val directory = files.newFolder("bindings"); val ledger = requireNotNull(WidgetBindingLedger.open(directory))
        val moved = File(files.root, "old-bindings"); assertTrue(directory.renameTo(moved)); directory.writeText("not a directory")
        try { ledger.quarantine(setOf(1)); fail() } catch (_: Exception) { }
        try { ledger.snapshot(); fail() } catch (_: IllegalStateException) { }
        assertNull(WidgetBindingLedger.open(directory))
    }
}
