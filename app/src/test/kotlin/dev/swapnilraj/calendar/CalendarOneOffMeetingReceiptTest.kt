package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.security.MessageDigest
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarOneOffMeetingReceiptTest {
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A")
    private val draft = EventDraft(CalendarId(2), EventTime.Timed(start, start.plusSeconds(3600)), ZoneId.of("Europe/London"),
        "Synthetic meeting", "Room", "Notes", listOf(10, 30), 1, 2, identity)
    private val guests = listOf(MeetingGuestInput("required@example.invalid", MeetingGuestType.REQUIRED, "Required guest"),
        MeetingGuestInput("optional@example.invalid", MeetingGuestType.OPTIONAL))
    private data class Fixture(val p: CalendarRecurringCreateTest.Provider, val writer: CalendarMeetingWriter, val reader: CalendarReader,
        val store: CalendarLocalStore, val permissions: MutableSet<String>)
    private suspend fun fixture(budgets: ProviderReadBudgets = ProviderReadBudgets(), block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val p = CalendarRecurringCreateTest.Provider().apply { nativeMeetingMode = true
            db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'") }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = CalendarLocalStore(context, "oneoff-receipt-${UUID.randomUUID()}.db", readBudgets = budgets)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarMeetingWriter(context.contentResolver, reader, store, permissions::contains, readBudgets = budgets)
        try { block(Fixture(p, writer, reader, store, permissions)) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); p.db.close(); context.deleteDatabase(store.databaseName) }
    }
    private suspend fun Fixture.token() = (writer.loadForCreate(identity) as MeetingReadResult.Ready).value.token
    private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
    private fun Fixture.receipt(id: String) = JSONObject(store.operation(id)!!.resultRef!!)
    private fun Fixture.purgeSources() { store.writableDatabase.delete("edit_snapshots", null, null) }

    @Test fun newReceiptsFreezeSourceNativePlanAndUidWithoutChangingCommandFingerprint() = runBlocking { fixture { f ->
        val token = f.token()
        val saved = f.writer.create("new", token, draft, guests) as MeetingMutationResult.LocalSaved
        val op = f.store.operation("new")!!; val receipt = f.receipt("new")
        assertEquals("MEETING_V1_CREATE", op.kind); assertEquals(2, receipt.getInt("version"))
        assertEquals(MeetingOneOffCreationReceipt.Feature, receipt.getString("feature"))
        assertEquals(f.store.edit(token.value), receipt.getJSONObject("source").toString())
        assertEquals(CalendarMeetingCreateWriter.fingerprint(token, draft, guests, 1024 * 1024).second, op.signature)
        assertEquals(CalendarMeetingCreateWriter.oneOffCommand(token, draft, guests, 1024 * 1024).toString(), receipt.getJSONObject("command").toString())
        assertEquals(f.p.value("uid2445"), receipt.getJSONObject("plan").getJSONObject("event").getString("uid2445"))
        assertTrue(f.p.value("uid2445")!!.endsWith("@daylight.invalid")); assertNull(saved.selected.recurrence)
        assertEquals(RecurrenceScope.THIS, saved.scope); assertEquals(1, f.p.batchCount)
    } }

    @Test fun knownCommittedAndVerifiedReceiptsRecoverAfterSourcePurgeReopenAndReadOnlyGrant() = runBlocking {
        for (alreadyVerified in listOf(false, true)) fixture { f ->
            val token = f.token(); f.p.failReadsAfterCommit = !alreadyVerified
            val outcome = f.writer.create("recover", token, draft, guests)
            if (alreadyVerified) assertTrue(outcome is MeetingMutationResult.LocalSaved) else assertEquals(common(MutationResult.Uncertain("recover", 1)), outcome)
            f.p.failReadsAfterCommit = false; f.purgeSources(); f.store.close(); f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val recovered = f.writer.recover("recover") as MeetingMutationResult.LocalSaved
            assertEquals(start, recovered.selected.instanceStart); assertEquals(start.plusSeconds(3600), recovered.selected.instanceEnd)
            assertEquals("VERIFIED", f.store.operation("recover")!!.phase); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun failedReceiptRetriesWinningFrozenPlanAndUidAfterSnapshotPurge() = runBlocking { fixture { f ->
        val token = f.token(); f.p.failReminder = true
        assertEquals(common(MutationResult.Unavailable), f.writer.create("retry", token, draft, guests))
        val failed = f.receipt("retry"); val uid = f.p.insertedEventValues.single().getAsString("uid2445")
        f.purgeSources(); f.store.close()
        assertEquals(common(MutationResult.SafeToRetry("retry")), f.writer.recover("retry"))
        assertEquals(common(MutationResult.Conflict(null)), f.writer.create("retry", token, draft.copy(title = "Changed"), guests))
        f.p.failReminder = false
        assertTrue(f.writer.create("retry", token, draft, guests) is MeetingMutationResult.LocalSaved)
        assertEquals(uid, f.p.value("uid2445")); assertEquals(failed.getJSONObject("plan").toString(), f.receipt("retry").getJSONObject("plan").toString())
        assertEquals(2, f.p.batchCount); assertEquals(1, f.p.count("events"))
    } }

    @Test fun preparedReceiptRetriesWithoutMutableSnapshotsOrChangingItsPlan() = runBlocking { fixture { f ->
        val token = f.token(); f.p.failReminder = true
        f.writer.create("prepared", token, draft, guests)
        f.store.writableDatabase.execSQL("UPDATE mutation_journal SET phase='PREPARED' WHERE id='prepared'")
        val frozen = f.receipt("prepared").getJSONObject("plan").toString()
        f.purgeSources(); f.p.failReminder = false
        assertEquals(common(MutationResult.SafeToRetry("prepared")), f.writer.recover("prepared"))
        assertTrue(f.writer.create("prepared", token, draft, guests) is MeetingMutationResult.LocalSaved)
        assertEquals(frozen, f.receipt("prepared").getJSONObject("plan").toString()); assertEquals(2, f.p.batchCount)
    } }

    @Test fun concurrentWritersAdoptOnePreparedUidAndCreateOneNativeMeeting() = runBlocking { fixture { f ->
        val token = f.token()
        val other = CalendarMeetingWriter(RuntimeEnvironment.getApplication().contentResolver, f.reader, f.store, f.permissions::contains)
        val outcomes = coroutineScope {
            listOf(async(Dispatchers.Default) { f.writer.create("race", token, draft, guests) },
                async(Dispatchers.Default) { other.create("race", token, draft, guests) }).awaitAll()
        }
        assertTrue(outcomes.any { it is MeetingMutationResult.LocalSaved })
        assertTrue(outcomes.all { it is MeetingMutationResult.LocalSaved || it is MeetingMutationResult.Common && it.outcome is MutationResult.Uncertain })
        assertEquals(1, f.p.batchCount); assertEquals(1, f.p.count("events")); assertEquals(3, f.p.count("attendees"))
        assertEquals(f.p.value("uid2445"), f.receipt("race").getJSONObject("plan").getJSONObject("event").getString("uid2445"))
    } }

    @Test fun lostOrMalformedParentAckNeverSearchesOrReplaysAfterSourcePurge() = runBlocking {
        for (malformed in listOf(false, true)) fixture { f ->
            val token = f.token(); f.p.lostAck = !malformed; f.p.malformedAck = malformed
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.create("lost", token, draft, guests))
            assertEquals("ATTEMPTED", f.store.operation("lost")!!.phase); f.purgeSources(); f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.recover("lost"))
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.create("lost", token, draft, guests))
            assertTrue(f.p.queries.isEmpty()); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun malformedChildAckStoresKnownParentAndPermanentlyRefusesReconstruction() = runBlocking { fixture { f ->
        val token = f.token(); f.p.malformedAttendeeAck = true
        assertEquals(common(MutationResult.Uncertain("partial", 1)), f.writer.create("partial", token, draft, guests))
        val committed = f.store.operation("partial")!!; assertEquals("COMMITTED", committed.phase)
        assertFalse(f.receipt("partial").getBoolean("ackComplete")); assertFalse(f.receipt("partial").has("attendees"))
        f.purgeSources(); f.store.close(); f.p.queries.clear(); f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertEquals(common(MutationResult.Uncertain("partial", 1)), f.writer.recover("partial"))
        assertTrue(f.p.queries.isEmpty()); assertEquals(committed, f.store.operation("partial")); assertEquals(1, f.p.batchCount)
    } }

    @Test fun generatedUidCannotBeLearnedFromAReplacementOrDowngradedBeforePrivateRead() = runBlocking {
        for (replacement in listOf<String?>(null, "", "foreign")) fixture { f ->
            assertTrue(f.writer.create("uid", f.token(), draft, guests) is MeetingMutationResult.LocalSaved)
            f.p.db.execSQL("UPDATE events SET uid2445=?", arrayOf(replacement)); f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("uid", 1)), f.writer.recover("uid"))
            assertTrue(f.p.queries.none { it.path in setOf("attendees", "reminders") }); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun nativeReminderCollectionStaysIdentityOnlyAndPayloadUsesGuardedKnownItems() = runBlocking { fixture { f ->
        f.writer.create("queries", f.token(), draft, guests); f.p.queries.clear()
        assertTrue(f.writer.recover("queries") is MeetingMutationResult.LocalSaved)
        val collection = f.p.queries.filter { it.path == "reminders" && it.itemId == null }
        assertEquals(2, collection.size); assertTrue(collection.all { it.fields == listOf("_id", "event_id") && it.selection == "event_id = ?" })
        val items = f.p.queries.filter { it.path == "reminders" && it.itemId != null }
        assertEquals(2, items.size); assertTrue(items.all { it.selection?.contains("uid2445 = ?") == true && it.fields == listOf("_id", "event_id", "minutes", "method") })
    } }

    @Test fun replacementBeforeEitherPrivatePayloadAndMembershipRacesStayUncertain() = runBlocking {
        for (child in listOf("attendees", "reminders")) fixture { f ->
            val token = f.token(); f.p.failReadsAfterCommit = true; f.writer.create("private", token, draft, guests)
            val committed = f.store.operation("private")!!; f.p.failReadsAfterCommit = false
            f.p.beforeQuery = { path -> if (path == child && (path != "reminders" || f.p.queries.last().itemId != null)) {
                f.p.beforeQuery = null; f.p.db.execSQL("UPDATE events SET uid2445='foreign-parent'")
            } }
            assertEquals(common(MutationResult.Uncertain("private", 1)), f.writer.recover("private"))
            assertEquals(committed, f.store.operation("private")); assertEquals(1, f.p.batchCount)
        }
        fixture { f ->
            val token = f.token(); f.p.failReadsAfterCommit = true; f.writer.create("membership", token, draft, guests)
            val committed = f.store.operation("membership")!!; f.p.failReadsAfterCommit = false
            var scans = 0
            f.p.beforeQuery = { path -> if (path == "reminders" && f.p.queries.last().itemId == null && ++scans == 2) {
                f.p.beforeQuery = null; f.p.db.execSQL("INSERT INTO reminders(event_id,minutes,method) VALUES (1,45,1)")
            } }
            assertEquals(common(MutationResult.Uncertain("membership", 1)), f.writer.recover("membership"))
            assertEquals(committed, f.store.operation("membership")); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun namespaceAndNativeBoundsMustRemainExactOnKnownReceiptRecovery() = runBlocking { fixture { f ->
        val token = f.token(); f.p.delayedInstances = true
        assertEquals(common(MutationResult.Uncertain("bounds", 1)), f.writer.create("bounds", token, draft, guests))
        f.purgeSources(); f.p.delayedInstances = false; f.p.endDelta = 1000
        assertEquals(common(MutationResult.Uncertain("bounds", 1)), f.writer.recover("bounds"))
        f.p.endDelta = 0; f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertTrue(f.writer.recover("bounds") is MeetingMutationResult.LocalSaved)
        f.p.setCalendar("_sync_id", "foreign-calendar"); f.p.queries.clear()
        assertEquals(common(MutationResult.Uncertain("bounds", 1)), f.writer.recover("bounds"))
        assertTrue(f.p.queries.all { it.path == "calendars" }); assertEquals(1, f.p.batchCount)
    } }

    @Test fun malformedOrUnknownExistingReceiptNeverSeedsAReplacementOperation() = runBlocking {
        for (version in listOf(2, 99)) fixture { f ->
            val token = f.token(); val (kind, signature) = CalendarMeetingCreateWriter.fingerprint(token, draft, guests, 1024 * 1024)
            f.store.prepare("broken", signature, kind, JSONObject().put("feature", MeetingOneOffCreationReceipt.Feature).put("version", version).toString())
            val original = f.store.operation("broken")!!; f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("broken", null)), f.writer.create("broken", token, draft, guests))
            assertEquals(original, f.store.operation("broken")); assertTrue(f.p.queries.isEmpty()); assertEquals(0, f.p.batchCount)
        }
    }

    @Test fun retirementStillUsesOriginalTypedFingerprintWithoutSourceOrPermissions() = runBlocking { fixture { f ->
        val token = f.token(); val retirement = CalendarOperationRetirement(f.store)
        val request = RetirableOperation.MeetingCreate("retired", token, draft, guests)
        assertEquals(OperationRetirementResult.Retired("retired"), retirement.retire(request))
        f.purgeSources(); f.permissions.clear(); f.p.queries.clear()
        assertEquals(common(MutationResult.Retired("retired")), f.writer.create("retired", token, draft, guests))
        assertTrue(f.p.queries.isEmpty()); assertEquals(0, f.p.batchCount)
    } }

    @Test fun receiptBudgetFailsBeforePrepareAndDeniedGrantNeverFreezesAnOperation() = runBlocking {
        fixture(ProviderReadBudgets(maxCanonicalBytes = 64 * 1024)) { f ->
            assertTrue(f.writer.create("budget", f.token(), draft, guests) is MeetingMutationResult.NeedsReview)
            assertNull(f.store.operationHeader("budget")); assertEquals(0, f.p.batchCount)
        }
        fixture { f ->
            val token = f.token(); f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(common(MutationResult.PermissionRequired), f.writer.create("permission", token, draft, guests))
            assertNull(f.store.operationHeader("permission")); assertEquals(0, f.p.batchCount)
        }
    }

    @Test fun legacyPreparedReceiptKeepsOriginalDispatchAndReadProtocol() = runBlocking { fixture { f ->
        val token = f.token(); f.store.prepareLegacyMeetingCreate("legacy", token, draft, guests)
        val original = f.store.operation("legacy")!!.resultRef
        f.p.failReminder = true
        assertEquals(common(MutationResult.Unavailable), f.writer.create("legacy", token, draft, guests))
        assertEquals(original, f.store.operation("legacy")!!.resultRef)
        assertFalse(f.p.insertedEventValues.single().containsKey("uid2445"))
        f.p.failReminder = false
        assertTrue(f.writer.create("legacy", token, draft, guests) is MeetingMutationResult.LocalSaved)
        assertEquals(1, f.receipt("legacy").getInt("version")); assertFalse(f.receipt("legacy").has("source")); assertFalse(f.receipt("legacy").has("plan"))
        f.purgeSources(); f.p.queries.clear()
        assertEquals(common(MutationResult.Uncertain("legacy", null)), f.writer.recover("legacy"))
        assertTrue(f.p.queries.isEmpty()); assertEquals(2, f.p.batchCount)
    } }

    @Test fun commandAndV1SourceTokenGoldenEncodingStayUnchanged() = runBlocking { fixture { f ->
        val issued = f.token(); val raw = f.store.edit(issued.value)!!
        val base = MeetingRecurringCreationSource.decode(raw, issued, 1024 * 1024).baseRaw
        fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val legacy = EditToken("meeting-create-v1:" + sha("MEETING_CREATE_SOURCE_V1" + base))
        f.store.saveEdit(legacy.value, base)
        val golden = JSONObject("""{"token":"${legacy.value}","draft":{"calendar":2,"event":0,"start":"2026-11-18T09:00:00Z","end":"2026-11-18T10:00:00Z","allDay":false,"zone":"Europe/London","title":"Synthetic meeting","location":"Room","description":"Notes","alerts":[10,30],"availability":1,"privacy":2,"targetIdentity":{"calendar":2,"name":"owner@example.invalid","type":"com.google","owner":"owner@example.invalid","providerId":"calendar-A"}},"guests":[{"email":"required@example.invalid","type":"REQUIRED","name":"Required guest"},{"email":"optional@example.invalid","type":"OPTIONAL","name":null}]}""").toString()
        assertEquals(golden, CalendarMeetingCreateWriter.oneOffCommand(legacy, draft, guests, 1024 * 1024).toString())
        assertEquals("MEETING_V1_CREATE" to sha("MEETING_CREATE_COMMAND_V1" + golden), CalendarMeetingCreateWriter.fingerprint(legacy, draft, guests, 1024 * 1024))
        assertTrue(f.writer.create("old-token-new-receipt", legacy, draft, guests) is MeetingMutationResult.LocalSaved)
        assertEquals(2, f.receipt("old-token-new-receipt").getInt("version"))
    } }

    @Test fun frozenCodecUsesVersionedMappingWithoutCurrentZoneOrGuestAuthorValidation() = runBlocking { fixture { f ->
        val token = f.token(); val source = f.store.edit(token.value)!!
        val calendar = CalendarMeetingCreateWriter.decodeIssuedSource(source, token, 1024 * 1024)
        val historical = draft.copy(eventZone = ZoneOffset.ofHoursMinutes(5, 30))
        val inputs = listOf(MeetingGuestInput("historically accepted input", MeetingGuestType.REQUIRED))
        val command = CalendarMeetingCreateWriter.oneOffCommand(token, historical, inputs, 1024 * 1024)
        val prototype = f.writer.create("prototype", token, draft, guests) as MeetingMutationResult.LocalSaved
        val original = MeetingOneOffCreationReceipt.parse(f.store.operation("prototype")!!, 1024 * 1024).second.plan
        val event = original.event + mapOf("eventTimezone" to "+05:30", "eventEndTimezone" to "+05:30")
        val rows = listOf(original.attendees.first(), mapOf("attendeeEmail" to "historically accepted input", "attendeeRelationship" to "1", "attendeeType" to "1", "attendeeStatus" to "3"))
        val plan = original.copy(event = event, attendees = rows)
        val signature = CalendarMeetingCreateWriter.fingerprint(token, historical, inputs, 1024 * 1024).second
        val receipt = MeetingOneOffCreationReceipt(command, source, calendar, historical, plan)
        val operation = CalendarLocalStore.Operation("historic", signature, "MEETING_V1_CREATE", "PREPARED", receipt.json(signature).toString())
        assertEquals(plan, MeetingOneOffCreationReceipt.parse(operation, 1024 * 1024).second.plan)
        assertEquals(1L, prototype.selected.eventId)
    } }
}
