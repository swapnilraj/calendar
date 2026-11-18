package dev.swapnilraj.calendar.ui.events

import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class SeriesEditorAction { EDIT, DELETE }

data class SeriesEditorFields(val title: String = "", val location: String = "", val description: String = "",
    val alerts: String = "", val replaceAlerts: Boolean = false, val availability: String = "", val privacy: String = "",
    val temporal: SeriesTemporalFields? = null, val recurrence: SeriesRuleFields? = null)

@ConsistentCopyVisibility
data class SeriesEditorLaunch internal constructor(val draftId: String, val selected: EventRef,
    val scope: RecurrenceScope, val action: SeriesEditorAction, val needsRecovery: Boolean,
    val title: String, val rawDate: String, val generation: String? = null)

sealed interface SeriesDraftPreparationResult {
    data class Ready(val launch: SeriesEditorLaunch) : SeriesDraftPreparationResult
    data object Unavailable : SeriesDraftPreparationResult
}

internal val seriesUiFields = setOf(EditableField.TITLE, EditableField.LOCATION, EditableField.DESCRIPTION,
    EditableField.ALERTS, EditableField.AVAILABILITY, EditableField.PRIVACY)

/** UI support is narrower than future backend capabilities; missing/unknown scope rights never enable actions. */
fun canPrepareSeriesScope(source: EditableSeries, scope: RecurrenceScope, action: SeriesEditorAction): Boolean {
    if (scope == RecurrenceScope.FOLLOWING && seriesOriginalBoundary(source.selectedDetails.occurrence.ref, source.snapshot.start) == null) return false
    if (scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT &&
        (source.snapshot.coverage != SeriesCoverage.COMPLETE || source.snapshot.exceptions.isNotEmpty() || source.snapshot.exDates.isNotEmpty() ||
            listOf(source.snapshot.exDatesRaw, source.snapshot.rDatesRaw, source.snapshot.exRuleRaw).any { !it.isNullOrBlank() })) return false
    if (scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT && runCatching { SeriesMasterContext.from(source).nominalTail(source.selectedDetails.occurrence.ref)?.template }.getOrNull() == null) return false
    val rights = source.scopes[scope] ?: return false
    return when (action) {
        SeriesEditorAction.EDIT -> rights.edit == AccessDecision.ALLOWED &&
            (rights.fields.intersect(source.allowedFields).intersect(seriesUiFields + if (scope != RecurrenceScope.THIS) setOf(EditableField.TIME, EditableField.ZONE) else emptySet()).isNotEmpty() ||
                scope != RecurrenceScope.THIS && rights.recurrence == AccessDecision.ALLOWED)
        SeriesEditorAction.DELETE -> rights.delete == AccessDecision.ALLOWED
    }
}

/** Explicit action only. Restoration must load an existing durable record, never call this factory. */
suspend fun prepareSeriesDraft(source: EditableSeries, scope: RecurrenceScope, action: SeriesEditorAction,
    draftId: String, store: DraftStore, clock: Clock = Clock.systemUTC()): SeriesDraftPreparationResult = try {
    require(draftId.isNotBlank())
    val selected = source.selectedDetails.occurrence.ref
    require(source.snapshot.validatedSelection == selected)
    require(canPrepareSeriesScope(source, scope, action))
    val existing = store.load(draftId)
    if (existing != null) {
        val document = SeriesDraftCodec.decode(existing)
        require(document.selected == selected && document.scope == scope && document.action == action)
        SeriesDraftPreparationResult.Ready(document.launch())
    } else {
        val atomic = store as? AtomicDraftStore ?: error("Atomic draft storage required")
        val document = SeriesDraftDocument.initial(source, scope, action, draftId)
        val record = SeriesDraftCodec.record(document, clock.instant())
        val result = try { atomic.compareAndSet(draftId, null, record) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DraftChangeResult.Unavailable }
        val durable = store.load(draftId)
        if (result != DraftChangeResult.Changed && durable == record) SeriesDraftPreparationResult.Ready(document.launch())
        else if (result == DraftChangeResult.Changed && durable != null) {
            val collision = SeriesDraftCodec.decode(durable)
            require(collision.selected == selected && collision.scope == scope && collision.action == action)
            SeriesDraftPreparationResult.Ready(collision.launch())
        } else SeriesDraftPreparationResult.Unavailable
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { SeriesDraftPreparationResult.Unavailable }

internal data class SeriesDraftDocument(val id: String, val selected: EventRef, val identity: SeriesIdentity,
    val scope: RecurrenceScope, val action: SeriesEditorAction, val token: EditToken,
    val masterStart: RecurrenceStart, val rights: SeriesScopeCapabilities, val allowedFields: Set<EditableField>,
    val baseline: SeriesEditorFields, val fields: SeriesEditorFields, val rawDate: String,
    val hasDefaultAlerts: Boolean, val operationId: String, val submitted: Boolean = false,
    val frozen: FrozenSeriesSubmission? = null, val reviewRequired: Boolean = false,
    val schema: Int = 2, val generation: String? = UUID.randomUUID().toString(), val revision: String? = UUID.randomUUID().toString(),
    val context: SeriesMasterContext? = null) {
    fun launch() = SeriesEditorLaunch(id, selected, scope, action, submitted, fields.title, if (scope == RecurrenceScope.ALL) fields.temporal?.startDate ?: rawDate else rawDate, generation)
    companion object {
        fun initial(source: EditableSeries, scope: RecurrenceScope, action: SeriesEditorAction, id: String): SeriesDraftDocument {
            val master = source.masterFields
            val details = source.selectedDetails
            val masterFields = scope == RecurrenceScope.ALL || scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT
            val reminders = if (masterFields) master.reminders else details.reminders
            val fields = if (masterFields) SeriesEditorFields(master.title, master.location, master.description,
                availability = master.availability?.toString().orEmpty(), privacy = master.privacy?.toString().orEmpty())
            else SeriesEditorFields(details.occurrence.title, details.location, details.description,
                availability = details.availability?.toString().orEmpty(), privacy = details.privacy?.toString().orEmpty())
            val local = reminders.filter { it.method == CalendarContract.Reminders.METHOD_ALERT }
            val context = SeriesMasterContext.from(source)
            val presentation = if (scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT) requireNotNull(context.nominalTail(selected = details.occurrence.ref)) else context
            val raw = fields.copy(temporal = if (scope != RecurrenceScope.THIS && action == SeriesEditorAction.EDIT) SeriesTemporalFields.from(presentation) else null,
                recurrence = if (scope != RecurrenceScope.THIS && action == SeriesEditorAction.EDIT) SeriesRuleFields.from(context) else null,
                alerts = local.filter { it.minutesBefore != CalendarContract.Reminders.MINUTES_DEFAULT }
                .joinToString(",") { it.minutesBefore.toString() })
            val date = if (scope == RecurrenceScope.ALL) source.snapshot.start.date.toString()
            else if (scope == RecurrenceScope.FOLLOWING) when (val boundary = seriesOriginalBoundary(details.occurrence.ref, source.snapshot.start)) {
                is RecurrenceKey.Date -> boundary.date.toString()
                is RecurrenceKey.Timed -> boundary.instant.atZone((source.snapshot.start as RecurrenceStart.Timed).zone).toLocalDate().toString()
                null -> throw IllegalArgumentException()
            } else when (val time = details.occurrence.time) {
                is EventTime.AllDay -> time.dates.start.toString()
                is EventTime.Timed -> details.eventZone?.let { time.start.atZone(it).toLocalDate().toString() }.orEmpty()
            }
            return SeriesDraftDocument(id, details.occurrence.ref, source.snapshot.identity, scope, action, source.token,
                source.snapshot.start, source.scopes.getValue(scope), source.allowedFields, raw, raw, date,
                local.any { it.minutesBefore == CalendarContract.Reminders.MINUTES_DEFAULT }, UUID.randomUUID().toString(), schema = if (scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT) 3 else 2, context = context)
        }
    }
}

/** A dedicated opaque version: series drafts can never be restored into the one-off editor. */
object SeriesDraftCodec {
    const val VERSION = 2
    fun describe(record: DraftRecord): SeriesEditorLaunch? = try { decode(record).launch() } catch (_: Exception) { null }
    internal fun record(value: SeriesDraftDocument, now: Instant): DraftRecord {
        require(value.schema in 2..3 && value.generation != null && value.revision != null && value.context != null)
        require(value.schema != 3 || value.scope == RecurrenceScope.FOLLOWING && value.action == SeriesEditorAction.EDIT)
        return DraftRecord(value.id, VERSION, JSONObject().apply {
        put("feature", "series-editor"); put("schema", value.schema)
        put("generation", value.generation); put("revision", value.revision); put("context", SeriesTemporalJson.context(value.context)); put("selected", SeriesJson.ref(value.selected))
        put("calendar", value.identity.calendarId.value); put("master", value.identity.masterEventId)
        put("scope", value.scope.name); put("action", value.action.name); put("token", value.token.value)
        put("masterStart", SeriesJson.start(value.masterStart)); put("rights", rights(value.rights))
        put("allowedFields", JSONArray(value.allowedFields.map { it.name }.sorted()))
        put("baseline", fields(value.baseline)); put("fields", fields(value.fields)); put("rawDate", value.rawDate)
        put("hasDefaultAlerts", value.hasDefaultAlerts); put("operationId", value.operationId); put("submitted", value.submitted); put("reviewRequired", value.reviewRequired)
        value.frozen?.let { put("frozen", it.encode()) }
    }.toString(), now)
    }
    internal fun decode(record: DraftRecord): SeriesDraftDocument {
        require(record.version == VERSION && record.id.isNotBlank())
        val json = JSONObject(record.payload)
        val schema = (json.get("schema") as? Int) ?: error("Invalid schema")
        require(json.getString("feature") == "series-editor" && schema in 1..3)
        require(json.keys().asSequence().all { it in setOf("feature", "schema", "selected", "calendar", "master", "scope", "action", "token", "masterStart", "rights", "allowedFields", "baseline", "fields", "rawDate", "hasDefaultAlerts", "operationId", "submitted", "frozen", "reviewRequired") + if (schema >= 2) setOf("generation", "revision", "context") else emptySet() })
        val selected = SeriesJson.ref(json.getJSONObject("selected"))
        val identity = SeriesIdentity(CalendarId(json.getLong("calendar")), json.getLong("master"))
        require(selected.calendarId == identity.calendarId && (selected.recurrence?.masterEventId ?: selected.eventId) == identity.masterEventId)
        val scope = RecurrenceScope.valueOf(json.getString("scope"))
        val action = SeriesEditorAction.valueOf(json.getString("action"))
        require(if (schema == 3) scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT else scope != RecurrenceScope.FOLLOWING || action == SeriesEditorAction.DELETE)
        val id = json.getString("operationId").also { require(it.isNotBlank()) }
        val frozen = if (json.has("frozen")) FrozenSeriesSubmission.decode(json.getJSONObject("frozen")) else null
        val token = EditToken(json.getString("token").also { require(it.isNotBlank()) })
        val submitted = json.getBoolean("submitted"); require(!submitted || frozen != null)
        val masterStart = SeriesJson.start(json.getJSONObject("masterStart"))
        require(scope != RecurrenceScope.FOLLOWING || seriesOriginalBoundary(selected, masterStart) != null)
        val fields = fields(json.getJSONObject("fields"), schema)
        frozen?.let {
            require(it.operationId == id && it.selected == selected && it.identity == identity && it.token == token)
            require(it.scope == scope && it.action == action)
            require(scope != RecurrenceScope.FOLLOWING || if (action == SeriesEditorAction.DELETE) it.deleteMasterStart == masterStart else it.followingMasterStart == masterStart)
            require(it.command !is SeriesCommand.WholeEdit || it.command.intent.expectedMasterStart == masterStart)
        }
        val document = SeriesDraftDocument(record.id, selected, identity, scope, action, token, masterStart,
            rights(json.getJSONObject("rights"), schema), enums(json.getJSONArray("allowedFields")),
            fields(json.getJSONObject("baseline"), schema), fields, json.getString("rawDate"), json.getBoolean("hasDefaultAlerts"), id, submitted, frozen, json.getBoolean("reviewRequired"),
            schema, if (schema >= 2) json.getString("generation").also { require(UUID.fromString(it).toString() == it) } else null,
            if (schema >= 2) json.getString("revision").also { require(UUID.fromString(it).toString() == it) } else null,
            if (schema >= 2) SeriesTemporalJson.context(json.getJSONObject("context")) else null)
        document.context?.let { require(it.start == masterStart && it.calendar.calendarId == identity.calendarId && it.calendar.account.name.isNotBlank() && it.calendar.account.type.isNotBlank()) }
        require(scope != RecurrenceScope.THIS && action == SeriesEditorAction.EDIT || fields.temporal == null && fields.recurrence == null)
        require(scope != RecurrenceScope.FOLLOWING || if (action == SeriesEditorAction.DELETE) document.rights.delete == AccessDecision.ALLOWED else document.rights.edit == AccessDecision.ALLOWED)
        if (submitted && action == SeriesEditorAction.EDIT) require(frozen?.command == seriesCommand(document))
        return document
    }
    private fun fields(value: SeriesEditorFields) = JSONObject().put("title", value.title).put("location", value.location)
        .put("description", value.description).put("alerts", value.alerts).put("replaceAlerts", value.replaceAlerts)
        .put("availability", value.availability).put("privacy", value.privacy)
        .put("temporal", value.temporal?.let(SeriesTemporalJson::fields) ?: JSONObject.NULL)
        .put("recurrence", value.recurrence?.let(SeriesTemporalJson::rule) ?: JSONObject.NULL)
    private fun fields(value: JSONObject, schema: Int): SeriesEditorFields {
        require(value.keys().asSequence().toSet() == setOf("title", "location", "description", "alerts", "replaceAlerts", "availability", "privacy") + if (schema >= 2) setOf("temporal", "recurrence") else emptySet())
        fun text(key: String) = if (schema >= 2) SeriesTemporalJson.text(value, key) else value.getString(key)
        return SeriesEditorFields(text("title"), text("location"), text("description"),
            text("alerts"), if (schema >= 2) value.get("replaceAlerts") as? Boolean ?: error("Invalid alert choice") else value.getBoolean("replaceAlerts"), text("availability"), text("privacy"),
            if (schema >= 2 && !value.isNull("temporal")) SeriesTemporalJson.fields(value.getJSONObject("temporal")) else null,
            if (schema >= 2 && !value.isNull("recurrence")) SeriesTemporalJson.rule(value.getJSONObject("recurrence")) else null)
    }
    private fun rights(value: SeriesScopeCapabilities) = JSONObject().put("edit", value.edit.name).put("delete", value.delete.name)
        .put("fields", JSONArray(value.fields.map { it.name }.sorted())).put("limitation", value.limitation.name).put("recurrence", value.recurrence.name)
    private fun rights(value: JSONObject, schema: Int): SeriesScopeCapabilities {
        require(value.keys().asSequence().toSet() == setOf("edit", "delete", "fields", "limitation") + if (schema >= 2) setOf("recurrence") else emptySet())
        return SeriesScopeCapabilities(AccessDecision.valueOf(value.getString("edit")), enums(value.getJSONArray("fields")),
            AccessDecision.valueOf(value.getString("delete")), SeriesActionLimitation.valueOf(value.getString("limitation")),
            if (schema >= 2) AccessDecision.valueOf(value.getString("recurrence")) else AccessDecision.UNKNOWN)
    }
    private fun enums(value: JSONArray): Set<EditableField> = (0 until value.length()).map { EditableField.valueOf(value.getString(it)) }.toSet()
}

internal fun seriesCommand(document: SeriesDraftDocument): SeriesCommand {
    if (document.action == SeriesEditorAction.DELETE) return SeriesCommand.Delete(document.scope)
    val fields = document.fields; val base = document.baseline
    val allowed = document.rights.fields.intersect(document.allowedFields).intersect(seriesUiFields)
    fun <T> changed(field: EditableField, dirty: Boolean, value: () -> T): FieldChange<T> =
        if (field in allowed && dirty) FieldChange.Set(value()) else FieldChange.Keep
    fun metadata(raw: String): Int? = raw.takeIf(String::isNotBlank)?.toInt()
    val patch = EventPatch(
        title = changed(EditableField.TITLE, fields.title != base.title) { fields.title },
        location = changed(EditableField.LOCATION, fields.location != base.location) { fields.location },
        description = changed(EditableField.DESCRIPTION, fields.description != base.description) { fields.description },
        alertMinutes = changed(EditableField.ALERTS, fields.replaceAlerts) {
            if (fields.alerts.isBlank()) emptyList() else fields.alerts.split(',').map { part -> part.trim().toInt().also {
                require(it != CalendarContract.Reminders.MINUTES_DEFAULT)
            } }.distinct()
        },
        availability = changed(EditableField.AVAILABILITY, fields.availability != base.availability) {
            metadata(fields.availability).also { require(it == null || it in 0..2) }
        },
        privacy = changed(EditableField.PRIVACY, fields.privacy != base.privacy) {
            metadata(fields.privacy).also { require(it == null || it in 0..3) }
        },
    )
    return if (document.scope != RecurrenceScope.THIS) {
        val context = document.context
        val temporal = if (context != null) seriesTemporalIntent(if (document.scope == RecurrenceScope.FOLLOWING) requireNotNull(context.nominalTail(document.selected)) else context, fields.temporal, document.rights.copy(fields = allowed + document.rights.fields.intersect(document.allowedFields).intersect(setOf(EditableField.TIME, EditableField.ZONE)))) else FieldChange.Keep to RecurrenceDurationChange.KeepRaw
        val rule = if (context?.parsed != null && document.rights.recurrence == AccessDecision.ALLOWED) fields.recurrence?.changes(requireNotNull(context.parsed), following = document.scope == RecurrenceScope.FOLLOWING) ?: RecurrenceRuleChanges() else RecurrenceRuleChanges()
        if (document.scope == RecurrenceScope.FOLLOWING) SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(
            requireNotNull(seriesOriginalBoundary(document.selected, document.masterStart)), fields = patch, newTailStart = temporal.first, duration = temporal.second, rule = rule))
        else SeriesCommand.WholeEdit(RecurrenceEditIntent.WholeSeriesEdit(document.masterStart, fields = patch, newMasterStart = temporal.first, duration = temporal.second, rule = rule))
    }
        else SeriesCommand.OccurrenceEdit(patch)
}

internal data class FrozenSeriesSubmission(val operationId: String, val selected: EventRef, val identity: SeriesIdentity,
    val token: EditToken, val scope: RecurrenceScope, val action: SeriesEditorAction, val command: SeriesCommand,
    val deleteMasterStart: RecurrenceStart? = null, val followingMasterStart: RecurrenceStart? = null) {
    suspend fun execute(port: CalendarSeriesMutations) = port.mutate(operationId, selected, token, command)
    fun encode(): JSONObject = JSONObject().apply {
        val temporal = command is SeriesCommand.WholeEdit && (command.intent.newMasterStart != FieldChange.Keep || command.intent.duration != RecurrenceDurationChange.KeepRaw || command.intent.rule != RecurrenceRuleChanges())
        put("schema", if (command is SeriesCommand.FollowingEdit) 3 else if (temporal) 2 else 1); put("operationId", operationId); put("selected", SeriesJson.ref(selected))
        put("calendar", identity.calendarId.value); put("master", identity.masterEventId); put("token", token.value)
        put("scope", scope.name); put("action", action.name)
        when (val command = command) {
            is SeriesCommand.Delete -> {
                require(action == SeriesEditorAction.DELETE && command.scope == scope)
                put("kind", "delete")
                if (scope == RecurrenceScope.FOLLOWING) put("masterStart", SeriesJson.start(requireNotNull(deleteMasterStart)))
                else require(deleteMasterStart == null)
            }
            is SeriesCommand.OccurrenceEdit -> { require(scope == RecurrenceScope.THIS); put("kind", "occurrence"); put("patch", SeriesJson.patch(command.patch, selected, token)) }
            is SeriesCommand.WholeEdit -> {
                require(scope == RecurrenceScope.ALL)
                put("kind", "whole")
                if (temporal) put("intent", SeriesWholeEditJson.encode(command.intent, selected, token))
                else { put("patch", SeriesJson.patch(command.intent.fields, selected, token)); put("masterStart", SeriesJson.start(command.intent.expectedMasterStart)) }
            }
            is SeriesCommand.FollowingEdit -> {
                require(scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT && deleteMasterStart == null)
                put("kind", "following"); put("intent", SeriesFollowingEditJson.encode(command.intent, requireNotNull(followingMasterStart), selected, token))
            }
        }
    }
    companion object {
        fun decode(value: JSONObject): FrozenSeriesSubmission {
            val schema = (value.get("schema") as? Int) ?: error("Invalid frozen schema"); require(schema in 1..3)
            val selected = SeriesJson.ref(value.getJSONObject("selected")); val token = EditToken(value.getString("token"))
            val scope = RecurrenceScope.valueOf(value.getString("scope")); val action = SeriesEditorAction.valueOf(value.getString("action"))
            val kind = value.getString("kind")
            val allowedKeys = setOf("schema", "operationId", "selected", "calendar", "master", "token", "scope", "action", "kind") +
                when (kind) { "whole" -> if (schema >= 2) setOf("intent") else setOf("patch", "masterStart"); "following" -> setOf("intent"); "occurrence" -> setOf("patch"); "delete" -> if (scope == RecurrenceScope.FOLLOWING) setOf("masterStart") else emptySet(); else -> emptySet() }
            require(value.keys().asSequence().all { it in allowedKeys })
            if (schema == 3) {
                require(value.keys().asSequence().toSet() == allowedKeys)
                listOf("operationId", "token", "scope", "action", "kind").forEach { require(SeriesTemporalJson.text(value, it).isNotBlank()) }
                listOf("calendar", "master").forEach { require(value.get(it) is Int || value.get(it) is Long) }
            }
            require(schema == 1 && kind != "following" || schema == 2 && kind == "whole" && scope == RecurrenceScope.ALL && action == SeriesEditorAction.EDIT || schema == 3 && kind == "following" && scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.EDIT)
            val command = when (kind) {
                "delete" -> { require(action == SeriesEditorAction.DELETE); SeriesCommand.Delete(scope) }
                "occurrence" -> { require(action == SeriesEditorAction.EDIT && scope == RecurrenceScope.THIS); SeriesCommand.OccurrenceEdit(SeriesJson.patch(value.getJSONObject("patch"), selected, token)) }
                "following" -> SeriesCommand.FollowingEdit(SeriesFollowingEditJson.decode(value.getJSONObject("intent"), selected, token).first)
                "whole" -> { require(action == SeriesEditorAction.EDIT && scope == RecurrenceScope.ALL)
                    SeriesCommand.WholeEdit(if (schema >= 2) SeriesWholeEditJson.decode(value.getJSONObject("intent"), selected, token) else RecurrenceEditIntent.WholeSeriesEdit(SeriesJson.start(value.getJSONObject("masterStart")),
                        fields = SeriesJson.patch(value.getJSONObject("patch"), selected, token))) }
                else -> throw IllegalArgumentException()
            }
            require(scope != RecurrenceScope.FOLLOWING || action == SeriesEditorAction.DELETE || schema == 3)
            return FrozenSeriesSubmission(value.getString("operationId"), selected, SeriesIdentity(CalendarId(value.getLong("calendar")), value.getLong("master")), token, scope, action, command,
                if (scope == RecurrenceScope.FOLLOWING && action == SeriesEditorAction.DELETE) SeriesJson.start(value.getJSONObject("masterStart")) else null,
                if (kind == "following") SeriesFollowingEditJson.decode(value.getJSONObject("intent"), selected, token).second else null)
        }
    }
}

/** Uses the existing exact one-off primitive codec, never its writer or draft schema. */
internal object SeriesJson {
    fun ref(value: EventRef): JSONObject = FrozenEditorSubmission.Delete("codec", value, EditToken("codec")).encode().getJSONObject("ref")
    fun ref(value: JSONObject): EventRef = (FrozenEditorSubmission.decode(JSONObject().put("version", 1).put("operationId", "codec")
        .put("kind", "delete").put("ref", value).put("token", "codec")) as FrozenEditorSubmission.Delete).ref
    fun patch(value: EventPatch, selected: EventRef, token: EditToken): JSONObject {
        require(value.time == FieldChange.Keep && value.eventZone == FieldChange.Keep)
        return FrozenEditorSubmission.Update("codec", selected, token, value).encode().getJSONObject("patch")
    }
    fun patch(value: JSONObject, selected: EventRef, token: EditToken): EventPatch {
        require(value.keys().asSequence().all { it in setOf("title", "location", "description", "alerts", "availability", "privacy") })
        return (FrozenEditorSubmission.decode(JSONObject().put("version", 1).put("operationId", "codec").put("kind", "update")
            .put("ref", ref(selected)).put("token", token.value).put("patch", value)) as FrozenEditorSubmission.Update).patch
    }
    fun start(value: RecurrenceStart): JSONObject = JSONObject().apply { when (value) {
        is RecurrenceStart.AllDay -> { put("kind", "allDay"); put("date", value.date.toString()) }
        is RecurrenceStart.Timed -> { put("kind", "timed"); put("local", value.localDateTime.toString()); put("zone", value.zone.id); put("offset", value.preferredOffset?.id ?: JSONObject.NULL) }
    } }
    fun start(value: JSONObject): RecurrenceStart = when (value.getString("kind")) {
        "allDay" -> { require(value.keys().asSequence().toSet() == setOf("kind", "date")); RecurrenceStart.AllDay(LocalDate.parse(value.getString("date"))) }
        "timed" -> { require(value.keys().asSequence().toSet() == setOf("kind", "local", "zone", "offset"))
            RecurrenceStart.Timed(LocalDateTime.parse(value.getString("local")), ZoneId.of(value.getString("zone")),
                if (value.isNull("offset")) null else ZoneOffset.of(value.getString("offset"))) }
        else -> throw IllegalArgumentException()
    }
}

/** Original recurrence identity is the cutoff, never a moved occurrence's actual appointment time. */
internal fun seriesOriginalBoundary(selected: EventRef, masterStart: RecurrenceStart): RecurrenceKey? = try {
    val original = selected.recurrence?.originalStart
    if (original == null || original.nano != 0) null else when (masterStart) {
        is RecurrenceStart.AllDay -> original.atOffset(ZoneOffset.UTC).takeIf { it.toLocalTime() == LocalTime.MIDNIGHT && it.year in 1..9999 }
            ?.let { RecurrenceKey.Date(it.toLocalDate()) }
        is RecurrenceStart.Timed -> original.atZone(masterStart.zone).takeIf { it.year in 1..9999 }?.let { RecurrenceKey.Timed(original) }
    }
} catch (_: Exception) { null }
