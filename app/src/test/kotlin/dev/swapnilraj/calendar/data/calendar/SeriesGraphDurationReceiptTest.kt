package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesGraphDurationReceiptTest {
    private val seed = Instant.parse("2026-11-17T09:00:00Z")
    private val actor = "fixture@example.invalid"
    private val defaultBudgets = ProviderReadBudgets()
    private val family = SeriesGraphDurationProtocol.FAMILY
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun rejected(label: String = "Expected strict receipt refusal", block: () -> Unit) { try { block(); fail(label) } catch (_: Exception) { } }
    private fun overBudget(block: () -> Unit) { try { block(); fail("Expected bounded refusal") } catch (_: ProviderReadLimitExceeded) { } }
    private data class Fixture(val source: CalendarSeriesSource, val command: SeriesGraphDurationCommand) { val raw get() = source.canonical() }
    private fun fixture(allDay: Boolean = false, selectedMoved: Boolean = false): Fixture {
        val begin = if (allDay) Instant.parse("2026-11-17T00:00:00Z") else seed
        val span = if (allDay) 86_400L else 3_600L
        val originalMoved = begin.plusSeconds(86_400)
        val actualMoved = begin.plusSeconds(3 * 86_400)
        val originalCancelled = begin.plusSeconds(2 * 86_400)
        val calendar = mapOf("_id" to "2", "account_name" to actor, "account_type" to "fixture.type", "ownerAccount" to actor,
            "calendar_access_level" to "700", "_sync_id" to "namespace-a", "opaque_calendar" to "keep")
        val master = mapOf("_id" to "10", "calendar_id" to "2", "_sync_id" to "master-sync", "uid2445" to "synthetic-uid",
            "dtstart" to begin.toEpochMilli().toString(), "dtend" to null, "duration" to if (allDay) "P1D" else "PT1H",
            "allDay" to if (allDay) "1" else "0", "eventTimezone" to "UTC", "eventEndTimezone" to null,
            "rrule" to "FREQ=DAILY;COUNT=6", "rdate" to null, "exrule" to null,
            "exdate" to if (allDay) "20261121,20261121" else "20261121T090000Z,20261121T090000Z",
            "original_id" to null, "original_sync_id" to null, "deleted" to "0", "organizer" to actor, "isOrganizer" to "1",
            "guestsCanSeeGuests" to "1", "hasAttendeeData" to "0", "eventStatus" to "1", "customAppPackage" to null,
            "opaque_provider" to "retain\u0001\\\"😀", "eventColor" to "123", "eventColor_index" to "opaque-key")
        fun exception(id: String, original: Instant, actual: Instant, cancelled: Boolean) = master + mapOf("_id" to id, "_sync_id" to "exception-$id",
            "uid2445" to "uid-$id", "rrule" to null, "exdate" to null, "dtstart" to actual.toEpochMilli().toString(),
            "dtend" to actual.plusSeconds(span).toEpochMilli().toString(), "duration" to null, "original_id" to "10", "original_sync_id" to "master-sync",
            "originalInstanceTime" to original.toEpochMilli().toString(), "originalAllDay" to if (allDay) "1" else "0", "eventStatus" to if (cancelled) "2" else "1")
        val exceptions = listOf(exception("21", originalMoved, actualMoved, false), exception("22", originalCancelled, originalCancelled, true))
        fun reference(id: Long, original: Instant, actual: Instant) = EventRef(CalendarId(2), id, actual, actual.plusSeconds(span), RecurrenceRef(10, original))
        val selected = if (selectedMoved) reference(21, originalMoved, actualMoved) else reference(10, begin, begin)
        val start = if (allDay) RecurrenceStart.AllDay(begin.atOffset(ZoneOffset.UTC).toLocalDate()) else RecurrenceStart.Timed(begin.atOffset(ZoneOffset.UTC).toLocalDateTime(), ZoneOffset.UTC)
        fun key(t: Instant): RecurrenceKey = if (allDay) RecurrenceKey.Date(t.atOffset(ZoneOffset.UTC).toLocalDate()) else RecurrenceKey.Timed(t)
        val snapshot = RecurrenceSeriesSnapshot(SeriesIdentity(CalendarId(2), 10), start, master["rrule"], master["duration"], exDatesRaw = master["exdate"],
            exDates = setOf(key(begin.plusSeconds(4 * 86_400))), exDatesDecoded = true, exceptions = listOf(
                SeriesException(reference(21, originalMoved, actualMoved), key(originalMoved)), SeriesException(reference(22, originalCancelled, originalCancelled), key(originalCancelled), true)),
            coverage = SeriesCoverage.COMPLETE, validatedSelection = selected)
        val empty = mapOf(10L to emptyList<Map<String, String?>>(), 21L to emptyList(), 22L to emptyList())
        val reminders = empty + (21L to listOf(mapOf("_id" to "31", "event_id" to "21", "method" to "2", "minutes" to "27", "opaque_child" to "retain"),
            mapOf("_id" to "32", "event_id" to "21", "method" to "3", "minutes" to "90")))
        val extensions = empty + (22L to listOf(mapOf("_id" to "41", "event_id" to "22", "name" to "opaque", "value" to "retained")))
        val time = if (allDay) EventTime.AllDay.fromProvider(selected.instanceStart, selected.instanceEnd) else EventTime.Timed(selected.instanceStart, selected.instanceEnd)
        val source = CalendarSeriesSource(calendar, master, exceptions, reminders, empty, EventDetails(EventOccurrence(selected, time, "Synthetic", 1), null),
            SeriesMasterFields("Synthetic"), snapshot, setOf(EditableField.TIME), extensions)
        val command = SeriesGraphDurationCommand(selected, EditToken(hash("SERIES_EDIT_V1" + source.canonical())),
            if (allDay) SeriesGraphDuration.AllDayDays(2) else SeriesGraphDuration.TimedSeconds(5400))
        return Fixture(source, command)
    }
    private fun prepare(f: Fixture = fixture(), budgets: ProviderReadBudgets = defaultBudgets) = SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, budgets = budgets)
    private fun operation(raw: String, command: SeriesGraphDurationCommand, phase: String = "PREPARED", kind: String = family) =
        CalendarLocalStore.Operation("graph-fixture", SeriesGraphDurationReceipt.fingerprint(command), kind, phase, raw)
    private fun parse(raw: String, f: Fixture, phase: String = "PREPARED", budgets: ProviderReadBudgets = defaultBudgets) =
        SeriesGraphDurationReceipt.parse(operation(raw, f.command, phase), budgets)
    private fun changedSource(f: Fixture, change: (JSONObject) -> Unit): Pair<String, SeriesGraphDurationCommand> {
        val source = JSONObject(f.raw).apply(change).toString()
        return source to f.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + source)))
    }

    @Test fun sourceV1BytesAndMovedCancelledGraphAreBoundToSparseDurationOnlyPostimage() {
        for (allDay in listOf(false, true)) for (selectedMoved in listOf(false, true)) {
            val f = fixture(allDay, selectedMoved)
            val raw = prepare(f).encode()
            val receipt = JSONObject(raw)
            assertEquals(f.raw, receipt.getString("sourceRaw"))
            val plan = receipt.getJSONObject("plan")
            assertEquals(setOf("duration"), plan.getJSONObject("changes").keys().asSequence().toSet())
            val duration = if (allDay) "P2D" else "PT5400S"
            assertEquals(duration, plan.getJSONObject("changes").getString("duration"))
            val expected = JSONObject(f.raw).apply { getJSONObject("master").put("duration", duration) }.toString()
            assertEquals(hash(expected), plan.getString("postGraphHash"))
            assertEquals(listOf(10L, 21L, 22L), plan.getJSONArray("membership").let { a -> List(a.length()) { a.getLong(it) } })
            assertEquals(f.command, parse(raw, f).command)
            assertEquals(raw, parse(raw, f).encode())
            assertEquals(f.raw, f.source.canonical())
        }
    }

    @Test fun everyPhaseIsStrictAndUnknownAttemptNeverBecomesAcknowledgedCommit() {
        val f = fixture(); val prepared = prepare(f).encode(); val post = JSONObject(prepared).getJSONObject("plan").getString("postGraphHash")
        for (phase in SeriesGraphDurationReceipt.Phase.entries) {
            val ack = if (phase in setOf(SeriesGraphDurationReceipt.Phase.COMMITTED, SeriesGraphDurationReceipt.Phase.VERIFIED)) SeriesGraphDurationReceipt.Acknowledgement(1, post) else null
            val encoded = SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, phase, ack).encode()
            assertEquals(phase, parse(encoded, f, phase.name).phase)
        }
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, SeriesGraphDurationReceipt.Phase.COMMITTED) }
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, SeriesGraphDurationReceipt.Phase.ATTEMPTED, SeriesGraphDurationReceipt.Acknowledgement(1, post)) }
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, SeriesGraphDurationReceipt.Phase.COMMITTED, SeriesGraphDurationReceipt.Acknowledgement(0, post)) }
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, SeriesGraphDurationReceipt.Phase.COMMITTED, SeriesGraphDurationReceipt.Acknowledgement(1, "0".repeat(64))) }
        rejected { parse(prepared, f, "ATTEMPTED") }
        rejected { parse(prepared, f, "FAILED") }
    }

    @Test fun foreignLegacyFamiliesAndFrozenCommandsCannotAliasThisCodec() {
        val f = fixture(); val raw = prepare(f).encode()
        val legacy = CalendarSeriesWriter.frozenCommandJson(SeriesCommand.WholeEdit(f.command.intent(f.source.snapshot.start))).toString()
        assertFalse(SeriesGraphDurationReceipt.fingerprint(f.command).contains(legacy))
        for (kind in listOf("SERIES_V1_ALL_EDIT", "SERIES_V1_FOLLOWING_EDIT", "UPDATE", "MEETING_CREATE"))
            rejected { SeriesGraphDurationReceipt.parse(operation(raw, f.command, kind = kind)) }
        for (old in listOf("{\"version\":1,\"temporal\":true}", "{\"version\":4}", "{\"version\":6}", legacy)) rejected { parse(old, f) }
        val old = CalendarSeriesWriter.retirementFingerprint(f.command.selected, f.command.token, SeriesCommand.WholeEdit(f.command.intent(f.source.snapshot.start)))
        assertEquals("SERIES_V1_ALL_EDIT", old.first)
        assertFalse(old.second.startsWith(SeriesGraphDurationProtocol.FINGERPRINT_PREFIX))
        assertNotEquals(old.second, SeriesGraphDurationReceipt.fingerprint(f.command))
    }

    @Test fun unknownVersionsKeysDuplicatesAndCoercedTypesAreNeverMigrated() {
        val f = fixture(); val raw = prepare(f).encode()
        val mutations: List<(JSONObject) -> Unit> = listOf({ it.put("version", 2) }, { it.put("version", "1") }, { it.put("future", true) },
            { it.put("family", "SERIES_V1_ALL_EDIT") }, { it.getJSONObject("command").put("patch", JSONObject()) },
            { it.getJSONObject("command").getJSONObject("duration").put("value", "5400") },
            { it.getJSONObject("plan").getJSONObject("changes").put("dtstart", "0") })
        mutations.forEachIndexed { index, change ->
            val mutated = JSONObject(raw).apply(change).toString()
            assertNotEquals("Mutation $index must change the actual wire", raw, mutated)
            rejected("Mutation $index was accepted") { parse(mutated, f) }
        }
        val floating = raw.replaceFirst("\"value\":5400", "\"value\":5400.0")
        assertNotEquals("Floating lexical wire must differ from the original", raw, floating)
        rejected("Floating lexical wire was accepted") { parse(floating, f) }
        rejected { parse(raw.replaceFirst("\"version\":1", "\"version\":1,\"version\":1"), f) }
        rejected { parse(raw + " ", f) }
        val duplicateSource = f.raw.replaceFirst("\"version\":1", "\"version\":1,\"version\":1")
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + duplicateSource))), duplicateSource) }
    }

    @Test fun tamperedSourceSelectionNamespaceChildAndPlanNeverValidateAsOldReceipt() {
        val f = fixture(); val raw = prepare(f).encode()
        val mutations: List<(JSONObject) -> Unit> = listOf({ it.put("operationId", "other") }, { it.put("signature", "other") },
            { it.put("namespace", "0".repeat(64)) }, { it.put("integrity", "0".repeat(64)) },
            { it.getJSONObject("plan").put("postGraphHash", "0".repeat(64)) }, { it.getJSONObject("plan").put("membership", JSONArray(listOf(10, 21))) },
            { it.getJSONObject("command").getJSONObject("selected").put("original", seed.plusSeconds(86400).toString()) },
            { val s = JSONObject(it.getString("sourceRaw")); s.getJSONObject("master").put("opaque_provider", "changed"); it.put("sourceRaw", s.toString()) })
        mutations.forEach { change -> rejected { parse(JSONObject(raw).apply(change).toString(), f) } }
    }

    @Test fun rehashedInvalidSourceGraphsStillCannotProducePreparedProof() {
        val f = fixture()
        val mutations: List<(JSONObject) -> Unit> = listOf({ it.put("version", 2) }, { it.put("unknown", 1) },
            { it.getJSONObject("master").put("rdate", "20261120T090000Z") }, { it.getJSONObject("master").put("exrule", "FREQ=WEEKLY") },
            { it.getJSONObject("master").put("exdate", "undecodable") }, { it.getJSONObject("master").put("dtend", seed.plusSeconds(3600).toEpochMilli().toString()) },
            { it.getJSONArray("exceptions").put(it.getJSONArray("exceptions").getJSONObject(0)) },
            { it.getJSONArray("exceptions").getJSONObject(0).put("original_id", "99") },
            { it.getJSONArray("exceptions").getJSONObject(0).put("originalInstanceTime", seed.plusSeconds(20 * 86400).toEpochMilli().toString()) },
            { it.getJSONArray("exceptions").getJSONObject(0).put("deleted", "1") },
            { it.getJSONArray("attendees").getJSONObject(0).getJSONArray("rows").put(JSONObject().put("_id", "55").put("event_id", "10").put("attendeeEmail", "foreign@example.invalid")) },
            { it.getJSONArray("reminders").getJSONObject(1).getJSONArray("rows").getJSONObject(0).put("event_id", "99") },
            { it.getJSONArray("reminders").getJSONObject(1).getJSONArray("rows").getJSONObject(1).put("_id", "31") },
            { it.getJSONArray("extendedProperties").put(2, it.getJSONArray("extendedProperties").getJSONObject(0)) },
            { it.getJSONObject("master").put("dtstart", seed.toEpochMilli()) })
        mutations.forEach { change ->
            val (source, command) = changedSource(f, change)
            rejected { SeriesGraphDurationReceipt.create("graph-fixture", command, source) }
        }
    }

    @Test fun opaqueColumnsAndRawDateTokenChangesProduceDifferentCompleteProofs() {
        val f = fixture(); val raw = prepare(f).encode(); val first = JSONObject(raw)
        val source = f.source.copy(master = f.source.master + ("opaque_provider" to "new opaque value"))
        val command = f.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + source.canonical())))
        val other = JSONObject(SeriesGraphDurationReceipt.create("graph-fixture", command, source.canonical()).encode())
        assertNotEquals(first.getString("signature"), other.getString("signature"))
        assertNotEquals(first.getJSONObject("plan").getString("sourceHash"), other.getJSONObject("plan").getString("sourceHash"))
        assertNotEquals(first.getJSONObject("plan").getString("postGraphHash"), other.getJSONObject("plan").getString("postGraphHash"))
        assertEquals(source.canonical(), other.getString("sourceRaw"))
        val dates = f.source.copy(master = f.source.master + ("exdate" to "20261121T090000Z"))
        val dateCommand = f.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + dates.canonical())))
        val dateReceipt = JSONObject(SeriesGraphDurationReceipt.create("graph-fixture", dateCommand, dates.canonical()).encode())
        assertEquals("20261121T090000Z", JSONObject(dateReceipt.getString("sourceRaw")).getJSONObject("master").getString("exdate"))
        assertNotEquals(first.getJSONObject("plan").getString("retainedGraphHash"), dateReceipt.getJSONObject("plan").getString("retainedGraphHash"))
    }

    @Test fun completeSourceAndAdoptedWinnerRespectActualConfiguredBudgets() {
        val f = fixture(); val raw = prepare(f).encode()
        overBudget { prepare(f, defaultBudgets.copy(maxCanonicalBytes = f.raw.length - 1)) }
        overBudget { parse(raw, f, budgets = defaultBudgets.copy(maxCanonicalBytes = raw.length - 1)) }
        overBudget { parse(raw, f, budgets = defaultBudgets.copy(mutation = defaultBudgets.mutation.copy(maxRows = 1))) }
        overBudget { parse(raw, f, budgets = defaultBudgets.copy(mutation = defaultBudgets.mutation.copy(maxCells = 1))) }
        overBudget { parse(raw, f, budgets = defaultBudgets.copy(mutation = defaultBudgets.mutation.copy(maxTextUnits = 100))) }
        overBudget { parse(raw, f, budgets = defaultBudgets.copy(mutation = defaultBudgets.mutation.copy(maxCellUnits = 16))) }
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, planning = RecurrencePlanningBudget(maxExceptions = 1)) }
        rejected { prepare(f, defaultBudgets.copy(maxSeriesChildren = 1)) }
    }

    @Test fun preparedReservationIncludesWorstCommittedEnvelopeAndEscapedOpaqueSource() {
        val f = fixture(); val prepared = prepare(f).encode(); val j = JSONObject(prepared)
        val committed = SeriesGraphDurationReceipt.create("graph-fixture", f.command, f.raw, SeriesGraphDurationReceipt.Phase.COMMITTED,
            SeriesGraphDurationReceipt.Acknowledgement(1, j.getJSONObject("plan").getString("postGraphHash"))).encode()
        assertTrue(committed.toByteArray(Charsets.UTF_8).size > prepared.toByteArray(Charsets.UTF_8).size)
        val cap = prepared.toByteArray(Charsets.UTF_8).size
        overBudget { prepare(f, defaultBudgets.copy(maxCanonicalBytes = cap)) }
        overBudget { parse(prepared, f, budgets = defaultBudgets.copy(maxCanonicalBytes = cap)) }
    }

    @Test fun invalidNativeDurationOrKindNeverProducesReceipt() {
        val f = fixture()
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command.copy(duration = SeriesGraphDuration.AllDayDays(2)), f.raw) }
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", f.command.copy(duration = SeriesGraphDuration.TimedSeconds(Long.MAX_VALUE)), f.raw) }
        val allDay = fixture(allDay = true)
        rejected { SeriesGraphDurationReceipt.create("graph-fixture", allDay.command.copy(duration = SeriesGraphDuration.TimedSeconds(3600)), allDay.raw) }
    }

    @Test fun adversarialAllDayActualBoundsCannotBeTruncatedIntoValidExceptions() {
        val accepted = mutableListOf<String>()
        val f = fixture(allDay = true)
        for (index in 0..1) for (edges in listOf("both", "start", "end")) {
            val rows = f.source.exceptions.mapIndexed { i, row -> if (i != index) row else row.toMutableMap().apply {
                if (edges != "end") this["dtstart"] = (getValue("dtstart")!!.toLong() + 3_600_000L).toString()
                if (edges != "start") this["dtend"] = (getValue("dtend")!!.toLong() + 3_600_000L).toString()
            } }
            val source = f.source.copy(exceptions = rows).canonical()
            val command = f.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + source)))
            if (runCatching { SeriesGraphDurationReceipt.create("graph-fixture", command, source) }.isSuccess) accepted += "$index:$edges"
        }
        println("M6 all-day nonmidnight cases=6 accepted=${accepted.size}")
        assertEquals("Nonmidnight moved/cancelled actual bounds must fail closed", emptyList<String>(), accepted)
    }

    @Test fun adversarialNonselectedActualYearsCannotExceedNativeCalendarDomain() {
        val accepted = mutableListOf<String>()
        for (allDay in listOf(false, true)) for (index in 0..1) for (year in listOf("0000", "+10000")) {
            val f = fixture(allDay = allDay)
            val actual = Instant.parse("${year}-01-01T00:00:00Z")
            val span = if (allDay) 86_400L else 3_600L
            val rows = f.source.exceptions.mapIndexed { i, row -> if (i != index) row else row + mapOf(
                "dtstart" to actual.toEpochMilli().toString(), "dtend" to actual.plusSeconds(span).toEpochMilli().toString()) }
            val source = f.source.copy(exceptions = rows).canonical()
            val command = f.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + source)))
            if (runCatching { SeriesGraphDurationReceipt.create("graph-fixture", command, source) }.isSuccess) accepted += "$allDay:$index:$year"
        }
        // A large moved offset within the native year range remains valid retained evidence.
        val control = fixture()
        val actual = Instant.parse("9999-01-01T00:00:00Z")
        val source = control.source.copy(exceptions = control.source.exceptions.mapIndexed { index, row -> if (index != 0) row else row + mapOf(
            "dtstart" to actual.toEpochMilli().toString(), "dtend" to actual.plusSeconds(3600).toEpochMilli().toString()) }).canonical()
        SeriesGraphDurationReceipt.create("graph-fixture", control.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + source))), source)
        println("M6 exception year cases=8 accepted=${accepted.size} valid9999=true")
        assertEquals("Native bounds apply to every retained actual row", emptyList<String>(), accepted)
    }

    @Test fun adversarialMasterSelectionCannotSelfCertifyForgedActualBounds() {
        val accepted = mutableListOf<String>()
        for (allDay in listOf(false, true)) for (change in listOf("shift", "longEnd", "shortEnd")) {
            val f = fixture(allDay = allDay)
            val selected = when (change) {
                "shift" -> f.command.selected.copy(instanceStart = f.command.selected.instanceStart.plusSeconds(3600), instanceEnd = f.command.selected.instanceEnd.plusSeconds(3600))
                "longEnd" -> f.command.selected.copy(instanceEnd = f.command.selected.instanceEnd.plusSeconds(if (allDay) 86400 else 3600))
                else -> f.command.selected.copy(instanceEnd = f.command.selected.instanceEnd.minusSeconds(if (allDay) 3600 else 1800))
            }
            val source = f.source.copy(snapshot = f.source.snapshot.copy(validatedSelection = selected)).canonical()
            val command = f.command.copy(selected = selected, token = EditToken(hash("SERIES_EDIT_V1" + source)))
            if (runCatching { SeriesGraphDurationReceipt.create("graph-fixture", command, source) }.isSuccess) accepted += "$allDay:$change"
        }
        println("M6 forged selection cases=6 accepted=${accepted.size}")
        assertEquals("A source wrapper cannot issue its own fresh selection proof", emptyList<String>(), accepted)
    }

    @Test fun adversarialSameBudgetCreationMustBeAdoptableOrRefuseBeforeEncoding() {
        val f = fixture()
        val largestCell = (listOf(f.source.calendar, f.source.master) + f.source.exceptions + f.source.reminders.values.flatten() +
            f.source.attendees.values.flatten() + f.source.extendedProperties.values.flatten()).flatMap { it.values.filterNotNull() }.maxOf { it.length }
        assertTrue("Actual source cells individually fit the counterexample cap", largestCell < f.raw.length - 1)
        val limits = listOf("cell" to defaultBudgets.copy(mutation = defaultBudgets.mutation.copy(maxCellUnits = f.raw.length - 1)),
            "text" to defaultBudgets.copy(mutation = defaultBudgets.mutation.copy(maxTextUnits = f.raw.length.toLong() + 1)))
        val failedRoundtrips = mutableListOf<String>()
        for ((name, budgets) in limits) {
            val creation = runCatching { prepare(f, budgets).encode(budgets) }
            if (creation.isSuccess) {
                val adoption = runCatching { parse(creation.getOrThrow(), f, budgets = budgets) }
                if (adoption.isFailure) failedRoundtrips += name
                println("M6 envelope $name create=true adopt=${adoption.isSuccess} sourceUnits=${f.raw.length} largestCell=$largestCell")
            } else {
                assertTrue("Only an explicit budget refusal can replace a roundtrip", creation.exceptionOrNull() is ProviderReadLimitExceeded)
                println("M6 envelope $name create=false budgetRefusal=true sourceUnits=${f.raw.length} largestCell=$largestCell")
            }
        }
        assertEquals("Every admitted frame must fit its own adoption parser limits", emptyList<String>(), failedRoundtrips)
    }

    @Test fun adversarialAllocatedNamespacePinsProviderCalendarIdentityAsWellAsSourceHash() {
        val f = fixture(); val before = JSONObject(prepare(f).encode())
        val source = f.source.copy(calendar = f.source.calendar + ("_sync_id" to "namespace-b")).canonical()
        val after = JSONObject(SeriesGraphDurationReceipt.create("graph-fixture", f.command.copy(token = EditToken(hash("SERIES_EDIT_V1" + source))), source).encode())
        assertNotEquals(before.getString("signature"), after.getString("signature"))
        assertNotEquals(before.getJSONObject("plan").getString("sourceHash"), after.getJSONObject("plan").getString("sourceHash"))
        assertNotEquals("The new namespace itself must include providerId", before.getString("namespace"), after.getString("namespace"))
    }
}
