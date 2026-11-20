package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Replacement is a new draft, never a rewritten old operation or a refreshed target namespace. */
internal suspend fun prepareOrdinaryReplacement(old: OrdinaryDraftDocument, initial: EventDraft, fresh: EditableEvent?,
    store: AtomicDraftStore, clock: Clock): EditorDraftLaunch? {
    val id = UUID.randomUUID().toString()
    val record = if (old.launch.kind == EditorDraftKind.EDIT) {
        val source = fresh ?: return null
        val pin = old.sourceIdentity ?: return null
        if (source.details.occurrence.ref != old.launch.ref || source.details.calendar?.identity != pin) return null
        EventEditorController.initialRecord(source.details.let { details -> EventDraft(details.occurrence.ref.calendarId,
            details.occurrence.time, details.eventZone, details.occurrence.title, details.location, details.description,
            details.reminders.filter { it.method == android.provider.CalendarContract.Reminders.METHOD_ALERT }.map { it.minutesBefore },
            details.availability, details.privacy) }, source, id, true, clock)
    } else {
        var fields = old.fields.copy(calendarId = "", targetIdentity = null, defaultVisibilityCalendar = "", defaultVisibilityIdentity = null)
        var notices = old.notices + EditorNotice.CALENDAR_RESELECTION_REQUIRED
        if (EditorNotice.DUPLICATE_AS_ONE_OFF in notices && fields.privacy.toIntOrNull() == 0) {
            fields = fields.copy(privacy = ""); notices = notices + EditorNotice.CALENDAR_VISIBILITY_REVIEW_REQUIRED
        }
        val seed = EventEditorController.initialRecord(initial.copy(targetIdentity = null), null, id, false, clock)
        val base = OrdinaryDraftDocument.decode(seed)
        base.copy(fields = fields, notices = notices.distinct()).record(clock.instant())
    }
    val result = try { store.compareAndSet(id, null, record) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DraftChangeResult.Unavailable }
    return when (result) {
        DraftChangeResult.Applied -> DraftBrowserCodec.read(record).launch
        DraftChangeResult.Changed -> null // A collision is never authority to choose somebody else's draft.
        DraftChangeResult.Unavailable -> try { if (store.load(id) == record) DraftBrowserCodec.read(record).launch else null }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
}
