package dev.swapnilraj.calendar.data.calendar

import android.content.ContentProviderResult
import android.net.Uri
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject

/** Receipt5 freezes result positions and raw bounded ACK facts, never inferred provider identities. */
internal object CalendarCreateAcknowledgments {
    private const val MaxResults = 4002
    private const val MaxUriBytes = 256
    private const val MaxFactsBytes = 64 * 1024

    fun layout(alerts: Int): JSONObject {
        require(alerts in 0..MaxResults - 2)
        return JSONObject().put("calendarAssert", 0).put("eventInsert", 1)
            .put("reminderInserts", JSONArray(List(alerts) { it + 2 })).put("resultCount", alerts + 2)
    }

    /** Capture only expected positions. URI/count meaning is deliberately checked after COMMITTED.
     * Null is incomplete evidence, including a missing/oversized/exception result. */
    fun capture(results: Array<ContentProviderResult>, expected: Int): JSONObject? = try {
        require(expected in 2..MaxResults && results.size == expected)
        val facts = JSONArray()
        var bytes = 64
        for (index in 0 until expected) {
            val result = results[index]
            val raw = result.uri?.toString()
            require(raw == null || raw.length <= MaxUriBytes && raw.toByteArray(Charsets.UTF_8).size <= MaxUriBytes)
            require(raw != null || result.count != null)
            val fact = JSONObject().put("index", index).put("uri", raw ?: JSONObject.NULL).put("count", result.count ?: JSONObject.NULL)
            bytes += fact.toString().toByteArray(Charsets.UTF_8).size + 1
            require(bytes <= MaxFactsBytes)
            facts.put(fact)
        }
        JSONObject().put("resultCount", results.size).put("results", facts)
    } catch (_: Exception) { null }

    /** A successful parse is complete ACK identity proof, not proof of current child payload. */
    fun reminderIds(facts: JSONObject?, alerts: Int, master: Long): List<Long>? = try {
        require(master > 0 && alerts in 0..MaxResults - 2)
        val expected = alerts + 2
        val raw = requireNotNull(facts)
        require(raw.keys().asSequence().toSet() == setOf("resultCount", "results"))
        require(integer(raw.get("resultCount")) == expected.toLong())
        require(raw.toString().toByteArray(Charsets.UTF_8).size <= MaxFactsBytes)
        val rows = raw.getJSONArray("results"); require(rows.length() == expected)
        fun row(index: Int): JSONObject = rows.getJSONObject(index).also {
            require(it.keys().asSequence().toSet() == setOf("index", "uri", "count"))
            require(integer(it.get("index")) == index.toLong())
        }
        val calendar = row(0)
        require(calendar.isNull("uri") && integer(calendar.get("count")) == 1L)
        val event = row(1)
        require(event.isNull("count") && insertId(Uri.parse(event.get("uri") as? String ?: error("Missing master ACK")), "events") == master)
        List(alerts) { index ->
            val child = row(index + 2)
            require(child.isNull("count"))
            insertId(Uri.parse(child.get("uri") as? String ?: error("Missing child ACK")), "reminders")
        }.also { require(it.distinct().size == it.size) }
    } catch (_: Exception) { null }

    fun insertId(uri: Uri?, table: String): Long {
        require(uri != null && uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY && uri.encodedQuery == null && uri.encodedFragment == null)
        val raw = uri.toString()
        require(raw.length <= MaxUriBytes && raw.toByteArray(Charsets.UTF_8).size <= MaxUriBytes)
        val path = uri.pathSegments
        require(path.size == 2 && path[0] == table)
        val id = path[1].toLong()
        require(id > 0 && uri.encodedPath == "/$table/$id")
        return id
    }

    fun positiveIds(array: JSONArray): List<Long> {
        require(array.length() <= MaxResults - 2)
        return List(array.length()) { integer(array.get(it)).also { id -> require(id > 0) } }
    }
    fun integer(value: Any): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Not an exact integer")
    }
}
