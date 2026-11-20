package dev.swapnilraj.calendar.ui.events.meetings.owned

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*

internal object OwnedPatch {
    fun temporalChanged(f:EventEditorFields,o:EventEditorFields)=listOf(f.startDate,f.endDate,f.startTime,f.endTime,f.allDay,f.zone,f.startOffset,f.endOffset)!=
        listOf(o.startDate,o.endDate,o.startTime,o.endTime,o.allDay,o.zone,o.startOffset,o.endOffset)
    fun canEdit(b:OwnedBaseline,field:EditableField)=b.rights.event.edit==AccessDecision.ALLOWED && field in b.rights.event.fields &&
        field!=EditableField.AVAILABILITY && (field!=EditableField.TIME || b.zone!=null || EditableField.ZONE in b.rights.event.fields)
    fun build(b:OwnedBaseline,f:OwnedMeetingFields):MeetingCommand.EditOwned {
        val o=b.original;val e=f.event
        require(e.calendarId==o.calendarId && e.targetIdentity==o.targetIdentity && e.defaultVisibilityCalendar==o.defaultVisibilityCalendar && e.defaultVisibilityIdentity==o.defaultVisibilityIdentity)
        require(e.recurrence==o.recurrence && e.availability==o.availability)
        fun <T> changed(field:EditableField,before:T,after:T):FieldChange<T> {
            if(before==after) return FieldChange.Keep
            require(canEdit(b,field));return FieldChange.Set(after)
        }
        val title=changed(EditableField.TITLE,o.title,e.title)
        val location=changed(EditableField.LOCATION,o.location,e.location)
        val description=changed(EditableField.DESCRIPTION,o.description,e.description)
        val temporal=temporalChanged(e,o);val zone=f.zoneIntent=="SET"
        require(f.zoneIntent in setOf("KEEP","SET"));require(e.zone==o.zone || zone)
        if(e.allDay!=o.allDay) require(canEdit(b,EditableField.TIME) && canEdit(b,EditableField.ZONE) && b.time is EventTime.Timed && zone)
        val normalized=if(temporal || zone) {
            require(!temporal || canEdit(b,EditableField.TIME));require(!zone || canEdit(b,EditableField.ZONE))
            require(b.zone!=null || zone)
            EventEditorController.normalizedDraft(e.copy(alerts="",availability="",privacy="",recurrence=RecurrenceEditorFields())).also {
                require(it.time is EventTime.AllDay || it.eventZone?.let(ProviderTimeZoneCompatibility::isCompatible)==true)
            }
        } else null
        val alerts:FieldChange<List<Int>> = if(e.alerts==o.alerts) FieldChange.Keep else {
            require(canEdit(b,EditableField.ALERTS));FieldChange.Set(if(e.alerts.isBlank()) emptyList() else e.alerts.split(',').map {it.trim().toInt().also {n->require(n>=0)}}.distinct())
        }
        val privacy:FieldChange<Int?> = if(e.privacy==o.privacy) FieldChange.Keep else {
            require(canEdit(b,EditableField.PRIVACY));FieldChange.Set(e.privacy.takeIf {it.isNotBlank()}?.toInt()?.also {require(it in 0..3)})
        }
        require(f.guests.map {it.ref}==b.rights.guests.map {it.ref})
        val changes=mutableListOf<MeetingGuestChange>()
        f.guests.zip(b.rights.guests).forEach {(choice,row)->
            if(choice.removed) {require(MeetingGuestAction.REMOVE in row.allowed);changes+=MeetingGuestChange.Remove(row.ref)}
            else if(choice.type.isNotEmpty()) {
                val type=MeetingGuestType.valueOf(choice.type);require(MeetingGuestAction.CHANGE_TYPE in row.allowed && type in b.rights.allowedGuestTypes)
                if(nativeType(type)!=row.attendee.type) changes+=MeetingGuestChange.ChangeType(row.ref,type)
            }
        }
        require(f.added.size<=200 && f.added.map {it.rowId}.distinct().size==f.added.size)
        if(f.added.isNotEmpty()) require(b.rights.addGuests==AccessDecision.ALLOWED)
        val emails=(b.rights.guests.map {it.attendee.email}+b.identity.owner.orEmpty()).map {it.trim().lowercase(java.util.Locale.ROOT)}.toMutableSet()
        f.added.forEach {
            val type=MeetingGuestType.valueOf(it.type);require(type in b.rights.allowedGuestTypes)
            require(it.email==it.email.trim() && it.name==it.name.trim() && it.email.length<=254 && it.name.length<=256)
            require(it.email.all {c->c.code in 33..126} && it.email.matches(Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")) && it.name.none {c->c.isISOControl()})
            require(emails.add(it.email.lowercase(java.util.Locale.ROOT)))
            changes+=MeetingGuestChange.Add(MeetingGuestInput(it.email,type,it.name.takeIf(String::isNotEmpty)))
        }
        val patch=EventPatch(title=title,location=location,description=description,
            time=if(temporal) FieldChange.Set(requireNotNull(normalized).time) else FieldChange.Keep,
            eventZone=if(zone) FieldChange.Set(requireNotNull(normalized).eventZone) else FieldChange.Keep,
            alertMinutes=alerts,privacy=privacy)
        require(patch!=EventPatch() || changes.isNotEmpty())
        return MeetingCommand.EditOwned(RecurrenceScope.THIS,patch,changes)
    }
    fun nativeType(type:MeetingGuestType)=when(type) {MeetingGuestType.UNSPECIFIED->0;MeetingGuestType.REQUIRED->1;MeetingGuestType.OPTIONAL->2}
    /** Structural rights from the captured protocol; never parses historical raw fields. */
    fun validateFrozenRights(b:OwnedBaseline,c:MeetingCommand) {
        when(c) {
            is MeetingCommand.CancelOwned -> require(c.scope==RecurrenceScope.THIS && b.rights.event.delete==AccessDecision.ALLOWED)
            is MeetingCommand.EditOwned -> {
                require(c.scope==RecurrenceScope.THIS && c.expectedMasterStart==null && b.rights.event.edit==AccessDecision.ALLOWED)
                val p=c.patch
                val fields=listOf(EditableField.TITLE to p.title,EditableField.LOCATION to p.location,EditableField.DESCRIPTION to p.description,
                    EditableField.TIME to p.time,EditableField.ZONE to p.eventZone,EditableField.ALERTS to p.alertMinutes,EditableField.PRIVACY to p.privacy)
                fields.forEach {(f,v)->require(v==FieldChange.Keep || f in b.rights.event.fields)}
                require(p.availability==FieldChange.Keep)
                val refs=mutableSetOf<MeetingGuestRef>()
                c.guests.forEach {when(it) {
                    is MeetingGuestChange.Add -> require(b.rights.addGuests==AccessDecision.ALLOWED && it.guest.type in b.rights.allowedGuestTypes)
                    is MeetingGuestChange.ChangeType -> require(refs.add(it.ref) && b.rights.guests.singleOrNull {g->g.ref==it.ref}?.let {g->MeetingGuestAction.CHANGE_TYPE in g.allowed}==true && it.type in b.rights.allowedGuestTypes)
                    is MeetingGuestChange.Remove -> require(refs.add(it.ref) && b.rights.guests.singleOrNull {g->g.ref==it.ref}?.let {g->MeetingGuestAction.REMOVE in g.allowed}==true)
                }}
            }
            else -> error("Not an owned command")
        }
    }
    fun expectedRef(b:OwnedBaseline,c:MeetingCommand):EventRef {
        val time=(c as? MeetingCommand.EditOwned)?.patch?.time?.let {(it as? FieldChange.Set)?.value} ?: b.time
        return when(time) {
            is EventTime.Timed -> b.selected.copy(instanceStart=time.start,instanceEnd=time.end)
            is EventTime.AllDay -> b.selected.copy(instanceStart=time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant(),instanceEnd=time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())
        }
    }
}
