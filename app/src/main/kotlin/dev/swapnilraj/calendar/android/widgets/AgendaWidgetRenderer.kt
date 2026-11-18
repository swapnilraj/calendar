package dev.swapnilraj.calendar.android.widgets

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.EventTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A fixed stack deliberately avoids mutable collection templates/fill-in event identities. */
class AgendaWidgetRenderer(private val context: Context, private val activity: ComponentName) {
    init { require(activity.packageName == context.packageName) }

    fun rowCapacity(size: AgendaWidgetSize): Int {
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        return AgendaWidgetSize(size.widthDp, (size.heightDp / scale).toInt().coerceAtLeast(1)).rowCapacity
    }

    fun render(widgetId: Int, size: AgendaWidgetSize, display: AgendaWidgetDisplay): RemoteViews {
        require(widgetId > 0)
        val views = RemoteViews(context.packageName, R.layout.widget_agenda)
        views.removeAllViews(R.id.widget_rows)
        views.setViewVisibility(R.id.widget_today, View.GONE)
        views.setViewVisibility(R.id.widget_create, View.GONE)
        views.setViewVisibility(R.id.widget_more, View.GONE)
        views.setViewVisibility(R.id.widget_coverage, View.GONE)
        views.setOnClickPendingIntent(R.id.widget_setup, pending(AgendaWidgetRoute.Setup(widgetId)))
        val message = when (display) {
            AgendaWidgetDisplay.Loading -> R.string.widget_loading
            AgendaWidgetDisplay.SetupRequired -> R.string.widget_setup
            AgendaWidgetDisplay.PermissionRequired -> R.string.widget_permission
            AgendaWidgetDisplay.Unavailable -> R.string.widget_unavailable
            is AgendaWidgetDisplay.Ready -> if (display.noCalendars) R.string.widget_no_calendars
                else if (display.rows.isEmpty()) R.string.widget_empty else null
        }
        views.setTextViewText(R.id.widget_message, message?.let(context::getString).orEmpty())
        views.setViewVisibility(R.id.widget_message, if (message == null) View.GONE else View.VISIBLE)
        if (display is AgendaWidgetDisplay.Ready) {
            val generation = display.configuration.generation
            views.setViewVisibility(R.id.widget_today, View.VISIBLE)
            views.setViewVisibility(R.id.widget_create, View.VISIBLE)
            views.setViewVisibility(R.id.widget_more, View.VISIBLE)
            views.setViewVisibility(R.id.widget_coverage, if (display.coverageIncomplete) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.widget_today, pending(AgendaWidgetRoute.Today(widgetId, generation)))
            views.setOnClickPendingIntent(R.id.widget_create, pending(AgendaWidgetRoute.Create(widgetId, generation)))
            views.setOnClickPendingIntent(R.id.widget_more, pending(AgendaWidgetRoute.More(widgetId, generation, display.date)))
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", locale)
            val timeFormatter = DateTimeFormatter.ofPattern(if (display.environment.use24Hour) "HH:mm" else "h:mm a", locale)
            display.rows.take(rowCapacity(size)).forEach { row ->
                val private = display.configuration.privacy == WidgetPrivacy.BUSY_ONLY
                val title = if (private) context.getString(R.string.widget_busy) else
                    row.occurrence.title.takeIf { it.isNotBlank() }?.let(::boundedTitle) ?: context.getString(R.string.widget_untitled)
                val time = when (val eventTime = row.occurrence.time) {
                    is EventTime.AllDay -> context.getString(R.string.widget_all_day)
                    is EventTime.Timed -> {
                        val start = eventTime.start.atZone(display.environment.displayZone)
                        val end = eventTime.end.atZone(display.environment.displayZone)
                        val endText = if (start.toLocalDate() == end.toLocalDate()) timeFormatter.format(end)
                            else "${dateFormatter.format(end)} ${timeFormatter.format(end)}"
                        if (start == end) timeFormatter.format(start) else context.getString(R.string.widget_time_range,
                            timeFormatter.format(start), endText)
                    }
                }
                val date = if (row.date == display.date) context.getString(R.string.widget_today) else dateFormatter.format(row.date)
                val label = context.getString(R.string.widget_row_time, date, time)
                val child = RemoteViews(context.packageName, R.layout.widget_agenda_row)
                val compact = context.resources.configuration.fontScale > 1.3f && size.heightDp < 336
                child.setTextViewText(R.id.widget_row_title, if (compact) "$label · $title" else title)
                child.setTextViewText(R.id.widget_row_time, label)
                child.setViewVisibility(R.id.widget_row_time, if (compact) View.GONE else View.VISIBLE)
                child.setContentDescription(R.id.widget_row, "$title, $label")
                child.setInt(R.id.widget_color, "setBackgroundColor", if (private) 0xff7d857f.toInt() else row.occurrence.color)
                child.setOnClickPendingIntent(R.id.widget_row, pending(AgendaWidgetRoute.OpenEvent(widgetId,
                    generation, row.occurrence.ref, row.date)))
                views.addView(R.id.widget_rows, child)
            }
        }
        return views
    }

    private fun pending(route: AgendaWidgetRoute): PendingIntent = PendingIntent.getActivity(context, 0,
        AgendaWidgetIntents.intent(context, activity, route), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun boundedTitle(title: String): String {
        val count = title.codePointCount(0, title.length)
        return if (count <= 200) title else title.substring(0, title.offsetByCodePoints(0, 200)) + "…"
    }
}
