package dev.swapnilraj.calendar.data.contacts

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import dev.swapnilraj.calendar.contacts.DeviceContactSearchResult
import dev.swapnilraj.calendar.contacts.DeviceContactSuggestion
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidDeviceContactSuggestionSourceTest {
    private class TrackingCursor : MatrixCursor(Fields) {
        var closed = false
        var moves = 0
        var reads = 0
        var moving: ((Int) -> Unit)? = null
        override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
            moves++; moving?.invoke(newPosition); return true
        }
        override fun getString(column: Int): String? { reads++; return super.getString(column) }
        override fun close() { closed = true; super.close() }
    }
    private data class Request(val uri: Uri, val fields: List<String>?, val selection: String?, val args: List<String>?, val sort: String?, val signal: CancellationSignal?)
    private class Provider : ContentProvider() {
        val requests = mutableListOf<Request>()
        var cursor: Cursor? = TrackingCursor()
        var querying: ((CancellationSignal?) -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
            error("Cancellation-aware query is required")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, cancellationSignal: CancellationSignal?): Cursor? {
            requests += Request(uri, projection?.toList(), selection, args?.toList(), sort, cancellationSignal)
            querying?.invoke(cancellationSignal)
            return cursor
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = throw AssertionError("Contacts must never be written")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw AssertionError("Contacts must never be written")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw AssertionError("Contacts must never be written")
    }
    private class Fixture {
        val provider = Provider()
        var permission = true
        val permissionChecks = mutableListOf<String>()
        init { ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider) }
        fun source(timeout: Long = 2_000, io: CoroutineDispatcher = Dispatchers.Unconfined) = AndroidDeviceContactSuggestionSource(
            RuntimeEnvironment.getApplication().contentResolver, { permissionChecks += it; permission }, io, timeout)
        fun rows(vararg rows: Pair<String?, String?>): TrackingCursor = TrackingCursor().also { c ->
            rows.forEach { c.addRow(arrayOf(it.first, it.second)) }; provider.cursor = c
        }
    }

    @Test fun disabledSearchNeverQueriesOrChecksPermissionEvenWithValidInputAndGrant() = runBlocking {
        val f = Fixture()
        assertEquals(DeviceContactSearchResult.Disabled, f.source().search("Alice", false))
        assertTrue(f.provider.requests.isEmpty()); assertTrue(f.permissionChecks.isEmpty())
    }

    @Test fun emptyShortOversizedControlAndMalformedUnicodeQueriesNeverReachProvider() = runBlocking {
        val f = Fixture(); val source = f.source()
        for (query in listOf("", " ", "a", " a ")) assertEquals(DeviceContactSearchResult.QueryRequired, source.search(query, true))
        for (query in listOf("a".repeat(65), "😀".repeat(65), "x".repeat(129), "Al\n", "Al\u0000", "\uD800x", "x\uDC00"))
            assertEquals(DeviceContactSearchResult.InvalidQuery, source.search(query, true))
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test fun boundedUnicodeQueryIsAcceptedAndBlankResultsAreTruthful() = runBlocking {
        val f = Fixture(); val cursor = f.rows()
        assertEquals(DeviceContactSearchResult.Ready(emptyList(), false), f.source().search("😀".repeat(64), true))
        assertEquals(1, f.provider.requests.size); assertTrue(cursor.closed)
    }

    @Test fun searchPinsPlainLocalFilterDirectoryProjectionAndOneQueryWithoutIdsOrAccountParameters() = runBlocking {
        val f = Fixture(); f.rows("alice@example.invalid" to "Alice")
        val term = "Al/ice?directory=99&limit=999"
        assertEquals(DeviceContactSearchResult.Ready(listOf(DeviceContactSuggestion("alice@example.invalid", "Alice")), false), f.source().search(term, true))
        val request = f.provider.requests.single()
        assertEquals(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI.pathSegments + term, request.uri.pathSegments)
        assertEquals(ContactsContract.AUTHORITY, request.uri.authority)
        assertEquals(setOf(ContactsContract.DIRECTORY_PARAM_KEY, ContactsContract.LIMIT_PARAM_KEY), request.uri.queryParameterNames)
        assertEquals(ContactsContract.Directory.DEFAULT.toString(), request.uri.getQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY))
        assertEquals("51", request.uri.getQueryParameter(ContactsContract.LIMIT_PARAM_KEY))
        assertEquals(Fields.toList(), request.fields); assertNull(request.selection); assertNull(request.args); assertNull(request.sort)
        assertNotNull(request.signal); assertTrue(f.permissionChecks.all { it == Manifest.permission.READ_CONTACTS })
    }

    @Test fun rawValuesRemainDistinctExceptByteIdenticalEmailNameTuples() = runBlocking {
        val f = Fixture()
        val cursor = f.rows(" A@example.invalid " to " Alice ", " A@example.invalid " to " Alice ", "a@example.invalid" to "Alice",
            " A@example.invalid " to "Different", "invalid email" to null, "" to "", null to "No address")
        assertEquals(DeviceContactSearchResult.Ready(listOf(DeviceContactSuggestion(" A@example.invalid ", " Alice "), DeviceContactSuggestion("a@example.invalid", "Alice"),
            DeviceContactSuggestion(" A@example.invalid ", "Different"), DeviceContactSuggestion("invalid email", null), DeviceContactSuggestion("", "")), false), f.source().search("Al", true))
        assertTrue(cursor.closed)
    }

    @Test fun providerIgnoringLimitStillReadsOnlyFiftyRowsAndOneLookaheadWithoutPagination() = runBlocking {
        val f = Fixture(); val cursor = f.rows(*Array(100) { "email$it" to "Name$it" })
        val result = f.source().search("Al", true) as DeviceContactSearchResult.Ready
        assertEquals(50, result.items.size); assertTrue(result.truncated)
        assertEquals(51, cursor.moves); assertEquals(100, cursor.reads); assertTrue(cursor.closed)
        assertEquals(1, f.provider.requests.size)
    }

    @Test fun duplicatesDoNotAllowScanningBeyondRawRowBudgetOrTriggerRefill() = runBlocking {
        val f = Fixture(); f.rows(*Array(100) { "same" to "Same" })
        assertEquals(DeviceContactSearchResult.Ready(listOf(DeviceContactSuggestion("same", "Same")), true), f.source().search("Sa", true))
        assertEquals(1, f.provider.requests.size)
    }

    @Test fun oversizedEmailOrNameReturnsNoPartialSuggestionsAndClosesCursor() = runBlocking {
        for (row in listOf("x".repeat(257) to "Name", "email" to "x".repeat(257))) {
            val f = Fixture(); val cursor = f.rows("small" to "First", row)
            assertEquals(DeviceContactSearchResult.LimitExceeded, f.source().search("Al", true)); assertTrue(cursor.closed)
        }
    }

    @Test fun utf8BudgetCountsRawDuplicateCellsAndNeverReturnsTruncatedStrings() = runBlocking {
        val f = Fixture(); val cursor = f.rows(*Array(33) { "a".repeat(256) to "n".repeat(256) })
        assertEquals(DeviceContactSearchResult.LimitExceeded, f.source().search("Al", true))
        assertTrue(cursor.closed); assertEquals(66, cursor.reads)
    }

    @Test fun permissionMissingInitiallyOrImmediatelyBeforeQueryNeverReadsContacts() = runBlocking {
        val f = Fixture(); f.permission = false
        assertEquals(DeviceContactSearchResult.PermissionRequired, f.source().search("Al", true))
        assertTrue(f.provider.requests.isEmpty())
        var calls = 0
        val source = AndroidDeviceContactSuggestionSource(RuntimeEnvironment.getApplication().contentResolver, { ++calls == 1 }, Dispatchers.Unconfined)
        assertEquals(DeviceContactSearchResult.PermissionRequired, source.search("Al", true)); assertTrue(f.provider.requests.isEmpty())
    }

    @Test fun permissionRevokedDuringQueryClosesCursorWithoutReadingFields() = runBlocking {
        val f = Fixture(); val cursor = f.rows("alice@example.invalid" to "Alice")
        f.provider.querying = { f.permission = false }
        assertEquals(DeviceContactSearchResult.PermissionRequired, f.source().search("Al", true))
        assertTrue(cursor.closed); assertEquals(0, cursor.reads)
        val noCursor = Fixture(); noCursor.provider.cursor = null
        noCursor.provider.querying = { noCursor.permission = false }
        assertEquals(DeviceContactSearchResult.PermissionRequired, noCursor.source().search("Al", true))
    }

    @Test fun permissionRevokedDuringReadDiscardsAlreadyBufferedResults() = runBlocking {
        val f = Fixture(); val cursor = f.rows("one" to "First", "two" to "Second")
        cursor.moving = { if (it == 1) f.permission = false }
        assertEquals(DeviceContactSearchResult.PermissionRequired, f.source().search("Al", true)); assertTrue(cursor.closed)
        assertEquals(2, cursor.reads)
    }

    @Test fun permissionRevokedAtEndOfCursorCannotPublishReady() = runBlocking {
        val f = Fixture(); val cursor = f.rows()
        // MatrixCursor does not call onMove beyond the final row, so the last permission check
        // is controlled independently rather than relying on a provider cursor side effect.
        var calls = 0
        val source = AndroidDeviceContactSuggestionSource(RuntimeEnvironment.getApplication().contentResolver, { ++calls < 3 }, Dispatchers.Unconfined)
        assertEquals(DeviceContactSearchResult.PermissionRequired, source.search("Al", true)); assertTrue(cursor.closed)
    }

    @Test fun nullCursorProviderFailureSecurityFailureAndMalformedProjectionDoNotRetryOrExposeMessages() = runBlocking {
        for (mode in 0..3) {
            val f = Fixture()
            when (mode) {
                0 -> f.provider.cursor = null
                1 -> f.provider.querying = { throw IllegalStateException("Synthetic private contact must not escape") }
                2 -> f.provider.querying = { throw SecurityException("Synthetic private contact must not escape") }
                else -> f.provider.cursor = MatrixCursor(arrayOf("unexpected"))
            }
            val expected = if (mode == 2) DeviceContactSearchResult.PermissionRequired else DeviceContactSearchResult.Unavailable
            assertEquals(expected, f.source().search("Al", true)); assertEquals(1, f.provider.requests.size)
            if (mode == 3) assertTrue(f.provider.cursor!!.isClosed)
        }
    }

    @Test fun unsupportedCursorValueTypeCannotBeCoercedIntoAContactSuggestion() = runBlocking {
        val f = Fixture(); val cursor = TrackingCursor().apply { addRow(arrayOf<Any?>(123, "Synthetic")) }
        f.provider.cursor = cursor
        assertEquals(DeviceContactSearchResult.Unavailable, f.source().search("Al", true))
        assertTrue(cursor.closed); assertEquals(0, cursor.reads); assertEquals(1, f.provider.requests.size)
    }

    @Test fun cancellationSignalsTheOnlyInFlightQueryAndPropagates() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val cancelled = CountDownLatch(1)
        f.provider.querying = { signal ->
            requireNotNull(signal).setOnCancelListener { cancelled.countDown() }; entered.complete(Unit)
            check(cancelled.await(5, TimeUnit.SECONDS)); signal.throwIfCanceled()
        }
        val task = async { f.source(io = Dispatchers.IO).search("Al", true) }
        withTimeout(2_000) { entered.await() }; task.cancel()
        withTimeout(2_000) { task.join() }
        assertTrue(task.isCancelled); assertTrue(f.provider.requests.single().signal!!.isCanceled)
    }

    @Test fun cursorReturnedAfterCancellationIsClosedWithoutDeliveringItsRows() = runBlocking {
        val f = Fixture(); val cursor = f.rows("late" to "Late")
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        f.provider.querying = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val task = async { f.source(io = Dispatchers.IO).search("Al", true) }
        withTimeout(2_000) { entered.await() }; task.cancel(); release.countDown()
        withTimeout(2_000) { task.join() }
        assertTrue(task.isCancelled); assertTrue(cursor.closed); assertEquals(0, cursor.reads)
    }

    @Test fun cancellationDuringCursorConsumptionClosesWithoutReturningPartialRows() = runBlocking {
        val f = Fixture(); val cursor = f.rows("one" to "First", "two" to "Second")
        val task = async(start = CoroutineStart.LAZY) { f.source().search("Al", true) }
        cursor.moving = { if (it == 1) task.cancel() }
        task.start(); task.join()
        assertTrue(task.isCancelled); assertTrue(cursor.closed); assertEquals(2, cursor.reads)
    }

    @Test fun cooperativeSourceDeadlineCancelsProviderAndReturnsUnavailableWithoutRetry() = runBlocking {
        val f = Fixture(); val cancelled = CountDownLatch(1)
        f.provider.querying = { signal ->
            requireNotNull(signal).setOnCancelListener { cancelled.countDown() }
            check(cancelled.await(5, TimeUnit.SECONDS)); signal.throwIfCanceled()
        }
        assertEquals(DeviceContactSearchResult.Unavailable, f.source(timeout = 25, io = Dispatchers.IO).search("Al", true))
        assertTrue(f.provider.requests.single().signal!!.isCanceled)
    }

    @Test fun callerDeadlinePropagatesInsteadOfBecomingUnavailable() = runBlocking {
        val f = Fixture(); val cancelled = CountDownLatch(1)
        f.provider.querying = { signal ->
            requireNotNull(signal).setOnCancelListener { cancelled.countDown() }
            check(cancelled.await(5, TimeUnit.SECONDS)); signal.throwIfCanceled()
        }
        try {
            withTimeout(100) { f.source(io = Dispatchers.IO).search("Al", true) }
            fail("Caller deadline must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(1, f.provider.requests.size); assertTrue(f.provider.requests.single().signal!!.isCanceled)
    }

    private companion object {
        val Fields = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
    }
}
