package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** Independent of display filters. Calendar data stays in memory and is never logged. */
class CalendarReminderSource(
    private val resolver: ContentResolver,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readBudgets: ProviderReadBudgets = ProviderReadBudgets(),
) : ReminderSource {
    private data class Calendar(val info: CalendarInfo, val identity: ReminderCalendarIdentity?)
    private data class Event(val id: Long, val calendar: CalendarId, val allDay: Boolean?, val start: Instant?,
        val originalId: Long?, val originalStart: Instant?, val recurring: Boolean, val eligible: Boolean?, val uid: String?)
    private data class Metadata(val calendars: Map<CalendarId, Calendar>, val removed: Set<CalendarId>,
        val blocked: Map<CalendarId, ReminderSourceProblem>, val warnings: MutableSet<ReminderSourceFailure>,
        val events: Map<Long, Event>, val failedCalendars: Set<CalendarId>,
        val offsets: Map<Long, Set<Int>>, val unresolvedEvents: Map<Long, ReminderSourceProblem>)
    private data class Plan(val bounds: InstantBounds, val eventIds: Set<Long>)
    private data class Occurrences(val candidates: Map<ReminderKey, ReminderCandidate>, val failedCalendars: Set<CalendarId>,
        val invalidKeys: Set<ReminderKey>)

    override suspend fun loadDueWindow(window: InstantBounds, configuration: ReminderConfiguration): ReminderSourceResult = try {
        withContext(io + readBudgetContext(readBudgets.range)) {
            require(window.endExclusive > window.start)
            if (!configuration.enabled || configuration.enabledCalendars.isEmpty()) return@withContext ReminderSourceResult.Ready(
                ReminderSourceSnapshot(window, emptyList(), emptySet(), emptyMap()))
            ensureRead()
            val metadata = metadata(configuration)
            val groups = mutableMapOf<Pair<Boolean, Int>, MutableSet<Long>>()
            metadata.offsets.forEach { (id, offsets) ->
                val event = metadata.events.getValue(id)
                if (event.eligible == true && event.allDay != null) offsets.forEach { offset ->
                    groups.getOrPut(event.allDay to offset) { mutableSetOf() } += id
                }
            }
            val plans = mutableListOf<Plan>()
            groups.forEach { (group, ids) ->
                try {
                    val (allDay, offset) = group
                    val bounds = if (allDay) {
                        // Reverse the wall-clock offset, then pad UTC date lookup across zone/DST transitions.
                        val start = window.start.atZone(configuration.deviceZone).toLocalDateTime().plusMinutes(offset.toLong()).toLocalDate().minusDays(2)
                        val end = window.endExclusive.atZone(configuration.deviceZone).toLocalDateTime().plusMinutes(offset.toLong()).toLocalDate().plusDays(3)
                        InstantBounds(start.atStartOfDay(ZoneOffset.UTC).toInstant(), end.atStartOfDay(ZoneOffset.UTC).toInstant())
                    } else InstantBounds(window.start.plusSeconds(offset.toLong() * 60), window.endExclusive.plusSeconds(offset.toLong() * 60))
                    plans += Plan(bounds, ids)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ids.forEach { metadata.warnings += ReminderSourceFailure(metadata.events.getValue(it).calendar, ReminderSourceProblem.INVALID_TIME) } }
            }
            val occurrences = occurrences(plans, metadata, configuration)
            val failed = metadata.failedCalendars + occurrences.failedCalendars
            failed.forEach { metadata.warnings += ReminderSourceFailure(it, ReminderSourceProblem.QUERY_FAILED) }
            val candidates = occurrences.candidates.filter { (key, value) -> key !in occurrences.invalidKeys && value.due in window }.values.toList()
            val observed = metadata.calendars.mapNotNull { (id, calendar) -> calendar.identity?.let { id to it } }.toMap()
            val incomplete = metadata.warnings.map { it.calendarId }.toSet() + metadata.blocked.keys + failed
            ReminderSourceResult.Ready(ReminderSourceSnapshot(window, candidates,
                configuration.enabledCalendars.intersect(observed.keys) - incomplete - metadata.removed,
                observed, metadata.warnings.toList(), metadata.removed))
        }
    } catch (_: SecurityException) { ReminderSourceResult.PermissionRequired }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ReminderSourceResult.Unavailable }

    override suspend fun resolve(keys: Set<ReminderKey>, configuration: ReminderConfiguration): ReminderResolutionResult = try {
        withContext(io + readBudgetContext(readBudgets.range)) {
            if (keys.isEmpty()) return@withContext ReminderResolutionResult.Ready(emptyMap())
            if (!configuration.enabled) return@withContext ReminderResolutionResult.Ready(keys.associateWith { ReminderResolution.GoneOrIneligible })
            ensureRead()
            val active = keys.filter { it.calendarId in configuration.enabledCalendars }.toSet()
            if (active.isEmpty()) return@withContext ReminderResolutionResult.Ready(keys.associateWith { ReminderResolution.GoneOrIneligible })
            val narrowed = configuration.copy(enabledCalendars = active.map { it.calendarId }.toSet())
            val metadata = metadata(narrowed, active.map { it.masterOrEventId }.toSet())
            // Share expanded-instance queries by bounded UTC date buckets, not one detail query per key.
            val buckets = mutableMapOf<Long, MutableSet<Long>>()
            for (key in active) {
                val related = metadata.events.values.filter { it.calendar == key.calendarId && (it.id == key.masterOrEventId || it.originalId == key.masterOrEventId) &&
                    it.eligible == true && key.resolvedMinutesBefore in metadata.offsets[it.id].orEmpty() &&
                    metadata.unresolvedEvents[it.id] != ReminderSourceProblem.INVALID_TIME }
                val starts = mutableSetOf(key.originalOrInstanceStart)
                related.filter { it.originalStart == key.originalOrInstanceStart || (!it.recurring && it.originalId == null) }
                    .mapNotNullTo(starts) { it.start }
                starts.forEach { start ->
                    val day = Math.floorDiv(start.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(), 32) * 32
                    buckets.getOrPut(day) { mutableSetOf() }.addAll(related.map { it.id })
                }
            }
            val plans = buckets.filterValues { it.isNotEmpty() }.map { (day, ids) -> Plan(
                InstantBounds(LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant(), LocalDate.ofEpochDay(day + 32).atStartOfDay(ZoneOffset.UTC).toInstant()), ids) }
            val occurrences = occurrences(plans, metadata, configuration)
            ReminderResolutionResult.Ready(keys.associateWith { key ->
                when {
                    key.calendarId !in configuration.enabledCalendars -> ReminderResolution.GoneOrIneligible
                    key.calendarId in metadata.removed -> ReminderResolution.GoneOrIneligible
                    metadata.blocked[key.calendarId] != null -> ReminderResolution.Deferred(metadata.blocked.getValue(key.calendarId))
                    key.calendarId in metadata.failedCalendars || key.calendarId in occurrences.failedCalendars || key in occurrences.invalidKeys ->
                        ReminderResolution.Deferred(ReminderSourceProblem.QUERY_FAILED)
                    occurrences.candidates[key] != null -> ReminderResolution.Eligible(occurrences.candidates.getValue(key))
                    else -> {
                        val related = metadata.events.values.filter { it.calendar == key.calendarId && (it.id == key.masterOrEventId || it.originalId == key.masterOrEventId) }
                        val problem = related.firstNotNullOfOrNull { metadata.unresolvedEvents[it.id] }
                        if (problem != null) ReminderResolution.Deferred(problem) else ReminderResolution.GoneOrIneligible
                    }
                }
            })
        }
    } catch (_: SecurityException) { ReminderResolutionResult.PermissionRequired }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ReminderResolutionResult.Unavailable }

    private suspend fun metadata(config: ReminderConfiguration, masterIds: Set<Long>? = null): Metadata {
        val calendars = mutableMapOf<CalendarId, Calendar>()
        val removed = mutableSetOf<CalendarId>()
        val blocked = mutableMapOf<CalendarId, ReminderSourceProblem>()
        val warnings = mutableSetOf<ReminderSourceFailure>()
        for (chunk in config.enabledCalendars.toList().chunked(Batch)) {
            try {
                calendarQuery("_id IN (${marks(chunk.size)})", chunk.map { it.value.toString() }.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = CalendarId(cursor.requiredLong("_id"))
                        if (id !in chunk) continue
                        val access = cursor.int(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                        val info = CalendarInfo(id, "", cursor.int(CalendarContract.Calendars.CALENDAR_COLOR) ?: FallbackColor,
                            CalendarAccount(cursor.text(CalendarContract.Calendars.ACCOUNT_NAME).orEmpty(), cursor.text(CalendarContract.Calendars.ACCOUNT_TYPE).orEmpty()),
                            cursor.text(CalendarContract.Calendars.OWNER_ACCOUNT), null, access,
                            cursor.int(CalendarContract.Calendars.VISIBLE) == 1, cursor.int(CalendarContract.Calendars.SYNC_EVENTS) == 1,
                            providerId = cursor.text(CalendarContract.Calendars._SYNC_ID))
                        val identity = calendarIdentity(info)
                        calendars[id] = Calendar(info, identity)
                        val problem = when {
                            identity == null -> ReminderSourceProblem.IDENTITY_UNAVAILABLE
                            config.expectedCalendarIdentities[id] == null -> ReminderSourceProblem.MISSING_EXPECTED_IDENTITY
                            config.expectedCalendarIdentities.getValue(id).isKnownDifferentFrom(identity) -> ReminderSourceProblem.IDENTITY_MISMATCH
                            access == null || access < CalendarContract.Calendars.CAL_ACCESS_READ -> ReminderSourceProblem.READ_ACCESS_UNAVAILABLE
                            else -> null
                        }
                        if (problem != null) blocked[id] = problem
                        if (!info.providerVisible) warnings += ReminderSourceFailure(id, ReminderSourceProblem.PROVIDER_HIDDEN)
                        if (!info.syncEnabled) warnings += ReminderSourceFailure(id, ReminderSourceProblem.SYNC_DISABLED)
                    }
                }
                removed += chunk.filter { it !in calendars }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: SecurityException) { throw denied }
            catch (_: Exception) { chunk.forEach { blocked[it] = ReminderSourceProblem.CALENDAR_UNAVAILABLE } }
        }
        blocked.forEach { (id, problem) -> warnings += ReminderSourceFailure(id, problem) }
        removed.forEach { warnings += ReminderSourceFailure(it, ReminderSourceProblem.CALENDAR_UNAVAILABLE) }
        val readable = calendars.keys - blocked.keys
        val events = mutableMapOf<Long, Event>()
        val failed = mutableSetOf<CalendarId>()
        for (calendarsChunk in readable.toList().chunked(Batch)) {
            val idChunks: List<List<Long>?> = masterIds?.toList()?.chunked(Batch)?.map { it } ?: listOf(null)
            for (eventChunk in idChunks) {
                val selection = "calendar_id IN (${marks(calendarsChunk.size)})" + (eventChunk?.let { " AND (_id IN (${marks(it.size)}) OR original_id IN (${marks(it.size)}))" } ?: "")
                val args = (calendarsChunk.map { it.value.toString() } + (eventChunk?.map { it.toString() }.orEmpty()) + (eventChunk?.map { it.toString() }.orEmpty())).toTypedArray()
                try {
                    eventQuery(selection, args).use { cursor ->
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val calendar = CalendarId(cursor.requiredLong("calendar_id"))
                            if (calendar !in calendarsChunk) continue
                            val id = cursor.requiredLong("_id")
                            if (eventChunk != null && id !in eventChunk && cursor.long("original_id") !in eventChunk) continue
                            val status = cursor.int(CalendarContract.Events.STATUS)
                            val self = cursor.int(CalendarContract.Events.SELF_ATTENDEE_STATUS)
                            val deleted = cursor.int(CalendarContract.Events.DELETED)
                            val eligible = when {
                                deleted == 1 || status == CalendarContract.Events.STATUS_CANCELED || self == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> false
                                deleted != 0 || status == null || self == null -> null
                                else -> true
                            }
                            events[id] = Event(id, calendar, cursor.flag(CalendarContract.Events.ALL_DAY), cursor.long(CalendarContract.Events.DTSTART)?.let(Instant::ofEpochMilli),
                                cursor.long(CalendarContract.Events.ORIGINAL_ID), cursor.long(CalendarContract.Events.ORIGINAL_INSTANCE_TIME)?.let(Instant::ofEpochMilli),
                                !cursor.text(CalendarContract.Events.RRULE).isNullOrBlank() || !cursor.text(CalendarContract.Events.RDATE).isNullOrBlank(),
                                eligible, cursor.text(CalendarContract.Events.UID_2445)?.takeIf { it.isNotBlank() }?.let { hash(listOf("event-uid-v1", it)) })
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (denied: SecurityException) { throw denied }
                catch (_: Exception) { failed += calendarsChunk }
            }
        }
        val offsets = mutableMapOf<Long, MutableSet<Int>>()
        val unresolved = mutableMapOf<Long, ReminderSourceProblem>()
        events.values.forEach { event ->
            if (event.eligible == null || event.allDay == null) unresolved[event.id] = ReminderSourceProblem.QUERY_FAILED
            if (event.start == null || (event.originalId == null) != (event.originalStart == null))
                unresolved[event.id] = ReminderSourceProblem.INVALID_TIME
        }
        for (chunk in events.values.filter { it.eligible == true && it.allDay != null && unresolved[it.id] != ReminderSourceProblem.INVALID_TIME }.map { it.id }.chunked(Batch)) {
            try {
                query(CalendarContract.Reminders.CONTENT_URI, reminderFields, "event_id IN (${marks(chunk.size)})", chunk.map { it.toString() }.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = cursor.requiredLong(CalendarContract.Reminders.EVENT_ID)
                        if (id !in chunk) continue
                        val event = events.getValue(id)
                        val defaults = config.defaults[event.calendar]
                        val method = cursor.int(CalendarContract.Reminders.METHOD)
                        val local = when (method) {
                            CalendarContract.Reminders.METHOD_ALERT -> true
                            CalendarContract.Reminders.METHOD_DEFAULT -> when (defaults?.defaultMethod) {
                                ReminderDefaultMethod.LOCAL_ALERT -> true
                                ReminderDefaultMethod.NON_LOCAL -> false
                                else -> null
                            }
                            CalendarContract.Reminders.METHOD_EMAIL, CalendarContract.Reminders.METHOD_SMS, CalendarContract.Reminders.METHOD_ALARM -> false
                            else -> null
                        }
                        if (local == null) { unresolved[id] = ReminderSourceProblem.UNRESOLVED_METHOD; continue }
                        if (!local) continue
                        val raw = cursor.int(CalendarContract.Reminders.MINUTES)
                        val minutes = if (raw == CalendarContract.Reminders.MINUTES_DEFAULT)
                            if (event.allDay == true) defaults?.allDayMinutesBefore else defaults?.timedMinutesBefore else raw
                        if (minutes == null || minutes == CalendarContract.Reminders.MINUTES_DEFAULT) {
                            unresolved[id] = ReminderSourceProblem.UNRESOLVED_MINUTES; continue
                        }
                        offsets.getOrPut(id) { mutableSetOf() } += minutes
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: SecurityException) { throw denied }
            catch (_: Exception) { chunk.forEach { unresolved[it] = ReminderSourceProblem.QUERY_FAILED; failed += events.getValue(it).calendar } }
        }
        unresolved.forEach { (id, problem) -> warnings += ReminderSourceFailure(events.getValue(id).calendar, problem) }
        return Metadata(calendars, removed, blocked, warnings, events, failed, offsets, unresolved)
    }

    private suspend fun occurrences(plans: List<Plan>, metadata: Metadata, config: ReminderConfiguration): Occurrences {
        val candidates = mutableMapOf<ReminderKey, ReminderCandidate>()
        val failed = mutableSetOf<CalendarId>()
        val invalid = mutableSetOf<ReminderKey>()
        for (plan in plans.distinct()) for (chunk in plan.eventIds.toList().chunked(Batch)) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, plan.bounds.start.toEpochMilli()); ContentUris.appendId(it, plan.bounds.endExclusive.toEpochMilli())
            }.build()
            try {
                query(uri, instanceFields, "event_id IN (${marks(chunk.size)})", chunk.map { it.toString() }.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = cursor.requiredLong(CalendarContract.Instances.EVENT_ID)
                        if (id !in chunk) continue
                        val event = metadata.events[id] ?: continue
                        if (event.calendar in metadata.failedCalendars || event.eligible != true) continue
                        if (metadata.unresolvedEvents[id] == ReminderSourceProblem.INVALID_TIME) { failed += event.calendar; continue }
                        if (cursor.long(CalendarContract.Instances.CALENDAR_ID) != event.calendar.value) { failed += event.calendar; continue }
                        val deleted = cursor.int(CalendarContract.Events.DELETED)
                        val status = cursor.int(CalendarContract.Events.STATUS)
                        val self = cursor.int(CalendarContract.Events.SELF_ATTENDEE_STATUS)
                        if (deleted == 1 || status == CalendarContract.Events.STATUS_CANCELED || self == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        if (deleted != 0 || status == null || self == null) { failed += event.calendar; continue }
                        try {
                            val start = Instant.ofEpochMilli(cursor.requiredLong(CalendarContract.Instances.BEGIN))
                            val end = Instant.ofEpochMilli(cursor.requiredLong(CalendarContract.Instances.END))
                            val recurrence = if (event.originalId != null && event.originalStart != null) RecurrenceRef(event.originalId, event.originalStart)
                                else if (event.recurring) RecurrenceRef(event.id, start) else null
                            val ref = EventRef(event.calendar, id, start, end, recurrence)
                            val allDay = cursor.flag(CalendarContract.Instances.ALL_DAY) ?: error("Unknown event time")
                            if (allDay != event.allDay) { failed += event.calendar; continue }
                            val time = if (allDay) EventTime.AllDay.fromProvider(start, end) else EventTime.Timed(start, end)
                            val occurrence = EventOccurrence(ref, time, cursor.text(CalendarContract.Instances.TITLE).orEmpty(),
                                cursor.int(CalendarContract.Instances.DISPLAY_COLOR) ?: metadata.calendars.getValue(event.calendar).info.color)
                            for (minutes in metadata.offsets[id].orEmpty()) {
                                val key = ReminderKey.of(ref, minutes)
                                val candidate = ReminderCandidate(key, metadata.calendars.getValue(event.calendar).identity!!,
                                    occurrence, dueInstant(time, minutes, config.deviceZone), event.uid)
                                val previous = candidates.putIfAbsent(key, candidate)
                                if (previous != null && previous != candidate) invalid += key
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed += event.calendar; metadata.warnings += ReminderSourceFailure(event.calendar, ReminderSourceProblem.INVALID_TIME) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: SecurityException) { throw denied }
            catch (_: Exception) { chunk.forEach { metadata.events[it]?.calendar?.let(failed::add) } }
        }
        invalid.forEach { failed += it.calendarId }
        return Occurrences(candidates, failed, invalid)
    }

    private suspend fun calendarQuery(selection: String, args: Array<String>): Cursor = try {
        query(CalendarContract.Calendars.CONTENT_URI, calendarFields + CalendarContract.Calendars._SYNC_ID, selection, args)
    } catch (_: IllegalArgumentException) {
        // Some providers omit optional sync IDs. Absence is unknown, never a replacement proof.
        query(CalendarContract.Calendars.CONTENT_URI, calendarFields, selection, args)
    } catch (failure: android.database.sqlite.SQLiteException) {
        // Public SQLite-backed providers may surface a missing projection column directly.
        // Other database errors remain failures, rather than weakening a known identity.
        if (!failure.message.orEmpty().contains("no such column: _sync_id", ignoreCase = true)) throw failure
        query(CalendarContract.Calendars.CONTENT_URI, calendarFields, selection, args)
    }
    private suspend fun eventQuery(selection: String, args: Array<String>): Cursor = try {
        query(CalendarContract.Events.CONTENT_URI, eventFields + CalendarContract.Events.UID_2445, selection, args)
    } catch (_: IllegalArgumentException) {
        // Optional UID support varies by provider. Missing UID is unknown, not identity replacement.
        query(CalendarContract.Events.CONTENT_URI, eventFields, selection, args)
    }
    private fun ensureRead() { if (!hasPermission(Manifest.permission.READ_CALENDAR)) throw SecurityException() }
    private suspend fun query(uri: Uri, fields: Array<String>, selection: String, args: Array<String>): Cursor {
        ensureRead()
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val cursor = (resolver.query(uri, fields, selection, args, null, signal) ?: error("Provider unavailable")).bounded()
                try { ensureRead() } catch (failure: Exception) { cursor.close(); throw failure }
                continuation.resume(cursor, onCancellation = { _, resource, _ -> resource.close() })
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
        }
    }

    companion object {
        private const val Batch = 200 // ID+calendar predicates remain below SQLite's legacy 999 bind limit.
        private const val FallbackColor = 0xff607d8b.toInt()
        private fun marks(size: Int) = List(size) { "?" }.joinToString(",")
        /** Shared with reminder setup: display names and provider visibility never change identity. */
        fun calendarIdentity(calendar: CalendarInfo): ReminderCalendarIdentity? {
            if (calendar.account.name.isBlank() || calendar.account.type.isBlank()) return null
            return ReminderCalendarIdentity(hash(listOf("calendar-v1", calendar.id.value.toString(), calendar.account.type, calendar.account.name, calendar.owner)),
                calendar.providerId?.takeIf { it.isNotBlank() }?.let { hash(listOf("calendar-uid-v1", it)) })
        }
        private fun hash(values: List<String?>): String {
            val canonical = values.joinToString("") { value -> value?.let { "${it.toByteArray(Charsets.UTF_8).size}:$it" } ?: "-1:" }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        private fun Cursor.text(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
        private fun Cursor.long(name: String): Long? = text(name)?.toLongOrNull()
        private fun Cursor.int(name: String): Int? = text(name)?.toIntOrNull()
        private fun Cursor.flag(name: String): Boolean? = when (int(name)) { 0 -> false; 1 -> true; else -> null }
        private fun Cursor.requiredLong(name: String): Long = long(name) ?: error("Missing provider identity")
        private val calendarFields = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.OWNER_ACCOUNT, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.SYNC_EVENTS, CalendarContract.Calendars.CALENDAR_COLOR)
        private val eventFields = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DTSTART, CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.STATUS, CalendarContract.Events.DELETED, CalendarContract.Events.SELF_ATTENDEE_STATUS)
        private val reminderFields = arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD)
        private val instanceFields = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.TITLE, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Events.STATUS,
            CalendarContract.Events.DELETED, CalendarContract.Events.SELF_ATTENDEE_STATUS)
    }
}
