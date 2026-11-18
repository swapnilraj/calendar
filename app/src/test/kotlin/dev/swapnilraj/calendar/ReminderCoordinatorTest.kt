package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReminderCoordinatorTest {
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val identity = ReminderCalendarIdentity("confirmed-calendar", "provider-calendar")
    private val id = CalendarId(2)
    private val config = ReminderConfiguration(true, setOf(id), ZoneOffset.UTC,
        expectedCalendarIdentities = mapOf(id to identity))
    private val permissions = ReminderPermissionState(true, true, true, true, true)
    private fun candidate(eventId: Long = 7, start: Instant = now, minutes: Int = 0,
        calendarId: CalendarId = id, sourceIdentity: ReminderCalendarIdentity = identity): ReminderCandidate {
        val ref = EventRef(calendarId, eventId, start, start.plusSeconds(3600))
        val occurrence = EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic event", 0xff112233.toInt())
        return ReminderCandidate(ReminderKey.of(ref, minutes), sourceIdentity, occurrence,
            dueInstant(occurrence.time, minutes, ZoneOffset.UTC), "event-$eventId")
    }
    private fun record(candidate: ReminderCandidate, state: ReminderDeliveryState = ReminderDeliveryState.SCHEDULED,
        nonce: String = "nonce-${candidate.key.masterOrEventId}") = ReminderRecord(candidate.key, candidate.sourceCalendarIdentity,
        candidate.occurrence.ref, candidate.occurrence.time is EventTime.AllDay, candidate.due, candidate.due,
        ReminderNotificationAlias(candidate.occurrence.ref, candidate.due, nonce), state, 1, 1,
        lastPostedAt = if (state == ReminderDeliveryState.POSTED || state == ReminderDeliveryState.DISMISSED) now else null,
        claimToken = if (state == ReminderDeliveryState.POSTING || state == ReminderDeliveryState.UNCERTAIN) "interrupted" else null,
        sourceEventUidFingerprint = candidate.sourceEventUidFingerprint,
        postToken = if (state in setOf(ReminderDeliveryState.POSTED, ReminderDeliveryState.DISMISSED,
            ReminderDeliveryState.POSTING, ReminderDeliveryState.UNCERTAIN)) "initial-$nonce" else null)

    private class Source : ReminderSource {
        val candidates = mutableMapOf<ReminderKey, ReminderCandidate>()
        val resolutions = mutableMapOf<ReminderKey, ReminderResolution>()
        val calls = mutableListOf<Set<ReminderKey>>()
        var window: InstantBounds? = null
        var unavailable = false
        var permissionRequired = false
        var omit = emptySet<ReminderKey>()
        var cancelResolution = false
        var observedIdentities: Map<CalendarId, ReminderCalendarIdentity>? = null
        override suspend fun loadDueWindow(window: InstantBounds, configuration: ReminderConfiguration): ReminderSourceResult {
            this.window = window
            if (permissionRequired) return ReminderSourceResult.PermissionRequired
            if (unavailable) return ReminderSourceResult.Unavailable
            return ReminderSourceResult.Ready(ReminderSourceSnapshot(window,
                candidates.values.filter { it.due in window }, configuration.enabledCalendars,
                observedIdentities ?: configuration.expectedCalendarIdentities))
        }
        override suspend fun resolve(keys: Set<ReminderKey>, configuration: ReminderConfiguration): ReminderResolutionResult {
            calls.add(keys)
            if (cancelResolution) throw CancellationException("cancelled before posting")
            if (permissionRequired) return ReminderResolutionResult.PermissionRequired
            if (unavailable) return ReminderResolutionResult.Unavailable
            return ReminderResolutionResult.Ready(keys.filterNot { it in omit }.associateWith {
                resolutions[it] ?: candidates[it]?.let(ReminderResolution::Eligible) ?: ReminderResolution.GoneOrIneligible
            })
        }
    }

    /** State-machine fixture only. SQLite transaction/durability coverage belongs to real-ledger tests. */
    private class Ledger(initial: List<ReminderRecord> = emptyList()) : ReminderLedger {
        val rows = initial.associateBy { it.key }.toMutableMap()
        var lastSnapshot: ReminderSourceSnapshot? = null
        var lastResolutions = emptyMap<ReminderKey, ReminderResolution>()
        var failPostAckOnce = false
        private var token = 0
        override suspend fun trackedForRevalidation(window: InstantBounds): List<ReminderRecord> = rows.values.filter {
            it.state in setOf(ReminderDeliveryState.SCHEDULED, ReminderDeliveryState.SNOOZED, ReminderDeliveryState.DEFERRED,
                ReminderDeliveryState.POSTING, ReminderDeliveryState.UNCERTAIN) || it.plannedDue in window
        }
        override suspend fun reconcile(snapshot: ReminderSourceSnapshot, enabledCalendars: Set<CalendarId>,
            resolutions: Map<ReminderKey, ReminderResolution>, now: Instant): ReminderPlanChanges {
            lastSnapshot = snapshot; lastResolutions = resolutions
            val disabled = rows.values.filter { it.key.calendarId !in enabledCalendars }
            for (row in disabled) {
                if (row.state in setOf(ReminderDeliveryState.SCHEDULED, ReminderDeliveryState.SNOOZED,
                        ReminderDeliveryState.DEFERRED)) {
                    rows[row.key] = row.copy(state = ReminderDeliveryState.CANCELLED, revision = row.revision + 1)
                }
            }
            val fresh = snapshot.candidates + resolutions.values.mapNotNull { (it as? ReminderResolution.Eligible)?.candidate }
            for (candidate in fresh.distinctBy { it.key }) {
                val old = rows[candidate.key]
                if (old == null) rows[candidate.key] = ReminderRecord(candidate.key, candidate.sourceCalendarIdentity,
                    candidate.occurrence.ref, candidate.occurrence.time is EventTime.AllDay, candidate.due, candidate.due,
                    ReminderNotificationAlias(candidate.occurrence.ref, candidate.due, "discovered-${++token}"),
                    ReminderDeliveryState.SCHEDULED, 1, 1, sourceEventUidFingerprint = candidate.sourceEventUidFingerprint)
                else if (old.state !in setOf(ReminderDeliveryState.POSTING, ReminderDeliveryState.UNCERTAIN)) {
                    rows[old.key] = old.copy(currentRef = candidate.occurrence.ref, plannedDue = candidate.due,
                        effectiveDue = old.snoozedUntil ?: candidate.due)
                }
            }
            return ReminderPlanChanges(disabled.map { it.notificationAlias }.toSet())
        }
        override suspend fun claimDue(now: Instant, limit: Int): List<ReminderClaim> = rows.values.filter {
            it.state in setOf(ReminderDeliveryState.SCHEDULED, ReminderDeliveryState.SNOOZED) && it.effectiveDue <= now
        }.sortedBy { it.effectiveDue }.take(limit).map {
            val freshToken = "claim-${++token}"
            val claimed = it.copy(state = ReminderDeliveryState.POSTING, revision = it.revision + 1,
                claimToken = freshToken, postToken = freshToken)
            rows[it.key] = claimed; ReminderClaim(claimed)
        }
        override suspend fun acknowledge(claim: ReminderClaim, outcome: ReminderDeliveryOutcome): Boolean {
            val old = rows[claim.record.key] ?: return false
            if (old.version != claim.record.version || old.claimToken != claim.record.claimToken || old.postToken != claim.record.postToken) return false
            if (outcome is ReminderDeliveryOutcome.Posted && failPostAckOnce) {
                failPostAckOnce = false
                rows[old.key] = old.copy(revision = old.revision + 1,
                    currentRef = old.currentRef.copy(instanceEnd = old.currentRef.instanceEnd.plusSeconds(60)))
                return false
            }
            val state = when (outcome) {
                is ReminderDeliveryOutcome.Posted -> ReminderDeliveryState.POSTED
                ReminderDeliveryOutcome.Deferred -> ReminderDeliveryState.DEFERRED
                ReminderDeliveryOutcome.Cancelled -> ReminderDeliveryState.CANCELLED
                ReminderDeliveryOutcome.Expired -> ReminderDeliveryState.EXPIRED
                ReminderDeliveryOutcome.Uncertain -> ReminderDeliveryState.UNCERTAIN
            }
            rows[old.key] = old.copy(state = state, revision = old.revision + 1,
                lastPostedAt = (outcome as? ReminderDeliveryOutcome.Posted)?.at ?: old.lastPostedAt,
                claimToken = if (state == ReminderDeliveryState.UNCERTAIN) old.claimToken else null)
            return true
        }
        override suspend fun findByAlias(alias: ReminderNotificationAlias) = rows.values.find { it.notificationAlias == alias }
        override suspend fun snooze(expected: ReminderVersion, postToken: String, minutes: Int, now: Instant): ReminderRecord? {
            val old = rows[expected.key] ?: return null
            if (old.version != expected || old.postToken != postToken || old.state != ReminderDeliveryState.POSTED || minutes !in listOf(5, 10, 30)) return null
            val until = now.plusSeconds(minutes * 60L)
            return old.copy(state = ReminderDeliveryState.SNOOZED, revision = old.revision + 1,
                snoozedUntil = until, effectiveDue = until).also { rows[old.key] = it }
        }
        override suspend fun dismiss(expected: ReminderVersion, postToken: String): Boolean {
            val old = rows[expected.key] ?: return false
            if (old.version != expected || old.postToken != postToken || old.state != ReminderDeliveryState.POSTED) return false
            rows[old.key] = old.copy(state = ReminderDeliveryState.DISMISSED, revision = old.revision + 1)
            return true
        }
        override suspend fun expireBefore(cutoff: Instant): ReminderPlanChanges {
            val expired = rows.values.filter { it.state in setOf(ReminderDeliveryState.SCHEDULED,
                ReminderDeliveryState.SNOOZED, ReminderDeliveryState.DEFERRED) && it.effectiveDue < cutoff }
            expired.forEach { rows[it.key] = it.copy(state = ReminderDeliveryState.EXPIRED, revision = it.revision + 1) }
            return ReminderPlanChanges(expired.map { it.notificationAlias }.toSet())
        }
        override suspend fun nextScheduled() = rows.values.filter { it.state in setOf(ReminderDeliveryState.SCHEDULED,
            ReminderDeliveryState.SNOOZED) }.minByOrNull { it.effectiveDue }
        override suspend fun interruptedDeliveries() = rows.values.filter { it.state in setOf(ReminderDeliveryState.POSTING,
            ReminderDeliveryState.UNCERTAIN) }.map(::ReminderClaim)
    }

    private class Notifications : ReminderNotificationPort {
        val posts = mutableListOf<ReminderNotificationAlias>()
        val active = mutableSetOf<ReminderPostIdentity>()
        val postIdentities = mutableListOf<ReminderPostIdentity>()
        val cancelled = mutableListOf<ReminderNotificationAlias>()
        var clears = 0
        var result = ReminderPostResult.POSTED
        override fun post(candidate: ReminderCandidate, identity: ReminderPostIdentity): ReminderPostResult {
            posts.add(identity.alias); postIdentities.add(identity)
            if (result == ReminderPostResult.POSTED) {
                active.removeAll { it.alias == identity.alias }; active.add(identity)
            }
            return result
        }
        override fun isActive(identity: ReminderPostIdentity) = identity in active
        override fun cancel(alias: ReminderNotificationAlias) { cancelled.add(alias); active.removeAll { it.alias == alias } }
        override fun cancelAllOwn() { clears++; active.clear() }
    }
    private class Alarms : ReminderAlarmPort {
        var next: ReminderAlarmRequest? = null
        var precision = AlarmSchedulingResult.EXACT
        override fun replaceNext(alarm: ReminderAlarmRequest?): AlarmSchedulingResult {
            next = alarm; return if (alarm == null) AlarmSchedulingResult.CANCELLED else precision
        }
    }
    private fun engine(source: Source, ledger: Ledger, notifications: Notifications, alarms: Alarms,
        configuration: ReminderConfiguration = config, permission: ReminderPermissionState = permissions,
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC)) =
        ReminderCoordinator(source, ledger, alarms, notifications, { configuration }, { permission }, clock)

    @Test fun discoversLongAdvanceReminderAndPostsOnlyOnce() = runBlocking {
        val reminder = candidate(start = now.plus(Duration.ofDays(365)), minutes = 365 * 24 * 60)
        val source = Source().apply { candidates[reminder.key] = reminder }
        val ledger = Ledger(); val sink = Notifications(); val alarms = Alarms()
        val coordinator = engine(source, ledger, sink, alarms)
        assertEquals(1, coordinator.reconcile().posted)
        assertEquals(now.minus(Duration.ofHours(24)), source.window!!.start)
        assertEquals(now.plus(Duration.ofDays(90)), source.window!!.endExclusive)
        coordinator.reconcile()
        assertEquals(1, sink.posts.size)
        assertEquals(ReminderDeliveryState.POSTED, ledger.rows[reminder.key]!!.state)
    }

    @Test fun trackedRevalidationIsBatchedAndMissingResultsNeverBecomeGone() = runBlocking {
        val reminders = (1L..3L).map { candidate(it, now.plusSeconds(600)) }
        val source = Source().apply { reminders.forEach { candidates[it.key] = it }; omit = setOf(reminders.last().key) }
        val ledger = Ledger(reminders.map { record(it) }); val sink = Notifications()
        val result = engine(source, ledger, sink, Alarms()).reconcile()
        assertEquals(1, source.calls.size)
        assertEquals(reminders.map { it.key }.toSet(), source.calls.single())
        assertTrue(ledger.lastResolutions[reminders.last().key] is ReminderResolution.Deferred)
        assertEquals(ReminderDeliveryState.SCHEDULED, ledger.rows[reminders.last().key]!!.state)
        assertEquals(ReminderCycleStatus.DEGRADED, result.status)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun zoneRecomputationOutsideWindowDoesNotReplayDeliveredHistory() = runBlocking {
        val dates = CalendarDateRange.day(LocalDate.of(2026, 11, 18))
        val start = dates.start.atStartOfDay(ZoneOffset.UTC).toInstant()
        val ref = EventRef(id, 7, start, dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant())
        val occurrence = EventOccurrence(ref, EventTime.AllDay(dates), "Synthetic all-day", 0xff112233.toInt())
        val old = ReminderCandidate(ReminderKey.of(ref, 900), identity, occurrence,
            dueInstant(occurrence.time, 900, ZoneId.of("Pacific/Pago_Pago")))
        val posted = record(old, ReminderDeliveryState.POSTED).copy(lastPostedAt = old.due)
        val newZone = ZoneId.of("Pacific/Kiritimati")
        val movedDue = old.copy(due = dueInstant(occurrence.time, 900, newZone))
        assertTrue(old.due >= now.minus(Duration.ofHours(24)))
        assertTrue(movedDue.due < now.minus(Duration.ofHours(24)))
        val source = Source().apply { resolutions[old.key] = ReminderResolution.Eligible(movedDue) }
        val ledger = Ledger(listOf(posted)); val sink = Notifications()
        engine(source, ledger, sink, Alarms(), configuration = config.copy(deviceZone = newZone)).reconcile()
        assertTrue(ledger.lastSnapshot!!.candidates.isEmpty())
        assertEquals(ReminderResolution.Eligible(movedDue), ledger.lastResolutions[old.key])
        assertEquals(ReminderDeliveryState.POSTED, ledger.rows[old.key]!!.state)
        assertEquals(posted.notificationAlias, ledger.rows[old.key]!!.notificationAlias)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun transientFailureDefersWithoutCancellingThePlan() = runBlocking {
        val reminder = candidate(); val ledger = Ledger(listOf(record(reminder)))
        val source = Source().apply { unavailable = true }; val sink = Notifications()
        val result = engine(source, ledger, sink, Alarms()).reconcile()
        assertEquals(ReminderCycleStatus.UNAVAILABLE, result.status)
        assertEquals(ReminderDeliveryState.DEFERRED, ledger.rows[reminder.key]!!.state)
        assertTrue(sink.posts.isEmpty()); assertTrue(sink.cancelled.isEmpty())
    }

    @Test fun disabledCalendarCleanupStillAppliesWhenDiscoveryIsUnavailable() = runBlocking {
        val enabled = record(candidate(7, now.plusSeconds(1200)))
        val disabledId = CalendarId(3)
        val disabledIdentity = ReminderCalendarIdentity("disabled-calendar")
        val disabledAlarm = record(candidate(8, now.plusSeconds(600), calendarId = disabledId,
            sourceIdentity = disabledIdentity))
        val disabledPosted = record(candidate(9, calendarId = disabledId, sourceIdentity = disabledIdentity),
            ReminderDeliveryState.POSTED)
        val ledger = Ledger(listOf(enabled, disabledAlarm, disabledPosted))
        val source = Source().apply { unavailable = true }
        val sink = Notifications().apply { active.add(disabledPosted.postIdentity!!) }
        val alarms = Alarms().apply { next = ReminderAlarmRequest(disabledAlarm.effectiveDue, disabledAlarm.version) }
        val result = engine(source, ledger, sink, alarms).reconcile()
        assertEquals(ReminderCycleStatus.UNAVAILABLE, result.status)
        assertNotNull(ledger.lastSnapshot)
        assertTrue(ledger.lastSnapshot!!.candidates.isEmpty())
        assertTrue(ledger.lastSnapshot!!.completeCalendars.isEmpty())
        assertTrue(ledger.lastResolutions[enabled.key] is ReminderResolution.Deferred)
        assertEquals(ReminderDeliveryState.CANCELLED, ledger.rows[disabledAlarm.key]!!.state)
        assertEquals(ReminderDeliveryState.SCHEDULED, ledger.rows[enabled.key]!!.state)
        assertEquals(ReminderDeliveryState.POSTED, ledger.rows[disabledPosted.key]!!.state)
        assertTrue(sink.active.none { it.alias == disabledPosted.notificationAlias })
        assertEquals(enabled.version, alarms.next!!.version)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun interruptedActivePostIsAcknowledgedWithoutReposting() = runBlocking {
        val reminder = candidate(); val interrupted = record(reminder, ReminderDeliveryState.POSTING)
        val source = Source().apply { candidates[reminder.key] = reminder }
        val ledger = Ledger(listOf(interrupted)); val sink = Notifications().apply { active.add(interrupted.postIdentity!!) }
        engine(source, ledger, sink, Alarms()).reconcile()
        assertEquals(ReminderDeliveryState.POSTED, ledger.rows[reminder.key]!!.state)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun interruptedAbsentPostRemainsUncertainWithoutAudibleReplay() = runBlocking {
        val reminder = candidate(); val ledger = Ledger(listOf(record(reminder, ReminderDeliveryState.POSTING)))
        val source = Source().apply { candidates[reminder.key] = reminder }; val sink = Notifications()
        val coordinator = engine(source, ledger, sink, Alarms())
        assertEquals(1, coordinator.reconcile().uncertain)
        coordinator.reconcile()
        assertEquals(ReminderDeliveryState.UNCERTAIN, ledger.rows[reminder.key]!!.state)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun failedPostAckDoesNotCancelSameAliasUnderNewRevision() = runBlocking {
        val reminder = candidate(); val source = Source().apply { candidates[reminder.key] = reminder }
        val ledger = Ledger(listOf(record(reminder))).apply { failPostAckOnce = true }
        val sink = Notifications(); val result = engine(source, ledger, sink, Alarms()).reconcile()
        assertEquals(ReminderCycleStatus.DEGRADED, result.status)
        assertEquals(1, sink.posts.size); assertTrue(sink.cancelled.isEmpty())
        assertEquals(ReminderDeliveryState.POSTING, ledger.rows[reminder.key]!!.state)
    }

    @Test fun privateActionCanRecoverPostingThenSnoozeAndRejectDuplicateAction() = runBlocking {
        val reminder = candidate(); val interrupted = record(reminder, ReminderDeliveryState.UNCERTAIN)
        val source = Source().apply { candidates[reminder.key] = reminder }
        val ledger = Ledger(listOf(interrupted)); val sink = Notifications(); val alarms = Alarms()
        val coordinator = engine(source, ledger, sink, alarms)
        assertEquals(ReminderActionResult.APPLIED, coordinator.snooze(interrupted.postIdentity, 10))
        assertEquals(now.plusSeconds(600), ledger.rows[reminder.key]!!.snoozedUntil)
        assertEquals(now.plusSeconds(600), alarms.next!!.at)
        assertEquals(ReminderActionResult.IGNORED, coordinator.snooze(interrupted.postIdentity, 5))
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun privateDismissalOfInterruptedPostDoesNotResurrectIt() = runBlocking {
        val reminder = candidate(); val old = record(reminder, ReminderDeliveryState.POSTING)
        val ledger = Ledger(listOf(old)); val sink = Notifications()
        val coordinator = engine(Source(), ledger, sink, Alarms())
        assertEquals(ReminderActionResult.APPLIED, coordinator.dismiss(old.postIdentity))
        assertEquals(ReminderDeliveryState.DISMISSED, ledger.rows[reminder.key]!!.state)
        assertEquals(ReminderActionResult.IGNORED, coordinator.dismiss(old.postIdentity))
    }

    @Test fun staleAccountSnoozeDoesNotPromoteAnInterruptedPostInANewEnabledNamespace() = runBlocking {
        val reminder = candidate(); val old = record(reminder, ReminderDeliveryState.POSTING)
        val ledger = Ledger(listOf(old)); val source = Source(); val sink = Notifications().apply { active.add(old.postIdentity!!) }
        val replacement = ReminderCalendarIdentity("replacement-account")
        val coordinator = engine(source, ledger, sink, Alarms(),
            configuration = config.copy(expectedCalendarIdentities = mapOf(id to replacement)))
        assertEquals(ReminderActionResult.IGNORED, coordinator.snooze(old.postIdentity, 5))
        assertEquals(ReminderDeliveryState.POSTING, ledger.rows[old.key]!!.state)
        assertNull(ledger.rows[old.key]!!.snoozedUntil)
        assertTrue(sink.active.none { it.alias == old.notificationAlias })
        assertTrue(source.calls.isEmpty())
    }

    @Test fun permissionLossClearsOlderPrivateAlertsOutsidePlanningWindow() = runBlocking {
        val old = record(candidate(start = now.minus(Duration.ofDays(7))), ReminderDeliveryState.POSTED)
        val sink = Notifications().apply { active.add(old.postIdentity!!) }; val source = Source(); val alarms = Alarms()
        val result = engine(source, Ledger(listOf(old)), sink, alarms,
            permission = permissions.copy(calendarAccess = false)).reconcile()
        assertEquals(ReminderCycleStatus.PERMISSION_REQUIRED, result.status)
        assertEquals(1, sink.clears); assertTrue(sink.active.isEmpty()); assertNull(alarms.next)
        assertTrue(source.calls.isEmpty())
    }

    @Test fun noStoredReminderCandidateDoesNotInventADefaultAlert() = runBlocking {
        val sink = Notifications(); val ledger = Ledger()
        engine(Source(), ledger, sink, Alarms()).reconcile()
        assertTrue(ledger.rows.isEmpty()); assertTrue(sink.posts.isEmpty())
    }

    @Test fun oldOverdueWorkExpiresButRecentOverdueWorkCatchesUp() = runBlocking {
        val recent = candidate(7, now.minusSeconds(600)); val old = candidate(8, now.minus(Duration.ofHours(25)))
        val source = Source().apply { candidates[recent.key] = recent; candidates[old.key] = old }
        val ledger = Ledger(listOf(record(recent), record(old))); val sink = Notifications()
        engine(source, ledger, sink, Alarms()).reconcile()
        assertEquals(ReminderDeliveryState.EXPIRED, ledger.rows[old.key]!!.state)
        assertEquals(ReminderDeliveryState.POSTED, ledger.rows[recent.key]!!.state)
        assertEquals(1, sink.posts.size)
    }

    @Test fun cancelledBeforePostIsSafelyDeferred() = runBlocking {
        val reminder = candidate(); val source = Source().apply { candidates[reminder.key] = reminder; cancelResolution = true }
        val ledger = Ledger(); val sink = Notifications()
        try { engine(source, ledger, sink, Alarms()).reconcile(); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(ReminderDeliveryState.DEFERRED, ledger.rows[reminder.key]!!.state)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun inexactSchedulingIsExplicitlyDegraded() = runBlocking {
        val reminder = candidate(start = now.plusSeconds(600))
        val source = Source().apply { candidates[reminder.key] = reminder }; val alarms = Alarms().apply { precision = AlarmSchedulingResult.INEXACT }
        val result = engine(source, Ledger(), Notifications(), alarms).reconcile()
        assertEquals(AlarmSchedulingResult.INEXACT, result.alarm)
        assertEquals(ReminderCycleStatus.DEGRADED, result.status)
    }

    @Test fun replacementNamespaceCannotBeAutoEnabledByTheSameNumericCalendarId() = runBlocking {
        val replacementIdentity = ReminderCalendarIdentity("replacement-account", "other-calendar")
        val reminder = candidate().copy(sourceCalendarIdentity = replacementIdentity)
        val source = Source().apply {
            candidates[reminder.key] = reminder
            observedIdentities = mapOf(id to replacementIdentity)
        }
        val ledger = Ledger(); val sink = Notifications()
        val result = engine(source, ledger, sink, Alarms()).reconcile()
        assertEquals(ReminderCycleStatus.DEGRADED, result.status)
        assertTrue(ledger.lastSnapshot!!.candidates.isEmpty())
        assertEquals(replacementIdentity, ledger.lastSnapshot!!.observedCalendarIdentities[id])
        assertTrue(result.sourceFailures.any { it.problem == ReminderSourceProblem.IDENTITY_MISMATCH })
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun missingPinnedIdentityDoesNotSilentlyEnableAStoredCalendarPreference() = runBlocking {
        val reminder = candidate(); val source = Source().apply {
            candidates[reminder.key] = reminder; observedIdentities = mapOf(id to identity)
        }
        val ledger = Ledger(); val sink = Notifications()
        val result = engine(source, ledger, sink, Alarms(), configuration = config.copy(expectedCalendarIdentities = emptyMap())).reconcile()
        assertTrue(result.sourceFailures.any { it.problem == ReminderSourceProblem.MISSING_EXPECTED_IDENTITY })
        assertTrue(ledger.rows.isEmpty()); assertTrue(sink.posts.isEmpty())
    }

    @Test fun explicitDisabledSettingDoesNotReadSourceOrDeliver() = runBlocking {
        val source = Source(); val sink = Notifications(); val alarms = Alarms()
        val result = engine(source, Ledger(), sink, alarms, configuration = config.copy(enabled = false)).reconcile()
        assertEquals(ReminderCycleStatus.DISABLED, result.status)
        assertTrue(source.calls.isEmpty()); assertNull(source.window)
        assertEquals(1, sink.clears); assertNull(alarms.next)
    }

    @Test fun snoozedAlarmUsesElapsedDeadlineAndKeepsItsNonceOnDelivery() = runBlocking {
        val reminder = candidate(); val posted = record(reminder, ReminderDeliveryState.POSTED)
        var current = now
        val clock = object : Clock() {
            override fun instant() = current
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = Clock.fixed(current, zone)
        }
        val ledger = Ledger(listOf(posted)); val source = Source().apply { candidates[reminder.key] = reminder }
        val sink = Notifications(); val coordinator = engine(source, ledger, sink, Alarms(), clock = clock)
        assertEquals(ReminderActionResult.APPLIED, coordinator.snooze(posted.postIdentity, 5))
        current = current.plusSeconds(300)
        coordinator.reconcile()
        assertEquals(listOf(posted.notificationAlias), sink.posts)
        assertEquals(ReminderDeliveryState.POSTED, ledger.rows[reminder.key]!!.state)
    }

    @Test fun retainedOldActionsCannotDismissOrResnoozeANewerPostOfTheSameGeneration() = runBlocking {
        val reminder = candidate(); val original = record(reminder, ReminderDeliveryState.POSTED)
        val oldAction = original.postIdentity!!
        var current = now
        val clock = object : Clock() {
            override fun instant() = current
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = Clock.fixed(current, zone)
        }
        val ledger = Ledger(listOf(original)); val source = Source().apply { candidates[reminder.key] = reminder }
        val sink = Notifications().apply { active.add(oldAction) }
        val coordinator = engine(source, ledger, sink, Alarms(), clock = clock)
        assertEquals(ReminderActionResult.APPLIED, coordinator.snooze(oldAction, 5))
        current = current.plusSeconds(300)
        coordinator.reconcile()
        val reposted = ledger.rows[reminder.key]!!
        val newAction = reposted.postIdentity!!
        assertEquals(oldAction.alias, newAction.alias)
        assertNotEquals(oldAction.token, newAction.token)
        assertEquals(ReminderActionResult.IGNORED, coordinator.dismiss(oldAction))
        assertEquals(ReminderActionResult.IGNORED, coordinator.snooze(oldAction, 30))
        assertEquals(reposted.version, ledger.rows[reminder.key]!!.version)
        assertEquals(ReminderDeliveryState.POSTED, ledger.rows[reminder.key]!!.state)
        assertTrue(newAction in sink.active)
        assertEquals(ReminderActionResult.APPLIED, coordinator.snooze(newAction, 10))
    }

    @Test fun oldSameTagActiveNotificationCannotConfirmANewerInterruptedPost() = runBlocking {
        val reminder = candidate()
        val interrupted = record(reminder, ReminderDeliveryState.POSTING).copy(postToken = "new-post")
        val oldActive = ReminderPostIdentity(interrupted.notificationAlias, "old-post")
        val source = Source().apply { candidates[reminder.key] = reminder }
        val ledger = Ledger(listOf(interrupted)); val sink = Notifications().apply { active.add(oldActive) }
        val result = engine(source, ledger, sink, Alarms()).reconcile()
        assertEquals(1, result.uncertain)
        assertEquals(ReminderDeliveryState.UNCERTAIN, ledger.rows[reminder.key]!!.state)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun missingLegacyTokenCannotAuthorizeAnActionOrConfirmAnInterruptedPost() = runBlocking {
        val reminder = candidate(); val legacy = record(reminder, ReminderDeliveryState.POSTING).copy(postToken = null)
        val source = Source().apply { candidates[reminder.key] = reminder }
        val ledger = Ledger(listOf(legacy)); val sink = Notifications()
        val coordinator = engine(source, ledger, sink, Alarms())
        assertEquals(ReminderActionResult.IGNORED, coordinator.snooze(null, 5))
        assertEquals(ReminderActionResult.IGNORED, coordinator.dismiss(null))
        assertEquals(ReminderDeliveryState.POSTING, ledger.rows[reminder.key]!!.state)
        coordinator.reconcile()
        assertEquals(ReminderDeliveryState.UNCERTAIN, ledger.rows[reminder.key]!!.state)
        assertNull(ledger.rows[reminder.key]!!.postToken)
        assertTrue(sink.posts.isEmpty())
    }

    @Test fun metadataRevisionChangeDoesNotInvalidateAStillVisibleCurrentPostAction() = runBlocking {
        val reminder = candidate(); val old = record(reminder, ReminderDeliveryState.POSTED)
        val updated = old.copy(revision = old.revision + 5,
            currentRef = old.currentRef.copy(instanceEnd = old.currentRef.instanceEnd.plusSeconds(300)))
        val ledger = Ledger(listOf(updated)); val sink = Notifications().apply { active.add(old.postIdentity!!) }
        val coordinator = engine(Source(), ledger, sink, Alarms())
        assertEquals(old.postIdentity, updated.postIdentity)
        assertEquals(ReminderActionResult.APPLIED, coordinator.dismiss(old.postIdentity))
        assertEquals(ReminderDeliveryState.DISMISSED, ledger.rows[old.key]!!.state)
    }
}
