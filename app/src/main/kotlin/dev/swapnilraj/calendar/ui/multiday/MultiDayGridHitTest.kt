package dev.swapnilraj.calendar.ui.multiday

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.time.LocalDate

/** Physical observed bounds; a partially visible date can accept only its visible pixels. */
internal data class MultiDayColumnBounds(val date: LocalDate, val left: Float, val right: Float)
internal fun multiDayDateAtRoot(point: Offset, viewport: Rect, columns: List<MultiDayColumnBounds>): LocalDate? {
    if (!viewport.contains(point)) return null
    return columns.singleOrNull {
        val left = maxOf(it.left, viewport.left)
        val right = minOf(it.right, viewport.right)
        right > left && point.x >= left && point.x < right
    }?.date
}
