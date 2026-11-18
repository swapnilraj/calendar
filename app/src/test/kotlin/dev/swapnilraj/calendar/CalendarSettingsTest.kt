package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarSelection
import dev.swapnilraj.calendar.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSettingsTest {
    @Test fun selectionPersistsIndependentlyOfZoomAndCreationDefault() = runBlocking {
        withTimeout(10_000) {
            val context = RuntimeEnvironment.getApplication()
            val settings = Settings(context)
            assertEquals(CalendarSelection.ProviderVisible, settings.calendarSelection.first())
            settings.setHourHeight(77f)
            assertTrue(settings.setCalendarSelection(CalendarSelection.Explicit(emptySet())))
            assertEquals(CalendarSelection.Explicit(emptySet()), Settings(context).calendarSelection.first())
            assertEquals(77f, settings.hourHeight.first(), 0f)

            val selection = CalendarSelection.Explicit(setOf(CalendarId(2), CalendarId(9)))
            assertTrue(settings.setCalendarSelection(selection))
            assertTrue(settings.setDefaultCalendar(CalendarId(11)))
            val reopened = Settings(context)
            assertEquals(selection, reopened.calendarSelection.first())
            assertEquals(CalendarId(11), reopened.defaultCalendar.first())
            assertTrue(reopened.setDefaultCalendar(null))
            assertNull(settings.defaultCalendar.first())
            assertEquals(selection, settings.calendarSelection.first())
            assertTrue(settings.setCalendarSelection(CalendarSelection.ProviderVisible))
            assertEquals(CalendarSelection.ProviderVisible, reopened.calendarSelection.first())
            assertEquals(77f, reopened.hourHeight.first(), 0f)
        }
    }
}
