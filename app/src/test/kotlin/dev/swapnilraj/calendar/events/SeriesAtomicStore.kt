package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*

/** In-memory atomic test port; production never emulates CAS using separate legacy calls. */
internal class SeriesAtomicStore : AtomicDraftStore {
    val records = mutableMapOf<String, DraftRecord>()
    var failWrites = false; var failReads = false; var unavailableAfter = false; var compares = 0; var legacyCalls = 0
    var beforeCompare: suspend (DraftRecord?, DraftRecord?) -> Unit = { _, _ -> }
    var beforeLoad: suspend (String) -> Unit = {}
    override suspend fun load(id: String): DraftRecord? { beforeLoad(id); if (failReads) error("fixture read failure"); return records[id] }
    override suspend fun list() = records.values.toList()
    override suspend fun upsert(record: DraftRecord) { legacyCalls++; if (failWrites) error("fixture write failure"); records[record.id] = record }
    override suspend fun delete(id: String) { legacyCalls++; records.remove(id) }
    override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
        compares++; beforeCompare(expected, replacement)
        if (failWrites) return DraftChangeResult.Unavailable
        val result = synchronized(records) {
            require(expected != null || replacement != null)
            require(expected == null || expected.id == id); require(replacement == null || replacement.id == id)
            if (records[id] != expected) DraftChangeResult.Changed
            else { if (replacement == null) records.remove(id) else records[id] = replacement; DraftChangeResult.Applied }
        }
        return if (unavailableAfter && result == DraftChangeResult.Applied) DraftChangeResult.Unavailable else result
    }
}
