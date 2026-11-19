package dev.swapnilraj.calendar.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import dev.swapnilraj.calendar.contacts.DeviceContactSearchResult
import dev.swapnilraj.calendar.contacts.DeviceContactSuggestion
import dev.swapnilraj.calendar.contacts.DeviceContactSuggestionSource
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** Optional, ephemeral search of this profile's local default email directory only.
 * No recipient validation, account/directory enumeration, contact writes or cached history. */
class AndroidDeviceContactSuggestionSource(
    private val resolver: ContentResolver,
    private val hasPermission: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 2_000,
) : DeviceContactSuggestionSource {
    init { require(timeoutMillis in 1..2_000) }

    override suspend fun search(query: String, enabled: Boolean): DeviceContactSearchResult {
        if (!enabled) return DeviceContactSearchResult.Disabled
        // Bound work before trimming, counting code points or UTF-8 encoding caller input.
        if (query.length > 128 || query.any { it.isISOControl() } || !validUtf16(query)) return DeviceContactSearchResult.InvalidQuery
        val filter = query.trim()
        val points = filter.codePointCount(0, filter.length)
        if (points < 2) return DeviceContactSearchResult.QueryRequired
        if (points > 64 || filter.toByteArray(Charsets.UTF_8).size > 256) return DeviceContactSearchResult.InvalidQuery
        if (!hasRead()) return DeviceContactSearchResult.PermissionRequired
        return withContext(io) {
            try {
                withTimeout(timeoutMillis) {
                    val uri = ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI.buildUpon()
                        .appendPath(filter)
                        .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY, ContactsContract.Directory.DEFAULT.toString())
                        .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, "51")
                        .build()
                    val cursor = queryCursor(uri)
                    if (cursor == null) {
                        ensureRead()
                        return@withTimeout DeviceContactSearchResult.Unavailable
                    }
                    cursor.use { read(it) }
                }
            } catch (_: TimeoutCancellationException) {
                // An outer caller cancellation/deadline must propagate, not become a search result.
                currentCoroutineContext().ensureActive()
                DeviceContactSearchResult.Unavailable
            } catch (_: SecurityException) {
                currentCoroutineContext().ensureActive()
                DeviceContactSearchResult.PermissionRequired
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                DeviceContactSearchResult.Unavailable
            }
        }
    }

    private fun hasRead() = hasPermission(Manifest.permission.READ_CONTACTS)
    private fun ensureRead() { if (!hasRead()) throw SecurityException() }

    private suspend fun queryCursor(uri: Uri): Cursor? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        try {
            ensureRead()
            val cursor = resolver.query(uri, Projection, null, null, null, signal)
            continuation.resume(cursor, onCancellation = { _, resource, _ -> resource?.close() })
        } catch (failure: Exception) { continuation.resumeWithException(failure) }
    }

    private suspend fun read(cursor: Cursor): DeviceContactSearchResult {
        val emailColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val items = linkedSetOf<DeviceContactSuggestion>()
        var rows = 0
        var bytes = 0
        var truncated = false
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive(); ensureRead()
            if (++rows > 50) { truncated = true; break }
            fun cell(column: Int): String? {
                if (cursor.isNull(column)) return null
                require(cursor.getType(column) == Cursor.FIELD_TYPE_STRING)
                return cursor.getString(column)
            }
            val email = cell(emailColumn)
            val name = cell(nameColumn)
            if ((email?.length ?: 0) > 256 || (name?.length ?: 0) > 256) return DeviceContactSearchResult.LimitExceeded
            bytes += (email?.toByteArray(Charsets.UTF_8)?.size ?: 0) + (name?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            if (bytes > 16 * 1024) return DeviceContactSearchResult.LimitExceeded
            // NULL has no email value to represent. Empty or invalid strings remain raw choices;
            // feature-specific rules decide what can be added/saved. Only exact tuples dedupe.
            if (email != null) items += DeviceContactSuggestion(email, name)
        }
        currentCoroutineContext().ensureActive(); ensureRead()
        return DeviceContactSearchResult.Ready(items.toList(), truncated)
    }

    private fun validUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index++]
            if (Character.isHighSurrogate(current)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(current)) return false
        }
        return true
    }

    private companion object {
        val Projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
    }
}
