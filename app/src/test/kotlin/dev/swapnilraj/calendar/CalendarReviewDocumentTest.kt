package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.*
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Private document evidence only: these tests have no provider or mutation port. */
@RunWith(RobolectricTestRunner::class)
class CalendarReviewDocumentTest {
    private val now = Instant.parse("2030-03-02T08:00:00.123456789Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val session = "dd1402dc-a358-4bbd-a656-49f154e31197"
    private val generation = "bd644434-671f-4ec2-b2e4-3b77db826c12"
    private val revision = "6aa7346f-222d-4659-a005-a75cd932e9e3"
    private val calendar = CalendarInfo(CalendarId(3), "Synthetic calendar", 123,
        CalendarAccount("owner@example.invalid", "com.synthetic"), "owner@example.invalid", ZoneOffset.UTC,
        700, false, true, CalendarCapabilities(readDetails = AccessDecision.ALLOWED, modifyEvents = AccessDecision.ALLOWED), "calendar-A")
    private val start = Instant.parse("2030-03-02T09:00:00Z")
    private val ref = EventRef(calendar.id, 7, start, start.plusSeconds(3600))
    private val seed = CalendarDraftSeed(CalendarOccurrenceHints(null, null, null), "Caller synthetic title", null,
        "Caller synthetic notes", listOf("raw guest"), "FREQ=INVALID", null, null, null)
    private fun editRequest() = CalendarEditReviewRequest(session, ref, CalendarEntryBinding(7, calendar.identity, "event-A"), seed, ZoneOffset.UTC)
    private fun native(edit: Boolean = true): OrdinaryDraftDocument {
        val draft = EventDraft(calendar.id, EventTime.Timed(ref.instanceStart, ref.instanceEnd), ZoneOffset.UTC,
            "Original synthetic title", description = "Original synthetic notes", targetIdentity = if (edit) null else calendar.identity)
        val source = if (edit) EditableEvent(EventDetails(EventOccurrence(ref, draft.time, draft.title, 123), calendar,
            description = draft.description, eventZone = ZoneOffset.UTC), EditToken("private-source-token"), EditableField.entries.toSet()) else null
        return OrdinaryDraftDocument.decode(EventEditorController.initialRecord(draft, source,
            if (edit) "incoming-edit:$session" else "incoming:$session", true, clock))
    }
    private fun editDocument(native: OrdinaryDraftDocument): CalendarEditReviewDocument = CalendarEditReviewDocument(editRequest(),
        generation, revision, native.sourceToken, native.sourceBaseline, native.fields, native.record(now))
    private fun changed(record: DraftRecord, change: (JSONObject) -> Unit): DraftRecord = record.copy(payload = JSONObject(record.payload).also(change).toString())

    @Test fun completionRoundTripRetainsIncompleteRawInputsAndPreparedProofExactly() {
        val request = CalendarSeedReviewRequest(session, seed, ZoneId.of("Asia/Tokyo"))
        val fields = CalendarSeedReviewFields("not a date", "", "25:", "", false, "raw zone", "FREQ=INVALID", "raw fold", "")
        val document = CalendarSeedCompletionDocument(request, generation, revision, fields, "raw prepared proof")
        val record = document.record(now)
        assertEquals(7, record.version); assertEquals(document, CalendarSeedCompletionDocument.decode(record))
        assertEquals("not a date", calendarSeedReviewPreview(record)?.rawDate)
        assertEquals(record, document.record(now))
    }

    @Test fun malformedCompletionNeverBecomesAFreshReviewOrConsumedReceipt() {
        val document = CalendarSeedCompletionDocument(CalendarSeedReviewRequest(session, seed, ZoneOffset.UTC), generation, revision,
            CalendarSeedReviewFields("", "", "", "", false, "", ""))
        val record = document.record(now)
        val invalid = listOf(record.copy(version = 8), record.copy(id = "incoming-review:${UUID.randomUUID()}"),
            changed(record) { it.put("phase", "handed-off") }, changed(record) { it.put("writeNow", true) },
            record.copy(payload = record.payload.replace("\"schema\":1", "\"schema\":1,\"schema\":1")))
        invalid.forEach { bad ->
            assertThrows(RuntimeException::class.java) { CalendarSeedCompletionDocument.decode(bad) }
            assertNull(calendarSeedReviewPreview(bad)); assertFalse(isCalendarSeedHandoffReceipt(bad))
        }
    }

    @Test fun seedHandoffRequiresExactNativeGenerationAndNeverContainsCallerPayload() {
        val native = native(edit = false); val record = native.record(now)
        val receipt = CalendarSeedHandoffReceipt(session, generation, requireNotNull(native.generation)).record(now)
        assertTrue(isCalendarSeedNativeHandoffVerified(record, receipt))
        assertFalse(isCalendarSeedNativeHandoffVerified(record, null))
        assertFalse(isCalendarSeedNativeHandoffVerified(native.copy(generation = UUID.randomUUID().toString()).record(now), receipt))
        assertFalse(isCalendarSeedNativeHandoffVerified(native().record(now), receipt))
        assertTrue(isCalendarSeedHandoffReceipt(receipt)); assertEquals(6, JSONObject(receipt.payload).length())
        listOf(seed.title!!, seed.description!!, seed.guests.single(), calendar.account.name).forEach { assertFalse(receipt.payload.contains(it)) }
    }

    @Test fun editReviewPreservesPinnedOriginalAndPreparedNativeBytesWithoutApplyingSuggestions() {
        val fresh = CalendarEditReviewDocument(editRequest(), generation, revision)
        assertEquals(fresh, CalendarEditReviewDocument.decode(fresh.record(now)))
        val native = native(); val prepared = editDocument(native); val record = prepared.record(now)
        val decoded = CalendarEditReviewDocument.decode(record)
        assertEquals(8, record.version); assertEquals(prepared, decoded)
        assertEquals(prepared.prepared, decoded.prepared)
        assertEquals("Original synthetic title", decoded.original?.title)
        assertEquals(seed.title, decoded.request.proposed.title)
        assertEquals(record, decoded.record(now))
    }

    @Test fun editPreparedProofRejectsForeignIdentitySourceAndFrozenOrLegacyCandidate() {
        val native = native(); val document = editDocument(native)
        val command = FrozenEditorSubmission.Update(native.operationId, ref, EditToken(native.sourceToken), EventPatch(title = FieldChange.Set("Frozen title")))
        val frozen = native.copy(frozen = command, submitted = true, operationKind = command.kind,
            operationPayload = EventEditorController.encodeRawFields(native.fields).toString()).record(now)
        val invalid = listOf(document.copy(sourceToken = "other source"),
            document.copy(request = editRequest().copy(binding = editRequest().binding.copy(calendar = calendar.identity.copy(providerId = "calendar-B")))),
            document.copy(prepared = native.record(now).copy(id = "incoming-edit:${UUID.randomUUID()}")),
            document.copy(prepared = native.record(now).copy(version = EventEditorController.PREVIOUS_VERSION)),
            document.copy(prepared = frozen))
        invalid.forEach { bad -> assertThrows(RuntimeException::class.java) { CalendarEditReviewDocument.decode(bad.record(now)) } }
        val record = document.record(now)
        assertThrows(RuntimeException::class.java) { CalendarEditReviewDocument.decode(changed(record) { it.put("phase", "review") }) }
        assertThrows(RuntimeException::class.java) { CalendarEditReviewDocument.decode(record.copy(version = 9)) }
    }

    @Test fun editHandoffBindsExactSourceHashesAndRetainsOnlyConsumedEvidence() {
        val native = native(); val record = native.record(now)
        val receipt = CalendarEditHandoffReceipt(session, generation, requireNotNull(native.generation), ref, editRequest().binding,
            ZoneOffset.UTC, CalendarEditHandoffReceipt.hash(native.sourceBaseline), CalendarEditHandoffReceipt.hash(native.sourceToken))
        val consumed = receipt.record(now)
        assertEquals(receipt, CalendarEditHandoffReceipt.decode(consumed)); assertTrue(receipt.matches(record))
        assertTrue(isCalendarEditHandoffReceipt(consumed)); assertNull(calendarEditReviewPreview(consumed))
        assertFalse(receipt.matches(native.copy(sourceToken = "changed source").record(now)))
        assertFalse(receipt.matches(native.copy(generation = UUID.randomUUID().toString()).record(now)))
        assertFalse(receipt.copy(selected = ref.copy(eventId = 99)).matches(record))
        assertFalse(isCalendarEditHandoffReceipt(changed(consumed) { it.put("tokenHash", "not a hash") }))
        listOf(native.fields.title, native.fields.description, native.sourceToken, seed.title!!, seed.description!!).forEach {
            assertFalse(consumed.payload.contains(it))
        }
    }
}
