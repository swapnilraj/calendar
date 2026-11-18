package dev.swapnilraj.calendar.events

import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventEditorMetadataTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val initial = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z")), ZoneOffset.UTC, "Private appointment", availability = 0, privacy = 2, targetIdentity = eventTestIdentity())
    private val ref = EventRef(initial.calendarId, 42, (initial.time as EventTime.Timed).start, (initial.time as EventTime.Timed).end)
    private val store = EventEditorControllerTest.Store()
    private val fixture = EventEditorControllerTest()
    private val writer = fixture.Writer()
    private fun source(draft: EventDraft = initial) = EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 1), null,
        availability = draft.availability, privacy = draft.privacy), EditToken("source"), EditableField.entries.toSet())
    private fun editor(draft: EventDraft = initial, existing: EditableEvent? = null) = EventEditorController(draft, existing, "metadata", writer, store, scope)
    @After fun stop() { scope.cancel() }
    @Test fun privateCreateAndBrowserSeedPreserveTypedMetadata() = runBlocking {
        val launch = (prepareNewDraft(initial, "metadata", true, store) as DraftPreparationResult.Ready).launch
        assertEquals(initial, launch.seed(LocalDate.parse("2040-01-01"), ZoneOffset.UTC))
        val editor = editor(); assertEquals("2", editor.state.fields.privacy); editor.save()
        assertEquals(2, writer.createdDrafts.single().privacy); assertEquals(0, writer.createdDrafts.single().availability)
    }
    @Test fun unchangedUnknownEditMetadataStaysKeepAndExplicitChoiceBecomesSet() {
        val unknown = initial.copy(availability = 87, privacy = 99)
        val editor = editor(unknown, source(unknown)); editor.change { it.copy(title = "Changed title") }; editor.save()
        assertEquals(FieldChange.Keep, writer.patch!!.privacy); assertEquals(FieldChange.Keep, writer.patch!!.availability)
        val another = editor(unknown, source(unknown)); another.change { it.copy(privacy = "0", availability = "1") }; another.save()
        assertEquals(FieldChange.Set(0), writer.patch!!.privacy); assertEquals(FieldChange.Set(1), writer.patch!!.availability)
    }
    @Test fun explicitNullableMetadataPatchAndFrozenRoundTripRemainDistinctFromKeep() {
        val patch = EventPatch(privacy = FieldChange.Set(null), availability = FieldChange.Set(1))
        val command = FrozenEditorSubmission.Update("operation", ref, EditToken("source"), patch)
        assertEquals(command, FrozenEditorSubmission.decode(command.encode()))
        assertTrue(command.encode().getJSONObject("patch").isNull("privacy"))
        val keep = FrozenEditorSubmission.Update("operation", ref, EditToken("source"), EventPatch())
        assertFalse(keep.encode().getJSONObject("patch").has("privacy"))
        assertFalse(keep.encode().getJSONObject("patch").has("availability"))
    }
    @Test fun oldFrozenCreateOmitsAbsentMetadataAndDecodesUnchanged() {
        val command = FrozenEditorSubmission.Create("operation", initial.copy(availability = null, privacy = null))
        val encoded = command.encode()
        assertFalse(encoded.getJSONObject("draft").has("privacy")); assertFalse(encoded.getJSONObject("draft").has("availability"))
        assertEquals(command, FrozenEditorSubmission.decode(encoded))
        val known = FrozenEditorSubmission.Create("known", initial)
        assertEquals(known, FrozenEditorSubmission.decode(known.encode()))
    }
    @Test fun missingOldMetadataBaselineRequiresReviewInsteadOfAdoptingToken() = runBlocking {
        val first = editor(existing = source()); first.change { it.copy(title = "Retained") }; first.flushDraft()
        val record = store.records.getValue("metadata"); val raw = JSONObject(record.payload)
        val baseline = JSONObject(raw.getString("sourceBaseline"))
        listOf("availability", "privacy", "sourceAvailability", "sourcePrivacy").forEach(baseline::remove)
        raw.put("sourceBaseline", baseline.toString()); raw.remove("privacy"); raw.remove("availability")
        store.records["metadata"] = record.copy(payload = raw.toString())
        val restored = editor(existing = source())
        assertTrue(restored.state.reviewRequired); assertEquals("", restored.state.fields.privacy)
        restored.save(); assertEquals(0, writer.updates)
    }
    @Test fun signedAlertOffsetsRoundTripAndDefaultSentinelIsRejected() {
        val fields = EventEditorController.fieldsFor(initial).copy(alerts = "-540,0,15")
        assertEquals(listOf(-540, 0, 15), EventEditorController.normalizedDraft(fields).alertMinutes)
        try { EventEditorController.normalizedDraft(fields.copy(alerts = CalendarContract.Reminders.MINUTES_DEFAULT.toString())); fail("sentinel must resolve first") }
        catch (_: InvalidInput) { }
    }
    @Test fun frozenRecoveryKeepsMetadataAndRejectsContradictoryRawChoice() = runBlocking {
        writer.next = MutationResult.Uncertain("ignored", null)
        val editor = editor(); editor.save()
        val record = store.records.getValue("metadata")
        val command = JSONObject(record.payload).getJSONObject("submission")
        assertEquals(2, command.getJSONObject("draft").getInt("privacy"))
        val bad = record.copy(payload = JSONObject(record.payload).put("privacy", "3").toString())
        assertNull(DraftBrowserCodec.read(bad).launch)
        assertNotNull(DraftBrowserCodec.read(record).launch)
    }
}
