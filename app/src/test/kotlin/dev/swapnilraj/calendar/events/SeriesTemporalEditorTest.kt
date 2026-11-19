package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun SeriesEditorFixture.temporalSource(rule: String = "FREQ=WEEKLY;BYDAY=MO;COUNT=8"): EditableSeries {
    val ref = EventRef(CalendarId(2), 41, original, original.plusSeconds(3600), RecurrenceRef(41, original))
    val allowed = seriesUiFields + setOf(EditableField.TIME, EditableField.ZONE)
    val capable = rights.copy(fields = allowed, recurrence = AccessDecision.ALLOWED)
    return source.copy(selectedDetails = source.selectedDetails.copy(calendar = eventTestCalendar().copy(providerId = "source-namespace"),
        occurrence = EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Selected occurrence", 1)),
        snapshot = source.snapshot.copy(ruleRaw = rule, exceptions = emptyList(), validatedSelection = ref), allowedFields = allowed,
        scopes = source.scopes + (RecurrenceScope.ALL to capable))
}

internal fun legacySeriesRecord(record: DraftRecord): DraftRecord {
    val raw = JSONObject(record.payload).put("schema", 1)
    listOf("generation", "revision", "context").forEach(raw::remove)
    raw.getJSONObject("rights").remove("recurrence")
    listOf("baseline", "fields").forEach { key -> raw.getJSONObject(key).apply { remove("temporal"); remove("recurrence") } }
    return record.copy(payload = raw.toString())
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesTemporalEditorTest {
    private val f = SeriesEditorFixture()
    private val source = f.temporalSource()
    @After fun stop() { f.scope.cancel() }
    private suspend fun editor(value: EditableSeries = source, permission: (suspend () -> Boolean)? = { true }): SeriesEventEditorController {
        val launch = f.prepare(source = value)
        return SeriesEventEditorController(launch, value, f.port, f.store, f.scope, requestWritePermission = permission)
    }
    private fun move(editor: SeriesEventEditorController, date: String = "2028-06-12") { editor.change { it.copy(temporal = it.temporal!!.copy(startDate = date)) } }

    @Test fun masterDatesAndDurationNeverComeFromSelectedDay() = runBlocking<Unit> {
        val editor = editor()
        assertEquals("2028-06-05", editor.state.fields.temporal!!.startDate)
        assertEquals("09:00", editor.state.fields.temporal!!.startTime)
        assertEquals("1", editor.state.fields.temporal!!.hours)
        assertEquals("2028-06-19", source.selectedDetails.occurrence.time.let { (it as EventTime.Timed).start.atZone(ZoneOffset.UTC).toLocalDate().toString() })
        assertFalse(editor.hasChanges); assertTrue(f.calls.isEmpty())
        assertEquals(2, JSONObject(f.store.records.getValue("series").payload).getInt("schema")); assertNotNull(editor.launch.generation)
    }
    @Test fun temporalSaveRequiresReviewAndFreezesExactWholeCommandOnce() = runBlocking<Unit> {
        val editor = editor(); move(editor); editor.save()
        assertNotNull(editor.state.wholeReview); assertTrue(f.calls.isEmpty())
        val gate = CompletableDeferred<Unit>(); f.hold = gate
        editor.confirmWholeReview(); editor.confirmWholeReview()
        val call = f.calls.single(); val command = call.command as SeriesCommand.WholeEdit
        assertEquals(f.start, command.intent.expectedMasterStart)
        assertEquals(LocalDate.parse("2028-06-12"), (command.intent.newMasterStart as FieldChange.Set).value.date)
        assertEquals(RecurrenceDurationChange.KeepRaw, command.intent.duration)
        assertEquals(RecurrenceRuleChanges(), command.intent.rule)
        assertEquals(FieldChange.Keep, command.intent.fields.time); assertEquals(FieldChange.Keep, command.intent.fields.eventZone)
        val frozen = JSONObject(f.store.records.getValue("series").payload).getJSONObject("frozen")
        assertEquals(2, frozen.getInt("schema")); assertEquals(command, FrozenSeriesSubmission.decode(frozen).command)
        assertEquals(0, f.store.legacyCalls); gate.complete(Unit)
    }
    @Test fun dirtyPatternPreservesCountAndImportedExactUntilIndependently() = runBlocking<Unit> {
        for (rule in listOf("FREQ=WEEKLY;BYDAY=MO;COUNT=8", "FREQ=WEEKLY;BYDAY=MO;UNTIL=20280801T160045Z")) {
            f.store.records.clear(); f.calls.clear()
            val editor = editor(f.temporalSource(rule)); val raw = editor.state.fields.recurrence!!.values
            editor.changeRule(raw.copy(frequency = "DAILY")); editor.save()
            val intent = editor.state.wholeReview!!.command.intent
            assertEquals(FieldChange.Set(RecurrencePattern.Daily), intent.rule.pattern)
            assertEquals(FieldChange.Keep, intent.rule.end); assertEquals(FieldChange.Keep, intent.rule.interval)
            if (rule.contains("UNTIL")) assertEquals(RecurrenceEnd.ExactUntil(Instant.parse("2028-08-01T16:00:45Z")), editor.state.wholeReview!!.recurrence.end)
            else assertEquals(RecurrenceEnd.Count(8), editor.state.wholeReview!!.recurrence.end)
            assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun unchangedAndRevertedRulePartsKeepLiteralRuleAndRawDuration() = runBlocking<Unit> {
        val editor = editor(); val original = editor.state.fields.recurrence!!.values
        editor.changeRule(original.copy(interval = "2")); editor.changeRule(original)
        editor.change { it.copy(temporal = it.temporal!!.copy(durationMode = "ELAPSED")) }
        assertFalse(editor.hasChanges)
        move(editor); editor.save()
        val intent = editor.state.wholeReview!!.command.intent
        assertEquals(RecurrenceRuleChanges(), intent.rule); assertEquals(RecurrenceDurationChange.KeepRaw, intent.duration)
        assertEquals("PT1H", editor.state.wholeReview!!.after.durationRaw)
    }
    @Test fun partialTemporalAndIndependentRawRuleFieldsSurviveProcessAndBrowser() = runBlocking<Unit> {
        val editor = editor(); editor.change { it.copy(temporal = it.temporal!!.copy(startDate = "2028-", hours = "")) }
        editor.changeRule(editor.state.fields.recurrence!!.values.copy(interval = "", endMode = "DATE", throughDate = "2028-"))
        assertTrue(editor.flushDraft()); val record = f.store.records.getValue("series")
        val launch = DraftBrowserCodec.read(record).seriesLaunch!!
        val restored = f.controller(launch, source)
        assertEquals("2028-", restored.state.fields.temporal!!.startDate)
        assertEquals("", restored.state.fields.recurrence!!.values.interval)
        assertTrue(restored.state.fields.recurrence!!.intervalDirty); assertTrue(restored.state.fields.recurrence!!.endDirty)
        restored.save(); assertEquals(SeriesEditorProblem.INVALID_INPUT, restored.state.problem); assertTrue(f.calls.isEmpty())
    }
    @Test fun unknownRightsClampInjectedTimeZoneAndRecurrenceParts() = runBlocking<Unit> {
        val denied = source.copy(scopes = source.scopes + (RecurrenceScope.ALL to f.rights))
        val editor = editor(denied); val initial = editor.state.fields
        editor.change { it.copy(temporal = it.temporal!!.copy(startDate = "2030-01-01", zone = "Asia/Tokyo"), recurrence = it.recurrence!!.copy(values = it.recurrence.values.copy(frequency = "DAILY"), patternDirty = true)) }
        editor.changeRule(initial.recurrence!!.values.copy(interval = "3"))
        assertEquals(initial.temporal, editor.state.fields.temporal); assertEquals(initial.recurrence, editor.state.fields.recurrence)
        assertFalse(editor.canChangeTime); assertFalse(editor.canChangeZone); assertFalse(editor.canChangeRule); assertFalse(editor.hasChanges)
    }
    @Test fun timeWithoutZoneRightCannotAuthorAZoneChange() = runBlocking<Unit> {
        val value = source.copy(scopes = source.scopes + (RecurrenceScope.ALL to source.scopes.getValue(RecurrenceScope.ALL).copy(fields = source.allowedFields - EditableField.ZONE)))
        val editor = editor(value); editor.change { it.copy(temporal = it.temporal!!.copy(zone = "Asia/Tokyo")) }
        assertEquals("UTC", editor.state.fields.temporal!!.zone); assertFalse(editor.hasChanges)
    }
    @Test fun nominalOrUnknownMasterDurationNeverEnablesTemporalOrRuleControls() = runBlocking<Unit> {
        val value = source.copy(snapshot = source.snapshot.copy(durationRaw = "P1D"))
        val editor = editor(value); assertFalse(editor.canChangeTime); assertFalse(editor.canChangeRule)
        editor.change { it.copy(title = "Metadata remains editable") }; editor.save()
        val command = f.calls.single().command as SeriesCommand.WholeEdit
        assertEquals(RecurrenceDurationChange.KeepRaw, command.intent.duration); assertEquals(RecurrenceRuleChanges(), command.intent.rule)
    }
    @Test fun namespaceAndContextDriftNeverAdoptsNewTokenOrOverwritesSavedRawFields() = runBlocking<Unit> {
        val editor = editor(); editor.change { it.copy(title = "Retained draft") }; editor.flushDraft(); val old = f.store.records.getValue("series")
        val replacement = source.copy(selectedDetails = source.selectedDetails.copy(calendar = source.selectedDetails.calendar!!.copy(account = CalendarAccount("replacement@example.test", "Other"))))
        val restored = f.controller(editor.launch, replacement)
        assertTrue(restored.state.reviewRequired); assertEquals("Retained draft", restored.state.fields.title)
        restored.save(); assertFalse(restored.flushDraft()); assertEquals(old, f.store.records.getValue("series")); assertTrue(f.calls.isEmpty())
    }
    @Test fun unsubmittedLegacyDraftRequiresExplicitReviewWithoutSchemaRewrite() = runBlocking<Unit> {
        val editor = editor(); editor.change { it.copy(title = "Legacy retained") }; editor.flushDraft()
        val old = legacySeriesRecord(f.store.records.getValue("series")); f.store.records[old.id] = old
        val launch = SeriesDraftCodec.describe(old)!!; assertNull(launch.generation)
        val restored = f.controller(launch, source); assertTrue(restored.state.reviewRequired)
        restored.save(); assertFalse(restored.flushDraft()); assertEquals(old, f.store.records[old.id]); assertTrue(f.calls.isEmpty())
    }
    @Test fun submittedLegacyCommandRemainsExactRecoveryOnlyAndIsNeverUpgraded() = runBlocking<Unit> {
        val editor = editor(); f.next = SeriesMutationResult.Common(MutationResult.Uncertain("original", null))
        editor.change { it.copy(title = "Original authorized metadata") }; editor.save()
        val command = f.calls.single(); val old = legacySeriesRecord(f.store.records.getValue("series")); f.store.records[old.id] = old
        val launch = SeriesDraftCodec.describe(old)!!; val restored = f.controller(launch, null)
        assertTrue(restored.state.recoveryRequired); f.proveSafe = true; restored.recover(); restored.retryOriginal()
        assertEquals(listOf(command, command), f.calls); assertEquals(old, f.store.records[old.id]); assertNull(restored.discardAndReload())
    }
    @Test fun preflightDenialLeavesUnsubmittedRawAndLaterExplicitSaveCanProceed() = runBlocking<Unit> {
        var granted = false; val editor = editor(permission = { granted }); editor.change { it.copy(title = "Permission retained") }; editor.save()
        assertTrue(f.calls.isEmpty()); assertFalse(JSONObject(f.store.records.getValue("series").payload).has("frozen"))
        assertEquals(SeriesEditorProblem.PERMISSION, editor.state.problem); assertTrue(editor.canChange)
        granted = true; editor.save(); assertEquals(1, f.calls.size)
    }
    @Test fun missingPreflightNeverImplicitlyAuthorizesTemporalSave() = runBlocking<Unit> {
        val editor = editor(permission = null); move(editor); editor.save(); editor.confirmWholeReview()
        assertTrue(f.calls.isEmpty()); assertEquals(SeriesEditorProblem.PERMISSION, editor.state.problem)
        assertFalse(JSONObject(f.store.records.getValue("series").payload).has("frozen"))
    }
    @Test fun anyChangeInvalidatesWholeReviewBeforeFreeze() = runBlocking<Unit> {
        val editor = editor(); move(editor); editor.save(); assertNotNull(editor.state.wholeReview)
        editor.change { it.copy(description = "Changed after review") }; editor.confirmWholeReview()
        assertNull(editor.state.wholeReview); assertTrue(f.calls.isEmpty())
    }
    @Test fun frozenPermissionRaceStaysLockedWithoutJournalProofOrFreshOperation() = runBlocking<Unit> {
        val editor = editor(); move(editor); f.next = SeriesMutationResult.Common(MutationResult.PermissionRequired)
        editor.save(); editor.confirmWholeReview(); val command = f.calls.single(); val record = f.store.records.getValue("series")
        editor.change { it.copy(title = "Unsafe replacement") }; editor.save(); assertNull(editor.discardAndReload())
        editor.recover(); editor.retryOriginal(); assertEquals(listOf(command), f.calls); assertEquals(record, f.store.records.getValue("series"))
        assertTrue(editor.state.recoveryRequired); assertFalse(editor.state.retryReady)
    }
    @Test fun masterStartDoesNotSilentlyReanchorItsExistingWeekdayPattern() = runBlocking<Unit> {
        val editor = editor(); move(editor, "2028-06-13"); editor.save()
        assertNull(editor.state.wholeReview); assertEquals(SeriesEditorProblem.NEEDS_REVIEW, editor.state.problem)
        assertEquals("WEEKLY", editor.state.fields.recurrence!!.values.frequency)
        assertEquals(listOf("MONDAY"), editor.state.fields.recurrence!!.values.weekdays); assertTrue(f.calls.isEmpty())
    }
    @Test fun gapAndFoldInputsRequireExplicitSourceZoneOffsetWithoutGuessing() = runBlocking<Unit> {
        val editor = editor()
        editor.change { it.copy(temporal = it.temporal!!.copy(startDate = "2028-10-29", startTime = "01:30", zone = "Europe/London")) }
        editor.changeRule(editor.state.fields.recurrence!!.values.copy(frequency = "DAILY")); editor.save()
        assertEquals(SeriesEditorProblem.INVALID_INPUT, editor.state.problem); assertNull(editor.state.wholeReview)
        editor.change { it.copy(temporal = it.temporal!!.copy(offset = "+01:00")) }; editor.save()
        assertEquals(Instant.parse("2028-10-29T00:30:00Z"), (editor.state.wholeReview!!.after.time as EventTime.Timed).start)
        editor.dismissWholeReview()
        editor.change { it.copy(temporal = it.temporal!!.copy(startDate = "2028-03-26")) }; editor.save()
        assertEquals(SeriesEditorProblem.INVALID_INPUT, editor.state.problem); assertTrue(f.calls.isEmpty())
    }
    @Test fun allDayMasterDurationUsesUtcDatesAndExclusiveEndNotProviderDisplayZone() = runBlocking<Unit> {
        val selected = source.selectedDetails.occurrence.ref.copy(instanceStart = Instant.parse("2028-06-19T00:00:00Z"),
            instanceEnd = Instant.parse("2028-06-21T00:00:00Z"), recurrence = RecurrenceRef(41, Instant.parse("2028-06-19T00:00:00Z")))
        val value = source.copy(selectedDetails = source.selectedDetails.copy(eventZone = ZoneId.of("Asia/Tokyo"),
            occurrence = EventOccurrence(selected, EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-19"), LocalDate.parse("2028-06-21"))), "Selected day", 1)),
            snapshot = source.snapshot.copy(start = RecurrenceStart.AllDay(LocalDate.parse("2028-06-05")), durationRaw = "P2D", validatedSelection = selected))
        val editor = editor(value); assertEquals("2028-06-05", editor.state.fields.temporal!!.startDate); assertEquals("2", editor.state.fields.temporal!!.days)
        editor.change { it.copy(temporal = it.temporal!!.copy(durationMode = "DAYS", days = "3")) }; editor.save()
        val review = editor.state.wholeReview!!; assertEquals(ZoneOffset.UTC, review.after.eventZone)
        assertEquals(EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-05"), LocalDate.parse("2028-06-08"))), review.after.time)
        assertEquals(RecurrenceDurationChange.AllDayDays(3), review.command.intent.duration); assertTrue(f.calls.isEmpty())
    }
    @Test fun monthlyOrdinalYearlyAndExplicitEndChangesRoundTripWithoutResettingOtherParts() = runBlocking<Unit> {
        val values = listOf(RecurrenceEditorFields(frequency = "MONTHLY", monthDay = "5", endMode = "KEEP"),
            RecurrenceEditorFields(frequency = "MONTHLY", monthlyMode = "WEEKDAY", ordinal = "1", weekday = "MONDAY", endMode = "KEEP"),
            RecurrenceEditorFields(frequency = "YEARLY", yearMonth = "6", yearDay = "5", endMode = "KEEP"))
        for (raw in values) {
            f.store.records.clear(); val editor = editor(); editor.changeRule(raw); editor.save()
            val command = editor.state.wholeReview!!.command
            assertEquals(FieldChange.Keep, command.intent.rule.end)
            val frozen = FrozenSeriesSubmission(editor.operationId!!, editor.launch.selected, f.identity, source.token, RecurrenceScope.ALL, SeriesEditorAction.EDIT, command)
            assertEquals(frozen, FrozenSeriesSubmission.decode(frozen.encode()))
        }
        f.store.records.clear(); val editor = editor(); editor.changeRule(editor.state.fields.recurrence!!.values.copy(endMode = "COUNT", count = "1")); editor.save()
        assertEquals(FieldChange.Set(RecurrenceEnd.Count(1)), editor.state.wholeReview!!.command.intent.rule.end)
        assertEquals(FieldChange.Keep, editor.state.wholeReview!!.command.intent.rule.pattern); assertTrue(f.calls.isEmpty())
    }

    @Test fun providerIncompatibleOffsetZonesBlockBeforeAnyFreeze() = runBlocking<Unit> {
        for (zone in listOf("+05:30", "GMT+05:30")) {
            f.store.records.clear(); val editor = editor()
            editor.change { it.copy(temporal = it.temporal!!.copy(zone = zone)) }; editor.save()
            assertNull(editor.state.wholeReview); assertEquals(SeriesEditorProblem.INVALID_INPUT, editor.state.problem)
            editor.confirmWholeReview(); assertTrue(f.calls.isEmpty())
            assertFalse(JSONObject(f.store.records.getValue("series").payload).has("frozen"))
        }
    }

}
