package dev.swapnilraj.calendar.core

import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID

/** Independent family: these names do not widen any historical source, command or receipt. */
object OrdinaryCompoundEditV1 {
    const val COMMAND = "ORDINARY_COMPOUND_EDIT_V1"
    const val SOURCE = "ORDINARY_COMPOUND_EDIT_SOURCE_V1"
    const val RECEIPT = "ORDINARY_COMPOUND_EDIT_RECEIPT_V1"
    const val EFFECT = "AOSP_EXISTING_ROW_COMPOUND_EDIT_V1"
    const val SOURCE_PREFIX = "ordinary-compound-edit-source-v1:"
    const val COMMAND_PREFIX = "ordinary-compound-edit-command-v1:"
    const val RAW_PREFIX = "ordinary-compound-edit-raw-v1:"
}

data class CompoundEditSourceToken(val value: String) {
    init { require(value.length <= 4096 && value.startsWith(OrdinaryCompoundEditV1.SOURCE_PREFIX) &&
        value.removePrefix(OrdinaryCompoundEditV1.SOURCE_PREFIX).isNotBlank()) }
}
data class CompoundEditRevision(val namespace: String, val generation: Long) {
    init { require(namespace.isNotBlank() && namespace.length <= 4096 && generation >= 0) }
}
/** The draft ID is opaque; only newly issued generation/revision fields promise canonical UUIDs. */
data class CompoundEditDraftBinding(val draftId: String, val generation: String, val rawRevision: String,
    val sourceToken: CompoundEditSourceToken) {
    init {
        require(draftId.isNotBlank() && draftId.length <= 4096)
        require(listOf(generation, rawRevision).all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) })
    }
}

/** Pure materialization ceilings, separate from native UTF-8/escaped receipt and Parcel limits. */
data class CompoundEditLimits(
    val sourceCellUnits: Int = 262_144, val authoredCellUnits: Int = 65_536,
    val sourceUnits: Long = 1_048_576, val intentUnits: Long = 1_048_576,
    val attendees: Int = 2000, val reminders: Int = 2000, val alerts: Int = 4000,
) {
    init {
        require(sourceCellUnits in 1..262_144 && authoredCellUnits in 1..65_536)
        require(sourceUnits in 1..1_048_576 && intentUnits in 1..1_048_576)
        require(attendees in 0..2000 && reminders in 0..2000 && alerts in 0..4000)
    }
}

/** These constraints are observations issued by the adapter, never inferred from a colour flag. */
class CompoundEditAuthoringRules private constructor(
    val allowedAvailability: Set<Int>?, val alertMethod: AccessDecision,
    val maxReminders: Int?, val preservedNonAlertReminders: Int?,
) {
    companion object {
        fun capture(allowedAvailability: Set<Int>? = null, alertMethod: AccessDecision = AccessDecision.UNKNOWN,
            maxReminders: Int? = null, preservedNonAlertReminders: Int? = null): CompoundEditAuthoringRules {
            require(maxReminders == null || maxReminders >= 0)
            require(preservedNonAlertReminders == null || preservedNonAlertReminders >= 0)
            require(allowedAvailability == null || allowedAvailability.size <= 3)
            val captured = allowedAvailability?.let { values ->
                val copy = ArrayList<Int>()
                for (value in values) { require(copy.size < 3 && value in 0..2); copy.add(value) }
                Collections.unmodifiableSet(LinkedHashSet(copy))
            }
            return CompoundEditAuthoringRules(captured, alertMethod, maxReminders, preservedNonAlertReminders)
        }
    }
}

/** Optional palette revision is independent from the ordinary source token and generation. */
sealed interface CompoundPaletteObservation {
    data object Loading : CompoundPaletteObservation
    data object PermissionRequired : CompoundPaletteObservation
    data object Unavailable : CompoundPaletteObservation
    data object LimitExceeded : CompoundPaletteObservation
    data object Unsupported : CompoundPaletteObservation
    data class Ready(val revision: ColorReadRevision, val palette: EventColorPalette) : CompoundPaletteObservation
}

enum class CompoundEditProblem {
    LIMIT_EXCEEDED, INVALID_SOURCE, UNSUPPORTED_SOURCE, STALE_BINDING, STALE_SOURCE,
    FIELD_NOT_ALLOWED, INVALID_TIME, INVALID_ZONE, INVALID_ALERTS, INVALID_AVAILABILITY,
    INVALID_PRIVACY, COLOR_REVIEW,
}
sealed interface CompoundEditSourceCapture {
    data class Ready(val source: CompoundEditableEvent) : CompoundEditSourceCapture
    data class Review(val problem: CompoundEditProblem) : CompoundEditSourceCapture
}

/** Defensive read model only. Native source snapshots/permissions remain independently authoritative. */
class CompoundEditableEvent private constructor(
    val details: EventDetails, val sourceToken: CompoundEditSourceToken, val revision: CompoundEditRevision,
    val allowedFields: Set<EditableField>, val colorObservation: EventColorObservation?,
    val colorRights: EventColorRights, val palette: CompoundPaletteObservation,
    val authoringRules: CompoundEditAuthoringRules,
) {
    val calendar: CalendarIdentity get() = requireNotNull(details.calendar).identity
    val ref: EventRef get() = details.occurrence.ref
    companion object {
        fun capture(details: EventDetails, sourceToken: CompoundEditSourceToken, revision: CompoundEditRevision,
            allowedFields: Set<EditableField>, colorObservation: EventColorObservation? = null,
            colorRights: EventColorRights = EventColorRights(), palette: CompoundPaletteObservation = CompoundPaletteObservation.Unavailable,
            authoringRules: CompoundEditAuthoringRules = CompoundEditAuthoringRules.capture(),
            limits: CompoundEditLimits = CompoundEditLimits()): CompoundEditSourceCapture = try {
            sourceBudget(details, sourceToken, revision, allowedFields, limits)
            val copying = SourceBudget(limits)
            copying.metadata(details, sourceToken, revision)
            val guests = boundedCopy(details.attendees, limits.attendees) { copying.guest(it) }
            val reminders = boundedCopy(details.reminders, limits.reminders) { copying.reminder(it) }
            val fields = boundedCopy(allowedFields, EditableField.entries.size) { copying.units(1) }
            val frozen = details.copy(attendees = guests, reminders = reminders)
            val calendar = frozen.calendar
            val occurrence = frozen.occurrence
            if (calendar == null || calendar.id != occurrence.ref.calendarId || calendar.identity.account.name.isBlank() || calendar.identity.account.type.isBlank() ||
                occurrence.ref.calendarId.value <= 0 || occurrence.ref.eventId <= 0 || !sameBounds(occurrence))
                CompoundEditSourceCapture.Review(CompoundEditProblem.INVALID_SOURCE)
            else if (occurrence.ref.recurrence != null || !frozen.recurrenceRule.isNullOrBlank())
                CompoundEditSourceCapture.Review(CompoundEditProblem.UNSUPPORTED_SOURCE)
            else CompoundEditSourceCapture.Ready(CompoundEditableEvent(frozen, sourceToken, revision,
                Collections.unmodifiableSet(LinkedHashSet(fields)), colorObservation, colorRights, palette, authoringRules))
        } catch (_: CompoundLimit) { CompoundEditSourceCapture.Review(CompoundEditProblem.LIMIT_EXCEEDED) }
    }
}

sealed interface CompoundEditIntentCapture {
    data class Ready(val intent: CompoundEditIntentV1) : CompoundEditIntentCapture
    data class Review(val problem: CompoundEditProblem) : CompoundEditIntentCapture
}
/** Captured sparse input, not a native plan. No mutable alert list escapes capture. */
@ConsistentCopyVisibility
data class CompoundEditIntentV1 internal constructor(
    val binding: CompoundEditDraftBinding, val calendar: CalendarIdentity, val ref: EventRef,
    val sourceToken: CompoundEditSourceToken, val revision: CompoundEditRevision,
    val ordinary: EventPatch, val color: EventColorChoice,
) {
    companion object {
        fun capture(current: CompoundEditableEvent, binding: CompoundEditDraftBinding, ordinary: EventPatch = EventPatch(),
            color: EventColorChoice = EventColorChoice.Keep, limits: CompoundEditLimits = CompoundEditLimits()): CompoundEditIntentCapture {
            if (binding.sourceToken != current.sourceToken) return CompoundEditIntentCapture.Review(CompoundEditProblem.STALE_BINDING)
            return try {
                intentBudget(current.calendar, current.sourceToken, current.revision, binding, ordinary, color, limits)
                val alerts = (ordinary.alertMinutes as? FieldChange.Set)?.value?.let { values ->
                    val budget = Units(limits.intentUnits, limits.authoredCellUnits)
                    boundedCopy(values, limits.alerts) { budget.units(1) }
                }
                CompoundEditIntentCapture.Ready(CompoundEditIntentV1(binding, current.calendar, current.ref,
                    current.sourceToken, current.revision,
                    if (alerts == null) ordinary else ordinary.copy(alertMinutes = FieldChange.Set(alerts)), color))
            } catch (_: CompoundLimit) { CompoundEditIntentCapture.Review(CompoundEditProblem.LIMIT_EXCEEDED) }
        }
    }
}

sealed interface CompoundEditPlanResult {
    data object NoChange : CompoundEditPlanResult
    data class Review(val problem: CompoundEditProblem, val colorProblem: EventColorProblem? = null) : CompoundEditPlanResult
    /** Semantic union only. Null colour key means Keep; empty means explicit default. */
    @ConsistentCopyVisibility
    data class Ready internal constructor(val ordinary: EventPatch, val colorKey: String?) : CompoundEditPlanResult
}

object CompoundEditPlans {
    /** expectedBinding is supplied consistency input; the caller must separately prove its live lease. */
    fun resolve(current: CompoundEditableEvent, expectedBinding: CompoundEditDraftBinding, intent: CompoundEditIntentV1,
        limits: CompoundEditLimits = CompoundEditLimits()): CompoundEditPlanResult {
        fun review(problem: CompoundEditProblem) = CompoundEditPlanResult.Review(problem)
        if (expectedBinding != intent.binding || expectedBinding.sourceToken != current.sourceToken)
            return review(CompoundEditProblem.STALE_BINDING)
        if (intent.calendar != current.calendar || intent.ref != current.ref || intent.sourceToken != current.sourceToken || intent.revision != current.revision)
            return review(CompoundEditProblem.STALE_SOURCE)
        val captured = CompoundEditIntentV1.capture(current, expectedBinding, intent.ordinary, intent.color, limits)
        if (captured is CompoundEditIntentCapture.Review) return review(captured.problem)
        val patch = (captured as CompoundEditIntentCapture.Ready).intent.ordinary
        val changed = changedFields(patch)
        if (changed.isEmpty() && intent.color == EventColorChoice.Keep) return CompoundEditPlanResult.NoChange
        if (current.details.canEdit != AccessDecision.ALLOWED || changed.any { it !in current.allowedFields })
            return review(CompoundEditProblem.FIELD_NOT_ALLOWED)
        validateAuthored(current, patch)?.let { return review(it) }
        if (intent.color == EventColorChoice.Keep) return CompoundEditPlanResult.Ready(patch, null)
        val cells = current.colorObservation ?: return CompoundEditPlanResult.Review(CompoundEditProblem.COLOR_REVIEW, EventColorProblem.MISSING_PROJECTION)
        // Keep and Reset never inspect optional palette. Only Named takes this branch.
        val palette = if (intent.color is EventColorChoice.Named) {
            val observation = current.palette as? CompoundPaletteObservation.Ready
                ?: return CompoundEditPlanResult.Review(CompoundEditProblem.COLOR_REVIEW, EventColorProblem.PALETTE_UNAVAILABLE)
            if (observation.revision != observation.palette.context.source)
                return CompoundEditPlanResult.Review(CompoundEditProblem.COLOR_REVIEW, EventColorProblem.STALE_PALETTE)
            observation
        } else null
        return try {
            val revision = palette?.revision ?: ColorReadRevision(current.revision.namespace, current.revision.generation)
            // Length-prefixed fields make this local session encoding injective; no delimiter alias.
            val session = session(expectedBinding)
            val context = EventColorChoiceContext(current.calendar, EventColorTarget.ExistingOccurrence(current.ref), revision,
                EventColorSourceToken(current.sourceToken.value), session, 0)
            val colorCurrent = EditableEventColor(context, cells, current.colorRights,
                palette?.let { EventPaletteRead.Ready(it.palette) } ?: EventPaletteRead.Unavailable)
            when (val result = EventColorPlans.resolve(colorCurrent, EventColorIntentV1(context, intent.color))) {
                EventColorPlanResult.NoChange -> if (changed.isEmpty()) CompoundEditPlanResult.NoChange else CompoundEditPlanResult.Ready(patch, null)
                is EventColorPlanResult.Review -> CompoundEditPlanResult.Review(CompoundEditProblem.COLOR_REVIEW, result.problem)
                is EventColorPlanResult.ExistingRowKeyUpdate -> CompoundEditPlanResult.Ready(patch, result.key)
            }
        } catch (_: IllegalArgumentException) { review(CompoundEditProblem.LIMIT_EXCEEDED) }
    }
}

sealed interface CompoundEditReadResult {
    data class Ready(val source: CompoundEditableEvent) : CompoundEditReadResult
    data object PermissionRequired : CompoundEditReadResult
    data class Denied(val reason: MutationDenial) : CompoundEditReadResult
    data object LimitExceeded : CompoundEditReadResult
    data object Unavailable : CompoundEditReadResult
}
/** Independent source and mutation port. Palette observation is explicit, optional and read-only. */
interface CalendarCompoundEdits {
    suspend fun loadForEdit(ref: EventRef): CompoundEditReadResult
    suspend fun readPalette(sourceToken: CompoundEditSourceToken): CompoundPaletteObservation
    suspend fun update(operationId: String, intent: CompoundEditIntentV1): MutationResult
    suspend fun recover(operationId: String): MutationResult
}

private fun changedFields(p: EventPatch): Set<EditableField> = buildSet {
    if (p.title is FieldChange.Set) add(EditableField.TITLE)
    if (p.time is FieldChange.Set) add(EditableField.TIME)
    if (p.eventZone is FieldChange.Set) add(EditableField.ZONE)
    if (p.location is FieldChange.Set) add(EditableField.LOCATION)
    if (p.description is FieldChange.Set) add(EditableField.DESCRIPTION)
    if (p.alertMinutes is FieldChange.Set) add(EditableField.ALERTS)
    if (p.availability is FieldChange.Set) add(EditableField.AVAILABILITY)
    if (p.privacy is FieldChange.Set) add(EditableField.PRIVACY)
}
private fun validateAuthored(s: CompoundEditableEvent, p: EventPatch): CompoundEditProblem? {
    if (p.time is FieldChange.Set || p.eventZone is FieldChange.Set) {
        if (p.eventZone is FieldChange.Set && p.eventZone.value?.let { !ProviderTimeZoneCompatibility.isCompatible(it) } == true)
            return CompoundEditProblem.INVALID_ZONE
        val time = (p.time as? FieldChange.Set)?.value ?: s.details.occurrence.time
        if (time is EventTime.Timed) {
            if (time.end <= time.start || listOf(time.start, time.end).any { it.nano % 1_000_000 != 0 ||
                    runCatching { it.atOffset(ZoneOffset.UTC).year !in 1..9999 }.getOrDefault(true) }) return CompoundEditProblem.INVALID_TIME
            val zone = when (val change = p.eventZone) { FieldChange.Keep -> s.details.eventZone; is FieldChange.Set -> change.value }
            if (zone == null || !ProviderTimeZoneCompatibility.isCompatible(zone)) return CompoundEditProblem.INVALID_ZONE
        } else if (time is EventTime.AllDay && (time.dates.start.year !in 1..9999 || time.dates.endExclusive.year !in 1..9999))
            return CompoundEditProblem.INVALID_TIME
        if ((time is EventTime.AllDay) != (s.details.occurrence.time is EventTime.AllDay) && EditableField.ZONE !in s.allowedFields)
            return CompoundEditProblem.FIELD_NOT_ALLOWED
    }
    if (p.availability is FieldChange.Set) {
        val value = p.availability.value ?: 0
        if (value !in 0..2 || s.authoringRules.allowedAvailability?.contains(value) != true) return CompoundEditProblem.INVALID_AVAILABILITY
    }
    if (p.privacy is FieldChange.Set && (p.privacy.value ?: 0) !in 0..3) return CompoundEditProblem.INVALID_PRIVACY
    if (p.alertMinutes is FieldChange.Set) {
        val values = p.alertMinutes.value
        if (values.any { it < 0 } || values.distinct().size != values.size) return CompoundEditProblem.INVALID_ALERTS
        if (values.isNotEmpty()) {
            val rules = s.authoringRules
            val maximum = rules.maxReminders ?: return CompoundEditProblem.INVALID_ALERTS
            val preserved = rules.preservedNonAlertReminders ?: return CompoundEditProblem.INVALID_ALERTS
            if (rules.alertMethod != AccessDecision.ALLOWED || values.size.toLong() + preserved > maximum) return CompoundEditProblem.INVALID_ALERTS
        }
    }
    return null
}
private fun sameBounds(o: EventOccurrence): Boolean = when (val time = o.time) {
    is EventTime.Timed -> time.start == o.ref.instanceStart && time.end == o.ref.instanceEnd
    is EventTime.AllDay -> time.dates.start.atStartOfDay(ZoneOffset.UTC).toInstant() == o.ref.instanceStart &&
        time.dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant() == o.ref.instanceEnd
}
private fun session(binding: CompoundEditDraftBinding): String = listOf(binding.draftId, binding.generation, binding.rawRevision)
    .joinToString("") { "${it.length}:$it" }
private class CompoundLimit : RuntimeException()
private class Units(private val maximum: Long, private val cellMaximum: Int) {
    private var used = 0L
    fun text(value: String?) { if (value != null) { if (value.length > cellMaximum) throw CompoundLimit(); units(value.length.toLong()) } }
    fun units(value: Long) { if (value < 0 || value > maximum - used) throw CompoundLimit(); used += value }
}
private class SourceBudget(private val limits: CompoundEditLimits) {
    private val budget = Units(limits.sourceUnits, limits.sourceCellUnits)
    fun units(value: Long) = budget.units(value)
    fun metadata(d: EventDetails, token: CompoundEditSourceToken, revision: CompoundEditRevision) {
        listOf(d.occurrence.title, d.location, d.description, d.organizer, d.recurrenceRule, d.eventZone?.id,
            d.calendar?.displayName, d.calendar?.account?.name, d.calendar?.account?.type, d.calendar?.owner,
            d.calendar?.providerId, d.calendar?.zone?.id, token.value, revision.namespace).forEach(budget::text)
    }
    fun guest(value: EventAttendee) { budget.text(value.email); budget.text(value.name); budget.units(3) }
    fun reminder(value: EventReminder) { budget.units(2) }
}
private fun sourceBudget(d: EventDetails, token: CompoundEditSourceToken, revision: CompoundEditRevision,
    fields: Set<EditableField>, limits: CompoundEditLimits) {
    val b = SourceBudget(limits); b.metadata(d, token, revision)
    boundedScan(d.attendees, limits.attendees, b::guest)
    boundedScan(d.reminders, limits.reminders, b::reminder)
    boundedScan(fields, EditableField.entries.size) { b.units(1) }
}
private fun intentBudget(calendar: CalendarIdentity, source: CompoundEditSourceToken, revision: CompoundEditRevision,
    binding: CompoundEditDraftBinding, patch: EventPatch, color: EventColorChoice, limits: CompoundEditLimits) {
    val b = Units(limits.intentUnits, limits.sourceCellUnits)
    listOf(calendar.account.name, calendar.account.type, calendar.owner, calendar.providerId, source.value,
        revision.namespace, binding.draftId, binding.generation, binding.rawRevision).forEach(b::text)
    val authored = Units(limits.intentUnits, limits.authoredCellUnits)
    for (field in listOf(patch.title, patch.location, patch.description)) if (field is FieldChange.Set) {
        authored.text(field.value); b.units(field.value.length.toLong())
    }
    (patch.eventZone as? FieldChange.Set)?.value?.id?.let { authored.text(it); b.units(it.length.toLong()) }
    (patch.alertMinutes as? FieldChange.Set)?.value?.let { boundedScan(it, limits.alerts) { b.units(1) } }
    if (color is EventColorChoice.Named) {
        val c = color.selection.context
        listOf(c.calendar.account.name, c.calendar.account.type, c.calendar.owner, c.calendar.providerId,
            c.source.namespace, c.stamp.value, color.selection.key).forEach(b::text)
    }
}
private fun <T> boundedScan(values: Collection<T>, maximum: Int, consume: (T) -> Unit) {
    if (values.size > maximum) throw CompoundLimit()
    var count = 0
    for (value in values) { if (count++ >= maximum) throw CompoundLimit(); consume(value) }
}
private fun <T> boundedCopy(values: Collection<T>, maximum: Int, consume: (T) -> Unit): List<T> {
    if (values.size > maximum) throw CompoundLimit()
    val copy = ArrayList<T>()
    for (value in values) { if (copy.size >= maximum) throw CompoundLimit(); consume(value); copy.add(value) }
    return Collections.unmodifiableList(copy)
}
