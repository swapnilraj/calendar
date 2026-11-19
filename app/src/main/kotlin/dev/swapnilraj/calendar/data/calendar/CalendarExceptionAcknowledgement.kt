package dev.swapnilraj.calendar.data.calendar

import android.net.Uri
import android.provider.CalendarContract

/** AOSP 10 f013f1c:2620 / 16 77bed8f:2665 append the inserted ID to the input
 * exception/<master> URI. A numeric suffix alone does not bind its authority or parent. */
internal object CalendarExceptionAcknowledgement {
    fun read(uri: Uri?, masterEventId: Long, existingIds: Set<Long>): Long {
        require(masterEventId > 0)
        require(uri != null && uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY &&
            uri.encodedQuery == null && uri.encodedFragment == null)
        val parts = uri.pathSegments
        require(parts.size == 3 && parts[0] == "exception" && parts[1] == masterEventId.toString())
        val id = parts[2].toLong()
        require(id > 0 && id !in existingIds && parts[2] == id.toString() && uri.encodedPath == "/exception/$masterEventId/$id")
        return id
    }
}
