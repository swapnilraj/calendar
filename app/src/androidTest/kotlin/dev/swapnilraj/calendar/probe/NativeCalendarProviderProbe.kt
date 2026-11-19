package dev.swapnilraj.calendar.probe

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as C
import android.provider.CalendarContract.Events as E
import android.provider.CalendarContract.Instances as I
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicitly opted-in, SDK-only native LOCAL-provider experiment. This is not a test runner for
 * Google sync, a production provider attestor, or an entry point included in the application APK.
 *
 * Read-only bootstrap: probeOptIn=IDENTITY_ONLY_V1 and probeRunId=<canonical UUID> emits
 * providerIdentity using PackageManager and public APK files only. It performs no calendar query,
 * permission check/grant, fixture mutation, or calendar cleanup. Use its complete signing history
 * and binary/build descriptor to supply the strict pins for a separate explicitly opted-in run.
 *
 * Required LOCAL-fixture arguments (all pins are checked before any fixture mutation):
 * probeOptIn=LOCAL_FIXTURE_V1; probeRunId=<canonical UUID>; expectedBuildFingerprint;
 * expectedProviderPackage; expectedProviderVersion; expectedBaseSha256;
 * expectedSplitsJson=[{"name":"split-name.apk","sha256":"..."}] (sorted by name, [] allowed);
 * expectedSignersJson=["sha256",...] (sorted); expectedSigningHistoryJson=["sha256",...] (ordered).
 * Optional probeEndZoneMode=distinct|null (default distinct). If a native implementation rejects
 * the distinct zone, the run fails and cleans up; it never retries creation with another payload.
 * The lead can review that report and request a new UUID run explicitly using null instead.
 *
 * No shell-identity adoption, AccountManager, permission grants, network, direct provider database,
 * global calendar reads, provider restarts, cache resets, or retries of a mutation occur here.
 * The 15-minute suite deadline is cooperative. Direct queries receive a 30-second cancellation
 * signal; SDK applyBatch has no cancellation API and may outlive that deadline. The host must
 * monitor safe phase progress; this runner never kills/restarts a provider to enforce a timeout.
 */
class NativeCalendarProviderProbe : Instrumentation() {
    private var arguments = Bundle()

    // This probe exercises the native provider only. Avoid CalendarApp's reminder/widget startup;
    // no application manifest or production initialization behavior changes.
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments?.let(::Bundle) ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val report = JSONObject().put("schema", 1).put("scope", "native-local-provider-only")
            .put("googleWorkspaceRoundTrip", "NOT_RUN").put("productionProfileActivation", "NONE")
            .put("deadlineKind", "cooperative").put("queryTimeoutSeconds", 30)
            .put("directQueryCap", 320).put("batchAssertionCap", 128)
            .put("batchAssertionCountKind", "operations-in-attempted-batches")
        val probe = Probe(targetContext, arguments) { phase ->
            sendStatus(0, Bundle().apply { putString("probe_phase", phase) })
        }
        var successful = false
        try {
            probe.run(report)
            successful = true
        } catch (failure: Exception) {
            report.put("failure", probe.safeFailure(failure))
        } finally {
            val cleaned = if (probe.identityOnly) true else probe.cleanup(report)
            probe.close()
            report.put("status", if (successful && cleaned) probe.successStatus else "FAIL")
            var encoded = report.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size > 65_536) {
                encoded = JSONObject().put("schema", 1).put("status", "FAIL")
                    .put("scope", "native-local-provider-only").put("failure", "REPORT_TOO_LARGE")
                    .put("cleanup", report.optJSONObject("cleanup") ?: JSONObject.NULL).toString()
                successful = false
            }
            finish(if (successful && cleaned) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("probe_report", encoded) })
        }
    }

    private class Failure(val code: String) : RuntimeException()
    private data class Occurrence(val eventId: Long, val begin: Long, val end: Long, val allDay: Boolean) {
        fun logical() = Triple(begin, end, allDay)
        fun json(): JSONObject = JSONObject().put("begin", Instant.ofEpochMilli(begin).toString())
            .put("end", Instant.ofEpochMilli(end).toString()).put("allDay", allDay)
    }
    private data class Seed(val label: String, val day: LocalDate, val zone: ZoneId, val allDay: Boolean) {
        val duration: String get() = if (allDay) "P2D" else "PT5400S"
        fun start(index: Int): Instant = if (allDay) day.plusDays(index.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant()
            else day.plusDays(index.toLong()).atTime(9, 0).atZone(zone).toInstant()
        fun end(index: Int): Instant = start(index).plusSeconds(if (allDay) 172_800 else 5_400)
        fun until(index: Int): String = if (allDay) day.plusDays(index.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE)
            else DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(start(index))
        fun expected(): List<Triple<Long, Long, Boolean>> = (0 until 8).map { Triple(start(it).toEpochMilli(), end(it).toEpochMilli(), allDay) }
        val windowStart: Long get() = day.minusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val windowEnd: Long get() = day.plusDays(12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    private enum class Limit { COUNT, UNTIL }
    private enum class Variant { RULE_ONLY_CONTROL, EXACT_CONTEXT, ROLLBACK }

    private class Probe(private val context: Context, private val arguments: Bundle, private val progress: (String) -> Unit) {
        private val resolver: ContentResolver get() = context.contentResolver
        private val watchdog = Executors.newSingleThreadScheduledExecutor()
        private var phase = "preflight"
        private var runId: String? = null
        private var account: String? = null
        private var owner: String? = null
        private var calendarId: Long? = null
        private var creationAttempted = false
        private var preflightAbsent = false
        private var inCleanup = false
        private var directQueries = 0
        private var batchAssertions = 0
        private var began = SystemClock.elapsedRealtime()
        private val knownEventIds = linkedSetOf<Long>()
        private val knownUids = linkedMapOf<Long, String>()
        private val cases = JSONArray()
        private lateinit var pinned: JSONObject
        private var endZoneMode = "distinct"
        var identityOnly: Boolean = false
            private set
        var successStatus = "PASS"
            private set
        private var appPhase: String? = null
        private var appState: NativeWriterProbeState? = null
        private var appCompleted = false

        fun run(report: JSONObject) {
            val optIn = argument("probeOptIn")
            requireCondition(optIn in setOf("LOCAL_FIXTURE_V1", "IDENTITY_ONLY_V1", "LOCAL_APP_CREATE_V1"), "OPT_IN_REQUIRED")
            identityOnly = optIn == "IDENTITY_ONLY_V1"
            runId = argument("probeRunId").also { requireCondition(UUID.fromString(it).toString() == it, "INVALID_RUN_ID") }
            report.put("runId", runId)
            if (identityOnly) {
                step("identity-only")
                report.put("scope", "native-provider-identity-only").put("providerIdentity", identity())
                return
            }
            if (optIn == "LOCAL_APP_CREATE_V1") {
                appPhase = argument("probeAppPhase").also { requireCondition(it in setOf("CREATE_STAGE", "RECOVER_READ_ONLY", "CLEANUP"), "INVALID_APP_PHASE") }
                runApp(report)
                appCompleted = true
                return
            }
            account = "daylight-native-probe-${requireNotNull(runId)}"
            owner = "${requireNotNull(runId)}@probe.invalid"
            endZoneMode = arguments.getString("probeEndZoneMode") ?: "distinct"
            requireCondition(endZoneMode in setOf("distinct", "null"), "INVALID_END_ZONE_MODE")
            report.put("runId", runId).put("endZoneMode", endZoneMode).put("cases", cases)
            permissions()
            pinned = identity().also(::verifyPins)
            report.put("providerIdentity", pinned)
            requireCondition(namespace().isEmpty(), "FIXTURE_NAMESPACE_ALREADY_EXISTS")
            preflightAbsent = true
            step("create-local-calendar")
            creationAttempted = true
            val requestedCalendarUri = localCalendarUri()
            val uri = resolver.insert(requestedCalendarUri, ContentValues().apply {
                put(C.ACCOUNT_NAME, account); put(C.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(C.NAME, account); put(C.OWNER_ACCOUNT, owner)
                put(C.CALENDAR_DISPLAY_NAME, "Daylight native LOCAL probe")
                put(C.CALENDAR_COLOR, 0xff567087.toInt()); put(C.CALENDAR_ACCESS_LEVEL, C.CAL_ACCESS_OWNER)
                put(C.CALENDAR_TIME_ZONE, "UTC"); put(C.SYNC_EVENTS, 1); put(C.VISIBLE, 0)
            }) ?: throw Failure("CALENDAR_INSERT_ACK_MISSING")
            // Record only query-key names and comparison facts, never the account values/URI.
            report.put("calendarInsertAcknowledgment", calendarAckFacts(uri, requestedCalendarUri))
            // CalendarProvider appends the ID to the request URI, retaining its exact local-account
            // query. Preserve the validated parent ID before subsequent provider reads can fail.
            calendarId = idFrom(uri, "calendars", requestedCalendarUri.encodedQuery)
            guardCalendar()
            val seeds = listOf(
                Seed("new-york-spring", LocalDate.of(2028, 3, 10), ZoneId.of("America/New_York"), false),
                Seed("lord-howe-half-hour", LocalDate.of(2028, 4, 1), ZoneId.of("Australia/Lord_Howe"), false),
                Seed("utc-date-two-day", LocalDate.of(2028, 3, 10), ZoneId.of("UTC"), true),
            )
            for (seed in seeds) for (limit in Limit.entries) for (variant in Variant.entries) runCase(seed, limit, variant)
            report.put("directQueryCountBeforeCleanup", directQueries).put("batchAssertionCountBeforeCleanup", batchAssertions)
            requireCondition(cases.length() == 18, "CASE_COUNT")
        }

        private fun runApp(report: JSONObject) {
            requireCondition(Build.VERSION.SDK_INT >= 35, "APP_PROBE_REQUIRES_API35")
            val run = requireNotNull(runId)
            val boot = argument("expectedBootId").also { requireCondition(UUID.fromString(it).toString() == it, "INVALID_BOOT_ID") }
            pinned = identity().also(::verifyPins)
            report.put("scope", "native-local-app-writer-only").put("providerIdentity", pinned).put("appPhase", appPhase).put("receiptVersion", 6)
            account = "daylight-native-probe-$run"; owner = "$run@probe.invalid"
            permissions()
            val state = if (appPhase == "CREATE_STAGE") NativeWriterProbeState.create(context, run, boot, pinned)
                else NativeWriterProbeState.open(context, run, boot, pinned)
            appState = state
            began = NativeWriterProbeState.number(state.value, "started")
            directQueries = NativeWriterProbeState.number(state.value, "queries").toInt()
            batchAssertions = NativeWriterProbeState.number(state.value, "assertions").toInt()
            preflightAbsent = state.value.getBoolean("preflightAbsent"); creationAttempted = state.value.getBoolean("creationAttempted")
            calendarId = if (state.value.isNull("calendar")) null else state.value.getLong("calendar")
            for (index in 0 until state.cases.length()) {
                val row = state.cases.getJSONObject(index); knownEventIds += row.getLong("id"); knownUids[row.getLong("id")] = row.getString("uid")
            }
            deadline()
            if (appPhase == "CREATE_STAGE") {
                requireCondition(state.phase == "CREATING" && namespace().isEmpty(), "FIXTURE_NAMESPACE_ALREADY_EXISTS")
                preflightAbsent = true; saveAppState()
                step("app-create-local-calendar"); creationAttempted = true; saveAppState()
                val requested = localCalendarUri()
                val inserted = resolver.insert(requested, ContentValues().apply {
                    put(C.ACCOUNT_NAME, account); put(C.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL); put(C.NAME, account); put(C.OWNER_ACCOUNT, owner)
                    put(C.CALENDAR_DISPLAY_NAME, "Daylight native app writer probe"); put(C.CALENDAR_COLOR, 0xff567087.toInt())
                    put(C.CALENDAR_ACCESS_LEVEL, C.CAL_ACCESS_OWNER); put(C.CALENDAR_TIME_ZONE, "UTC"); put(C.SYNC_EVENTS, 1); put(C.VISIBLE, 0)
                    put(C.ALLOWED_REMINDERS, "1"); put(C.MAX_REMINDERS, 2); put(C.ALLOWED_AVAILABILITY, "0,1,2"); put(C.ALLOWED_ATTENDEE_TYPES, "0,1,2")
                }) ?: throw Failure("CALENDAR_INSERT_ACK_MISSING")
                calendarId = idFrom(inserted, "calendars", requested.encodedQuery); saveAppState()
            } else {
                requireCondition(preflightAbsent && creationAttempted && calendarId != null && state.cases.length() == 6, "APP_OWNERSHIP_INCOMPLETE")
                requireCondition(state.phase == if (appPhase == "RECOVER_READ_ONLY") "STAGED" else "RECOVERED", "APP_PHASE_MISMATCH")
                if (appPhase == "RECOVER_READ_ONLY") requireCondition(context.getDatabasePath(state.database).isFile, "APP_STORE_MISSING")
            }
            guardCalendar()
            if (appPhase == "CLEANUP") {
                requireCondition((0 until state.cases.length()).all { state.cases.getJSONObject(it).getInt("recoveries") == 2 }, "APP_RECOVERY_INCOMPLETE")
                report.put("cases", NativeCalendarWriterProbeCases.reportCases(state)).put("ownershipProof", "EXACT_RUN_MANIFEST_AND_CALENDAR")
                return
            }
            val sourceUid = query(C.CONTENT_URI, arrayOf(C._SYNC_ID), calendarSelection(), calendarArgs()).single()[C._SYNC_ID]?.takeIf(String::isNotBlank)
            val identity = dev.swapnilraj.calendar.core.CalendarIdentity(dev.swapnilraj.calendar.core.CalendarId(calendar()),
                dev.swapnilraj.calendar.core.CalendarAccount(requireNotNull(account), CalendarContract.ACCOUNT_TYPE_LOCAL), owner, sourceUid)
            val app = NativeCalendarWriterProbeCases(context, state, calendar(), identity,
                beforeQuery = { consumeAppQuery() }, beforeBatch = { count ->
                    deadline(); requireCondition(batchAssertions + count <= 128, "BATCH_ASSERTION_BUDGET"); batchAssertions += count; saveAppState()
                }, guard = ::guardCalendar, persist = ::saveAppState, watchdog = watchdog, progress = ::step)
            try {
                if (appPhase == "CREATE_STAGE") {
                    app.create(); state.phase = "STAGED"; successStatus = "STAGED"
                } else {
                    state.phase = "RECOVERING"; saveAppState()
                    report.put("recovery", app.recover()); state.phase = "RECOVERED"; successStatus = "RECOVERED"
                }
            } finally { app.close(); saveAppState() }
            report.put("cases", NativeCalendarWriterProbeCases.reportCases(state)).put("ownershipProof", "EXACT_RUN_MANIFEST_AND_CALENDAR")
                .put("fixtureRetained", true).put("directQueryCount", directQueries).put("batchAssertionCount", batchAssertions)
        }

        private fun consumeAppQuery() {
            requireCondition(++directQueries <= 300, "DIRECT_QUERY_BUDGET"); deadline(); saveAppState()
        }
        private fun saveAppState() {
            appState?.let { state ->
                state.value.put("queries", directQueries).put("assertions", batchAssertions).put("preflightAbsent", preflightAbsent)
                    .put("creationAttempted", creationAttempted).put("calendar", calendarId ?: JSONObject.NULL)
                for (index in 0 until state.cases.length()) {
                    val row = state.cases.getJSONObject(index); knownEventIds += row.getLong("id"); knownUids[row.getLong("id")] = row.getString("uid")
                }
                state.save()
            }
        }

        private fun runCase(seed: Seed, limit: Limit, variant: Variant) {
            val label = "${seed.label}:${limit.name}:${variant.name}"
            step("$label:create")
            guardCalendar()
            val uid = UUID.randomUUID().toString()
            requireCondition(eventsByUid(uid).isEmpty(), "NEW_EVENT_UID_COLLISION")
            val originalRule = rule(seed, limit, 8, 7)
            val requestedEndZone = if (!seed.allDay && endZoneMode == "distinct") "Pacific/Honolulu" else null
            val initial = eventValues(seed, uid, originalRule, 0, requestedEndZone)
            val created = applyBatch(arrayListOf(calendarAssertion(),
                ContentProviderOperation.newInsert(E.CONTENT_URI).withValues(initial).withYieldAllowed(false).build()))
            val master = idFrom(created.getOrNull(1)?.uri ?: throw Failure("MASTER_INSERT_ACK_MISSING"), "events")
            knownEventIds += master; knownUids[master] = uid
            requireCondition(created.size == 2 && created[0].count == 1, "MASTER_INSERT_ACK_SHAPE")
            val source = readEvent(master, uid)
            val result = JSONObject().put("seed", seed.label).put("limit", limit.name).put("variant", variant.name)
                .put("duration", seed.duration).put("originalRule", originalRule)
                .put("requestedEndZone", requestedEndZone ?: JSONObject.NULL)
                .put("storedEndZone", source[E.EVENT_END_TIMEZONE] ?: JSONObject.NULL)
            cases.put(result)
            requireCondition(source[E.EVENT_END_TIMEZONE] == requestedEndZone, "MASTER_END_ZONE_CHANGED_ON_INSERT")
            requireCondition(source[E.DTSTART] == seed.start(0).toEpochMilli().toString() && source[E.DTEND] == null &&
                source[E.DURATION] == seed.duration && source[E.RRULE] == originalRule && source[E.ALL_DAY] == if (seed.allDay) "1" else "0", "MASTER_CONTEXT")
            requireCondition(source[E.EVENT_TIMEZONE] == seed.zone.id && source[E.ORIGINAL_ID] == null &&
                source[E.ORIGINAL_SYNC_ID] == null && source[E.ORIGINAL_INSTANCE_TIME] == null && source[E.ORIGINAL_ALL_DAY] == null, "MASTER_SOURCE_LINK")
            val before = instances(seed, listOf(master))
            requireExact(before, seed.expected(), "WARM_INSTANCE_BOUNDS")
            result.put("before", summary(before, seed.expected()))
            val prefixRule = rule(seed, limit, 3, 2)
            val tailRule = rule(seed, limit, 5, 7)
            val tailUid = UUID.randomUUID().toString()
            step("$label:split-warm-cache")
            guardCalendar()
            requireCondition(readEvent(master, uid) == source, "SOURCE_CHANGED_BEFORE_SPLIT")
            requireCondition(eventsByUid(tailUid).isEmpty(), "TAIL_UID_COLLISION")
            val prefix = ContentValues().apply {
                put(E.RRULE, prefixRule)
                if (variant != Variant.RULE_ONLY_CONTROL) {
                    // Exact CalendarSeriesWriter.followingContext payload. In particular do not
                    // write EVENT_END_TIMEZONE, nor interpret/re-encode the original duration.
                    listOf(E.DTSTART, E.DTEND, E.DURATION, E.EVENT_TIMEZONE, E.ALL_DAY,
                        E.ORIGINAL_INSTANCE_TIME, E.ORIGINAL_ALL_DAY).forEach { key -> putNullable(key, source[key]) }
                }
            }
            val expectedPrefix = source.toMutableMap().apply { put(E.RRULE, prefixRule) }
            // Native EVENTS_ID updates forbid a selection. The preceding complete source
            // assertion pins this exact ID/calendar/UID in the same non-yielding transaction.
            val operations = arrayListOf(calendarAssertion(), eventAssertion(master, uid, source),
                ContentProviderOperation.newUpdate(eventUri(master))
                    .withValues(prefix).withExpectedCount(1).withYieldAllowed(false).build(),
                eventAssertion(master, uid, expectedPrefix),
                ContentProviderOperation.newInsert(E.CONTENT_URI)
                    .withValues(eventValues(seed, tailUid, tailRule, 3, source[E.EVENT_END_TIMEZONE]))
                    .withYieldAllowed(false).build())
            if (variant == Variant.ROLLBACK) {
                // Only this final operation expects zero rows. Its known failure proves that the
                // preceding insert was reached, without exposing exception text in any report.
                operations += ContentProviderOperation.newAssertQuery(E.CONTENT_URI)
                    .withSelection("${E.CALENDAR_ID}=? AND ${E.UID_2445}=?", arrayOf(calendar().toString(), tailUid))
                    .withExpectedCount(0).withYieldAllowed(false).build()
                var finalAssertionFailed = false
                try { applyBatch(operations) }
                catch (failure: OperationApplicationException) {
                    finalAssertionFailed = failure.message?.contains("Expected 0 rows but actual 1") == true
                }
                requireCondition(finalAssertionFailed, "FINAL_ROLLBACK_ASSERTION_NOT_VERIFIED")
                requireCondition(readEvent(master, uid) == source, "ROLLBACK_MASTER_CONTEXT_CHANGED")
                requireCondition(eventsByUid(tailUid).isEmpty(), "ROLLBACK_TAIL_REMAINED")
                val after = instances(seed, listOf(master))
                requireExact(after, seed.expected(), "ROLLBACK_WARM_INSTANCE_BOUNDS")
                result.put("after", summary(after, seed.expected())).put("rollback", "VERIFIED_FINAL_ASSERTION")
            } else {
                val acknowledgments = applyBatch(operations)
                val tail = acknowledgedTail(acknowledgments, tailUid)
                requireCondition(readEvent(master, uid) == expectedPrefix, "PREFIX_CONTEXT_CHANGED")
                val tailSource = readEvent(tail, tailUid)
                requireCondition(tailSource[E.DTSTART] == seed.start(3).toEpochMilli().toString() && tailSource[E.RRULE] == tailRule &&
                    tailSource[E.DURATION] == source[E.DURATION] && tailSource[E.DTEND] == null &&
                    tailSource[E.EVENT_TIMEZONE] == source[E.EVENT_TIMEZONE] && tailSource[E.EVENT_END_TIMEZONE] == source[E.EVENT_END_TIMEZONE] &&
                    tailSource[E.ORIGINAL_ID] == null && tailSource[E.ORIGINAL_SYNC_ID] == null &&
                    tailSource[E.ORIGINAL_INSTANCE_TIME] == null && tailSource[E.ORIGINAL_ALL_DAY] == null, "TAIL_CONTEXT_CHANGED")
                val after = instances(seed, listOf(master, tail))
                result.put("after", summary(after, seed.expected()))
                    .put("prefixCount", after.count { it.eventId == master }).put("tailCount", after.count { it.eventId == tail })
                if (variant == Variant.EXACT_CONTEXT) {
                    requireExact(after, seed.expected(), "SPLIT_WARM_INSTANCE_BOUNDS")
                    requireCondition(after.count { it.eventId == master } == 3 && after.count { it.eventId == tail } == 5, "SPLIT_PREFIX_TAIL_COUNTS")
                    result.put("outcome", "PASS")
                } else result.put("outcome", if (after.size == 8 && after.map { it.logical() }.toSet() == seed.expected().toSet())
                    "CONTROL_BUG_NOT_REPRODUCED" else "CONTROL_DIFFERENCE_OBSERVED")
            }
            step("$label:done")
        }

        private fun eventValues(seed: Seed, uid: String, rule: String, index: Int, endZone: String?) = ContentValues().apply {
            put(E.CALENDAR_ID, calendar()); put(E.UID_2445, uid); put(E.ORGANIZER, owner)
            put(E.TITLE, "Daylight synthetic native fixture")
            put(E.DTSTART, seed.start(index).toEpochMilli()); putNull(E.DTEND); put(E.DURATION, seed.duration)
            put(E.RRULE, rule); put(E.ALL_DAY, if (seed.allDay) 1 else 0); put(E.EVENT_TIMEZONE, seed.zone.id)
            putNullable(E.EVENT_END_TIMEZONE, endZone)
        }
        private fun rule(seed: Seed, limit: Limit, count: Int, lastIndex: Int): String =
            if (limit == Limit.COUNT) "FREQ=DAILY;COUNT=$count" else "FREQ=DAILY;UNTIL=${seed.until(lastIndex)}"

        private fun acknowledgedTail(results: Array<ContentProviderResult>, uid: String): Long {
            val id = idFrom(results.getOrNull(4)?.uri ?: throw Failure("TAIL_INSERT_ACK_MISSING"), "events")
            knownEventIds += id; knownUids[id] = uid
            requireCondition(results.size == 5 && listOf(0, 1, 2, 3).all { results[it].count == 1 }, "SPLIT_ACK_SHAPE")
            return id
        }
        private fun instances(seed: Seed, ids: List<Long>): List<Occurrence> {
            requireCondition(ids.isNotEmpty() && ids.all { it in knownEventIds }, "UNOWNED_INSTANCE_QUERY")
            val uri = I.CONTENT_URI.buildUpon().appendPath(seed.windowStart.toString()).appendPath(seed.windowEnd.toString()).build()
            val rows = query(uri, arrayOf(I.EVENT_ID, I.BEGIN, I.END, I.ALL_DAY),
                "${I.CALENDAR_ID}=? AND ${I.EVENT_ID} IN (${ids.joinToString(",") { "?" }})",
                (listOf(calendar().toString()) + ids.map(Long::toString)).toTypedArray())
            return rows.map { row ->
                val id = row[I.EVENT_ID]?.toLongOrNull() ?: throw Failure("INSTANCE_ID_MISSING")
                val begin = row[I.BEGIN]?.toLongOrNull() ?: throw Failure("INSTANCE_BEGIN_MISSING")
                val end = row[I.END]?.toLongOrNull() ?: throw Failure("INSTANCE_END_MISSING")
                requireCondition(id in ids && end > begin && row[I.ALL_DAY] in setOf("0", "1"), "INSTANCE_SHAPE")
                Occurrence(id, begin, end, row[I.ALL_DAY] == "1")
            }.sortedWith(compareBy(Occurrence::begin, Occurrence::end, Occurrence::eventId))
        }
        private fun summary(rows: List<Occurrence>, expected: List<Triple<Long, Long, Boolean>>): JSONObject {
            val actual = rows.map { it.logical() }; val unique = actual.toSet()
            return JSONObject().put("count", rows.size).put("duplicates", rows.size - unique.size)
                .put("missingExpected", (expected.toSet() - unique).size).put("unexpectedBounds", (unique - expected.toSet()).size)
                .put("bounds", JSONArray(rows.map(Occurrence::json)))
        }
        private fun requireExact(rows: List<Occurrence>, expected: List<Triple<Long, Long, Boolean>>, code: String) {
            requireCondition(rows.size == 8 && rows.map { it.logical() }.toSet() == expected.toSet(), code)
        }

        private fun guardCalendar() {
            permissions(); verifyPins(identity())
            val found = query(C.CONTENT_URI, CALENDAR_FIELDS, calendarSelection(), calendarArgs())
            requireCondition(found.size == 1, "CALENDAR_IDENTITY_CHANGED")
        }
        private fun calendarAssertion(): ContentProviderOperation = ContentProviderOperation.newAssertQuery(C.CONTENT_URI)
            .withSelection(calendarSelection(), calendarArgs()).withExpectedCount(1).withYieldAllowed(false).build()
        private fun calendarSelection() = "${C._ID}=? AND ${C.ACCOUNT_NAME}=? AND ${C.ACCOUNT_TYPE}=? AND ${C.NAME}=? AND ${C.OWNER_ACCOUNT}=?"
        private fun calendarArgs() = arrayOf(calendar().toString(), requireNotNull(account), CalendarContract.ACCOUNT_TYPE_LOCAL,
            requireNotNull(account), requireNotNull(owner))
        private fun eventSelection() = "${E._ID}=? AND ${E.CALENDAR_ID}=? AND ${E.UID_2445}=?"
        private fun eventArgs(id: Long, uid: String) = arrayOf(id.toString(), calendar().toString(), uid)
        private fun readEvent(id: Long, uid: String): Map<String, String?> {
            requireCondition(id in knownEventIds && knownUids[id] == uid, "UNOWNED_EVENT_QUERY")
            val rows = query(E.CONTENT_URI, EVENT_FIELDS, eventSelection(), eventArgs(id, uid))
            requireCondition(rows.size == 1, "EVENT_IDENTITY_CHANGED")
            return rows.single()
        }
        private fun eventAssertion(id: Long, uid: String, expected: Map<String, String?>): ContentProviderOperation {
            val values = ContentValues().apply { expected.forEach { (key, value) -> putNullable(key, value) } }
            return ContentProviderOperation.newAssertQuery(E.CONTENT_URI).withSelection(eventSelection(), eventArgs(id, uid))
                .withValues(values).withExpectedCount(1).withYieldAllowed(false).build()
        }
        private fun eventsByUid(uid: String) = query(E.CONTENT_URI, arrayOf(E._ID), "${E.CALENDAR_ID}=? AND ${E.UID_2445}=?",
            arrayOf(calendar().toString(), uid))
        private fun namespace() = query(C.CONTENT_URI, CALENDAR_FIELDS,
            "${C.ACCOUNT_NAME}=? AND ${C.ACCOUNT_TYPE}=? AND ${C.NAME}=?",
            arrayOf(requireNotNull(account), CalendarContract.ACCOUNT_TYPE_LOCAL, requireNotNull(account)))
        private fun calendar() = calendarId ?: throw Failure("CALENDAR_ID_UNKNOWN")
        private fun localCalendarUri(): Uri = C.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(C.ACCOUNT_NAME, requireNotNull(account))
            .appendQueryParameter(C.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        private fun eventUri(id: Long) = E.CONTENT_URI.buildUpon().appendPath(id.toString()).build()

        fun cleanup(report: JSONObject): Boolean {
            val result = JSONObject(); report.put("cleanup", result)
            if (appPhase != null && (appPhase == "RECOVER_READ_ONLY" || appPhase == "CREATE_STAGE" && appCompleted)) {
                result.put("status", "RETAINED_FOR_EXPLICIT_NEXT_PHASE").put("ownershipVerified", appState != null)
                return true
            }
            if (appPhase != null && appState == null) {
                result.put("status", "BLOCKED_NO_VALID_RUN_MANIFEST")
                return false
            }
            inCleanup = true
            if (!creationAttempted) {
                result.put("status", "NO_CREATION_ATTEMPTED")
                    .put("directQueryCount", directQueries).put("batchAssertionCount", batchAssertions)
                return true
            }
            return try {
                step("cleanup-owned-local-calendar")
                appState?.assertPersisted(context)
                requireCondition(preflightAbsent && account != null && owner != null, "CLEANUP_NAMESPACE_NOT_PROVEN")
                permissions(); verifyPins(identity())
                val matches = namespace()
                requireCondition(matches.size <= 1, "CLEANUP_NAMESPACE_AMBIGUOUS")
                if (calendarId == null && matches.size == 1) {
                    // The sole reconciliation is for cleanup after a lost calendar-create ACK.
                    // It never retries creation or continues the experiment using a guessed ID.
                    calendarId = matches.single()[C._ID]?.toLongOrNull()?.takeIf { it > 0 }
                        ?: throw Failure("CLEANUP_ID_INVALID")
                    result.put("calendarIdRecoveredForCleanupOnly", true)
                }
                if (calendarId != null && matches.isEmpty()) {
                    val byId = query(C.CONTENT_URI, arrayOf(C._ID), "${C._ID}=?", arrayOf(calendar().toString()))
                    requireCondition(byId.isEmpty(), "CLEANUP_KNOWN_ID_CHANGED")
                }
                if (matches.isNotEmpty()) {
                    requireCondition(matches.single()[C._ID] == calendar().toString(), "CLEANUP_KNOWN_ID_CHANGED")
                    guardCalendar()
                    var acknowledged = false
                    try {
                        val deleted = applyBatch(arrayListOf(calendarAssertion(),
                            ContentProviderOperation.newDelete(localCalendarUri()).withSelection(calendarSelection(), calendarArgs())
                                .withExpectedCount(1).withYieldAllowed(false).build()))
                        acknowledged = deleted.size == 2 && deleted[1].count == 1
                    } catch (_: Exception) { /* Read back exact ownership; never retry deletion. */ }
                    result.put("deleteAcknowledged", acknowledged)
                }
                requireCondition(namespace().isEmpty(), "CLEANUP_CALENDAR_REMAINED")
                if (calendarId != null && knownEventIds.isNotEmpty()) {
                    val leftover = query(E.CONTENT_URI, arrayOf(E._ID),
                        "${E.CALENDAR_ID}=? AND ${E._ID} IN (${knownEventIds.joinToString(",") { "?" }})",
                        (listOf(calendar().toString()) + knownEventIds.map(Long::toString)).toTypedArray())
                    requireCondition(leftover.isEmpty(), "CLEANUP_TRACKED_EVENTS_REMAINED")
                }
                result.put("status", "VERIFIED_CALENDAR_AND_TRACKED_IDS_ABSENT").put("trackedEventCount", knownEventIds.size)
                    .put("directQueryCount", directQueries).put("batchAssertionCount", batchAssertions)
                appState?.let { state -> state.phase = "CLEANED"; saveAppState(); state.deleteAfterVerifiedCleanup(context); result.put("localFiles", "VERIFIED_EXACT_RUN_FILES_ABSENT") }
                true
            } catch (failure: Exception) {
                result.put("status", "BLOCKED_NO_BLIND_DELETE").put("failure", safeFailure(failure))
                    .put("directQueryCount", directQueries).put("batchAssertionCount", batchAssertions)
                false
            }
        }

        private fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            if (!inCleanup) deadline()
            val assertions = operations.count { it.isAssertQuery }
            requireCondition(batchAssertions + assertions <= 128, "BATCH_ASSERTION_BUDGET")
            batchAssertions += assertions
            return resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        }

        private fun query(uri: Uri, projection: Array<String>, selection: String, args: Array<String>): List<Map<String, String?>> {
            requireCondition(++directQueries <= 320 && (inCleanup || directQueries <= 300), "DIRECT_QUERY_BUDGET")
            if (!inCleanup) deadline()
            val cancellation = CancellationSignal()
            val timeout = watchdog.schedule({ cancellation.cancel() }, 30, TimeUnit.SECONDS)
            return try {
                resolver.query(uri, projection, selection, args, null, cancellation)?.use { cursor ->
                    val indexes = projection.map(cursor::getColumnIndexOrThrow)
                    val rows = ArrayList<Map<String, String?>>()
                    while (cursor.moveToNext()) {
                        requireCondition(rows.size < 32, "ROW_BUDGET")
                        rows += projection.indices.associate { i -> projection[i] to if (cursor.isNull(indexes[i])) null else cursor.getString(indexes[i]) }
                    }
                    rows
                } ?: throw Failure("QUERY_NULL_CURSOR")
            } finally { timeout.cancel(false) }
        }
        private fun permissions() {
            val read = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            val write = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            if (appPhase == "RECOVER_READ_ONLY") requireCondition(read && !write, "READ_ONLY_CALENDAR_PERMISSIONS_REQUIRED")
            else requireCondition(read && write, "READ_WRITE_PERMISSION_REQUIRED")
        }
        @Suppress("DEPRECATION")
        private fun identity(): JSONObject {
            val manager = context.packageManager
            val provider = manager.resolveContentProvider(CalendarContract.AUTHORITY, 0) ?: throw Failure("PROVIDER_NOT_VISIBLE")
            val pkg = manager.getPackageInfo(provider.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val app = pkg.applicationInfo ?: throw Failure("PROVIDER_APPLICATION_INFO_MISSING")
            val signing = pkg.signingInfo ?: throw Failure("PROVIDER_SIGNING_INFO_MISSING")
            val splits = (app.splitSourceDirs ?: emptyArray()).map { File(it).name to fileHash(it) }.sortedBy { it.first }
            requireCondition(splits.map { it.first }.distinct().size == splits.size, "SPLIT_NAMES_AMBIGUOUS")
            return JSONObject().put("package", provider.packageName).put("versionCode", pkg.longVersionCode)
                .put("baseSha256", fileHash(app.sourceDir))
                .put("splits", JSONArray(splits.map { JSONObject().put("name", it.first).put("sha256", it.second) }))
                .put("signers", JSONArray(signing.apkContentsSigners.map { hash(it.toByteArray()) }.sorted()))
                .put("signingHistory", JSONArray((signing.signingCertificateHistory ?: emptyArray()).map { hash(it.toByteArray()) }))
                .put("buildFingerprint", Build.FINGERPRINT).put("sdk", Build.VERSION.SDK_INT).put("securityPatch", Build.VERSION.SECURITY_PATCH)
        }
        private fun verifyPins(actual: JSONObject) {
            requireCondition(actual.getString("package") == argument("expectedProviderPackage") &&
                actual.getLong("versionCode").toString() == argument("expectedProviderVersion") &&
                actual.getString("baseSha256") == argument("expectedBaseSha256") &&
                actual.getString("buildFingerprint") == argument("expectedBuildFingerprint"), "PROVIDER_BINARY_OR_BUILD_PIN_MISMATCH")
            requireCondition(actual.getJSONArray("splits").toString() == JSONArray(argument("expectedSplitsJson")).toString() &&
                actual.getJSONArray("signers").toString() == JSONArray(argument("expectedSignersJson")).toString() &&
                actual.getJSONArray("signingHistory").toString() == JSONArray(argument("expectedSigningHistoryJson")).toString(), "PROVIDER_SPLIT_OR_SIGNER_PIN_MISMATCH")
        }
        private fun argument(key: String): String = arguments.getString(key)?.takeIf { it.isNotBlank() && it.length <= 16_384 }
            ?: throw Failure("REQUIRED_ARGUMENT_MISSING")
        private fun step(next: String) { phase = next; if (!inCleanup) deadline(); progress(next) }
        private fun deadline() = requireCondition(SystemClock.elapsedRealtime() - began <= 15 * 60_000L, "SUITE_DEADLINE")
        fun safeFailure(failure: Exception): JSONObject = JSONObject().put("phase", phase)
            .put("code", (failure as? Failure)?.code ?: "PLATFORM_FAILURE")
            .put("class", failure.javaClass.simpleName.take(80))
        fun close() { watchdog.shutdownNow() }

        companion object {
            private val CALENDAR_FIELDS = arrayOf(C._ID, C.ACCOUNT_NAME, C.ACCOUNT_TYPE, C.NAME, C.OWNER_ACCOUNT)
            private val EVENT_FIELDS = arrayOf(E._ID, E.CALENDAR_ID, E.UID_2445, E.DTSTART, E.DTEND, E.DURATION,
                E.RRULE, E.ALL_DAY, E.EVENT_TIMEZONE, E.EVENT_END_TIMEZONE, E.ORIGINAL_ID,
                E.ORIGINAL_SYNC_ID, E.ORIGINAL_INSTANCE_TIME, E.ORIGINAL_ALL_DAY)
            private fun requireCondition(valid: Boolean, code: String) { if (!valid) throw Failure(code) }
            private fun ContentValues.putNullable(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }
            private fun calendarAckFacts(uri: Uri, requested: Uri): JSONObject {
                val knownKeys = setOf(CalendarContract.CALLER_IS_SYNCADAPTER, C.ACCOUNT_NAME, C.ACCOUNT_TYPE)
                val facts = JSONObject().put("matchesRequestedQuery", uri.encodedQuery == requested.encodedQuery)
                    .put("queryAbsent", uri.encodedQuery == null)
                try {
                    val keys = uri.queryParameterNames
                    facts.put("queryKeys", JSONArray(keys.map { if (it in knownKeys) it else "<unexpected-key>" }.sorted()))
                        .put("unexpectedQueryKeyCount", keys.count { it !in knownKeys })
                } catch (_: RuntimeException) { facts.put("queryKeysReadable", false) }
                return facts
            }
            private fun idFrom(uri: Uri, table: String, requestedCalendarEncodedQuery: String? = null): Long {
                // Keep query-less ACKs valid. Calendar-only ACKs may additionally echo exactly the
                // known requested encoded query; changed/extra/reordered query data is rejected.
                // Every event insertion still requires no query, with no compatibility exemption.
                val queryAllowed = uri.encodedQuery == null || table == "calendars" && requestedCalendarEncodedQuery != null &&
                    uri.encodedQuery == requestedCalendarEncodedQuery
                requireCondition(uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY && queryAllowed &&
                    uri.fragment == null && uri.encodedPath == "/$table/${uri.lastPathSegment}", "INSERT_ACK_URI")
                val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: throw Failure("INSERT_ACK_ID")
                requireCondition(uri.lastPathSegment == id.toString(), "INSERT_ACK_ID_NONCANONICAL")
                return id
            }
            private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
            private fun fileHash(path: String): String {
                val digest = MessageDigest.getInstance("SHA-256")
                File(path).inputStream().use { stream ->
                    val buffer = ByteArray(16_384)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                return digest.digest().hex()
            }
            private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }
    }
}
