package dev.swapnilraj.calendar.ui.events.meetings

import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Raw selections survive incomplete/unknown input. Neither choice is inferred on open. */
data class MeetingResponseFields(val scope: String = "", val response: String = "")

@ConsistentCopyVisibility
data class MeetingResponseLaunch internal constructor(val draftId: String, val selected: EventRef,
    val needsRecovery: Boolean, val generation: String)

sealed interface MeetingResponsePreparation {
    data class Ready(val launch: MeetingResponseLaunch) : MeetingResponsePreparation
    data object RetainedForReview : MeetingResponsePreparation
    data object Unavailable : MeetingResponsePreparation
}

internal val responseScopes = listOf(RecurrenceScope.THIS, RecurrenceScope.ALL)
internal fun responseRights(source: EditableMeeting) = responseScopes.associateWith { source.scopes[it]?.respond ?: AccessDecision.UNKNOWN }

/** Explicit action only; missing restored records are never seeded or silently replaced. */
suspend fun prepareMeetingResponseDraft(source: EditableMeeting, draftId: String, store: AtomicDraftStore,
    clock: Clock = Clock.systemUTC()): MeetingResponsePreparation {
    if (draftId.isBlank()) return MeetingResponsePreparation.Unavailable
    val old = try { store.load(draftId) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return MeetingResponsePreparation.Unavailable }
    if (old != null) return existingResponseLaunch(old, source.details.occurrence.ref, draftId)
    if (responseRights(source).values.none { it == AccessDecision.ALLOWED } || source.token.value.isBlank())
        return MeetingResponsePreparation.Unavailable
    val document = MeetingResponseDocument.initial(source, draftId)
    val replacement = MeetingResponseDraftCodec.record(document, clock.instant())
    val result = try { store.compareAndSet(draftId, null, replacement) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DraftChangeResult.Unavailable }
    return when (result) {
        DraftChangeResult.Applied -> MeetingResponsePreparation.Ready(document.launch())
        DraftChangeResult.Changed -> {
            val collision = try { store.load(draftId) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (collision != null) existingResponseLaunch(collision, source.details.occurrence.ref, draftId) else MeetingResponsePreparation.RetainedForReview
        }
        DraftChangeResult.Unavailable -> {
            val readBack = try { store.load(draftId) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (readBack == replacement) MeetingResponsePreparation.Ready(document.launch())
            else MeetingResponsePreparation.Unavailable
        }
    }
}
private fun existingResponseLaunch(record: DraftRecord, selected: EventRef, expectedId: String): MeetingResponsePreparation {
    val document = try { MeetingResponseDraftCodec.decode(record) } catch (_: Exception) { return MeetingResponsePreparation.RetainedForReview }
    return if (record.id == expectedId && document.selected == selected) MeetingResponsePreparation.Ready(document.launch())
        else MeetingResponsePreparation.RetainedForReview
}

internal data class FrozenMeetingResponse(val operationId: String, val selected: EventRef, val token: EditToken,
    val command: MeetingCommand.Respond) {
    fun encode(): JSONObject = JSONObject().put("kind", "respond").put("operationId", operationId)
        .put("selected", ResponseJson.ref(selected)).put("token", token.value).put("scope", command.scope.name).put("response", command.response.name)
    companion object {
        fun decode(value: JSONObject): FrozenMeetingResponse {
            require(value.keys().asSequence().toSet() == setOf("kind", "operationId", "selected", "token", "scope", "response"))
            require(value.getString("kind") == "respond")
            val scope = RecurrenceScope.valueOf(value.getString("scope")); require(scope in responseScopes)
            return FrozenMeetingResponse(value.getString("operationId").also { require(it.isNotBlank()) }, ResponseJson.ref(value.getJSONObject("selected")),
                EditToken(value.getString("token").also { require(it.isNotBlank()) }), MeetingCommand.Respond(scope, MeetingResponse.valueOf(value.getString("response"))))
        }
    }
}

internal data class MeetingResponseDocument(val id: String, val generation: String, val revision: String,
    val selected: EventRef, val token: EditToken, val title: String, val time: EventTime,
    val calendarLabel: String?, val accountLabel: String?, val originalStatus: Int?, val recurring: Boolean,
    val rights: Map<RecurrenceScope, AccessDecision>, val fields: MeetingResponseFields,
    val operationId: String, val frozen: FrozenMeetingResponse? = null) {
    fun launch() = MeetingResponseLaunch(id, selected, frozen != null, generation)
    companion object {
        fun initial(source: EditableMeeting, id: String): MeetingResponseDocument {
            val details = source.details
            return MeetingResponseDocument(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), details.occurrence.ref,
                source.token, details.occurrence.title, details.occurrence.time, details.calendar?.displayName,
                details.calendar?.account?.name, details.selfAttendeeStatus, details.occurrence.ref.recurrence != null || source.master != null,
                responseRights(source), MeetingResponseFields(), UUID.randomUUID().toString())
        }
    }
}

/** Dedicated feature tag/version. No ordinary event or series draft parser/writer is used. */
object MeetingResponseDraftCodec {
    const val VERSION = 3
    fun describe(record: DraftRecord): MeetingResponseLaunch? = try { decode(record).launch() } catch (_: Exception) { null }
    internal fun record(value: MeetingResponseDocument, updatedAt: Instant) = DraftRecord(value.id, VERSION, JSONObject().apply {
        put("feature", "meeting-response"); put("schema", 1); put("generation", value.generation); put("revision", value.revision)
        put("selected", ResponseJson.ref(value.selected)); put("token", value.token.value); put("title", value.title); put("time", ResponseJson.time(value.time))
        put("calendarLabel", value.calendarLabel ?: JSONObject.NULL); put("accountLabel", value.accountLabel ?: JSONObject.NULL)
        put("originalStatus", value.originalStatus ?: JSONObject.NULL); put("recurring", value.recurring)
        put("rights", JSONObject().apply { responseScopes.forEach { put(it.name, value.rights.getValue(it).name) } })
        put("scope", value.fields.scope); put("response", value.fields.response); put("operationId", value.operationId)
        value.frozen?.let { put("frozen", it.encode()) }
    }.toString(), updatedAt)
    internal fun decode(record: DraftRecord): MeetingResponseDocument {
        require(record.id.isNotBlank() && record.version == VERSION)
        val value = JSONObject(record.payload)
        require(value.keys().asSequence().all { it in setOf("feature", "schema", "generation", "revision", "selected", "token", "title", "time", "calendarLabel", "accountLabel", "originalStatus", "recurring", "rights", "scope", "response", "operationId", "frozen") })
        require(value.getString("feature") == "meeting-response" && value.getInt("schema") == 1)
        val selected = ResponseJson.ref(value.getJSONObject("selected")); val token = EditToken(value.getString("token").also { require(it.isNotBlank()) })
        val id = value.getString("operationId").also { require(it.isNotBlank()) }
        val rights = value.getJSONObject("rights"); require(rights.keys().asSequence().toSet() == setOf("THIS", "ALL"))
        val fields = MeetingResponseFields(value.getString("scope"), value.getString("response"))
        val frozen = if (value.has("frozen")) FrozenMeetingResponse.decode(value.getJSONObject("frozen")) else null
        frozen?.let { require(it.selected == selected && it.token == token && it.operationId == id && it.command.scope.name == fields.scope && it.command.response.name == fields.response) }
        fun nullableString(key: String): String? = if (value.isNull(key)) null else value.getString(key)
        val mappedRights = responseScopes.associateWith { AccessDecision.valueOf(rights.getString(it.name)) }
        require(frozen == null || mappedRights[frozen.command.scope] == AccessDecision.ALLOWED)
        return MeetingResponseDocument(record.id, value.getString("generation").also { UUID.fromString(it) }, value.getString("revision").also { UUID.fromString(it) },
            selected, token, value.getString("title"), ResponseJson.time(value.getJSONObject("time")), nullableString("calendarLabel"), nullableString("accountLabel"),
            if (value.isNull("originalStatus")) null else value.getInt("originalStatus"), value.getBoolean("recurring"),
            mappedRights, fields, id, frozen)
    }
}

internal object ResponseJson {
    fun ref(value: EventRef) = JSONObject().put("calendar", value.calendarId.value).put("event", value.eventId)
        .put("start", value.instanceStart.toString()).put("end", value.instanceEnd.toString()).apply {
            value.recurrence?.let { put("master", it.masterEventId); put("original", it.originalStart.toString()) }
        }
    fun ref(value: JSONObject): EventRef {
        require(value.keys().asSequence().all { it in setOf("calendar", "event", "start", "end", "master", "original") })
        require(value.has("master") == value.has("original"))
        return EventRef(CalendarId(value.getLong("calendar")), value.getLong("event"), Instant.parse(value.getString("start")), Instant.parse(value.getString("end")),
            if (value.has("master")) RecurrenceRef(value.getLong("master"), Instant.parse(value.getString("original"))) else null)
    }
    fun time(value: EventTime) = JSONObject().apply { when (value) {
        is EventTime.Timed -> { put("kind", "timed"); put("start", value.start.toString()); put("end", value.end.toString()) }
        is EventTime.AllDay -> { put("kind", "allDay"); put("start", value.dates.start.toString()); put("end", value.dates.endExclusive.toString()) }
    } }
    fun time(value: JSONObject): EventTime {
        require(value.keys().asSequence().toSet() == setOf("kind", "start", "end"))
        return when (value.getString("kind")) {
            "timed" -> EventTime.Timed(Instant.parse(value.getString("start")), Instant.parse(value.getString("end")))
            "allDay" -> EventTime.AllDay(CalendarDateRange(LocalDate.parse(value.getString("start")), LocalDate.parse(value.getString("end"))))
            else -> throw IllegalArgumentException()
        }
    }
}
