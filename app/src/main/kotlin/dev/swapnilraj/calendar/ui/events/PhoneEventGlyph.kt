package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Original simple Daylight line drawings; decorative row icons, never external brand assets. */
internal enum class PhoneEventGlyph { CLOSE, CALENDAR, CLOCK, ZONE, PLACE, NOTES, ALERT, REPEAT, VISIBILITY, AVAILABILITY, MORE }
@Composable
internal fun PhoneEventIcon(kind: PhoneEventGlyph, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(ink, p(x,y), p(xx,yy), 1.6f * unit)
        fun circle(x: Float, y: Float, radius: Float) = drawCircle(ink, radius * unit, p(x,y), style = Stroke(1.6f * unit))
        when (kind) {
            PhoneEventGlyph.CLOSE -> { line(6f,6f,18f,18f); line(6f,18f,18f,6f) }
            PhoneEventGlyph.CALENDAR -> { drawRect(ink,p(4f,5f),Size(16*unit,15*unit),style=Stroke(1.6f*unit)); line(4f,10f,20f,10f); line(8f,3f,8f,7f); line(16f,3f,16f,7f) }
            PhoneEventGlyph.CLOCK -> { circle(12f,12f,8f); line(12f,7f,12f,12f); line(12f,12f,16f,14f) }
            PhoneEventGlyph.ZONE -> { circle(12f,12f,8f); drawOval(ink,p(8f,4f),Size(8*unit,16*unit),style=Stroke(1.6f*unit)); line(4f,12f,20f,12f) }
            PhoneEventGlyph.PLACE -> { val path=Path().apply { moveTo(12*unit,21*unit); cubicTo(9*unit,17*unit,5*unit,13*unit,5*unit,9*unit); cubicTo(5*unit,1*unit,19*unit,1*unit,19*unit,9*unit); cubicTo(19*unit,13*unit,15*unit,17*unit,12*unit,21*unit) }; drawPath(path,ink,style=Stroke(1.6f*unit)); circle(12f,9f,2f) }
            PhoneEventGlyph.NOTES -> { line(5f,6f,19f,6f); line(5f,10f,19f,10f); line(5f,14f,19f,14f); line(5f,18f,14f,18f) }
            PhoneEventGlyph.ALERT -> { val path=Path().apply { moveTo(5*unit,17*unit); lineTo(7*unit,14*unit); lineTo(7*unit,9*unit); cubicTo(7*unit,2*unit,17*unit,2*unit,17*unit,9*unit); lineTo(17*unit,14*unit); lineTo(19*unit,17*unit); close() }; drawPath(path,ink,style=Stroke(1.6f*unit)); line(10f,21f,14f,21f) }
            PhoneEventGlyph.REPEAT -> { line(5f,7f,19f,7f); line(19f,7f,16f,4f); line(19f,7f,16f,10f); line(19f,17f,5f,17f); line(5f,17f,8f,14f); line(5f,17f,8f,20f) }
            PhoneEventGlyph.VISIBILITY -> { drawRoundRect(ink,p(6f,10f),Size(12*unit,11*unit),style=Stroke(1.6f*unit)); drawArc(ink,180f,180f,false,p(8f,3f),Size(8*unit,12*unit),style=Stroke(1.6f*unit)) }
            PhoneEventGlyph.AVAILABILITY -> { circle(12f,12f,8f); line(8f,12f,11f,15f); line(11f,15f,17f,9f) }
            PhoneEventGlyph.MORE -> { for (y in listOf(5f,12f,19f)) drawCircle(ink,1.5f*unit,p(12f,y)) }
        }
    }
}
