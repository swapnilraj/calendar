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

/** Explicit grid/new-launch preparation with raw fields and an immutable original source baseline. */
suspend fun prepareEditorFieldsDraft(
    initial: EventDraft,
    existing: EditableEvent?,
    draftId: String,
    initialCalendarSelected: Boolean,
    fields: EventEditorFields,
    store: AtomicDraftStore,
    clock: Clock = Clock.systemUTC(),
): DraftPreparationResult = prepareDraft(initial, existing, draftId, initialCalendarSelected, store, clock, rawFields = fields)

private suspend fun prepareDraft(initial: EventDraft, existing: EditableEvent?, id: String, selected: Boolean,
    store: DraftStore, clock: Clock, notices: List<EditorNotice> = emptyList(), defaultVisibilityCalendar: String = "", defaultVisibilityIdentity: CalendarIdentity? = null, rawFields: EventEditorFields? = null): DraftPreparationResult = try {
    require(id.isNotBlank())
    val atomic = store as? AtomicDraftStore ?: error("Atomic draft storage required")
    val old = store.load(id)
    val record = old ?: EventEditorController.initialRecord(initial, existing, id, selected, clock, notices, defaultVisibilityCalendar, defaultVisibilityIdentity).let { candidate ->
        if (rawFields == null) candidate else {
            val doc = OrdinaryDraftDocument.decode(candidate)
            // A raw grid edit may change dates/times, never the authority of its original calendar.
            require(rawFields.calendarId == doc.fields.calendarId && rawFields.targetIdentity == doc.fields.targetIdentity)
            doc.copy(fields = rawFields).record(clock.instant())
        }
    }
    val launch = DraftBrowserCodec.read(record).launch
    val expected = editorDraftTarget(existing?.details?.occurrence?.ref)
    if (launch == null || editorDraftTarget(launch.ref) != expected) DraftPreparationResult.Unavailable
    else if (old != null) DraftPreparationResult.Ready(launch)
    else {
        val changed = try { atomic.compareAndSet(id, null, record) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DraftChangeResult.Unavailable }
        val durable = store.load(id)
        if (changed != DraftChangeResult.Changed && durable == record) DraftPreparationResult.Ready(launch)
        else if (changed == DraftChangeResult.Changed && durable != null) {
            val collision = DraftBrowserCodec.read(durable).launch
            if (collision != null && editorDraftTarget(collision.ref) == expected) DraftPreparationResult.Ready(collision) else DraftPreparationResult.Unavailable
        } else DraftPreparationResult.Unavailable
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
    targetIdentity: CalendarIdentity? = null,
): DraftPreparationResult {
    val boundTarget = targetIdentity?.takeIf { it.calendarId == targetCalendar && it.account.name.isNotBlank() && it.account.type.isNotBlank() }
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
    var privacy = details.privacy?.takeIf { it in 0..3 }
    if (privacy == null) notices += EditorNotice.VISIBILITY_REVIEW_REQUIRED
    if (privacy == CalendarContract.Events.ACCESS_DEFAULT && (boundTarget == null || boundTarget != details.calendar?.identity)) {
        privacy = null
        notices += EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED
    }
    val availability = details.availability?.takeIf { it in 0..2 }
    if (availability == null) notices += EditorNotice.AVAILABILITY_REVIEW
    val draft = EventDraft(targetCalendar ?: CalendarId(0), details.occurrence.time, details.eventZone,
        details.occurrence.title, details.location, details.description, alerts, availability, privacy, boundTarget)
    if (boundTarget == null) notices += EditorNotice.CALENDAR_RESELECTION_REQUIRED
    return prepareDraft(draft, null, draftId, boundTarget != null, store, clock, notices.toList(),
        if (privacy == CalendarContract.Events.ACCESS_DEFAULT) boundTarget?.calendarId?.value?.toString().orEmpty() else "",
        if (privacy == CalendarContract.Events.ACCESS_DEFAULT) boundTarget else null)
}
