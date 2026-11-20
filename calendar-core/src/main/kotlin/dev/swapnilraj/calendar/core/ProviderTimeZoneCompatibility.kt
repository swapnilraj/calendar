package dev.swapnilraj.calendar.core

import java.time.ZoneId
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Portable guard for AOSP's older tzdb-only Time and newer calendarcommon2 String parser. The
 * ZoneId overload silently adapts offsets; the String overload may silently fall back to GMT.
 * Require a device tzdb ID (or provably fixed UTC) AND equal full String-resolved rules. Custom
 * nonzero GMT offsets are conservatively unsupported even where newer Java accepts them.
 * This is not proof of every OEM provider's actual recurrence expansion. Input is never rewritten.
 */
object ProviderTimeZoneCompatibility {
    private val tzdbIds = ZoneId.getAvailableZoneIds()
    fun isCompatible(zone: ZoneId): Boolean = try {
        val rules = zone.rules
        val fixedUtc = rules.isFixedOffset && rules.getOffset(Instant.EPOCH) == ZoneOffset.UTC
        (zone.id in tzdbIds || fixedUtc) && TimeZone.getTimeZone(zone.id).toZoneId().rules == rules
    } catch (_: Exception) {
        false
    }
}
