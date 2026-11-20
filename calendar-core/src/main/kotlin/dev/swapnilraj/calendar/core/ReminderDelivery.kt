package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.ZoneId

/** Stable across end-only edits, device-zone changes, and a moved exception's provider row ID. */
data class ReminderKey(
    val calendarId: CalendarId,
    val masterOrEventId: Long,
    val originalOrInstanceStart: Instant,
    val resolvedMinutesBefore: Int,
) {
    init { require(masterOrEventId >= 0) }

    companion object {
        fun of(ref: EventRef, resolvedMinutesBefore: Int): ReminderKey = ReminderKey(
            ref.calendarId, ref.recurrence?.masterEventId ?: ref.eventId,
            ref.recurrence?.originalStart ?: ref.instanceStart, resolvedMinutesBefore,
        )
    }
}

enum class ReminderDefaultMethod { UNRESOLVED, LOCAL_ALERT, NON_LOCAL }

/** Opaque stable canonical fingerprint of at least account type/name, owner and calendar ID.
 * Prefer a stable provider calendar UID fingerprint when available. A temporarily missing UID is
 * not proof of a changed namespace. Without stable UIDs, a same-account provider reset reusing every
 * ID/time cannot be distinguished reliably; neither source nor ledger may claim otherwise.
 */
data class ReminderCalendarIdentity(val fingerprint: String, val providerUidFingerprint: String? = null) {
    init {
        require(fingerprint.isNotBlank())
        require(providerUidFingerprint == null || providerUidFingerprint.isNotBlank())
    }

    fun isKnownDifferentFrom(other: ReminderCalendarIdentity): Boolean = fingerprint != other.fingerprint ||
        (providerUidFingerprint != null && other.providerUidFingerprint != null &&
            providerUidFingerprint != other.providerUidFingerprint)
}

/** Null offsets/default method mean unresolved, never an implicit 10/900-minute substitution. */
data class ReminderDefaults(
    val timedMinutesBefore: Int? = null,
    val allDayMinutesBefore: Int? = null,
    val defaultMethod: ReminderDefaultMethod = ReminderDefaultMethod.UNRESOLVED,
)

/** These IDs are reminder preferences, not the current calendar display filter. */
data class ReminderConfiguration(
    val enabled: Boolean,
    val enabledCalendars: Set<CalendarId>,
    val deviceZone: ZoneId,
    val defaults: Map<CalendarId, ReminderDefaults> = emptyMap(),
    /** Explicitly confirmed identities; a numeric ID alone never enables a replacement calendar. */
    val expectedCalendarIdentities: Map<CalendarId, ReminderCalendarIdentity> = emptyMap(),
)

enum class ReminderSourceProblem {
    CALENDAR_UNAVAILABLE, READ_ACCESS_UNAVAILABLE, PROVIDER_HIDDEN, SYNC_DISABLED,
    UNRESOLVED_MINUTES, UNRESOLVED_METHOD, INVALID_TIME, QUERY_FAILED,
    MISSING_EXPECTED_IDENTITY, IDENTITY_MISMATCH, IDENTITY_UNAVAILABLE,
}

data class ReminderSourceFailure(val calendarId: CalendarId, val problem: ReminderSourceProblem)

/** Only stored, resolved local-alert rows produce candidates. Duplicate rows coalesce to one key.
 * Occurrence presentation is fresh, in-memory data; the ledger must NOT persist its title/colour.
 */
data class ReminderCandidate(
    val key: ReminderKey,
    val sourceCalendarIdentity: ReminderCalendarIdentity,
    val occurrence: EventOccurrence,
    val due: Instant,
    /** Optional stable provider event UID fingerprint; missing is unknown, not a changed event. */
    val sourceEventUidFingerprint: String? = null,
) {
    init {
        require(key == ReminderKey.of(occurrence.ref, key.resolvedMinutesBefore))
        require(sourceEventUidFingerprint == null || sourceEventUidFingerprint.isNotBlank())
    }
}

/** Completeness covers discovery in this due window, including advance reminders on later starts.
 * Absence here NEVER proves a tracked reminder disappeared: due can move outside the window.
 */
data class ReminderSourceSnapshot(
    val window: InstantBounds,
    val candidates: List<ReminderCandidate>,
    val completeCalendars: Set<CalendarId>,
    /** Successful current metadata observations, even when event reads fail or no candidates exist. */
    val observedCalendarIdentities: Map<CalendarId, ReminderCalendarIdentity>,
    val failures: List<ReminderSourceFailure> = emptyList(),
    /** Only an authoritative successful metadata read may prove a calendar was removed. */
    val removedCalendars: Set<CalendarId> = emptySet(),
) {
    init {
        require(window.endExclusive > window.start)
        require(candidates.all { it.due in window })
        require(candidates.map { it.key }.toSet().size == candidates.size)
        require(failures.none { it.calendarId in completeCalendars })
        require(completeCalendars.all { it in observedCalendarIdentities })
        require(candidates.all { observedCalendarIdentities[it.key.calendarId] == it.sourceCalendarIdentity })
        require(removedCalendars.none { it in observedCalendarIdentities })
    }
}

sealed interface ReminderSourceResult {
    data class Ready(val snapshot: ReminderSourceSnapshot) : ReminderSourceResult
    data object PermissionRequired : ReminderSourceResult
    data object Unavailable : ReminderSourceResult
}

sealed interface ReminderResolution {
    data class Eligible(val candidate: ReminderCandidate) : ReminderResolution
    data object GoneOrIneligible : ReminderResolution
    data class Deferred(val problem: ReminderSourceProblem) : ReminderResolution
}

sealed interface ReminderResolutionResult {
    /** Exactly the requested keys must be present; omitted keys are NOT proof of deletion. */
    data class Ready(val results: Map<ReminderKey, ReminderResolution>) : ReminderResolutionResult
    data object PermissionRequired : ReminderResolutionResult
    data object Unavailable : ReminderResolutionResult
}

interface ReminderSource {
    /** Batch lightweight events/reminders/instances; do not call per-event detail/attendee readers.
     * Resolve MINUTES_DEFAULT before dueInstant, and METHOD_DEFAULT only under explicit known policy.
     * Timed offset m shifts the start-query window by +m; group offsets instead of querying the huge
     * continuous gap between them. All-day lookup uses padded UTC dates derived in the device zone.
     * Always apply exact due-window filtering after resolution, including negative/long offsets.
     * Missing/mismatched expected calendar identity yields an explicit failure and NO candidate for
     * that namespace. Still report successful observed identities so old claims can be retired.
     */
    suspend fun loadDueWindow(window: InstantBounds, configuration: ReminderConfiguration): ReminderSourceResult

    /** Batch fresh eligibility/time/title reads immediately before delivery; never infer Gone from
     * a failed query. Source identity, cancellation, decline and reminder removal must be rechecked.
     */
    suspend fun resolve(keys: Set<ReminderKey>, configuration: ReminderConfiguration): ReminderResolutionResult
}

/** Stable E1 notification (tag,id) alias for a delivery generation. This is an identity anchor,
 * not the current alarm time. Device-zone/end-only changes preserve it; actual start reschedules
 * create a new alias/generation. The persisted unique nonce enters BOTH the notification tag and
 * immutable action Intent identity. Pending actions resolve the complete alias through the ledger.
 */
data class ReminderNotificationAlias(val ref: EventRef, val anchorDue: Instant, val nonce: String) {
    init { require(nonce.isNotBlank()) }
}

/** One specific notification post. Snooze reposts retain the stable tag but receive a fresh token.
 * Immutable action identity includes this token; an old action can never adopt a newer post token.
 */
data class ReminderPostIdentity(val alias: ReminderNotificationAlias, val token: String) {
    init { require(token.isNotBlank()) }
}

enum class ReminderDeliveryState {
    SCHEDULED, POSTING, POSTED, SNOOZED, DISMISSED, CANCELLED, DEFERRED, EXPIRED, UNCERTAIN, RETIRED,
}

/** Persistence contains scheduling identity/state only, never event presentation or guest data. */
data class ReminderRecord(
    val key: ReminderKey,
    val sourceCalendarIdentity: ReminderCalendarIdentity,
    val currentRef: EventRef,
    val allDay: Boolean,
    val plannedDue: Instant,
    val effectiveDue: Instant,
    val notificationAlias: ReminderNotificationAlias,
    val state: ReminderDeliveryState,
    val generation: Long,
    val revision: Long,
    val lastPostedAt: Instant? = null,
    /** Retained while claiming a snooze, so a zone recomputation cannot replace a user deadline. */
    val snoozedUntil: Instant? = null,
    val claimToken: String? = null,
    val sourceEventUidFingerprint: String? = null,
    /** Persisted before posting, retained through acknowledgement and metadata changes. Null is
     * allowed for unposted/legacy history but cannot authorize a private notification action.
     */
    val postToken: String? = null,
) {
    init {
        require(key == ReminderKey.of(currentRef, key.resolvedMinutesBefore))
        require(key == ReminderKey.of(notificationAlias.ref, key.resolvedMinutesBefore))
        require(sourceEventUidFingerprint == null || sourceEventUidFingerprint.isNotBlank())
        require(postToken == null || postToken.isNotBlank())
        require(generation >= 0 && revision >= 0)
        require(snoozedUntil == null || snoozedUntil == effectiveDue)
        require(state != ReminderDeliveryState.SNOOZED || snoozedUntil != null)
        require((state == ReminderDeliveryState.POSTING || state == ReminderDeliveryState.UNCERTAIN) == !claimToken.isNullOrBlank())
    }
    val version: ReminderVersion get() = ReminderVersion(key, sourceCalendarIdentity, generation, revision)
    val postIdentity: ReminderPostIdentity? get() = postToken?.let { ReminderPostIdentity(notificationAlias, it) }
}

data class ReminderVersion(
    val key: ReminderKey,
    val sourceCalendarIdentity: ReminderCalendarIdentity,
    val generation: Long,
    val revision: Long,
)

/** Record is already durably POSTING when returned; acknowledgements compare version AND token. */
data class ReminderClaim(val record: ReminderRecord) {
    init { require(record.state == ReminderDeliveryState.POSTING || record.state == ReminderDeliveryState.UNCERTAIN) }
}

sealed interface ReminderDeliveryOutcome {
    data class Posted(val at: Instant) : ReminderDeliveryOutcome
    data object Deferred : ReminderDeliveryOutcome
    data object Cancelled : ReminderDeliveryOutcome
    data object Expired : ReminderDeliveryOutcome
    data object Uncertain : ReminderDeliveryOutcome
}

data class ReminderPlanChanges(val cancelNotifications: Set<ReminderNotificationAlias> = emptySet())

interface ReminderLedger {
    /** All pending/interrupted/deferred records plus delivered history whose plannedDue is in window.
     * Coordinator resolves their keys in ONE batch before reconciliation; no per-event detail reads.
     */
    suspend fun trackedForRevalidation(window: InstantBounds): List<ReminderRecord>

    /** Atomic upsert/cancel. Missing map/window entries and Deferred NEVER prove disappearance.
     * Only explicit GoneOrIneligible retires a known reminder (or authoritative namespace/removal
     * evidence below). Eligible may be due outside snapshot.window; update it without discarding
     * zone/end-only delivered history. completeCalendars certifies discovery, not global absence.
     * Disabled-calendar pending/snoozed work is cancelled; posted/dismissed history is retained.
     * Same actual start preserves delivered history on zone/end-only changes; start changes create
     * a new generation/alias. Preserve an unchanged snooze's elapsed effectiveDue. Never reset POSTING
     * or UNCERTAIN blindly. Returned obsolete aliases are cancelled by the coordinator, outside SQL.
     * Known observed namespace changes/authoritative removals retire old aliases/history/claims even
     * when candidates are empty or coverage incomplete. A changed known event UID also retires the
     * old generation; a missing UID must not erase a previously known UID. Authoritatively absent
     * one-off occurrences become RETIRED: reschedule-away/back must receive a fresh generation/nonce,
     * not inherit an old dismissed state. Every new generation gets a persisted unique nonce;
     * zone/end-only changes retain it. Same-account total reset without UIDs remains unprovable.
     */
    suspend fun reconcile(
        snapshot: ReminderSourceSnapshot,
        enabledCalendars: Set<CalendarId>,
        resolutions: Map<ReminderKey, ReminderResolution>,
        now: Instant,
    ): ReminderPlanChanges

    /** One transaction moves due SCHEDULED/SNOOZED rows to POSTING with new unique claim tokens.
     * Persist a fresh postToken (which may equal the claim token) before any external notification.
     */
    suspend fun claimDue(now: Instant, limit: Int): List<ReminderClaim>

    /** CAS on key/generation/revision/token. Returns false if another action invalidated the claim. */
    suspend fun acknowledge(claim: ReminderClaim, outcome: ReminderDeliveryOutcome): Boolean

    suspend fun findByAlias(alias: ReminderNotificationAlias): ReminderRecord?

    /** CAS; only the matching POSTED generation may be snoozed/dismissed. Allowed minutes:5/10/30.
     * A valid private immutable nonce-bearing user action is evidence for an interrupted post:
     * coordinator may first acknowledge its matching interrupted claim as Posted, reload version,
     * then apply the action. Never revive a retired namespace/generation or blindly reset claims.
     */
    suspend fun snooze(expected: ReminderVersion, postToken: String, minutes: Int, now: Instant): ReminderRecord?
    suspend fun dismiss(expected: ReminderVersion, postToken: String): Boolean

    /** Expire only old SCHEDULED/SNOOZED/DEFERRED work; do not invent outcomes for uncertain posts. */
    suspend fun expireBefore(cutoff: Instant): ReminderPlanChanges

    /** Null when no SCHEDULED/SNOOZED row exists. DEFERRED work waits for fresh reconciliation. */
    suspend fun nextScheduled(): ReminderRecord?

    /** POSTING/UNCERTAIN claims survive restart; never automatically convert them to SCHEDULED. */
    suspend fun interruptedDeliveries(): List<ReminderClaim>
}

data class ReminderAlarmRequest(val at: Instant, val version: ReminderVersion)
enum class AlarmSchedulingResult { EXACT, INEXACT, CANCELLED, UNAVAILABLE }

interface ReminderAlarmPort {
    /** Replace one private PendingIntent alarm, or cancel it for null. Never claim inexact is exact. */
    fun replaceNext(alarm: ReminderAlarmRequest?): AlarmSchedulingResult
}

enum class ReminderPostResult { POSTED, PERMISSION_REQUIRED, UNCERTAIN }

interface ReminderNotificationPort {
    /** Stable alias is used for (tag,id); immutable action identity also contains the post token.
     * Presentation is freshly revalidated. Persist this token in private notification extras too.
     */
    fun post(candidate: ReminderCandidate, identity: ReminderPostIdentity): ReminderPostResult
    /** Tag alone cannot prove a new snooze post was accepted; verify its exact token as well. */
    fun isActive(identity: ReminderPostIdentity): Boolean
    fun cancel(alias: ReminderNotificationAlias)
    /** Cancel only this app's active reminder alerts outside confirmed calendar scopes. This must
     * not enumerate/mutate SQL history. Missing observation is not evidence of provider removal;
     * known namespace mismatches/removals and unconfigured scopes do authorize alert cancellation.
     * Throw on unavailable enumeration/cancellation so the coordinator does not deliver new work.
     */
    fun cancelIneligibleScopes(
        allowedCalendars: Map<CalendarId, ReminderCalendarIdentity>,
        observedCalendars: Map<CalendarId, ReminderCalendarIdentity>,
        removedCalendars: Set<CalendarId>,
    )
    /** Remove this app's reminder notifications on permission loss, including older posted rows
     * outside the current reconciliation window. Never touches another application's alerts.
     */
    fun cancelAllOwn()
}
