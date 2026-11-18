package dev.swapnilraj.calendar.android.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews

class AndroidAgendaWidgetHost(context: Context) : AgendaWidgetHost {
    private val manager = AppWidgetManager.getInstance(context)
    private val provider = ComponentName(context, AgendaWidgetProvider::class.java)
    override fun widgetIds(): Set<Int> = manager.getAppWidgetIds(provider).filter { it > 0 }.toSet()
    override fun size(widgetId: Int): AgendaWidgetSize {
        val options = manager.getAppWidgetOptions(widgetId)
        return AgendaWidgetSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220).coerceAtLeast(1),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 240).coerceAtLeast(1))
    }
    override fun publish(widgetId: Int, views: RemoteViews) { manager.updateAppWidget(widgetId, views) }
}

/** Root registers this provider/metadata and supplies refresh jobs independently of reminders. */
class AgendaWidgetProvider : AppWidgetProvider() {
    private fun runtime(context: Context) = (context.applicationContext as? AgendaWidgetRuntimeOwner)?.agendaWidgetRuntime
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        runtime(context)?.invalidate(appWidgetIds.toSet())
    }
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        runtime(context)?.invalidate(setOf(appWidgetId))
    }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val runtime = runtime(context) ?: return
        val pending = goAsync()
        runtime.removed(appWidgetIds.toSet(), pending::finish)
    }
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val runtime = runtime(context) ?: return
        val pending = goAsync()
        // Restored IDs require fresh setup, including privacy; never inherit another widget's title mode.
        runtime.removed(oldWidgetIds.toSet() + newWidgetIds.toSet(), pending::finish)
    }
}
