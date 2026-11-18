package dev.swapnilraj.calendar.android.reminders

import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ReminderCycleStatus { READY, DEGRADED, DISABLED, PERMISSION_REQUIRED, UNAVAILABLE }
data class ReminderCycleResult(
    val status: ReminderCycleStatus,
    val alarm: AlarmSchedulingResult,
    val posted: Int = 0,
    val deferred: Int = 0,
    val uncertain: Int = 0,
    val sourceFailures: List<ReminderSourceFailure> = emptyList(),
)
enum class ReminderActionResult { APPLIED, IGNORED, PERMISSION_REQUIRED, SOURCE_UNAVAILABLE }

/** One app-owned instance serializes receivers/jobs/UI. Durable CAS remains the cross-instance guard.
 * No provider writes, default selection, or app/manifest wiring occurs here.
 */
class ReminderCoordinator(
    private val source: ReminderSource,
    private val ledger: ReminderLedger,
    private val alarms: ReminderAlarmPort,
    private val notifications: ReminderNotificationPort,
    private val configuration: suspend () -> ReminderConfiguration,
    private val permissions: () -> ReminderPermissionState,
    private val clock: Clock = Clock.systemUTC(),
    private val horizon: Duration = Duration.ofDays(90),
    private val catchUp: Duration = Duration.ofHours(24),
    private val batchSize: Int = 32,
) {
    private val mutex = Mutex()
    init { require(!horizon.isNegative && !horizon.isZero && !catchUp.isNegative && batchSize > 0) }

    suspend fun reconcile(): ReminderCycleResult = mutex.withLock {
        try { cycle() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReminderCycleResult(ReminderCycleStatus.UNAVAILABLE, cancelAlarm()) }
    }

    /** A stale wake-up may reconcile current state, but can never force delivery of its old key. */
    suspend fun onAlarm(@Suppress("UNUSED_PARAMETER") expected: ReminderVersion): ReminderCycleResult = reconcile()

    private class Counts { var posted = 0; var deferred = 0; var uncertain = 0; var permissionRequired = false }
    private data class Resolved(val values: Map<ReminderKey, ReminderResolution>, val permissionRequired: Boolean = false)

    private suspend fun cycle(): ReminderCycleResult {
        val config = configuration()
        val now = clock.instant()
        val window = InstantBounds(now.minus(catchUp), now.plus(horizon))
        if (!config.enabled) {
            val empty = ReminderSourceSnapshot(window, emptyList(), emptySet(), emptyMap())
            ledger.reconcile(empty, emptySet(), emptyMap(), now)
            notifications.cancelAllOwn()
            return ReminderCycleResult(ReminderCycleStatus.DISABLED, cancelAlarm())
        }
        if (!canDeliver()) return permissionRequired()
        val tracked = ledger.trackedForRevalidation(window).filter { it.key.calendarId in config.enabledCalendars }
        val resolved = resolve(tracked.map { it.key }.toSet(), config)
        if (resolved.permissionRequired) return permissionRequired()
        val loaded = source.loadDueWindow(window, config)
        if (loaded == ReminderSourceResult.PermissionRequired) return permissionRequired()
        val snapshot = (loaded as? ReminderSourceResult.Ready)?.snapshot?.takeIf { it.window == window }
            ?.let { safeSnapshot(it, config) }
        val counts = Counts()
        // Discovery failure cannot erase plans, but confirmed configuration and per-key resolution
        // still apply. In particular, a disabled calendar must not keep its old alarm/active alert.
        val reconciliation = snapshot ?: ReminderSourceSnapshot(window, emptyList(), emptySet(), emptyMap())
        ledger.reconcile(reconciliation, config.enabledCalendars, resolved.values, now)
            .cancelNotifications.forEach(notifications::cancel)
        ledger.expireBefore(now.minus(catchUp)).cancelNotifications.forEach(notifications::cancel)
        recover(ledger.interruptedDeliveries(), resolved.values, config, counts)
        while (true) {
            if (!canDeliver()) return permissionRequired(counts)
            val claims = ledger.claimDue(clock.instant(), batchSize)
            if (claims.isEmpty()) break
            deliver(claims, config, counts)
            if (counts.permissionRequired) return permissionRequired(counts)
        }
        val alarm = armNext()
        val status = when {
            snapshot == null -> ReminderCycleStatus.UNAVAILABLE
            snapshot.failures.isNotEmpty() || counts.deferred > 0 || counts.uncertain > 0 ||
                resolved.values.values.any { it is ReminderResolution.Deferred } ||
                alarm in setOf(AlarmSchedulingResult.INEXACT, AlarmSchedulingResult.UNAVAILABLE) -> ReminderCycleStatus.DEGRADED
            else -> ReminderCycleStatus.READY
        }
        return ReminderCycleResult(status,
            alarm, counts.posted, counts.deferred, counts.uncertain, snapshot?.failures.orEmpty())
    }

    /** A broken source cannot bypass explicit pinned reminder-calendar identities. */
    private fun safeSnapshot(snapshot: ReminderSourceSnapshot, config: ReminderConfiguration): ReminderSourceSnapshot {
        val accepted = snapshot.candidates.filter { candidate ->
            candidate.key.calendarId in config.enabledCalendars &&
                config.expectedCalendarIdentities[candidate.key.calendarId] == candidate.sourceCalendarIdentity
        }
        val rejected = snapshot.candidates.filterNot { it in accepted }.map {
            ReminderSourceFailure(it.key.calendarId, if (it.key.calendarId !in config.expectedCalendarIdentities)
                ReminderSourceProblem.MISSING_EXPECTED_IDENTITY else ReminderSourceProblem.IDENTITY_MISMATCH)
        }
        val failures = (snapshot.failures + rejected).distinct()
        return snapshot.copy(candidates = accepted, failures = failures,
            completeCalendars = snapshot.completeCalendars.filter { id -> id in config.enabledCalendars &&
                failures.none { it.calendarId == id } && config.expectedCalendarIdentities[id] == snapshot.observedCalendarIdentities[id] }.toSet())
    }

    private suspend fun resolve(keys: Set<ReminderKey>, config: ReminderConfiguration): Resolved {
        if (keys.isEmpty()) return Resolved(emptyMap())
        return when (val result = source.resolve(keys, config)) {
            ReminderResolutionResult.PermissionRequired -> Resolved(emptyMap(), true)
            ReminderResolutionResult.Unavailable -> Resolved(keys.associateWith { deferred() })
            is ReminderResolutionResult.Ready -> Resolved(keys.associateWith { key ->
                when (val value = result.results[key]) {
                    is ReminderResolution.Eligible -> if (value.candidate.key == key && key.calendarId in config.enabledCalendars &&
                        config.expectedCalendarIdentities[key.calendarId] == value.candidate.sourceCalendarIdentity) value
                    else ReminderResolution.Deferred(ReminderSourceProblem.IDENTITY_MISMATCH)
                    null -> deferred()
                    else -> value
                }
            })
        }
    }

    private suspend fun recover(claims: List<ReminderClaim>, resolutions: Map<ReminderKey, ReminderResolution>,
        config: ReminderConfiguration, counts: Counts) {
        for (claim in claims) {
            val record = claim.record
            when (val resolution = resolutions[record.key]) {
                ReminderResolution.GoneOrIneligible -> {
                    if (ledger.acknowledge(claim, ReminderDeliveryOutcome.Cancelled)) notifications.cancel(record.notificationAlias)
                }
                is ReminderResolution.Eligible -> {
                    if (!sameOccurrence(resolution.candidate, record, config)) {
                        markUncertain(claim, counts)
                    } else if (record.postIdentity?.let(notifications::isActive) == true) {
                        if (ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(clock.instant()))) counts.posted++
                    } else markUncertain(claim, counts)
                }
                else -> markUncertain(claim, counts)
            }
        }
    }

    private suspend fun markUncertain(claim: ReminderClaim, counts: Counts) {
        if (claim.record.state == ReminderDeliveryState.POSTING) ledger.acknowledge(claim, ReminderDeliveryOutcome.Uncertain)
        counts.uncertain++ // Absence from active notifications cannot prove that posting never happened.
    }

    private suspend fun deliver(claims: List<ReminderClaim>, config: ReminderConfiguration, counts: Counts) {
        val unattempted = claims.toMutableList()
        try {
            val resolved = resolve(claims.map { it.record.key }.toSet(), config)
            if (resolved.permissionRequired) {
                counts.permissionRequired = true
                for (claim in claims) { ledger.acknowledge(claim, ReminderDeliveryOutcome.Deferred); counts.deferred++ }
                return
            }
            for (claim in claims) {
                val record = claim.record
                val resolution = resolved.values[record.key]
                if (resolution == ReminderResolution.GoneOrIneligible) {
                    if (ledger.acknowledge(claim, ReminderDeliveryOutcome.Cancelled)) notifications.cancel(record.notificationAlias)
                    unattempted.remove(claim)
                    continue
                }
                val candidate = (resolution as? ReminderResolution.Eligible)?.candidate
                val postIdentity = record.postIdentity
                if (postIdentity == null) {
                    markUncertain(claim, counts)
                    unattempted.remove(claim)
                    continue
                }
                val permissionAvailable = canDeliver()
                if (candidate == null || !sameOccurrence(candidate, record, config) ||
                    (record.snoozedUntil == null && candidate.due != record.plannedDue) || !permissionAvailable) {
                    if (!permissionAvailable) counts.permissionRequired = true
                    ledger.acknowledge(claim, ReminderDeliveryOutcome.Deferred)
                    counts.deferred++
                    unattempted.remove(claim)
                    continue
                }
                unattempted.remove(claim) // From here an exception may follow an accepted notification.
                val outcome = try { notifications.post(candidate, postIdentity) }
                    catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { ledger.acknowledge(claim, ReminderDeliveryOutcome.Uncertain) }
                        throw cancelled
                    }
                    catch (_: RuntimeException) { ReminderPostResult.UNCERTAIN }
                when (outcome) {
                    ReminderPostResult.POSTED -> {
                        if (ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(clock.instant()))) counts.posted++
                        else {
                            // End-only reconciliation may preserve this alias under a newer revision.
                            // Do not cancel a valid replacement post merely because the old CAS failed.
                            val current = ledger.findByAlias(record.notificationAlias)
                            if (current == null || current.state in setOf(ReminderDeliveryState.RETIRED, ReminderDeliveryState.CANCELLED)) {
                                notifications.cancel(record.notificationAlias)
                            } else counts.uncertain++
                        }
                    }
                    ReminderPostResult.PERMISSION_REQUIRED -> {
                        counts.permissionRequired = true
                        ledger.acknowledge(claim, ReminderDeliveryOutcome.Deferred); counts.deferred++
                    }
                    ReminderPostResult.UNCERTAIN -> markUncertain(claim, counts)
                }
                if (counts.permissionRequired) break
            }
        } finally {
            // Cancellation before an attempted post is known safe to defer. Actual process death is
            // recovered through persisted POSTING/UNCERTAIN instead, never an audible blind replay.
            withContext(NonCancellable) {
                for (claim in unattempted) {
                    if (ledger.acknowledge(claim, ReminderDeliveryOutcome.Deferred)) counts.deferred++
                }
            }
        }
    }

    private fun sameOccurrence(candidate: ReminderCandidate, record: ReminderRecord, config: ReminderConfiguration): Boolean =
        candidate.key == record.key && candidate.sourceCalendarIdentity == record.sourceCalendarIdentity &&
            config.expectedCalendarIdentities[record.key.calendarId] == record.sourceCalendarIdentity &&
            candidate.occurrence.ref.instanceStart == record.currentRef.instanceStart &&
            (candidate.occurrence.time is EventTime.AllDay) == record.allDay &&
            !(candidate.sourceEventUidFingerprint != null && record.sourceEventUidFingerprint != null &&
                candidate.sourceEventUidFingerprint != record.sourceEventUidFingerprint)

    suspend fun snooze(identity: ReminderPostIdentity?, minutes: Int): ReminderActionResult = mutex.withLock {
        if (identity == null) return@withLock ReminderActionResult.IGNORED
        val alias = identity.alias
        if (minutes !in listOf(5, 10, 30)) return@withLock ReminderActionResult.IGNORED
        val config = configuration()
        if (!config.enabled) return@withLock ReminderActionResult.IGNORED
        if (!canDeliver()) return@withLock ReminderActionResult.PERMISSION_REQUIRED
        val existing = ledger.findByAlias(alias) ?: return@withLock ReminderActionResult.IGNORED
        if (existing.key.calendarId !in config.enabledCalendars ||
            config.expectedCalendarIdentities[existing.key.calendarId] != existing.sourceCalendarIdentity) {
            notifications.cancel(alias)
            return@withLock ReminderActionResult.IGNORED
        }
        val record = postedForAction(identity) ?: return@withLock ReminderActionResult.IGNORED
        val result = resolve(setOf(record.key), config)
        if (result.permissionRequired) return@withLock ReminderActionResult.PERMISSION_REQUIRED
        if (result.values[record.key] == ReminderResolution.GoneOrIneligible) {
            if (ledger.dismiss(record.version, identity.token)) notifications.cancel(alias)
            armNext()
            return@withLock ReminderActionResult.IGNORED
        }
        val candidate = (result.values[record.key] as? ReminderResolution.Eligible)?.candidate
            ?: return@withLock ReminderActionResult.SOURCE_UNAVAILABLE
        if (!sameOccurrence(candidate, record, config)) return@withLock ReminderActionResult.IGNORED
        val snoozed = ledger.snooze(record.version, identity.token, minutes, clock.instant()) ?: return@withLock ReminderActionResult.IGNORED
        notifications.cancel(snoozed.notificationAlias)
        armNext()
        ReminderActionResult.APPLIED
    }

    suspend fun dismiss(identity: ReminderPostIdentity?): ReminderActionResult = mutex.withLock {
        if (identity == null) return@withLock ReminderActionResult.IGNORED
        val alias = identity.alias
        val record = postedForAction(identity)
        if (record == null || !ledger.dismiss(record.version, identity.token)) return@withLock ReminderActionResult.IGNORED
        notifications.cancel(alias)
        if (configuration().enabled && canDeliver()) armNext() else cancelAlarm()
        ReminderActionResult.APPLIED
    }

    /** Called only for validated private immutable nonce-bearing notification actions. */
    private suspend fun postedForAction(identity: ReminderPostIdentity): ReminderRecord? {
        var record = ledger.findByAlias(identity.alias) ?: return null
        if (record.postToken != identity.token) return null
        if (record.state == ReminderDeliveryState.POSTING || record.state == ReminderDeliveryState.UNCERTAIN) {
            if (!ledger.acknowledge(ReminderClaim(record), ReminderDeliveryOutcome.Posted(clock.instant()))) return null
            record = ledger.findByAlias(identity.alias) ?: return null
        }
        return record.takeIf { it.state == ReminderDeliveryState.POSTED && it.postToken == identity.token }
    }

    private fun canDeliver(): Boolean = permissions().let { it.calendarAccess && it.canPostNotifications }
    private fun permissionRequired(counts: Counts = Counts()): ReminderCycleResult {
        notifications.cancelAllOwn()
        return ReminderCycleResult(ReminderCycleStatus.PERMISSION_REQUIRED, cancelAlarm(), counts.posted, counts.deferred, counts.uncertain)
    }
    private suspend fun armNext(): AlarmSchedulingResult = ledger.nextScheduled()?.let {
        alarms.replaceNext(ReminderAlarmRequest(it.effectiveDue, it.version))
    } ?: cancelAlarm()
    private fun cancelAlarm(): AlarmSchedulingResult = alarms.replaceNext(null)
    private fun deferred() = ReminderResolution.Deferred(ReminderSourceProblem.QUERY_FAILED)
}
