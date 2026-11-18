package dev.swapnilraj.calendar.events

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import dev.swapnilraj.calendar.NativeSeriesTemporalJourneyProvider
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real SQLite provider assertions and the real ordinary writer; only request capture is decorated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NullZoneTravelEditTest {
    private val start = Instant.parse("2028-06-15T09:00:00Z")
    private val ref = EventRef(CalendarId(2), 7, start, start.plusSeconds(3600))
    private val clock = Clock.fixed(Instant.parse("2028-06-01T00:00:00.123456789Z"), ZoneOffset.UTC)
    internal suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val oldZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            val provider = NativeSeriesTemporalJourneyProvider().apply { allowUpdates = true }
            provider.db.execSQL("ALTER TABLE events ADD COLUMN fixture_opaque TEXT")
            provider.db.insertOrThrow("events", null, ContentValues().apply {
                put("_id", 7); put("calendar_id", 2); put("dtstart", ref.instanceStart.toEpochMilli()); put("dtend", ref.instanceEnd.toEpochMilli())
                put("allDay", 0); putNull("eventTimezone"); putNull("eventEndTimezone"); put("title", "Synthetic nullable-zone event")
                put("eventLocation", "Synthetic room"); put("description", "Synthetic opaque notes"); put("availability", 1); put("accessLevel", 2)
                put("hasAlarm", 1); put("_sync_id", "fixture-event-A"); put("uid2445", "fixture-stable-event-uid"); put("fixture_opaque", "opaque sentinel")
            })
            listOf(10,30).forEach { minutes -> provider.db.insertOrThrow("reminders", null, ContentValues().apply {
                put("event_id", 7); put("minutes", minutes); put("method", CalendarContract.Reminders.METHOD_ALERT)
            }) }
            ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
            val name = "null-zone-${UUID.randomUUID()}.db"
            val store = CalendarLocalStore(context, name)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
            val writer = CalendarWriter(context.contentResolver, reader, store, { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
            val fixture = Fixture(provider, store, scope, writer)
            try { block(fixture) }
            finally {
                scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); provider.db.close()
                context.deleteDatabase(name)
            }
        } finally { TimeZone.setDefault(oldZone) }
    }
    internal inner class Fixture(val provider: NativeSeriesTemporalJourneyProvider, val store: CalendarLocalStore, val scope: CoroutineScope,
        val writer: CalendarWriter) {
        val patches = mutableListOf<EventPatch>()
        val captured = object : CalendarMutations by writer {
            override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult {
                patches += patch; return writer.update(operationId, ref, token, patch)
            }
        }
        suspend fun source(): EditableEvent = (writer.loadForEdit(ref) as EditReadResult.Ready).event
        fun seed(source: EditableEvent) = source.details.let { EventDraft(it.occurrence.ref.calendarId, it.occurrence.time, it.eventZone,
            it.occurrence.title, it.location, it.description, it.reminders.filter { r -> r.method == CalendarContract.Reminders.METHOD_ALERT }.map { r -> r.minutesBefore }, it.availability, it.privacy) }
        suspend fun prepare(id: String = "draft"): Pair<EditableEvent, EditorDraftLaunch> {
            val source = source(); assertNull(source.details.eventZone)
            return source to (prepareEditDraft(seed(source), source, id, store, clock) as DraftPreparationResult.Ready).launch
        }
        suspend fun open(launch: EditorDraftLaunch, source: EditableEvent? = null): EventEditorController {
            val controller = EventEditorController(if (source == null) launch.seed(LocalDate.parse("2040-01-01"), ZoneOffset.UTC) else seed(source), source,
                launch.draftId, captured, store, scope, clock, recoveryOnly = launch.needsRecovery, recoveryRef = launch.ref,
                requireStoredDraft = true, expectedGeneration = launch.generation, requestWritePermission = { true })
            withTimeout(15_000) { while (controller.state.loading) yield() }
            return controller
        }
        suspend fun save(controller: EventEditorController) {
            controller.save(); withTimeout(15_000) { while (controller.state.saving) yield() }
        }
    }
    @Test fun titleOnlyAfterSummerTravelKeepsBothProviderTimesAndNullZone() = runBlocking { fixture { f ->
        val (original, launch) = f.prepare()
        val first = f.open(launch, original); first.change { it.copy(title = "Retained title after travel") }; assertTrue(first.flushDraft())
        val before = f.provider.snapshot("events").single(); val reminders = f.provider.snapshot("reminders")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val fresh = f.source(); assertEquals(original.token, fresh.token); assertNull(fresh.details.eventZone)
        val restored = f.open(launch, fresh); assertFalse(restored.state.reviewRequired); assertEquals("09:00", restored.state.fields.startTime)
        f.save(restored)
        assertTrue(restored.state.result is MutationResult.LocalSaved)
        val patch = f.patches.single(); assertEquals(FieldChange.Keep, patch.time); assertEquals(FieldChange.Keep, patch.eventZone)
        val after = f.provider.snapshot("events").single()
        assertEquals("Retained title after travel", after["title"])
        assertEquals(before.filterKeys { it != "title" }, after.filterKeys { it != "title" })
        assertNull(after["eventTimezone"]); assertNull(after["eventEndTimezone"])
        assertEquals("opaque sentinel", after["fixture_opaque"]); assertEquals(reminders, f.provider.snapshot("reminders"))
        assertEquals(1, f.provider.batchCount); assertTrue(f.store.list().isEmpty())
    } }
    @Test fun alertOnlyAfterTravelKeepsTimeZoneInstantsAndOpaqueProviderMetadata() = runBlocking { fixture { f ->
        val (original, launch) = f.prepare(); val first = f.open(launch, original)
        first.change { it.copy(alerts = "10,30,60") }; assertTrue(first.flushDraft())
        val before = f.provider.snapshot("events").single()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val restored = f.open(launch, f.source()); assertFalse(restored.state.reviewRequired); f.save(restored)
        assertTrue(restored.state.result is MutationResult.LocalSaved)
        assertEquals(FieldChange.Keep, f.patches.single().time); assertEquals(FieldChange.Keep, f.patches.single().eventZone)
        assertEquals(before, f.provider.snapshot("events").single())
        assertEquals(listOf("10", "30", "60"), f.provider.snapshot("reminders").map { it["minutes"] })
    } }
    @Test fun missingOrMalformedOriginalDisplayProofRetainsRawDraftForReviewWithoutWrite() = runBlocking { fixture { f ->
        for (value in listOf<Any?>(null, 17, "Not/AZone")) {
            val id = "invalid-${UUID.randomUUID()}"; val (source, launch) = f.prepare(id)
            val first = f.open(launch, source); first.change { it.copy(title = "Keep invalid-proof raw title") }; first.flushDraft()
            val record = requireNotNull(f.store.load(id)); val raw = JSONObject(record.payload)
            val baseline = JSONObject(raw.getString("sourceBaseline"))
            if (value == null) baseline.remove("originalDisplayZone") else baseline.put("originalDisplayZone", value)
            raw.put("sourceBaseline", baseline.toString()); val invalid = record.copy(payload = raw.toString())
            assertEquals(DraftChangeResult.Applied, f.store.compareAndSet(id, record, invalid))
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val restored = f.open(launch, f.source())
            assertTrue(restored.state.reviewRequired); assertEquals("Keep invalid-proof raw title", restored.state.fields.title)
            f.save(restored); restored.requestDelete(); restored.confirmDelete(); assertFalse(restored.state.confirmDelete)
            assertFalse(restored.flushDraft()); assertEquals(invalid, f.store.load(id)); assertEquals(0, f.provider.batchCount)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
    } }
    @Test fun realSourceZoneChangeCannotAdoptNewTokenOrOverwriteRemoteZone() = runBlocking { fixture { f ->
        val (source, launch) = f.prepare(); val first = f.open(launch, source)
        first.change { it.copy(title = "Retain original draft") }; first.flushDraft(); val record = f.store.load(launch.draftId)
        f.provider.db.update("events", ContentValues().apply { put("eventTimezone", "Europe/London") }, "_id=7", null)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val fresh = f.source(); assertNotEquals(source.token, fresh.token)
        val restored = f.open(launch, fresh); assertTrue(restored.state.reviewRequired); f.save(restored)
        assertEquals(record, f.store.load(launch.draftId)); assertEquals(0, f.provider.batchCount)
        assertEquals("Europe/London", f.provider.value("eventTimezone")); assertEquals("Retain original draft", restored.state.fields.title)
    } }
    @Test fun explicitSourceZoneNeedsNoDisplayProofAndKeepsItsRawZoneAfterTravel() = runBlocking { fixture { f ->
        f.provider.db.update("events", ContentValues().apply { put("eventTimezone", "Europe/London"); put("eventEndTimezone", "Europe/London") }, "_id=7", null)
        val source = f.source(); val launch = (prepareEditDraft(f.seed(source), source, "known-zone", f.store, clock) as DraftPreparationResult.Ready).launch
        val initial = f.open(launch, source); initial.change { it.copy(title = "Known source zone remains") }; initial.flushDraft()
        val record = requireNotNull(f.store.load(launch.draftId))
        assertFalse(JSONObject(JSONObject(record.payload).getString("sourceBaseline")).has("originalDisplayZone"))
        val before = f.provider.snapshot("events").single()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val restored = f.open(launch, f.source()); assertFalse(restored.state.reviewRequired)
        f.save(restored); assertTrue(restored.state.result is MutationResult.LocalSaved)
        assertEquals(FieldChange.Keep, f.patches.single().time); assertEquals(FieldChange.Keep, f.patches.single().eventZone)
        val after = f.provider.snapshot("events").single()
        assertEquals(before.filterKeys { it != "title" }, after.filterKeys { it != "title" })
    } }
    @Test fun oldFrozenRequestRecoveryKeepsExactBytesWithoutDisplayProofOrLiveSource() = runBlocking { fixture { f ->
        val (source, launch) = f.prepare(); val record = requireNotNull(f.store.load(launch.draftId)); val raw = JSONObject(record.payload)
        val operation = raw.getString("operationId")
        val frozen = FrozenEditorSubmission.Update(operation, ref, source.token, EventPatch(title = FieldChange.Set("Original frozen title")))
        raw.put("title", "Original frozen title"); raw.put("submitted", true); raw.put("operationKind", "update")
        raw.put("operationPayload", "exact legacy operation payload")
        raw.put("sourceBaseline", JSONObject(raw.getString("sourceBaseline")).apply { remove("originalDisplayZone") }.toString())
        raw.put("submission", frozen.encode())
        val legacy = record.copy(payload = raw.toString()); assertEquals(DraftChangeResult.Applied, f.store.compareAndSet(record.id, record, legacy))
        val recovery = requireNotNull(DraftBrowserCodec.read(legacy).launch); assertTrue(recovery.needsRecovery)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val restored = f.open(recovery); restored.recover()
        withTimeout(15_000) { while (restored.state.saving) yield() }
        assertTrue(restored.state.recoveryRequired); restored.save(); restored.retryOriginal(); assertTrue(restored.flushDraft())
        assertEquals(legacy, f.store.load(record.id)); assertEquals(frozen.encode().toString(), JSONObject(requireNotNull(f.store.load(record.id)).payload).getJSONObject("submission").toString())
        assertTrue(f.patches.isEmpty()); assertEquals(0, f.provider.batchCount)
    } }
}
