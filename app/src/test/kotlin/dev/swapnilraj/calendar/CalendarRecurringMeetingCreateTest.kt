package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
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

/** Dedicated synthetic provider; first-instance expansion is not Google sync certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarRecurringMeetingCreateTest {
    private val start = Instant.parse("2026-11-18T09:00:00Z")
    private val identity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A")
    private val draft = EventDraft(CalendarId(2), EventTime.Timed(start, start.plusSeconds(3600)), ZoneId.of("Europe/London"),
        "Synthetic recurring meeting", "Synthetic room", "Synthetic notes", listOf(10, 30), 1, 2, identity,
        RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(4)))
    private val guests = listOf(MeetingGuestInput("required@example.invalid", MeetingGuestType.REQUIRED, "Required guest"),
        MeetingGuestInput("optional@example.invalid", MeetingGuestType.OPTIONAL))
    private data class Fixture(val p: CalendarRecurringCreateTest.Provider, val writer: CalendarMeetingWriter,
        val reader: CalendarReader, val store: CalendarLocalStore, val permissions: MutableSet<String>)
    private suspend fun fixture(budgets: ProviderReadBudgets = ProviderReadBudgets(), block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val p = CalendarRecurringCreateTest.Provider().apply {
            nativeMeetingMode = true
            db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = CalendarLocalStore(context, "recurring-meeting-${UUID.randomUUID()}.db", readBudgets = budgets)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarMeetingWriter(context.contentResolver, reader, store, permissions::contains, readBudgets = budgets)
        try { block(Fixture(p, writer, reader, store, permissions)) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); p.db.close(); context.deleteDatabase(store.databaseName) }
    }
    private suspend fun Fixture.creation() = (writer.loadForCreate(identity) as MeetingReadResult.Ready).value
    private fun common(result: MutationResult) = MeetingMutationResult.Common(result)
    private fun Fixture.receipt(id: String) = JSONObject(store.operation(id)!!.resultRef!!)
    private fun Fixture.dropSources() { store.writableDatabase.delete("edit_snapshots", null, null) }

    @Test fun sourceIsReadOnlyVersionedAndActualCreateHasOneOrganizerAndNativeSeriesShape() = runBlocking { fixture { f ->
        f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        val creation = f.creation()
        assertEquals(AccessDecision.ALLOWED, creation.recurringCreation)
        assertTrue(creation.token.value.startsWith(MeetingRecurringCreationSource.TokenPrefix))
        assertEquals(0, f.p.batchCount); assertTrue(f.p.queries.all { it.path == "calendars" })
        assertEquals(common(MutationResult.PermissionRequired), f.writer.create("series", creation.token, draft, guests))
        assertNull(f.store.operationHeader("series"))
        f.permissions += Manifest.permission.WRITE_CALENDAR
        val saved = f.writer.create("series", creation.token, draft, guests) as MeetingMutationResult.LocalSaved
        assertEquals(RecurrenceScope.ALL, saved.scope); assertEquals(RecurrenceRef(saved.selected.eventId, start), saved.selected.recurrence)
        assertEquals(start.plusSeconds(3600), saved.selected.instanceEnd)
        assertNull(f.p.value("dtend")); assertEquals("PT3600S", f.p.value("duration"))
        assertEquals("FREQ=DAILY;INTERVAL=1;WKST=MO;COUNT=4", f.p.value("rrule"))
        assertTrue(f.p.value("uid2445")!!.endsWith("@daylight.invalid"))
        assertEquals(3, f.p.count("attendees")); assertEquals(2, f.p.count("reminders")); assertEquals(1, f.p.batchCount)
        val inserted = f.p.insertedEventValues.single()
        assertFalse(inserted.containsKey("selfAttendeeStatus")); assertFalse(inserted.containsKey("isOrganizer"))
        assertEquals("VERIFIED", f.store.operation("series")!!.phase)
        val receipt = f.receipt("series"); assertEquals(2, receipt.getInt("version")); assertTrue(receipt.getBoolean("ackComplete"))
        assertEquals(f.p.value("uid2445"), receipt.getString("uid")); assertTrue(receipt.has("firstRef"))
    } }

    @Test fun dailyWeeklyMonthlyYearlyAndInclusiveUntilKeepTheExactCompiledRule() = runBlocking {
        val specs = listOf(RecurrenceSpec(RecurrencePattern.Daily),
            RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)), interval = 2, end = RecurrenceEnd.Count(5)),
            RecurrenceSpec(RecurrencePattern.MonthlyDate(18), end = RecurrenceEnd.Count(3)),
            RecurrenceSpec(RecurrencePattern.MonthlyWeekday(3, DayOfWeek.WEDNESDAY), end = RecurrenceEnd.Count(3)),
            RecurrenceSpec(RecurrencePattern.Yearly(MonthDay.of(11, 18)), end = RecurrenceEnd.Count(2)),
            RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ThroughDate(LocalDate.of(2026, 11, 30))))
        specs.forEach { spec -> fixture { f ->
            val input = draft.copy(recurrence = spec); val token = f.creation().token
            assertTrue(f.writer.create("patterns", token, input, guests) is MeetingMutationResult.LocalSaved)
            assertEquals(RecurrenceRules.build(spec, RecurrenceStart.Timed(start.atZone(input.eventZone).toLocalDateTime(), input.eventZone!!, ZoneOffset.UTC)), f.p.value("rrule"))
            f.dropSources(); f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertTrue(f.writer.recover("patterns") is MeetingMutationResult.LocalSaved); assertEquals(1, f.p.batchCount)
        } }
    }

    @Test fun allDayIsUtcDateWithWholeDayDurationAndNoTimedZoneDefault() = runBlocking { fixture { f ->
        val dates = CalendarDateRange(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 21))
        val input = draft.copy(time = EventTime.AllDay(dates), eventZone = null,
            recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ThroughDate(LocalDate.of(2026, 12, 1))))
        val saved = f.writer.create("dates", f.creation().token, input, guests) as MeetingMutationResult.LocalSaved
        assertEquals("P3D", f.p.value("duration")); assertEquals("UTC", f.p.value("eventTimezone")); assertNull(f.p.value("dtend"))
        assertEquals(Instant.parse("2026-11-18T00:00:00Z"), saved.selected.instanceStart)
        assertEquals(Instant.parse("2026-11-21T00:00:00Z"), saved.selected.instanceEnd)
        assertTrue(f.p.value("rrule")!!.endsWith("UNTIL=20261201"))
    } }

    @Test fun invalidActualPatternCountPrecisionTransitionAndProviderZoneNeverPrepare() = runBlocking {
        val inputs = listOf(draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Weekly(setOf(DayOfWeek.MONDAY)))),
            draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(10001))),
            draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ExactUntil(start.plusSeconds(100000)))),
            draft.copy(time = EventTime.Timed(start.plusNanos(1), start.plusSeconds(3600))),
            draft.copy(time = EventTime.Timed(start, start.plusSeconds(Int.MAX_VALUE.toLong() + 1))),
            draft.copy(time = EventTime.Timed(Instant.parse("2026-03-28T01:30:00Z"), Instant.parse("2026-03-28T02:30:00Z"))),
            draft.copy(eventZone = ZoneOffset.ofHoursMinutes(5, 30)))
        inputs.forEach { input -> fixture { f ->
            assertTrue((f.writer.create("invalid", f.creation().token, input, guests) as MeetingMutationResult.Common).outcome is MutationResult.ValidationFailed)
            assertNull(f.store.operationHeader("invalid")); assertEquals(0, f.p.batchCount)
        } }
    }

    @Test fun invalidGuestRowsRetainTheirTypedErrorAndNeverPrepare() = runBlocking {
        val invalid = listOf(listOf(MeetingGuestInput("bad address", MeetingGuestType.REQUIRED)),
            listOf(MeetingGuestInput(identity.owner!!, MeetingGuestType.REQUIRED)),
            listOf(guests[0], guests[0].copy(type = MeetingGuestType.OPTIONAL)))
        invalid.forEach { inputs -> fixture { f ->
            assertTrue(f.writer.create("guests", f.creation().token, draft, inputs) is MeetingMutationResult.Invalid)
            assertNull(f.store.operationHeader("guests")); assertEquals(0, f.p.batchCount)
        } }
    }

    @Test fun versionOneTokensNeverAuthorizeRecurrenceAndBothVersionsKeepOneOffCommandBytes() = runBlocking { fixture { f ->
        val v2 = f.creation().token
        val base = MeetingRecurringCreationSource.decode(f.store.edit(v2.value)!!, v2, 1024 * 1024).baseRaw
        val v1 = EditToken("meeting-create-v1:" + meetingHash("MEETING_CREATE_SOURCE_V1" + base))
        f.store.saveEdit(v1.value, base)
        assertTrue(f.writer.create("old-series", v1, draft, guests) is MeetingMutationResult.NeedsReview)
        assertNull(f.store.operationHeader("old-series")); assertEquals(0, f.p.batchCount)
        for ((index, token) in listOf(v1, v2).withIndex()) {
            val oneoff = draft.copy(recurrence = null)
            f.store.prepareLegacyMeetingCreate("oneoff-$index", token, oneoff, guests)
            assertTrue(f.writer.create("oneoff-$index", token, oneoff, guests) is MeetingMutationResult.LocalSaved)
            val op = f.store.operation("oneoff-$index")!!
            assertEquals("MEETING_V1_CREATE", op.kind); assertEquals(1, f.receipt(op.id).getInt("version"))
            assertEquals(token.value, f.receipt(op.id).getJSONObject("command").getString("token"))
            assertFalse(f.receipt(op.id).getJSONObject("command").getJSONObject("draft").has("recurrence"))
        }
    } }

    @Test fun unknownCalendarTimeZonePolicyIssuesNoRecurringCapability() = runBlocking { fixture { f ->
        f.p.setCalendar("canModifyTimeZone", null)
        val creation = f.creation(); assertEquals(AccessDecision.UNKNOWN, creation.recurringCreation)
        assertTrue(creation.token.value.startsWith("meeting-create-v1:"))
        assertTrue(f.writer.create("unknown", creation.token, draft, guests) is MeetingMutationResult.NeedsReview)
        assertNull(f.store.operationHeader("unknown")); assertEquals(0, f.p.batchCount)
    } }

    @Test fun rollbackRetriesReuseWinningPlanAndUidWithoutTheEditSnapshot() = runBlocking {
        for (attendee in listOf(true, false)) fixture { f ->
            val token = f.creation().token; f.p.failAttendee = attendee; f.p.failReminder = !attendee
            assertEquals(common(MutationResult.Unavailable), f.writer.create("retry", token, draft, guests))
            assertEquals("FAILED", f.store.operation("retry")!!.phase); assertEquals(0, f.p.count("events"))
            val before = f.receipt("retry").toString(); val firstUid = f.p.insertedEventValues.single().getAsString("uid2445")
            f.dropSources(); assertEquals(common(MutationResult.SafeToRetry("retry")), f.writer.recover("retry"))
            assertEquals(common(MutationResult.Conflict(null)), f.writer.create("retry", token, draft, guests.reversed()))
            f.p.failAttendee = false; f.p.failReminder = false
            assertTrue(f.writer.create("retry", token, draft, guests) is MeetingMutationResult.LocalSaved)
            assertEquals(firstUid, f.p.value("uid2445")); assertEquals(2, f.p.batchCount)
            assertEquals(JSONObject(before).getJSONObject("plan").toString(), f.receipt("retry").getJSONObject("plan").toString())
        }
    }

    @Test fun lostOrMalformedParentAcknowledgementNeverSearchesOrReplays() = runBlocking {
        for (malformed in listOf(false, true)) fixture { f ->
            val token = f.creation().token; f.p.lostAck = !malformed; f.p.malformedAck = malformed
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.create("lost", token, draft, guests))
            assertEquals("ATTEMPTED", f.store.operation("lost")!!.phase)
            f.dropSources(); f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.recover("lost"))
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.create("lost", token, draft, guests))
            assertTrue(f.p.queries.isEmpty()); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun malformedChildAckPersistsKnownParentAndStaysUncertainWithoutReconstruction() = runBlocking { fixture { f ->
        val token = f.creation().token; f.p.malformedAttendeeAck = true
        assertEquals(common(MutationResult.Uncertain("partial", 1)), f.writer.create("partial", token, draft, guests))
        val op = f.store.operation("partial")!!; assertEquals("COMMITTED", op.phase)
        val receipt = f.receipt("partial"); assertFalse(receipt.getBoolean("ackComplete")); assertEquals(1, receipt.getLong("event"))
        assertFalse(receipt.has("attendees")); assertFalse(receipt.has("firstRef"))
        f.dropSources(); f.store.close(); f.p.queries.clear(); f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertEquals(common(MutationResult.Uncertain("partial", 1)), f.writer.recover("partial"))
        assertTrue(f.p.queries.isEmpty()); assertEquals(op, f.store.operation("partial")); assertEquals(1, f.p.batchCount)
    } }

    @Test fun committedKnownIdRecoversReadOnlyAfterReopenAndSourceDeletion() = runBlocking { fixture { f ->
        val token = f.creation().token; f.p.failReadsAfterCommit = true
        assertEquals(common(MutationResult.Uncertain("known", 1)), f.writer.create("known", token, draft, guests))
        assertEquals("COMMITTED", f.store.operation("known")!!.phase)
        f.dropSources(); f.store.close(); f.p.failReadsAfterCommit = false; f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertTrue(f.writer.recover("known") is MeetingMutationResult.LocalSaved); assertEquals(1, f.p.batchCount)
        f.p.setCalendar("_sync_id", "replacement-calendar"); f.p.queries.clear()
        assertEquals(common(MutationResult.Uncertain("known", 1)), f.writer.recover("known"))
        assertTrue(f.p.queries.all { it.path == "calendars" })
    } }

    @Test fun provisionalUidChangesOrMissingProjectionNeverExposePrivateGuestRows() = runBlocking {
        for (replacement in listOf<String?>(null, "", "foreign-uid")) fixture { f ->
            val token = f.creation().token
            assertTrue(f.writer.create("uid", token, draft, guests) is MeetingMutationResult.LocalSaved)
            f.p.db.execSQL("UPDATE events SET uid2445=?", arrayOf(replacement)); f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("uid", 1)), f.writer.recover("uid"))
            assertTrue(f.p.queries.none { it.path == "attendees" }); assertEquals(1, f.p.batchCount)
        }
        fixture { f ->
            assertTrue(f.writer.create("projection", f.creation().token, draft, guests) is MeetingMutationResult.LocalSaved)
            f.p.rejectEventUidProjection = true; f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("projection", 1)), f.writer.recover("projection"))
            assertTrue(f.p.queries.none { it.path == "attendees" }); assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun sourceEligibilityDoesNotInventOrPrevalidateAnActualAuthoredZone() = runBlocking { fixture { f ->
        f.p.setCalendar("canModifyTimeZone", "0")
        val creation = f.creation()
        assertEquals(AccessDecision.ALLOWED, creation.recurringCreation)
        assertTrue(f.p.queries.all { it.path == "calendars" })
        assertEquals(common(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE))),
            f.writer.create("wrong-zone", creation.token, draft.copy(eventZone = ZoneId.of("Asia/Tokyo")), guests))
        assertNull(f.store.operationHeader("wrong-zone")); assertEquals(0, f.p.batchCount)
        assertTrue(f.writer.create("calendar-zone", creation.token, draft, guests) is MeetingMutationResult.LocalSaved)
    } }

    @Test fun uidReplacementBeforeEitherChildReadCannotMaterializeForeignRows() = runBlocking {
        for (child in listOf("attendees", "reminders")) fixture { f ->
            assertTrue(f.writer.create("child-race", f.creation().token, draft, guests) is MeetingMutationResult.LocalSaved)
            f.p.queries.clear()
            f.p.beforeQuery = { path ->
                if (path == child && (path != "reminders" || f.p.queries.last().itemId != null)) {
                    f.p.beforeQuery = null
                    f.p.db.execSQL("UPDATE events SET uid2445='foreign-parent-identity'")
                }
            }
            assertEquals(common(MutationResult.Uncertain("child-race", 1)), f.writer.recover("child-race"))
            val privateReads = f.p.queries.filter { it.path == "attendees" || it.path == "reminders" && it.fields?.contains("minutes") == true }
            assertTrue(privateReads.isNotEmpty())
            assertTrue(privateReads.all { it.selection?.contains("uid2445 = ?") == true && it.selection.contains("calendar_id = ?") })
            assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun rawReminderCollectionRejectsJoinsAndRecoveryOnlyReadsPayloadFromGuardedItems() = runBlocking { fixture { f ->
        assertTrue(f.writer.create("native-shape", f.creation().token, draft, guests) is MeetingMutationResult.LocalSaved)
        try {
            RuntimeEnvironment.getApplication().contentResolver.query(CalendarContract.Reminders.CONTENT_URI,
                arrayOf("_id", "minutes"), "event_id = ? AND uid2445 = ?", arrayOf("1", f.p.value("uid2445")), null)?.close()
            fail("The pinned native collection has no joined event columns")
        } catch (_: android.database.sqlite.SQLiteException) { }
        f.p.queries.clear()
        assertTrue(f.writer.recover("native-shape") is MeetingMutationResult.LocalSaved)
        val collections = f.p.queries.filter { it.path == "reminders" && it.itemId == null }
        assertEquals(2, collections.size)
        assertTrue(collections.all { it.fields == listOf("_id", "event_id") && it.selection == "event_id = ?" })
        val items = f.p.queries.filter { it.path == "reminders" && it.itemId != null }
        assertEquals(2, items.size)
        assertTrue(items.all { it.fields == listOf("_id", "event_id", "minutes", "method") && it.selection?.contains("uid2445 = ?") == true })
    } }

    @Test fun changedReminderMembershipBetweenScansStaysCommittedAndNeverReplays() = runBlocking {
        for (change in listOf("extra", "deleted", "replaced")) fixture { f ->
            val token = f.creation().token; f.p.failReadsAfterCommit = true
            assertEquals(common(MutationResult.Uncertain("membership", 1)), f.writer.create("membership", token, draft, guests))
            val committed = f.store.operation("membership")!!; assertEquals("COMMITTED", committed.phase)
            f.p.failReadsAfterCommit = false; f.p.queries.clear()
            var collections = 0
            f.p.beforeQuery = { path ->
                if (path == "reminders" && f.p.queries.last().itemId == null && ++collections == 2) {
                    f.p.beforeQuery = null
                    if (change != "extra") f.p.db.execSQL("DELETE FROM reminders WHERE _id=1")
                    if (change != "deleted") f.p.db.execSQL("INSERT INTO reminders(event_id,minutes,method) VALUES (1,45,1)")
                }
            }
            assertEquals(common(MutationResult.Uncertain("membership", 1)), f.writer.recover("membership"))
            assertEquals(committed, f.store.operation("membership"))
            assertEquals(common(MutationResult.Uncertain("membership", 1)), f.writer.create("membership", token, draft, guests))
            assertEquals(1, f.p.batchCount)
        }
    }

    @Test fun changedParentUidAfterChildrenCannotVerifyOrMutateTheCommittedReceipt() = runBlocking { fixture { f ->
        val token = f.creation().token; f.p.failReadsAfterCommit = true
        assertEquals(common(MutationResult.Uncertain("parent-after", 1)), f.writer.create("parent-after", token, draft, guests))
        val committed = f.store.operation("parent-after")!!; f.p.failReadsAfterCommit = false
        f.p.afterQuery = { path -> if (path == "instances") {
            f.p.afterQuery = null
            f.p.db.execSQL("UPDATE events SET uid2445='replacement-after-child-reads'")
        } }
        assertEquals(common(MutationResult.Uncertain("parent-after", 1)), f.writer.recover("parent-after"))
        assertEquals(committed, f.store.operation("parent-after")); assertEquals(1, f.p.batchCount)
    } }

    @Test fun delayedOrWrongNativeBoundsNeverInventTheFirstOccurrence() = runBlocking { fixture { f ->
        val token = f.creation().token; f.p.delayedInstances = true
        assertEquals(common(MutationResult.Uncertain("delayed", 1)), f.writer.create("delayed", token, draft, guests))
        assertFalse(f.receipt("delayed").has("firstRef"))
        f.p.delayedInstances = false; f.p.endDelta = 1000
        assertEquals(common(MutationResult.Uncertain("delayed", 1)), f.writer.recover("delayed"))
        f.p.endDelta = 0; f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertTrue(f.writer.recover("delayed") is MeetingMutationResult.LocalSaved); assertEquals(1, f.p.batchCount)
    } }

    @Test fun exactNamespaceChangesAndBatchRacesNeverAdoptReplacementCalendars() = runBlocking {
        for (field in listOf("account_name", "ownerAccount", "_sync_id")) fixture { f ->
            val token = f.creation().token; f.p.setCalendar(field, "replacement@example.invalid")
            assertEquals(common(MutationResult.Conflict(null)), f.writer.create("changed", token, draft, guests))
            assertNull(f.store.operationHeader("changed")); assertEquals(0, f.p.batchCount)
        }
        fixture { f ->
            val token = f.creation().token; f.p.beforeBatch = { f.p.setCalendar("_sync_id", "replacement") }
            assertEquals(common(MutationResult.Unavailable), f.writer.create("race", token, draft, guests))
            assertEquals("FAILED", f.store.operation("race")!!.phase); assertEquals(0, f.p.count("events"))
        }
    }

    @Test fun typedRetirementFencesLateRecurringCreateWithoutPermissionOrSourceReads() = runBlocking { fixture { f ->
        val token = f.creation().token; val retirement = CalendarOperationRetirement(f.store)
        val request = RetirableOperation.MeetingCreate("retired", token, draft, guests)
        assertEquals(OperationRetirementResult.Retired("retired"), retirement.retire(request))
        assertEquals(OperationRetirementResult.Conflict("retired"), retirement.inspect(request.copy(guests = guests.reversed())))
        f.permissions.clear(); f.dropSources(); f.p.queries.clear()
        assertEquals(common(MutationResult.Retired("retired")), f.writer.create("retired", token, draft, guests))
        assertTrue(f.p.queries.isEmpty()); assertEquals(0, f.p.batchCount)
    } }

    @Test fun completeReceiptAndNativeBatchBudgetsAreCheckedBeforePreparing() = runBlocking {
        fixture(ProviderReadBudgets(maxCanonicalBytes = 64 * 1024)) { f ->
            val token = f.creation().token
            assertTrue(f.writer.create("receipt-budget", token, draft, guests) is MeetingMutationResult.NeedsReview)
            assertNull(f.store.operationHeader("receipt-budget")); assertEquals(0, f.p.batchCount)
        }
        fixture { f ->
            val token = f.creation().token
            val large = List(200) { MeetingGuestInput("guest$it@example.invalid", MeetingGuestType.REQUIRED, "N".repeat(256)) }
            assertTrue(f.writer.create("batch-budget", token, draft, large) is MeetingMutationResult.NeedsReview)
            assertNull(f.store.operationHeader("batch-budget")); assertEquals(0, f.p.batchCount)
        }
    }

    @Test fun explicitLateFoldInstantKeepsElapsedDurationWithoutChoosingAnEarlierOffset() = runBlocking { fixture { f ->
        val late = Instant.parse("2026-10-25T01:30:00Z")
        val input = draft.copy(time = EventTime.Timed(late, late.plusSeconds(3600)),
            recurrence = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.Count(1)))
        val saved = f.writer.create("fold", f.creation().token, input, guests) as MeetingMutationResult.LocalSaved
        assertEquals(late, saved.selected.instanceStart); assertEquals(late.plusSeconds(3600), saved.selected.instanceEnd)
        assertEquals("PT3600S", f.p.value("duration")); assertEquals(0, f.receipt("fold").getJSONObject("plan").getInt("startOffset"))
    } }

    @Test fun independentWriterInstancesAdoptOneWinningPlanAndDispatchOnlyOneBatch() = runBlocking { fixture { f ->
        val token = f.creation().token
        val other = CalendarMeetingWriter(RuntimeEnvironment.getApplication().contentResolver, f.reader, f.store, f.permissions::contains)
        val outcomes = coroutineScope {
            listOf(async(Dispatchers.Default) { f.writer.create("concurrent", token, draft, guests) },
                async(Dispatchers.Default) { other.create("concurrent", token, draft, guests) }).awaitAll()
        }
        assertTrue(outcomes.any { it is MeetingMutationResult.LocalSaved })
        assertTrue(outcomes.all { it is MeetingMutationResult.LocalSaved || it is MeetingMutationResult.Common && it.outcome is MutationResult.Uncertain })
        assertEquals(1, f.p.batchCount); assertEquals(1, f.p.count("events")); assertEquals(3, f.p.count("attendees"))
        assertEquals(f.receipt("concurrent").getString("uid"), f.p.value("uid2445"))
        assertTrue(f.writer.recover("concurrent") is MeetingMutationResult.LocalSaved)
    } }

    @Test fun frozenHistoricalOffsetsValidateArithmeticallyWithoutCurrentZoneRules() {
        // The offsets may represent a previously installed tzdb. Stored UTC boundaries remain exact.
        val spec = RecurrenceSpec(RecurrencePattern.Daily, end = RecurrenceEnd.ThroughDate(LocalDate.of(2026, 11, 30)))
        val input = draft.copy(recurrence = spec)
        val frozen = FrozenMeetingCreationPlan(start, start.plusSeconds(3600), false, "Europe/London",
            "FREQ=DAILY;INTERVAL=1;WKST=MO;UNTIL=20261130T215959Z", "PT3600S", "2026-11-18T11:00", 7200, "2026-12-01T00:00", 7200)
        frozen.validate(input)
        assertEquals(frozen, FrozenMeetingCreationPlan.parse(frozen.json(), input))
        try { frozen.copy(rule = frozen.rule.replace("215959", "235959")).validate(input); fail("Do not rebuild UNTIL from current rules") }
        catch (_: IllegalArgumentException) { }
    }
}
