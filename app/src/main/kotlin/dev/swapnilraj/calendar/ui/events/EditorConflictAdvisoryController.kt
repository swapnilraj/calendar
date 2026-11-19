package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Advisory observation only. There is no mutation port, permission request or save callback. */
class EditorConflictAdvisoryController(
    private val source: CalendarSchedulingConflicts,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val owner = Any()
    private val session = UUID.randomUUID().toString()
    private var epoch = 0L
    private var collectionEpoch = 0L
    private var current: EditorConflictAdvisoryInput? = null
    private var job: Job? = null
    private var closed = false
    private val mutable = MutableStateFlow(EditorConflictAdvisoryObservation(owner,null, epoch,
        EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.HIDDEN)))
    val state: StateFlow<EditorConflictAdvisoryObservation> = mutable.asStateFlow()

    /** Called by the host on input changes; mask synchronously before cancel/collect can run. */
    fun update(input: EditorConflictAdvisoryInput) {
        val next = synchronized(lock) {
            if (closed || current?.same(input) == true) return
            check(epoch < Long.MAX_VALUE)
            epoch++
            collectionEpoch = epoch
            current = input
            val initial = initial(input)
            mutable.value = EditorConflictAdvisoryObservation(owner,input, epoch, initial)
            job?.cancel()
            job = null
            if (initial.status != EditorConflictAdvisoryStatus.LOADING || !input.sourceReady) return
            val request = try {
                ConflictReadRequest(ConflictDraftRevision(session, epoch), input.binding!!.normalizedTime(input.visibleTime!!),
                    input.planningZone, input.selectedCalendars!!, input.binding.editedRef)
            } catch (_: Exception) {
                mutable.value = EditorConflictAdvisoryObservation(owner,input, epoch,
                    EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.UNSUPPORTED_TIME, firstOccurrenceOnly=input.firstOccurrenceOnly))
                return
            }
            val ownedEpoch = collectionEpoch
            scope.launch(start=CoroutineStart.LAZY) { observe(input, ownedEpoch, request) }.also { job=it }
        }
        next.start()
    }

    /** Safe during composition: even before update/effects run, old counts cannot render. */
    fun presentation(input: EditorConflictAdvisoryInput,
        observed: EditorConflictAdvisoryObservation = state.value): EditorConflictAdvisoryState = synchronized(lock) {
        if (closed) EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.HIDDEN)
        else if (observed.owner === owner && current?.same(input) == true && observed.epoch == epoch && observed.input === current) observed.presentation
        else initial(input)
    }

    fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        epoch++
        current = null
        mutable.value=EditorConflictAdvisoryObservation(owner,null,epoch,EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.HIDDEN))
        job?.cancel(); job=null
    }

    private suspend fun observe(input: EditorConflictAdvisoryInput, ownedEpoch: Long, request: ConflictReadRequest) {
        var latest: ConflictSourceRevision? = null
        try {
            source.observe(request).collect { read ->
                val value = when (read) {
                    CalendarReadState.Loading -> EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.LOADING)
                    CalendarReadState.PermissionRequired -> EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.PERMISSION_REQUIRED)
                    CalendarReadState.Unavailable -> EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.UNAVAILABLE)
                    CalendarReadState.LimitExceeded -> EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.LIMIT_EXCEEDED)
                    is CalendarReadState.Ready -> {
                        val snapshot=read.value
                        val revision=snapshot.context.sourceRevision
                        if (!request.matches(snapshot.context) || latest?.let {
                                revision.namespace != it.namespace || revision.generation <= it.generation
                            } == true) EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.UNAVAILABLE)
                        else {
                            latest=revision
                            when(val assessment=SchedulingConflicts.assess(request.context(revision),snapshot)) {
                                is ConflictAssessment.Assessed -> EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED,
                                    assessment.conflicts.size,assessment.possibleConflicts.size,assessment.coverage==ConflictCoverage.Complete)
                                is ConflictAssessment.NoCalendarsSelected -> EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.NO_CALENDARS_SELECTED)
                                is ConflictAssessment.Rejected -> EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.UNAVAILABLE)
                                is ConflictAssessment.Unavailable -> EditorConflictAdvisoryState(when(assessment.reason) {
                                    ConflictUnavailableReason.PERMISSION_REQUIRED -> EditorConflictAdvisoryStatus.PERMISSION_REQUIRED
                                    ConflictUnavailableReason.UNSUPPORTED_TIME_RANGE -> EditorConflictAdvisoryStatus.UNSUPPORTED_TIME
                                    else -> EditorConflictAdvisoryStatus.UNAVAILABLE
                                })
                            }
                        }
                    }
                }
                publish(input,ownedEpoch,value.copy(firstOccurrenceOnly=input.firstOccurrenceOnly))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(input,ownedEpoch,EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.UNAVAILABLE,
            firstOccurrenceOnly=input.firstOccurrenceOnly)) }
    }

    private fun publish(input: EditorConflictAdvisoryInput, ownedEpoch: Long, value: EditorConflictAdvisoryState) = synchronized(lock) {
        if (!closed && collectionEpoch==ownedEpoch && current === input) {
            // An invalidation or new source observation also makes held render frames obsolete.
            check(epoch < Long.MAX_VALUE)
            epoch++
            mutable.value=EditorConflictAdvisoryObservation(owner,input,epoch,value)
        }
    }

    private fun validEditedNamespace(input: EditorConflictAdvisoryInput): Boolean {
        val binding = input.binding ?: return false
        val ref = binding.editedRef ?: return true
        val identity = binding.calendarIdentity ?: return false
        if (identity.calendarId != ref.calendarId) return false
        // A selected reused numeric ID must not exclude the replacement account's event.
        // An edited calendar absent from selection needs no exclusion in another namespace.
        return input.selectedCalendars?.none { it.calendarId == ref.calendarId && it != identity } != false
    }

    private fun initial(input: EditorConflictAdvisoryInput): EditorConflictAdvisoryState {
        val status=when {
            !input.presentationVisible -> EditorConflictAdvisoryStatus.HIDDEN
            !input.readPermission -> EditorConflictAdvisoryStatus.PERMISSION_REQUIRED
            !input.sourceReady -> EditorConflictAdvisoryStatus.LOADING
            input.binding==null -> EditorConflictAdvisoryStatus.INACTIVE_DRAFT
            input.pendingRaw || input.visibleTime==null || input.visibleTime!=input.binding.input -> EditorConflictAdvisoryStatus.PENDING_INPUT
            !validEditedNamespace(input) -> EditorConflictAdvisoryStatus.UNAVAILABLE
            input.selectedCalendars==null -> EditorConflictAdvisoryStatus.SELECTION_UNAVAILABLE
            input.selectedCalendars.isEmpty() -> EditorConflictAdvisoryStatus.NO_CALENDARS_SELECTED
            else -> try {
                val time=input.binding.normalizedTime(input.visibleTime)
                if (time is EventTime.Timed && time.start==time.end) EditorConflictAdvisoryStatus.UNSUPPORTED_TIME
                else EditorConflictAdvisoryStatus.LOADING
            } catch (_: InvalidInput) { EditorConflictAdvisoryStatus.UNFINISHED_TIME }
              catch (_: Exception) { EditorConflictAdvisoryStatus.UNSUPPORTED_TIME }
        }
        return EditorConflictAdvisoryState(status,firstOccurrenceOnly=input.firstOccurrenceOnly)
    }
}
