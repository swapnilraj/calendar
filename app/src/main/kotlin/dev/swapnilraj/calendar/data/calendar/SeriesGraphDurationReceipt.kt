package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** Codec only. No resolver, live capability, dispatch, retirement or journal mutation is supplied. */
internal class SeriesGraphDurationReceipt private constructor(
    val operationId: String,
    val command: SeriesGraphDurationCommand,
    val sourceRaw: String,
    val phase: Phase,
    val acknowledgement: Acknowledgement?,
) {
    enum class Phase { PREPARED, ATTEMPTED, COMMITTED, VERIFIED }
    /** Recorded evidence must be produced by a future adapter; constructing it grants no authority. */
    data class Acknowledgement(val updateCount: Int, val postGraphHash: String)

    fun encode(budgets: ProviderReadBudgets = ProviderReadBudgets(), planning: RecurrencePlanningBudget = RecurrencePlanningBudget()): String {
        val plan = derive(command, sourceRaw, budgets, planning)
        validateAcknowledgement(plan)
        // Reserve the full committed envelope even for PREPARED/adopted winners. Conservative
        // escaped UTF-8 bounds apply before constructing the receipt tree/string, not afterwards.
        val worst = payload(plan, Phase.COMMITTED, Acknowledgement(1, plan.postGraphHash)) + ("integrity" to "0".repeat(64))
        reserveEnvelope(worst, budgets)
        val values = payload(plan, phase, acknowledgement)
        val unsigned = json(values).toString()
        val result = json(values + ("integrity" to hash(SeriesGraphDurationProtocol.FAMILY + unsigned))).toString()
        checkStoredJson(result, budgets.maxCanonicalBytes)
        return result
    }

    private fun validateAcknowledgement(plan: Plan) {
        when (phase) {
            Phase.PREPARED, Phase.ATTEMPTED -> require(acknowledgement == null)
            Phase.COMMITTED, Phase.VERIFIED -> require(acknowledgement == Acknowledgement(1, plan.postGraphHash))
        }
    }

    private fun payload(plan: Plan, stage: Phase, ack: Acknowledgement?): Map<String, Any?> = linkedMapOf(
        "family" to SeriesGraphDurationProtocol.FAMILY, "version" to SeriesGraphDurationProtocol.RECEIPT_VERSION,
        "operationId" to operationId, "signature" to fingerprint(command), "phase" to stage.name,
        "command" to commandMap(command), "sourceRaw" to sourceRaw, "namespace" to plan.namespace,
        "plan" to plan.values, "acknowledgement" to ack?.let { linkedMapOf("updateCount" to it.updateCount, "postGraphHash" to it.postGraphHash) },
    )

    companion object {
        private data class Plan(val namespace: String, val postGraphHash: String, val values: Map<String, Any?>)
        private val sourceKeys = setOf("version", "calendar", "master", "exceptions", "reminders", "attendees", "extendedProperties", "selection")
        private val receiptKeys = setOf("family", "version", "operationId", "signature", "phase", "command", "sourceRaw", "namespace", "plan", "acknowledgement", "integrity")
        private val referenceKeys = setOf("calendar", "event", "start", "end", "master", "original")

        fun create(operationId: String, command: SeriesGraphDurationCommand, sourceRaw: String,
            phase: Phase = Phase.PREPARED, acknowledgement: Acknowledgement? = null,
            budgets: ProviderReadBudgets = ProviderReadBudgets(), planning: RecurrencePlanningBudget = RecurrencePlanningBudget()): SeriesGraphDurationReceipt {
            require(operationId.isNotBlank() && operationId.length <= 256)
            return SeriesGraphDurationReceipt(operationId, command, sourceRaw, phase, acknowledgement).also { it.encode(budgets, planning) }
        }

        /** Every parsed/adopted winner is independently validated, recomputed and fully reserved. */
        fun parse(operation: CalendarLocalStore.Operation, budgets: ProviderReadBudgets = ProviderReadBudgets(),
            planning: RecurrencePlanningBudget = RecurrencePlanningBudget()): SeriesGraphDurationReceipt {
            require(operation.kind == SeriesGraphDurationProtocol.FAMILY)
            val raw = requireNotNull(operation.resultRef)
            val root = strictObject(raw, budgets)
            keys(root, receiptKeys)
            require(number(root, "version") == SeriesGraphDurationProtocol.RECEIPT_VERSION.toLong())
            require(string(root, "family") == SeriesGraphDurationProtocol.FAMILY)
            require(string(root, "operationId") == operation.id && string(root, "signature") == operation.signature)
            val phase = Phase.valueOf(string(root, "phase"))
            require(phase.name == operation.phase)
            val c = root.getJSONObject("command")
            keys(c, setOf("selected", "token", "duration"))
            val d = c.getJSONObject("duration")
            keys(d, setOf("kind", "value"))
            val duration = when (string(d, "kind")) {
                "timedSeconds" -> SeriesGraphDuration.TimedSeconds(number(d, "value"))
                "allDayDays" -> SeriesGraphDuration.AllDayDays(number(d, "value"))
                else -> error("Unknown duration kind")
            }
            val command = SeriesGraphDurationCommand(ref(c.getJSONObject("selected")), EditToken(string(c, "token")), duration)
            require(fingerprint(command) == operation.signature)
            val ack = if (root.isNull("acknowledgement")) null else root.getJSONObject("acknowledgement").let {
                keys(it, setOf("updateCount", "postGraphHash"))
                require(number(it, "updateCount") == 1L)
                Acknowledgement(1, string(it, "postGraphHash"))
            }
            val receipt = create(operation.id, command, string(root, "sourceRaw"), phase, ack, budgets, planning)
            // Exact re-encoding rejects unknown/changed plan, namespace, integrity, primitive types,
            // duplicate JSON keys and alternate/noncanonical representations. No legacy migration.
            require(receipt.encode(budgets, planning) == raw)
            return receipt
        }

        fun fingerprint(command: SeriesGraphDurationCommand): String = SeriesGraphDurationProtocol.FINGERPRINT_PREFIX + hash(json(commandMap(command)).toString())

        private fun derive(command: SeriesGraphDurationCommand, raw: String, budgets: ProviderReadBudgets, planning: RecurrencePlanningBudget): Plan {
            val source = strictObject(raw, budgets)
            keys(source, sourceKeys)
            require(number(source, "version") == 1L)
            require(command.token.value == hash("SERIES_EDIT_V1" + raw))
            require(ref(source.getJSONObject("selection")) == command.selected)
            val calendar = row(source.getJSONObject("calendar"), budgets)
            val master = row(source.getJSONObject("master"), budgets)
            val calendarId = command.selected.calendarId.value
            val masterId = requireNotNull(command.selected.recurrence).masterEventId
            require(canonicalLong(calendar["_id"]) == calendarId && canonicalLong(master["_id"]) == masterId)
            require(canonicalLong(master["calendar_id"]) == calendarId && master["deleted"] == "0")
            require(master["original_id"].isNullOrBlank() && master["original_sync_id"].isNullOrBlank())
            require(master["dtend"] == null && !master["rrule"].isNullOrBlank())
            require(master["rdate"].isNullOrBlank() && master["exrule"].isNullOrBlank())
            val actor = calendar["account_name"]?.takeIf { it.isNotBlank() } ?: error("Unknown actor")
            require(!calendar["account_type"].isNullOrBlank())
            require(canonicalLong(calendar["calendar_access_level"]) in setOf(500L, 600L, 700L, 800L))
            val startInstant = Instant.ofEpochMilli(canonicalLong(master["dtstart"]) ?: error("Missing DTSTART"))
            val start = when (master["allDay"]) {
                "0" -> {
                    val zone = ZoneId.of(requireNotNull(master["eventTimezone"]))
                    val zoned = startInstant.atZone(zone)
                    RecurrenceStart.Timed(zoned.toLocalDateTime(), zone, zoned.offset)
                }
                "1" -> {
                    val utc = startInstant.atOffset(ZoneOffset.UTC)
                    require(utc.toLocalTime() == LocalTime.MIDNIGHT)
                    RecurrenceStart.AllDay(utc.toLocalDate())
                }
                else -> error("Unknown time mode")
            }
            val sourceTemplate = (RecurrenceMasterTemplates.resolve(start, master["duration"]) as? RecurrenceMasterTemplateResult.Ready)?.template
                ?: error("Unproved source duration")
            val array = source.getJSONArray("exceptions")
            require(array.length() <= planning.maxExceptions)
            val exceptions = List(array.length()) { row(array.getJSONObject(it), budgets) }
            val eventRows = listOf(master) + exceptions
            val ids = eventRows.map { canonicalLong(it["_id"])?.takeIf { id -> id > 0 } ?: error("Missing event ID") }
            require(ids.distinct().size == ids.size)
            eventRows.forEachIndexed { index, event ->
                require(canonicalLong(event["calendar_id"]) == calendarId && event["deleted"] == "0")
                require(event["organizer"].orEmpty().equals(actor, ignoreCase = true) && event["isOrganizer"] in setOf(null, "1"))
                require(event["isOrganizer"] == "1" || event["guestsCanSeeGuests"] == "1")
                require(event["hasAttendeeData"] in setOf("0", "1") && (event["hasAttendeeData"] != "1" || event["guestsCanSeeGuests"] == "1"))
                require(event["eventStatus"] in setOf(null, "0", "1", "2") && (index != 0 || event["eventStatus"] != "2"))
                require(event["customAppPackage"].isNullOrBlank())
                require(event["eventEndTimezone"].isNullOrBlank() || event["eventEndTimezone"] == event["eventTimezone"])
            }
            val models = exceptions.map { event ->
                require(listOf("rrule", "rdate", "exrule", "exdate").all { event[it].isNullOrBlank() })
                val parent = canonicalLong(event["original_id"])
                val sync = event["original_sync_id"]?.takeIf(String::isNotBlank)
                require(parent == null || parent == masterId)
                require(event["original_id"].isNullOrBlank() || parent != null)
                require(sync == null || sync == master["_sync_id"]?.takeIf(String::isNotBlank))
                require(parent != null || sync != null)
                require(event["originalAllDay"] == master["allDay"])
                val original = Instant.ofEpochMilli(canonicalLong(event["originalInstanceTime"]) ?: error("Missing original"))
                val originalKey = when (start) {
                    is RecurrenceStart.AllDay -> { require(original.atOffset(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT); RecurrenceKey.Date(original.atOffset(ZoneOffset.UTC).toLocalDate()) }
                    is RecurrenceStart.Timed -> RecurrenceKey.Timed(original)
                }
                val begin = Instant.ofEpochMilli(canonicalLong(event["dtstart"]) ?: error("Missing exception start"))
                val end = Instant.ofEpochMilli(canonicalLong(event["dtend"]) ?: error("Missing exception end"))
                require(listOf(begin, end).all { it.atOffset(ZoneOffset.UTC).year in 1..9999 })
                when (event["allDay"]) {
                    "0" -> EventTime.Timed(begin, end)
                    "1" -> {
                        val dates = EventTime.AllDay.fromProvider(begin, end).dates
                        require(begin == dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() && end == dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())
                    }
                    else -> error("Unknown exception mode")
                }
                SeriesException(EventRef(command.selected.calendarId, canonicalLong(event["_id"])!!, begin, end, RecurrenceRef(masterId, original)), originalKey, event["eventStatus"] == "2")
            }
            require(models.map { it.original }.distinct().size == models.size)
            val children = linkedMapOf<String, Map<Long, List<Map<String, String?>>>>()
            for (name in listOf("reminders", "attendees", "extendedProperties")) {
                val groups = source.getJSONArray(name)
                require(groups.length() == ids.size)
                var count = 0
                val values = linkedMapOf<Long, List<Map<String, String?>>>()
                for (i in 0 until groups.length()) {
                    val group = groups.getJSONObject(i)
                    keys(group, setOf("parent", "rows"))
                    val parent = number(group, "parent")
                    require(parent in ids && parent !in values)
                    val rows = group.getJSONArray("rows")
                    require(rows.length() <= budgets.maxSeriesChildren - count)
                    count += rows.length()
                    values[parent] = List(rows.length()) {
                        row(rows.getJSONObject(it), budgets).also { child ->
                            require(canonicalLong(child["event_id"]) == parent && canonicalLong(child["_id"])?.let { id -> id > 0 } == true)
                            if (name == "attendees") {
                                require(child.keys.none(SeriesAttendeeColumns::isJoinedAlias))
                                require(child["attendeeEmail"].orEmpty().equals(actor, ignoreCase = true))
                            }
                        }
                    }
                }
                require(values.keys == ids.toSet())
                val childIds = values.values.flatten().map { canonicalLong(it["_id"])!! }
                require(childIds.distinct().size == childIds.size)
                children[name] = values
            }
            val decoded = RecurrenceDateCodec.parse(master["exdate"], start, RecurrenceDateBudget(maxValues = planning.maxInstances), AllDayDateTimePolicy.PROVIDER_UTC_MIDNIGHT) as? ParsedRecurrenceDates.Supported ?: error("Unknown EXDATE")
            require(master["exdate"]?.lineSequence()?.none { it.startsWith("RDATE", ignoreCase = true) } != false)
            if (command.selected.eventId == masterId) {
                val original = requireNotNull(command.selected.recurrence).originalStart
                require(command.selected.instanceStart == original)
                val expectedEnd = when (val template = sourceTemplate.time) {
                    is EventTime.Timed -> original.plus(template.duration)
                    is EventTime.AllDay -> {
                        require(original.atOffset(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT)
                        original.plusSeconds(Math.multiplyExact(java.time.temporal.ChronoUnit.DAYS.between(template.dates.start, template.dates.endExclusive), 86_400L))
                    }
                }
                require(command.selected.instanceEnd == expectedEnd)
                require(listOf(original, expectedEnd).all { it.atOffset(ZoneOffset.UTC).year in 1..9999 })
            }
            val snapshot = RecurrenceSeriesSnapshot(SeriesIdentity(command.selected.calendarId, masterId), start, master["rrule"], master["duration"],
                master["rdate"], master["exrule"], master["exdate"], decoded.keys, true, models, SeriesCoverage.COMPLETE, command.selected)
            val intent = command.intent(start)
            val planned = RecurrenceEditing.plan(snapshot, command.selected, intent, planning) as? RecurrencePlanningResult.Ready ?: error("Unproved duration plan")
            val proof = (RecurrenceGraphProof.prove(snapshot, command.selected, RecurrenceGraphRequest.Edited(intent), planned, planning) as? RecurrenceGraphProofResult.Complete)?.proof ?: error("Unproved complete graph")
            require(proof.newExceptions.isEmpty())
            val action = planned.actions.filterIsInstance<RecurrencePlanAction.UpdateMaster>().single()
            require(planned.actions.size == models.size + 1 && action.patch == EventPatch() && action.anchor == FieldChange.Keep && action.duration == intent.duration)
            require((RecurrenceMasterTemplates.effectiveRule(snapshot, intent, action) as? RecurrenceMasterRuleResult.Ready)?.ruleRaw == master["rrule"])
            proof.exceptions.forEach { value ->
                when (value) {
                    is RecurrenceExceptionDisposition.Retain -> require(value.explicitPatch == EventPatch() && value.cancellation == FieldChange.Keep)
                    is RecurrenceExceptionDisposition.Remap -> require(value.parent == SeriesTarget.EXISTING && value.original == value.source.original && value.explicitPatch == EventPatch())
                    else -> error("Exception graph is not retained")
                }
            }
            val exclusions = proof.exclusions
            if (exclusions is RecurrenceExclusionProof.Transform) require(exclusions.mappings.all { it.targetParent == SeriesTarget.EXISTING && it.targetOriginal == it.source })
            val duration = when (val value = command.duration) { is SeriesGraphDuration.TimedSeconds -> "PT${value.seconds}S"; is SeriesGraphDuration.AllDayDays -> "P${value.days}D" }
            require(RecurrenceMasterTemplates.resolve(start, duration) is RecurrenceMasterTemplateResult.Ready)
            // Reconstruct exactly the existing canonical sourceV1, including sorted opaque columns.
            fun sourceMap(parent: Map<String, String?>): Map<String, Any?> = linkedMapOf("version" to 1, "calendar" to calendar.toSortedMap(), "master" to parent.toSortedMap(),
                "exceptions" to exceptions.sortedBy { canonicalLong(it["_id"]) }.map { it.toSortedMap() },
                "reminders" to childMap(children.getValue("reminders")), "attendees" to childMap(children.getValue("attendees")),
                "extendedProperties" to childMap(children.getValue("extendedProperties")), "selection" to refMap(command.selected))
            val before = sourceMap(master)
            checkCanonical(before, budgets.maxCanonicalBytes)
            require(json(before).toString() == raw)
            val after = sourceMap(master + ("duration" to duration))
            checkCanonical(after, budgets.maxCanonicalBytes)
            val postHash = hash(json(after).toString())
            val namespace = hash(json(linkedMapOf("_id" to calendar["_id"], "account_name" to calendar["account_name"], "account_type" to calendar["account_type"], "ownerAccount" to calendar["ownerAccount"], "_sync_id" to calendar["_sync_id"])).toString())
            val retained = linkedMapOf("exceptions" to exceptions.map { it.toSortedMap() }, "children" to children.mapValues { childMap(it.value) },
                "rawDates" to listOf(master["rrule"], master["rdate"], master["exrule"], master["exdate"]))
            val values = linkedMapOf<String, Any?>("master" to masterId, "changes" to mapOf("duration" to duration), "membership" to ids.sorted(),
                "sourceHash" to hash(raw), "retainedGraphHash" to hash(json(retained).toString()), "postGraphHash" to postHash)
            return Plan(namespace, postHash, values)
        }

        private fun childMap(values: Map<Long, List<Map<String, String?>>>) = values.toSortedMap().map { (id, rows) -> linkedMapOf("parent" to id,
            "rows" to rows.sortedBy { canonicalLong(it["_id"]) }.map { it.toSortedMap() }) }
        private fun commandMap(command: SeriesGraphDurationCommand): Map<String, Any?> = linkedMapOf("selected" to refMap(command.selected), "token" to command.token.value,
            "duration" to when (val d = command.duration) { is SeriesGraphDuration.TimedSeconds -> linkedMapOf("kind" to "timedSeconds", "value" to d.seconds); is SeriesGraphDuration.AllDayDays -> linkedMapOf("kind" to "allDayDays", "value" to d.days) })
        private fun refMap(ref: EventRef): Map<String, Any?> = linkedMapOf("calendar" to ref.calendarId.value, "event" to ref.eventId, "start" to ref.instanceStart.toString(),
            "end" to ref.instanceEnd.toString(), "master" to requireNotNull(ref.recurrence).masterEventId, "original" to requireNotNull(ref.recurrence).originalStart.toString())
        private fun ref(value: JSONObject): EventRef {
            keys(value, referenceKeys)
            return EventRef(CalendarId(number(value, "calendar")), number(value, "event"), Instant.parse(string(value, "start")), Instant.parse(string(value, "end")), RecurrenceRef(number(value, "master"), Instant.parse(string(value, "original"))))
        }
        private fun row(value: JSONObject, budgets: ProviderReadBudgets): Map<String, String?> {
            require(value.length() <= budgets.mutation.maxColumns)
            return value.keys().asSequence().associateWith { key ->
                require(key.isNotBlank() && key.length <= budgets.mutation.maxCellUnits)
                if (value.isNull(key)) null else string(value, key).also { require(it.length <= budgets.mutation.maxCellUnits) }
            }
        }
        /** Reserve every configured adoption-parser limit on the full worst ACK before encoding. */
        private fun reserveEnvelope(value: Map<String, Any?>, budgets: ProviderReadBudgets) {
            val local = budgets.mutation
            val context = currentReadBudget()?.limits ?: ProviderReadBudgets().mutation
            val limits = ProviderReadLimits(minOf(local.maxRows, context.maxRows), minOf(local.maxCells, context.maxCells),
                minOf(local.maxTextUnits, context.maxTextUnits), minOf(local.maxCellUnits, context.maxCellUnits), minOf(local.maxColumns, context.maxColumns))
            // This conservative escaped UTF-8 bound also bounds the parser's raw UTF-16 text.
            if (checkCanonical(value, budgets.maxCanonicalBytes) > limits.maxTextUnits) throw ProviderReadLimitExceeded()
            var objects = 0
            var nodes = 0L
            fun cell(text: String) { if (text.length > limits.maxCellUnits) throw ProviderReadLimitExceeded() }
            fun visit(item: Any?, depth: Int) {
                nodes++
                when (item) {
                    is Map<*, *> -> {
                        objects++
                        if (depth > limits.maxColumns || objects > limits.maxRows) throw ProviderReadLimitExceeded()
                        item.forEach { (key, child) -> cell(key.toString()); nodes += 3; visit(child, depth + 1) }
                    }
                    is Iterable<*> -> {
                        if (depth > limits.maxColumns) throw ProviderReadLimitExceeded()
                        item.forEach { nodes++; visit(it, depth + 1) }
                    }
                    is String -> cell(item)
                }
                if (nodes > limits.maxCells) throw ProviderReadLimitExceeded()
            }
            visit(value, 1)
        }

        private fun strictObject(raw: String, budgets: ProviderReadBudgets): JSONObject {
            checkStoredJson(raw, budgets.maxCanonicalBytes)
            val limits = budgets.mutation
            if (raw.length.toLong() > limits.maxTextUnits) throw ProviderReadLimitExceeded()
            var depth = 0
            var objects = 0
            var nodes = 0L
            var index = 0
            // Independent of a coroutine read-budget context: bound tree allocation BEFORE parse.
            while (index < raw.length) {
                when (raw[index]) {
                    '{', '[' -> { depth++; nodes++; if (raw[index] == '{') objects++ }
                    '}', ']' -> depth--
                    ',', ':' -> nodes++
                    '"' -> {
                        nodes++
                        var units = 0
                        index++
                        while (index < raw.length && raw[index] != '"') {
                            if (raw[index] == '\\') { index++; if (index < raw.length && raw[index] == 'u') index += 4 }
                            units++; if (units > limits.maxCellUnits) throw ProviderReadLimitExceeded()
                            index++
                        }
                    }
                }
                if (depth > limits.maxColumns || objects > limits.maxRows || nodes > limits.maxCells) throw ProviderReadLimitExceeded()
                index++
            }
            return JSONObject(raw).also { require(it.toString() == raw) }
        }
        private fun canonicalLong(raw: String?): Long? = raw?.toLongOrNull()?.takeIf { it.toString() == raw }
        private fun keys(value: JSONObject, expected: Set<String>) = require(value.keys().asSequence().toSet() == expected)
        private fun string(value: JSONObject, key: String): String = (value.get(key) as? String) ?: error("Invalid string field")
        private fun number(value: JSONObject, key: String): Long { val n = value.get(key); require(n is Int || n is Long); return (n as Number).toLong() }
        private fun json(values: Map<String, Any?>): JSONObject = JSONObject().apply { values.forEach { (key, value) -> put(key, wrap(value)) } }
        private fun wrap(value: Any?): Any = when (value) { null -> JSONObject.NULL; is Map<*, *> -> json(value.entries.associate { it.key.toString() to it.value }); is Iterable<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }; else -> value }
        private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
