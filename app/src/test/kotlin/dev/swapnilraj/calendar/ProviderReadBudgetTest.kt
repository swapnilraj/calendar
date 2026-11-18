package dev.swapnilraj.calendar.data.calendar

import android.database.MatrixCursor
import dev.swapnilraj.calendar.core.EditToken
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
class ProviderReadBudgetTest {
    @Test fun countersCheckRowsAndAggregateColumnsBeforeMaterializationAndCloseOnRejection() = runBlocking {
        val raw = MatrixCursor(arrayOf("a", "b")).apply { repeat(3) { addRow(arrayOf("abcd", "efgh")) } }
        try {
            withReadBudget(ProviderReadLimits(10, 20, 7)) {
                raw.bounded().use { cursor ->
                    assertTrue(cursor.moveToNext())
                    assertEquals("abcd", cursor.getString(0))
                    assertEquals("abcd", cursor.getString(0)) // rewind/duplicate access does not consume twice.
                    cursor.getString(1)
                }
            }
            fail("aggregate strings should exceed the limit")
        } catch (_: ProviderReadLimitExceeded) { assertTrue(raw.isClosed) }
        val rows = MatrixCursor(arrayOf("value")).apply { repeat(3) { addRow(arrayOf("x")) } }
        try {
            withReadBudget(ProviderReadLimits(2, 10, 100)) { rows.bounded().use { while (it.moveToNext()) Unit } }
            fail("third raw row should be rejected")
        } catch (_: ProviderReadLimitExceeded) { assertTrue(rows.isClosed) }
    }

    @Test fun coroutineBudgetsRemainIndependentAcrossSuspensionsAndDispatchers() = runBlocking {
        coroutineScope {
            val narrow = async(Dispatchers.Default) {
                withReadBudget(ProviderReadLimits(2, 10, 3)) {
                    val original = currentReadBudget()
                    yield()
                    withContext(Dispatchers.IO) { assertSame(original, currentReadBudget()); currentReadBudget()!!.text("ab") }
                    delay(1)
                    try { currentReadBudget()!!.text("cd"); false } catch (_: ProviderReadLimitExceeded) { true }
                }
            }
            val wide = async(Dispatchers.Default) {
                withReadBudget(ProviderReadLimits(2, 10, 20)) {
                    val original = currentReadBudget()
                    delay(1)
                    withContext(Dispatchers.IO) { assertSame(original, currentReadBudget()); currentReadBudget()!!.text("abcdefgh") }
                    currentReadBudget()!!.text("abcdefgh")
                    true
                }
            }
            assertTrue(narrow.await()); assertTrue(wide.await())
        }
        assertNull(currentReadBudget())
    }

    @Test fun canonicalChecksIncludeEscapingKeysAndUtf8BeforeJsonTreeConstruction() = runBlocking {
        val raw = mapOf("long-key" to "\u0001".repeat(200))
        try { checkCanonical(raw, 1000); fail("escaped JSON byte quota") } catch (_: ProviderReadLimitExceeded) { }
        try { checkStoredJson("\u20ac".repeat(400), 1000); fail("UTF8 byte quota") } catch (_: ProviderReadLimitExceeded) { }
        assertTrue(checkCanonical(mapOf("a" to "ok"), 1000) <= 1000)
        withReadBudget(ProviderReadLimits(10, 5, 1000)) {
            try { checkStoredJson("[1,2,3,4,5,6,7]", 1000); fail("scalar JSON cells before parsing") } catch (_: ProviderReadLimitExceeded) { }
        }
        try { checkStoredJson("[".repeat(200) + "]".repeat(200), 1000); fail("deep JSON before parsing") } catch (_: ProviderReadLimitExceeded) { }
    }

    @Test fun legacyPrivateJsonIsRejectedByStoredByteLengthBeforeParsingOrDispatch() {
        val context = RuntimeEnvironment.getApplication()
        val name = "budget-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name, readBudgets = ProviderReadBudgets(maxCanonicalBytes = 1000))
        try {
            store.writableDatabase.execSQL("INSERT INTO edit_snapshots(token,snapshot) VALUES (?,?)", arrayOf("legacy", "x".repeat(2000)))
            store.writableDatabase.execSQL("INSERT INTO mutation_journal(id,signature,kind,phase,result_ref) VALUES (?,?,?,?,?)",
                arrayOf("legacy", "signature", "CREATE", "COMMITTED", "x".repeat(2000)))
            try { store.edit("legacy"); fail("oversized stored source") } catch (_: ProviderReadLimitExceeded) { }
            try { store.operation("legacy"); fail("oversized stored receipt") } catch (_: ProviderReadLimitExceeded) { }
            assertNull(store.operation("new"))
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
