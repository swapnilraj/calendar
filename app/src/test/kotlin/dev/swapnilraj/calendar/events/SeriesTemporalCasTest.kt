package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesTemporalCasTest {
    private val f = SeriesEditorFixture()
    private val source = f.temporalSource()
    @After fun stop() { f.scope.cancel() }
    private suspend fun editor() = f.controller(f.prepare(source = source), source)
    @Test fun factoryRequiresAtomicPortAndNeverFallsBackToBlindUpsert() = runBlocking {
        val backing = EventEditorControllerTest.Store()
        val plain = object : DraftStore by backing {}
        assertEquals(SeriesDraftPreparationResult.Unavailable, prepareSeriesDraft(source, RecurrenceScope.ALL, SeriesEditorAction.EDIT, "series", plain))
        assertTrue(backing.records.isEmpty())
        f.store.unavailableAfter = true
        assertTrue(prepareSeriesDraft(source, RecurrenceScope.ALL, SeriesEditorAction.EDIT, "series", f.store) is SeriesDraftPreparationResult.Ready)
        assertEquals(0, f.store.legacyCalls)
    }
    @Test fun generationMismatchCannotRestoreAReplacementUnderTheSameId() = runBlocking {
        val old = editor(); f.store.records.clear()
        val replacement = f.prepare(source = source)
        assertNotEquals(old.launch.generation, replacement.generation)
        val restored = f.controller(old.launch, source); assertTrue(restored.state.draftReadFailed)
        restored.save(); assertTrue(f.calls.isEmpty())
    }
    @Test fun staleAutosaveCannotOverwriteNewerRawRevision() = runBlocking {
        val old = editor(); val newer = f.controller(old.launch, source)
        newer.change { it.copy(title = "Newer raw draft") }; assertTrue(newer.flushDraft())
        val record = f.store.records.getValue("series")
        old.change { it.copy(title = "Stale raw") }; assertFalse(old.flushDraft()); old.save()
        assertEquals(record, f.store.records.getValue("series")); assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.legacyCalls)
    }
    @Test fun unknownFreezeAckRequiresExactOwnRevisionBeforeAnyDispatch() = runBlocking {
        val editor = editor(); editor.change { it.copy(title = "Authorized raw") }
        f.store.beforeCompare = { _, replacement ->
            if (replacement != null && JSONObject(replacement.payload).has("frozen")) {
                val foreign = replacement.copy(payload = JSONObject(replacement.payload).put("revision", UUID.randomUUID().toString()).toString())
                f.store.records[replacement.id] = foreign; f.store.failWrites = true
            }
        }
        editor.save(); assertTrue(f.calls.isEmpty()); assertEquals(SeriesEditorProblem.DRAFT_UNAVAILABLE, editor.state.problem)
    }
    @Test fun exactFreezeReadbackAfterUnknownAckPermitsOnlyOneOriginalDispatch() = runBlocking {
        val editor = editor(); f.store.unavailableAfter = true
        f.next = SeriesMutationResult.Common(MutationResult.Uncertain("original", null))
        editor.change { it.copy(title = "Authorized raw") }; editor.save(); editor.save()
        assertEquals(1, f.calls.size); assertTrue(editor.state.recoveryRequired); assertEquals(0, f.store.legacyCalls)
    }
    @Test fun successCleanupCannotDeleteANewerReplacementRecord() = runBlocking {
        val editor = editor(); var replacement: DraftRecord? = null
        f.store.beforeCompare = { expected, next ->
            if (next == null && expected != null) {
                replacement = expected.copy(payload = JSONObject(expected.payload).put("generation", UUID.randomUUID().toString()).put("revision", UUID.randomUUID().toString()).toString())
                f.store.records[expected.id] = replacement!!
            }
        }
        editor.change { it.copy(title = "Saved original") }; editor.save()
        assertEquals(replacement, f.store.records["series"]); assertNull(editor.state.result); assertTrue(editor.state.localOutcome is SeriesMutationResult.LocalSaved)
        assertEquals(1, f.calls.size); assertEquals(0, f.store.legacyCalls)
    }
    @Test fun newTemporalFrozenRecordRecoversSourceFreeAndRetriesExactCommand() = runBlocking {
        val editor = editor(); f.next = SeriesMutationResult.Common(MutationResult.Uncertain("original", null))
        editor.change { it.copy(temporal = it.temporal!!.copy(durationMode = "ELAPSED", hours = "2")) }; editor.save(); editor.confirmWholeReview()
        val original = f.calls.single(); val record = f.store.records.getValue("series")
        val restored = f.controller(SeriesDraftCodec.describe(record)!!, null)
        f.proveSafe = true; restored.recover(); assertEquals(1, f.calls.size)
        restored.retryOriginal(); assertEquals(listOf(original, original), f.calls)
        assertEquals(record, f.store.records.getValue("series")); assertNull(restored.discardAndReload())
    }
    @Test fun unknownRawAndFrozenSchemaNeverFallThroughToOneoffOrOverwrite() = runBlocking {
        val editor = editor(); editor.change { it.copy(temporal = it.temporal!!.copy(durationMode = "ELAPSED", hours = "2")) }
        f.next = SeriesMutationResult.Common(MutationResult.Uncertain("original", null)); editor.save(); editor.confirmWholeReview()
        val record = f.store.records.getValue("series")
        for (bad in listOf(JSONObject(record.payload).put("schema", 3), JSONObject(record.payload).apply { getJSONObject("frozen").put("schema", 2.1) },
            JSONObject(record.payload).apply { getJSONObject("frozen").getJSONObject("intent").put("unknown", true) })) {
            val corrupt = record.copy(payload = bad.toString()); f.store.records[record.id] = corrupt
            val row = DraftBrowserCodec.read(corrupt); assertNull(row.launch); assertNull(row.seriesLaunch)
            val restored = f.controller(editor.launch, source); assertTrue(restored.state.draftReadFailed); restored.save()
            assertEquals(corrupt, f.store.records[record.id])
        }
        assertEquals(1, f.calls.size)
    }
    @Test fun missingDraftNeverSeedsNewOperationAndMissingIdentityNeverPrepares() = runBlocking {
        val editor = editor(); f.store.records.clear(); val restored = f.controller(editor.launch, source)
        assertTrue(restored.state.draftReadFailed); restored.save(); assertTrue(f.store.records.isEmpty())
        val unknown = source.copy(selectedDetails = source.selectedDetails.copy(calendar = null))
        assertEquals(SeriesDraftPreparationResult.Unavailable, prepareSeriesDraft(unknown, RecurrenceScope.ALL, SeriesEditorAction.EDIT, "series", f.store))
        assertTrue(f.calls.isEmpty())
    }
    @Test fun discardReviewCannotAdoptReplacementUidFromLiveConstructorSource() = runBlocking {
        val editor = editor(); editor.change { it.copy(title = "Keep original A raw fields") }; editor.flushDraft()
        val original = f.store.records.getValue("series")
        val replacement = source.copy(selectedDetails = source.selectedDetails.copy(calendar = source.selectedDetails.calendar!!.copy(providerId = "replacement-namespace")))
        f.readResult = SeriesReadResult.Ready(replacement)
        val restored = f.controller(editor.launch, replacement)
        assertTrue(restored.state.reviewRequired)
        assertTrue(restored.discardAndReload() is SeriesReadResult.NeedsReview)
        assertEquals(original, f.store.records.getValue("series")); assertEquals("Keep original A raw fields", restored.state.fields.title)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.legacyCalls)
    }
    @Test fun legacyDraftWithoutPersistedNamespaceCannotAutoReviewOrRebind() = runBlocking {
        val editor = editor(); editor.change { it.copy(title = "Legacy retained A raw fields") }; editor.flushDraft()
        val old = legacySeriesRecord(f.store.records.getValue("series")); f.store.records[old.id] = old
        val replacement = source.copy(selectedDetails = source.selectedDetails.copy(calendar = source.selectedDetails.calendar!!.copy(providerId = "replacement-namespace")))
        f.readResult = SeriesReadResult.Ready(replacement)
        val restored = f.controller(SeriesDraftCodec.describe(old)!!, replacement)
        assertTrue(restored.state.reviewRequired); assertFalse(restored.canReviewDraft)
        assertNull(restored.discardAndReload()); assertEquals(0, f.reads)
        assertEquals(old, f.store.records.getValue("series")); assertEquals("Legacy retained A raw fields", restored.state.fields.title)
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.store.legacyCalls)
    }

}
