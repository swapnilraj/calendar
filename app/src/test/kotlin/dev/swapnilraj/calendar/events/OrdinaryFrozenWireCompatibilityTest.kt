package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.FrozenEditorSubmission
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Literal fixtures captured from the shipped v1 ordinary command shape, before generation envelopes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrdinaryFrozenWireCompatibilityTest {
    private val ref = EventRef(CalendarId(2), 42, Instant.parse("2030-06-15T00:00:00Z"), Instant.parse("2030-06-16T00:00:00Z"))
    @Test fun ordinaryCreateRetainsExactLegacyBytesAndRoundTripsWithoutRecurrence() {
        val expected = """{"version":1,"operationId":"stable-create","kind":"create","draft":{"calendar":2,"time":{"kind":"allDay","start":"2030-06-15","end":"2030-06-16"},"zone":"UTC","title":"Synthetic","location":"","description":"","alerts":[10]}}"""
        val command = FrozenEditorSubmission.Create("stable-create", EventDraft(CalendarId(2),
            EventTime.AllDay(CalendarDateRange.day(LocalDate.parse("2030-06-15"))), ZoneId.of("UTC"), "Synthetic", alertMinutes = listOf(10)))
        assertEquals(expected, command.encode().toString())
        assertEquals(command, FrozenEditorSubmission.decode(JSONObject(expected)))
        assertFalse(command.encode().getJSONObject("draft").has("recurrence"))
    }
    @Test fun sparseOrdinaryUpdateRetainsExactLegacyBytesAndKeepFields() {
        val expected = """{"version":1,"operationId":"stable-update","kind":"update","ref":{"calendar":2,"event":42,"start":"2030-06-15T00:00:00Z","end":"2030-06-16T00:00:00Z"},"token":"original-token","patch":{"title":"Revised"}}"""
        val command = FrozenEditorSubmission.Update("stable-update", ref, EditToken("original-token"), EventPatch(title = FieldChange.Set("Revised")))
        assertEquals(expected, command.encode().toString())
        assertEquals(command, FrozenEditorSubmission.decode(JSONObject(expected)))
        assertEquals(FieldChange.Keep, command.patch.time)
        assertEquals(FieldChange.Keep, command.patch.eventZone)
        assertEquals(FieldChange.Keep, command.patch.alertMinutes)
    }
    @Test fun ordinaryDeleteRetainsExactLegacyBytesAndOriginalToken() {
        val expected = """{"version":1,"operationId":"stable-delete","kind":"delete","ref":{"calendar":2,"event":42,"start":"2030-06-15T00:00:00Z","end":"2030-06-16T00:00:00Z"},"token":"original-token"}"""
        val command = FrozenEditorSubmission.Delete("stable-delete", ref, EditToken("original-token"))
        assertEquals(expected, command.encode().toString())
        assertEquals(command, FrozenEditorSubmission.decode(JSONObject(expected)))
    }
}
