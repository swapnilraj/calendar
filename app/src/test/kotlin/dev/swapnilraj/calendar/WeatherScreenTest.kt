package dev.swapnilraj.calendar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.data.*
import dev.swapnilraj.calendar.ui.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import dev.swapnilraj.calendar.weather.*
import java.time.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherScreenTest {
    @get:Rule val compose=createComposeRule()
    private val zone=ZoneId.of("Europe/London")
    private val now=ZonedDateTime.of(2026,11,17,15,20,0,0,zone)
    private fun show(kind: String) {
        val date=now.toLocalDate()
        val forecast=(0..24).map { i ->
            val condition=when {kind=="rainy_evening" && i in 15..22->63;kind=="snowy_morning" && i in 5..11->73;else->0}
            ForecastHour(date.atStartOfDay(zone).toInstant().plusSeconds(i*3600L),condition,
                if(kind=="snowy_morning") -2.0 else if(i in 8..16) 11.0 else 6.0,
                if(condition==0) 5.0 else 80.0,if(condition==0) 0.0 else if(kind=="snowy_morning")0.8 else 3.0,i in 8..16)
        }
        val events=if(kind=="snowy_morning") listOf(
            CalEvent(1,date.atTime(8,30).atZone(zone).toInstant(),date.atTime(9,30).atZone(zone).toInstant(),"Morning planning",0xff397d70.toInt()),
            CalEvent(2,date.atTime(11,0).atZone(zone).toInstant(),date.atTime(12,0).atZone(zone).toInstant(),"Studio session",0xff526e9e.toInt())) else listOf(
            CalEvent(1,date.atTime(16,0).atZone(zone).toInstant(),date.atTime(17,0).atZone(zone).toInstant(),"Finish the proposal",0xff397d70.toInt()),
            CalEvent(2,date.atTime(18,0).atZone(zone).toInstant(),date.atTime(19,0).atZone(zone).toInstant(),"Evening walk",0xffb06a47.toInt()))
        compose.setContent {
            DaylightTheme {
                DayScreen(now,true,64f,{ _,_->flowOf(CalendarState.Ready(events)) },{},{},
                    initialScrollHour=if(kind=="snowy_morning")5.0 else 13.0,
                    actions={ WeatherOptions(WeatherState(forecast,"London"),{},{}) },
                    summary={ daySummary(forecast,date,zone)?.let { WeatherSummary(it) } },
                    background={ hoursForDay(forecast,date,zone)?.let { WeatherBackground(it) } })
            }
        }
        compose.waitForIdle()
        if(kind=="dry_day") compose.onAllNodesWithContentDescription("Precipitation chance",substring=true).assertCountEquals(0)
        else compose.onNodeWithContentDescription("Precipitation chance 80 percent").assertIsDisplayed()
    }
    @Test @Config(qualifiers="+notnight") fun dryLight() { show("dry_day");compose.onRoot().captureRoboImage("src/test/screenshots/dry_day_light.png") }
    @Test @Config(qualifiers="+night") fun dryDark() { show("dry_day");compose.onRoot().captureRoboImage("src/test/screenshots/dry_day_dark.png") }
    @Test @Config(qualifiers="+notnight") fun rainLight() { show("rainy_evening");compose.onRoot().captureRoboImage("src/test/screenshots/rainy_evening_light.png") }
    @Test @Config(qualifiers="+night") fun rainDark() { show("rainy_evening");compose.onRoot().captureRoboImage("src/test/screenshots/rainy_evening_dark.png") }
    @Test @Config(qualifiers="+notnight") fun snowLight() { show("snowy_morning");compose.onRoot().captureRoboImage("src/test/screenshots/snowy_morning_light.png") }
    @Test @Config(qualifiers="+night") fun snowDark() { show("snowy_morning");compose.onRoot().captureRoboImage("src/test/screenshots/snowy_morning_dark.png") }
}
