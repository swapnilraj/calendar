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
            assertEquals("DAY", settings.calendarView.first())
            assertTrue(settings.setCalendarView("MONTH"))
            assertEquals("MONTH", Settings(context).calendarView.first())
            assertFalse(settings.setCalendarView("unsupported"))
            assertEquals("MONTH", settings.calendarView.first())
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

            fun calendar(id: Long, account: String = "owner", visible: Boolean = true, name: String = "Calendar $id") =
                dev.swapnilraj.calendar.core.CalendarInfo(CalendarId(id), name, 0xff397d70.toInt(),
                    dev.swapnilraj.calendar.core.CalendarAccount(account, "com.google"), account, null, 700, visible, true,
                    dev.swapnilraj.calendar.core.CalendarCapabilities(
                        createEvents = dev.swapnilraj.calendar.core.AccessDecision.ALLOWED))
            val known = listOf(calendar(2), calendar(9))
            assertTrue(settings.reconcileCalendars(known))
            assertTrue(settings.setCalendarSelection(CalendarSelection.Explicit(setOf(CalendarId(2))), known))
            assertTrue(settings.setDefaultCalendar(CalendarId(2), known))
            val discovered = listOf(calendar(2, name = "Renamed"), calendar(9), calendar(12), calendar(13, visible = false))
            assertTrue(settings.reconcileCalendars(discovered))
            assertEquals(CalendarSelection.Explicit(setOf(CalendarId(2), CalendarId(12))), settings.calendarSelection.first())
            assertEquals(CalendarId(2), settings.defaultCalendar.first())
            assertEquals("MONTH", settings.calendarView.first())
            assertTrue(settings.reconcileCalendars(discovered.filter { it.id != CalendarId(2) }))
            assertEquals(CalendarSelection.Explicit(setOf(CalendarId(12))), settings.calendarSelection.first())
            assertNull(settings.defaultCalendar.first())
            assertTrue(settings.setDefaultCalendar(CalendarId(12), discovered))
            assertTrue(settings.reconcileCalendars(listOf(calendar(9), calendar(12, account = "different-account"))))
            assertNull(settings.defaultCalendar.first()) // A reused row ID cannot transfer the old default.
            val current = listOf(calendar(9), calendar(12, account = "different-account"))
            assertTrue(settings.setCalendarSelection(CalendarSelection.Explicit(emptySet()), current))
            assertTrue(settings.reconcileCalendars(current.map { it.copy(displayName = "Another name") }))
            assertEquals(CalendarSelection.Explicit(emptySet()), settings.calendarSelection.first())
            assertEquals(77f, settings.hourHeight.first(), 0f)
            assertTrue(settings.setCalendarSelection(CalendarSelection.ProviderVisible))
            assertTrue(settings.setCalendarView("DAY"))
        }
    }
}
