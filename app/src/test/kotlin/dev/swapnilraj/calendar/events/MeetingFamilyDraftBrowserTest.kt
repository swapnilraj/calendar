package dev.swapnilraj.calendar.events

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.events.meetings.MeetingResponseFixture
import dev.swapnilraj.calendar.events.meetings.create.MeetingCreationFixture
import dev.swapnilraj.calendar.events.meetings.owned.OwnedFixture
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeetingFamilyDraftBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val response = MeetingResponseFixture()
    private val creation = MeetingCreationFixture()
    private val owned = OwnedFixture()
    @After fun stop() { response.scope.cancel() }

    private suspend fun records(): List<DraftRecord> {
        response.prepare()
        prepareMeetingCreationDraft(creation.fields.copy(title = "Creation raw", startDate = "2028-"), "draft", creation.store, creation.clock)
        prepareOwnedMeetingDraft(owned.source, "owned", ZoneOffset.UTC, owned.store, owned.clock)
        return listOf(response.record(), creation.record(), owned.record())
    }
    private suspend fun ordinary(): DraftRecord {
        val store = EventEditorControllerTest.Store()
        val time = EventTime.Timed(Instant.parse("2028-06-15T09:00:00Z"), Instant.parse("2028-06-15T10:00:00Z"))
        prepareNewDraft(EventDraft(CalendarId(2), time, ZoneOffset.UTC, "Ordinary", targetIdentity = eventTestIdentity()), "ordinary", true, store)
        return store.records.getValue("ordinary")
    }
    private fun assertNoAuthorityCalls() {
        assertEquals(0, response.reads); assertEquals(0, response.permissionCalls); assertTrue(response.calls.isEmpty())
        assertEquals(0, creation.reads); assertEquals(0, creation.permissions); assertTrue(creation.calls.isEmpty())
        assertEquals(0, owned.reads); assertEquals(0, owned.permissions); assertTrue(owned.calls.isEmpty())
    }
    @Test fun threeFamiliesRetainExactDescriptorsAndRawBytesWithoutOrdinaryLaunch() = runBlocking {
        val before = records()
        val rows = before.map(DraftBrowserCodec::read)
        rows.forEach { assertNull(it.launch); assertNull(it.issue) }
        assertEquals(response.launch(), rows[0].meetingPreview!!.launch)
        assertEquals(creation.launch(), rows[1].meetingCreation)
        assertEquals("Creation raw", rows[1].meetingCreationTitle)
        assertEquals("2028-", MeetingCreationDraftCodec.decode(creation.record()).fields.event.startDate)
        assertEquals(owned.launch(), rows[2].ownedMeeting!!.launch)
        assertEquals(before, listOf(response.record(), creation.record(), owned.record()))
        assertNotNull(DraftBrowserCodec.read(ordinary()).launch)
        assertNoAuthorityCalls()
    }
    @Test fun unknownOrMalformedFeatureCannotMasqueradeAsOrdinaryDraft() = runBlocking {
        val original = ordinary()
        assertNotNull(DraftBrowserCodec.read(original).launch)
        val tagged = listOf("future-draft", 42, JSONObject.NULL).map { feature ->
            original.copy(payload = JSONObject(original.payload).put("feature", feature).toString())
        }
        val raw = tagged.map { it.payload }
        tagged.forEach { record ->
            val row = DraftBrowserCodec.read(record)
            assertNull("Tagged draft must never open as an ordinary event", row.launch)
            assertEquals(DraftReadIssue.UNREADABLE, row.issue)
            assertEquals(record.id, row.recordId)
            assertNull(row.meetingPreview); assertNull(row.meetingCreation); assertNull(row.ownedMeeting)
        }
        assertEquals(raw, tagged.map { it.payload })
    }
    @Test fun knownFamilyVersionMismatchAndUnknownScopedRowsRemainRetained() = runBlocking {
        val original = ordinary()
        val tagged = listOf("meeting-response", "meeting-create", "owned-meeting-edit").map {
            original.copy(payload = JSONObject(original.payload).put("feature", it).toString())
        }
        val unknown = listOf(original.copy(version = 2, payload = "{\"feature\":\"series-edit\"}"), original.copy(version = 99), original.copy(payload = "broken"))
        val records = tagged + unknown + records().map { it.copy(payload = "broken") }
        val before = records.toList()
        records.forEach {
            val row = DraftBrowserCodec.read(it)
            assertNull(row.launch); assertNull(row.meetingPreview); assertNull(row.meetingCreation); assertNull(row.ownedMeeting)
            assertNotNull(row.issue)
        }
        assertEquals(before, records)
        assertNoAuthorityCalls()
    }
    @Test fun creationOpenDispatchesOnlyExactCreationDescriptorWithoutWrites() = runBlocking {
        val before = records(); val row = DraftBrowserCodec.read(before[1]); var opened: MeetingCreationLaunch? = null
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            DraftBrowserContent(DraftBrowserState.Ready(listOf(row)), {}, { error("ordinary") }, {}, ZoneOffset.UTC,
                onOpenMeeting = { error("response") }, onOpenMeetingCreation = { opened = it }, onOpenOwnedMeeting = { error("owned") })
        } } }
        compose.onNodeWithText("Creation raw").assertIsDisplayed()
        compose.onNodeWithText("Open draft").performClick()
        assertEquals(creation.launch(), opened)
        assertEquals(before, listOf(response.record(), creation.record(), owned.record())); assertNoAuthorityCalls()
    }
    @Test fun ownedOpenDispatchesOnlyExactOwnedDescriptorWithoutWrites() = runBlocking {
        val before = records(); val row = DraftBrowserCodec.read(before[2]); var opened: OwnedMeetingLaunch? = null
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            DraftBrowserContent(DraftBrowserState.Ready(listOf(row)), {}, { error("ordinary") }, {}, ZoneOffset.UTC,
                onOpenMeeting = { error("response") }, onOpenMeetingCreation = { error("creation") }, onOpenOwnedMeeting = { opened = it })
        } } }
        compose.onNodeWithText("Open draft").performClick()
        assertEquals(owned.launch(), opened)
        assertEquals(before, listOf(response.record(), creation.record(), owned.record())); assertNoAuthorityCalls()
    }
    @Test fun mixedFamiliesWithoutHostsRemainVisibleAndCannotOpen() = runBlocking {
        val before = records(); val unknown = before[0].copy(id = "unknown", version = 99, payload = "broken")
        var refreshes = 0
        compose.setContent { DaylightTheme { Surface(Modifier.fillMaxSize()) {
            DraftBrowserContent(DraftBrowserState.Ready((before + unknown).map(DraftBrowserCodec::read)), {}, { error("ordinary") }, { refreshes++ }, ZoneOffset.UTC)
        } } }
        compose.onNodeWithText("Planning invitation").assertIsDisplayed()
        compose.onNodeWithText("Creation raw").assertIsDisplayed()
        compose.onNodeWithText("Planning").assertIsDisplayed()
        compose.onNodeWithText("Open draft").assertDoesNotExist()
        compose.onNodeWithText("Recover operation").assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/events_drafts_meeting_families_light.png")
        compose.onNodeWithText("Unrecognized draft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick(); assertEquals(1, refreshes)
        assertEquals(before, listOf(response.record(), creation.record(), owned.record())); assertNoAuthorityCalls()
    }
}
