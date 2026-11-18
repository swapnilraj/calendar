package dev.swapnilraj.calendar.android.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.EventRef
import dev.swapnilraj.calendar.core.RecurrenceRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Root checks the live widget/configuration generation again before resolving any event metadata. */
sealed interface AgendaWidgetRoute {
    val widgetId: Int
    data class Setup(override val widgetId: Int) : AgendaWidgetRoute
    data class Today(override val widgetId: Int, val generation: String) : AgendaWidgetRoute
    data class Create(override val widgetId: Int, val generation: String) : AgendaWidgetRoute
    data class More(override val widgetId: Int, val generation: String, val date: LocalDate) : AgendaWidgetRoute
    data class OpenEvent(override val widgetId: Int, val generation: String, val ref: EventRef, val date: LocalDate, val calendarFingerprint: String? = null) : AgendaWidgetRoute
}

object AgendaWidgetIntents {
    const val ACTION = "dev.swapnilraj.calendar.widgets.OPEN"
    private const val PREFIX = "agenda_widget."

    fun intent(context: Context, activity: ComponentName, route: AgendaWidgetRoute): Intent {
        require(activity.packageName == context.packageName && route.widgetId > 0)
        val uri = Uri.Builder().scheme("daylight").authority("agenda-widget").appendPath("v1")
            .appendPath(route.widgetId.toString())
        return Intent(ACTION).setComponent(activity).apply {
            putExtra(PREFIX + "id", route.widgetId)
            fun generation(value: String) {
                require(value.isNotBlank()); uri.appendPath(value); putExtra(PREFIX + "generation", value)
            }
            fun date(value: LocalDate) {
                require(value.year in 1..9999); uri.appendPath(value.toString()); putExtra(PREFIX + "date", value.toString())
            }
            when (route) {
                is AgendaWidgetRoute.Setup -> uri.appendPath("setup")
                is AgendaWidgetRoute.Today -> { uri.appendPath("today"); generation(route.generation) }
                is AgendaWidgetRoute.Create -> { uri.appendPath("create"); generation(route.generation) }
                is AgendaWidgetRoute.More -> { uri.appendPath("more"); generation(route.generation); date(route.date) }
                is AgendaWidgetRoute.OpenEvent -> {
                    uri.appendPath("event"); generation(route.generation); date(route.date)
                    val ref = route.ref
                    uri.appendPath(ref.calendarId.value.toString()).appendPath(ref.eventId.toString())
                        .appendPath(ref.instanceStart.toString()).appendPath(ref.instanceEnd.toString())
                        .appendPath(ref.recurrence?.masterEventId?.toString() ?: "-")
                        .appendPath(ref.recurrence?.originalStart?.toString() ?: "-")
                    putExtra(PREFIX + "calendar", ref.calendarId.value); putExtra(PREFIX + "event", ref.eventId)
                    putExtra(PREFIX + "start", ref.instanceStart.toString()); putExtra(PREFIX + "end", ref.instanceEnd.toString())
                    route.calendarFingerprint?.let {
                        require(Regex("[0-9a-f]{64}").matches(it))
                        uri.appendPath("calendar").appendPath(it)
                        putExtra(PREFIX + "calendar_fingerprint", it)
                    }
                    ref.recurrence?.let {
                        putExtra(PREFIX + "master", it.masterEventId); putExtra(PREFIX + "original", it.originalStart.toString())
                    }
                }
            }
            data = uri.build()
        }
    }

    @Suppress("DEPRECATION")
    fun decode(context: Context, intent: Intent?, expectedActivity: ComponentName): AgendaWidgetRoute? = try {
        requireNotNull(intent)
        require(expectedActivity.packageName == context.packageName && intent.component == expectedActivity && intent.action == ACTION)
        val uri = requireNotNull(intent.data)
        require(uri.scheme == "daylight" && uri.encodedAuthority == "agenda-widget" && uri.query == null && uri.fragment == null)
        val path = uri.pathSegments
        require(path.size >= 3 && path[0] == "v1")
        val extras = requireNotNull(intent.extras)
        fun value(name: String): Any = requireNotNull(extras.get(PREFIX + name))
        fun string(name: String) = value(name) as String
        fun date() = LocalDate.parse(string("date")).also { require(it.year in 1..9999) }
        val id = value("id") as Int
        require(id > 0 && path[1] == id.toString())
        fun generation() = string("generation").also { require(it.isNotBlank()) }
        fun instant(name: String) = Instant.parse(string(name)).also { require(it.atOffset(ZoneOffset.UTC).year in 1..9999) }
        val route = when (path[2]) {
            "setup" -> AgendaWidgetRoute.Setup(id)
            "today" -> AgendaWidgetRoute.Today(id, generation())
            "create" -> AgendaWidgetRoute.Create(id, generation())
            "more" -> AgendaWidgetRoute.More(id, generation(), date())
            "event" -> {
                require(extras.containsKey(PREFIX + "master") == extras.containsKey(PREFIX + "original"))
                val recurrence = if (extras.containsKey(PREFIX + "master"))
                    RecurrenceRef(value("master") as Long, instant("original")) else null
                AgendaWidgetRoute.OpenEvent(id, generation(), EventRef(CalendarId(value("calendar") as Long),
                    value("event") as Long, instant("start"), instant("end"), recurrence), date(),
                    if (extras.containsKey(PREFIX + "calendar_fingerprint")) string("calendar_fingerprint") else null)
            }
            else -> error("Unsupported widget route")
        }
        require(intent.data == AgendaWidgetIntents.intent(context, expectedActivity, route).data)
        route
    } catch (_: RuntimeException) { null }
}
