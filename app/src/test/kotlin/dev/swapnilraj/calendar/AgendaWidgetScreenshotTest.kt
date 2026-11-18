package dev.swapnilraj.calendar

import android.content.ComponentName
import android.content.Context
import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.github.takahirom.roborazzi.captureRoboImage
import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.*
import java.time.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgendaWidgetScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val today = LocalDate.of(2026, 11, 18)
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private fun ready(privacy: WidgetPrivacy, count: Int): AgendaWidgetDisplay.Ready {
        val rows = (0 until count).map { offset ->
            val start = now.plusSeconds((offset + 1) * 3600L)
            AgendaWidgetRow(EventOccurrence(EventRef(CalendarId(1), offset.toLong(), start, start.plusSeconds(1800)),
                EventTime.Timed(start, start.plusSeconds(1800)), listOf("Project planning", "Design review", "Focus time")[offset % 3],
                listOf(0xff538264.toInt(), 0xff8270a0.toInt(), 0xffb78348.toInt())[offset % 3]), today)
        }
        return AgendaWidgetDisplay.Ready(AgendaWidgetConfiguration("synthetic-generation", privacy = privacy),
            AgendaWidgetEnvironment(CalendarSelection.Explicit(setOf(CalendarId(1))), ZoneOffset.UTC, false, true),
            today, rows, true, false, false)
    }
    private fun capture(name: String, size: AgendaWidgetSize, display: AgendaWidgetDisplay, fontScale: Float = 1f) {
        RuntimeEnvironment.setQualifiers("en-rUS-w${size.widthDp}dp-h${size.heightDp}dp-notnight-mdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val configured = controller.get()
        val renderer = AgendaWidgetRenderer(configured, ComponentName(context.packageName, "SyntheticWidgetActivity"))
        val host = FrameLayout(configured)
        val root = renderer.render(1, size, display).apply(configured, host)
        host.addView(root)
        configured.setContentView(host)
        host.measure(View.MeasureSpec.makeMeasureSpec(size.widthDp, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size.heightDp, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, size.widthDp, size.heightDp)
        val footer = root.findViewById<View>(R.id.widget_setup)
        val location = IntArray(2); footer.getLocationInWindow(location)
        val rootLocation = IntArray(2); root.getLocationInWindow(rootLocation)
        assertTrue(location[1] - rootLocation[1] + footer.height <= root.height)
        host.captureRoboImage("src/test/screenshots/$name.png")
        controller.pause().stop().destroy()

    }
    @Test fun busyOnlyThreeRows() = capture("widget_agenda_busy_three", AgendaWidgetSize(240, 240), ready(WidgetPrivacy.BUSY_ONLY, 3))
    @Test fun titlesFiveRows() = capture("widget_agenda_titles_five", AgendaWidgetSize(320, 360), ready(WidgetPrivacy.EVENT_TITLES, 5))
    @Test fun titlesEightRows() = capture("widget_agenda_titles_eight", AgendaWidgetSize(320, 500), ready(WidgetPrivacy.EVENT_TITLES, 8))
    @Test fun permissionRecovery() = capture("widget_agenda_permission", AgendaWidgetSize(240, 240), AgendaWidgetDisplay.PermissionRequired)
    @Test fun largeFontPrivateRows() = capture("widget_agenda_large_font", AgendaWidgetSize(320, 240), ready(WidgetPrivacy.BUSY_ONLY, 8), 1.8f)
}
