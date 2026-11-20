package dev.swapnilraj.calendar.core

import java.util.Collections

/** Missing projection, SQL NULL and exact provider text are independent observations. */
sealed interface ColorCell {
    data object Missing : ColorCell
    data object SqlNull : ColorCell
    data class Text(val raw: String) : ColorCell { init { scalar(raw) } }
}

data class EventColorObservation(
    val eventColor: ColorCell,
    val eventColorKey: ColorCell,
    val displayColor: ColorCell,
    val calendarColor: ColorCell,
)

/** Display bits only, never an arbitrary-colour mutation value. Raw cells remain unchanged. */
@JvmInline
value class Argb32 private constructor(val bits: Int) {
    companion object {
        fun fromProviderInteger(raw: String): Argb32? {
            if (raw.length > EventColorPalette.MAX_SCALAR) return null
            val value = raw.toLongOrNull()?.takeIf { it.toString() == raw } ?: return null
            return value.takeIf { it in Int.MIN_VALUE.toLong()..0xFFFFFFFFL }?.let { Argb32(it.toInt()) }
        }
    }
}

data class ColorReadRevision(val namespace: String, val generation: Long) {
    init { token(namespace); require(generation >= 0) }
}
data class ColorPaletteStamp(val value: String) { init { token(value) } }
data class EventColorSourceToken(val value: String) { init { token(value) } }

enum class EventPaletteType { EVENT, CALENDAR, UNKNOWN }
data class EventPaletteEntry(val account: CalendarAccount, val type: EventPaletteType, val key: String, val colorRaw: String) {
    init { accountScalars(account); scalar(key); scalar(colorRaw) }
}
data class EventPaletteContext(val calendar: CalendarIdentity, val source: ColorReadRevision, val stamp: ColorPaletteStamp) {
    init { identityScalars(calendar) }
}

enum class EventColorProblem {
    LIMIT_EXCEEDED, EMPTY_KEY, FOREIGN_PALETTE, DUPLICATE_KEY, INVALID_ARGB,
    STALE_CONTEXT, STALE_PALETTE, RIGHTS_UNPROVEN, MISSING_PROJECTION, PALETTE_UNAVAILABLE,
}
sealed interface EventPaletteBuild {
    data class Ready(val palette: EventColorPalette) : EventPaletteBuild
    data class Review(val problem: EventColorProblem) : EventPaletteBuild
}

/** Immutable scoped palette. A selection is issued only from a completely validated palette. */
class EventColorPalette private constructor(val context: EventPaletteContext, entries: List<EventPaletteEntry>) {
    val entries: List<EventPaletteEntry> = Collections.unmodifiableList(ArrayList(entries))
    fun select(key: String): EventPaletteSelection? = entries.singleOrNull { it.key == key }?.let {
        EventPaletteSelection(context, it.key, checkNotNull(Argb32.fromProviderInteger(it.colorRaw)))
    }
    companion object {
        const val MAX_ENTRIES = 256
        const val MAX_SCALAR = 4096
        const val MAX_UNITS = 65_536L
        fun build(context: EventPaletteContext, entries: Collection<EventPaletteEntry>): EventPaletteBuild {
            fun limit() = EventPaletteBuild.Review(EventColorProblem.LIMIT_EXCEEDED)
            if (entries.size !in 0..MAX_ENTRIES) return limit()
            val initial = identityUnits(context.calendar) + context.source.namespace.length + context.stamp.value.length
            // Bound aggregate scalar work before copying or hashing keys. Repeated aliases are charged.
            var retainedUnits = initial
            var count = 0
            val preflight = entries.iterator()
            while (preflight.hasNext()) {
                if (count++ == MAX_ENTRIES) return limit()
                retainedUnits += entryUnits(preflight.next())
                if (retainedUnits > MAX_UNITS) return limit()
            }
            if (retainedUnits > MAX_UNITS) return limit()
            val captured = ArrayList<EventPaletteEntry>(count)
            var copiedUnits = initial
            for (entry in entries) {
                if (captured.size == MAX_ENTRIES) return limit()
                copiedUnits += entryUnits(entry)
                if (copiedUnits > MAX_UNITS) return limit()
                captured.add(entry)
            }
            val keys = HashSet<String>()
            for (entry in captured) {
                if (entry.account != context.calendar.account || entry.type != EventPaletteType.EVENT)
                    return EventPaletteBuild.Review(EventColorProblem.FOREIGN_PALETTE)
                if (entry.key.isEmpty()) return EventPaletteBuild.Review(EventColorProblem.EMPTY_KEY)
                if (!keys.add(entry.key)) return EventPaletteBuild.Review(EventColorProblem.DUPLICATE_KEY)
                if (Argb32.fromProviderInteger(entry.colorRaw) == null)
                    return EventPaletteBuild.Review(EventColorProblem.INVALID_ARGB)
            }
            return EventPaletteBuild.Ready(EventColorPalette(context, captured))
        }
        private fun entryUnits(entry: EventPaletteEntry): Long = entry.account.name.length.toLong() +
            entry.account.type.length + entry.key.length + entry.colorRaw.length
    }
}

/** Constructor is module-internal; even a forged selection is checked against the current palette. */
@ConsistentCopyVisibility
data class EventPaletteSelection internal constructor(
    val context: EventPaletteContext,
    val key: String,
    val expectedColor: Argb32,
) { init { scalar(key); require(key.isNotEmpty()) } }
sealed interface EventPaletteRead {
    data object Loading : EventPaletteRead
    data object PermissionRequired : EventPaletteRead
    data object Unavailable : EventPaletteRead
    data object LimitExceeded : EventPaletteRead
    data object Unsupported : EventPaletteRead
    data class Ready(val palette: EventColorPalette) : EventPaletteRead
}

sealed interface EventColorTarget {
    data class ExistingOccurrence(val ref: EventRef) : EventColorTarget
    /** Names one existing master row; this is not authority to propagate an ALL-series edit. */
    data class ExistingMaster(val identity: SeriesIdentity) : EventColorTarget
}
data class EventColorChoiceContext(
    val calendar: CalendarIdentity,
    val target: EventColorTarget,
    val source: ColorReadRevision,
    val sourceToken: EventColorSourceToken,
    val draftSession: String,
    val draftRevision: Long,
) {
    init {
        identityScalars(calendar); token(draftSession); require(draftRevision >= 0)
        val id = when (target) {
            is EventColorTarget.ExistingOccurrence -> target.ref.calendarId
            is EventColorTarget.ExistingMaster -> target.identity.calendarId
        }
        require(id == calendar.calendarId)
    }
}
data class EventColorRights(
    val chooseKey: AccessDecision = AccessDecision.UNKNOWN,
    val resetToDefault: AccessDecision = AccessDecision.UNKNOWN,
)
data class EditableEventColor(
    val context: EventColorChoiceContext,
    val observation: EventColorObservation,
    val rights: EventColorRights = EventColorRights(),
    val palette: EventPaletteRead = EventPaletteRead.Unavailable,
)
sealed interface EventColorChoice {
    data object Keep : EventColorChoice
    data object CalendarDefault : EventColorChoice
    data class Named(val selection: EventPaletteSelection) : EventColorChoice
}
/** First pure API revision only. No persisted envelope, source kind or wire discriminator is allocated. */
data class EventColorIntentV1(val context: EventColorChoiceContext, val choice: EventColorChoice)
sealed interface EventColorPlanResult {
    /** Colour-only no-op: this cannot discard or acknowledge another feature's pending authoring. */
    data object NoChange : EventColorPlanResult
    data class Review(val problem: EventColorProblem) : EventColorPlanResult
    /** Semantic key change only, not a provider plan or permission to dispatch. Empty means explicit default. */
    @ConsistentCopyVisibility
    data class ExistingRowKeyUpdate internal constructor(val context: EventColorChoiceContext, val key: String) : EventColorPlanResult
}
object EventColorPlans {
    fun resolve(current: EditableEventColor, intent: EventColorIntentV1): EventColorPlanResult {
        fun review(problem: EventColorProblem) = EventColorPlanResult.Review(problem)
        if (current.context != intent.context) return review(EventColorProblem.STALE_CONTEXT)
        if (intent.choice == EventColorChoice.Keep) return EventColorPlanResult.NoChange
        val cells = current.observation
        if (listOf(cells.eventColor, cells.eventColorKey, cells.displayColor, cells.calendarColor).any { it == ColorCell.Missing })
            return review(EventColorProblem.MISSING_PROJECTION)
        return when (val choice = intent.choice) {
            EventColorChoice.Keep -> EventColorPlanResult.NoChange
            EventColorChoice.CalendarDefault -> {
                if (current.rights.resetToDefault != AccessDecision.ALLOWED) review(EventColorProblem.RIGHTS_UNPROVEN)
                // Do not normalize present unknown raw values, or replace this explicit action with omission/NULL.
                else EventColorPlanResult.ExistingRowKeyUpdate(current.context, "")
            }
            is EventColorChoice.Named -> {
                if (current.rights.chooseKey != AccessDecision.ALLOWED) return review(EventColorProblem.RIGHTS_UNPROVEN)
                val palette = (current.palette as? EventPaletteRead.Ready)?.palette
                    ?: return review(EventColorProblem.PALETTE_UNAVAILABLE)
                if (palette.context.calendar != current.context.calendar || palette.context.source != current.context.source ||
                    choice.selection.context != palette.context || palette.select(choice.selection.key) != choice.selection)
                    review(EventColorProblem.STALE_PALETTE)
                else EventColorPlanResult.ExistingRowKeyUpdate(current.context, choice.selection.key)
            }
        }
    }
}

sealed interface EventColorCurrent {
    data class CalendarDefault(val preview: Argb32?) : EventColorCurrent
    data class Named(val key: String, val color: Argb32) : EventColorCurrent
    data class RawOverride(val raw: ColorCell, val preview: Argb32?) : EventColorCurrent
    data object Unresolved : EventColorCurrent
}
object EventColorCurrentSelection {
    fun describe(current: EditableEventColor): EventColorCurrent {
        val raw = current.observation
        fun parse(cell: ColorCell) = (cell as? ColorCell.Text)?.raw?.let { Argb32.fromProviderInteger(it) }
        if (listOf(raw.eventColor, raw.eventColorKey, raw.displayColor, raw.calendarColor).any { it == ColorCell.Missing })
            return EventColorCurrent.Unresolved
        val event = parse(raw.eventColor)
        val display = parse(raw.displayColor)
        val calendar = parse(raw.calendarColor)
        val key = (raw.eventColorKey as? ColorCell.Text)?.raw
        if (key.isNullOrEmpty()) {
            if (raw.eventColor == ColorCell.SqlNull) {
                if (display != null && calendar != null && display != calendar) return EventColorCurrent.Unresolved
                return EventColorCurrent.CalendarDefault(display ?: calendar)
            }
            if (event != null && display != null && event != display) return EventColorCurrent.Unresolved
            return EventColorCurrent.RawOverride(raw.eventColor, event)
        }
        val palette = (current.palette as? EventPaletteRead.Ready)?.palette ?: return EventColorCurrent.Unresolved
        if (palette.context.calendar != current.context.calendar || palette.context.source != current.context.source)
            return EventColorCurrent.Unresolved
        val choice = palette.select(key) ?: return EventColorCurrent.Unresolved
        return if (event == choice.expectedColor && (display == null || display == event))
            EventColorCurrent.Named(key, choice.expectedColor) else EventColorCurrent.Unresolved
    }
}

private fun scalar(raw: String) { require(raw.length <= EventColorPalette.MAX_SCALAR) }
private fun token(raw: String) { scalar(raw); require(raw.isNotBlank()) }
private fun accountScalars(account: CalendarAccount) { scalar(account.name); scalar(account.type) }
private fun identityScalars(identity: CalendarIdentity) {
    accountScalars(identity.account); identity.owner?.let(::scalar); identity.providerId?.let(::scalar)
}
private fun identityUnits(identity: CalendarIdentity): Long = identity.account.name.length.toLong() +
    identity.account.type.length + (identity.owner?.length ?: 0) + (identity.providerId?.length ?: 0)
