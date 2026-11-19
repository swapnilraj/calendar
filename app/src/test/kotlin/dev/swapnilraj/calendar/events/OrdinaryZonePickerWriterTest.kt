package dev.swapnilraj.calendar.events

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real phone picker → ordinary controller → CalendarWriter/SQLite journal → exact synthetic provider values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrdinaryZonePickerWriterTest {
    @get:Rule val compose=createComposeRule()
    private val harness=NullZoneTravelEditTest()
    private fun journey(editTime:Boolean)=runBlocking {harness.fixture {f->
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val (source,launch)=f.prepare();val c=f.open(launch,source);val original=c.state.fields
        assertEquals("Asia/Tokyo",original.zone);assertNull(source.details.eventZone)
        val before=f.provider.events.map { it.toMap() }.single();val reminders=f.provider.reminders.map { it.toMap() }
        compose.setContent {DaylightTheme {PhoneEventEditor(c,listOf(source.details.calendar!!),{}, {}, {})}}
        compose.waitForIdle()
        if(editTime) c.change {it.copy(startTime="19:00",endTime="20:00")}
        compose.onNodeWithText("Time zone").performScrollTo().performClick()
        compose.onNodeWithText("Search time zones").performTextInput("Asia/Tokyo")
        compose.onNode(hasText("Asia/Tokyo") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.RadioButton)).performClick()
        compose.waitForIdle();assertTrue(c.state.zoneAuthored)
        if(!editTime) assertEquals(original,c.state.fields)
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {!c.state.saving && c.state.result is MutationResult.LocalSaved}
        val patch=f.patches.single();assertEquals(FieldChange.Set(ZoneId.of("Asia/Tokyo")),patch.eventZone)
        if(editTime) assertEquals(FieldChange.Set(EventTime.Timed(Instant.parse("2028-06-15T10:00:00Z"),Instant.parse("2028-06-15T11:00:00Z"))),patch.time)
        else assertEquals(FieldChange.Keep,patch.time)
        val after=f.provider.events.map { it.toMap() }.single();assertEquals("Asia/Tokyo",after["eventTimezone"]);assertEquals("Asia/Tokyo",after["eventEndTimezone"])
        val changed=setOf("eventTimezone","eventEndTimezone")+if(editTime) setOf("dtstart","dtend") else emptySet()
        assertEquals(before.filterKeys {it !in changed},after.filterKeys {it !in changed})
        assertEquals(reminders,f.provider.reminders.map { it.toMap() });assertEquals(1,f.provider.batchCount)
    }}
    @Test fun choosingSameRenderedZoneAuthorsOnlyZoneOnNativePhoneSave()=journey(editTime=false)
    @Test fun authoredTimesAndSameRenderedZoneSaveBothExactDeltasOnNativePhone()=journey(editTime=true)
}
