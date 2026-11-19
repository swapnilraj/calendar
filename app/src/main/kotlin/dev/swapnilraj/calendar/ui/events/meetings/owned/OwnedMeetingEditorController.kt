package dev.swapnilraj.calendar.ui.events.meetings.owned

import androidx.compose.runtime.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class OwnedMeetingProblem { DRAFT_REVIEW,DRAFT_UNAVAILABLE,DRAFT_CHANGED,SOURCE_CHANGED,PERMISSION,INVALID,NO_CHANGES,UNAVAILABLE,UNCERTAIN,SAFE_TO_RETRY,RETIRED,RETIRE_UNAVAILABLE,RETIRE_BLOCKED }
enum class OwnedMeetingLeaveWarning { UNSAVED_INPUT,LOCAL_OPERATION,UNCERTAIN_REQUEST }
data class OwnedMeetingEditorState(val fields:OwnedMeetingFields?=null,val loading:Boolean=true,val busy:Boolean=false,
    val problem:OwnedMeetingProblem?=null,val recoveryRequired:Boolean=false,val retryReady:Boolean=false,
    val confirmCancel:Boolean=false,val confirmDiscard:Boolean=false,val localOutcome:MeetingMutationResult?=null,
    val acknowledged:Boolean=false,val discarded:Boolean=false)

/** Dedicated THIS organizer workflow. Local raw persistence and provider authorization are separate. */
class OwnedMeetingEditorController(val launch:OwnedMeetingLaunch,private val meetings:CalendarMeetings,
    private val store:AtomicDraftStore,private val requestWritePermission:suspend ()->Boolean,
    private val retirement:OperationRetirement?=null,private val clock:Clock=Clock.systemUTC()) {
    private var backing by mutableStateOf(OwnedMeetingEditorState())
    var presentationVisible by mutableStateOf(true);private set
    val state get()=if(presentationVisible) backing else backing.copy(fields=null,problem=OwnedMeetingProblem.PERMISSION,
        confirmCancel=false,confirmDiscard=false,localOutcome=null)
    private var document:OwnedDocument?=null
    private var expected:DraftRecord?=null
    private var pendingRaw:OwnedDocument?=null
    private var verified=false
    private var stopped=false
    private var retirementProven=false
    private var cleanupAttempt:DraftRecord?=null
    private var cleanupChanged=false
    private var leaseLost=false
    private val action=AtomicBoolean(false)
    private val storage=Mutex()
    private val operations=Mutex()
    val frozen get()=document?.frozen!=null
    val calendarLabel get()=if(presentationVisible) document?.baseline?.calendarLabel.orEmpty() else ""
    val accountLabel get()=if(presentationVisible) document?.baseline?.accountLabel.orEmpty() else ""
    val guestRows get()=if(presentationVisible) document?.baseline?.rights?.guests.orEmpty() else emptyList()
    val allowedGuestTypes get()=if(presentationVisible) document?.baseline?.rights?.allowedGuestTypes.orEmpty() else emptySet()
    val hasPendingRawInput get()=pendingRaw!=null
    private fun mutableRaw()=!stopped && !backing.loading && !frozen && document!=null &&
        (backing.problem !in setOf(OwnedMeetingProblem.DRAFT_REVIEW,OwnedMeetingProblem.DRAFT_UNAVAILABLE,OwnedMeetingProblem.DRAFT_CHANGED) || pendingRaw!=null)
    val canEditRaw get()=presentationVisible && mutableRaw()
    fun canEdit(field:EditableField)=canEditRaw && document?.baseline?.let {OwnedPatch.canEdit(it,field)}==true
    val canChangeKind get()=canEdit(EditableField.TIME) && canEdit(EditableField.ZONE) && document?.baseline?.time is EventTime.Timed
    val canAddGuests get()=canEditRaw && document?.baseline?.rights?.addGuests==AccessDecision.ALLOWED
    val canSave get()=canEditRaw && verified && !backing.busy && pendingRaw==null && document?.let {runCatching {OwnedPatch.build(it.baseline,it.fields)}.isSuccess}==true
    val canCancel get()=canEditRaw && verified && !backing.busy && pendingRaw==null && document?.baseline?.rights?.event?.delete==AccessDecision.ALLOWED
    val canDiscard get()=presentationVisible && !stopped && !leaseLost && !backing.loading && !backing.busy && document!=null && backing.localOutcome==null && (!frozen || retirement!=null)
    val canRecover get()=presentationVisible && !stopped && !leaseLost && !backing.loading && !backing.busy && frozen
    val canLeaveAfterLeaseLoss get()=leaseLost && !stopped && !backing.busy && !action.get()
    val leaveWarning get()=when {backing.localOutcome!=null->OwnedMeetingLeaveWarning.LOCAL_OPERATION;frozen->OwnedMeetingLeaveWarning.UNCERTAIN_REQUEST;else->OwnedMeetingLeaveWarning.UNSAVED_INPUT}
    /** Explicit confirmation only. This is neither draft deletion nor operation retirement. */
    fun confirmLeaveAfterLeaseLoss():Boolean {
        if(!canLeaveAfterLeaseLoss || !operations.tryLock()) return false
        action.set(true)
        try {if(!leaseLost || backing.busy) return false;stopped=true;pendingRaw=null;return true}
        finally {action.set(false);operations.unlock()}
    }

    /** Synchronous masking retains the exact private command and any in-flight outcome. */
    fun observeReadPermission(granted:Boolean) {
        presentationVisible=granted
        if(!granted) {verified=false;backing=backing.copy(confirmCancel=false,confirmDiscard=false)}
    }
    suspend fun load()=exclusive {
        if(stopped || document!=null || !presentationVisible) return@exclusive
        val row=lookup() as? Lookup.Known ?: run {backing=backing.copy(loading=false,problem=OwnedMeetingProblem.DRAFT_UNAVAILABLE);return@exclusive}
        val doc=runCatching {OwnedMeetingDraftCodec.decode(requireNotNull(row.record))}.getOrNull()
            ?: run {backing=backing.copy(loading=false,problem=OwnedMeetingProblem.DRAFT_REVIEW);return@exclusive}
        if(doc.generation!=launch.generation || doc.baseline.selected!=launch.selected) {backing=backing.copy(loading=false,problem=OwnedMeetingProblem.DRAFT_CHANGED);return@exclusive}
        document=doc;expected=row.record
        backing=backing.copy(fields=doc.fields,loading=false,recoveryRequired=doc.frozen!=null,problem=if(doc.frozen!=null) OwnedMeetingProblem.UNCERTAIN else null)
        if(doc.frozen==null) verifyFresh(doc.baseline)
    }
    suspend fun refreshSource()=exclusive {
        val doc=document ?: return@exclusive
        if(!stopped && presentationVisible && doc.frozen==null) verifyFresh(doc.baseline)
    }
    private suspend fun verifyFresh(b:OwnedBaseline):Boolean {
        if(leaseLost) {verified=false;backing=backing.copy(problem=OwnedMeetingProblem.DRAFT_CHANGED);return false}
        val source=try {meetings.loadForEdit(b.selected)} catch(c:CancellationException) {throw c} catch(_:Exception) {MeetingReadResult.Unavailable}
        // Never replace a mounted frozen workflow after a service hint or permission transition.
        if(!presentationVisible) {verified=false;return false}
        verified=source is MeetingReadResult.Ready && b.matches(source.value)
        backing=backing.copy(problem=if(verified) null else if(source==MeetingReadResult.PermissionRequired) OwnedMeetingProblem.PERMISSION else OwnedMeetingProblem.SOURCE_CHANGED)
        return verified
    }
    suspend fun changeFields(fields:OwnedMeetingFields) {
        if(!canEditRaw) return
        exclusive(wait=true) {
            if(!mutableRaw()) return@exclusive
            val doc=pendingRaw ?: document ?: return@exclusive
            if(!validRawChange(doc,fields)) {backing=backing.copy(problem=OwnedMeetingProblem.INVALID);return@exclusive}
            val before=doc.fields.event;var event=fields.event
            if(event.startDate!=before.startDate || event.startTime!=before.startTime) event=event.copy(startOffset="")
            if(event.endDate!=before.endDate || event.endTime!=before.endTime) event=event.copy(endOffset="")
            val guests=fields.guests.zip(doc.baseline.rights.guests).map {(choice,row)->
                val type=runCatching {MeetingGuestType.valueOf(choice.type)}.getOrNull()
                if(type!=null && OwnedPatch.nativeType(type)==row.attendee.type) choice.copy(type="") else choice
            }
            persistRaw(doc.copy(fields=fields.copy(event=event,guests=guests)))
        }
    }
    suspend fun selectZone(raw:String) {
        if(!canEdit(EditableField.ZONE)) return
        exclusive(wait=true) {
            if(!mutableRaw()) return@exclusive
            val doc=pendingRaw ?: document ?: return@exclusive;val old=doc.fields.event
            val event=old.copy(zone=raw,startOffset=if(raw==old.zone) old.startOffset else "",endOffset=if(raw==old.zone) old.endOffset else "")
            persistRaw(doc.copy(fields=doc.fields.copy(event=event,zoneIntent="SET")))
        }
    }
    suspend fun keepOriginalZone()=exclusive {
        if(!canEdit(EditableField.ZONE)) return@exclusive
        val doc=pendingRaw ?: document ?: return@exclusive
        persistRaw(doc.copy(fields=doc.fields.copy(event=doc.fields.event.copy(zone=doc.baseline.original.zone,
            startOffset="",endOffset=""),zoneIntent="KEEP")))
    }
    suspend fun setAllDay(value:Boolean)=exclusive {
        if(!canChangeKind) return@exclusive
        val doc=pendingRaw ?: document ?: return@exclusive
        if(doc.fields.event.allDay==value) return@exclusive
        persistRaw(doc.copy(fields=doc.fields.copy(event=doc.fields.event.copy(allDay=value,zone=if(value) "UTC" else doc.baseline.original.zone,
            startOffset="",endOffset=""),zoneIntent="SET")))
    }
    private fun validRawChange(doc:OwnedDocument,f:OwnedMeetingFields):Boolean {
        val before=doc.fields;val e=f.event;val old=before.event;val b=doc.baseline
        if(f.zoneIntent!=before.zoneIntent || e.zone!=old.zone || e.allDay!=old.allDay || e.calendarId!=old.calendarId || e.targetIdentity!=old.targetIdentity ||
            e.recurrence!=old.recurrence || e.availability!=old.availability || e.defaultVisibilityCalendar!=old.defaultVisibilityCalendar || e.defaultVisibilityIdentity!=old.defaultVisibilityIdentity) return false
        val changed=listOf(EditableField.TITLE to (e.title!=old.title),EditableField.LOCATION to (e.location!=old.location),EditableField.DESCRIPTION to (e.description!=old.description),
            EditableField.TIME to OwnedPatch.temporalChanged(e,old),EditableField.ALERTS to (e.alerts!=old.alerts),EditableField.PRIVACY to (e.privacy!=old.privacy))
        if(changed.any {(field,different)->different && !OwnedPatch.canEdit(b,field)}) return false
        if(f.guests.map {it.ref}!=b.rights.guests.map {it.ref}) return false
        f.guests.zip(before.guests).forEach {(n,o)->val row=b.rights.guests.single {it.ref==n.ref}
            if(n.removed!=o.removed && MeetingGuestAction.REMOVE !in row.allowed || n.type!=o.type && MeetingGuestAction.CHANGE_TYPE !in row.allowed) return false
        }
        if(f.added!=before.added && b.rights.addGuests!=AccessDecision.ALLOWED) return false
        return f.added.size<=200 && f.added.map {it.rowId}.distinct().size==f.added.size && f.added.all {runCatching {java.util.UUID.fromString(it.rowId).toString()==it.rowId}.getOrDefault(false)}
    }
    private suspend fun persistRaw(candidate:OwnedDocument):Boolean {
        if(candidate==(pendingRaw ?: document)) return pendingRaw==null
        pendingRaw=candidate;backing=backing.copy(fields=candidate.fields)
        if(backing.problem==OwnedMeetingProblem.DRAFT_CHANGED) return false
        return replace(candidate).also {if(it) pendingRaw=null}
    }
    suspend fun flushDraft()=exclusive {flushPending()}
    private suspend fun flushPending():Boolean {
        val candidate=pendingRaw ?: return true
        if(stopped || backing.problem==OwnedMeetingProblem.DRAFT_CHANGED) return false
        return replace(candidate).also {if(it) pendingRaw=null}
    }
    suspend fun prepareDismiss():Boolean {
        if(leaseLost || backing.loading || backing.busy || !operations.tryLock()) return false
        action.set(true)
        backing=backing.copy(busy=true)
        try {if(!flushPending()) return false;stopped=true;return true}
        finally {backing=backing.copy(busy=false);action.set(false);operations.unlock()}
    }
    suspend fun save()=exclusive {
        val doc=document ?: return@exclusive
        if(!canEditRaw || !verified || pendingRaw!=null) return@exclusive
        val command=try {OwnedPatch.build(doc.baseline,doc.fields)} catch(_:Exception) {backing=backing.copy(problem=OwnedMeetingProblem.INVALID);return@exclusive}
        freezeAndDispatch(doc,command)
    }
    fun requestCancellation() {if(canCancel && !action.get()) backing=backing.copy(confirmCancel=true)}
    fun dismissCancellation() {if(!action.get()) backing=backing.copy(confirmCancel=false)}
    suspend fun confirmCancellation()=exclusive {
        if(!backing.confirmCancel || !canEditRaw || !verified || pendingRaw!=null) return@exclusive
        val doc=document ?: return@exclusive
        if(doc.baseline.rights.event.delete!=AccessDecision.ALLOWED) return@exclusive
        backing=backing.copy(confirmCancel=false)
        freezeAndDispatch(doc,MeetingCommand.CancelOwned(RecurrenceScope.THIS))
    }
    private suspend fun freezeAndDispatch(doc:OwnedDocument,command:MeetingCommand) {
        if(!permission()) {backing=backing.copy(problem=OwnedMeetingProblem.PERMISSION);return}
        if(!presentationVisible || !verifyFresh(doc.baseline)) return
        if(!owns()) return
        val frozen=FrozenOwnedMeeting(doc.operationId,doc.baseline.selected,doc.baseline.token,command,OwnedJson.fingerprint(doc.fields))
        if(!replace(doc.copy(frozen=frozen))) return
        dispatch(frozen)
    }
    private suspend fun dispatch(f:FrozenOwnedMeeting) {
        if(stopped || retirementProven || !owns()) return
        currentCoroutineContext().ensureActive()
        if(!presentationVisible) return
        backing=backing.copy(recoveryRequired=true,retryReady=false,problem=null)
        val result=try {meetings.mutate(f.operationId,f.selected,f.token,f.command)} catch(c:CancellationException) {throw c} catch(_:Exception) {null}
        applyResult(result,false)
    }
    suspend fun recover()=exclusive {
        val f=document?.frozen ?: return@exclusive
        if(stopped || leaseLost || !presentationVisible || backing.localOutcome!=null || !owns()) return@exclusive
        val result=try {meetings.recover(f.operationId)} catch(c:CancellationException) {throw c} catch(_:Exception) {null}
        applyResult(result,true)
    }
    suspend fun retryOriginal()=exclusive {
        val f=document?.frozen ?: return@exclusive
        if(stopped || !presentationVisible || !backing.retryReady || retirementProven || !owns()) return@exclusive
        if(!permission()) {backing=backing.copy(problem=OwnedMeetingProblem.PERMISSION);return@exclusive}
        backing=backing.copy(retryReady=false);dispatch(f)
    }
    private fun applyResult(result:MeetingMutationResult?,recovery:Boolean) {
        val doc=document ?: return;val f=doc.frozen ?: return
        backing=backing.copy(recoveryRequired=true,retryReady=false)
        val valid=when(result) {
            is MeetingMutationResult.LocalSaved -> f.command is MeetingCommand.EditOwned && result.scope==RecurrenceScope.THIS && result.selected==OwnedPatch.expectedRef(doc.baseline,f.command)
            is MeetingMutationResult.LocalCancelled -> f.command is MeetingCommand.CancelOwned && result.scope==RecurrenceScope.THIS && result.selected==f.selected
            else -> false
        }
        if(valid) {backing=backing.copy(localOutcome=result,problem=null);return}
        val common=(result as? MeetingMutationResult.Common)?.outcome
        when {
            common is MutationResult.SafeToRetry && recovery && common.operationId==f.operationId && !retirementProven -> backing=backing.copy(retryReady=true,problem=OwnedMeetingProblem.SAFE_TO_RETRY)
            common is MutationResult.Retired && common.operationId==f.operationId -> {retirementProven=true;backing=backing.copy(problem=OwnedMeetingProblem.RETIRED)}
            common==MutationResult.PermissionRequired -> backing=backing.copy(problem=OwnedMeetingProblem.PERMISSION)
            else -> backing=backing.copy(problem=OwnedMeetingProblem.UNCERTAIN)
        }
    }
    fun requestDiscard() {if(canDiscard && !action.get()) backing=backing.copy(confirmDiscard=true)}
    fun dismissDiscard() {if(!action.get()) backing=backing.copy(confirmDiscard=false)}
    suspend fun confirmDiscard()=exclusive {
        val doc=document ?: return@exclusive
        if(!backing.confirmDiscard || stopped || leaseLost || backing.localOutcome!=null || !presentationVisible) return@exclusive
        backing=backing.copy(confirmDiscard=false)
        val f=doc.frozen
        if(f!=null && !retirementProven) {
            val port=retirement ?: return@exclusive
            var proof=try {port.retire(f.retirement())} catch(c:CancellationException) {throw c} catch(_:Exception) {OperationRetirementResult.Unavailable}
            if(proof==OperationRetirementResult.Unavailable) proof=try {port.inspect(f.retirement())} catch(c:CancellationException) {throw c} catch(_:Exception) {OperationRetirementResult.Unavailable}
            if(proof !is OperationRetirementResult.Retired || proof.operationId!=f.operationId) {backing=backing.copy(problem=if(proof is OperationRetirementResult.Blocked) OwnedMeetingProblem.RETIRE_BLOCKED else OwnedMeetingProblem.RETIRE_UNAVAILABLE);return@exclusive}
            retirementProven=true;backing=backing.copy(retryReady=false)
        }
        if(deleteExact()) {stopped=true;pendingRaw=null;backing=backing.copy(discarded=true)}
    }
    suspend fun acknowledgeLocalOutcome()=exclusive {
        if(backing.localOutcome==null || stopped || !presentationVisible) return@exclusive
        if(deleteExact()) {stopped=true;backing=backing.copy(acknowledged=true)}
    }
    private suspend fun permission()=try {requestWritePermission()} catch(c:CancellationException) {throw c} catch(_:Exception) {false}
    private sealed interface Lookup {data class Known(val record:DraftRecord?):Lookup;data object Unknown:Lookup}
    private suspend fun lookup():Lookup=try {Lookup.Known(store.load(launch.draftId))} catch(c:CancellationException) {throw c} catch(_:Exception) {Lookup.Unknown}
    private suspend fun owns():Boolean {
        val result=lookup();val ok=result is Lookup.Known && expected!=null && result.record==expected
        if(!ok) {if(result is Lookup.Known) loseLease() else backing=backing.copy(retryReady=false,problem=OwnedMeetingProblem.DRAFT_UNAVAILABLE)}
        return ok
    }
    private fun loseLease() {leaseLost=true;verified=false;backing=backing.copy(retryReady=false,problem=OwnedMeetingProblem.DRAFT_CHANGED)}
    private suspend fun replace(candidate:OwnedDocument):Boolean=storage.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val old=expected ?: return@withContext false
            if(leaseLost) return@withContext false
            val next=candidate.copy(revision=ownedUuid());val record=OwnedMeetingDraftCodec.record(next,clock.instant())
            val result=try {store.compareAndSet(launch.draftId,old,record)} catch(_:Exception) {DraftChangeResult.Unavailable}
            val actual=lookup()
            if(result!=DraftChangeResult.Changed && actual==Lookup.Known(record)) {
                expected=record;document=next;backing=backing.copy(fields=next.fields,problem=if(!verified && next.frozen==null) OwnedMeetingProblem.SOURCE_CHANGED else null,recoveryRequired=next.frozen!=null);true
            } else {
                if(result==DraftChangeResult.Changed || actual is Lookup.Known && actual.record!=old) loseLease()
                else backing=backing.copy(problem=OwnedMeetingProblem.DRAFT_UNAVAILABLE)
                false
            }
        }
    }
    private suspend fun deleteExact():Boolean=storage.withLock {withContext(NonCancellable) {
        val old=expected ?: return@withContext false
        if(cleanupChanged || leaseLost) {loseLease();return@withContext false}
        if(cleanupAttempt==old) when(val row=lookup()) {
            is Lookup.Known -> if(row.record==null) return@withContext cleanupComplete() else if(row.record!=old) {cleanupChanged=true;loseLease();return@withContext false}
            Lookup.Unknown -> {backing=backing.copy(problem=OwnedMeetingProblem.DRAFT_UNAVAILABLE);return@withContext false}
        }
        cleanupAttempt=old
        val result=try {store.compareAndSet(launch.draftId,old,null)} catch(_:Exception) {DraftChangeResult.Unavailable}
        if(result==DraftChangeResult.Changed) {cleanupChanged=true;loseLease();return@withContext false}
        when(val row=lookup()) {
            is Lookup.Known -> if(row.record==null) cleanupComplete() else {cleanupChanged=row.record!=old;if(cleanupChanged) loseLease() else backing=backing.copy(problem=OwnedMeetingProblem.DRAFT_UNAVAILABLE);false}
            Lookup.Unknown -> {backing=backing.copy(problem=OwnedMeetingProblem.DRAFT_UNAVAILABLE);false}
        }
    }}
    private fun cleanupComplete():Boolean {expected=null;cleanupAttempt=null;cleanupChanged=false;return true}
    private suspend fun exclusive(wait:Boolean=false,block:suspend ()->Unit) {
        if(wait) operations.lock() else if(!operations.tryLock()) return
        action.set(true)
        backing=backing.copy(busy=true)
        try {block()} finally {backing=backing.copy(busy=false);action.set(false);operations.unlock()}
    }
}
