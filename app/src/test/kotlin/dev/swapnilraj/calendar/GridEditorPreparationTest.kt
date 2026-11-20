package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalendarPreferences
import dev.swapnilraj.calendar.events.EventEditorControllerTest.Store
import dev.swapnilraj.calendar.ui.editorSeed
import dev.swapnilraj.calendar.ui.events.EventEditorController
import dev.swapnilraj.calendar.ui.grid.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GridEditorPreparationTest {
    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 11, 17)
    private val now = date.atTime(8, 0).atZone(zone)
    private val clock = Clock.fixed(now.toInstant(), zone)
    private val calendar = CalendarInfo(CalendarId(3), "Work", 1, CalendarAccount("fixture@invalid", "test"), "owner", zone,
        700, true, true, CalendarCapabilities(AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED), "namespace-a")
    private fun occurrence(day: LocalDate = date, startMinute: Int = 540, duration: Int = 60): EventOccurrence {
        val start = day.atStartOfDay().plusMinutes(startMinute.toLong()).atZone(zone).toInstant()
        val end = start.plusSeconds(duration * 60L)
        return EventOccurrence(EventRef(calendar.id, 7, start, end), EventTime.Timed(start, end), "Meeting", 1)
    }
    private fun context(occurrence: EventOccurrence = occurrence(), selected: Boolean = true): GridEditorContext {
        val day = (occurrence.time as EventTime.Timed).start.atZone(zone).toLocalDate()
        val request = CalendarRequest(CalendarDateRange.day(day), zone)
        return GridEditorContext(emptyList(), request, CalendarSnapshot(request, listOf(calendar), listOf(occurrence)), listOf(calendar),
            CalendarPreferences(CalendarSelection.ProviderVisible, calendar.id.takeIf { selected }, "DAY", 64f,
                defaultCalendarIdentity = calendar.identity.takeIf { selected }), "DAY", day, false)
    }
    private fun source(event: EventOccurrence = occurrence()) = EditableEvent(EventDetails(event, calendar,
        location = "Room", description = "Notes", eventZone = zone, reminders = listOf(EventReminder(10, 1)), availability = 2, privacy = 2),
        EditToken("token-a"), EditableField.entries.toSet())
    private class Port(var source: EditableEvent) : CalendarMutations {
        var reads = 0; var writes = 0; var afterRead: suspend () -> Unit = {}
        var patch: EventPatch? = null
        override suspend fun loadForEdit(ref: EventRef): EditReadResult { reads++; afterRead(); return EditReadResult.Ready(source) }
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult { writes++; error("Unexpected create") }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult {
            writes++; this.patch = patch; return MutationResult.LocalSaved(ref)
        }
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult { writes++; error("Unexpected delete") }
        override suspend fun recover(operationId: String): MutationResult = error("Unexpected recovery")
    }
    private fun edit(context: GridEditorContext, kind: TimedGridEditKind = TimedGridEditKind.MOVE, minute: Double = 600.0): GridEditorIntent.Edit {
        val occurrence = context.snapshot!!.occurrences.single()
        val source = TimedGridSource(occurrence.ref, occurrence.time as EventTime.Timed)
        val segment = DayGeometry(context.date, zone).segments(source.ref).last().key
        val target = GridTarget(context.date, minute, zone)
        return GridEditorIntent.Edit(TimedGridEditProposal(context, kind, source, segment, target,
            planGridEdit(source, segment, kind, target)))
    }
    private fun create(context: GridEditorContext, minute: Double) = GridEditorIntent.Create(GridTarget(context.date, minute, zone).let {
        TimedGridCreateProposal(context, it, TimedGridPlanning.create(it, Duration.ofHours(1)))
    })
    @Test fun moveDraftKeepsSourceBaselineAndRawProposalRestoresWithoutConflictOrWrites() = runBlocking {
        val context = context(); val port = Port(source()); val store = Store()
        val prepared = prepareGridEditor(context, edit(context), port, store, now, { context }, clock) as GridEditorPreparation.Ready
        assertEquals(1, port.reads)
        val record = store.load(prepared.launch.draftId)!!
        val raw = JSONObject(record.payload)
        assertEquals("09:00", JSONObject(raw.getString("sourceBaseline")).getString("start").let { Instant.parse(it).atZone(zone).toLocalTime().toString() })
        assertEquals("10:00", raw.getString("startTime"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = EventEditorController(port.source.details.editorSeed(), port.source, prepared.launch.draftId, port, store, scope, clock, requireStoredDraft = true, expectedGeneration = prepared.launch.generation, requestWritePermission = { true })
            assertFalse(controller.state.reviewRequired)
            assertNull(controller.state.problem)
            assertEquals("10:00", controller.state.fields.startTime)
            assertEquals(0, port.writes)
            controller.save()
            assertEquals(1, port.writes)
            assertEquals(EventTime.Timed(Instant.parse("2026-11-17T10:00:00Z"), Instant.parse("2026-11-17T11:00:00Z")), (port.patch!!.time as FieldChange.Set).value)
            assertEquals(FieldChange.Keep, port.patch!!.title)
            assertEquals(FieldChange.Keep, port.patch!!.description)
            assertEquals(FieldChange.Keep, port.patch!!.eventZone)
            assertEquals(FieldChange.Keep, port.patch!!.alertMinutes)
        } finally { scope.cancel() }
    }
    @Test fun resizeChangesOnlyRawEndAndUsesUniqueDraftsWithoutReplacingRetainedInput() = runBlocking {
        val context = context(); val port = Port(source()); val store = Store()
        val first = prepareGridEditor(context, edit(context, TimedGridEditKind.RESIZE_END, 630.0), port, store, now, { context }, clock) as GridEditorPreparation.Ready
        val second = prepareGridEditor(context, edit(context), port, store, now, { context }, clock) as GridEditorPreparation.Ready
        assertNotEquals(first.launch.draftId, second.launch.draftId)
        val raw = JSONObject(store.load(first.launch.draftId)!!.payload)
        assertEquals("09:00", raw.getString("startTime")); assertEquals("10:30", raw.getString("endTime"))
        assertEquals(2, store.records.size); assertEquals(0, port.writes)
    }
    @Test fun createKeepsSelectedNamespaceAndAskEachCalendarRemainsUnselected() = runBlocking {
        for (selected in listOf(true, false)) {
            val context = context(selected = selected); val port = Port(source()); val store = Store()
            val prepared = prepareGridEditor(context, create(context, 645.0), port, store, now, { context }, clock) as GridEditorPreparation.Ready
            val raw = JSONObject(store.load(prepared.launch.draftId)!!.payload)
            assertEquals("10:45", raw.getString("startTime")); assertEquals("11:45", raw.getString("endTime"))
            assertEquals(selected, prepared.launch.calendarSelected)
            assertEquals(if (selected) "3" else "", raw.getString("calendarId"))
            assertEquals(0, port.reads); assertEquals(0, port.writes)
        }
    }
    @Test fun preflightAndDropBothReadAndSourceRefreshDuringAwaitCancelsNavigation() = runBlocking {
        val context = context(); val port = Port(source()); var current = context; val store = Store()
        assertTrue(gridEditPreflight(context, occurrence().ref, port) { current })
        port.afterRead = { current = context.copy(request = context.request.copy(selection = CalendarSelection.Explicit(emptySet()))) }
        assertEquals(GridEditorPreparation.Stale, prepareGridEditor(context, edit(context), port, store, now, { current }, clock))
        assertEquals(2, port.reads); assertTrue(store.records.isEmpty()); assertEquals(0, port.writes)
    }
    @Test fun reusedNamespaceOrMissingTimeCapabilityNeverPreparesAnEdit() = runBlocking {
        val context = context()
        val sources = listOf(source().copy(details = source().details.copy(calendar = calendar.copy(providerId = "namespace-b"))),
            source().copy(allowedFields = setOf(EditableField.TITLE)), source().copy(details = source().details.copy(occurrence = occurrence(duration = 30))))
        for (source in sources) {
            val port = Port(source); val store = Store()
            assertFalse(gridEditPreflight(context, occurrence().ref, port) { context })
            assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, edit(context), port, store, now, { context }, clock))
            assertTrue(store.records.isEmpty()); assertEquals(0, port.writes)
        }
    }
    @Test fun contextChangeDuringDurableWriteNeverExposesItsRoute() = runBlocking {
        val context = context(); var current = context; val port = Port(source()); val records = mutableMapOf<String, DraftRecord>()
        val store = object : AtomicDraftStore {
            override suspend fun load(id: String) = records[id]
            override suspend fun list() = records.values.toList()
            override suspend fun upsert(record: DraftRecord) = error("CAS only")
            override suspend fun delete(id: String) = error("CAS only")
            override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult = synchronized(records) {
                if (records[id] != expected) DraftChangeResult.Changed else {
                    if (replacement == null) records.remove(id) else records[id] = replacement
                    current = context.copy(blocked = true)
                    DraftChangeResult.Applied
                }
            }
        }
        assertEquals(GridEditorPreparation.Stale, prepareGridEditor(context, edit(context), port, store, now, { current }, clock))
        assertTrue(records.isEmpty()); assertEquals(0, port.writes)
    }
    @Test fun stalePreparationCleansOnlyItsExactAcknowledgedRecordAndKeepsAnotherOwnersAdoptedRaw() = runBlocking {
        val context = context(); val port = Port(source()); val records = mutableMapOf<String, DraftRecord>()
        var created: DraftRecord? = null; var acked = false; var adopted: DraftRecord? = null; var cleanups = 0
        val store = object : AtomicDraftStore {
            override suspend fun load(id: String): DraftRecord? = records[id].also { if (it != null && it == created) acked = true }
            override suspend fun list() = records.values.toList()
            override suspend fun upsert(record: DraftRecord) = error("CAS only")
            override suspend fun delete(id: String) = error("CAS only")
            override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult = synchronized(records) {
                if (replacement == null) { cleanups++; assertEquals(created, expected) }
                if (records[id] != expected) DraftChangeResult.Changed else {
                    if (replacement == null) records.remove(id) else { records[id] = replacement; created = replacement }
                    DraftChangeResult.Applied
                }
            }
        }
        val current = {
            if (acked) {
                if (adopted == null) {
                    val owned = requireNotNull(created)
                    val doc = dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument.decode(owned)
                    adopted = doc.copy(fields = doc.fields.copy(description = "Another owner's pending raw notes")).record(clock.instant().plusSeconds(1))
                    records[owned.id] = requireNotNull(adopted)
                }
                context.copy(blocked = true)
            } else context
        }
        assertEquals(GridEditorPreparation.Stale, prepareGridEditor(context, create(context, 645.0), port, store, now, current, clock))
        assertEquals(1, cleanups); assertEquals(adopted, records.values.single())
        assertEquals("Another owner's pending raw notes", JSONObject(records.values.single().payload).getString("description"))
        assertEquals(0, port.writes)
    }
    @Test fun unavailableStaleCleanupRetainsTheAcknowledgedUnsentRawAndNeverReturnsARoute() = runBlocking {
        val context = context(); val port = Port(source()); val records = mutableMapOf<String, DraftRecord>()
        var created: DraftRecord? = null; var acked = false; var cleanups = 0
        val store = object : AtomicDraftStore {
            override suspend fun load(id: String): DraftRecord? = records[id].also { if (it != null && it == created) acked = true }
            override suspend fun list() = records.values.toList()
            override suspend fun upsert(record: DraftRecord) = error("CAS only")
            override suspend fun delete(id: String) = error("CAS only")
            override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult = synchronized(records) {
                if (replacement == null) { cleanups++; assertEquals(created, expected); DraftChangeResult.Unavailable }
                else if (records[id] != expected) DraftChangeResult.Changed else { records[id] = replacement; created = replacement; DraftChangeResult.Applied }
            }
        }
        assertEquals(GridEditorPreparation.Stale, prepareGridEditor(context, create(context, 645.0), port, store, now,
            { if (acked) context.copy(blocked = true) else context }, clock))
        assertEquals(1, cleanups); assertEquals(created, records.values.single()); assertEquals(0, port.writes)
    }
    @Test fun overlapRequiresExplicitChoiceAndChosenOffsetSurvivesEditorRestore() = runBlocking {
        val day = LocalDate.of(2026, 10, 25); val event = occurrence(day, 180)
        val context = context(event); val port = Port(source(event)); val store = Store()
        val intent = create(context, 90.0)
        assertTrue(prepareGridEditor(context, intent, port, store, now, { context }, clock) is GridEditorPreparation.Review)
        assertTrue(store.records.isEmpty())
        val result = prepareGridEditor(context, intent, port, store, now, { context }, clock, offset = ZoneOffset.UTC) as GridEditorPreparation.Ready
        val fields = EventEditorController.readRawFields(JSONObject(store.load(result.launch.draftId)!!.payload))
        assertEquals("Z", fields.startOffset)
        val normalized = EventEditorController.normalizedDraft(fields)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), (normalized.time as EventTime.Timed).start)
        assertEquals(Duration.ofHours(1), (normalized.time as EventTime.Timed).duration)
        assertEquals(0, port.writes)
    }
    @Test fun gapAlternativeKeepsOriginalEditTimeRatherThanAdvancingTheTarget() = runBlocking {
        val day = LocalDate.of(2026, 3, 29); val event = occurrence(day, 180)
        val context = context(event); val port = Port(source(event)); val store = Store(); val intent = edit(context, minute = 75.0)
        assertTrue(prepareGridEditor(context, intent, port, store, now, { context }, clock) is GridEditorPreparation.Review)
        assertTrue(store.records.isEmpty())
        val result = prepareGridEditor(context, intent, port, store, now, { context }, clock, editorAlternative = true) as GridEditorPreparation.Ready
        val fields = EventEditorController.readRawFields(JSONObject(store.load(result.launch.draftId)!!.payload))
        assertEquals(event.time, EventEditorController.normalizedDraft(fields).time)
        assertEquals(0, port.writes)
    }
    @Test fun freshEditorSourceDriftRequiresReviewAndCannotSubmitThePreparedTime() = runBlocking {
        val context = context(); val port = Port(source()); val store = Store()
        val result = prepareGridEditor(context, edit(context), port, store, now, { context }, clock) as GridEditorPreparation.Ready
        port.source = port.source.copy(details = port.source.details.copy(location = "Changed room"), token = EditToken("token-b"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = EventEditorController(port.source.details.editorSeed(), port.source, result.launch.draftId, port, store, scope, clock, requireStoredDraft = true, expectedGeneration = result.launch.generation, requestWritePermission = { true })
            assertTrue(controller.state.reviewRequired)
            assertEquals("10:00", controller.state.fields.startTime)
            assertEquals("Room", controller.state.fields.location)
            controller.save()
            assertEquals(0, port.writes)
        } finally { scope.cancel() }
    }
    @Test fun rejectedAtomicCreationNeverFallsBackToOverwritingAStoreRow() = runBlocking {
        val context = context(); val port = Port(source()); var comparisons = 0
        val store = object : AtomicDraftStore {
            override suspend fun load(id: String): DraftRecord? = null
            override suspend fun list() = emptyList<DraftRecord>()
            override suspend fun upsert(record: DraftRecord) = error("Must not overwrite")
            override suspend fun delete(id: String) = error("Must not delete")
            override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
                comparisons++; assertNull(expected); assertNotNull(replacement); return DraftChangeResult.Changed
            }
        }
        assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, edit(context), port, store, now, { context }, clock))
        assertEquals(1, comparisons); assertEquals(0, port.writes)
    }
    @Test fun unknownSourceZoneRejectsGesturesButUnchangedEditorAlternativeKeepsTimeAndZone() = runBlocking {
        val context = context(); val unknown = source().copy(details = source().details.copy(eventZone = null))
        val port = Port(unknown); val store = Store()
        assertFalse(gridEditPreflight(context, occurrence().ref, port) { context })
        assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, edit(context), port, store, now, { context }, clock))
        assertTrue(store.records.isEmpty()); assertEquals(0, port.writes)
        val result = prepareGridEditor(context, edit(context), port, store, now, { context }, clock, editorAlternative = true) as GridEditorPreparation.Ready
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = EventEditorController(unknown.details.editorSeed(), unknown, result.launch.draftId, port, store, scope, clock, requireStoredDraft = true, expectedGeneration = result.launch.generation, requestWritePermission = { true })
            controller.change { it.copy(title = "Edited title") }
            controller.save()
            assertEquals(1, port.writes)
            assertEquals(FieldChange.Keep, port.patch!!.time)
            assertEquals(FieldChange.Keep, port.patch!!.eventZone)
            assertEquals(FieldChange.Set("Edited title"), port.patch!!.title)
        } finally { scope.cancel() }
    }
    @Test fun positiveSubminuteDurationNeverClaimsZeroElapsedMinutes() {
        assertEquals(GridElapsedAmount("0.001", true), gridElapsedAmount(Duration.ofMillis(1), java.util.Locale.US))
        assertEquals(GridElapsedAmount("0.000000001", true), gridElapsedAmount(Duration.ofNanos(1), java.util.Locale.US))
        assertEquals(GridElapsedAmount("60", false), gridElapsedAmount(Duration.ofHours(1), java.util.Locale.US))
    }
    private fun multiContext(event: EventOccurrence = occurrence(), window: CalendarDateRange = CalendarDateRange(date, date.plusDays(3)),
        week: Boolean = false, weekends: Boolean = true): GridEditorContext {
        val old = context(event)
        val request = old.request.copy(range = window)
        val view = if (week) "WEEK" else "THREE_DAY"
        return old.copy(request = request, snapshot = old.snapshot!!.copy(request = request), date = window.start, view = view,
            preferences = old.preferences.copy(view = view, options = old.preferences.options.copy(showWeekends = weekends)))
    }
    private fun pointerEdit(context: GridEditorContext, hover: GridTarget): GridEditorIntent.Edit {
        val original = edit(context).proposal
        val path = TimedGridPointerPath(GridTarget(context.date, 570.0, zone), hover)
        return GridEditorIntent.Edit(original.copy(pointerPath = path))
    }
    @Test fun multiDayMoveRecomputesContinuedFullStartFromColumnPathAndIgnoresCallerTarget() = runBlocking {
        val event = occurrence(date.minusDays(2), 540, 84 * 60)
        val context = multiContext(event); val port = Port(source(event)); val store = Store()
        val intent = pointerEdit(context, GridTarget(date.plusDays(1), 570.0, zone)).let {
            GridEditorIntent.Edit(it.proposal.copy(target = GridTarget(date.plusDays(99), 1000.0, zone)))
        }
        val result = prepareGridEditor(context, intent, port, store, now, { context }, clock) as GridEditorPreparation.Ready
        val fields = EventEditorController.readRawFields(JSONObject(store.load(result.launch.draftId)!!.payload))
        val time = EventEditorController.normalizedDraft(fields).time as EventTime.Timed
        assertEquals(event.ref.instanceStart.plus(Duration.ofDays(1)), time.start)
        assertEquals(Duration.ofHours(84), time.duration)
        assertTrue(time.start.atZone(zone).toLocalDate() < context.request.range.start)
        assertEquals(0, port.writes)
    }
    @Test fun multiDayMissingOrForgedPointerPathsFailClosedBeforeReadingSource() = runBlocking {
        val context = multiContext(); val original = pointerEdit(context, GridTarget(date.plusDays(1), 570.0, zone)).proposal
        val path = original.pointerPath!!
        val bad = listOf(original.copy(pointerPath = null),
            original.copy(pointerPath = path.copy(origin = path.origin.copy(date = date.minusDays(1)))),
            original.copy(pointerPath = path.copy(hover = path.hover.copy(date = date.plusDays(3)))),
            original.copy(pointerPath = path.copy(hover = path.hover.copy(minute = Double.NaN))),
            original.copy(pointerPath = path.copy(hover = path.hover.copy(zone = ZoneId.of("America/New_York")))),
            original.copy(pointerPath = path.copy(origin = path.origin.copy(offsetChoice = GridOffsetChoice.Explicit(ZoneOffset.UTC)))),
            original.copy(selectedSegment = original.selectedSegment.copy(start = original.selectedSegment.start.plusSeconds(1))))
        for (proposal in bad) {
            val port = Port(source()); val store = Store()
            assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, GridEditorIntent.Edit(proposal), port, store, now, { context }, clock))
            assertEquals(0, port.reads); assertEquals(0, port.writes); assertTrue(store.records.isEmpty())
        }
    }
    @Test fun hiddenWeekendsCannotBeClaimedAsPhysicalMoveColumnsOrCreationTargets() = runBlocking {
        val context = multiContext(window = CalendarDateRange(date, date.plusDays(7)), week = true, weekends = false)
        val saturday = date.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SATURDAY))
        val port = Port(source()); val store = Store()
        assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, pointerEdit(context, GridTarget(saturday, 570.0, zone)), port, store, now, { context }, clock))
        val target = GridTarget(saturday, 540.0, zone)
        val create = GridEditorIntent.Create(TimedGridCreateProposal(context, target, TimedGridPlanning.create(target, Duration.ofHours(1))))
        assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, create, port, store, now, { context }, clock))
        assertEquals(0, port.reads); assertEquals(0, port.writes); assertTrue(store.records.isEmpty())
    }
    @Test fun multiDayCoreNormalizedMidnightOutsideRangeIsCreationDeniedButExactResizeBoundaryAllowed() = runBlocking {
        val context = multiContext(); val port = Port(source()); val store = Store()
        val last = context.request.range.endExclusive.minusDays(1)
        val target = GridTarget(last, 1439.0, zone)
        val create = GridEditorIntent.Create(TimedGridCreateProposal(context, target, TimedGridPlanning.create(target, Duration.ofHours(1))))
        assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, create, port, store, now, { context }, clock))
        val resize = edit(context, TimedGridEditKind.RESIZE_END).proposal.copy(target = GridTarget(last, 1440.0, zone))
        val result = prepareGridEditor(context, GridEditorIntent.Edit(resize), port, store, now, { context }, clock) as GridEditorPreparation.Ready
        val fields = EventEditorController.readRawFields(JSONObject(store.load(result.launch.draftId)!!.payload))
        assertEquals(context.request.range.endExclusive.atStartOfDay(zone).toInstant(), (EventEditorController.normalizedDraft(fields).time as EventTime.Timed).end)
        val beyond = resize.copy(target = GridTarget(context.request.range.endExclusive, 15.0, zone))
        assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, GridEditorIntent.Edit(beyond), port, store, now, { context }, clock))
        assertEquals(0, port.writes)
    }
    @Test fun multiDayPointerOverlapRequiresExplicitOffsetAndRetainsItInDurableFields() = runBlocking {
        val first = LocalDate.of(2026, 10, 24)
        val event = occurrence(first, 60)
        val context = multiContext(event, CalendarDateRange(first, first.plusDays(3)))
        val port = Port(source(event)); val store = Store()
        val proposal = edit(context, minute = 60.0).proposal.copy(pointerPath = TimedGridPointerPath(
            GridTarget(first, 90.0, zone), GridTarget(first.plusDays(1), 90.0, zone)))
        val intent = GridEditorIntent.Edit(proposal)
        val review = prepareGridEditor(context, intent, port, store, now, { context }, clock) as GridEditorPreparation.Review
        assertEquals(GridReviewReason.CLOCK_OVERLAP, review.preview.reason)
        assertTrue(store.records.isEmpty())
        val result = prepareGridEditor(context, intent, port, store, now, { context }, clock, offset = ZoneOffset.UTC) as GridEditorPreparation.Ready
        val fields = EventEditorController.readRawFields(JSONObject(store.load(result.launch.draftId)!!.payload))
        val time = EventEditorController.normalizedDraft(fields).time as EventTime.Timed
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"), time.start)
        assertEquals(Duration.ofHours(1), time.duration)
        assertEquals("Z", fields.startOffset)
        assertEquals(0, port.writes)
    }
    @Test fun unacknowledgedStorageNeverOpensAnEditor() = runBlocking {
        val context = context(); val port = Port(source()); val store = Store().also { it.failWrites = true }
        assertEquals(GridEditorPreparation.Unavailable, prepareGridEditor(context, edit(context), port, store, now, { context }, clock))
        assertEquals(0, port.writes)
    }
}
