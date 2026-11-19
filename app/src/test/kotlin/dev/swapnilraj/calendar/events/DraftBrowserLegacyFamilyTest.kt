package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DraftBrowserLegacyFamilyTest {
    private suspend fun ordinary(): DraftRecord {
        val store = EventEditorControllerTest.Store()
        val time = EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z"))
        prepareNewDraft(EventDraft(CalendarId(2), time, ZoneOffset.UTC, "Ordinary", targetIdentity = eventTestIdentity()), "ordinary", true, store)
        return store.records.getValue("ordinary")
    }
    @Test fun unknownOrMalformedFeatureCannotMasqueradeAsOrdinaryDraft() = runBlocking {
        val original = ordinary()
        assertNotNull(DraftBrowserCodec.read(original).launch)
        val legacy = listOf(EventEditorController.LEGACY_VERSION, EventEditorController.PREVIOUS_VERSION).map { version ->
            val payload = JSONObject(original.payload).apply {
                listOf("feature", "schema", "generation", "revision").forEach { remove(it) }
                if (version == EventEditorController.LEGACY_VERSION) remove("recurrence")
            }
            original.copy(version = version, payload = payload.toString())
        }
        val taggedLegacy = legacy.map { it.copy(payload = JSONObject(it.payload).put("feature", "ordinary-editor").toString()) }
        val controls = listOf(original) + legacy + taggedLegacy
        controls.forEach { assertNotNull("Valid ordinary v${it.version} control must launch", DraftBrowserCodec.read(it).launch) }
        val tagged = controls.flatMap { control ->
            listOf("future-draft", 42, JSONObject.NULL).map { feature ->
                control.copy(payload = JSONObject(control.payload).put("feature", feature).toString())
            }
        }
        val raw = tagged.map { it.payload }
        val rejectedIncorrectly = mutableListOf<String>()
        tagged.forEach { record ->
            val row = DraftBrowserCodec.read(record)
            val feature = JSONObject(record.payload).get("feature")
            if (row.launch != null || row.issue != DraftReadIssue.UNREADABLE)
                rejectedIncorrectly += "v${record.version} feature=$feature launch=${row.launch != null} issue=${row.issue}"
            assertEquals(record.id, row.recordId)
            assertNull(row.meetingPreview); assertNull(row.meetingCreation); assertNull(row.ownedMeeting)
        }
        assertEquals(raw, tagged.map { it.payload })
        assertTrue("Tagged drafts must remain unreadable: $rejectedIncorrectly", rejectedIncorrectly.isEmpty())
    }
}
