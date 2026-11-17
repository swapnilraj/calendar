package dev.swapnilraj.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.data.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CalendarRepositoryTest {
    @Test fun readsExpandedInstancesAndRefreshesWithoutLeakingObserver() = runBlocking {
        val date=LocalDate.of(2026,11,17)
        val provider=object : ContentProvider() {
            var title="Fixture event"
            var queried: Uri?=null
            var selectionUsed: String?=null
            override fun onCreate()=true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                queried=uri; selectionUsed=selection
                return MatrixCursor(projection!!).apply {
                    addRow(arrayOf<Any>(7L,Instant.parse("2026-11-17T09:00:00Z").toEpochMilli(),Instant.parse("2026-11-17T10:00:00Z").toEpochMilli(),title,0xff447766.toInt(),0,CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED))
                    addRow(arrayOf<Any>(8L,Instant.parse("2026-11-17T00:00:00Z").toEpochMilli(),Instant.parse("2026-11-18T00:00:00Z").toEpochMilli(),"All day",0xff447766.toInt(),1,0))
                }
            }
            override fun getType(uri: Uri): String?=null
            override fun insert(uri: Uri, values: ContentValues?): Uri?=null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?)=0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?)=0
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
        val resolver=RuntimeEnvironment.getApplication().contentResolver
        val states=Channel<CalendarState>(Channel.UNLIMITED)
        val job=launch { CalendarRepository(resolver).events(date,ZoneId.of("America/Los_Angeles")).collect { states.send(it) } }
        try {
            val ready=withTimeout(10_000) { states.receive() } as CalendarState.Ready
            assertEquals(2,ready.events.size)
            assertTrue(ready.events.first().declined)
            assertTrue(ready.events.last().allDay)
            assertEquals("visible = ?",provider.selectionUsed)
            assertTrue(provider.queried.toString().contains("instances/when/"))
            provider.title="Updated fixture"
            resolver.notifyChange(CalendarContract.Events.CONTENT_URI,null)
            val changed=withTimeout(10_000) { states.receive() } as CalendarState.Ready
            assertEquals("Updated fixture",changed.events.first().title)
        } finally { job.cancelAndJoin() }
        assertTrue(org.robolectric.Shadows.shadowOf(resolver).getContentObservers(CalendarContract.CONTENT_URI).isEmpty())
    }
}
