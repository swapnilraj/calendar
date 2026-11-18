package dev.swapnilraj.calendar.data.calendar

import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AtomicDraftStoreTest {
    private val original = DraftRecord("draft", 7, "{unfinished raw input\\n", Instant.parse("2026-11-18T09:00:00.123456789Z"))
    private suspend fun fixture(block: suspend (CalendarLocalStore, CalendarLocalStore) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val name = "draft-cas-${UUID.randomUUID()}.db"
        val first = CalendarLocalStore(context, name)
        val second = CalendarLocalStore(context, name)
        try { block(first, second) } finally { first.close(); second.close(); context.deleteDatabase(name) }
    }

    @Test fun createUpdateAndDeleteCompareCompleteRawRecordsAcrossHelpersAndReopen() = runBlocking {
        fixture { first, second ->
            assertEquals(DraftChangeResult.Applied, first.compareAndSet(original.id, null, original))
            assertEquals(original, second.load(original.id))
            val changed = original.copy(version = 8, payload = "invalid unchanged-format input", updatedAt = original.updatedAt.plusNanos(1))
            assertEquals(DraftChangeResult.Applied, second.compareAndSet(original.id, original, changed))
            assertEquals(changed, first.load(original.id))
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), first.databaseName)
            try { assertEquals(changed, reopened.load(original.id)) } finally { reopened.close() }
            assertEquals(DraftChangeResult.Changed, first.compareAndSet(original.id, original, null))
            assertEquals(changed, first.load(original.id))
            assertEquals(DraftChangeResult.Applied, first.compareAndSet(original.id, changed, null))
            assertNull(second.load(original.id))
        }
    }

    @Test fun versionPayloadAndTimestampEachParticipateInTheComparison() = runBlocking {
        fixture { first, second ->
            assertEquals(DraftChangeResult.Applied, first.compareAndSet(original.id, null, original))
            val variants = listOf(original.copy(version = 9), original.copy(payload = "different raw text"), original.copy(updatedAt = original.updatedAt.plusNanos(1)))
            variants.forEach { stale ->
                assertEquals(DraftChangeResult.Changed, second.compareAndSet(original.id, stale, stale.copy(payload = "overwrite")))
                assertEquals(DraftChangeResult.Changed, second.compareAndSet(original.id, stale, null))
                assertEquals(original, first.load(original.id))
            }
        }
    }

    @Test fun concurrentAbsentCreatesHaveExactlyOneWinnerAndNeverReplaceIt() = runBlocking {
        fixture { first, second ->
            val other = original.copy(payload = "competing editor")
            val gate = CompletableDeferred<Unit>()
            val a = async(Dispatchers.IO) { gate.await(); first.compareAndSet(original.id, null, original) }
            val b = async(Dispatchers.IO) { gate.await(); second.compareAndSet(original.id, null, other) }
            gate.complete(Unit)
            val results = listOf(a.await(), b.await())
            assertEquals(1, results.count { it == DraftChangeResult.Applied })
            assertEquals(1, results.count { it == DraftChangeResult.Changed })
            assertEquals(if (results[0] == DraftChangeResult.Applied) original else other, first.load(original.id))
        }
    }

    @Test fun concurrentStaleUpdatesHaveOneWinnerAndStaleDeleteCannotRemoveIt() = runBlocking {
        fixture { first, second ->
            first.upsert(original)
            val versions = listOf(original.copy(payload = "editor A"), original.copy(payload = "editor B"))
            val gate = CompletableDeferred<Unit>()
            val a = async(Dispatchers.IO) { gate.await(); first.compareAndSet(original.id, original, versions[0]) }
            val b = async(Dispatchers.IO) { gate.await(); second.compareAndSet(original.id, original, versions[1]) }
            gate.complete(Unit)
            val results = listOf(a.await(), b.await())
            assertEquals(1, results.count { it == DraftChangeResult.Applied })
            assertEquals(1, results.count { it == DraftChangeResult.Changed })
            val winner = versions[if (results[0] == DraftChangeResult.Applied) 0 else 1]
            assertEquals(winner, second.load(original.id))
            assertEquals(DraftChangeResult.Changed, first.compareAndSet(original.id, original, null))
            assertEquals(winner, second.load(original.id))
        }
    }

    @Test fun rollbackAndCorruptRedundantTimestampReturnUnavailableWithoutChangingRawDraft() = runBlocking {
        fixture { first, second ->
            first.upsert(original)
            first.writableDatabase.execSQL("CREATE TRIGGER fail_draft_update BEFORE UPDATE ON drafts BEGIN SELECT RAISE(FAIL, 'Synthetic storage failure'); END")
            assertEquals(DraftChangeResult.Unavailable, second.compareAndSet(original.id, original, original.copy(payload = "new")))
            assertEquals(original, first.load(original.id))
            first.writableDatabase.execSQL("DROP TRIGGER fail_draft_update")
            first.writableDatabase.execSQL("UPDATE drafts SET updated_nano = 0 WHERE id = ?", arrayOf(original.id))
            assertEquals(DraftChangeResult.Unavailable, second.compareAndSet(original.id, original, null))
            assertEquals(original, first.load(original.id))
        }
    }

    @Test fun invalidIdentifiersAndEmptyCompareAreRejectedBeforeAnyWrite() = runBlocking {
        fixture { first, _ ->
            val invalid: List<Pair<DraftRecord?, DraftRecord?>> = listOf(null to null, original to original.copy(id = "other"), null to original.copy(id = "other"))
            invalid.forEach { (expected, replacement) ->
                try { first.compareAndSet(original.id, expected, replacement); fail("invalid CAS arguments") } catch (_: IllegalArgumentException) { }
            }
            assertTrue(first.list().isEmpty())
            assertEquals(DraftChangeResult.Changed, first.compareAndSet(original.id, original, null))
        }
    }
}
