package dev.swapnilraj.calendar.data.calendar

import android.database.Cursor
import android.database.CursorWrapper
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** App-side materialization limits. The provider/SDK may allocate a CursorWindow first. */
data class ProviderReadLimits(
    val maxRows: Int,
    val maxCells: Long,
    val maxTextUnits: Long,
    val maxCellUnits: Int = 64 * 1024,
    val maxColumns: Int = 128,
) {
    init { require(maxRows > 0 && maxCells > 0 && maxTextUnits > 0 && maxCellUnits > 0 && maxColumns > 0) }
}

data class ProviderReadBudgets(
    val calendars: ProviderReadLimits = ProviderReadLimits(2_000, 32_000, 256 * 1024),
    val range: ProviderReadLimits = ProviderReadLimits(12_000, 250_000, 2 * 1024 * 1024),
    val details: ProviderReadLimits = ProviderReadLimits(8_192, 128_000, 1024 * 1024, 256 * 1024),
    val mutation: ProviderReadLimits = ProviderReadLimits(20_000, 250_000, 4 * 1024 * 1024),
    val search: ProviderReadLimits = ProviderReadLimits(100_000, 2_000_000, 8 * 1024 * 1024),
    val maxRangeInstances: Int = 10_000,
    val maxDetailInstances: Int = 256,
    val maxDetailChildren: Int = 2_000,
    val maxSeriesChildren: Int = 4_000,
    val maxCanonicalBytes: Int = 1024 * 1024,
    val maxSearchRetainedUnits: Long = 2 * 1024 * 1024,
) {
    init { require(listOf(maxRangeInstances, maxDetailInstances, maxDetailChildren, maxSeriesChildren, maxCanonicalBytes).all { it > 0 }); require(maxSearchRetainedUnits > 0) }
}

internal class ProviderReadLimitExceeded : Exception()
internal class ProviderReadBudget(val limits: ProviderReadLimits) {
    private var rows = 0L
    private var cells = 0L
    private var text = 0L
    internal val consumedTextUnits: Long get() = text
    fun row(columns: Int) {
        if (columns > limits.maxColumns || rows >= limits.maxRows || cells > limits.maxCells - columns) throw ProviderReadLimitExceeded()
        rows++; cells += columns
    }
    fun text(value: String?, cell: Boolean = true) {
        val count = value?.length?.toLong() ?: 0
        if (cell && count > limits.maxCellUnits || count > limits.maxTextUnits - text) throw ProviderReadLimitExceeded()
        text += count
    }
    fun encoded(units: Long) {
        if (units < 0 || units > limits.maxTextUnits - text) throw ProviderReadLimitExceeded()
        text += units
    }
}
private val activeBudget = ThreadLocal<ProviderReadBudget?>()
internal fun readBudgetContext(limits: ProviderReadLimits): kotlin.coroutines.CoroutineContext =
    if (activeBudget.get() == null) activeBudget.asContextElement(ProviderReadBudget(limits)) else kotlin.coroutines.EmptyCoroutineContext
internal fun currentReadBudget(): ProviderReadBudget? = activeBudget.get()
internal suspend fun <T> withFreshReadBudget(limits: ProviderReadLimits, block: suspend () -> T): T =
    withContext(activeBudget.asContextElement(ProviderReadBudget(limits))) { block() }
internal suspend fun <T> withReadBudget(limits: ProviderReadLimits, block: suspend () -> T): T {
    if (activeBudget.get() != null) return block()
    return withContext(activeBudget.asContextElement(ProviderReadBudget(limits))) { block() }
}

/** Charge each raw row/cell once, including identity prepasses that rewind the cursor. */
internal fun Cursor.bounded(maxRows: Int = Int.MAX_VALUE): Cursor {
    val budget = currentReadBudget() ?: return this
    try {
        if (columnCount > budget.limits.maxColumns) throw ProviderReadLimitExceeded()
        columnNames.forEach { budget.text(it) }
    } catch (failure: Exception) { close(); throw failure }
    return object : CursorWrapper(this) {
        private val seenRows = mutableSetOf<Int>()
        private val seenText = mutableSetOf<Long>()
        private fun checked(found: Boolean): Boolean {
            if (found && position >= 0 && position !in seenRows) {
                if (seenRows.size >= maxRows) throw ProviderReadLimitExceeded()
                budget.row(columnCount)
                seenRows += position
            }
            return found
        }
        override fun moveToNext() = checked(super.moveToNext())
        override fun moveToFirst() = checked(super.moveToFirst())
        override fun moveToLast() = checked(super.moveToLast())
        override fun moveToPrevious() = checked(super.moveToPrevious())
        override fun move(offset: Int) = checked(super.move(offset))
        override fun moveToPosition(position: Int) = checked(super.moveToPosition(position))
        override fun getString(columnIndex: Int): String? {
            if (getType(columnIndex) == Cursor.FIELD_TYPE_BLOB) throw ProviderReadLimitExceeded()
            val value = super.getString(columnIndex)
            val key = (position.toLong() shl 32) or columnIndex.toLong()
            if (key !in seenText) {
                budget.text(value)
                seenText += key
            }
            return value
        }
    }
}

/** Upper bound on escaped JSON UTF-8 bytes, before any JSON tree/string is constructed. */
internal fun checkCanonical(value: Any?, maxBytes: Int, overheadBytes: Long = 0): Long {
    if (overheadBytes < 0 || overheadBytes > maxBytes) throw ProviderReadLimitExceeded()
    var bytes = overheadBytes
    fun add(amount: Long) { if (amount < 0 || amount > maxBytes.toLong() - bytes) throw ProviderReadLimitExceeded(); bytes += amount }
    fun visit(item: Any?) {
        when (item) {
            null -> add(4)
            is String -> { add(2); add(item.length.toLong() * 6) }
            is Map<*, *> -> { add(2); item.forEach { (key, value) -> visit(key.toString()); add(2); visit(value) } }
            is Iterable<*> -> { add(2); item.forEach { visit(it); add(1) } }
            else -> add(item.toString().length.toLong() + 2)
        }
    }
    visit(value)
    currentReadBudget()?.encoded(bytes)
    return bytes
}
internal fun checkStoredJson(value: String, maxBytes: Int) {
    // UTF-8 never uses fewer bytes than UTF-16 code units. Bound before encoding/parsing.
    if (value.length > maxBytes) throw ProviderReadLimitExceeded()
    var utf8Bytes = 0L
    var utf16Index = 0
    while (utf16Index < value.length) {
        val code = value[utf16Index].code
        utf8Bytes += when {
            code <= 0x7f -> 1
            code <= 0x7ff -> 2
            Character.isHighSurrogate(value[utf16Index]) && utf16Index + 1 < value.length && Character.isLowSurrogate(value[utf16Index + 1]) -> { utf16Index++; 4 }
            else -> 3 // Conservative for unpaired surrogates; no temporary UTF-8 allocation.
        }
        if (utf8Bytes > maxBytes) throw ProviderReadLimitExceeded()
        utf16Index++
    }
    val limits = currentReadBudget()?.limits ?: ProviderReadBudgets().mutation
    // Reject deeply nested/huge legacy JSON structures and overlong decoded cells BEFORE
    // JSONObject/JSONArray can allocate a tree. This is a budget scan, not a JSON validator.
    var depth = 0
    var nodes = 0L
    var objects = 0
    var index = 0
    while (index < value.length) {
        when (value[index]) {
            '{', '[' -> {
                depth++; nodes++
                if (value[index] == '{') objects++
                if (depth > limits.maxColumns || nodes > limits.maxCells || objects > limits.maxRows) throw ProviderReadLimitExceeded()
            }
            '}', ']' -> depth--
            ',', ':' -> { nodes++; if (nodes > limits.maxCells) throw ProviderReadLimitExceeded() }
            '"' -> {
                nodes++
                if (nodes > limits.maxCells) throw ProviderReadLimitExceeded()
                var units = 0
                index++
                while (index < value.length && value[index] != '"') {
                    if (value[index] == '\\') {
                        index++
                        if (index < value.length && value[index] == 'u') index += 4
                    }
                    units++
                    if (units > limits.maxCellUnits) throw ProviderReadLimitExceeded()
                    index++
                }
            }
        }
        index++
    }
    currentReadBudget()?.encoded(value.length.toLong())
}

internal fun checkPatchInput(patch: dev.swapnilraj.calendar.core.EventPatch, maxBytes: Int) {
    val fields = listOf(patch.title, patch.location, patch.description)
        .mapNotNull { (it as? dev.swapnilraj.calendar.core.FieldChange.Set)?.value }
    fields.forEach { currentReadBudget()?.text(it) }
    checkCanonical(listOf(fields, (patch.alertMinutes as? dev.swapnilraj.calendar.core.FieldChange.Set)?.value), maxBytes)
}
