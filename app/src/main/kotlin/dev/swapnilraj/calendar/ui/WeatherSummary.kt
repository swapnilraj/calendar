package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.swapnilraj.calendar.weather.*
import kotlin.math.*

@Composable
fun RowScope.WeatherSummary(summary: DaySummary, stale: Boolean = false) {
    WeatherIcon(summary.condition)
    Spacer(Modifier.width(6.dp))
    Text("${summary.high.toInt()}° / ${summary.low.toInt()}°", style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.semantics { contentDescription = "High ${summary.high.toInt()} degrees, low ${summary.low.toInt()} degrees${if(stale) ", cached forecast" else ""}" })
    if (summary.condition.wet && summary.precipProbability != null) {
        Spacer(Modifier.width(8.dp))
        Text("${summary.precipProbability!!.toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Precipitation chance ${summary.precipProbability!!.toInt()} percent" })
    }
}

/** Small original vector marks; no vendor icon or font assets. */
@Composable
private fun WeatherIcon(condition: Condition) {
    val cloud = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(20.dp).semantics { contentDescription = condition.label }) {
        val unit = size.width / 20
        fun pt(x: Float,y: Float) = Offset(x * unit,y * unit)
        if (condition == Condition.CLEAR || condition == Condition.PARTLY_CLOUDY) {
            val center = pt(9f,8f)
            drawCircle(Color(0xffc88a35), 3.2f * unit, center)
            for (i in 0..7) {
                val angle = i * PI / 4
                drawLine(Color(0xffc88a35),center + pt((cos(angle)*4.8).toFloat(),(sin(angle)*4.8).toFloat()),
                    center + pt((cos(angle)*6.1).toFloat(),(sin(angle)*6.1).toFloat()),unit,StrokeCap.Round)
            }
        }
        if (condition != Condition.CLEAR) {
            drawCircle(cloud,3f*unit,pt(6f,11f));drawCircle(cloud,4f*unit,pt(11f,9f));drawCircle(cloud,3f*unit,pt(15f,11f))
            drawLine(cloud,pt(6f,13f),pt(15f,13f),3f*unit,StrokeCap.Round)
        }
        when (condition) {
            Condition.RAIN,Condition.DRIZZLE -> for(x in listOf(6f,11f,16f)) drawLine(accent,pt(x,16f),pt(x-1,18f),unit,StrokeCap.Round)
            Condition.SNOW -> for(x in listOf(6f,11f,16f)) drawCircle(accent,unit,pt(x,17f))
            Condition.THUNDERSTORM -> drawPath(Path().apply { moveTo(11*unit,13*unit);lineTo(8*unit,17*unit);lineTo(11*unit,17*unit);lineTo(10*unit,20*unit);lineTo(15*unit,15*unit);lineTo(12*unit,15*unit);close() },accent)
            Condition.FOG -> drawLine(cloud,pt(4f,18f),pt(17f,18f),unit,StrokeCap.Round)
            else -> Unit
        }
    }
}
