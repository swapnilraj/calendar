package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.CalEvent
import dev.swapnilraj.calendar.data.CalendarState
import dev.swapnilraj.calendar.ui.DayScreen
import dev.swapnilraj.calendar.ui.grid.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimedGridInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/London")
    private val date = LocalDate.of(2026, 11, 17)
    private fun event(minutes: Long = 60, withRef: Boolean = true): CalEvent {
        val start = date.atTime(9, 0).atZone(zone).toInstant()
        return CalEvent(7, start, start.plusSeconds(minutes * 60), "Meeting", 0xff397d70.toInt(),
            ref = if (withRef) EventRef(CalendarId(1), 7, start, start.plusSeconds(minutes * 60), RecurrenceRef(6, start.minusSeconds(86400))) else null)
    }
    private fun config(onCreate: (TimedGridCreateProposal) -> Unit = {}, onEdit: (TimedGridEditProposal) -> Unit = {},
        preflight: suspend (EventRef) -> Boolean = { true }, onEditor: (EventRef) -> Unit = {}) = TimedGridInteractions("context-a", Duration.ofMinutes(60),
        onCreate, onEdit, { true }, preflight, {}, onEditor)
    private fun show(events: List<CalEvent> = listOf(event()), interactions: TimedGridInteractions? = config(),
        context: MutableState<TimedGridInteractions?>? = null, source: MutableState<List<CalEvent>>? = null,
        selectedDate: LocalDate = date, scroll: Double = 8.0, onTap: (CalEvent) -> Unit = {}, onScroll: (Double) -> Unit = {},
        rtl: Boolean = false, font: Float = 1f) {
        compose.setContent { DaylightTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                val current = source?.value ?: events
                DayScreen(selectedDate.atTime(10, 0).atZone(zone), true, 64f,
                    { day, displayZone -> flowOf(CalendarState.Ready(current.filter { it.occursOn(day, displayZone) })) }, {}, onTap,
                    initialScrollHour = scroll, onScrollMinuteChanged = onScroll, interactions = context?.value ?: interactions)
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun viewport(day: LocalDate = date) = compose.onNodeWithTag("timed_grid_viewport_$day")
    private fun hold(node: SemanticsNodeInteraction, point: Offset? = null) {
        node.performTouchInput { down(point ?: center) }
        compose.mainClock.advanceTimeBy(650)
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }
    private fun move(node: SemanticsNodeInteraction, delta: Offset) {
        node.performTouchInput { moveBy(delta) }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }
    private fun release(node: SemanticsNodeInteraction) {
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
    }
    @Test fun fragmentMoveTranslatesFullStartAndPreservesTrueDuration() {
        val day = LocalDate.of(2026, 10, 25)
        val start = Instant.parse("2026-10-25T00:15:00Z")
        val end = Instant.parse("2026-10-25T01:45:00Z")
        val ref = EventRef(CalendarId(1), 9, start, end)
        val source = TimedGridSource(ref, EventTime.Timed(start, end))
        val segment = DayGeometry(day, zone).segments(ref).last().key
        val target = translatedGridTarget(source, segment, TimedGridEditKind.MOVE, 60.0, zone)
        assertEquals(135.0, target.minute, 0.0)
        val result = planGridEdit(source, segment, TimedGridEditKind.MOVE, target) as GridPreviewResult.Ready
        assertEquals(Duration.ofMinutes(90), result.time.duration)
        assertEquals(ref, result.source)
    }
    @Test fun transitionEndBoundaryIsRetainedOnlyAtOriginalEndpoint() {
        val day = LocalDate.of(2026, 3, 29)
        val start = Instant.parse("2026-03-29T00:00:00Z")
        val end = Instant.parse("2026-03-29T01:00:00Z")
        val source = TimedGridSource(EventRef(CalendarId(1), 9, start, end), EventTime.Timed(start, end))
        val segment = DayGeometry(day, zone).segments(source.ref).last().key
        val original = translatedGridTarget(source, segment, TimedGridEditKind.RESIZE_END, 0.0, zone)
        assertEquals(60.0, original.minute, 0.0)
        val kept = planGridEdit(source, segment, TimedGridEditKind.RESIZE_END, original) as GridPreviewResult.Ready
        assertEquals(source.time, kept.time)
        assertTrue(kept.snappedTarget.boundary is GridBoundaryChoice.TransitionEnd)
        val changed = planGridEdit(source, segment, TimedGridEditKind.RESIZE_END,
            translatedGridTarget(source, segment, TimedGridEditKind.RESIZE_END, 15.0, zone)) as GridPreviewResult.NeedsReview
        assertEquals(GridReviewReason.CLOCK_GAP, changed.reason)
        assertNull(changed.snappedTarget.boundary)
    }
    @Test fun emptyLongPressCreatesSnappedProposalOnlyWhenBothRoutesExist() {
        var proposal: TimedGridCreateProposal? = null
        show(interactions = config(onCreate = { proposal = it }))
        val view = viewport()
        hold(view, Offset(280f, 160f)) // 10:30, away from the 09:00 event.
        release(view)
        val ready = proposal!!.preview as GridPreviewResult.Ready
        assertEquals(date.atTime(10, 30).atZone(zone).toInstant(), ready.time.start)
        assertEquals(Duration.ofMinutes(60), ready.time.duration)
        assertNull(ready.source)
    }
    @Test fun moveReadsOnceAndKeepsOriginalRecurrenceRefWithoutOpeningTapDetails() {
        var reads = 0
        var taps = 0
        var proposal: TimedGridEditProposal? = null
        show(interactions = config(onEdit = { proposal = it }, preflight = { reads++; true }), onTap = { taps++ })
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        move(node, Offset(0f, 64f))
        release(node)
        assertEquals(1, reads)
        assertEquals(0, taps)
        assertEquals(event().ref, proposal?.source?.ref)
        assertEquals(TimedGridEditKind.MOVE, proposal?.kind)
        assertEquals(date.atTime(10, 0).atZone(zone).toInstant(), (proposal!!.preview as GridPreviewResult.Ready).time.start)
    }
    @Test fun shortEventResizeUsesTrueEndRatherThanPaintedMinimumHeight() {
        var proposal: TimedGridEditProposal? = null
        show(events = listOf(event(5)), interactions = config(onEdit = { proposal = it }))
        val node = compose.onNodeWithText("Meeting")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        hold(node, Offset(bounds.width / 2, bounds.height - 3))
        move(node, Offset(0f, 24f)) // Exceeds platform touch slop; 22.5 wall minutes from the real 09:05 end.
        release(node)
        assertEquals(TimedGridEditKind.RESIZE_END, proposal?.kind)
        assertEquals(date.atTime(9, 30).atZone(zone).toInstant(), (proposal!!.preview as GridPreviewResult.Ready).time.end)
    }
    @Test fun readOnlyPreflightNeverProducesAnEditPreviewOrDrop() {
        var edits = 0
        show(interactions = config(onEdit = { edits++ }, preflight = { false }))
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        move(node, Offset(0f, 64f))
        release(node)
        assertEquals(0, edits)
    }
    @Test fun releasingBeforeAsyncPreflightCompletesCancelsItsResult() {
        val gate = CompletableDeferred<Boolean>()
        var edits = 0
        var cancelled = false
        show(interactions = config(onEdit = { edits++ }, preflight = { try { gate.await() } finally { cancelled = true } }))
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        release(node)
        gate.complete(true)
        compose.mainClock.advanceTimeBy(64)
        assertTrue(cancelled)
        assertEquals(0, edits)
    }
    @Test fun contextChangeCancelsHeldGestureAndNeverTurnsItsUpIntoAnEventTap() {
        val context = mutableStateOf<TimedGridInteractions?>(config())
        var edits = 0
        var taps = 0
        context.value = config(onEdit = { edits++ })
        show(context = context, onTap = { taps++ })
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        context.value = config(onEdit = { edits++ }).copy(contextKey = "replacement-context")
        compose.mainClock.advanceTimeBy(48)
        move(node, Offset(0f, 64f))
        release(node)
        assertEquals(0, edits)
        assertEquals(0, taps)
    }
    @Test fun replacingSourceCancelsPendingPreflightAndRetainsNoAuthority() {
        val gate = CompletableDeferred<Boolean>()
        var edits = 0
        var cancelled = false
        val source = mutableStateOf(listOf(event()))
        show(source = source, interactions = config(onEdit = { edits++ }, preflight = { try { gate.await() } finally { cancelled = true } }))
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        source.value = listOf(event(30))
        compose.mainClock.advanceTimeBy(48)
        gate.complete(true)
        move(compose.onNodeWithText("Meeting"), Offset(0f, 64f))
        release(compose.onNodeWithText("Meeting"))
        assertTrue(cancelled)
        assertEquals(0, edits)
    }
    @Test fun editWithoutReadOnlyPreflightExposesNoGestureOrEditorAction() {
        var edits = 0
        var editors = 0
        var taps = 0
        show(interactions = config(onEdit = { edits++ }, onEditor = { editors++ }).copy(onCreate = null, onBeginEdit = null), onTap = { taps++ })
        val node = compose.onNodeWithText("Meeting")
        assertFalse(node.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
        node.performClick()
        assertEquals(1, taps)
        assertEquals(0, edits)
        assertEquals(0, editors)
    }
    @Test fun geometryOnlyFallbackCannotStartEditingOrBecomeAnEmptySlot() {
        var edits = 0
        var creates = 0
        var reads = 0
        show(events = listOf(event(withRef = false)), interactions = config(onCreate = { creates++ }, onEdit = { edits++ }, preflight = { reads++; true }))
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        release(node)
        assertEquals(0, reads)
        assertEquals(0, edits)
        assertEquals(0, creates)
    }
    @Test fun editorAlternativeIsARealSemanticActionWithoutDoingPreviewReads() {
        var editors = 0
        var reads = 0
        show(interactions = config(onEditor = { editors++ }, preflight = { reads++; true }))
        compose.onNodeWithText("Meeting").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        assertEquals(1, editors)
        assertEquals(0, reads)
    }
    @Test fun edgeAutoScrollAdvancesLogicalAnchorAndStopsOnRelease() {
        var minute = 480.0
        show(onScroll = { minute = it })
        val event = compose.onNodeWithText("Meeting")
        hold(event)
        val view = viewport().fetchSemanticsNode().boundsInRoot
        val center = event.fetchSemanticsNode().boundsInRoot.center
        move(event, Offset(0f, view.bottom - center.y - 3))
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertTrue(minute > 480)
        release(event)
        val stopped = minute
        compose.mainClock.advanceTimeBy(400)
        assertEquals(stopped, minute, 1.0)
    }
    @Test fun positiveMillisecondMovePreviewKeepsVisibleAndAccessibleDurationNonzero() {
        val original = event()
        val end = original.start.plusMillis(1)
        val short = original.copy(end = end, ref = original.ref!!.copy(instanceEnd = end))
        show(events = listOf(short))
        val node = compose.onNodeWithText("Meeting")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        hold(node, Offset(bounds.width / 2, bounds.height / 4))
        move(node, Offset(0f, 64f))
        compose.onNodeWithText("0.001 s", substring = true).assertExists()
        compose.onNodeWithContentDescription("0.001 elapsed seconds", substring = true).assertExists()
        release(node)
    }
    @Test @Config(qualifiers = "+notnight") fun createPreviewUsesUntintedOutlineUnderExistingEvents() {
        show()
        val node = viewport()
        hold(node, Offset(280f, 160f))
        compose.onRoot().captureRoboImage("src/test/screenshots/timed_grid_create_preview.png")
        release(node)
    }
    @Test @Config(qualifiers = "+notnight") fun movePreviewRtlKeepsLogicalTimeAndEditorAlternative() {
        show(rtl = true, font = 1.5f)
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        move(node, Offset(0f, 64f))
        compose.onRoot().captureRoboImage("src/test/screenshots/timed_grid_move_rtl.png")
        release(node)
    }
    @Test @Config(qualifiers = "+notnight") fun gapPreviewNeverSelectsAGuessedInstant() {
        val day = LocalDate.of(2026, 3, 29)
        var proposal: TimedGridCreateProposal? = null
        show(events = emptyList(), selectedDate = day, scroll = 0.0, interactions = config(onCreate = { proposal = it }))
        val node = viewport(day)
        hold(node, Offset(280f, 80f))
        compose.onRoot().captureRoboImage("src/test/screenshots/timed_grid_gap_review.png")
        release(node)
        val result = proposal!!.preview as GridPreviewResult.NeedsReview
        assertEquals(GridReviewReason.CLOCK_GAP, result.reason)
        assertEquals(75.0, result.snappedTarget.minute, 0.0)
        assertEquals(GridOffsetChoice.Unspecified, result.snappedTarget.offsetChoice)
    }
}
