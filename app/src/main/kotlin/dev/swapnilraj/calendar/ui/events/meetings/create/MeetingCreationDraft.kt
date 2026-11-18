package dev.swapnilraj.calendar.ui.events.meetings.create

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Invalid partial guest input is local draft data, never a backend command. */
data class MeetingCreationGuestFields(val rowId: String = UUID.randomUUID().toString(),
    val email: String = "", val name: String = "", val type: String = "")
data class MeetingCreationFields(val event: EventEditorFields, val guests: List<MeetingCreationGuestFields> = emptyList())
@ConsistentCopyVisibility
data class MeetingCreationLaunch internal constructor(val draftId: String, val generation: String, val needsRecovery: Boolean)
sealed interface MeetingCreationPreparation {
    data class Ready(val launch: MeetingCreationLaunch, val exactRecord: DraftRecord) : MeetingCreationPreparation
    data object RetainedForReview : MeetingCreationPreparation
    data object Unavailable : MeetingCreationPreparation
}

/** Explicit new action only. A collision is retained; opening/restoring never calls this factory. */
suspend fun prepareMeetingCreationDraft(initial: EventEditorFields, draftId: String, store: AtomicDraftStore,
    clock: Clock = Clock.systemUTC()): MeetingCreationPreparation {
    if (draftId.isBlank()) return MeetingCreationPreparation.Unavailable
    val doc = MeetingCreationDocument(draftId, uuid(), uuid(), MeetingCreationFields(initial), uuid())
    val record = MeetingCreationDraftCodec.record(doc, clock.instant())
    val changed = try { store.compareAndSet(draftId, null, record) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { DraftChangeResult.Unavailable }
    if (changed == DraftChangeResult.Changed) return MeetingCreationPreparation.RetainedForReview
    val actual = try { store.load(draftId) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return MeetingCreationPreparation.Unavailable }
    return if (actual == record) MeetingCreationPreparation.Ready(doc.launch(), record) else MeetingCreationPreparation.Unavailable
}
internal fun uuid() = UUID.randomUUID().toString()
internal fun validIdentity(value: CalendarIdentity) = value.account.name.isNotBlank() && value.account.type.isNotBlank()
internal data class MeetingCreationAuthority(val identity: CalendarIdentity, val token: EditToken,
    val allowedTypes: Set<MeetingGuestType>)
internal data class FrozenMeetingCreation(val operationId: String, val token: EditToken,
    val draft: EventDraft, val guests: List<MeetingGuestInput>) {
    fun retirement() = RetirableOperation.MeetingCreate(operationId, token, draft, guests)
}
internal data class MeetingCreationDocument(val id: String, val generation: String, val revision: String,
    val fields: MeetingCreationFields, val operationId: String, val authority: MeetingCreationAuthority? = null,
    val policyAccepted: Boolean = false, val frozen: FrozenMeetingCreation? = null) {
    fun launch() = MeetingCreationLaunch(id, generation, frozen != null)
}

/** Dedicated schema; newer/unknown data is never offered to an ordinary event decoder. */
object MeetingCreationDraftCodec {
    const val VERSION = 6
    fun describe(record: DraftRecord): MeetingCreationLaunch? = try { decode(record).launch() } catch (_: Exception) { null }
    internal fun record(d: MeetingCreationDocument, at: Instant) = DraftRecord(d.id, VERSION, JSONObject().apply {
        put("feature", "meeting-create"); put("schema", 1); put("generation", d.generation); put("revision", d.revision)
        put("operationId", d.operationId); put("event", raw(d.fields.event))
        put("guests", JSONArray(d.fields.guests.map { JSONObject().put("id", it.rowId).put("email", it.email).put("name", it.name).put("type", it.type) }))
        put("policyAccepted", d.policyAccepted)
        put("authority", d.authority?.let { JSONObject().put("identity", CalendarIdentityJson.encode(it.identity)).put("token", it.token.value)
            .put("allowedTypes", JSONArray(it.allowedTypes.map { type -> type.name }.sorted())) } ?: JSONObject.NULL)
        put("frozen", d.frozen?.let { JSONObject().put("schema", 1).put("operationId", it.operationId).put("token", it.token.value)
            .put("draft", draft(it.draft)).put("guests", JSONArray(it.guests.map { guest -> JSONObject().put("email", guest.email)
                .put("name", guest.name ?: JSONObject.NULL).put("type", guest.type.name) })) } ?: JSONObject.NULL)
    }.toString(), at)
    internal fun decode(record: DraftRecord): MeetingCreationDocument {
        require(record.version == VERSION && record.id.isNotBlank())
        val p = JSONObject(record.payload)
        keys(p, "feature", "schema", "generation", "revision", "operationId", "event", "guests", "policyAccepted", "authority", "frozen")
        require(text(p,"feature") == "meeting-create" && p.get("schema") == 1)
        val generation = text(p,"generation").also { require(UUID.fromString(it).toString() == it) }; val revision = text(p,"revision").also { require(UUID.fromString(it).toString() == it) }
        val operationId = text(p,"operationId").also { require(UUID.fromString(it).toString() == it) }
        val rawGuests = p.getJSONArray("guests").objects().map { keys(it,"id","email","name","type")
            MeetingCreationGuestFields(text(it,"id").also { id -> require(UUID.fromString(id).toString() == id) }, text(it,"email"), text(it,"name"), text(it,"type")) }
        require(rawGuests.map { it.rowId }.distinct().size == rawGuests.size)
        val fields = MeetingCreationFields(raw(p.getJSONObject("event")), rawGuests)
        val authority = if (p.isNull("authority")) null else p.getJSONObject("authority").let {
            keys(it,"identity","token","allowedTypes")
            val identity = CalendarIdentityJson.optional(it,"identity")!!.also { pin -> require(validIdentity(pin)) }
            val types = it.getJSONArray("allowedTypes").let { array -> (0 until array.length()).map { i -> MeetingGuestType.valueOf(array.getString(i)) } }
            require(types.isNotEmpty() && types.distinct().size == types.size)
            MeetingCreationAuthority(identity, EditToken(text(it,"token").also { token -> require(token.isNotBlank()) }), types.toSet())
        }
        val frozen = if (p.isNull("frozen")) null else p.getJSONObject("frozen").let {
            keys(it,"schema","operationId","token","draft","guests"); require(it.get("schema") == 1)
            FrozenMeetingCreation(text(it,"operationId"), EditToken(text(it,"token")), draft(it.getJSONObject("draft")),
                it.getJSONArray("guests").objects().map { guest -> keys(guest,"email","name","type")
                    MeetingGuestInput(text(guest,"email"), MeetingGuestType.valueOf(text(guest,"type")), if (guest.isNull("name")) null else text(guest,"name")) })
        }
        require(p.get("policyAccepted") is Boolean)
        val accepted = p.getBoolean("policyAccepted")
        frozen?.let {
            require(authority != null && accepted && it.operationId == operationId && it.token == authority.token && it.draft.targetIdentity == authority.identity)
            require(it.draft.calendarId == authority.identity.calendarId && it.draft.recurrence == null && it.guests.isNotEmpty())
            require(it.guests.all { guest -> guest.type in authority.allowedTypes })
            require(it.draft == EventEditorController.normalizedDraft(fields.event))
            require(it.guests == typedGuests(fields.guests, authority.allowedTypes))
        }
        return MeetingCreationDocument(record.id, generation, revision, fields, operationId, authority, accepted, frozen)
    }
    private val rawText = setOf("calendarId","title","startDate","endDate","startTime","endTime","zone","location","description","alerts","startOffset","endOffset","availability","privacy","defaultVisibilityCalendar")
    private fun raw(f: EventEditorFields) = JSONObject().apply {
        val values = listOf(f.calendarId,f.title,f.startDate,f.endDate,f.startTime,f.endTime,f.zone,f.location,f.description,f.alerts,f.startOffset,f.endOffset,f.availability,f.privacy,f.defaultVisibilityCalendar)
        rawText.zip(values).forEach { (key,value) -> put(key,value) }
        put("allDay",f.allDay); put("targetIdentity",f.targetIdentity?.let(CalendarIdentityJson::encode) ?: JSONObject.NULL)
        put("defaultVisibilityIdentity",f.defaultVisibilityIdentity?.let(CalendarIdentityJson::encode) ?: JSONObject.NULL)
        put("recurrence",RecurrenceEditorFieldsJson.encode(f.recurrence))
    }
    private fun raw(p: JSONObject): EventEditorFields {
        require(p.keys().asSequence().toSet() == rawText + setOf("allDay","targetIdentity","defaultVisibilityIdentity","recurrence"))
        rawText.forEach { text(p,it) }; require(p.get("allDay") is Boolean)
        return EventEditorFields(text(p,"calendarId"),text(p,"title"),text(p,"startDate"),text(p,"endDate"),text(p,"startTime"),text(p,"endTime"),p.getBoolean("allDay"),text(p,"zone"),text(p,"location"),text(p,"description"),text(p,"alerts"),text(p,"startOffset"),text(p,"endOffset"),text(p,"availability"),text(p,"privacy"),text(p,"defaultVisibilityCalendar"),CalendarIdentityJson.optional(p,"targetIdentity"),CalendarIdentityJson.optional(p,"defaultVisibilityIdentity"),RecurrenceEditorFieldsJson.decode(p.getJSONObject("recurrence")))
    }
    private fun draft(d: EventDraft) = JSONObject().put("calendar",d.calendarId.value).put("time",time(d.time))
        .put("zone",d.eventZone?.id ?: JSONObject.NULL).put("title",d.title).put("location",d.location).put("description",d.description)
        .put("alerts",JSONArray(d.alertMinutes)).put("availability",d.availability ?: JSONObject.NULL).put("privacy",d.privacy ?: JSONObject.NULL)
        .put("targetIdentity",d.targetIdentity?.let(CalendarIdentityJson::encode) ?: JSONObject.NULL)
    private fun draft(p: JSONObject): EventDraft {
        keys(p,"calendar","time","zone","title","location","description","alerts","availability","privacy","targetIdentity")
        return EventDraft(CalendarId(p.getLong("calendar")),time(p.getJSONObject("time")),if(p.isNull("zone")) null else ZoneId.of(text(p,"zone")),text(p,"title"),text(p,"location"),text(p,"description"),p.getJSONArray("alerts").let { a -> (0 until a.length()).map { a.getInt(it) } },if(p.isNull("availability")) null else p.getInt("availability"),if(p.isNull("privacy")) null else p.getInt("privacy"),CalendarIdentityJson.optional(p,"targetIdentity"))
    }
    private fun time(t: EventTime) = JSONObject().apply { when(t) {
        is EventTime.Timed -> { put("kind","timed"); put("start",t.start.toString()); put("end",t.end.toString()) }
        is EventTime.AllDay -> { put("kind","allDay"); put("start",t.dates.start.toString()); put("end",t.dates.endExclusive.toString()) }
    } }
    private fun time(p: JSONObject): EventTime { keys(p,"kind","start","end"); return when(text(p,"kind")) {
        "timed" -> EventTime.Timed(Instant.parse(text(p,"start")),Instant.parse(text(p,"end")))
        "allDay" -> EventTime.AllDay(CalendarDateRange(LocalDate.parse(text(p,"start")),LocalDate.parse(text(p,"end"))))
        else -> error("Unknown time")
    } }
    private fun keys(p: JSONObject,vararg keys: String) { require(p.keys().asSequence().toSet() == keys.toSet()) }
    private fun text(p: JSONObject,key: String) = p.get(key).let { require(it is String); it }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}

internal fun typedGuests(raw: List<MeetingCreationGuestFields>, types: Set<MeetingGuestType>): List<MeetingGuestInput> {
    require(raw.isNotEmpty() && raw.size <= 200)
    val seen = mutableSetOf<String>()
    return raw.map {
        require(it.email.length <= 256 && it.name.length <= 256 && it.email == it.email.trim() && it.name == it.name.trim())
        require(it.email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) && (it.email + it.name).none { c -> c.isISOControl() })
        require(seen.add(it.email.lowercase(java.util.Locale.ROOT)))
        val type = MeetingGuestType.valueOf(it.type); require(type in types)
        MeetingGuestInput(it.email,type,it.name.takeIf(String::isNotEmpty))
    }
}
