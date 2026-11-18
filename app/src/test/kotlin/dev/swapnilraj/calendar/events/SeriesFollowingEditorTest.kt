package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun SeriesEditorFixture.followingEditSource(rule: String = "FREQ=WEEKLY;BYDAY=MO;COUNT=8") = temporalSource(rule).let {
    it.copy(scopes = it.scopes + (RecurrenceScope.FOLLOWING to it.scopes.getValue(RecurrenceScope.ALL)))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesFollowingEditorTest {
    private val f = SeriesEditorFixture()
    @After fun stop() { f.scope.cancel() }
    private suspend fun editor(source: EditableSeries = f.followingEditSource(), permission: (suspend () -> Boolean)? = { true }): SeriesEventEditorController {
        val launch = f.prepare(RecurrenceScope.FOLLOWING, source = source)
        return SeriesEventEditorController(launch, source, f.port, f.store, f.scope, requestWritePermission = permission)
    }

    @Test fun onlyBackendAllowedFollowingGraphsExposeControls() {
        val source = f.followingEditSource()
        val context = SeriesMasterContext.from(source)
        assertNotNull("Source rule", context.parsed)
        assertNotNull("Nominal tail", context.nominalTail(source.selectedDetails.occurrence.ref))
        assertNotNull("Nominal duration", context.nominalTail(source.selectedDetails.occurrence.ref)?.template)
        assertTrue("snapshot=${source.snapshot}, rights=${source.scopes}, allowed=${source.allowedFields}", canPrepareSeriesScope(source, RecurrenceScope.FOLLOWING, SeriesEditorAction.EDIT))
        for (denied in listOf(source.copy(scopes = emptyMap()), source.copy(scopes = mapOf(RecurrenceScope.FOLLOWING to f.rights.copy(edit = AccessDecision.UNKNOWN))),
            source.copy(snapshot = source.snapshot.copy(exceptions = f.source.snapshot.exceptions)),
            source.copy(snapshot = source.snapshot.copy(rDatesRaw = "20280626T090000Z")),
            source.copy(selectedDetails = source.selectedDetails.copy(calendar = null)))) {
            assertFalse(canPrepareSeriesScope(denied, RecurrenceScope.FOLLOWING, SeriesEditorAction.EDIT))
        }
    }

    @Test fun nominalTailUsesOriginalIdentityInMasterZoneNeverActualDayOrMasterDate() = runBlocking {
        val zone = ZoneId.of("America/Los_Angeles")
        val original = Instant.parse("2028-06-19T16:00:00Z")
        val actual = Instant.parse("2028-06-20T13:00:00Z")
        val base = f.followingEditSource()
        val ref = base.selectedDetails.occurrence.ref.copy(instanceStart = actual, instanceEnd = actual.plusSeconds(3600), recurrence = RecurrenceRef(41, original))
        val source = base.copy(snapshot = base.snapshot.copy(start = RecurrenceStart.Timed(LocalDateTime.parse("2028-06-05T09:00"), zone), validatedSelection = ref),
            selectedDetails = base.selectedDetails.copy(occurrence = base.selectedDetails.occurrence.copy(ref = ref, time = EventTime.Timed(actual, actual.plusSeconds(3600))), eventZone = ZoneId.of("Asia/Tokyo")))
        val editor = editor(source)
        assertEquals("2028-06-19", editor.state.fields.temporal!!.startDate)
        assertEquals("09:00", editor.state.fields.temporal!!.startTime)
        assertEquals(zone.id, editor.state.fields.temporal!!.zone)
        assertEquals("-07:00", editor.state.fields.temporal!!.offset)
        assertEquals("Master title", editor.state.fields.title)
        assertEquals(source.snapshot.start, editor.masterStart)
        assertTrue(editor.temporalPresented); assertTrue(editor.rulePresented); assertFalse(editor.hasChanges)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun fieldsOnlyEditRequiresExplicitFollowingReviewAndKeepsRemainingCount() = runBlocking {
        val editor = editor(); editor.change { it.copy(title = "Tail title") }; editor.save()
        val review = editor.state.followingReview!!
        assertEquals(2, review.earlierCount); assertEquals(RecurrenceEnd.Count(6), review.recurrence.end)
        assertEquals(RecurrenceKey.Timed(f.original), review.boundary)
        assertEquals(f.original, (review.before.time as EventTime.Timed).start)
        assertEquals(FieldChange.Keep, review.command.intent.newTailStart)
        assertEquals(RecurrenceRuleChanges(), review.command.intent.rule)
        assertTrue(f.calls.isEmpty())
        editor.confirmFollowingReview()
        val call = f.calls.single()
        assertTrue(call.command is SeriesCommand.FollowingEdit)
        assertEquals(FieldChange.Set("Tail title"), (call.command as SeriesCommand.FollowingEdit).intent.fields.title)
        assertEquals(editor.launch.selected, call.selected)
    }

    @Test fun changedPatternKeepsCountAndExactUntilUntilExplicitlyChanged() = runBlocking {
        for (rule in listOf("FREQ=WEEKLY;BYDAY=MO;COUNT=8", "FREQ=WEEKLY;BYDAY=MO;UNTIL=20280801T160045Z")) {
            f.store.records.clear()
            val editor = editor(f.followingEditSource(rule))
            editor.changeRule(editor.state.fields.recurrence!!.values.copy(frequency = "DAILY")); editor.save()
            val review = editor.state.followingReview!!
            assertEquals(FieldChange.Keep, review.command.intent.rule.end)
            assertEquals(FieldChange.Set(RecurrencePattern.Daily), review.command.intent.rule.pattern)
            if (rule.contains("UNTIL")) assertEquals(RecurrenceEnd.ExactUntil(Instant.parse("2028-08-01T16:00:45Z")), review.recurrence.end)
            else assertEquals(RecurrenceEnd.Count(6), review.recurrence.end)
            editor.dismissFollowingReview(); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun explicitTailEndAndTemporalChangeRemainIndependentTypedIntent() = runBlocking {
        val editor = editor()
        editor.change { it.copy(temporal = it.temporal!!.copy(startDate = "2028-06-20", durationMode = "ELAPSED", hours = "2")) }
        editor.changeRule(editor.state.fields.recurrence!!.values.copy(frequency = "DAILY", endMode = "COUNT", count = "3"))
        editor.save(); val review = editor.state.followingReview!!
        assertEquals(RecurrenceEnd.Count(3), review.recurrence.end)
        assertEquals(FieldChange.Set(RecurrenceEnd.Count(3)), review.command.intent.rule.end)
        assertEquals(LocalDate.of(2028, 6, 20), (review.command.intent.newTailStart as FieldChange.Set).value.date)
        assertEquals(RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2)), review.command.intent.duration)
        assertEquals(Duration.ofHours(2), (review.after.time as EventTime.Timed).duration)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun explicitTailCountEqualToOriginalWholeCountMustNotBecomeKeep() = runBlocking {
        val editor = editor()
        editor.changeRule(editor.state.fields.recurrence!!.values.copy(endMode = "COUNT", count = "8"))
        editor.save()
        assertEquals(FieldChange.Set(RecurrenceEnd.Count(8)), editor.state.followingReview!!.command.intent.rule.end)
        assertEquals(RecurrenceEnd.Count(8), editor.state.followingReview!!.recurrence.end)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun allDayTailUsesUtcOriginalDatesIndependentOfEventDisplayZone() = runBlocking {
        val base = f.followingEditSource()
        val midnight = Instant.parse("2028-06-19T00:00:00Z")
        val ref = base.selectedDetails.occurrence.ref.copy(instanceStart = midnight, instanceEnd = midnight.plusSeconds(172800), recurrence = RecurrenceRef(41, midnight))
        val source = base.copy(snapshot = base.snapshot.copy(start = RecurrenceStart.AllDay(LocalDate.of(2028, 6, 5)), durationRaw = "P2D", validatedSelection = ref),
            selectedDetails = base.selectedDetails.copy(eventZone = ZoneId.of("Pacific/Honolulu"),
                occurrence = base.selectedDetails.occurrence.copy(ref = ref, time = EventTime.AllDay(CalendarDateRange(LocalDate.of(2028, 6, 19), LocalDate.of(2028, 6, 21))))))
        val editor = editor(source)
        assertEquals("2028-06-19", editor.state.fields.temporal!!.startDate)
        assertEquals("2", editor.state.fields.temporal!!.days)
        editor.change { it.copy(title = "All day tail") }; editor.save()
        assertEquals(RecurrenceKey.Date(LocalDate.of(2028, 6, 19)), editor.state.followingReview!!.boundary)
        assertEquals(ref, editor.launch.selected)
        assertEquals(RecurrenceEnd.Count(6), editor.state.followingReview!!.recurrence.end)
    }

    @Test fun rawPartialFollowingDraftRestoresThroughBrowserWithSameGeneration() = runBlocking {
        val source = f.followingEditSource(); val first = editor(source)
        first.change { it.copy(temporal = it.temporal!!.copy(startDate = "2028-")) }
        assertTrue(first.flushDraft())
        val record = f.store.records.getValue("series")
        assertEquals(3, JSONObject(record.payload).getInt("schema"))
        val launch = DraftBrowserCodec.read(record).seriesLaunch!!
        val restored = f.controller(launch, source)
        assertEquals(first.launch.generation, restored.launch.generation)
        assertEquals(first.operationId, restored.operationId)
        assertEquals("2028-", restored.state.fields.temporal!!.startDate)
        restored.save(); assertEquals(SeriesEditorProblem.INVALID_INPUT, restored.state.problem)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun followingFrozenSchemaRecoversWithoutSourceAndRetriesExactOriginal() = runBlocking {
        val editor = editor(); f.next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", null))
        editor.change { it.copy(title = "Frozen tail") }; editor.save(); editor.confirmFollowingReview()
        val call = f.calls.single(); val record = f.store.records.getValue("series")
        val raw = JSONObject(record.payload).getJSONObject("frozen")
        assertEquals(3, raw.getInt("schema")); assertEquals("following", raw.getString("kind"))
        assertEquals(call.command, FrozenSeriesSubmission.decode(raw).command)
        assertEquals(raw.toString(), FrozenSeriesSubmission.decode(raw).encode().toString())
        val restored = f.controller(SeriesDraftCodec.describe(record)!!, null)
        assertFalse(restored.canChange); assertNull(restored.discardAndReload())
        f.proveSafe = true; restored.recover(); assertEquals(1, f.calls.size)
        restored.retryOriginal(); assertEquals(listOf(call, call), f.calls)
        assertEquals(record, f.store.records.getValue("series"))
    }

    @Test fun legacySchemaCannotMasqueradeAsFollowingAndUnknownPayloadStaysUnreadable() = runBlocking {
        val editor = editor(); f.next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", null))
        editor.change { it.copy(title = "Tail") }; editor.save(); editor.confirmFollowingReview()
        val record = f.store.records.getValue("series")
        val bad = listOf(JSONObject(record.payload).put("schema", 2),
            JSONObject(record.payload).apply { getJSONObject("frozen").put("schema", 2) },
            JSONObject(record.payload).apply { getJSONObject("frozen").getJSONObject("intent").put("unknown", true) },
            JSONObject(record.payload).apply { getJSONObject("frozen").getJSONObject("intent").getJSONObject("boundary").put("instant", f.actual.toString()) })
        for (json in bad) assertNull(SeriesDraftCodec.describe(record.copy(payload = json.toString())))
        assertEquals(1, f.calls.size)
    }

    @Test fun absentPermissionLeavesReviewDraftUnsubmittedAndEditable() = runBlocking {
        val editor = editor(permission = null)
        editor.change { it.copy(title = "Pending permission") }; editor.save(); editor.confirmFollowingReview()
        assertEquals(SeriesEditorProblem.PERMISSION, editor.state.problem)
        assertFalse(JSONObject(f.store.records.getValue("series").payload).getBoolean("submitted"))
        assertTrue(editor.canChange); assertTrue(f.calls.isEmpty())
    }

    @Test fun changedNamespaceAndOldGenerationCannotAdoptFollowingDraft() = runBlocking {
        val source = f.followingEditSource(); val editor = editor(source)
        editor.change { it.copy(title = "Retained") }; editor.flushDraft()
        val replaced = source.copy(selectedDetails = source.selectedDetails.copy(calendar = source.selectedDetails.calendar!!.copy(providerId = "different-account")))
        val restored = f.controller(editor.launch, replaced)
        assertTrue(restored.state.reviewRequired); restored.save(); assertTrue(f.calls.isEmpty())
        val record = f.store.records.getValue("series")
        f.store.records.clear(); val replacement = f.prepare(RecurrenceScope.FOLLOWING, source = source)
        assertNotEquals(editor.launch.generation, replacement.generation)
        assertTrue(f.controller(editor.launch, source).state.draftReadFailed)
        assertEquals("Retained", SeriesDraftCodec.decode(record).fields.title)
    }

    @Test fun staleReviewCannotFreezeAfterOtherHostChangesRevision() = runBlocking {
        val source = f.followingEditSource(); val first = editor(source)
        first.change { it.copy(title = "First") }; first.save(); assertNotNull(first.state.followingReview)
        val newer = f.controller(first.launch, source)
        newer.change { it.copy(title = "Newer") }; newer.flushDraft()
        val record = f.store.records.getValue("series")
        first.confirmFollowingReview()
        assertTrue(f.calls.isEmpty()); assertEquals(record, f.store.records.getValue("series"))
    }

    @Test fun firstOccurrenceReviewMakesWholeScopeExplicitWithoutInventingTail() = runBlocking {
        val base = f.followingEditSource(); val start = (base.snapshot.start as RecurrenceStart.Timed).instant
        val ref = EventRef(CalendarId(2), 41, start, start.plusSeconds(3600), RecurrenceRef(41, start))
        val source = base.copy(snapshot = base.snapshot.copy(validatedSelection = ref), selectedDetails = base.selectedDetails.copy(
            occurrence = base.selectedDetails.occurrence.copy(ref = ref, time = EventTime.Timed(start, start.plusSeconds(3600)))))
        val editor = editor(source); editor.change { it.copy(title = "From first") }; editor.save()
        assertEquals(0, editor.state.followingReview!!.earlierCount)
        assertEquals(RecurrenceEnd.Count(8), editor.state.followingReview!!.recurrence.end)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun existingFrozenWholeSchemasKeepExactHistoricalBytes() {
        val ref = """{"calendar":2,"event":52,"start":"2028-06-20T13:00:00Z","end":"2028-06-20T14:00:00Z","master":41,"original":"2028-06-19T09:00:00Z"}"""
        val start = """{"kind":"timed","local":"2028-06-05T09:00","zone":"UTC","offset":"Z"}"""
        val prefix = "\"operationId\":\"legacy\",\"selected\":$ref,\"calendar\":2,\"master\":41,\"token\":\"graph-token\",\"scope\":\"ALL\",\"action\":\"EDIT\",\"kind\":\"whole\","
        val fields = FrozenSeriesSubmission("legacy", f.selected, f.identity, f.source.token, RecurrenceScope.ALL, SeriesEditorAction.EDIT,
            SeriesCommand.WholeEdit(RecurrenceEditIntent.WholeSeriesEdit(f.start, fields = EventPatch(title = FieldChange.Set("Changed")))))
        val oldFields = """{"schema":1,$prefix"patch":{"title":"Changed"},"masterStart":$start}"""
        assertEquals(oldFields, fields.encode().toString())
        assertEquals(oldFields, FrozenSeriesSubmission.decode(JSONObject(oldFields)).encode().toString())
        val temporal = fields.copy(command = SeriesCommand.WholeEdit(RecurrenceEditIntent.WholeSeriesEdit(f.start,
            duration = RecurrenceDurationChange.TimedElapsed(Duration.ofHours(2)))))
        val oldTemporal = """{"schema":2,$prefix"intent":{"expected":$start,"fields":{},"duration":{"kind":"elapsed","seconds":7200},"rule":{}}}"""
        assertEquals(oldTemporal, temporal.encode().toString())
        assertEquals(oldTemporal, FrozenSeriesSubmission.decode(JSONObject(oldTemporal)).encode().toString())
    }

    private fun tailResult(calendar: Long = 2, master: Long = 99, selected: EventRef? = EventRef(CalendarId(calendar), master,
        f.original, f.original.plusSeconds(3600), RecurrenceRef(master, f.original))) =
        SeriesMutationResult.LocalSaved(SeriesIdentity(CalendarId(calendar), master), selected)

    @Test fun verifiedNewTailIdentityCompletesAndDeletesOnlyItsExactDraft() = runBlocking {
        val editor = editor(); val saved = tailResult()
        f.next = saved
        editor.change { it.copy(title = "New tail") }; editor.save(); editor.confirmFollowingReview()
        assertEquals(saved, editor.state.result); assertEquals(saved, editor.state.localOutcome)
        assertFalse(editor.state.recoveryRequired); assertFalse(f.store.records.containsKey("series"))
        assertEquals(1, f.calls.size)
    }

    @Test fun verifiedNewTailWithoutSelectedOrdinalCanStillComplete() = runBlocking {
        val editor = editor(); val saved = tailResult(selected = null)
        f.next = saved
        editor.change { it.copy(title = "Tail with exhausted selected ordinal") }; editor.save(); editor.confirmFollowingReview()
        assertEquals(saved, editor.state.result); assertFalse(f.store.records.containsKey("series"))
    }

    @Test fun foreignOldZeroOrContradictoryTailResultsRemainUncertainAndKeepFrozenDraft() = runBlocking {
        val baseRef = tailResult().selected!!
        val invalid = listOf(tailResult(calendar = 3), tailResult(master = 41), tailResult(master = 0, selected = null),
            tailResult(selected = baseRef.copy(calendarId = CalendarId(3))),
            tailResult(selected = baseRef.copy(recurrence = RecurrenceRef(41, f.original))),
            tailResult(selected = baseRef.copy(recurrence = null)), tailResult(selected = baseRef.copy(eventId = 41)))
        for (result in invalid) {
            f.store.records.clear(); f.calls.clear()
            val editor = editor(); f.next = result
            editor.change { it.copy(title = "Held tail") }; editor.save(); editor.confirmFollowingReview()
            assertTrue(editor.state.recoveryRequired); assertNull(editor.state.localOutcome)
            assertTrue(editor.state.result is SeriesMutationResult.Common)
            assertEquals(SeriesEditorProblem.UNCERTAIN, editor.state.problem)
            assertTrue(SeriesDraftCodec.decode(f.store.records.getValue("series")).submitted)
            assertEquals(1, f.calls.size)
        }
    }

    @Test fun firstOrdinalAcceptsOnlyOriginalMasterAndConsistentSelectedReference() = runBlocking {
        val base = f.followingEditSource(); val instant = (base.snapshot.start as RecurrenceStart.Timed).instant
        val ref = EventRef(CalendarId(2), 41, instant, instant.plusSeconds(3600), RecurrenceRef(41, instant))
        val source = base.copy(snapshot = base.snapshot.copy(validatedSelection = ref), selectedDetails = base.selectedDetails.copy(
            occurrence = base.selectedDetails.occurrence.copy(ref = ref, time = EventTime.Timed(ref.instanceStart, ref.instanceEnd))))
        val invalid = editor(source); f.next = tailResult()
        invalid.change { it.copy(title = "First changes") }; invalid.save(); invalid.confirmFollowingReview()
        assertTrue(invalid.state.recoveryRequired); assertNull(invalid.state.localOutcome)
        f.store.records.clear(); f.calls.clear()
        val valid = editor(source); val saved = SeriesMutationResult.LocalSaved(source.snapshot.identity, ref); f.next = saved
        valid.change { it.copy(title = "First changes") }; valid.save(); valid.confirmFollowingReview()
        assertEquals(saved, valid.state.result); assertTrue(f.store.records.isEmpty())
    }

    @Test fun sourceFreeRecoveryAcceptsVerifiedReceiptBoundTailAndCleansDraft() = runBlocking {
        val source = f.followingEditSource(); val editor = editor(source)
        f.next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", null))
        editor.change { it.copy(title = "Interrupted tail") }; editor.save(); editor.confirmFollowingReview()
        val restored = f.controller(SeriesDraftCodec.describe(f.store.records.getValue("series"))!!, null)
        val saved = tailResult(); f.recovered = saved
        restored.recover()
        assertEquals(saved, restored.state.result); assertEquals(saved, restored.state.localOutcome)
        assertTrue(f.store.records.isEmpty()); assertEquals(1, f.calls.size)
        assertEquals(listOf(editor.operationId), f.recoveryIds)
    }

    @Test fun sourceFreeRecoveryRejectsContradictoryTailWithoutCleanup() = runBlocking {
        val editor = editor(); f.next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", null))
        editor.change { it.copy(title = "Interrupted tail") }; editor.save(); editor.confirmFollowingReview()
        val record = f.store.records.getValue("series")
        val restored = f.controller(SeriesDraftCodec.describe(record)!!, null)
        f.recovered = tailResult(calendar = 3); restored.recover()
        assertTrue(restored.state.recoveryRequired); assertNull(restored.state.localOutcome)
        assertEquals(record, f.store.records.getValue("series")); assertEquals(1, f.calls.size)
    }
}
