package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.Clock
import kotlinx.coroutines.CancellationException

sealed interface DraftPreparationResult {
    data class Ready(val launch: EditorDraftLaunch) : DraftPreparationResult
    data object Unavailable : DraftPreparationResult
}

/** Call only for an explicit NEW action with its stable new draft ID, never to restore a route. */
suspend fun prepareNewDraft(
    initial: EventDraft,
    draftId: String,
    initialCalendarSelected: Boolean,
    store: DraftStore,
    clock: Clock = Clock.systemUTC(),
): DraftPreparationResult = prepareDraft(initial, null, draftId, initialCalendarSelected, store, clock)

/** Reuses an existing raw draft; only an explicit discard permits preparing a fresh source baseline. */
suspend fun prepareEditDraft(
    initial: EventDraft,
    existing: EditableEvent,
    draftId: String,
    store: DraftStore,
    clock: Clock = Clock.systemUTC(),
): DraftPreparationResult = prepareDraft(initial, existing, draftId, true, store, clock)

private suspend fun prepareDraft(initial: EventDraft, existing: EditableEvent?, id: String, selected: Boolean,
    store: DraftStore, clock: Clock): DraftPreparationResult = try {
    require(id.isNotBlank())
    val old = store.load(id)
    val record = if (old != null) old else EventEditorController.initialRecord(initial, existing, id, selected, clock)
    val launch = DraftBrowserCodec.read(record).launch
    val expected = editorDraftTarget(existing?.details?.occurrence?.ref)
    if (launch == null || editorDraftTarget(launch.ref) != expected) DraftPreparationResult.Unavailable
    else {
        if (old == null) store.upsert(record)
        // Establish durable identity before navigation; do not expose a route on ignored/failed writes.
        val durable = store.load(id)
        if (durable != null && durable.version == record.version && durable.payload == record.payload) DraftPreparationResult.Ready(launch)
        else DraftPreparationResult.Unavailable
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { DraftPreparationResult.Unavailable }
