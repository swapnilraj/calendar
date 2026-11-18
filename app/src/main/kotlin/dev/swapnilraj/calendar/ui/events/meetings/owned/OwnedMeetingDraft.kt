package dev.swapnilraj.calendar.ui.events.meetings.owned

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.ResponseJson
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class OwnedGuestChoice(val ref: MeetingGuestRef,val removed:Boolean=false,val type:String="")
data class OwnedMeetingFields(val event:EventEditorFields,val zoneIntent:String="KEEP",
    val guests:List<OwnedGuestChoice> = emptyList(),val added:List<MeetingCreationGuestFields> = emptyList())
@ConsistentCopyVisibility
data class OwnedMeetingLaunch internal constructor(val draftId:String,val generation:String,val selected:EventRef,val needsRecovery:Boolean)
sealed interface OwnedMeetingPreparation {
    data class Ready(val launch:OwnedMeetingLaunch):OwnedMeetingPreparation
    data object RetainedForReview:OwnedMeetingPreparation
    data object Unavailable:OwnedMeetingPreparation
}
data class OwnedMeetingDraftPreview(val launch:OwnedMeetingLaunch,val title:String,val time:EventTime)

/** Explicit fresh-source action only. Restore never creates a replacement or adopts a collision. */
suspend fun prepareOwnedMeetingDraft(source:EditableMeeting,id:String,displayZone:ZoneId,store:AtomicDraftStore,
    clock:Clock=Clock.systemUTC()):OwnedMeetingPreparation {
    val baseline=runCatching {OwnedBaseline.from(source,displayZone)}.getOrNull() ?: return OwnedMeetingPreparation.Unavailable
    if(id.isBlank()) return OwnedMeetingPreparation.Unavailable
    val doc=OwnedDocument(id,ownedUuid(),ownedUuid(),ownedUuid(),baseline,OwnedMeetingFields(baseline.original,
        guests=baseline.rights.guests.map {OwnedGuestChoice(it.ref)}))
    val record=OwnedMeetingDraftCodec.record(doc,clock.instant())
    val result=try {store.compareAndSet(id,null,record)} catch(c:CancellationException) {throw c} catch(_:Exception) {DraftChangeResult.Unavailable}
    if(result==DraftChangeResult.Changed) return OwnedMeetingPreparation.RetainedForReview
    val actual=try {store.load(id)} catch(c:CancellationException) {throw c} catch(_:Exception) {return OwnedMeetingPreparation.Unavailable}
    return if(actual==record) OwnedMeetingPreparation.Ready(doc.launch()) else OwnedMeetingPreparation.Unavailable
}
internal fun ownedUuid()=UUID.randomUUID().toString()
internal data class OwnedBaseline(val selected:EventRef,val identity:CalendarIdentity,val token:EditToken,
    val time:EventTime,val zone:ZoneId?,val original:EventEditorFields,val displayZone:ZoneId,
    val rights:MeetingScopeAccess,val stamp:String,val calendarLabel:String,val accountLabel:String) {
    fun matches(source:EditableMeeting)=runCatching {
        val fresh=from(source,displayZone)
        fresh.selected==selected && fresh.identity==identity && fresh.token==token && fresh.rights==rights &&
            fresh.time==time && fresh.zone==zone && fresh.original==original && fresh.stamp==stamp
    }.getOrDefault(false)
    companion object {
        fun eligible(s:EditableMeeting):Boolean=s.details.occurrence.ref.let {it.eventId>0 && it.recurrence==null} &&
            s.details.isOrganizer==true && s.details.recurrenceRule.isNullOrBlank() && s.master==null && s.token.value.isNotBlank() &&
            s.details.calendar?.identity?.let {it.account.name.isNotBlank() && it.account.type.isNotBlank() && it.owner==it.account.name && it.calendarId==s.details.occurrence.ref.calendarId}==true &&
            s.scopes.keys==setOf(RecurrenceScope.THIS) && s.details.status in setOf(null,0,1) &&
            s.scopes[RecurrenceScope.THIS]?.let {it.event.edit==AccessDecision.ALLOWED || it.event.delete==AccessDecision.ALLOWED}==true
        fun from(s:EditableMeeting,displayZone:ZoneId):OwnedBaseline {
            require(eligible(s));val d=s.details;val cal=requireNotNull(d.calendar)
            val bounds=when(val time=d.occurrence.time) {is EventTime.Timed->time.start to time.end;is EventTime.AllDay->time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()}
            require(bounds==(d.occurrence.ref.instanceStart to d.occurrence.ref.instanceEnd))
            val draft=EventDraft(cal.id,d.occurrence.time,d.eventZone,d.occurrence.title,d.location,d.description,
                d.reminders.filter {it.method==1}.map {it.minutesBefore},d.availability,d.privacy,cal.identity)
            val renderer=d.eventZone ?: displayZone
            val fields=EventEditorController.fieldsFor(draft.copy(eventZone=renderer))
            val rights=requireNotNull(s.scopes[RecurrenceScope.THIS])
            require(rights.guests.map {it.ref}.distinct().size==rights.guests.size && rights.guests.all {it.ref.value.isNotBlank()})
            return OwnedBaseline(d.occurrence.ref,cal.identity,s.token,d.occurrence.time,d.eventZone,fields,renderer,rights,
                OwnedJson.stamp(s),cal.displayName,cal.account.name)
        }
    }
}
internal data class FrozenOwnedMeeting(val operationId:String,val selected:EventRef,val token:EditToken,
    val command:MeetingCommand,val fingerprint:String) {
    fun retirement()=RetirableOperation.Meeting(operationId,selected,token,command)
}
internal data class OwnedDocument(val id:String,val generation:String,val revision:String,val operationId:String,
    val baseline:OwnedBaseline,val fields:OwnedMeetingFields,val frozen:FrozenOwnedMeeting?=null) {
    fun launch()=OwnedMeetingLaunch(id,generation,baseline.selected,frozen!=null)
}

/** Feature version 9, protocol 1. Frozen typed commands never depend on current form parsers. */
object OwnedMeetingDraftCodec {
    const val VERSION=9
    fun describe(record:DraftRecord):OwnedMeetingLaunch?=runCatching {decode(record).launch()}.getOrNull()
    fun preview(record:DraftRecord):OwnedMeetingDraftPreview?=runCatching {decode(record).let {OwnedMeetingDraftPreview(it.launch(),it.fields.event.title,it.baseline.time)}}.getOrNull()
    internal fun record(d:OwnedDocument,at:Instant)=DraftRecord(d.id,VERSION,JSONObject().put("feature","owned-meeting-edit").put("schema",1)
        .put("generation",d.generation).put("revision",d.revision).put("operationId",d.operationId)
        .put("baseline",OwnedJson.baseline(d.baseline)).put("fields",OwnedJson.fields(d.fields))
        .put("frozen",d.frozen?.let {JSONObject().put("schema",1).put("operationId",it.operationId).put("selected",ResponseJson.ref(it.selected))
            .put("token",it.token.value).put("fingerprintSchema",1).put("fingerprint",it.fingerprint).put("command",OwnedJson.command(it.command))} ?: JSONObject.NULL).toString(),at)
    internal fun decode(r:DraftRecord):OwnedDocument {
        require(r.version==VERSION && r.id.isNotBlank());val j=JSONObject(r.payload)
        OwnedJson.keys(j,"feature","schema","generation","revision","operationId","baseline","fields","frozen")
        require(j.get("feature")=="owned-meeting-edit" && j.get("schema")==1)
        fun uuid(key:String)=OwnedJson.text(j,key).also {require(UUID.fromString(it).toString()==it)}
        val baseline=OwnedJson.baseline(j.getJSONObject("baseline"));val fields=OwnedJson.fields(j.getJSONObject("fields"))
        require(fields.guests.map {it.ref}==baseline.rights.guests.map {it.ref})
        require(fields.event.calendarId==baseline.original.calendarId && fields.event.targetIdentity==baseline.identity &&
            fields.event.availability==baseline.original.availability && fields.event.recurrence==baseline.original.recurrence &&
            fields.event.defaultVisibilityCalendar==baseline.original.defaultVisibilityCalendar && fields.event.defaultVisibilityIdentity==baseline.original.defaultVisibilityIdentity)
        val op=uuid("operationId")
        val frozen=if(j.isNull("frozen")) null else j.getJSONObject("frozen").let {
            OwnedJson.keys(it,"schema","operationId","selected","token","fingerprintSchema","fingerprint","command")
            require(it.get("schema")==1 && it.get("fingerprintSchema")==1)
            FrozenOwnedMeeting(OwnedJson.text(it,"operationId"),OwnedJson.ref(it.getJSONObject("selected")),EditToken(OwnedJson.text(it,"token")),
                OwnedJson.command(it.getJSONObject("command")),OwnedJson.text(it,"fingerprint"))
        }
        frozen?.let {
            require(it.operationId==op && it.selected==baseline.selected && it.token==baseline.token && it.fingerprint==OwnedJson.fingerprint(fields))
            require(it.fingerprint.matches(Regex("[0-9a-f]{64}")));OwnedPatch.validateFrozenRights(baseline,it.command)
        }
        return OwnedDocument(r.id,uuid("generation"),uuid("revision"),op,baseline,fields,frozen)
    }
}

internal object OwnedJson {
    fun keys(j:JSONObject,vararg names:String) {require(j.keys().asSequence().toSet()==names.toSet())}
    fun text(j:JSONObject,k:String)=j.get(k) as? String ?: error("Invalid text")
    fun ref(j:JSONObject):EventRef {keys(j,"calendar","event","start","end");return ResponseJson.ref(j).also {require(it.eventId>0 && it.instanceEnd>it.instanceStart)}}
    private fun nullable(j:JSONObject,k:String)=if(j.isNull(k)) null else text(j,k)
    private fun strings(j:JSONArray)=(0 until j.length()).map {j.get(it) as? String ?: error("Invalid string")}
    private val rawKeys=listOf("calendarId","title","startDate","endDate","startTime","endTime","zone","location","description","alerts","startOffset","endOffset","availability","privacy","defaultVisibilityCalendar")
    /** V9 raw protocol 1 is independent from ordinary editor envelope/fingerprint evolution. */
    fun raw(f:EventEditorFields)=JSONObject().apply {
        val values=listOf(f.calendarId,f.title,f.startDate,f.endDate,f.startTime,f.endTime,f.zone,f.location,f.description,f.alerts,f.startOffset,f.endOffset,f.availability,f.privacy,f.defaultVisibilityCalendar)
        rawKeys.zip(values).forEach {(k,v)->put(k,v)}
        put("allDay",f.allDay);put("targetIdentity",f.targetIdentity?.let(CalendarIdentityJson::encode) ?: JSONObject.NULL)
        put("defaultVisibilityIdentity",f.defaultVisibilityIdentity?.let(CalendarIdentityJson::encode) ?: JSONObject.NULL)
        put("recurrence",RecurrenceEditorFieldsJson.encode(f.recurrence))
    }
    private fun raw(j:JSONObject):EventEditorFields {
        require(j.keys().asSequence().toSet()==rawKeys.toSet()+setOf("allDay","targetIdentity","defaultVisibilityIdentity","recurrence"))
        rawKeys.forEach {text(j,it)}
        return EventEditorFields(text(j,"calendarId"),text(j,"title"),text(j,"startDate"),text(j,"endDate"),text(j,"startTime"),text(j,"endTime"),j.get("allDay") as Boolean,
            text(j,"zone"),text(j,"location"),text(j,"description"),text(j,"alerts"),text(j,"startOffset"),text(j,"endOffset"),text(j,"availability"),text(j,"privacy"),text(j,"defaultVisibilityCalendar"),
            CalendarIdentityJson.optional(j,"targetIdentity"),CalendarIdentityJson.optional(j,"defaultVisibilityIdentity"),RecurrenceEditorFieldsJson.decode(j.getJSONObject("recurrence")))
    }
    fun fields(f:OwnedMeetingFields)=JSONObject().put("event",raw(f.event)).put("zoneIntent",f.zoneIntent)
        .put("guests",JSONArray(f.guests.map {JSONObject().put("ref",it.ref.value).put("removed",it.removed).put("type",it.type)}))
        .put("added",JSONArray(f.added.map {JSONObject().put("id",it.rowId).put("email",it.email).put("name",it.name).put("type",it.type)}))
    fun fields(j:JSONObject):OwnedMeetingFields {
        keys(j,"event","zoneIntent","guests","added");val choice=j.getJSONArray("guests");val added=j.getJSONArray("added")
        val people=(0 until added.length()).map {added.getJSONObject(it).let {row -> keys(row,"id","email","name","type")
            MeetingCreationGuestFields(text(row,"id").also {id -> require(UUID.fromString(id).toString()==id)},text(row,"email"),text(row,"name"),text(row,"type"))}}
        require(people.size<=200 && people.map {it.rowId}.distinct().size==people.size)
        return OwnedMeetingFields(raw(j.getJSONObject("event")),text(j,"zoneIntent").also {require(it in setOf("KEEP","SET"))},
            (0 until choice.length()).map {choice.getJSONObject(it).let {row -> keys(row,"ref","removed","type");OwnedGuestChoice(MeetingGuestRef(text(row,"ref")),row.get("removed") as Boolean,text(row,"type"))}},people)
    }
    fun rights(r:MeetingScopeAccess)=JSONObject().put("edit",r.event.edit.name).put("fields",JSONArray(r.event.fields.map {it.name}.sorted()))
        .put("cancel",r.event.delete.name).put("limitation",r.event.limitation.name).put("recurrence",r.event.recurrence.name).put("respond",r.respond.name)
        .put("add",r.addGuests.name).put("types",JSONArray(r.allowedGuestTypes.map {it.name}.sorted()))
        .put("guests",JSONArray(r.guests.map {g -> JSONObject().put("ref",g.ref.value).put("email",g.attendee.email).put("name",g.attendee.name ?: JSONObject.NULL)
            .put("status",g.attendee.status).put("relationship",g.attendee.relationship).put("type",g.attendee.type).put("actions",JSONArray(g.allowed.map {it.name}.sorted()))}))
    fun rights(j:JSONObject):MeetingScopeAccess {
        keys(j,"edit","fields","cancel","limitation","recurrence","respond","add","types","guests");val guests=j.getJSONArray("guests")
        return MeetingScopeAccess(event=SeriesScopeCapabilities(AccessDecision.valueOf(text(j,"edit")),strings(j.getJSONArray("fields")).map(EditableField::valueOf).toSet(),AccessDecision.valueOf(text(j,"cancel")),
                SeriesActionLimitation.valueOf(text(j,"limitation")),AccessDecision.valueOf(text(j,"recurrence"))),respond=AccessDecision.valueOf(text(j,"respond")),
            addGuests=AccessDecision.valueOf(text(j,"add")),allowedGuestTypes=strings(j.getJSONArray("types")).map(MeetingGuestType::valueOf).toSet(),
            guests=(0 until guests.length()).map {guests.getJSONObject(it).let {g -> keys(g,"ref","email","name","status","relationship","type","actions")
                MeetingGuestRow(MeetingGuestRef(text(g,"ref")),EventAttendee(text(g,"email"),nullable(g,"name"),g.getInt("status"),g.getInt("relationship"),g.getInt("type")),strings(g.getJSONArray("actions")).map(MeetingGuestAction::valueOf).toSet())}})
    }
    fun baseline(b:OwnedBaseline)=JSONObject().put("selected",ResponseJson.ref(b.selected)).put("identity",CalendarIdentityJson.encode(b.identity)).put("token",b.token.value)
        .put("time",ResponseJson.time(b.time)).put("zone",b.zone?.id ?: JSONObject.NULL).put("original",raw(b.original)).put("displayZone",b.displayZone.id)
        .put("rights",rights(b.rights)).put("stamp",b.stamp).put("calendarLabel",b.calendarLabel).put("accountLabel",b.accountLabel)
    fun baseline(j:JSONObject):OwnedBaseline {
        keys(j,"selected","identity","token","time","zone","original","displayZone","rights","stamp","calendarLabel","accountLabel")
        val b=OwnedBaseline(ref(j.getJSONObject("selected")),requireNotNull(CalendarIdentityJson.optional(j,"identity")),EditToken(text(j,"token")),ResponseJson.time(j.getJSONObject("time")),nullable(j,"zone")?.let(ZoneId::of),
            raw(j.getJSONObject("original")),ZoneId.of(text(j,"displayZone")),rights(j.getJSONObject("rights")),text(j,"stamp"),text(j,"calendarLabel"),text(j,"accountLabel"))
        require(b.identity.calendarId==b.selected.calendarId && b.identity.account.name.isNotBlank() && b.identity.account.type.isNotBlank() && b.identity.owner==b.identity.account.name)
        require(b.original.targetIdentity==b.identity && b.original.calendarId==b.identity.calendarId.value.toString() && b.token.value.isNotBlank())
        require(b.stamp.matches(Regex("[0-9a-f]{64}")) && b.rights.guests.map {it.ref}.distinct().size==b.rights.guests.size)
        val bounds=when(val time=b.time) {is EventTime.Timed->time.start to time.end;is EventTime.AllDay->time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() to time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()}
        require(bounds==(b.selected.instanceStart to b.selected.instanceEnd))
        return b
    }
    fun command(c:MeetingCommand):JSONObject=when(c) {
        is MeetingCommand.CancelOwned -> JSONObject().put("kind","cancel").put("scope",c.scope.name)
        is MeetingCommand.EditOwned -> JSONObject().put("kind","edit").put("scope",c.scope.name).put("patch",patch(c.patch)).put("guests",JSONArray(c.guests.map {when(it) {
            is MeetingGuestChange.Add -> JSONObject().put("kind","add").put("email",it.guest.email).put("name",it.guest.name ?: JSONObject.NULL).put("type",it.guest.type.name)
            is MeetingGuestChange.ChangeType -> JSONObject().put("kind","type").put("ref",it.ref.value).put("type",it.type.name)
            is MeetingGuestChange.Remove -> JSONObject().put("kind","remove").put("ref",it.ref.value)
        }})).also {require(c.expectedMasterStart==null)}
        else -> error("Not an owned command")
    }
    fun command(j:JSONObject):MeetingCommand {
        require(text(j,"scope")=="THIS")
        return when(text(j,"kind")) {
            "cancel" -> {keys(j,"kind","scope");MeetingCommand.CancelOwned(RecurrenceScope.THIS)}
            "edit" -> {keys(j,"kind","scope","patch","guests");val a=j.getJSONArray("guests");require(a.length()<=400)
                MeetingCommand.EditOwned(RecurrenceScope.THIS,patch(j.getJSONObject("patch")),(0 until a.length()).map {a.getJSONObject(it).let {g -> when(text(g,"kind")) {
                    "add" -> {keys(g,"kind","email","name","type");MeetingGuestChange.Add(MeetingGuestInput(text(g,"email"),MeetingGuestType.valueOf(text(g,"type")),nullable(g,"name")))}
                    "type" -> {keys(g,"kind","ref","type");MeetingGuestChange.ChangeType(MeetingGuestRef(text(g,"ref")),MeetingGuestType.valueOf(text(g,"type")))}
                    "remove" -> {keys(g,"kind","ref");MeetingGuestChange.Remove(MeetingGuestRef(text(g,"ref")))}
                    else -> error("Unknown guest change")
                }}})}
            else -> error("Unknown command")
        }
    }
    private fun patch(p:EventPatch)=JSONObject().apply {
        fun <T> set(k:String,c:FieldChange<T>,encode:(T)->Any?={it}) {if(c is FieldChange.Set) put(k,encode(c.value) ?: JSONObject.NULL)}
        set("title",p.title);set("location",p.location);set("description",p.description);set("time",p.time,ResponseJson::time)
        set("zone",p.eventZone) {it?.id};set("alerts",p.alertMinutes) {JSONArray(it)};set("privacy",p.privacy);set("availability",p.availability)
    }
    private fun patch(j:JSONObject):EventPatch {
        require(j.keys().asSequence().all {it in setOf("title","location","description","time","zone","alerts","privacy","availability")})
        fun <T> set(k:String,read:()->T):FieldChange<T> = if(j.has(k)) FieldChange.Set(read()) else FieldChange.Keep
        return EventPatch(title=set("title") {text(j,"title")},location=set("location") {text(j,"location")},description=set("description") {text(j,"description")},
            time=set("time") {ResponseJson.time(j.getJSONObject("time"))},eventZone=set("zone") {nullable(j,"zone")?.let(ZoneId::of)},
            alertMinutes=set("alerts") {j.getJSONArray("alerts").let {a -> (0 until a.length()).map(a::getInt)}},
            privacy=set("privacy") {if(j.isNull("privacy")) null else j.getInt("privacy")},availability=set("availability") {if(j.isNull("availability")) null else j.getInt("availability")})
    }
    fun stamp(s:EditableMeeting):String {
        val d=s.details;val j=JSONObject().put("selected",ResponseJson.ref(d.occurrence.ref)).put("identity",d.calendar?.identity?.let(CalendarIdentityJson::encode))
            .put("token",s.token.value).put("rights",s.scopes[RecurrenceScope.THIS]?.let(::rights)).put("time",ResponseJson.time(d.occurrence.time))
            .put("title",d.occurrence.title).put("location",d.location).put("description",d.description).put("zone",d.eventZone?.id ?: JSONObject.NULL)
            .put("organizer",d.organizer ?: JSONObject.NULL).put("status",d.status ?: JSONObject.NULL).put("availability",d.availability ?: JSONObject.NULL).put("privacy",d.privacy ?: JSONObject.NULL)
            .put("reminders",JSONArray(d.reminders.map {JSONObject().put("minutes",it.minutesBefore).put("method",it.method)}))
        return hash(j)
    }
    fun fingerprint(f:OwnedMeetingFields)=hash(fields(f))
    /** Protocol 1: typed, sorted object keys, ordered arrays, UTF-16 code-unit strings. */
    private fun hash(j:JSONObject):String {
        fun canonical(v:Any?):String=when(v) {
            null,JSONObject.NULL -> "N;"
            is String -> "S${v.length}:"+v.map {it.code.toString(16).padStart(4,'0')}.joinToString("")
            is Boolean -> if(v) "T;" else "F;"
            is Number -> "I$v;"
            is JSONArray -> "A${v.length()}:"+(0 until v.length()).joinToString("") {canonical(v.get(it))}
            is JSONObject -> "O${v.length()}:"+v.keys().asSequence().toList().sorted().joinToString("") {canonical(it)+canonical(v.get(it))}
            else -> error("Unknown value")
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(canonical(j).toByteArray(Charsets.UTF_8)).joinToString("") {(it.toInt() and 255).toString(16).padStart(2,'0')}
    }
}
