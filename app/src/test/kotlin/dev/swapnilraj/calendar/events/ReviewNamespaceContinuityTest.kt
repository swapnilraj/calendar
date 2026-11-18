package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewNamespaceContinuityTest {
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 7, start, start.plusSeconds(3600))
    private val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "fixture"), "owner@example.invalid", "calendar-A")
    private val calendar = CalendarInfo(identity.calendarId, "Synthetic", 1, identity.account, identity.owner,
        ZoneOffset.UTC, 700, true, true, providerId = identity.providerId)
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(start, ref.instanceEnd), ZoneOffset.UTC, "Synthetic event")
    private val source = EditableEvent(EventDetails(EventOccurrence(ref, initial.time, initial.title, 1), calendar), EditToken("token-A"), EditableField.entries.toSet())

    private class Writer : CalendarMutations {
        var editRead: EditReadResult = EditReadResult.Unavailable
        var updates = 0; var deletes = 0
        override suspend fun loadForEdit(ref: EventRef) = editRead
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult = error("No creation expected")
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult {
            updates++; return MutationResult.LocalSaved(ref)
        }
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult {
            deletes++; return MutationResult.LocalDeleted(ref)
        }
        override suspend fun recover(operationId: String): MutationResult = error("No recovery expected")
    }

    @Test fun restoredForeignLiveSourceCannotBecomeTheOriginalNamespaceForReloadOrDiscard() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            for (replacement in listOf(calendar.copy(providerId = "calendar-B"), calendar.copy(account = CalendarAccount("other@example.invalid", "fixture")),
                calendar.copy(owner = "other@example.invalid"), calendar.copy(providerId = null))) {
                val store = EventEditorControllerTest.Store(); val writer = Writer()
                val old = preparedEditor(initial, source, "original", writer, store, scope)
                old.change { it.copy(title = "Retain original raw edit", startTime = "partial-") }; assertTrue(old.flushDraft())
                val record = store.records.getValue("original")
                val foreign = source.copy(details = source.details.copy(calendar = replacement), token = EditToken("token-B"))
                val restored = preparedEditor(initial, foreign, "original", writer, store, scope)
                assertTrue(restored.state.reviewRequired); assertNull(restored.state.reviewLatest)
                writer.editRead = EditReadResult.Ready(foreign)
                assertEquals(EditReadResult.Unavailable, restored.reloadExisting())
                assertEquals(EditReadResult.Unavailable, restored.discardAndReload())
                assertEquals(record, store.records.getValue("original")); restored.save(); restored.confirmDelete()
                assertEquals(0, writer.updates); assertEquals(0, writer.deletes)
                // A separate explicit fresh selection can create a new B draft after leaving this flow.
                assertTrue(prepareEditDraft(initial, foreign, "fresh-selection", store) is DraftPreparationResult.Ready)
            }
        } finally { scope.cancel() }
    }

    @Test fun legacyMissingPersistedIdentityCannotDiscardIntoEvenAnIdenticalCurrentSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = EventEditorControllerTest.Store(); val writer = Writer()
            val old = preparedEditor(initial, source, "legacy", writer, store, scope)
            old.change { it.copy(description = "Keep legacy raw notes") }; assertTrue(old.flushDraft())
            val original = store.records.getValue("legacy")
            val raw = JSONObject(original.payload)
            raw.put("sourceBaseline", JSONObject(raw.getString("sourceBaseline")).apply { remove("sourceCalendarIdentity") }.toString())
            val legacy = original.copy(payload = raw.toString()); store.records[legacy.id] = legacy
            val restored = preparedEditor(initial, source, "legacy", writer, store, scope)
            assertTrue(restored.state.reviewRequired)
            writer.editRead = EditReadResult.Ready(source)
            assertEquals(EditReadResult.Unavailable, restored.reloadExisting())
            assertEquals(EditReadResult.Unavailable, restored.discardAndReload())
            assertEquals(legacy, store.records.getValue(legacy.id)); assertEquals(0, writer.updates)
        } finally { scope.cancel() }
    }
    @Test fun legacyDraftStoreAdaptersCannotAuthorizeNewPreparationOrMutation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val backing = EventEditorControllerTest.Store()
            for (adapter in listOf(object : DraftStore by backing {
                override suspend fun upsert(record: DraftRecord) = backing.upsert(record.copy(updatedAt = record.updatedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
            }, object : DraftStore by backing { override suspend fun upsert(record: DraftRecord) = Unit })) {
                val writer = Writer()
                assertEquals(DraftPreparationResult.Unavailable, prepareEditDraft(initial, source, "adapter", adapter))
                val controller = EventEditorController(initial, source, "adapter", writer, adapter, scope)
                assertTrue(controller.state.draftReadFailed); controller.change { it.copy(description = "Never persisted") }
                assertFalse(controller.flushDraft()); controller.save(); controller.confirmDelete()
                assertTrue(backing.records.isEmpty()); assertEquals(0, writer.updates); assertEquals(0, writer.deletes)
            }
        } finally { scope.cancel() }
    }
}
