package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.Clock
import kotlinx.coroutines.CoroutineScope

/** Explicit synthetic NEW-action preparation; production restore never creates a missing row. */
internal fun preparedEditor(
    initial: EventDraft, existing: EditableEvent?, draftId: String,
    mutations: CalendarMutations, drafts: DraftStore, scope: CoroutineScope,
    clock: Clock = Clock.systemUTC(), initialCalendarSelected: Boolean = true,
    recoveryOnly: Boolean = false, recoveryRef: EventRef? = null,
    requireStoredDraft: Boolean = false, recurringCreationSupported: Boolean = false,
): EventEditorController {
    val memory = drafts as? EventEditorControllerTest.Store
    if (!requireStoredDraft && memory != null && draftId !in memory.records && !memory.failReads) {
        memory.records[draftId] = EventEditorController.initialRecord(initial, existing, draftId, initialCalendarSelected, clock)
    }
    val launch = memory?.records?.get(draftId)?.let { DraftBrowserCodec.read(it).launch }
    val generation = launch?.generation
    val frozen = launch?.needsRecovery == true
    return EventEditorController(initial, if (frozen) null else existing, draftId, mutations, drafts, scope, clock, initialCalendarSelected,
        recoveryOnly || frozen, if (recoveryOnly) recoveryRef else if (frozen) launch?.ref else existing?.details?.occurrence?.ref, true, recurringCreationSupported,
        expectedGeneration = generation, requestWritePermission = { true })
}
