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

internal class SeriesEditorFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val start = RecurrenceStart.Timed(LocalDateTime.parse("2028-06-05T09:00:00"), ZoneId.of("UTC"), ZoneOffset.UTC)
    val original = Instant.parse("2028-06-19T09:00:00Z")
    val actual = Instant.parse("2028-06-20T13:00:00Z")
    val selected = EventRef(CalendarId(2), 52, actual, actual.plusSeconds(3600), RecurrenceRef(41, original))
    val identity = SeriesIdentity(CalendarId(2), 41)
    val rights = SeriesScopeCapabilities(AccessDecision.ALLOWED, seriesUiFields, AccessDecision.ALLOWED)
    val source = EditableSeries(
        EventDetails(EventOccurrence(selected, EventTime.Timed(actual, actual.plusSeconds(3600)), "Override title", 1), null,
            location = "Override room", description = "Override notes", eventZone = ZoneId.of("UTC"),
            reminders = listOf(EventReminder(30, 1)), privacy = 3, availability = 0),
        SeriesMasterFields("Master title", "Master room", "Master notes", listOf(EventReminder(10, 1), EventReminder(-1, 1), EventReminder(60, 2)), 91, 2),
        RecurrenceSeriesSnapshot(identity, start, "FREQ=WEEKLY", durationRaw = "PT1H",
            exceptions = listOf(SeriesException(selected, RecurrenceKey.Timed(original))), coverage = SeriesCoverage.COMPLETE, validatedSelection = selected),
        EditToken("graph-token"), seriesUiFields,
        mapOf(RecurrenceScope.ALL to rights, RecurrenceScope.THIS to rights,
            RecurrenceScope.FOLLOWING to SeriesScopeCapabilities(limitation = SeriesActionLimitation.UNSUPPORTED)))
    val store = EventEditorControllerTest.Store()
    data class Call(val id: String, val selected: EventRef, val token: EditToken, val command: SeriesCommand)
    val calls = mutableListOf<Call>(); val recoveryIds = mutableListOf<String>(); var reads = 0
    var next: SeriesMutationResult = SeriesMutationResult.LocalSaved(identity, selected)
    var recovered: SeriesMutationResult = SeriesMutationResult.Common(MutationResult.Unavailable)
    var proveSafe = false; var hold: CompletableDeferred<Unit>? = null
    var readResult: SeriesReadResult = SeriesReadResult.Ready(source)
    var beforeRead: suspend () -> Unit = {}
    val port = object : CalendarSeriesMutations {
        override suspend fun loadForEdit(selected: EventRef): SeriesReadResult { reads++; beforeRead(); return readResult }
        override suspend fun mutate(operationId: String, selected: EventRef, token: EditToken, command: SeriesCommand): SeriesMutationResult {
            calls += Call(operationId, selected, token, command); hold?.await(); return next
        }
        override suspend fun recover(operationId: String): SeriesMutationResult { recoveryIds += operationId
            return if (proveSafe) SeriesMutationResult.Common(MutationResult.SafeToRetry(operationId)) else recovered }
    }
    suspend fun prepare(scope: RecurrenceScope = RecurrenceScope.ALL, action: SeriesEditorAction = SeriesEditorAction.EDIT,
        source: EditableSeries = this.source, id: String = "series"): SeriesEditorLaunch =
        (prepareSeriesDraft(source, scope, action, id, store) as SeriesDraftPreparationResult.Ready).launch
    fun controller(launch: SeriesEditorLaunch, source: EditableSeries? = this.source, recoveryOnly: Boolean = source == null) =
        SeriesEventEditorController(launch, source, port, store, scope, recoveryOnly)
    fun launch() = SeriesDraftCodec.describe(store.records.getValue("series"))!!
    fun pending(scope: RecurrenceScope = RecurrenceScope.ALL, action: SeriesEditorAction = SeriesEditorAction.EDIT): SeriesEventEditorController = runBlocking {
        val editor = controller(prepare(scope, action)); next = SeriesMutationResult.Common(MutationResult.Uncertain("ignored", selected.eventId))
        if (action == SeriesEditorAction.EDIT) { editor.change { it.copy(title = "Pending title") }; editor.save() }
        else { editor.requestDelete(); editor.confirmDelete() }
        editor
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesEditorTest {
    private val f = SeriesEditorFixture()
    @After fun stop() { f.scope.cancel() }
    @Test fun capabilitiesAreAuthoritativeAndFollowingIsNotFalselyEnabled() {
        assertTrue(canPrepareSeriesScope(f.source, RecurrenceScope.ALL, SeriesEditorAction.EDIT))
        assertFalse(canPrepareSeriesScope(f.source.copy(scopes = emptyMap()), RecurrenceScope.ALL, SeriesEditorAction.EDIT))
        assertFalse(canPrepareSeriesScope(f.source.copy(scopes = mapOf(RecurrenceScope.FOLLOWING to f.rights)), RecurrenceScope.FOLLOWING, SeriesEditorAction.EDIT))
        assertFalse(canPrepareSeriesScope(f.source.copy(scopes = mapOf(RecurrenceScope.THIS to f.rights.copy(delete = AccessDecision.UNKNOWN))), RecurrenceScope.THIS, SeriesEditorAction.DELETE))
    }
    @Test fun allFieldsComeFromMasterAndThisFieldsComeFromMovedSelectedOccurrence() = runBlocking {
        val all = f.controller(f.prepare()); assertEquals("Master title", all.state.fields.title); assertEquals("Master room", all.state.fields.location)
        assertEquals(f.start, all.masterStart); assertEquals("2028-06-05", all.launch.rawDate)
        val occurrence = f.controller(f.prepare(RecurrenceScope.THIS, id = "this"))
        assertEquals("Override title", occurrence.state.fields.title); assertEquals("Override room", occurrence.state.fields.location)
        assertEquals("2028-06-20", occurrence.launch.rawDate); assertEquals(f.selected, occurrence.launch.selected)
    }
    @Test fun allEditFreezesMasterAnchorAndKeepsTemporalRuleAndUnchangedMetadata() = runBlocking {
        val editor = f.controller(f.prepare()); editor.change { it.copy(title = "New master") }; editor.save()
        val call = f.calls.single(); val intent = (call.command as SeriesCommand.WholeEdit).intent
        assertEquals(f.start, intent.expectedMasterStart); assertEquals(FieldChange.Keep, intent.newMasterStart)
        assertEquals(RecurrenceDurationChange.KeepRaw, intent.duration); assertEquals(RecurrenceRuleChanges(), intent.rule)
        assertEquals(FieldChange.Set("New master"), intent.fields.title); assertEquals(FieldChange.Keep, intent.fields.location)
        assertEquals(FieldChange.Keep, intent.fields.availability); assertEquals(FieldChange.Keep, intent.fields.privacy)
        assertEquals(FieldChange.Keep, intent.fields.time); assertEquals(FieldChange.Keep, intent.fields.eventZone)
        assertEquals(FieldChange.Keep, intent.fields.alertMinutes); assertEquals(f.selected, call.selected); assertEquals(f.source.token, call.token)
    }
    @Test fun thisEditNeverCopiesMasterFieldsOrChangesOriginalIdentity() = runBlocking {
        val editor = f.controller(f.prepare(RecurrenceScope.THIS)); editor.change { it.copy(location = "Changed override") }; editor.save()
        val call = f.calls.single(); val patch = (call.command as SeriesCommand.OccurrenceEdit).patch
        assertEquals(FieldChange.Set("Changed override"), patch.location); assertEquals(FieldChange.Keep, patch.title)
        assertEquals(f.original, call.selected.recurrence!!.originalStart); assertEquals(f.actual, call.selected.instanceStart)
    }
    @Test fun noChangeSaveAndForbiddenFieldTransformsNeverSubmit() = runBlocking {
        val source = f.source.copy(scopes = mapOf(RecurrenceScope.ALL to f.rights.copy(fields = setOf(EditableField.TITLE))))
        val editor = f.controller(f.prepare(source = source), source); editor.save(); assertTrue(f.calls.isEmpty())
        editor.change { it.copy(location = "Unauthorized", description = "Unauthorized", privacy = "0") }
        assertEquals("Master room", editor.state.fields.location); assertEquals("2", editor.state.fields.privacy); assertFalse(editor.hasChanges)
        editor.change { it.copy(title = "Allowed") }; editor.save()
        val patch = (f.calls.single().command as SeriesCommand.WholeEdit).intent.fields
        assertEquals(FieldChange.Keep, patch.location); assertEquals(FieldChange.Keep, patch.privacy)
    }
    @Test fun unresolvedAndOtherNotificationsStayKeepUnlessReplacementIsExplicit() = runBlocking {
        val editor = f.controller(f.prepare()); assertTrue(editor.hasDefaultAlerts); assertEquals("10", editor.state.fields.alerts)
        editor.change { it.copy(title = "Changed", alerts = "unfinished") }; editor.save()
        assertEquals(FieldChange.Keep, (f.calls.single().command as SeriesCommand.WholeEdit).intent.fields.alertMinutes)
        f.store.records.clear(); f.calls.clear()
        val clear = f.controller(f.prepare()); clear.change { it.copy(replaceAlerts = true, alerts = "") }; clear.save()
        assertEquals(FieldChange.Set(emptyList<Int>()), (f.calls.single().command as SeriesCommand.WholeEdit).intent.fields.alertMinutes)
        assertEquals(listOf(EventReminder(10, 1), EventReminder(-1, 1), EventReminder(60, 2)), f.source.masterFields.reminders)
    }
    @Test fun signedReplacementOffsetsSupportedAndDefaultSentinelRejected() = runBlocking {
        val editor = f.controller(f.prepare()); editor.change { it.copy(replaceAlerts = true, alerts = "-1") }; editor.save()
        assertEquals(SeriesEditorProblem.INVALID_INPUT, editor.state.problem); assertTrue(f.calls.isEmpty())
        editor.change { it.copy(alerts = "-540,0,15,15") }; editor.save()
        assertEquals(FieldChange.Set(listOf(-540, 0, 15)), (f.calls.single().command as SeriesCommand.WholeEdit).intent.fields.alertMinutes)
    }
    @Test fun unknownUntouchedMetadataIsKeptAndChangedMetadataIsTypedValidated() = runBlocking {
        val editor = f.controller(f.prepare()); editor.change { it.copy(availability = "99") }; editor.save(); assertTrue(f.calls.isEmpty())
        editor.change { it.copy(availability = "1", privacy = "") }; editor.save()
        val patch = (f.calls.single().command as SeriesCommand.WholeEdit).intent.fields
        assertEquals(FieldChange.Set(1), patch.availability); assertEquals(FieldChange.Set<Int?>(null), patch.privacy)
    }
    @Test fun partialRawFieldsAndStableOperationRestoreWithoutAnyEventWrite() = runBlocking {
        val first = f.controller(f.prepare()); first.change { it.copy(description = "Unfinished\nnotes", alerts = "15,unfinished", replaceAlerts = true) }
        assertTrue(first.flushDraft()); val id = first.operationId
        val restored = f.controller(f.launch()); assertEquals("15,unfinished", restored.state.fields.alerts)
        assertEquals("Unfinished\nnotes", restored.state.fields.description); assertEquals(id, restored.operationId)
        assertTrue(restored.prepareDismiss()); assertTrue(f.calls.isEmpty()); assertEquals(0, f.reads)
    }
    @Test fun preparationIsDurableAndRetainedRawCannotBeOverwrittenOrRescoped() = runBlocking {
        val first = f.controller(f.prepare()); first.change { it.copy(title = "Retained") }; first.flushDraft()
        val record = f.store.records.getValue("series"); assertEquals("Retained", f.prepare().title)
        assertEquals(record, f.store.records.getValue("series"))
        assertEquals(SeriesDraftPreparationResult.Unavailable, prepareSeriesDraft(f.source, RecurrenceScope.THIS, SeriesEditorAction.EDIT, "series", f.store))
        assertEquals(record, f.store.records.getValue("series"))
        f.store.records.clear(); f.store.failWrites = true
        assertEquals(SeriesDraftPreparationResult.Unavailable, prepareSeriesDraft(f.source, RecurrenceScope.ALL, SeriesEditorAction.EDIT, "series", f.store))
        assertTrue(f.calls.isEmpty())
    }
    @Test fun missingOrWrongRouteDraftNeverSeedsOrDeletesAnEvent() = runBlocking {
        val launch = f.prepare(action = SeriesEditorAction.DELETE); f.store.records.clear()
        val missing = f.controller(launch); missing.requestDelete(); missing.confirmDelete(); missing.save()
        assertTrue(missing.state.draftReadFailed); assertTrue(f.calls.isEmpty())
        val actual = f.prepare(); val wrong = f.controller(actual.copy(scope = RecurrenceScope.THIS))
        wrong.change { it.copy(title = "Wrong scope") }; wrong.save(); assertTrue(wrong.state.draftReadFailed); assertTrue(f.calls.isEmpty())
    }
    @Test fun doubleSaveIsGuardedAndSuccessfulControllerIsTerminal() = runBlocking {
        val gate = CompletableDeferred<Unit>(); f.hold = gate
        val editor = f.controller(f.prepare()); editor.change { it.copy(title = "Changed") }; editor.save(); editor.save()
        assertEquals(1, f.calls.size); gate.complete(Unit); yield()
        editor.save(); assertEquals(1, f.calls.size); assertTrue(f.store.records.isEmpty())
    }
    @Test fun deleteRequiresExplicitConfirmationAndUsesExactSelectedScope() = runBlocking {
        f.next = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.THIS, f.selected)
        val editor = f.controller(f.prepare(RecurrenceScope.THIS, SeriesEditorAction.DELETE))
        editor.confirmDelete(); assertTrue(f.calls.isEmpty()); editor.requestDelete(); editor.cancelDelete(); editor.confirmDelete(); assertTrue(f.calls.isEmpty())
        editor.requestDelete(); editor.confirmDelete(); editor.confirmDelete()
        assertEquals(SeriesCommand.Delete(RecurrenceScope.THIS), f.calls.single().command); assertEquals(f.selected, f.calls.single().selected)
    }
    @Test fun changedTokenOrCapabilitiesLocksDraftInsteadOfAdoptingFreshSource() = runBlocking {
        val first = f.controller(f.prepare()); first.change { it.copy(title = "My draft") }; first.flushDraft()
        for (source in listOf(f.source.copy(token = EditToken("new")), f.source.copy(scopes = mapOf(RecurrenceScope.ALL to f.rights.copy(fields = setOf(EditableField.TITLE)))))) {
            val restored = f.controller(f.launch(), source); assertTrue(restored.state.reviewRequired)
            assertEquals("My draft", restored.state.fields.title); restored.save(); assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun backendReviewPersistsAcrossRestoreWithoutShowingTechnicalReasons() = runBlocking {
        f.next = SeriesMutationResult.PlanningReview("SECRET provider-id 41")
        val first = f.controller(f.prepare()); first.change { it.copy(title = "Draft") }; first.save()
        assertTrue(first.state.reviewRequired); val restored = f.controller(f.launch())
        assertTrue(restored.state.reviewRequired); restored.save(); assertEquals(1, f.calls.size)
        assertEquals(SeriesEditorProblem.SOURCE_CHANGED, restored.state.problem)
    }
    @Test fun conflictRetainsDraftAndOnlyExplicitDiscardLoadsFreshSource() = runBlocking {
        f.next = SeriesMutationResult.Common(MutationResult.Conflict(null))
        val editor = f.controller(f.prepare()); editor.change { it.copy(title = "Draft") }; editor.save()
        assertTrue(editor.state.reviewRequired); assertFalse(editor.flushDraft()); assertEquals(0, f.reads)
        assertTrue(editor.discardAndReload() is SeriesReadResult.Ready); assertEquals(1, f.reads); assertTrue(f.store.records.isEmpty())
    }
    @Test fun unknownRecoveryAndWrongProofStayLockedWithoutNewOperation() {
        val pending = f.pending(); val id = pending.operationId
        val restored = f.controller(f.launch(), null); restored.recover(); restored.change { it.copy(title = "Replacement") }; restored.save(); restored.retryOriginal()
        assertTrue(restored.state.recoveryRequired); assertEquals(1, f.calls.size); assertEquals(id, restored.operationId)
        f.recovered = SeriesMutationResult.Common(MutationResult.SafeToRetry("wrong")); restored.recover(); restored.retryOriginal()
        assertFalse(restored.state.retryReady); assertEquals(1, f.calls.size)
    }
    @Test fun safeRecoveryOnlyEnablesExplicitExactOriginalRetryAndNoEditing() {
        f.pending(); val command = f.calls.single()
        val restored = f.controller(f.launch(), null); f.proveSafe = true; restored.recover()
        assertEquals(1, f.calls.size); assertTrue(restored.state.retryReady)
        restored.change { it.copy(title = "Unsafe") }; restored.save(); restored.requestDelete(); restored.confirmDelete()
        f.next = SeriesMutationResult.Common(MutationResult.PermissionRequired); restored.retryOriginal()
        assertEquals(listOf(command, command), f.calls); assertTrue(restored.state.retryReady)
        assertEquals("Pending title", restored.state.fields.title)
    }
    @Test fun exactRetryStillHasDoubleTapGuard() = runBlocking {
        f.pending(); val restored = f.controller(f.launch(), null); f.proveSafe = true; restored.recover()
        val gate = CompletableDeferred<Unit>(); f.hold = gate
        restored.retryOriginal(); restored.retryOriginal(); assertEquals(2, f.calls.size)
        gate.complete(Unit); yield(); assertEquals(1, f.calls.map { it.id }.distinct().size)
    }
    @Test fun recoveryOnlyCanResolveDeletedSeriesWithoutAnyLiveGraph() {
        f.pending(action = SeriesEditorAction.DELETE)
        val saved = f.store.records.getValue("series"); val restored = f.controller(f.launch(), null)
        f.recovered = SeriesMutationResult.LocalDeleted(f.identity, RecurrenceScope.ALL, f.selected); restored.recover()
        assertTrue(restored.state.result is SeriesMutationResult.LocalDeleted); assertEquals(1, f.calls.size); assertEquals(0, f.reads)
        assertTrue(f.store.records.isEmpty()); assertTrue(SeriesDraftCodec.describe(saved)!!.needsRecovery)
    }
    @Test fun changedRecoveryRecordBlocksRetryWithoutOverwritingIt() {
        f.pending(); val restored = f.controller(f.launch(), null); f.proveSafe = true; restored.recover()
        val old = f.store.records.getValue("series"); val changed = old.copy(payload = JSONObject(old.payload).put("rawDate", "externally changed").toString())
        f.store.records["series"] = changed; restored.retryOriginal()
        assertEquals(1, f.calls.size); assertEquals(changed, f.store.records.getValue("series"))
    }
    @Test fun corruptOrFutureSeriesRecordsAreRetainedNonlaunchableAndNeverBecomeOneOffs() = runBlocking {
        f.pending(); val original = f.store.records.getValue("series")
        val bad = listOf(original.copy(payload = JSONObject(original.payload).put("schema", 999).toString()),
            original.copy(payload = JSONObject(original.payload).apply { getJSONObject("fields").put("title", "Contradicts frozen request") }.toString()),
            original.copy(payload = JSONObject(original.payload).apply { getJSONObject("frozen").put("unrepresentedRuleChange", "FREQ=DAILY") }.toString()))
        bad.forEach { val item = DraftBrowserCodec.read(it); assertNull(item.launch); assertNull(item.seriesLaunch); assertNotNull(item.issue) }
        val item = DraftBrowserCodec.read(original); assertNull(item.launch); assertNotNull(item.seriesLaunch)
        assertEquals(original, f.store.records.getValue("series"))
    }
    @Test fun knownSavedResultWithNullableSelectionClosesWithoutFabricatingReference() {
        f.pending(); val restored = f.controller(f.launch(), null)
        f.recovered = SeriesMutationResult.LocalSaved(f.identity, null); restored.recover()
        assertEquals(SeriesMutationResult.LocalSaved(f.identity, null), restored.state.result); assertTrue(f.store.records.isEmpty())
    }
    @Test fun preDispatchStorageFailureDoesNotClaimProviderUncertaintyAndCanRetryAfterRepair() = runBlocking {
        val editor = f.controller(f.prepare()); editor.change { it.copy(title = "Changed") }; f.store.failWrites = true; editor.save()
        assertTrue(f.calls.isEmpty()); assertEquals(SeriesEditorProblem.DRAFT_UNAVAILABLE, editor.state.problem)
        assertFalse(editor.state.recoveryRequired); f.store.failWrites = false; editor.save(); assertEquals(1, f.calls.size)
    }
    @Test fun permissionFailureCanRetrySameExactCommandWhileChangedPayloadRotatesIdentity() = runBlocking {
        f.next = SeriesMutationResult.Common(MutationResult.PermissionRequired)
        val editor = f.controller(f.prepare()); editor.change { it.copy(title = "Changed") }; editor.save(); editor.save()
        assertEquals(2, f.calls.size); assertEquals(1, f.calls.map { it.id }.distinct().size)
        editor.change { it.copy(title = "Another change") }; editor.save()
        assertNotEquals(f.calls.first().id, f.calls.last().id)
    }
    @Test fun concurrentConfirmedDiscardsCannotDeleteReplacementRawDraft() = runBlocking {
        val old = f.controller(f.prepare(), f.source.copy(token = EditToken("fresh-token")))
        val gateA = CompletableDeferred<Unit>(); val gateB = CompletableDeferred<Unit>()
        f.beforeRead = { if (f.reads == 1) gateA.await() else gateB.await() }
        val first = f.scope.async { old.discardAndReload() }
        val second = f.scope.async { old.discardAndReload() }
        assertTrue(old.state.reviewing); assertFalse(old.canReviewDraft)
        gateA.complete(Unit); assertTrue(first.await() is SeriesReadResult.Ready)
        val replacement = f.controller(f.prepare())
        replacement.change { it.copy(title = "New replacement raw edits") }; assertTrue(replacement.flushDraft())
        val newRecord = f.store.records.getValue("series")
        gateB.complete(Unit); assertNull(second.await())
        assertNull(old.discardAndReload()); assertEquals(SeriesReadResult.Unavailable, old.reloadForReview())
        old.change { it.copy(title = "Old controller overwrite") }; old.save(); old.requestDelete(); old.confirmDelete()
        assertEquals(newRecord, f.store.records.getValue("series")); assertEquals(1, f.reads); assertTrue(f.calls.isEmpty())
        assertTrue(old.prepareDismiss()); assertFalse(old.canReviewDraft)
    }
    @Test fun discardChecksExpectedRecordBeforeDeletingObservedReplacement() = runBlocking {
        val old = f.controller(f.prepare(), f.source.copy(token = EditToken("fresh-token")))
        val gate = CompletableDeferred<Unit>(); f.beforeRead = { gate.await() }
        val deleting = f.scope.async { old.discardAndReload() }
        val original = f.store.records.getValue("series")
        val replacement = original.copy(payload = JSONObject(original.payload).apply { getJSONObject("fields").put("title", "Observed replacement") }.toString())
        f.store.records["series"] = replacement; gate.complete(Unit)
        assertNull(deleting.await()); assertEquals(replacement, f.store.records.getValue("series"))
        assertEquals(SeriesEditorProblem.DRAFT_UNAVAILABLE, old.state.problem); assertFalse(old.state.reviewing)
        assertTrue(f.calls.isEmpty())
    }
    @Test fun failedReviewReadReleasesSingleFlightForNextExplicitConfirmation() = runBlocking {
        val old = f.controller(f.prepare(), f.source.copy(token = EditToken("fresh-token")))
        val before = f.store.records.getValue("series"); f.readResult = SeriesReadResult.Unavailable
        assertEquals(SeriesReadResult.Unavailable, old.discardAndReload()); assertEquals(before, f.store.records.getValue("series"))
        assertFalse(old.state.reviewing); assertTrue(old.canReviewDraft)
        f.readResult = SeriesReadResult.Ready(f.source)
        assertTrue(old.discardAndReload() is SeriesReadResult.Ready); assertEquals(2, f.reads); assertTrue(f.store.records.isEmpty())
        assertNull(old.discardAndReload()); assertEquals(2, f.reads)
    }
    @Test fun reviewInFlightBlocksChangesAndDismissUntilFreshReadCompletes() = runBlocking {
        val old = f.controller(f.prepare(), f.source.copy(token = EditToken("fresh-token")))
        val gate = CompletableDeferred<Unit>(); f.beforeRead = { gate.await() }
        val deleting = f.scope.async { old.discardAndReload() }
        old.change { it.copy(title = "Must not change") }; old.save(); assertFalse(old.prepareDismiss())
        assertEquals("Master title", old.state.fields.title); assertTrue(f.calls.isEmpty())
        f.readResult = SeriesReadResult.Unavailable; gate.complete(Unit); deleting.await()
        assertFalse(old.state.reviewing); assertTrue(old.prepareDismiss())
    }
    @Test fun mismatchedSuccessIdentityStaysUncertainAndRetainsDraft() {
        f.pending(); val restored = f.controller(f.launch(), null)
        f.recovered = SeriesMutationResult.LocalSaved(SeriesIdentity(CalendarId(2), 999), null); restored.recover()
        assertTrue(restored.state.recoveryRequired); assertTrue(f.store.records.isNotEmpty()); assertEquals(1, f.calls.size)
    }
}
