package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
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
    store: DraftStore, clock: Clock, notices: List<EditorNotice> = emptyList()): DraftPreparationResult = try {
    require(id.isNotBlank())
    val old = store.load(id)
    val record = if (old != null) old else EventEditorController.initialRecord(initial, existing, id, selected, clock, notices)
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

/** Copies one displayed occurrence into a durable new-event draft; never mutates the source. */
suspend fun prepareDuplicateDraft(
    details: EventDetails,
    targetCalendar: CalendarId?,
    draftId: String,
    store: DraftStore,
    defaultTimedMinutes: Int? = null,
    defaultAllDayMinutes: Int? = null,
    clock: Clock = Clock.systemUTC(),
): DraftPreparationResult {
    val notices = linkedSetOf(EditorNotice.DUPLICATE_AS_ONE_OFF, EditorNotice.GUESTS_NOT_COPIED)
    if (!details.recurrenceRule.isNullOrBlank() || details.occurrence.ref.recurrence != null)
        notices += EditorNotice.RECURRENCE_NOT_COPIED
    val defaults = if (details.occurrence.time is EventTime.AllDay) defaultAllDayMinutes else defaultTimedMinutes
    val alerts = details.reminders.mapNotNull { reminder ->
        if (reminder.method != CalendarContract.Reminders.METHOD_ALERT) {
            notices += EditorNotice.REMINDERS_NOT_COPIED; null
        } else if (reminder.minutesBefore != CalendarContract.Reminders.MINUTES_DEFAULT) reminder.minutesBefore
        else defaults?.takeUnless { it == CalendarContract.Reminders.MINUTES_DEFAULT } ?: run {
            notices += EditorNotice.DEFAULT_REMINDERS_UNRESOLVED; null
        }
    }.distinct()
    val privacy = details.privacy?.takeIf { it in 0..3 }
    if (privacy == null) notices += EditorNotice.VISIBILITY_REVIEW_REQUIRED
    val availability = details.availability?.takeIf { it in 0..2 }
    if (availability == null) notices += EditorNotice.AVAILABILITY_REVIEW
    val draft = EventDraft(targetCalendar ?: CalendarId(0), details.occurrence.time, details.eventZone,
        details.occurrence.title, details.location, details.description, alerts, availability, privacy)
    return prepareDraft(draft, null, draftId, targetCalendar != null, store, clock, notices.toList())
}
