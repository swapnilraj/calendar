package dev.swapnilraj.calendar.ui.events

import dev.swapnilraj.calendar.core.*
import org.json.JSONObject

/** Exact destination proof. No current calendar metadata or display labels are used to fill missing fields. */
internal object CalendarIdentityJson {
    fun encode(value: CalendarIdentity): JSONObject = JSONObject().put("calendar", value.calendarId.value)
        .put("accountName", value.account.name).put("accountType", value.account.type)
        .put("owner", value.owner ?: JSONObject.NULL).put("providerId", value.providerId ?: JSONObject.NULL)
    fun optional(value: JSONObject, key: String): CalendarIdentity? {
        if (!value.has(key) || value.isNull(key)) return null
        val source = value.getJSONObject(key)
        require(source.keys().asSequence().toSet() == setOf("calendar", "accountName", "accountType", "owner", "providerId"))
        fun text(key: String): String = source.get(key).let { require(it is String); it }
        return CalendarIdentity(CalendarId(source.getLong("calendar")), CalendarAccount(text("accountName"), text("accountType")),
            if (source.isNull("owner")) null else text("owner"), if (source.isNull("providerId")) null else text("providerId"))
    }
}
