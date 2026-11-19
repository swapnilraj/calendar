package dev.swapnilraj.calendar.settings

import dev.swapnilraj.calendar.data.settings.CalendarSettingChange
import dev.swapnilraj.calendar.ui.settings.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PhoneSettingsStateTest {
    private val zone = ZoneId.of("UTC")

    @Test fun pendingAcknowledgmentBlocksAnotherEditorOrCancellation() {
        val state = PhoneSettingsState()
        state.open(PhoneSettingEditor.DURATION, CalendarSettingsOptions(), zone)
        state.raw = " 90 "
        state.submit(CalendarSettingChange.Duration(90), 7)
        state.open(PhoneSettingEditor.ZONE, CalendarSettingsOptions(), zone)
        state.cancelEditor()
        assertEquals(PhoneSettingEditor.DURATION, state.editor)
        assertEquals(" 90 ", state.raw)
        state.observeResult(false, null, 7)
        assertTrue(state.awaitingSave)
        state.observeResult(false, null, 8)
        assertEquals(PhoneSettingEditor.NONE, state.editor)
        assertFalse(state.awaitingSave)
    }

    @Test fun lateRevisionAfterFailureCannotCloseALaterEditor() {
        val state = PhoneSettingsState()
        state.open(PhoneSettingEditor.DURATION, CalendarSettingsOptions(), zone)
        state.raw = " 90 "
        state.submit(CalendarSettingChange.Duration(90), 0)
        state.observeResult(false, "Unavailable", 0)
        assertEquals(" 90 ", state.raw)
        state.cancelEditor()
        state.open(PhoneSettingEditor.ZONE, CalendarSettingsOptions(), zone)
        state.raw = "Europe/Paris"
        state.observeResult(false, null, 1)
        assertEquals(PhoneSettingEditor.ZONE, state.editor)
        assertEquals("Europe/Paris", state.raw)
    }

    @Test fun rawValidationDoesNotNormalizeTheRetainedInput() {
        val state = PhoneSettingsState()
        state.open(PhoneSettingEditor.DURATION, CalendarSettingsOptions(), zone)
        for (raw in listOf("0", "1441", "2147483648", "invalid")) {
            state.raw = raw
            assertNull(state.rawChange())
            assertEquals(raw, state.raw)
        }
        state.raw = " 135 "
        assertEquals(CalendarSettingChange.Duration(135), state.rawChange())
        assertEquals(" 135 ", state.raw)
        assertFalse(state.awaitingSave)
    }
}
