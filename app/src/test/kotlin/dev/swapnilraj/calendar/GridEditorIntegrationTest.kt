package dev.swapnilraj.calendar

import android.content.*
import android.database.*
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarReader
import dev.swapnilraj.calendar.events.EventEditorControllerTest.Store
import dev.swapnilraj.calendar.ui.LiveCalendarDay
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GridEditorIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val zone = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 11, 17)
    private val start = day.atTime(9, 0).atZone(zone).toInstant()
    private val ref = EventRef(CalendarId(3), 7, start, start.plusSeconds(3600))
    private val store = Store()
    private val provider = Provider(start, ref.instanceEnd)
    private val calendar = CalendarInfo(CalendarId(3), "Fixture calendar", 0xff397d70.toInt(), CalendarAccount("owner@example.invalid", "com.google"),
        "owner@example.invalid", zone, 700, true, true, CalendarCapabilities(AccessDecision.ALLOWED, AccessDecision.ALLOWED, AccessDecision.ALLOWED), "namespace-a")
    private val source = EditableEvent(EventDetails(EventOccurrence(ref, EventTime.Timed(start, ref.instanceEnd), "Fixture meeting", calendar.color),
        calendar, location = "Room", description = "Fixture notes", eventZone = zone), EditToken("fixture-token"), EditableField.entries.toSet())
    private var reads = 0; private var writes = 0; private var deny = false; private var replaceNamespaceAtEditorRestore = false
    private val mutations = object : CalendarMutations {
        override suspend fun loadForEdit(ref: EventRef): EditReadResult {
            reads++
            val loaded = if (replaceNamespaceAtEditorRestore && reads >= 3)
                source.copy(details = source.details.copy(calendar = calendar.copy(providerId = "namespace-b")), token = EditToken("namespace-b-token")) else source
            return if (deny) EditReadResult.Denied(MutationDenial.CALENDAR_READ_ONLY) else EditReadResult.Ready(loaded)
        }
        override suspend fun create(operationId: String, draft: EventDraft): MutationResult { writes++; return MutationResult.LocalSaved(ref) }
        override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult { writes++; return MutationResult.LocalSaved(ref) }
        override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult { writes++; error("Unexpected delete") }
        override suspend fun recover(operationId: String): MutationResult = error("Unexpected recovery")
    }
    @After fun cleanUp() { scope.cancel() }
    private fun show(selectedDay: LocalDate = day, rtl: Boolean = false, font: Float = 1f) {
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val reader = CalendarReader(RuntimeEnvironment.getApplication().contentResolver, scope, stopTimeoutMillis = 0)
        compose.setContent { DaylightTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, selectedDay.atTime(if (selectedDay == day) 9 else 2, 20).atZone(zone), true, 64f,
                    {}, {}, mutations = mutations, drafts = store, requestMeetingWritePermission = { true })
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("timed_grid_viewport_$selectedDay").fetchSemanticsNodes().isNotEmpty() }
        if (selectedDay == day) compose.onNodeWithText("Fixture meeting").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun hold(node: SemanticsNodeInteraction, point: Offset? = null) {
        node.performTouchInput { down(point ?: center) }
        compose.mainClock.advanceTimeBy(700); compose.waitForIdle()
    }
    private fun drop(node: SemanticsNodeInteraction, delta: Offset = Offset.Zero) {
        if (delta != Offset.Zero) { node.performTouchInput { moveBy(delta) }; compose.mainClock.advanceTimeBy(48) }
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
    }
    private fun awaitEditor(title: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasSetTextAction() and hasContentDescription("Title") and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }
    private fun chooseCalendar() {
        val choose = hasText("Choose a calendar") and hasClickAction() and isEnabled()
        compose.waitUntil(10_000) { compose.onAllNodes(choose).fetchSemanticsNodes().size == 1 }
        compose.onNode(choose).performScrollTo().performClick()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        val row = hasText(calendar.displayName) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(row and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(row and isEnabled()).performClick()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(row).fetchSemanticsNodes().isEmpty()
        }
    }
    @Test fun nativeEmptySlotCreatesDurableTimedDraftAndWaitsForExplicitSave() {
        show()
        val event = compose.onNodeWithText("Fixture meeting").fetchSemanticsNode().boundsInRoot
        val view = compose.onNodeWithTag("timed_grid_viewport_$day")
        val bounds = view.fetchSemanticsNode().boundsInRoot
        hold(view, Offset(280f, event.top - bounds.top + 128f))
        drop(view)
        awaitEditor("New event")
        val raw = JSONObject(store.records.values.single().payload)
        assertEquals("11:00", raw.getString("startTime")); assertEquals("12:00", raw.getString("endTime"))
        assertEquals("", raw.getString("calendarId"))
        assertEquals(0, writes); assertEquals(0, reads)
        chooseCalendar()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { writes == 1 }
        assertEquals(1, writes)
    }
    @Test fun nativeMovePerformsPreflightThenFreshReadsAndPreservesUnrelatedFields() {
        show()
        val node = compose.onNodeWithText("Fixture meeting")
        hold(node); drop(node, Offset(0f, 64f)); awaitEditor("Edit event")
        assertTrue(reads >= 3) // Long press, drop, normal editor restoration.
        assertEquals(0, writes)
        val raw = JSONObject(store.records.values.single().payload)
        assertEquals("10:00", raw.getString("startTime")); assertEquals("11:00", raw.getString("endTime"))
        assertEquals("Fixture notes", raw.getString("description"))
        // Phone time rows expose their complete value; editing opens the guarded picker.
        compose.onNodeWithContentDescription("Ends: 11:00").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/grid_editor_move.png")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { writes == 1 }
        assertEquals(1, writes)
    }
    @Test fun nativeLowerHandleResizesEndRatherThanMovingStart() {
        show()
        val node = compose.onNodeWithText("Fixture meeting")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        hold(node, Offset(bounds.width / 2, bounds.height - 3)); drop(node, Offset(0f, 32f)); awaitEditor("Edit event")
        val raw = JSONObject(store.records.values.single().payload)
        assertEquals("09:00", raw.getString("startTime")); assertEquals("10:30", raw.getString("endTime"))
        assertEquals(0, writes)
    }
    @Test fun nativeDeniedReadOnlyPreflightNeverOpensAnEditorOrSaves() {
        deny = true; show()
        val node = compose.onNodeWithText("Fixture meeting")
        hold(node); drop(node, Offset(0f, 64f)); compose.waitForIdle()
        compose.onNodeWithText("Edit event").assertDoesNotExist()
        compose.onNodeWithText("Fixture meeting").assertIsDisplayed()
        assertEquals(1, reads); assertEquals(0, writes); assertTrue(store.records.isEmpty())
    }
    @Test fun nativeOverlapRequiresExplicitOffsetAndKeepsChoiceInDurableEditor() {
        val date = LocalDate.of(2026, 10, 25)
        show(date, rtl = true, font = 1.5f)
        val view = compose.onNodeWithTag("timed_grid_viewport_$date")
        hold(view, Offset(100f, 96f)); drop(view)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose the clock occurrence").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.records.isEmpty()); assertEquals(0, writes)
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/grid_editor_overlap_large_rtl.png")
        compose.onNodeWithText("Second occurrence (Z)").performScrollTo().performClick()
        awaitEditor("New event")
        val raw = JSONObject(store.records.values.single().payload)
        assertEquals("01:30", raw.getString("startTime")); assertEquals("Z", raw.getString("startOffset"))
        assertEquals("02:30", raw.getString("endTime")); assertEquals(0, writes)
    }
    @Test fun nativeGapOpensRealDateDefaultEditorOnlyAfterExplicitAlternative() {
        val date = LocalDate.of(2026, 3, 29)
        show(date)
        val view = compose.onNodeWithTag("timed_grid_viewport_$date")
        hold(view, Offset(280f, 80f)); drop(view)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("This clock time does not exist").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.records.isEmpty()); assertEquals(0, writes)
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/grid_editor_gap.png")
        compose.onNodeWithText("Choose another time in editor").performClick()
        awaitEditor("New event")
        val raw = JSONObject(store.records.values.single().payload)
        assertEquals("02:30", raw.getString("startTime"))
        assertEquals("2026-03-29", raw.getString("startDate"))
        assertEquals(0, writes)
    }
    @Test fun cancellingOverlapWhileDraftStorageIsSuspendedNeverOpensTheLateEditor() {
        val date = LocalDate.of(2026, 10, 25)
        show(date)
        val view = compose.onNodeWithTag("timed_grid_viewport_$date")
        hold(view, Offset(280f, 96f)); drop(view)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Choose the clock occurrence").fetchSemanticsNodes().isNotEmpty() }
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        store.beforeCas = { _, _, _ -> entered.complete(Unit); gate.await() }
        compose.onNodeWithText("Second occurrence (Z)").performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); entered.isCompleted }
        compose.onNodeWithText("Cancel").performClick()
        store.beforeCas = null
        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("New event").assertDoesNotExist()
        compose.onNodeWithText("Choose the clock occurrence").assertDoesNotExist()
        assertTrue(store.records.isEmpty()); assertEquals(0, writes)
    }
    @Test fun namespaceReplacementAfterDurableDropBeforeEditorRestoreLocksTheRetainedMove() {
        replaceNamespaceAtEditorRestore = true
        show()
        val node = compose.onNodeWithText("Fixture meeting")
        hold(node); drop(node, Offset(0f, 64f))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Start a new draft").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit event").assertExists()
        assertTrue(reads >= 3)
        val raw = JSONObject(store.records.values.single().payload)
        assertEquals("10:00", raw.getString("startTime"))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Delete event").assertDoesNotExist()
        assertEquals(0, writes)
    }
    private class Provider(val start: Instant, val end: Instant) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val fields = requireNotNull(projection); val table = uri.pathSegments.first()
            return MatrixCursor(fields).apply {
                if (table !in setOf("attendees", "reminders")) addRow(fields.map<String, Any?> { field -> when (field) {
                    "_id" -> if (table == "calendars") 3L else 7L
                    "calendar_id" -> 3L
                    "event_id" -> 7L
                    "_sync_id" -> "namespace-a"
                    "calendar_displayName" -> "Fixture calendar"
                    "calendar_color", "displayColor" -> 0xff397d70.toInt()
                    "account_name", "ownerAccount", "organizer" -> "owner@example.invalid"
                    "account_type" -> "com.google"
                    "calendar_timezone", "eventTimezone" -> "Europe/London"
                    "calendar_access_level" -> CalendarContract.Calendars.CAL_ACCESS_OWNER
                    "visible", "sync_events" -> 1
                    "begin", "dtstart" -> start.toEpochMilli()
                    "end", "dtend" -> end.toEpochMilli()
                    "title" -> "Fixture meeting"
                    "description" -> "Fixture notes"
                    "allDay", "deleted", "selfAttendeeStatus" -> 0
                    else -> null
                } }.toTypedArray())
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Read-only fixture")
    }
}
