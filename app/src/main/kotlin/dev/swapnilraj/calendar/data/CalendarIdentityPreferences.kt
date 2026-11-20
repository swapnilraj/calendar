package dev.swapnilraj.calendar.data

import dev.swapnilraj.calendar.core.*
import org.json.JSONObject

/** Malformed or legacy proof is unproven, never permission to use the current row with that ID. */
internal object CalendarIdentityPreferences {
    fun encode(value: CalendarIdentity): String = JSONObject().put("schema", 1).put("calendar", value.calendarId.value)
        .put("accountName", value.account.name).put("accountType", value.account.type)
        .put("owner", value.owner ?: JSONObject.NULL).put("provider", value.providerId ?: JSONObject.NULL).toString()

    fun decode(raw: String?): CalendarIdentity? = try {
        if (raw == null) null else JSONObject(raw).let { value ->
            require(value.keys().asSequence().toSet() == setOf("schema", "calendar", "accountName", "accountType", "owner", "provider"))
            require(value.get("schema") == 1)
            val number = value.get("calendar"); require(number is Int || number is Long)
            fun text(key: String): String = (value.get(key) as String).also { require(it.isNotBlank()) }
            fun optional(key: String): String? = if (value.isNull(key)) null else text(key)
            CalendarIdentity(CalendarId((number as Number).toLong()), CalendarAccount(text("accountName"), text("accountType")), optional("owner"), optional("provider"))
        }
    } catch (_: Exception) { null }
}
