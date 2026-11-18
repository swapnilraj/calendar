package dev.swapnilraj.calendar.data.calendar

import android.content.ContentValues

/** Synthetic legacy/corruption setup. Production phase transitions deliberately cannot do this. */
internal fun CalendarLocalStore.forceOperationForTest(id: String, phase: String, metadata: String? = null) {
    check(writableDatabase.update("mutation_journal", ContentValues().apply {
        put("phase", phase); if (metadata != null) put("result_ref", metadata)
    }, "id = ?", arrayOf(id)) == 1)
}
