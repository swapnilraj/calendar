package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderLedgerTest {
    private val now = Instant.parse("2026-11-17T09:00:00Z")
    private val calendar = CalendarId(2)
    private val identity = ReminderCalendarIdentity("opaque-google-account-A", "opaque-calendar-A")
    private val window = InstantBounds(now.minusSeconds(86_400), now.plusSeconds(90 * 86_400L))
    private fun ref(id: Long = 10, start: Instant = now.plusSeconds(3_600), end: Instant = start.plusSeconds(3_600), recurrence: RecurrenceRef? = null) =
        EventRef(calendar, id, start, end, recurrence)
    private fun candidate(ref: EventRef = ref(), minutes: Int = 60, due: Instant = ref.instanceStart.minusSeconds(minutes * 60L),
        identity: ReminderCalendarIdentity = this.identity, uid: String? = "opaque-event-${ref.recurrence?.masterEventId ?: ref.eventId}", allDay: Boolean = false): ReminderCandidate {
        val time = if (allDay) EventTime.AllDay.fromProvider(ref.instanceStart, ref.instanceEnd) else EventTime.Timed(ref.instanceStart, ref.instanceEnd)
        return ReminderCandidate(ReminderKey.of(ref, minutes), identity,
            EventOccurrence(ref, time, "PRIVATE-SYNTHETIC-TITLE-NOT-PERSISTED", 0xffaa3366.toInt()), due, uid)
    }
    private fun snapshot(candidates: List<ReminderCandidate> = emptyList(), identity: ReminderCalendarIdentity = this.identity,
        complete: Boolean = true, window: InstantBounds = this.window, removed: Boolean = false) = ReminderSourceSnapshot(
        window, candidates, if (complete && !removed) setOf(calendar) else emptySet(),
        if (removed) emptyMap() else mapOf(calendar to identity),
        if (complete) emptyList() else listOf(ReminderSourceFailure(calendar, ReminderSourceProblem.QUERY_FAILED)),
        if (removed) setOf(calendar) else emptySet())
    private suspend fun fixture(block: suspend (CalendarLocalStore, SqliteReminderLedger) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val name = "reminder-ledger-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name)
        try { block(store, SqliteReminderLedger(store)) }
        finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun schedule(ledger: ReminderLedger, candidate: ReminderCandidate = candidate()): ReminderRecord {
        ledger.reconcile(snapshot(listOf(candidate), candidate.sourceCalendarIdentity), setOf(calendar), emptyMap(), now)
        return ledger.nextScheduled()!!
    }
    private suspend fun post(ledger: ReminderLedger, candidate: ReminderCandidate = candidate()): ReminderRecord {
        val record = schedule(ledger, candidate)
        val claim = ledger.claimDue(candidate.due, 1).single()
        assertTrue(ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(candidate.due)))
        return ledger.findByAlias(record.notificationAlias)!!
    }

    @Test fun repeatedDiscoveryIsIdempotentAndNoPresentationTextIsStored() = runBlocking {
        fixture { store, ledger ->
            val original = schedule(ledger)
            repeat(3) { assertTrue(ledger.reconcile(snapshot(listOf(candidate())), setOf(calendar), emptyMap(), now).cancelNotifications.isEmpty()) }
            assertEquals(original, ledger.nextScheduled())
            store.readableDatabase.rawQuery("SELECT payload FROM reminder_ledger", null).use {
                assertTrue(it.moveToFirst())
                val payload = it.getString(0)
                assertFalse(payload.contains("PRIVATE-SYNTHETIC-TITLE-NOT-PERSISTED"))
                assertFalse(payload.contains("\"title\""))
                assertFalse(payload.contains("\"colour\""))
                assertFalse(payload.contains("\"attendees\""))
                assertFalse(it.moveToNext())
            }
        }
    }

    @Test fun competingClaimsFromTwoConnectionsCannotClaimOneDeliveryTwice() = runBlocking {
        fixture { store, ledger ->
            schedule(ledger)
            val secondStore = CalendarLocalStore(RuntimeEnvironment.getApplication(), store.databaseName)
            try {
                val second = SqliteReminderLedger(secondStore)
                val claims = listOf(async(Dispatchers.IO) { ledger.claimDue(now, 10) }, async(Dispatchers.IO) { second.claimDue(now, 10) }).awaitAll().flatten()
                assertEquals(1, claims.size)
                assertFalse(claims.single().record.claimToken.isNullOrBlank())
                assertEquals(1, ledger.interruptedDeliveries().size)
                assertNull(ledger.nextScheduled())
            } finally { secondStore.close() }
        }
    }

    @Test fun acknowledgementIsVersionAndTokenCheckedAndCannotRepeat() = runBlocking {
        fixture { _, ledger ->
            schedule(ledger)
            val claim = ledger.claimDue(now, 1).single()
            val forged = ReminderClaim(claim.record.copy(claimToken = "wrong-token"))
            assertFalse(ledger.acknowledge(forged, ReminderDeliveryOutcome.Posted(now)))
            assertTrue(ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(now)))
            assertFalse(ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(now)))
            assertTrue(ledger.interruptedDeliveries().isEmpty())
        }
    }

    @Test fun endOnlyEditsKeepAliasAndHistoryButInvalidateStaleClaimRevision() = runBlocking {
        fixture { _, ledger ->
            val original = schedule(ledger)
            val claim = ledger.claimDue(now, 1).single()
            val changed = candidate(ref = ref(end = ref().instanceEnd.plusSeconds(1_800)))
            ledger.reconcile(snapshot(listOf(changed)), setOf(calendar), mapOf(changed.key to ReminderResolution.Eligible(changed)), now)
            assertFalse(ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(now)))
            val currentClaim = ledger.interruptedDeliveries().single()
            assertEquals(claim.record.claimToken, currentClaim.record.claimToken)
            assertEquals(changed.occurrence.ref, currentClaim.record.currentRef)
            assertEquals(original.notificationAlias, currentClaim.record.notificationAlias)
            assertTrue(ledger.acknowledge(currentClaim, ReminderDeliveryOutcome.Posted(now)))
            assertEquals(changed.occurrence.ref, ledger.findByAlias(original.notificationAlias)!!.currentRef)
        }
    }

    @Test fun discoveryWindowAbsenceAndZoneOnlyDueOutsideWindowNeverEraseDismissedHistory() = runBlocking {
        fixture { _, ledger ->
            val allDayRef = ref(start = Instant.parse("2026-11-18T00:00:00Z"), end = Instant.parse("2026-11-19T00:00:00Z"))
            val initial = candidate(allDayRef, minutes = 900, due = now, allDay = true)
            val posted = post(ledger, initial)
            assertTrue(ledger.dismiss(posted.version, posted.postToken!!))
            val dismissed = ledger.findByAlias(posted.notificationAlias)!!
            val narrow = InstantBounds(now.minusSeconds(60), now.plusSeconds(60))
            assertEquals(listOf(dismissed), ledger.trackedForRevalidation(narrow))
            assertTrue(ledger.reconcile(snapshot(window = narrow), setOf(calendar), emptyMap(), now).cancelNotifications.isEmpty())
            assertEquals(dismissed, ledger.findByAlias(posted.notificationAlias))
            // NY device zone moves the same all-day reminder five hours later, beyond discovery.
            val shifted = initial.copy(due = now.plusSeconds(18_000))
            ledger.reconcile(snapshot(window = narrow), setOf(calendar), mapOf(initial.key to ReminderResolution.Eligible(shifted)), now)
            val current = ledger.findByAlias(posted.notificationAlias)!!
            assertEquals(ReminderDeliveryState.DISMISSED, current.state)
            assertEquals(shifted.due, current.plannedDue)
            assertEquals(dismissed.generation, current.generation)
            assertEquals(dismissed.notificationAlias, current.notificationAlias)
            assertNull(ledger.nextScheduled())
        }
    }

    @Test fun deferredResolutionDoesNotRetireAndFreshOutsideWindowEligibilityCanResume() = runBlocking {
        fixture { _, ledger ->
            val allDayRef = ref(start = Instant.parse("2026-11-18T00:00:00Z"), end = Instant.parse("2026-11-19T00:00:00Z"))
            val originalCandidate = candidate(allDayRef, minutes = 900, due = now, allDay = true)
            val initial = schedule(ledger, originalCandidate)
            ledger.reconcile(snapshot(), setOf(calendar), mapOf(initial.key to ReminderResolution.Deferred(ReminderSourceProblem.QUERY_FAILED)), now)
            val deferred = ledger.findByAlias(initial.notificationAlias)!!
            assertEquals(ReminderDeliveryState.DEFERRED, deferred.state)
            assertNull(ledger.nextScheduled())
            val future = originalCandidate.copy(due = now.plusSeconds(18_000))
            val narrow = InstantBounds(now.minusSeconds(60), now.plusSeconds(60))
            ledger.reconcile(snapshot(window = narrow), setOf(calendar), mapOf(initial.key to ReminderResolution.Eligible(future)), now)
            val resumed = ledger.nextScheduled()!!
            assertEquals(future.due, resumed.effectiveDue)
            assertEquals(initial.notificationAlias, resumed.notificationAlias)
        }
    }

    @Test fun unavailableDiscoveryStillAppliesKnownEligibleResolutionAndDisabledCleanup() = runBlocking {
        fixture { _, ledger ->
            val initial = schedule(ledger)
            val changed = candidate(ref = ref(end = ref().instanceEnd.plusSeconds(600)))
            val unavailable = ReminderSourceSnapshot(window, emptyList(), emptySet(), emptyMap(),
                listOf(ReminderSourceFailure(calendar, ReminderSourceProblem.QUERY_FAILED)))
            ledger.reconcile(unavailable, setOf(calendar), mapOf(initial.key to ReminderResolution.Eligible(changed)), now)
            val updated = ledger.findByAlias(initial.notificationAlias)!!
            assertEquals(changed.occurrence.ref, updated.currentRef)
            assertEquals(initial.notificationAlias, updated.notificationAlias)
            val cleanup = ledger.reconcile(unavailable, emptySet(), emptyMap(), now)
            assertEquals(setOf(initial.notificationAlias), cleanup.cancelNotifications)
            assertEquals(ReminderDeliveryState.CANCELLED, ledger.findByAlias(initial.notificationAlias)!!.state)
            assertNull(ledger.nextScheduled())
        }
    }

    @Test fun interruptedAndUncertainPostsSurviveReconcileAndDatabaseReopen() = runBlocking {
        fixture { store, ledger ->
            val initial = schedule(ledger)
            val claim = ledger.claimDue(now, 1).single()
            ledger.reconcile(snapshot(), setOf(calendar), mapOf(initial.key to ReminderResolution.Deferred(ReminderSourceProblem.QUERY_FAILED)), now)
            assertEquals(claim, ledger.interruptedDeliveries().single())
            assertTrue(ledger.acknowledge(claim, ReminderDeliveryOutcome.Uncertain))
            val uncertain = ledger.interruptedDeliveries().single()
            assertEquals(ReminderDeliveryState.UNCERTAIN, uncertain.record.state)
            ledger.reconcile(snapshot(listOf(candidate())), setOf(calendar), emptyMap(), now)
            assertEquals(uncertain, ledger.interruptedDeliveries().single())
            assertTrue(ledger.claimDue(now, 1).isEmpty())
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), store.databaseName)
            try { assertEquals(listOf(uncertain), SqliteReminderLedger(reopened).interruptedDeliveries()) } finally { reopened.close() }
        }
    }

    @Test fun namespaceChangeRetiresEvenWithEmptyIncompleteDiscoveryAndRejectsOldActions() = runBlocking {
        fixture { _, ledger ->
            val posted = post(ledger)
            val replacement = ReminderCalendarIdentity("opaque-google-account-B", "opaque-calendar-B")
            val changes = ledger.reconcile(snapshot(identity = replacement, complete = false), setOf(calendar), emptyMap(), now)
            assertEquals(setOf(posted.notificationAlias), changes.cancelNotifications)
            assertEquals(ReminderDeliveryState.RETIRED, ledger.findByAlias(posted.notificationAlias)!!.state)
            assertFalse(ledger.dismiss(posted.version, posted.postToken!!))
            val newCandidate = candidate(identity = replacement)
            ledger.reconcile(snapshot(listOf(newCandidate), replacement), setOf(calendar), emptyMap(), now)
            val current = ledger.nextScheduled()!!
            assertNotEquals(posted.notificationAlias.nonce, current.notificationAlias.nonce)
            assertNotEquals(posted.generation, current.generation)
            assertNull(ledger.findByAlias(posted.notificationAlias))
            assertEquals(replacement, current.sourceCalendarIdentity)
        }
    }

    @Test fun knownUidIsRetainedWhenMissingButChangedKnownUidCreatesFreshGeneration() = runBlocking {
        fixture { _, ledger ->
            val posted = post(ledger)
            val missing = candidate(identity = identity.copy(providerUidFingerprint = null), uid = null)
            ledger.reconcile(snapshot(listOf(missing), missing.sourceCalendarIdentity), setOf(calendar), emptyMap(), now)
            val preserved = ledger.findByAlias(posted.notificationAlias)!!
            assertEquals(identity, preserved.sourceCalendarIdentity)
            assertEquals(posted.sourceEventUidFingerprint, preserved.sourceEventUidFingerprint)
            assertEquals(ReminderDeliveryState.POSTED, preserved.state)
            val differentEvent = candidate(uid = "opaque-replacement-event")
            val changes = ledger.reconcile(snapshot(listOf(differentEvent)), setOf(calendar), emptyMap(), now)
            assertEquals(setOf(posted.notificationAlias), changes.cancelNotifications)
            val current = ledger.nextScheduled()!!
            assertEquals("opaque-replacement-event", current.sourceEventUidFingerprint)
            assertNotEquals(posted.notificationAlias.nonce, current.notificationAlias.nonce)
            assertNull(current.lastPostedAt)
        }
    }

    @Test fun observedKnownCalendarUidChangeAndAuthoritativeRemovalRetireOldClaims() = runBlocking {
        fixture { _, ledger ->
            val original = schedule(ledger)
            val claim = ledger.claimDue(now, 1).single()
            ledger.reconcile(snapshot(identity = identity.copy(providerUidFingerprint = "new-provider-calendar"), complete = false), setOf(calendar), emptyMap(), now)
            assertFalse(ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(now)))
            assertTrue(ledger.interruptedDeliveries().isEmpty())
            assertEquals(ReminderDeliveryState.RETIRED, ledger.findByAlias(original.notificationAlias)!!.state)
            val currentCandidate = candidate(identity = identity.copy(providerUidFingerprint = "new-provider-calendar"))
            ledger.reconcile(snapshot(listOf(currentCandidate), currentCandidate.sourceCalendarIdentity), setOf(calendar), emptyMap(), now)
            val newRecord = ledger.nextScheduled()!!
            ledger.reconcile(snapshot(removed = true), setOf(calendar), emptyMap(), now)
            assertEquals(ReminderDeliveryState.RETIRED, ledger.findByAlias(newRecord.notificationAlias)!!.state)
        }
    }

    @Test fun explicitGoneThenRescheduleBackGetsFreshNonceWithoutInheritingDismissal() = runBlocking {
        fixture { _, ledger ->
            val posted = post(ledger)
            assertTrue(ledger.dismiss(posted.version, posted.postToken!!))
            ledger.reconcile(snapshot(), setOf(calendar), mapOf(posted.key to ReminderResolution.GoneOrIneligible), now)
            assertEquals(ReminderDeliveryState.RETIRED, ledger.findByAlias(posted.notificationAlias)!!.state)
            ledger.reconcile(snapshot(listOf(candidate())), setOf(calendar), emptyMap(), now)
            val returned = ledger.nextScheduled()!!
            assertNotEquals(posted.notificationAlias.nonce, returned.notificationAlias.nonce)
            assertTrue(returned.generation > posted.generation)
            assertNull(returned.lastPostedAt)
            assertNull(ledger.findByAlias(posted.notificationAlias))
        }
    }

    @Test fun movedRecurringExceptionKeepsSemanticKeyButStartsFreshGeneration() = runBlocking {
        fixture { _, ledger ->
            val recurrence = RecurrenceRef(100, ref().instanceStart)
            val original = candidate(ref(101, recurrence = recurrence), uid = "series-uid")
            val posted = post(ledger, original)
            val moved = candidate(ref(102, start = ref().instanceStart.plusSeconds(172_800), recurrence = recurrence), uid = "series-uid")
            assertEquals(original.key, moved.key)
            ledger.reconcile(snapshot(listOf(moved)), setOf(calendar), mapOf(original.key to ReminderResolution.Eligible(moved)), now)
            val current = ledger.nextScheduled()!!
            assertEquals(moved.occurrence.ref, current.currentRef)
            assertNotEquals(posted.notificationAlias.nonce, current.notificationAlias.nonce)
            assertTrue(current.generation > posted.generation)
        }
    }

    @Test fun snoozeDeadlineSurvivesZoneChangeClaimAndDeferredRetry() = runBlocking {
        fixture { _, ledger ->
            val allDayRef = ref(start = Instant.parse("2026-11-18T00:00:00Z"), end = Instant.parse("2026-11-19T00:00:00Z"))
            val originalCandidate = candidate(allDayRef, minutes = 900, due = now, allDay = true)
            val original = post(ledger, originalCandidate)
            val snoozed = ledger.snooze(original.version, original.postToken!!, 5, now)!!
            assertNull(ledger.snooze(original.version, original.postToken!!, 5, now))
            val zoneChanged = originalCandidate.copy(due = now.plusSeconds(18_000))
            ledger.reconcile(snapshot(listOf(zoneChanged)), setOf(calendar), emptyMap(), now)
            val retained = ledger.nextScheduled()!!
            assertEquals(now.plusSeconds(300), retained.effectiveDue)
            assertEquals(snoozed.notificationAlias, retained.notificationAlias)
            val claim = ledger.claimDue(now.plusSeconds(300), 1).single()
            assertEquals(now.plusSeconds(300), claim.record.snoozedUntil)
            assertTrue(ledger.acknowledge(claim, ReminderDeliveryOutcome.Deferred))
            ledger.reconcile(snapshot(listOf(zoneChanged)), setOf(calendar), mapOf(original.key to ReminderResolution.Eligible(zoneChanged)), now.plusSeconds(360))
            val resumed = ledger.nextScheduled()!!
            assertEquals(now.plusSeconds(300), resumed.snoozedUntil)
            assertEquals(now.plusSeconds(300), resumed.effectiveDue)
            val again = ledger.claimDue(now.plusSeconds(360), 1).single()
            assertNotEquals(claim.record.claimToken, again.record.claimToken)
            assertFalse(ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(now.plusSeconds(360))))
            assertTrue(ledger.acknowledge(again, ReminderDeliveryOutcome.Posted(now.plusSeconds(360))))
            assertNull(ledger.findByAlias(original.notificationAlias)!!.snoozedUntil)
        }
    }

    @Test fun stalePostActionsCannotAffectSnoozeRepostEvenWithFreshVersion() = runBlocking {
        fixture { store, ledger ->
            val original = post(ledger)
            assertNull(original.claimToken)
            assertNotNull(original.postIdentity)
            val snoozed = ledger.snooze(original.version, original.postToken!!, 5, now)!!
            val claim = ledger.claimDue(snoozed.effectiveDue, 1).single()
            assertEquals(claim.record.claimToken, claim.record.postToken)
            assertNotEquals(original.postToken, claim.record.postToken)
            // The durable token exists before notification posting, including process recovery.
            store.close()
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), store.databaseName)
            try {
                val recovered = SqliteReminderLedger(reopened)
                val interrupted = recovered.interruptedDeliveries().single()
                assertEquals(claim.record.postIdentity, interrupted.record.postIdentity)
                assertTrue(recovered.acknowledge(interrupted, ReminderDeliveryOutcome.Posted(snoozed.effectiveDue)))
                val current = recovered.findByAlias(original.notificationAlias)!!
                assertEquals(original.notificationAlias, current.notificationAlias)
                assertFalse(recovered.dismiss(current.version, original.postToken!!))
                assertNull(recovered.snooze(current.version, original.postToken!!, 5, now))
                val changed = candidate(ref = ref(end = ref().instanceEnd.plusSeconds(600)))
                recovered.reconcile(snapshot(listOf(changed)), setOf(calendar), emptyMap(), now)
                val edited = recovered.findByAlias(original.notificationAlias)!!
                assertEquals(current.postIdentity, edited.postIdentity)
                assertTrue(recovered.dismiss(edited.version, current.postToken!!))
            } finally { reopened.close() }
        }
    }

    @Test fun legacyPostedPayloadWithoutTokenRetainsHistoryButAuthorizesNoAction() = runBlocking {
        fixture { store, ledger ->
            val posted = post(ledger)
            store.writableDatabase.rawQuery("SELECT payload FROM reminder_ledger", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val payload = org.json.JSONObject(cursor.getString(0)).apply { remove("postToken") }
                store.writableDatabase.execSQL("UPDATE reminder_ledger SET payload = ?", arrayOf(payload.toString()))
            }
            val legacy = ledger.findByAlias(posted.notificationAlias)!!
            assertNull(legacy.postIdentity)
            assertEquals(posted.lastPostedAt, legacy.lastPostedAt)
            assertFalse(ledger.dismiss(legacy.version, posted.postToken!!))
            assertFalse(ledger.dismiss(legacy.version, ""))
            assertNull(ledger.snooze(legacy.version, posted.postToken!!, 5, now))
            assertNull(ledger.nextScheduled())
        }
    }

    @Test fun disablingCalendarCancelsPendingSnoozeButDoesNotResurrectDeliveredHistory() = runBlocking {
        fixture { _, ledger ->
            val posted = post(ledger)
            val snoozed = ledger.snooze(posted.version, posted.postToken!!, 5, now)!!
            ledger.reconcile(snapshot(), emptySet(), mapOf(posted.key to ReminderResolution.GoneOrIneligible), now)
            val cancelled = ledger.findByAlias(posted.notificationAlias)!!
            assertEquals(ReminderDeliveryState.CANCELLED, cancelled.state)
            assertEquals(snoozed.lastPostedAt, cancelled.lastPostedAt)
            assertNull(cancelled.snoozedUntil)
            ledger.reconcile(snapshot(listOf(candidate())), setOf(calendar), emptyMap(), now)
            assertNull(ledger.nextScheduled())
            assertEquals(ReminderDeliveryState.CANCELLED, ledger.findByAlias(posted.notificationAlias)!!.state)
        }
    }

    @Test fun disabledFirstDeliveryCanBeReenabledWithFreshAlias() = runBlocking {
        fixture { _, ledger ->
            val scheduled = schedule(ledger)
            ledger.reconcile(snapshot(), emptySet(), emptyMap(), now)
            assertEquals(ReminderDeliveryState.CANCELLED, ledger.findByAlias(scheduled.notificationAlias)!!.state)
            ledger.reconcile(snapshot(listOf(candidate())), setOf(calendar), emptyMap(), now)
            val restored = ledger.nextScheduled()!!
            assertNotEquals(scheduled.notificationAlias.nonce, restored.notificationAlias.nonce)
        }
    }

    @Test fun expireOnlyPendingWorkAndNeverInventOutcomeForInterruptedPosts() = runBlocking {
        fixture { _, ledger ->
            val overdue = candidate(ref(10, start = ref().instanceStart.minusSeconds(600)))
            val uncertain = candidate(ref(11, start = ref().instanceStart.minusSeconds(300)))
            val recent = candidate(ref(12))
            ledger.reconcile(snapshot(listOf(overdue, uncertain, recent)), setOf(calendar), emptyMap(), now)
            val claim = ledger.claimDue(now, 1).single()
            assertEquals(overdue.key, claim.record.key)
            assertTrue(ledger.acknowledge(claim, ReminderDeliveryOutcome.Uncertain))
            val changes = ledger.expireBefore(now)
            assertTrue(ledger.interruptedDeliveries().isNotEmpty())
            assertFalse(overdue.key in changes.cancelNotifications.map { ReminderKey.of(it.ref, 60) })
            assertEquals(recent.key, ledger.nextScheduled()!!.key)
        }
    }

    @Test fun dueOrderingPreservesNanosecondsAndExclusiveClaimBoundary() = runBlocking {
        fixture { _, ledger ->
            val later = candidate(ref(10, start = ref().instanceStart.plusNanos(1)))
            val earlier = candidate(ref(20))
            ledger.reconcile(snapshot(listOf(later, earlier)), setOf(calendar), emptyMap(), now)
            assertEquals(earlier.key, ledger.nextScheduled()!!.key)
            assertEquals(listOf(earlier.key), ledger.claimDue(now, 10).map { it.record.key })
            assertEquals(listOf(later.key), ledger.claimDue(now.plusNanos(1), 10).map { it.record.key })
        }
    }

    @Test fun reusedNonceFailsTransactionWithoutReplacingAnotherReminder() = runBlocking {
        fixture { store, _ ->
            val ledger = SqliteReminderLedger(store, nonceFactory = { "fixed-test-nonce" })
            val first = schedule(ledger)
            try {
                ledger.reconcile(snapshot(listOf(candidate(), candidate(ref(11)))), setOf(calendar), emptyMap(), now)
                fail("Reusing a consumed nonce must fail closed")
            } catch (_: IllegalStateException) { }
            assertEquals(first, ledger.nextScheduled())
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM reminder_ledger", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test fun invalidResolutionKeyRollsBackAllRetirementChanges() = runBlocking {
        fixture { _, ledger ->
            val posted = post(ledger)
            val replacement = ReminderCalendarIdentity("replacement-account")
            try {
                ledger.reconcile(snapshot(identity = replacement), setOf(calendar), mapOf(posted.key to ReminderResolution.Eligible(candidate(ref(99), identity = replacement))), now)
                fail("Mismatched resolution key must not partially reconcile")
            } catch (_: IllegalArgumentException) { }
            assertEquals(ReminderDeliveryState.POSTED, ledger.findByAlias(posted.notificationAlias)!!.state)
        }
    }

    @Test fun v1UpgradePreservesDraftSnapshotsAndEveryMutationJournalPhase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "v1-upgrade-${UUID.randomUUID()}.db"
        val old = object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE drafts (id TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL, updated_epoch INTEGER NOT NULL, updated_nano INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE edit_snapshots (token TEXT PRIMARY KEY, snapshot TEXT NOT NULL)")
                db.execSQL("CREATE TABLE mutation_journal (id TEXT PRIMARY KEY, signature TEXT NOT NULL, kind TEXT NOT NULL, phase TEXT NOT NULL, result_ref TEXT)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Not used")
        }
        val payload = "{\"invalid-field\":\"raw form remains unchanged\"}"
        old.writableDatabase.insertOrThrow("drafts", null, ContentValues().apply {
            put("id", "draft-v1"); put("version", 17); put("payload", payload); put("updated_at", now.toString())
            put("updated_epoch", now.epochSecond); put("updated_nano", now.nano)
        })
        old.writableDatabase.insertOrThrow("edit_snapshots", null, ContentValues().apply { put("token", "old-token"); put("snapshot", "opaque-source-snapshot") })
        val phases = listOf("PREPARED", "ATTEMPTED", "COMMITTED", "VERIFIED", "FAILED")
        phases.forEach { phase -> old.writableDatabase.insertOrThrow("mutation_journal", null, ContentValues().apply {
            put("id", phase); put("signature", "signature-$phase"); put("kind", "CREATE"); put("phase", phase)
            put("result_ref", if (phase == "COMMITTED" || phase == "VERIFIED") "opaque-known-ref" else null as String?)
        }) }
        old.close()
        val upgraded = CalendarLocalStore(context, name)
        try {
            assertEquals(2, upgraded.writableDatabase.version)
            assertEquals(DraftRecord("draft-v1", 17, payload, now), upgraded.load("draft-v1"))
            assertEquals("opaque-source-snapshot", upgraded.edit("old-token"))
            phases.forEach { phase ->
                val operation = upgraded.operation(phase)!!
                assertEquals(phase, operation.phase)
                assertEquals("signature-$phase", operation.signature)
                assertEquals(if (phase == "COMMITTED" || phase == "VERIFIED") "opaque-known-ref" else null, operation.resultRef)
            }
            upgraded.writableDatabase.rawQuery("PRAGMA synchronous", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            val record = schedule(SqliteReminderLedger(upgraded))
            val reopened = CalendarLocalStore(context, name)
            try {
                assertEquals(record, SqliteReminderLedger(reopened).nextScheduled())
                assertEquals(payload, reopened.load("draft-v1")!!.payload)
                assertEquals("ATTEMPTED", reopened.operation("ATTEMPTED")!!.phase)
            } finally { reopened.close() }
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
