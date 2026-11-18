package dev.swapnilraj.calendar

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.*
import dev.swapnilraj.calendar.ui.editorSeed
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real provider predicates, real writer/journal and file-backed review/native/receipt storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarEditReviewSqlTest {
    private val start = Instant.parse("2028-06-15T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 7, start, start.plusSeconds(3600))
    private val zone = ZoneId.of("Asia/Tokyo")
    private val clock = Clock.fixed(Instant.parse("2028-06-01T00:00:00.123456789Z"), ZoneOffset.UTC)

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val oldZone = TimeZone.getDefault(); val context = RuntimeEnvironment.getApplication()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = NativeSeriesTemporalJourneyProvider().apply { allowUpdates = true }
        provider.db.execSQL("ALTER TABLE events ADD COLUMN fixture_opaque TEXT")
        provider.db.insertOrThrow("events", null, ContentValues().apply {
            put("_id", 7); put("calendar_id", 2); put("dtstart", ref.instanceStart.toEpochMilli()); put("dtend", ref.instanceEnd.toEpochMilli())
            put("allDay", 0); putNull("eventTimezone"); putNull("eventEndTimezone"); put("title", "Original nullable-zone source")
            put("eventLocation", "Original room"); put("description", "Original notes"); put("availability", 1); put("accessLevel", 2)
            put("hasAlarm", 1); put("_sync_id", "event-cloud-A"); put("uid2445", "event-uid-A"); put("fixture_opaque", "untouched opaque metadata")
        })
        listOf(10, 30).forEach { minutes -> provider.db.insertOrThrow("reminders", null, ContentValues().apply { put("event_id", 7); put("minutes", minutes); put("method", 1) }) }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val name = "public-edit-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarWriter(context.contentResolver, reader, store, { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
        val f = Fixture(provider, store, scope, reader, writer)
        try { block(f) } finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); provider.db.close(); context.deleteDatabase(name); TimeZone.setDefault(oldZone)
        }
    }
    private inner class Fixture(val provider: NativeSeriesTemporalJourneyProvider, val store: CalendarLocalStore, val scope: CoroutineScope,
        val reader: CalendarReader, val writer: CalendarWriter) {
        var travelBeforeNativeCas = false
        val patches = mutableListOf<EventPatch>()
        val captured = object : CalendarMutations by writer {
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult {
                patches += patch; return writer.update(operationId, ref, token, patch)
            }
        }
        val storage = object : AtomicDraftStore by store {
            override suspend fun compareAndSet(id: String, expected: DraftRecord?, replacement: DraftRecord?): DraftChangeResult {
                if (travelBeforeNativeCas && id.startsWith("incoming-edit:")) TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
                return store.compareAndSet(id, expected, replacement)
            }
        }
        val services = CalendarEditReviewServices(storage, captured, CalendarOperationRetirement(store), ReaderIncomingCalendarSource(reader), {
            when (val calendars = reader.readCalendars()) {
                is CalendarReadState.Ready -> CalendarReadState.Ready(CalendarEditReviewEnvironment(calendars.value, zone, true))
                else -> CalendarReadState.Unavailable
            }
        }, MutableSharedFlow(), clock)
        suspend fun request(seed: CalendarDraftSeed = CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), "Proposed title only", null, null, emptyList(), null, null, null, null)): CalendarEditReviewRequest {
            val info = (reader.readEventNavigationInfo(7) as CalendarReadState.Ready).value!!
            return CalendarEditReviewRequest("d7c14384-0b18-48e8-9443-6bab048b3e19", ref, CalendarEntryBinding(7, info.calendar, info.providerUid), seed, zone)
        }
        suspend fun controller(launch: EditorDraftLaunch): EventEditorController {
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            return EventEditorController(editable.details.editorSeed(), editable, launch.draftId, captured, store, scope, clock,
                requireStoredDraft = true, expectedGeneration = launch.generation, requestWritePermission = { true }).also { controller ->
                withTimeout(15_000) { while (controller.state.loading) yield() }
            }
        }
        suspend fun save(controller: EventEditorController) { controller.save(); withTimeout(15_000) { while (controller.state.saving) yield() } }
    }

    @Test fun titleOnlyNullZoneUsesReviewedRendererAcrossAwaitAndKeepsProviderTimesZoneAndOpaqueFields() = runBlocking { fixture { f ->
        val request = f.request(); val review = f.services.open(request) as CalendarEditReviewResult.Review
        assertNull(review.snapshot.sourceZone); assertEquals("18:00", review.snapshot.original?.startTime)
        val before = f.provider.snapshot("events").single(); val reminders = f.provider.snapshot("reminders")
        f.travelBeforeNativeCas = true
        val editor = f.services.applySuggestions(review.snapshot) as CalendarEditReviewResult.Editor
        val raw = OrdinaryDraftDocument.decode(requireNotNull(f.store.load(request.nativeId)))
        assertEquals("Asia/Tokyo", raw.fields.zone); assertEquals("18:00", raw.fields.startTime)
        assertEquals("", org.json.JSONObject(raw.sourceBaseline).getString("zone"))
        assertEquals("Asia/Tokyo", org.json.JSONObject(raw.sourceBaseline).getString(OrdinaryEditDisplayProof.KEY))
        val controller = f.controller(editor.launch); assertFalse(controller.state.reviewRequired); f.save(controller)
        assertTrue(controller.state.result is MutationResult.LocalSaved)
        val patch = f.patches.single(); assertEquals(FieldChange.Keep, patch.time); assertEquals(FieldChange.Keep, patch.eventZone); assertEquals(FieldChange.Keep, patch.alertMinutes)
        val after = f.provider.snapshot("events").single()
        assertEquals("Proposed title only", after["title"]); assertEquals(before.filterKeys { it != "title" }, after.filterKeys { it != "title" })
        assertNull(after["eventTimezone"]); assertNull(after["eventEndTimezone"]); assertEquals(reminders, f.provider.snapshot("reminders"))
        assertEquals(1, f.provider.batchCount); assertNull(f.store.load(request.nativeId)); assertTrue(isCalendarEditHandoffReceipt(requireNotNull(f.store.load(request.reviewId))))
        assertEquals(CalendarEditReviewResult.Consumed, f.services.open(request)); assertEquals(1, f.provider.batchCount)
    } }

    @Test fun remoteSourceChangeBeforeApplyRetainsReviewAndCannotOverwriteOrAdoptNewToken() = runBlocking { fixture { f ->
        val request = f.request(); val review = f.services.open(request) as CalendarEditReviewResult.Review
        f.provider.db.update("events", ContentValues().apply { put("title", "Remote source title") }, "_id=7", null)
        assertEquals(CalendarEditReviewResult.Stale, f.services.applySuggestions(review.snapshot))
        assertEquals(review.snapshot.record, f.store.load(request.reviewId)); assertNull(f.store.load(request.nativeId))
        assertEquals("Remote source title", f.provider.value("title")); assertEquals(0, f.provider.batchCount)
    } }

    @Test fun directNativeProofShowsSameRendererExplicitZoneCannotAuthorMissingZoneSoFeatureBlocksIt() = runBlocking { fixture { f ->
        val proposed = CalendarDraftSeed(CalendarOccurrenceHints(start.plusSeconds(3600), start.plusSeconds(7200), false), null, null, null, emptyList(), null, zone, null, null)
        val request = f.request(proposed)
        val blocked = f.services.open(request) as CalendarEditReviewResult.Unsupported
        assertTrue(CalendarEditReviewIssue.ZONE_AUTHORING_REVIEW in blocked.reasons); assertNull(f.store.load(request.nativeId))
        // Isolate the underlying unchanged ordinary contract, without weakening its provider zone.
        val source = (f.writer.loadForEdit(ref) as EditReadResult.Ready).event; val original = source.details.editorSeed()
        val record = EventEditorController.initialRecord(original, source, "null-zone-explicit-proof", true, clock)
        val fields = EventEditorController.fieldsFor(original.copy(eventZone = zone))
        val native = OrdinaryDraftDocument.decode(record).copy(fields = CalendarEditSuggestions.overlay(fields, request),
            sourceBaseline = EventEditorController.sourceBaseline(original, source, zone.id)).record(clock.instant())
        assertEquals(DraftChangeResult.Applied, f.store.compareAndSet(native.id, null, native))
        val launch = OrdinaryDraftDocument.decode(native).launch; val controller = f.controller(launch); f.save(controller)
        assertEquals(FieldChange.Keep, f.patches.single().eventZone)
        assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE)), controller.state.result)
        assertEquals(0, f.provider.batchCount); assertNull(f.provider.value("eventTimezone"))
    } }
}
