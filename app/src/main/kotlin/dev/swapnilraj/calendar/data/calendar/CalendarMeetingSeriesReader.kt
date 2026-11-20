package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal class MeetingSeriesReview(val reason: String) : Exception()
internal class MeetingSeriesConflict : Exception()
internal class MeetingSeriesDenied(val reason: MutationDenial) : Exception()

/** A complete event-membership graph and own-row evidence, never a private guest-list snapshot. */
internal data class MeetingSeriesSource(
    val selected: EventRef,
    val calendar: Map<String, String?>,
    val membership: List<Map<String, String?>>,
    val events: List<Map<String, String?>>,
    val candidates: Map<Long, List<Map<String, String?>>>,
    val ownRows: Map<Long, Map<String, String?>>,
    val eligible: Set<Long>,
    val details: EventDetails? = null,
) {
    val masterId get() = requireNotNull(selected.recurrence).masterEventId
    val master get() = events.single { it.meetingLong("_id") == masterId }
    val activeIds get() = events.filter { it["eventStatus"] != "2" }.map { requireNotNull(it.meetingLong("_id")) }.toSet()
    fun targets(scope: RecurrenceScope): Set<Long>? = when (scope) {
        RecurrenceScope.THIS -> setOf(selected.eventId).takeIf { selected.eventId != masterId && selected.eventId in eligible }
        RecurrenceScope.ALL -> activeIds.takeIf { masterId in eligible && eligible.containsAll(it) }
        RecurrenceScope.FOLLOWING -> null
    }
    private var encoded: String? = null
    fun canonical(maxBytes: Int): String {
        encoded?.let { return it }
        checkCanonical(listOf(calendar, membership, events, candidates, ownRows, eligible), maxBytes, 8192L + 128L * candidates.size)
        return JSONObject().put("feature", Feature).put("selected", meetingRefJson(selected)).put("calendar", meetingRowJson(calendar))
            .put("membership", JSONArray(membership.map(::meetingRowJson))).put("events", JSONArray(events.map(::meetingRowJson)))
            .put("candidates", JSONArray(candidates.toSortedMap().map { (event, rows) -> JSONObject().put("event", event).put("rows", JSONArray(rows.map(::meetingRowJson))) }))
            .put("ownRows", JSONArray(ownRows.toSortedMap().values.map(::meetingRowJson))).put("eligible", JSONArray(eligible.sorted())).toString().also { encoded = it }
    }
    companion object {
        const val Feature = "MEETING_SERIES_EDIT_V1"
        fun token(raw: String) = "meeting-series-edit-v1:" + meetingHash(Feature + raw)
        fun decode(raw: String, maxBytes: Int): MeetingSeriesSource {
            checkStoredJson(raw, maxBytes)
            val json = JSONObject(raw)
            require(json.getString("feature") == Feature)
            fun rows(array: JSONArray) = List(array.length()) { meetingRow(array.getJSONObject(it)) }
            val candidates = json.getJSONArray("candidates").let { array -> (0 until array.length()).associate { i ->
                val group = array.getJSONObject(i); group.getLong("event") to rows(group.getJSONArray("rows"))
            } }
            val own = rows(json.getJSONArray("ownRows")).associateBy { requireNotNull(it.meetingLong("event_id")) }
            val eligible = json.getJSONArray("eligible").let { array -> (0 until array.length()).map { array.getLong(it) }.toSet() }
            return MeetingSeriesSource(meetingParseRef(json.getJSONObject("selected")), meetingRow(json.getJSONObject("calendar")),
                rows(json.getJSONArray("membership")), rows(json.getJSONArray("events")), candidates, own, eligible)
        }
    }
}

internal class CalendarMeetingSeriesReader(
    private val resolver: ContentResolver,
    private val reader: CalendarReader,
    private val hasPermission: (String) -> Boolean,
    private val readBudgets: ProviderReadBudgets,
    private val planningBudget: RecurrencePlanningBudget,
) {
    suspend fun capture(selected: EventRef, expectedIdentity: CalendarIdentity? = null, validateSelected: Boolean = true): MeetingSeriesSource {
        ensureRead()
        val recurrence = selected.recurrence ?: throw MeetingSeriesReview("Recurring identity is required")
        val calendar = calendar(selected.calendarId)
        if (expectedIdentity != null && meetingIdentity(calendar) != expectedIdentity) throw MeetingSeriesConflict()
        val namespace = meetingIdentity(calendar)
        if ((calendar["calendar_access_level"]?.toIntOrNull() ?: -1) < CalendarContract.Calendars.CAL_ACCESS_RESPOND)
            throw MeetingSeriesDenied(MutationDenial.CALENDAR_READ_ONLY)
        val owner = namespace.owner
        if (owner.isNullOrBlank() || owner != namespace.account.name || namespace.account.type.isBlank()) throw MeetingSeriesDenied(MutationDenial.OWNERSHIP_UNKNOWN)
        val masterId = recurrence.masterEventId
        val identity = rows(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, masterId), maxRows = 1, fields = MembershipFields).singleOrNull()
            ?: throw MeetingSeriesConflict()
        if (identity.meetingLong("calendar_id") != selected.calendarId.value || identity["deleted"] != "0" ||
            !identity["original_id"].isNullOrBlank() || !identity["original_sync_id"].isNullOrBlank()) throw MeetingSeriesReview("Invalid series master identity")
        val sync = identity["_sync_id"]?.takeIf(String::isNotBlank)
        if (sync == null && rows(CalendarContract.Events.CONTENT_URI,
                "calendar_id = ? AND original_id IS NULL AND original_sync_id IS NOT NULL AND deleted = 0", arrayOf(selected.calendarId.value.toString()),
                planningBudget.maxExceptions + 1, MembershipFields).isNotEmpty()) throw MeetingSeriesReview("Sync-only membership cannot be assigned without the master sync identity")
        val (membershipWhere, membershipArgs) = membershipFilter(selected.calendarId, masterId, sync)
        val membership = rows(CalendarContract.Events.CONTENT_URI, membershipWhere, membershipArgs, planningBudget.maxExceptions + 1, MembershipFields)
        if (membership.any { it["deleted"] !in setOf("0", "1") } || membership.map { it.meetingLong("_id") }.toSet().size != membership.size)
            throw MeetingSeriesReview("Unknown or duplicate membership state")
        if (membership.any { it.meetingLong("calendar_id") != selected.calendarId.value })
            throw MeetingSeriesReview("A linked event belongs to another calendar")
        val graphWhere = "calendar_id = ? AND deleted = 0 AND (_id = ? OR original_id = ?" + if (sync == null) ")" else " OR original_sync_id = ?)"
        val graphArgs = arrayOf(selected.calendarId.value.toString(), masterId.toString(), masterId.toString(), *listOfNotNull(sync).toTypedArray())
        val events = rows(CalendarContract.Events.CONTENT_URI, graphWhere, graphArgs, planningBudget.maxExceptions + 1)
        if (events.map { it.meetingLong("_id") }.toSet() != membership.filter { it["deleted"] == "0" }.map { it.meetingLong("_id") }.toSet()) throw MeetingSeriesConflict()
        if (events.any { it.meetingLong("_id") == null || it.meetingLong("calendar_id") != selected.calendarId.value || it["deleted"] != "0" } ||
            events.map { it.meetingLong("_id") }.toSet().size != events.size) throw MeetingSeriesReview("Ambiguous private event graph")
        val master = events.singleOrNull { it.meetingLong("_id") == masterId } ?: throw MeetingSeriesConflict()
        if (master["_sync_id"]?.takeIf(String::isNotBlank) != sync || master["rrule"].isNullOrBlank() && master["rdate"].isNullOrBlank())
            throw MeetingSeriesReview("Master is no longer the same recurring source")
        val masterAllDay = when (master["allDay"]) { "0" -> false; "1" -> true; else -> throw MeetingSeriesReview("Unknown master time mode") }
        val masterStart = master.meetingLong("dtstart")?.let(Instant::ofEpochMilli) ?: throw MeetingSeriesReview("Unknown master start")
        if (masterAllDay) {
            if (masterStart.atOffset(ZoneOffset.UTC).toLocalTime() != java.time.LocalTime.MIDNIGHT) throw MeetingSeriesReview("All-day identity is not UTC midnight")
        } else try { ZoneId.of(master["eventTimezone"] ?: "") } catch (_: Exception) { throw MeetingSeriesReview("Unknown master time zone") }
        val originals = mutableSetOf<Long>()
        events.filter { it.meetingLong("_id") != masterId }.forEach { event ->
            val parent = event.meetingLong("original_id")
            val parentSync = event["original_sync_id"]?.takeIf(String::isNotBlank)
            if ((!event["original_id"].isNullOrBlank() && parent == null) || parent != null && parent != masterId ||
                parentSync != null && parentSync != sync || parent == null && parentSync == null || event["originalAllDay"] != (if (masterAllDay) "1" else "0"))
                throw MeetingSeriesReview("Exception original identity is incomplete or contradictory")
            if (listOf("rrule", "rdate", "exrule", "exdate").any { !event[it].isNullOrBlank() }) throw MeetingSeriesReview("Nested exception recurrence is unsupported")
            val original = event.meetingLong("originalInstanceTime") ?: throw MeetingSeriesReview("Missing original occurrence identity")
            if (!originals.add(original)) throw MeetingSeriesReview("Duplicate original occurrence identity")
            if (masterAllDay && Instant.ofEpochMilli(original).atOffset(ZoneOffset.UTC).toLocalTime() != java.time.LocalTime.MIDNIGHT)
                throw MeetingSeriesReview("Original all-day identity is not UTC midnight")
        }
        if (!invited(master, owner)) throw MeetingSeriesDenied(MutationDenial.INVITATION)
        val selectedRow = events.singleOrNull { it.meetingLong("_id") == selected.eventId } ?: throw MeetingSeriesConflict()
        if (selectedRow["eventStatus"] == "2") throw MeetingSeriesReview("The selected exception is cancelled")
        if (selected.eventId == masterId) {
            if (recurrence.originalStart != selected.instanceStart || recurrence.originalStart.toEpochMilli() in originals) throw MeetingSeriesConflict()
        } else if (selectedRow.meetingLong("originalInstanceTime") != recurrence.originalStart.toEpochMilli() ||
            selectedRow.meetingLong("dtstart") != selected.instanceStart.toEpochMilli() ||
            selectedRow.meetingLong("dtend")?.let { it != selected.instanceEnd.toEpochMilli() } == true) throw MeetingSeriesConflict()
        val active = events.filter { it["eventStatus"] != "2" }
        val candidateIds = active.filter { invited(it, owner) }.map { requireNotNull(it.meetingLong("_id")) }.toSet()
        val candidates = candidateIds.associateWith { mutableListOf<Map<String, String?>>() }
        for (chunk in candidateIds.sorted().chunked(200)) {
            val predicate = ownFilter(chunk, owner)
            val found = rows(CalendarContract.Attendees.CONTENT_URI, predicate.first, predicate.second,
                readBudgets.maxSeriesChildren, arrayOf("_id", "event_id", "attendeeEmail"))
            found.forEach { row ->
                val event = row.meetingLong("event_id")
                if (event !in chunk || row.meetingLong("_id") == null || row["attendeeEmail"]?.trim()?.equals(owner.trim(), ignoreCase = true) != true)
                    throw MeetingSeriesReview("Provider did not return an exact bounded own-candidate set")
                candidates.getValue(event!!) += row
            }
        }
        val exact = candidates.filterValues { it.size == 1 && it.single()["attendeeEmail"] == owner }
            .mapValues { requireNotNull(it.value.single().meetingLong("_id")) }
        if (exact.values.toSet().size != exact.size) throw MeetingSeriesReview("Duplicate own-row identities")
        val ownRows = mutableMapOf<Long, Map<String, String?>>()
        for (chunk in exact.entries.sortedBy { it.key }.chunked(200)) {
            val ids = chunk.map { it.value }
            val eventIds = chunk.map { it.key }
            val found = rows(CalendarContract.Attendees.CONTENT_URI,
                "_id IN (${marks(ids.size)}) AND event_id IN (${marks(eventIds.size)}) AND attendeeEmail = ?",
                (ids.map(Long::toString) + eventIds.map(Long::toString) + owner).toTypedArray(), readBudgets.maxSeriesChildren, CalendarMeetingWriter.attendeeFields)
            found.forEach { row ->
                val event = row.meetingLong("event_id")
                if (event !in eventIds || row.meetingLong("_id") != exact[event] || row["attendeeEmail"] != owner || ownRows.put(event!!, row) != null)
                    throw MeetingSeriesReview("Own-row identity changed during capture")
            }
            if (found.size != chunk.size) throw MeetingSeriesConflict()
        }
        val byId = events.associateBy { requireNotNull(it.meetingLong("_id")) }
        val eligible = ownRows.filter { (id, row) -> selfPolicy(row, byId.getValue(id)) }.keys
        val details = if (validateSelected) when (val result = reader.readDetails(selected)) {
            is CalendarReadState.Ready -> result.value.takeIf { it.occurrence.ref == selected } ?: throw MeetingSeriesConflict()
            CalendarReadState.PermissionRequired -> throw SecurityException()
            CalendarReadState.LimitExceeded -> throw ProviderReadLimitExceeded()
            else -> throw MeetingSeriesConflict()
        } else null
        ensureRead()
        return MeetingSeriesSource(selected, calendar, membership, events, candidates, ownRows, eligible, details)
    }
    suspend fun calendar(id: CalendarId): Map<String, String?> = rows(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id.value), maxRows = 1)
        .singleOrNull()?.also { if (it.meetingLong("_id") != id.value) throw MeetingSeriesConflict() } ?: throw MeetingSeriesConflict()
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private suspend fun rows(uri: Uri, selection: String? = null, args: Array<String>? = null, maxRows: Int, fields: Array<String>? = null): List<Map<String, String?>> {
        ensureRead()
        val cursor = suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal(); continuation.invokeOnCancellation { signal.cancel() }
            try {
                val value = (resolver.query(uri, fields, selection, args, "_id ASC", signal) ?: error("Provider unavailable")).bounded(maxRows)
                try { ensureRead() } catch (failure: Exception) { value.close(); throw failure }
                continuation.resume(value, onCancellation = { _, resource, _ -> resource.close() })
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }
        return cursor.use { buildList { while (it.moveToNext()) {
            currentCoroutineContext().ensureActive()
            add(it.columnNames.associateWith { name -> it.getColumnIndexOrThrow(name).let { column -> if (it.isNull(column)) null else it.getString(column) } })
        } } }
    }
    companion object {
        val MembershipFields = arrayOf("_id", "calendar_id", "_sync_id", "original_id", "original_sync_id", "originalInstanceTime", "originalAllDay", "deleted", "eventStatus")
        fun membershipFilter(calendar: CalendarId, master: Long, sync: String?): Pair<String, Array<String>> {
            val sql = "(_id = ? OR original_id = ?" + if (sync == null) ")" else " OR (calendar_id = ? AND original_sync_id = ?))"
            val args = listOf(master.toString(), master.toString()) + if (sync == null) emptyList() else listOf(calendar.value.toString(), sync)
            return sql to args.toTypedArray()
        }
        fun ownFilter(events: List<Long>, owner: String): Pair<String, Array<String>> {
            val single = try { CalendarMeetingWriter.ownFilter(events.first(), owner) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw MeetingSeriesReview("Private own-row lookup is unsupported for this actor") }
            return "event_id IN (${marks(events.size)})" + single.first.removePrefix("event_id = ?") to
                (events.map(Long::toString) + single.second.drop(1)).toTypedArray()
        }
        private fun marks(size: Int) = List(size) { "?" }.joinToString(",")
        private fun invited(event: Map<String, String?>, owner: String): Boolean = event["deleted"] == "0" && event["isOrganizer"] == "0" &&
            !event["organizer"].isNullOrBlank() && !event.getValue("organizer")!!.equals(owner, true) && event["eventStatus"] in setOf(null, "0", "1") &&
            event["customAppPackage"].isNullOrBlank() && event["customAppUri"].isNullOrBlank()
        private fun selfPolicy(row: Map<String, String?>, event: Map<String, String?>) = row["attendeeRelationship"] == "1" && row["attendeeType"] in setOf("0", "1", "2") &&
            row["attendeeStatus"] in setOf("0", "1", "2", "3", "4") && row["attendeeStatus"] == event["selfAttendeeStatus"] &&
            row["attendeeIdentity"].isNullOrBlank() == row["attendeeIdNamespace"].isNullOrBlank()
    }
}
internal fun Map<String, String?>.meetingLong(key: String) = this[key]?.toLongOrNull()
internal fun meetingHash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun meetingRowJson(row: Map<String, String?>) = JSONObject().apply { row.toSortedMap().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
internal fun meetingRow(json: JSONObject) = json.keys().asSequence().associateWith { if (json.isNull(it)) null else json.getString(it) }
internal fun meetingIdentity(row: Map<String, String?>) = CalendarIdentity(CalendarId(requireNotNull(row.meetingLong("_id"))), CalendarAccount(row["account_name"].orEmpty(), row["account_type"].orEmpty()),
    row["ownerAccount"]?.takeIf(String::isNotBlank), row["_sync_id"]?.takeIf(String::isNotBlank))
internal fun meetingIdentityJson(value: CalendarIdentity) = JSONObject().put("calendar", value.calendarId.value).put("name", value.account.name).put("type", value.account.type)
    .put("owner", value.owner ?: JSONObject.NULL).put("uid", value.providerId ?: JSONObject.NULL)
internal fun meetingParseIdentity(json: JSONObject): CalendarIdentity {
    require(json.has("owner") && json.has("uid"))
    return CalendarIdentity(CalendarId(json.getLong("calendar")), CalendarAccount(json.getString("name"), json.getString("type")),
        if (json.isNull("owner")) null else json.getString("owner"), if (json.isNull("uid")) null else json.getString("uid"))
}
internal fun meetingRefJson(ref: EventRef) = JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId).put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString())
    .apply { ref.recurrence?.let { put("master", it.masterEventId); put("original", it.originalStart.toString()) } }
internal fun meetingParseRef(json: JSONObject) = EventRef(CalendarId(json.getLong("calendar")), json.getLong("event"), Instant.parse(json.getString("start")), Instant.parse(json.getString("end")),
    if (json.has("master")) RecurrenceRef(json.getLong("master"), Instant.parse(json.getString("original"))) else null)
