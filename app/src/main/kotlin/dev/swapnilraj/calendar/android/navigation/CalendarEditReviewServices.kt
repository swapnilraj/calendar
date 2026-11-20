package dev.swapnilraj.calendar.android.navigation

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.EventNavigationInfo
import dev.swapnilraj.calendar.ui.editorSeed
import dev.swapnilraj.calendar.ui.events.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

/** App-private review/handoff storage. Only the reused native editor can submit provider commands. */
class CalendarEditReviewServices(
    val drafts: AtomicDraftStore, val mutations: CalendarMutations, val retirement: OperationRetirement,
    private val source: IncomingCalendarSource,
    val readEnvironment: suspend () -> CalendarReadState<CalendarEditReviewEnvironment>,
    val changes: Flow<Unit>, private val clock: Clock = Clock.systemUTC(),
) {
    private class ReadPermissionRequired : Exception()
    private enum class Failure { PERMISSION, STALE, INELIGIBLE, UNAVAILABLE }
    private sealed interface SourceRead {
        data class Ready(val editable: EditableEvent, val original: EventDraft, val fields: EventEditorFields, val baseline: String) : SourceRead
        data class Failed(val reason: Failure) : SourceRead
    }

    suspend fun open(request: CalendarEditReviewRequest, expectedGeneration: String? = null, restoreOnly: Boolean = false,
        calendarReadGranted: Boolean = true): CalendarEditReviewResult = guarded {
        require(CalendarEditReviewCodec.decode(CalendarEditReviewCodec.encode(request)) == request)
        var record = drafts.load(request.reviewId)
        val native = drafts.load(request.nativeId)
        if (native != null) return@guarded openNative(native, requireNotNull(record), expectedGeneration, calendarReadGranted,
            expectedRequest = request)
        if (record != null && isCalendarEditHandoffReceipt(record)) {
            val receipt = CalendarEditHandoffReceipt.decode(record)
            return@guarded if (receipt.session == request.sessionId && receipt.selected == request.selected && receipt.binding == request.binding &&
                (expectedGeneration == null || expectedGeneration == receipt.generation)) CalendarEditReviewResult.Consumed else CalendarEditReviewResult.Stale
        }
        if (record == null) {
            if (restoreOnly || expectedGeneration != null) return@guarded CalendarEditReviewResult.Stale
            val candidate = CalendarEditReviewDocument(request, UUID.randomUUID().toString(), UUID.randomUUID().toString()).record(clock.instant())
            if (!replace(null, candidate)) return@guarded CalendarEditReviewResult.Unavailable
            record = candidate
        }
        var doc = CalendarEditReviewDocument.decode(requireNotNull(record))
        if (doc.request != request || expectedGeneration != null && doc.generation != expectedGeneration) return@guarded CalendarEditReviewResult.Stale
        val environment = environment(calendarReadGranted) ?: return@guarded CalendarEditReviewResult.Unavailable
        if (!environment.calendarReadGranted) return@guarded CalendarEditReviewResult.PermissionRequired
        val read = readSource(request, environment)
        if (read !is SourceRead.Ready) return@guarded failure((read as SourceRead.Failed).reason, doc, requireNotNull(record), environment)
        if (doc.sourceToken != null && (doc.sourceToken != read.editable.token.value || doc.sourceBaseline != read.baseline)) return@guarded CalendarEditReviewResult.Stale
        if (doc.sourceToken == null) {
            val next = doc.copy(revision = UUID.randomUUID().toString(), sourceToken = read.editable.token.value, sourceBaseline = read.baseline, original = read.fields)
            val nextRecord = next.record(clock.instant())
            if (!replace(record, nextRecord)) return@guarded CalendarEditReviewResult.Unavailable
            doc = next; record = nextRecord
        }
        if (doc.prepared != null) return@guarded handoff(doc, requireNotNull(record), calendarReadGranted)
        review(doc, requireNotNull(record), read, environment)
    }

    /** This is the only gesture which applies caller suggestions to an ordinary native draft. */
    suspend fun applySuggestions(snapshot: CalendarEditReviewSnapshot, calendarReadGranted: Boolean = true): CalendarEditReviewResult = guarded {
        val record = drafts.load(snapshot.request.reviewId) ?: return@guarded CalendarEditReviewResult.Stale
        if (record != snapshot.record) return@guarded CalendarEditReviewResult.Stale
        val doc = CalendarEditReviewDocument.decode(record)
        if (doc.request != snapshot.request || doc.generation != snapshot.generation) return@guarded CalendarEditReviewResult.Stale
        drafts.load(doc.request.nativeId)?.let { return@guarded openNative(it, record, doc.generation, calendarReadGranted) }
        if (doc.prepared != null) return@guarded handoff(doc, record, calendarReadGranted)
        val environment = environment(calendarReadGranted) ?: return@guarded CalendarEditReviewResult.Unavailable
        if (!environment.calendarReadGranted) return@guarded CalendarEditReviewResult.PermissionRequired
        val read = readSource(doc.request, environment)
        if (read !is SourceRead.Ready) return@guarded failure((read as SourceRead.Failed).reason, doc, record, environment)
        if (doc.sourceToken == null || doc.sourceToken != read.editable.token.value || doc.sourceBaseline != read.baseline || doc.original != read.fields)
            return@guarded CalendarEditReviewResult.Stale
        val problems = issues(doc, read)
        if (problems.isNotEmpty()) return@guarded CalendarEditReviewResult.Unsupported(snapshot(doc, record, read.original.eventZone), problems, environment)
        // The pure factory captures generation/operation ID before any handoff await. Preserve its
        // source authority, then use a separate renderer proof for a genuinely missing source zone.
        val initial = EventEditorController.initialRecord(read.original, read.editable, doc.request.nativeId, true, clock)
        val native = OrdinaryDraftDocument.decode(initial).copy(
            fields = CalendarEditSuggestions.overlay(read.fields, doc.request), sourceBaseline = read.baseline,
            zoneAuthored = authorsMissingZone(doc, read))
        val candidate = native.record(clock.instant())
        val prepared = doc.copy(revision = UUID.randomUUID().toString(), prepared = candidate)
        val preparedRecord = prepared.record(clock.instant())
        CalendarEditReviewDocument.decode(preparedRecord)
        if (!replace(record, preparedRecord)) return@guarded CalendarEditReviewResult.Unavailable
        handoff(prepared, preparedRecord, calendarReadGranted)
    }

    /** A restored native route never creates another candidate, even if successful Save removed it. */
    internal suspend fun resumeNative(session: String, generation: String, reviewGeneration: String, calendarReadGranted: Boolean): CalendarEditReviewResult = guarded {
        require(UUID.fromString(session).toString() == session && UUID.fromString(generation).toString() == generation)
        val record = drafts.load("incoming-edit-review:$session") ?: return@guarded CalendarEditReviewResult.Unavailable
        val native = drafts.load("incoming-edit:$session") ?: return@guarded if (isCalendarEditHandoffReceipt(record)) CalendarEditReviewResult.Consumed else CalendarEditReviewResult.Stale
        if (OrdinaryDraftDocument.decode(native).generation != generation) return@guarded CalendarEditReviewResult.Stale
        openNative(native, record, reviewGeneration, calendarReadGranted)
    }

    internal sealed interface MountedRefresh {
        data class Ready(val environment: CalendarEditReviewEnvironment, val sourceFree: Boolean) : MountedRefresh
        data object PermissionRequired : MountedRefresh
        data object Stale : MountedRefresh
        data object Unavailable : MountedRefresh
    }

    /** The native workflow performs another source read. It must not silently refresh the original
     * external-review token, even if ordinary display fields happen to remain equal. */
    internal suspend fun loadForNative(editor: CalendarEditReviewResult.Editor): EditReadResult {
        val record = drafts.load(editor.launch.draftId) ?: return EditReadResult.Unavailable
        val native = OrdinaryDraftDocument.decode(record)
        if (native.generation != editor.launch.generation || native.hasRequest ||
            CalendarEditHandoffReceipt.hash(native.sourceToken) != editor.originalTokenHash) return EditReadResult.Unavailable
        val ref = editor.launch.ref ?: return EditReadResult.Unavailable
        val probe = CalendarEditReviewRequest(editor.launch.draftId.removePrefix("incoming-edit:"), ref, editor.binding,
            CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), null, null, null, emptyList(), null, null, null, null), editor.reviewedZone)
        return when (val read = readSource(probe, editor.environment)) {
            is SourceRead.Ready -> if (CalendarEditHandoffReceipt.hash(read.editable.token.value) == editor.originalTokenHash &&
                CalendarEditHandoffReceipt.hash(read.baseline) == editor.originalBaselineHash) EditReadResult.Ready(read.editable) else EditReadResult.Unavailable
            is SourceRead.Failed -> if (read.reason == Failure.PERMISSION) EditReadResult.PermissionRequired else EditReadResult.Unavailable
        }
    }

    /** Preserve the exact mounted controller. Its own update/recovery may have changed event bounds
     * or learned a UID before exact local cleanup; frozen commands use receipt/backend proof only. */
    internal suspend fun refreshMounted(editor: CalendarEditReviewResult.Editor, calendarReadGranted: Boolean): MountedRefresh {
        return try {
            val environment = environment(calendarReadGranted) ?: return MountedRefresh.Unavailable
            suspend fun native(): OrdinaryDraftDocument? = drafts.load(editor.launch.draftId)?.let(OrdinaryDraftDocument::decode)
            val before = native()
            if (before == null) return MountedRefresh.Ready(environment, true) // Owner may be finishing exact cleanup.
            if (before.generation != editor.launch.generation) return MountedRefresh.Stale
            if (before.hasRequest) return MountedRefresh.Ready(environment, true)
            if (!environment.calendarReadGranted) return MountedRefresh.PermissionRequired
            val current = source.readEventInfo(editor.binding.eventId)
            val valid = current is CalendarReadState.Ready && current.value?.let { matches(editor.binding, requireNotNull(editor.launch.ref), it) } == true
            val after = native()
            if (after == null || after.generation == editor.launch.generation && after.hasRequest) return MountedRefresh.Ready(environment, true)
            if (after.generation != editor.launch.generation) return MountedRefresh.Stale
            when {
                current == CalendarReadState.PermissionRequired -> MountedRefresh.PermissionRequired
                current !is CalendarReadState.Ready -> MountedRefresh.Unavailable
                !valid -> MountedRefresh.Stale
                else -> MountedRefresh.Ready(environment, false)
            }
        } catch (_: ReadPermissionRequired) { MountedRefresh.PermissionRequired }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MountedRefresh.Unavailable }
    }

    private suspend fun handoff(doc: CalendarEditReviewDocument, record: DraftRecord, platformRead: Boolean): CalendarEditReviewResult {
        val candidate = requireNotNull(doc.prepared)
        val old = drafts.load(candidate.id)
        if (old == null) {
            val changed = change(candidate.id, null, candidate)
            val durable = drafts.load(candidate.id)
            if (durable != candidate || changed == DraftChangeResult.Changed) return CalendarEditReviewResult.Unavailable
        }
        val current = drafts.load(candidate.id) ?: return CalendarEditReviewResult.Unavailable
        return openNative(current, record, doc.generation, platformRead)
    }

    private suspend fun openNative(nativeRecord: DraftRecord, companion: DraftRecord, expectedGeneration: String?, platformRead: Boolean,
        expectedRequest: CalendarEditReviewRequest? = null): CalendarEditReviewResult {
        CalendarEditJson.objectValue(nativeRecord.payload)
        val native = OrdinaryDraftDocument.decode(nativeRecord)
        require(!native.legacy && native.launch.kind == EditorDraftKind.EDIT && native.launch.ref?.recurrence == null)
        val receipt = if (isCalendarEditHandoffReceipt(companion)) CalendarEditHandoffReceipt.decode(companion) else {
            val doc = CalendarEditReviewDocument.decode(companion)
            val prepared = requireNotNull(doc.prepared)
            val planned = OrdinaryDraftDocument.decode(prepared)
            if (native.generation != planned.generation || nativeRecord.id != prepared.id || native.launch.ref != doc.request.selected ||
                native.sourceBaseline != doc.sourceBaseline || native.sourceToken != doc.sourceToken) return CalendarEditReviewResult.Stale
            CalendarEditHandoffReceipt(doc.request.sessionId, doc.generation, requireNotNull(native.generation), doc.request.selected, doc.request.binding,
                doc.request.reviewedZone, CalendarEditHandoffReceipt.hash(native.sourceBaseline), CalendarEditHandoffReceipt.hash(native.sourceToken))
        }
        if (!receipt.matches(nativeRecord) || expectedGeneration != null && expectedGeneration != receipt.generation) return CalendarEditReviewResult.Stale
        if (expectedRequest != null && (expectedRequest.sessionId != receipt.session || expectedRequest.selected != receipt.selected || expectedRequest.binding != receipt.binding))
            return CalendarEditReviewResult.Stale
        val environment = environment(platformRead) ?: return CalendarEditReviewResult.Unavailable
        if (!native.hasRequest) {
            if (!environment.calendarReadGranted) return CalendarEditReviewResult.PermissionRequired
            val probe = CalendarEditReviewRequest(receipt.session, receipt.selected, receipt.binding,
                CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), null, null, null, emptyList(), null, null, null, null), receipt.reviewedZone)
            val read = readSource(probe, environment)
            if (read is SourceRead.Failed) return if (read.reason == Failure.PERMISSION) CalendarEditReviewResult.PermissionRequired else CalendarEditReviewResult.Stale
            read as SourceRead.Ready
            if (read.editable.token.value != native.sourceToken || read.baseline != native.sourceBaseline) return CalendarEditReviewResult.Stale
        }
        if (!isCalendarEditHandoffReceipt(companion)) {
            val candidate = receipt.record(clock.instant())
            val changed = change(companion.id, companion, candidate)
            val durable = drafts.load(companion.id)
            val sameReceipt = durable?.let { try { CalendarEditHandoffReceipt.decode(it) == receipt } catch (_: Exception) { false } } == true
            if (!(changed != DraftChangeResult.Changed && durable == candidate || sameReceipt)) return CalendarEditReviewResult.Unavailable
        }
        return CalendarEditReviewResult.Editor(native.launch, environment, receipt.binding, receipt.generation, native.hasRequest,
            receipt.tokenHash, receipt.baselineHash, receipt.reviewedZone)
    }

    private suspend fun readSource(request: CalendarEditReviewRequest, environment: CalendarEditReviewEnvironment): SourceRead {
        if (!environment.calendarReadGranted) return SourceRead.Failed(Failure.PERMISSION)
        if (environment.calendars.singleOrNull { it.id == request.selected.calendarId }?.identity != request.binding.calendar) return SourceRead.Failed(Failure.STALE)
        val before = source.readEventInfo(request.binding.eventId)
        if (before == CalendarReadState.PermissionRequired) return SourceRead.Failed(Failure.PERMISSION)
        if (before !is CalendarReadState.Ready) return SourceRead.Failed(Failure.UNAVAILABLE)
        val info = before.value ?: return SourceRead.Failed(Failure.STALE)
        if (!matches(request.binding, request.selected, info)) return SourceRead.Failed(Failure.STALE)
        val read = mutations.loadForEdit(request.selected)
        val editable = when (read) {
            EditReadResult.PermissionRequired -> return SourceRead.Failed(Failure.PERMISSION)
            is EditReadResult.Denied -> return SourceRead.Failed(Failure.INELIGIBLE)
            is EditReadResult.Ready -> read.event
            else -> return SourceRead.Failed(Failure.UNAVAILABLE)
        }
        // Ready is the writer's fresh eligibility proof; the reader keeps advisory canEdit
        // UNKNOWN until a writer checks its source. A declared DENIED still blocks admission.
        val details = editable.details
        val calendar = details.calendar ?: return SourceRead.Failed(Failure.INELIGIBLE)
        val actor = calendar.account.name
        if (details.occurrence.ref != request.selected || calendar.identity != request.binding.calendar ||
            calendar.capabilities.modifyEvents != AccessDecision.ALLOWED || details.canEdit == AccessDecision.DENIED || details.isOrganizer != true ||
            actor.isBlank() || calendar.account.type.isBlank() || calendar.owner?.equals(actor, true) != true || details.organizer?.equals(actor, true) != true ||
            details.attendees.any { !it.email.equals(actor, true) } || !details.recurrenceRule.isNullOrEmpty() ||
            (details.occurrence.time is EventTime.AllDay) != info.allDay) return SourceRead.Failed(Failure.INELIGIBLE)
        val after = source.readEventInfo(request.binding.eventId)
        if (after == CalendarReadState.PermissionRequired) return SourceRead.Failed(Failure.PERMISSION)
        if (after !is CalendarReadState.Ready) return SourceRead.Failed(Failure.UNAVAILABLE)
        if (after.value != info) return SourceRead.Failed(Failure.STALE)
        val original = details.editorSeed()
        val fields = EventEditorController.fieldsFor(if (original.eventZone == null) original.copy(eventZone = request.reviewedZone) else original)
        return SourceRead.Ready(editable, original, fields, EventEditorController.sourceBaseline(original, editable, fields.zone))
    }

    private fun matches(binding: CalendarEntryBinding, ref: EventRef, info: EventNavigationInfo) = info.eventId == binding.eventId &&
        info.calendar == binding.calendar && info.providerUid == binding.providerUid && !info.recurringOrDetached && ref.recurrence == null &&
        info.start == ref.instanceStart && info.endExclusive == ref.instanceEnd

    private fun issues(doc: CalendarEditReviewDocument, read: SourceRead.Ready): Set<CalendarEditReviewIssue> {
        val suggested = CalendarEditSuggestions.overlay(read.fields, doc.request)
        return CalendarEditSuggestions.issues(read.fields, suggested, read.editable.allowedFields, doc.request).toMutableSet().apply {
            // A proposed zone is semantic authoring even when it equals the original renderer.
            if (authorsMissingZone(doc, read) && EditableField.ZONE !in read.editable.allowedFields)
                add(CalendarEditReviewIssue.FIELD_NOT_ALLOWED)
            // A renderer alone still cannot authorize changing a missing-zone source's times.
            if (read.original.eventZone == null && !read.fields.allDay && !suggested.allDay &&
                doc.request.proposed.timeZone == null &&
                listOf(suggested.startDate, suggested.endDate, suggested.startTime, suggested.endTime, suggested.startOffset, suggested.endOffset) !=
                listOf(read.fields.startDate, read.fields.endDate, read.fields.startTime, read.fields.endTime, read.fields.startOffset, read.fields.endOffset))
                add(CalendarEditReviewIssue.ZONE_AUTHORING_REVIEW)
        }
    }
    private fun authorsMissingZone(doc: CalendarEditReviewDocument, read: SourceRead.Ready): Boolean =
        read.original.eventZone == null && !read.fields.allDay &&
            !(doc.request.proposed.occurrence.allDay ?: read.fields.allDay) && doc.request.proposed.timeZone != null

    private fun review(doc: CalendarEditReviewDocument, record: DraftRecord, read: SourceRead.Ready, environment: CalendarEditReviewEnvironment): CalendarEditReviewResult {
        val issues = issues(doc, read)
        return if (issues.isEmpty()) CalendarEditReviewResult.Review(snapshot(doc, record, read.original.eventZone), environment)
            else CalendarEditReviewResult.Unsupported(snapshot(doc, record, read.original.eventZone), issues, environment)
    }
    private fun snapshot(doc: CalendarEditReviewDocument, record: DraftRecord, sourceZone: java.time.ZoneId? = null) = CalendarEditReviewSnapshot(doc.request, doc.generation, doc.original,
        doc.original?.let { CalendarEditSuggestions.overlay(it, doc.request) }, record, sourceZone)
    private fun failure(reason: Failure, doc: CalendarEditReviewDocument, record: DraftRecord, environment: CalendarEditReviewEnvironment): CalendarEditReviewResult = when (reason) {
        Failure.PERMISSION -> CalendarEditReviewResult.PermissionRequired
        Failure.STALE -> CalendarEditReviewResult.Stale
        Failure.INELIGIBLE -> CalendarEditReviewResult.Unsupported(snapshot(doc, record).copy(original = null, suggested = null), setOf(CalendarEditReviewIssue.SOURCE_NOT_ELIGIBLE), environment)
        Failure.UNAVAILABLE -> CalendarEditReviewResult.Unavailable
    }
    private suspend fun environment(platformRead: Boolean): CalendarEditReviewEnvironment? = when (val state = readEnvironment()) {
        is CalendarReadState.Ready -> if (state.value.calendarReadGranted && platformRead) state.value else state.value.copy(calendars = emptyList(), calendarReadGranted = false)
        CalendarReadState.PermissionRequired -> throw ReadPermissionRequired()
        else -> null
    }
    private suspend fun change(id: String, expected: DraftRecord?, candidate: DraftRecord?): DraftChangeResult = try { drafts.compareAndSet(id, expected, candidate) }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { DraftChangeResult.Unavailable }
    private suspend fun replace(expected: DraftRecord?, candidate: DraftRecord): Boolean {
        val changed = change(candidate.id, expected, candidate)
        return changed != DraftChangeResult.Changed && drafts.load(candidate.id) == candidate
    }
    private suspend fun guarded(block: suspend () -> CalendarEditReviewResult): CalendarEditReviewResult = try { withTimeout(15_000) { block() } }
        catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); CalendarEditReviewResult.Unavailable }
        catch (_: ReadPermissionRequired) { CalendarEditReviewResult.PermissionRequired }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { CalendarEditReviewResult.Unavailable }
}
