package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.LiveCalendarDay
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Real native UI -> series adapters -> SQLite journal -> synthetic CalendarContract writes.
 * Instances below are an explicit synthetic projection, NOT a recurrence engine or Google proof.
 * The projection joins current event fields and omits cancelled rows after actual provider writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecurringProviderJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val selected = EventRef(CalendarId(2), 10, start, start.plusSeconds(3600), RecurrenceRef(10, start))
    private val exception = EventRef(CalendarId(2), 11, start.plusSeconds(86400), start.plusSeconds(90000), RecurrenceRef(10, start.plusSeconds(86400)))

    @Test fun nativeAllEditCommitsThroughRealSeriesWriterAndReopenedSqlDeduplicates() = fixture(selected) { f ->
        open("Synthetic template", "Edit event", "Entire series")
        compose.onNode(hasText("Synthetic template") and hasSetTextAction()).performTextReplacement("Updated synthetic template")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(15_000) { f.saved == 1 }
        assertEquals(1, f.permissionChecks)
        assertEquals(1, f.provider.rows.batchCount)
        assertEquals("Updated synthetic template", f.provider.rows.events[0]["title"])
        assertEquals(f.originalException, f.provider.rows.events[1])
        val fresh = runBlocking { f.reader.readDetails(selected) } as CalendarReadState.Ready
        assertEquals("Updated synthetic template", fresh.value.occurrence.title)
        assertEquals("Explicit synthetic override", (runBlocking { f.reader.readDetails(exception) } as CalendarReadState.Ready).value.occurrence.title)
        val operation = f.store.readableDatabase.query("mutation_journal", arrayOf("id", "phase"), null, null, null, null, null).use {
            assertTrue(it.moveToFirst()); assertEquals("VERIFIED", it.getString(1)); it.getString(0)
        }
        // A different SQLite helper and writer represent process restart, with READ permission only.
        CalendarLocalStore(f.context, f.database).use { reopened ->
            val readOnly = { permission: String -> permission == Manifest.permission.READ_CALENDAR }
            val source = CalendarSeriesReader(f.context.contentResolver, f.reader, reopened, readOnly)
            val writer = CalendarSeriesWriter(f.context.contentResolver, source, reopened, readOnly)
            val result = runBlocking { writer.recover(operation) }
            assertTrue(result is SeriesMutationResult.LocalSaved)
            assertEquals(SeriesIdentity(CalendarId(2), 10), (result as SeriesMutationResult.LocalSaved).identity)
            assertEquals(1, f.provider.rows.batchCount)
            assertTrue(runBlocking { reopened.list() }.isEmpty())
        }
    }

    @Test fun nativeThisCancellationRequiresConfirmationAndPreservesMasterAndOtherOccurrence() = fixture(exception) { f ->
        val masterBefore = f.provider.rows.events[0].toMap()
        open("Explicit synthetic override", "Delete event", "This occurrence")
        compose.onNode(hasText("Delete recurring event") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Delete recurring events?").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, f.provider.rows.batchCount)
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { f.deleted == 1 }
        assertEquals(1, f.permissionChecks)
        assertEquals(1, f.provider.rows.batchCount)
        assertEquals(masterBefore, f.provider.rows.events[0])
        val changed = f.provider.rows.events[1].toMap()
        assertEquals("2", changed["eventStatus"])
        assertEquals(f.originalException.filterKeys { it != "eventStatus" }, changed.filterKeys { it != "eventStatus" })
        assertTrue(runBlocking { f.reader.readDetails(exception) } is CalendarReadState.Unavailable)
        assertEquals("Synthetic template", (runBlocking { f.reader.readDetails(selected) } as CalendarReadState.Ready).value.occurrence.title)
        assertTrue(runBlocking { f.store.list() }.isEmpty())
    }

    private fun open(title: String, action: String, scope: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performClick()
        if (action == "Edit event") {
            val edit = hasContentDescription(action) and hasClickAction() and isEnabled()
            compose.waitUntil(15_000) { compose.onAllNodes(edit).fetchSemanticsNodes().size == 1 }
            compose.onNode(edit).performClick()
        } else {
            val more = hasContentDescription("More event actions") and hasClickAction() and isEnabled()
            compose.waitUntil(15_000) { compose.onAllNodes(more).fetchSemanticsNodes().size == 1 }
            compose.onNode(more).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(action).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(action).performClick()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(scope).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(scope).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(if (action == "Edit event") "Edit recurring event" else "Delete recurring event").fetchSemanticsNodes().isNotEmpty() }
    }

    private class Fixture(val context: Context, val provider: ProjectionProvider, val reader: CalendarReader,
        val store: CalendarLocalStore, val database: String, val originalException: Map<String, String?>) {
        @Volatile var permissionChecks = 0
        @Volatile var saved = 0
        @Volatile var deleted = 0
    }

    private fun fixture(initial: EventRef, block: (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = ProjectionProvider().apply {
            rows.allowWrites = true
            rows.calendar["allowedReminders"] = "0,1,2"; rows.calendar["maxReminders"] = "5"
            rows.events += event(10, "Synthetic template", start).apply { put("rrule", "FREQ=DAILY;COUNT=3"); put("duration", "PT1H"); put("dtend", null) }
            rows.events += event(11, "Explicit synthetic override", exception.instanceStart).apply {
                put("original_id", "10"); put("original_sync_id", "master-cloud-id"); put("originalInstanceTime", exception.instanceStart.toEpochMilli().toString()); put("originalAllDay", "0")
            }
            rows.instances += instance(selected); rows.instances += instance(exception)
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val database = "recurring-journey-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, database)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val grants = { _: String -> true }
        val source = CalendarSeriesReader(context.contentResolver, reader, store, grants)
        val writer = CalendarSeriesWriter(context.contentResolver, source, store, grants)
        val f = Fixture(context, provider, reader, store, database, provider.rows.events[1].toMap())
        try {
            compose.setContent { DaylightTheme {
                LiveCalendarDay(reader, CalendarSelection.ProviderVisible, initial.instanceStart.atZone(ZoneId.of("Europe/London")).plusMinutes(20),
                    true, 64f, {}, {}, drafts = store, seriesMutations = writer,
                    onLocalSaved = { f.saved++ }, onLocalDeleted = { f.deleted++ },
                    requestMeetingWritePermission = { f.permissionChecks++; true })
            } }
            block(f)
        } finally { scope.cancel(); runBlocking { scope.coroutineContext[Job]!!.join() }; store.close(); context.deleteDatabase(database) }
    }

    private fun event(id: Long, title: String, at: Instant) = mutableMapOf<String, String?>(
        "_id" to "$id", "calendar_id" to "2", "_sync_id" to if (id == 10L) "master-cloud-id" else "exception-cloud-id", "uid2445" to "synthetic-uid-$id",
        "dtstart" to at.toEpochMilli().toString(), "dtend" to at.plusSeconds(3600).toEpochMilli().toString(), "duration" to null,
        "allDay" to "0", "eventTimezone" to "Europe/London", "eventEndTimezone" to "Europe/London", "title" to title,
        "eventLocation" to "Synthetic room", "description" to "Synthetic notes", "rrule" to null, "rdate" to null, "exrule" to null, "exdate" to null,
        "original_id" to null, "original_sync_id" to null, "originalInstanceTime" to null, "originalAllDay" to null, "deleted" to "0", "eventStatus" to "1",
        "organizer" to "owner@example.invalid", "isOrganizer" to "1", "hasAttendeeData" to "0", "guestsCanSeeGuests" to "1", "availability" to "0", "accessLevel" to "2", "hasAlarm" to "0", "customAppPackage" to null,
        "future_private_column" to "Synthetic opaque field")
    private fun instance(ref: EventRef) = mutableMapOf<String, String?>("event_id" to "${ref.eventId}", "calendar_id" to "2",
        "begin" to ref.instanceStart.toEpochMilli().toString(), "end" to ref.instanceEnd.toEpochMilli().toString(), "displayColor" to "123", "selfAttendeeStatus" to "0")

    private class ProjectionProvider : ContentProvider() {
        val rows = CalendarSeriesReaderTest.Provider()
        override fun onCreate() = true
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?, signal: CancellationSignal?): Cursor? = query(uri, fields, selection, args, sort)
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            if (uri.pathSegments.first() != "instances") return rows.query(uri, fields, selection, args, sort)
            val from = uri.pathSegments[2].toLong(); val until = uri.pathSegments[3].toLong()
            return MatrixCursor(requireNotNull(fields)).apply {
                rows.instances.forEach { instance ->
                    val event = rows.events.single { it["_id"] == instance["event_id"] }
                    val detail = selection?.contains("event_id =") == true
                    if (event["deleted"] != "1" && event["eventStatus"] != "2" && instance["begin"]!!.toLong() < until && instance["end"]!!.toLong() > from &&
                        (if (detail) instance["event_id"] == args!![0] && instance["calendar_id"] == args[1] else instance["calendar_id"] in args.orEmpty())) {
                        val joined = event + instance
                        addRow(fields.map { joined[it] }.toTypedArray())
                    }
                }
            }
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = rows.applyBatch(operations)
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Only asserted batches are authorized")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Only asserted batches are authorized")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Only asserted batches are authorized")
    }
}
