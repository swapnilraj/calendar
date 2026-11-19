package dev.swapnilraj.calendar.events

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneConflictAdvisoryScreenshotsTest {
    @get:Rule val compose=createComposeRule()
    @After fun reset(){RuntimeEnvironment.setFontScale(1f)}
    private fun capture(state:EditorConflictAdvisoryState,name:String,dark:Boolean=false,rtl:Boolean=false) {
        RuntimeEnvironment.setFontScale(if(rtl)2f else 1f)
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(rtl)2f else density.fontScale),
                LocalLayoutDirection provides if(rtl)LayoutDirection.Rtl else LayoutDirection.Ltr) {
                DaylightTheme(dark=dark) {Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)){PhoneConflictAdvisory(state)}
                }}
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
    @Test fun knownAndPossibleLight()=capture(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED,2,1,true),"phone_conflict_advisory_known_light")
    @Test fun partialCoverageDark()=capture(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED,1,1,false),"phone_conflict_advisory_partial_dark",dark=true)
    @Test fun noSelectedCalendars()=capture(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.NO_CALENDARS_SELECTED),"phone_conflict_advisory_no_selection")
    @Test fun unfinishedTime()=capture(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.UNFINISHED_TIME),"phone_conflict_advisory_unfinished")
    @Test fun recurringFirstOccurrenceRtlLarge()=capture(EditorConflictAdvisoryState(EditorConflictAdvisoryStatus.ASSESSED,complete=true,firstOccurrenceOnly=true),"phone_conflict_advisory_first_rtl_large",rtl=true)
}
