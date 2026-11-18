package dev.swapnilraj.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.grid.*
import dev.swapnilraj.calendar.ui.DayScreen
import dev.swapnilraj.calendar.data.CalendarState
import kotlinx.coroutines.flow.flowOf
import dev.swapnilraj.calendar.ui.multiday.*
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiDayGridInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 11, 16)
    private val zone = ZoneId.of("Europe/London")
    private val range = CalendarDateRange(day, day.plusDays(3))
    private fun event(first: LocalDate = day, startHour: Int = 9, duration: Duration = Duration.ofHours(1)): EventOccurrence {
        val start = first.atTime(startHour, 0).atZone(zone).toInstant()
        val end = start.plus(duration)
        return EventOccurrence(EventRef(CalendarId(1), 7, start, end), EventTime.Timed(start, end), "Meeting", 0xff397d70.toInt())
    }
    private fun config(onEdit: (TimedGridEditProposal) -> Unit = {}, onCreate: (TimedGridCreateProposal) -> Unit = {},
        begin: suspend (EventRef) -> Boolean = { true }) = TimedGridInteractions("context", Duration.ofHours(1),
        onCreate, onEdit, { true }, begin, {}, {})
    private fun show(window: CalendarDateRange = range, events: List<EventOccurrence> = listOf(event()),
        interactions: TimedGridInteractions? = config(), rtl: Boolean = false, font: Float = 1f,
        onScroll: (Double) -> Unit = {}, onOpen: (EventRef) -> Unit = {}, showWeekends: Boolean = true, minute: Double = 480.0, onCancel: (() -> Unit)? = null) {
        compose.setContent { DaylightTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MultiDayView(window, window.start, events, day.atTime(10, 0).atZone(zone), zone, true, 64f, {}, {}, onOpen, {}, {},
                    initialScrollMinute = minute, onScrollMinuteChanged = onScroll, showWeekends = showWeekends, interactions = interactions, onCancelPendingEdit = onCancel)
            }
        } }
        compose.waitForIdle(); compose.mainClock.autoAdvance = false
    }
    private fun grid(date: LocalDate) = compose.onNodeWithTag("multiday_grid_$date")
    private fun hold(node: SemanticsNodeInteraction, point: Offset? = null) {
        node.performTouchInput { down(point ?: center) }; compose.mainClock.advanceTimeBy(700); compose.waitForIdle()
    }
    private fun move(node: SemanticsNodeInteraction, delta: Offset) {
        node.performTouchInput { moveBy(delta) }; compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
    }
    private fun release(node: SemanticsNodeInteraction) {
        node.performTouchInput { up() }; compose.mainClock.advanceTimeBy(48); compose.waitForIdle()
    }
    private fun cross(rtl: Boolean) {
        var proposal: TimedGridEditProposal? = null
        var opens = 0
        show(interactions = config(onEdit = { proposal = it }), rtl = rtl, onOpen = { opens++ })
        val node = compose.onNodeWithText("Meeting")
        val original = node.fetchSemanticsNode().boundsInRoot.center
        val target = grid(day.plusDays(1)).fetchSemanticsNode().boundsInRoot.center
        hold(node); move(node, Offset(target.x - original.x, 0f)); release(node)
        val result = proposal!!.preview as GridPreviewResult.Ready
        assertEquals(day.plusDays(1), proposal!!.pointerPath!!.hover.date)
        assertEquals(day, proposal!!.pointerPath!!.origin.date)
        assertEquals(day.plusDays(1).atTime(9, 0).atZone(zone).toInstant(), result.time.start)
        assertEquals(Duration.ofHours(1), result.time.duration)
        assertEquals(event().ref, proposal!!.source.ref)
        assertEquals(0, opens)
    }
    @Test fun crossColumnMoveResolvesThePhysicalNextDate() { cross(false) }
    @Test fun rtlCrossColumnMoveUsesObservedLeftwardNextDate() { cross(true) }
    @Test fun hiddenWeekendAdjacentColumnsMoveFridayToMondayRatherThanSaturday() {
        val friday = LocalDate.of(2026, 11, 20)
        val monday = friday.plusDays(3)
        var proposal: TimedGridEditProposal? = null
        show(window = CalendarDateRange(friday, friday.plusDays(7)), events = listOf(event(friday)),
            interactions = config(onEdit = { proposal = it }), showWeekends = false)
        val node = compose.onNodeWithText("Meeting")
        val origin = node.fetchSemanticsNode().boundsInRoot.center
        val target = grid(monday).fetchSemanticsNode().boundsInRoot.center
        hold(node); move(node, Offset(target.x - origin.x, 0f)); release(node)
        assertEquals(monday, proposal?.pointerPath?.hover?.date)
        assertEquals(monday.atTime(9, 0).atZone(zone).toInstant(), (proposal!!.preview as GridPreviewResult.Ready).time.start)
    }
    @Test fun crossColumnResizeKeepsFullStartAndChangesTrueEnd() {
        var proposal: TimedGridEditProposal? = null
        show(interactions = config(onEdit = { proposal = it }))
        val node = compose.onNodeWithText("Meeting")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val target = grid(day.plusDays(1)).fetchSemanticsNode().boundsInRoot.center
        hold(node, Offset(bounds.width / 2, bounds.height - 3))
        move(node, Offset(target.x - bounds.center.x, 32f)); release(node)
        assertEquals(TimedGridEditKind.RESIZE_END, proposal?.kind)
        val result = proposal!!.preview as GridPreviewResult.Ready
        assertEquals(event().ref.instanceStart, result.time.start)
        assertEquals(day.plusDays(1).atTime(10, 30).atZone(zone).toInstant(), result.time.end)
    }
    @Test fun emptyThirdColumnCreatesOnItsOwnDateWithOneSharedGutter() {
        var proposal: TimedGridCreateProposal? = null
        show(interactions = config(onCreate = { proposal = it }))
        val target = grid(day.plusDays(2)).fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("multiday_time_scroll")
        val viewport = body.fetchSemanticsNode().boundsInRoot
        hold(body, Offset(target.center.x - viewport.left, 160f)); release(body)
        assertEquals(day.plusDays(2), proposal?.target?.date)
        assertEquals(day.plusDays(2).atTime(10, 30).atZone(zone).toInstant(), (proposal!!.preview as GridPreviewResult.Ready).time.start)
        compose.onAllNodesWithTag("multiday_time_gutter").assertCountEquals(1)
    }
    @Test fun droppingInTheFixedGutterNeverRoutesAnEdit() {
        var edits = 0
        show(interactions = config(onEdit = { edits++ }))
        val node = compose.onNodeWithText("Meeting")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        hold(node); move(node, Offset(10f - bounds.center.x, 0f)); release(node)
        assertEquals(0, edits)
    }
    @Test fun horizontalGeometryChangeCancelsPendingAccessAndItsLateResult() {
        val gate = CompletableDeferred<Boolean>(); var cancelled = false; var edits = 0; var opens = 0
        show(window = CalendarDateRange(day, day.plusDays(7)), interactions = config(onEdit = { edits++ }, begin = { try { gate.await() } finally { cancelled = true } }),
            onOpen = { opens++ })
        val node = compose.onNodeWithText("Meeting")
        hold(node)
        compose.onNodeWithTag("multiday_columns").performSemanticsAction(SemanticsActions.ScrollBy) { it(120f, 0f) }
        compose.mainClock.advanceTimeBy(48)
        gate.complete(true); release(node)
        assertTrue(cancelled); assertEquals(0, edits); assertEquals(0, opens)
    }
    @Test fun sharedVerticalAutoScrollKeepsCrossColumnDateAndStopsAtRelease() {
        var anchor = 480.0; var proposal: TimedGridEditProposal? = null
        show(interactions = config(onEdit = { proposal = it }), onScroll = { anchor = it })
        val node = compose.onNodeWithText("Meeting")
        val source = node.fetchSemanticsNode().boundsInRoot.center
        val body = compose.onNodeWithTag("multiday_time_scroll").fetchSemanticsNode().boundsInRoot
        val target = grid(day.plusDays(1)).fetchSemanticsNode().boundsInRoot.center
        hold(node); move(node, Offset(target.x - source.x, body.bottom - source.y - 3))
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        assertTrue(anchor > 480)
        release(node)
        assertEquals(day.plusDays(1), proposal?.pointerPath?.hover?.date)
        val stopped = anchor; compose.mainClock.advanceTimeBy(400)
        assertEquals(stopped, anchor, 1.0)
    }
    @Test fun continuedFragmentMoveRetainsItsFullStartAndDuration() {
        val source = event(day.minusDays(2), 9, Duration.ofHours(84))
        var proposal: TimedGridEditProposal? = null
        show(events = listOf(source), interactions = config(onEdit = { proposal = it }))
        // Inject at the visible viewport; the full-day event's actual local origin is above it.
        val node = compose.onNodeWithTag("multiday_time_scroll")
        val viewport = node.fetchSemanticsNode().boundsInRoot
        val origin = grid(day).fetchSemanticsNode().boundsInRoot
        val target = grid(day.plusDays(1)).fetchSemanticsNode().boundsInRoot
        hold(node, Offset(origin.center.x - viewport.left, 80f))
        move(node, Offset(target.center.x - origin.center.x, 0f)); release(node)
        val result = proposal!!.preview as GridPreviewResult.Ready
        assertEquals(source.ref.instanceStart.plus(Duration.ofDays(1)), result.time.start)
        assertEquals(Duration.ofHours(84), result.time.duration)
        assertEquals(source.ref, proposal!!.source.ref)
    }
    private fun clockReview(first: LocalDate, expected: GridReviewReason) {
        var proposal: TimedGridEditProposal? = null
        show(window = CalendarDateRange(first, first.plusDays(3)), events = listOf(event(first, 1)),
            interactions = config(onEdit = { proposal = it }), minute = 0.0)
        val node = compose.onNodeWithText("Meeting")
        val origin = node.fetchSemanticsNode().boundsInRoot.center
        val target = grid(first.plusDays(1)).fetchSemanticsNode().boundsInRoot.center
        hold(node); move(node, Offset(target.x - origin.x, 0f)); release(node)
        val result = proposal!!.preview as GridPreviewResult.NeedsReview
        assertEquals(expected, result.reason)
        assertEquals(first.plusDays(1), result.snappedTarget.date)
        assertEquals(GridOffsetChoice.Unspecified, result.snappedTarget.offsetChoice)
    }
    @Test fun crossColumnOverlapRemainsAnExplicitClockOccurrenceReview() { clockReview(LocalDate.of(2026, 10, 24), GridReviewReason.CLOCK_OVERLAP) }
    @Test fun crossColumnGapNeverGuessesAnInstant() { clockReview(LocalDate.of(2026, 3, 28), GridReviewReason.CLOCK_GAP) }
    @Test @Config(qualifiers = "+notnight") fun targetColumnGhostLeavesEventSurfaceUntinted() {
        show()
        val node = compose.onNodeWithText("Meeting")
        val source = node.fetchSemanticsNode().boundsInRoot.center
        val target = grid(day.plusDays(1)).fetchSemanticsNode().boundsInRoot.center
        hold(node); move(node, Offset(target.x - source.x, 0f))
        compose.onRoot().captureRoboImage("src/test/screenshots/multiday_drag_cross_column.png")
        release(node)
    }
    @Test @Config(qualifiers = "+notnight") fun narrowRtlWeekKeepsPartiallyVisibleColumnTargetsAccessible() {
        show(window = CalendarDateRange(day, day.plusDays(7)), rtl = true, font = 1.5f)
        val node = compose.onNodeWithText("Meeting")
        val origin = node.fetchSemanticsNode().boundsInRoot.center
        val target = grid(day.plusDays(1)).fetchSemanticsNode().boundsInRoot.center
        hold(node); move(node, Offset(target.x - origin.x, 0f))
        compose.onRoot().captureRoboImage("src/test/screenshots/multiday_drag_week_rtl.png")
        release(node)
    }
    @Test fun accessibleCreateActionNamesTheActualColumnDateAndUsesItsEditorCallback() {
        var requested: LocalDate? = null
        show(interactions = config().copy(onCreateInEditor = { requested = it }))
        val label = day.plusDays(2).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(java.util.Locale.US))
        val action = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsActions.CustomActions] }.single { it.label.contains(label) }
        compose.runOnIdle { assertTrue(action.action()) }
        assertEquals(day.plusDays(2), requested)
    }
    @Test fun busyMultiDayPickerStillCancelsPendingHostPreparationWithoutGesturePorts() {
        var cancellations = 0
        show(interactions = null, onCancel = { cancellations++ })
        compose.onNodeWithTag("multiday_date_picker").performClick()
        assertEquals(1, cancellations)
    }
    @Test fun busyDayHeaderModalStillCancelsPendingHostPreparationWithoutGesturePorts() {
        var cancellations = 0
        compose.setContent { DaylightTheme {
            DayScreen(day.atTime(10, 0).atZone(zone), true, 64f, { _, _ -> flowOf(CalendarState.Ready(emptyList())) }, {}, {},
                initialScrollHour = 8.0, onCancelPendingEdit = { cancellations++ })
        } }
        compose.waitForIdle()
        val label = day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(java.util.Locale.US))
        compose.onNodeWithContentDescription("View events for $label").performClick()
        assertEquals(1, cancellations)
    }
    @Test fun physicalRectHitTestClipsPartialColumnsAndNeverGuessesRtlOrder() {
        val viewport = Rect(50f, 100f, 350f, 500f)
        val columns = listOf(MultiDayColumnBounds(day, 250f, 400f), MultiDayColumnBounds(day.plusDays(1), 100f, 250f),
            MultiDayColumnBounds(day.plusDays(2), -50f, 100f))
        assertEquals(day, multiDayDateAtRoot(Offset(349f, 200f), viewport, columns))
        assertEquals(day.plusDays(1), multiDayDateAtRoot(Offset(150f, 200f), viewport, columns))
        assertEquals(day.plusDays(2), multiDayDateAtRoot(Offset(51f, 200f), viewport, columns))
        assertNull(multiDayDateAtRoot(Offset(49f, 200f), viewport, columns))
        assertNull(multiDayDateAtRoot(Offset(351f, 200f), viewport, columns))
        assertNull(multiDayDateAtRoot(Offset(150f, 99f), viewport, columns))
    }
}
