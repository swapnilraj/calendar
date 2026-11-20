package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** Independent native envelope; semantic receipt1 is an immutable structured proof, never migrated. */
internal class SeriesGraphDurationNativeReceipt private constructor(
    val operationId: String, val semanticRaw: String, val binding: SeriesGraphDurationNativeBinding,
    val phase: Phase, private val acknowledgedCounts: List<Int>?,
) {
    enum class Phase { PREPARED, COMMITTED, VERIFIED }
    data class Plan(val command: SeriesGraphDurationCommand, val sourceRaw: String, val expectedRaw: String,
        val candidate: EventRef, val updateIndex: Int, val counts: List<Int>, val value: Map<String, Any?>)

    fun plan(budgets: ProviderReadBudgets, planning: RecurrencePlanningBudget): Plan {
        val semantic = SeriesGraphDurationReceipt.parse(CalendarLocalStore.Operation(operationId, NativeDurationJson.text(NativeDurationJson.parse(semanticRaw, budgets), "signature"),
            SeriesGraphDurationProtocol.FAMILY, "PREPARED", semanticRaw), budgets, planning)
        require(semantic.phase == SeriesGraphDurationReceipt.Phase.PREPARED && semantic.acknowledgement == null)
        val source = NativeDurationJson.parse(semantic.sourceRaw, budgets)
        val master = NativeDurationJson.row(source.getJSONObject("master"))
        val calendar = NativeDurationJson.row(source.getJSONObject("calendar"))
        require(calendar["canPartiallyUpdate"] == "0" && master["lastSynced"] == "0")
        require(master["dirty"] in setOf("0", "1") && master.containsKey("mutators"))
        require(master.containsKey("lastDate") && master["lastDate"] == null)
        val begin = Instant.ofEpochMilli(requireNotNull(master["dtstart"]).toLong())
        val start = if (master["allDay"] == "1") RecurrenceStart.AllDay(begin.atOffset(ZoneOffset.UTC).toLocalDate()) else {
            val z = begin.atZone(ZoneId.of(requireNotNull(master["eventTimezone"])))
            RecurrenceStart.Timed(z.toLocalDateTime(), z.zone, z.offset)
        }
        val rule = RecurrenceRules.parse(requireNotNull(master["rrule"]), start) as? ParsedRecurrence.Supported ?: error("Unproved RRULE")
        require(rule.spec.end == RecurrenceEnd.Never)
        val semanticPlan = NativeDurationJson.parse(semanticRaw, budgets).getJSONObject("plan")
        val duration = semanticPlan.getJSONObject("changes").getString("duration")
        val expected = JSONObject(semantic.sourceRaw).apply {
            getJSONObject("master").put("duration", duration).put("dirty", "1").put("mutators", binding.callerPackage).put("lastDate", JSONObject.NULL)
        }.toString()
        val selected = semantic.command.selected
        val recurrence = requireNotNull(selected.recurrence)
        val candidate = if (selected.eventId != recurrence.masterEventId) selected else {
            val original = recurrence.originalStart
            val end = when (val change = semantic.command.duration) {
                is SeriesGraphDuration.TimedSeconds -> original.plusSeconds(change.seconds)
                is SeriesGraphDuration.AllDayDays -> { require(original.atOffset(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT); original.plusSeconds(Math.multiplyExact(change.days, 86_400L)) }
            }
            require(end.atOffset(ZoneOffset.UTC).year in 1..9999)
            selected.copy(instanceStart = original, instanceEnd = end)
        }
        val count = assertionCounts(source)
        if (count.size * 2 + 1 > MAX_OPERATIONS) throw ProviderReadLimitExceeded()
        val values = linkedMapOf<String, Any?>("effectContract" to binding.effectContract,
            "request" to linkedMapOf("duration" to duration, "dtstart" to master.getValue("dtstart")),
            "effects" to linkedMapOf("dirty" to "1", "mutators" to binding.callerPackage, "lastDate" to null),
            "sourceHash" to NativeDurationJson.hash(semantic.sourceRaw), "semanticPostGraphHash" to semanticPlan.getString("postGraphHash"),
            "nativePostGraphHash" to NativeDurationJson.hash(expected), "membership" to semanticPlan.getJSONArray("membership").let { a -> List(a.length()) { a.getLong(it) } },
            "retainedGraphHash" to semanticPlan.getString("retainedGraphHash"), "updateIndex" to count.size,
            "expectedCounts" to (count + 1 + count))
        return Plan(semantic.command, semantic.sourceRaw, expected, candidate, count.size, count + 1 + count, values)
    }

    fun encode(budgets: ProviderReadBudgets = ProviderReadBudgets(), planning: RecurrencePlanningBudget = RecurrencePlanningBudget()): String {
        val p = plan(budgets, planning)
        require(if (phase == Phase.PREPARED) acknowledgedCounts == null else acknowledgedCounts == p.counts)
        fun payload(stage: Phase, counts: List<Int>?): Map<String, Any?> = linkedMapOf("family" to FAMILY, "version" to 1,
            "operationId" to operationId, "signature" to fingerprint(p.command), "phase" to stage.name,
            "semanticPrepared" to NativeDurationJson.value(NativeDurationJson.parse(semanticRaw, budgets)),
            "semanticPreparedHash" to NativeDurationJson.hash(semanticRaw), "nativeBinding" to bindingMap(binding), "effectPlan" to p.value,
            "acknowledgement" to counts?.let { linkedMapOf("counts" to it, "updateIndex" to p.updateIndex, "nativePostGraphHash" to NativeDurationJson.hash(p.expectedRaw)) })
        NativeDurationJson.reserve(payload(Phase.COMMITTED, p.counts) + ("integrity" to "0".repeat(64)), budgets)
        val body = payload(phase, acknowledgedCounts)
        val result = NativeDurationJson.json(body + ("integrity" to NativeDurationJson.hash(FAMILY + NativeDurationJson.json(body).toString()))).toString()
        checkStoredJson(result, budgets.maxCanonicalBytes)
        return result
    }

    fun withPhase(next: Phase, counts: List<Int>, budgets: ProviderReadBudgets, planning: RecurrencePlanningBudget): SeriesGraphDurationNativeReceipt {
        require((phase == Phase.PREPARED && next == Phase.COMMITTED) || (phase == Phase.COMMITTED && next == Phase.VERIFIED) || (phase == Phase.VERIFIED && next == Phase.VERIFIED))
        return SeriesGraphDurationNativeReceipt(operationId, semanticRaw, binding, next, counts.toList()).also { it.encode(budgets, planning) }
    }

    companion object {
        const val FAMILY = "SERIES_GRAPH_DURATION_NATIVE_V1"
        const val PREFIX = "series-graph-duration-native-v1:"
        const val MAX_OPERATIONS = 2048
        fun fingerprint(command: SeriesGraphDurationCommand) = PREFIX + SeriesGraphDurationReceipt.fingerprint(command).removePrefix(SeriesGraphDurationProtocol.FINGERPRINT_PREFIX)
        fun create(id: String, command: SeriesGraphDurationCommand, sourceRaw: String, binding: SeriesGraphDurationNativeBinding,
            budgets: ProviderReadBudgets, planning: RecurrencePlanningBudget): SeriesGraphDurationNativeReceipt {
            val semantic = SeriesGraphDurationReceipt.create(id, command, sourceRaw, budgets = budgets, planning = planning).encode(budgets, planning)
            return SeriesGraphDurationNativeReceipt(id, semantic, binding, Phase.PREPARED, null).also { it.encode(budgets, planning) }
        }
        fun parse(operation: CalendarLocalStore.Operation, budgets: ProviderReadBudgets, planning: RecurrencePlanningBudget): SeriesGraphDurationNativeReceipt {
            require(operation.kind == FAMILY)
            val raw = requireNotNull(operation.resultRef); val root = NativeDurationJson.parse(raw, budgets)
            NativeDurationJson.keys(root, setOf("family", "version", "operationId", "signature", "phase", "semanticPrepared", "semanticPreparedHash", "nativeBinding", "effectPlan", "acknowledgement", "integrity"))
            require(NativeDurationJson.number(root, "version") == 1L && NativeDurationJson.text(root, "family") == FAMILY)
            require(NativeDurationJson.text(root, "operationId") == operation.id && NativeDurationJson.text(root, "signature") == operation.signature)
            val semantic = root.getJSONObject("semanticPrepared").toString()
            val b = root.getJSONObject("nativeBinding")
            NativeDurationJson.keys(b, setOf("authority", "providerComponent", "providerPackage", "providerVersion", "providerCertificateSha256", "sdk", "callerPackage", "callerUid", "callerPackageCount", "effectContract"))
            fun integer(key: String): Int { val n = NativeDurationJson.number(b, key); require(n in 0..Int.MAX_VALUE.toLong()); return n.toInt() }
            val binding = SeriesGraphDurationNativeBinding(NativeDurationJson.text(b,"authority"), NativeDurationJson.text(b,"providerComponent"), NativeDurationJson.text(b,"providerPackage"),
                NativeDurationJson.number(b,"providerVersion"),NativeDurationJson.text(b,"providerCertificateSha256"),integer("sdk"),NativeDurationJson.text(b,"callerPackage"),integer("callerUid"),integer("callerPackageCount"),NativeDurationJson.text(b,"effectContract"))
            val phase = Phase.valueOf(NativeDurationJson.text(root, "phase")); require(phase.name == operation.phase)
            val counts = if (root.isNull("acknowledgement")) null else root.getJSONObject("acknowledgement").getJSONArray("counts").let { a ->
                require(a.length() <= budgets.mutation.maxRows); List(a.length()) { NativeDurationJson.integerValue(a.get(it)).also { n -> require(n in 0..Int.MAX_VALUE.toLong()) }.toInt() }
            }
            val receipt = SeriesGraphDurationNativeReceipt(operation.id, semantic, binding, phase, counts)
            val p = receipt.plan(budgets, planning); require(operation.signature == fingerprint(p.command))
            require(receipt.encode(budgets, planning) == raw)
            return receipt
        }
        private fun bindingMap(b: SeriesGraphDurationNativeBinding): Map<String, Any?> = linkedMapOf("authority" to b.authority,"providerComponent" to b.providerComponent,"providerPackage" to b.providerPackage,
            "providerVersion" to b.providerVersion,"providerCertificateSha256" to b.providerCertificateSha256,"sdk" to b.sdk,"callerPackage" to b.callerPackage,"callerUid" to b.callerUid,"callerPackageCount" to b.callerPackageCount,"effectContract" to b.effectContract)
        private fun assertionCounts(source: JSONObject): List<Int> = buildList {
            add(1)
            val events = listOf(source.getJSONObject("master")) + source.getJSONArray("exceptions").let { a -> List(a.length()) { a.getJSONObject(it) } }
            add(events.size)
            if (source.getJSONObject("master").isNull("_sync_id") || source.getJSONObject("master").optString("_sync_id").isBlank()) add(0)
            events.forEach { add(1) }
            for (name in listOf("reminders", "attendees", "extendedProperties")) {
                val groups = source.getJSONArray(name)
                for (i in 0 until groups.length()) add(groups.getJSONObject(i).getJSONArray("rows").length())
                for (i in 0 until groups.length()) repeat(groups.getJSONObject(i).getJSONArray("rows").length()) { add(1) }
            }
        }
    }
}

/** Strict bounded new-envelope helpers; no existing JSON or source codecs are modified. */
internal object NativeDurationJson {
    fun text(j: JSONObject, key: String) = (j.get(key) as? String) ?: error("Invalid native receipt string")
    fun integerValue(value: Any): Long { require(value is Int || value is Long); return (value as Number).toLong() }
    fun number(j: JSONObject, key: String) = integerValue(j.get(key))
    fun keys(j: JSONObject, expected: Set<String>) = require(j.keys().asSequence().toSet() == expected)
    fun row(j: JSONObject): Map<String, String?> = j.keys().asSequence().associateWith { if (j.isNull(it)) null else text(j,it) }
    fun value(item: Any?): Any? = when (item) {
        null, JSONObject.NULL -> null
        is JSONObject -> item.keys().asSequence().associateWith { value(item.get(it)) }
        is JSONArray -> List(item.length()) { value(item.get(it)) }
        else -> item
    }
    fun json(values: Map<String, Any?>) = JSONObject().apply { values.forEach { (key, v) -> put(key, wrap(v)) } }
    private fun wrap(v: Any?): Any = when (v) { null -> JSONObject.NULL; is Map<*,*> -> json(v.entries.associate { it.key.toString() to it.value }); is Iterable<*> -> JSONArray().apply { v.forEach { put(wrap(it)) } }; else -> v }
    fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun reserve(map: Map<String,Any?>, budgets: ProviderReadBudgets) {
        val local = budgets.mutation; val context = currentReadBudget()?.limits ?: ProviderReadBudgets().mutation
        val limits = ProviderReadLimits(minOf(local.maxRows,context.maxRows),minOf(local.maxCells,context.maxCells),minOf(local.maxTextUnits,context.maxTextUnits),minOf(local.maxCellUnits,context.maxCellUnits),minOf(local.maxColumns,context.maxColumns))
        if (checkCanonical(map,budgets.maxCanonicalBytes) > limits.maxTextUnits) throw ProviderReadLimitExceeded()
        var objects=0; var nodes=0L
        fun cell(s:String) { if(s.length>limits.maxCellUnits) throw ProviderReadLimitExceeded() }
        fun visit(v:Any?, depth:Int) { nodes++; when(v) {
            is Map<*,*> -> { objects++; if(depth>limits.maxColumns||objects>limits.maxRows) throw ProviderReadLimitExceeded(); v.forEach { (k,x)->cell(k.toString());nodes+=3;visit(x,depth+1) } }
            is Iterable<*> -> { if(depth>limits.maxColumns) throw ProviderReadLimitExceeded(); v.forEach { nodes++;visit(it,depth+1) } }
            is String -> cell(v)
        }; if(nodes>limits.maxCells) throw ProviderReadLimitExceeded() }
        visit(map,1)
    }
    fun parse(raw:String,budgets:ProviderReadBudgets):JSONObject {
        checkStoredJson(raw,budgets.maxCanonicalBytes)
        if(raw.length.toLong()>budgets.mutation.maxTextUnits) throw ProviderReadLimitExceeded()
        // Original scan bounds allocation under the active/default context; this independent scan
        // applies smaller caller-configured limits before JSONObject can allocate a tree.
        var i=0;var depth=0;var objects=0;var nodes=0L
        while(i<raw.length) { when(raw[i]) {
            '{','['->{depth++;nodes++;if(raw[i]=='{')objects++}
            '}',']'->depth--
            ',',':'->nodes++
            '"'->{nodes++;var units=0;i++;while(i<raw.length&&raw[i]!='"'){if(raw[i]=='\\'){i++;if(i<raw.length&&raw[i]=='u')i+=4};units++;if(units>budgets.mutation.maxCellUnits)throw ProviderReadLimitExceeded();i++}}
        };if(depth>budgets.mutation.maxColumns||objects>budgets.mutation.maxRows||nodes>budgets.mutation.maxCells)throw ProviderReadLimitExceeded();i++ }
        return JSONObject(raw).also { require(it.toString()==raw) }
    }
}
