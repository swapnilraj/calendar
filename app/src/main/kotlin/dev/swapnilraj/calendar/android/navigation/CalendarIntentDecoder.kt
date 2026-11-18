package dev.swapnilraj.calendar.android.navigation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Decodes only. No Context, ContentResolver, navigation, permission prompts, or calendar writes. */
class CalendarIntentDecoder(private val displayZone: ZoneId) {
    fun decode(intent: Intent?): CalendarIntentRoute {
        if (intent == null || intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_INSERT, Intent.ACTION_EDIT)) {
            return CalendarIntentRoute.Unsupported
        }
        return try {
            val data = intent.data
            val path = data?.let(::providerPath)
            val extras = Extras(intent.extras)
            when (intent.action) {
                Intent.ACTION_VIEW -> {
                    if (path == null) invalid(CalendarIntentError.INVALID_URI)
                    val time = TIME_PATH.matchEntire(path)
                    if (time != null) {
                        val instant = instant(time.groupValues[1].toLongOrNull()
                            ?: invalid(CalendarIntentError.OUT_OF_RANGE_TIME), displayZone)
                        CalendarIntentRoute.OpenDate(instant.atZone(displayZone).toLocalDate(), instant)
                    } else CalendarIntentRoute.OpenEvent(eventId(path), occurrence(extras))
                }
                Intent.ACTION_INSERT -> {
                    if (path != "/events" && !(data == null && intent.type in INSERT_TYPES)) {
                        invalid(CalendarIntentError.INVALID_URI)
                    }
                    if (intent.type != null && intent.type !in INSERT_TYPES) {
                        return CalendarIntentRoute.Unsupported
                    }
                    CalendarIntentRoute.CreateDraftSeed(seed(extras))
                }
                Intent.ACTION_EDIT -> CalendarIntentRoute.EditEvent(
                    eventId(path ?: invalid(CalendarIntentError.INVALID_URI)), seed(extras))
                else -> CalendarIntentRoute.Unsupported
            }
        } catch (failure: InvalidInput) {
            CalendarIntentRoute.Invalid(failure.reason)
        } catch (_: RuntimeException) {
            // Malformed parcels and platform URI/Bundle failures must not escape the entry point.
            CalendarIntentRoute.Invalid(CalendarIntentError.MALFORMED_INPUT)
        }
    }

    private fun providerPath(uri: Uri): String {
        if (uri.scheme != "content" || uri.encodedAuthority != CalendarContract.AUTHORITY ||
            uri.query != null || uri.fragment != null) invalid(CalendarIntentError.INVALID_URI)
        // Keep encodedPath: Android pathSegments normalizes duplicate slashes and encoded separators.
        return uri.encodedPath ?: invalid(CalendarIntentError.INVALID_URI)
    }

    private fun eventId(path: String): Long {
        val text = EVENT_PATH.matchEntire(path)?.groupValues?.get(1)
            ?: invalid(CalendarIntentError.INVALID_URI)
        return text.toLongOrNull() ?: invalid(CalendarIntentError.INVALID_URI)
    }

    private fun instant(millis: Long, zone: ZoneId): Instant {
        val instant = Instant.ofEpochMilli(millis)
        if (instant.atZone(zone).year !in 1..9999) invalid(CalendarIntentError.OUT_OF_RANGE_TIME)
        return instant
    }

    private fun occurrence(extras: Extras): CalendarOccurrenceHints {
        val allDay = extras.boolean(CalendarContract.EXTRA_EVENT_ALL_DAY)
        val zone = if (allDay == true) ZoneOffset.UTC else displayZone
        val begin = extras.long(CalendarContract.EXTRA_EVENT_BEGIN_TIME)?.let { instant(it, zone) }
        val end = extras.long(CalendarContract.EXTRA_EVENT_END_TIME)?.let { instant(it, zone) }
        if (end != null && begin == null) invalid(CalendarIntentError.MISSING_BEGIN)
        if (begin != null && end != null) {
            if (end < begin) invalid(CalendarIntentError.INVALID_INTERVAL)
            if (allDay == true && end.atOffset(ZoneOffset.UTC).toLocalDate() <= begin.atOffset(ZoneOffset.UTC).toLocalDate()) {
                invalid(CalendarIntentError.INVALID_INTERVAL)
            }
        }
        return CalendarOccurrenceHints(begin, end, allDay)
    }

    private fun seed(extras: Extras): CalendarDraftSeed {
        val availability = extras.int(CalendarContract.Events.AVAILABILITY)?.also {
            if (it !in setOf(CalendarContract.Events.AVAILABILITY_BUSY, CalendarContract.Events.AVAILABILITY_FREE,
                    CalendarContract.Events.AVAILABILITY_TENTATIVE)) invalid(CalendarIntentError.INVALID_SEED_VALUE)
        }
        val access = extras.int(CalendarContract.Events.ACCESS_LEVEL)?.also {
            if (it !in setOf(CalendarContract.Events.ACCESS_DEFAULT, CalendarContract.Events.ACCESS_CONFIDENTIAL,
                    CalendarContract.Events.ACCESS_PRIVATE, CalendarContract.Events.ACCESS_PUBLIC)) invalid(CalendarIntentError.INVALID_SEED_VALUE)
        }
        val zone = extras.string(CalendarContract.Events.EVENT_TIMEZONE)?.let {
            try { ZoneId.of(it) } catch (_: RuntimeException) { invalid(CalendarIntentError.INVALID_SEED_VALUE) }
        }
        val hints = occurrence(extras)
        if (zone != null && hints.allDay != true) {
            for (time in listOfNotNull(hints.begin, hints.end)) {
                if (time.atZone(zone).year !in 1..9999) invalid(CalendarIntentError.OUT_OF_RANGE_TIME)
            }
        }
        return CalendarDraftSeed(hints, extras.string(CalendarContract.Events.TITLE),
            extras.string(CalendarContract.Events.EVENT_LOCATION), extras.string(CalendarContract.Events.DESCRIPTION),
            extras.guests(), extras.string(CalendarContract.Events.RRULE), zone, availability, access)
    }

    private class Extras(private val bundle: Bundle?) {
        @Suppress("DEPRECATION")
        private fun value(key: String): Any? = if (bundle?.containsKey(key) == true) {
            bundle.get(key) ?: invalid(CalendarIntentError.INVALID_EXTRA_TYPE)
        } else null

        fun long(key: String): Long? = value(key)?.let { it as? Long ?: invalid(CalendarIntentError.INVALID_EXTRA_TYPE) }
        fun int(key: String): Int? = value(key)?.let { it as? Int ?: invalid(CalendarIntentError.INVALID_EXTRA_TYPE) }
        fun boolean(key: String): Boolean? = value(key)?.let { it as? Boolean ?: invalid(CalendarIntentError.INVALID_EXTRA_TYPE) }
        fun string(key: String): String? = value(key)?.let { (it as? CharSequence)?.toString() ?: invalid(CalendarIntentError.INVALID_EXTRA_TYPE) }
        fun guests(): List<String> = when (val value = value(Intent.EXTRA_EMAIL)) {
            null -> emptyList()
            is String -> value.split(',', ';').map(String::trim).filter(String::isNotEmpty)
            is Array<*> -> value.map { it as? String ?: invalid(CalendarIntentError.INVALID_EXTRA_TYPE) }
            is List<*> -> value.map { it as? String ?: invalid(CalendarIntentError.INVALID_EXTRA_TYPE) }
            else -> invalid(CalendarIntentError.INVALID_EXTRA_TYPE)
        }
    }

    private class InvalidInput(val reason: CalendarIntentError) : RuntimeException()

    companion object {
        private val EVENT_PATH = Regex("/events/([0-9]+)")
        private val TIME_PATH = Regex("/time/(-?[0-9]+)")
        const val EVENTS_MIME_TYPE = "vnd.android.cursor.dir/event"
        const val EVENT_MIME_TYPE = "vnd.android.cursor.item/event"
        private val INSERT_TYPES = setOf(EVENTS_MIME_TYPE, EVENT_MIME_TYPE)
        private fun invalid(reason: CalendarIntentError): Nothing = throw InvalidInput(reason)
    }
}
