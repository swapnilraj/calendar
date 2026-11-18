package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentResolver
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.ZoneId
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
class CalendarSeriesWriterTest {
    private val start = Instant.parse("2026-11-17T09:00:00Z")
    private val selected = EventRef(CalendarId(2), 10, start.plusSeconds(86_400), start.plusSeconds(90_000), RecurrenceRef(10, start.plusSeconds(86_400)))
    private val moved = EventRef(CalendarId(2), 11, start.plusSeconds(20 * 86_400), start.plusSeconds(20 * 86_400 + 3_600), RecurrenceRef(10, start.plusSeconds(2 * 86_400)))
    private fun master() = mutableMapOf<String, String?>("_id" to "10", "calendar_id" to "2", "_sync_id" to "cloud-master-A", "uid2445" to "uid-master-A",
        "dtstart" to start.toEpochMilli().toString(), "dtend" to null, "duration" to "PT1H", "allDay" to "0", "eventTimezone" to "Europe/London", "eventEndTimezone" to "Europe/London",
        "title" to "Master", "eventLocation" to "Office", "description" to "Raw notes", "rrule" to "FREQ=DAILY;COUNT=5", "rdate" to null, "exrule" to null, "exdate" to null,
        "original_id" to null, "original_sync_id" to null, "originalInstanceTime" to null, "originalAllDay" to null,
        "deleted" to "0", "eventStatus" to "1", "organizer" to "owner@example.invalid", "isOrganizer" to "1", "hasAttendeeData" to "0", "guestsCanSeeGuests" to "1",
        "availability" to "77", "accessLevel" to "2", "hasAlarm" to "1", "customAppPackage" to null, "future_private_column" to "Never rewrite this")
    private fun exception(id: Long, originalDays: Long, actualDays: Long, cancelled: Boolean = false) = master().apply {
        put("_id", id.toString()); put("_sync_id", "cloud-exception-$id"); put("uid2445", "uid-exception-$id")
        put("rrule", null); put("duration", null); put("title", "Explicit override $id")
        put("dtstart", start.plusSeconds(actualDays * 86_400).toEpochMilli().toString()); put("dtend", start.plusSeconds(actualDays * 86_400 + 3_600).toEpochMilli().toString())
        put("original_id", null); put("original_sync_id", "cloud-master-A"); put("originalInstanceTime", start.plusSeconds(originalDays * 86_400).toEpochMilli().toString()); put("originalAllDay", "0")
        put("eventStatus", if (cancelled) "2" else "1")
    }
    private fun instance(ref: EventRef, template: Map<String, String?>) = template.toMutableMap().apply {
        put("event_id", ref.eventId.toString()); put("begin", ref.instanceStart.toEpochMilli().toString()); put("end", ref.instanceEnd.toEpochMilli().toString()); put("displayColor", "123"); put("selfAttendeeStatus", "0")
    }
    private data class Fixture(val provider: CalendarSeriesReaderTest.Provider, val writer: CalendarSeriesWriter,
        val source: CalendarSeriesReader, val store: CalendarLocalStore, val permissions: MutableSet<String>, val reader: CalendarReader, val resolver: ContentResolver)
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val p = CalendarSeriesReaderTest.Provider().apply {
            allowWrites = true
            calendar["allowedReminders"] = "0,1,2"; calendar["maxReminders"] = "5"
            events += master(); events += exception(11, 2, 20); events += exception(12, 3, 3, true)
            instances += instance(selected, events[0]); instances += instance(moved, events[1])
            reminders += mutableMapOf("_id" to "50", "event_id" to "10", "minutes" to "10", "method" to "1")
            reminders += mutableMapOf("_id" to "51", "event_id" to "10", "minutes" to "30", "method" to "2", "opaque_child" to "Keep email metadata")
            reminders += mutableMapOf("_id" to "52", "event_id" to "10", "minutes" to "-1", "method" to "0")
            reminders += mutableMapOf("_id" to "53", "event_id" to "11", "minutes" to "20", "method" to "1")
        }
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, p)
        val store = CalendarLocalStore(context, "series-writer-${UUID.randomUUID()}.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val source = CalendarSeriesReader(context.contentResolver, reader, store, permissions::contains)
        val writer = CalendarSeriesWriter(context.contentResolver, source, store, permissions::contains)
        try { block(Fixture(p, writer, source, store, permissions, reader, context.contentResolver)) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); context.deleteDatabase(store.databaseName) }
    }
    private suspend fun Fixture.editable(ref: EventRef = selected) = (writer.loadForEdit(ref) as SeriesReadResult.Ready).series
    private fun whole(series: EditableSeries, patch: EventPatch) = SeriesCommand.WholeEdit(RecurrenceEditIntent.WholeSeriesEdit(series.snapshot.start, fields = patch))
    private fun common(result: MutationResult) = SeriesMutationResult.Common(result)

    @Test fun allFieldEditsPreserveRawRecurrenceOverridesUnknownFieldsAndNonAlertRows() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val result = f.writer.mutate("all-edit", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Changed master"),
                alertMinutes = FieldChange.Set(listOf(15)), privacy = FieldChange.Set(3))))
            assertEquals(SeriesMutationResult.LocalSaved(SeriesIdentity(CalendarId(2), 10), selected), result)
            assertEquals("Changed master", f.provider.events[0]["title"])
            assertEquals("3", f.provider.events[0]["accessLevel"])
            assertEquals("77", f.provider.events[0]["availability"])
            assertEquals("PT1H", f.provider.events[0]["duration"])
            assertEquals("FREQ=DAILY;COUNT=5", f.provider.events[0]["rrule"])
            assertEquals("Never rewrite this", f.provider.events[0]["future_private_column"])
            assertEquals("Explicit override 11", f.provider.events[1]["title"])
            assertEquals("2", f.provider.events[2]["eventStatus"])
            assertTrue(f.provider.reminders.any { it["_id"] == "51" && it["opaque_child"] == "Keep email metadata" })
            assertTrue(f.provider.reminders.any { it["_id"] == "52" && it["method"] == "0" && it["minutes"] == "-1" })
            assertEquals(listOf("15"), f.provider.reminders.filter { it["event_id"] == "10" && it["method"] == "1" }.map { it["minutes"] })
            assertEquals("20", f.provider.reminders.single { it["event_id"] == "11" }["minutes"])
        }
    }

    @Test fun unrelatedWholeEditsPreserveUnsupportedRawRecurrenceAndDateSyntax() = runBlocking {
        fixture { f ->
            f.provider.events[0]["rrule"] = "FREQ=HOURLY;INTERVAL=3"
            f.provider.events[0]["exdate"] = "Unsupported;opaque-date-set"
            f.provider.reminders += mutableMapOf("_id" to "54", "event_id" to "10", "minutes" to "-1", "method" to "1")
            val editable = f.editable()
            assertTrue(f.writer.mutate("raw-rule", selected, editable.token, whole(editable,
                EventPatch(description = FieldChange.Set("Only notes")))) is SeriesMutationResult.LocalSaved)
            assertEquals("FREQ=HOURLY;INTERVAL=3", f.provider.events[0]["rrule"])
            assertEquals("Unsupported;opaque-date-set", f.provider.events[0]["exdate"])
            assertEquals("-1", f.provider.reminders.single { it["_id"] == "54" }["minutes"])
        }
    }

    @Test fun existingThisExceptionEditsAndCancellationPreserveCloudParentLinksAndOriginalTime() = runBlocking {
        fixture { f ->
            var editable = f.editable(moved)
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.THIS).edit)
            assertTrue(f.writer.mutate("this-edit", moved, editable.token, SeriesCommand.OccurrenceEdit(EventPatch(title = FieldChange.Set("Only override"),
                alertMinutes = FieldChange.Set(listOf(25)), availability = FieldChange.Set(1)))) is SeriesMutationResult.LocalSaved)
            val exception = f.provider.events[1]
            assertEquals("Only override", exception["title"])
            assertNull(exception["original_id"])
            assertEquals("cloud-master-A", exception["original_sync_id"])
            assertEquals(moved.recurrence!!.originalStart.toEpochMilli().toString(), exception["originalInstanceTime"])
            assertEquals("Master", f.provider.events[0]["title"])
            editable = f.editable(moved)
            assertEquals(SeriesMutationResult.LocalDeleted(SeriesIdentity(CalendarId(2), 10), RecurrenceScope.THIS, moved),
                f.writer.mutate("this-delete", moved, editable.token, SeriesCommand.Delete(RecurrenceScope.THIS)))
            assertEquals("2", exception["eventStatus"])
            assertEquals("0", f.provider.events[0]["deleted"])
            assertEquals(3, f.provider.events.size)
        }
    }

    @Test fun wholeDeletionIncludesCancelledAndSyncOnlyOverridesAndRecoversWithoutWrites() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val result = f.writer.mutate("all-delete", selected, editable.token, SeriesCommand.Delete(RecurrenceScope.ALL))
            assertEquals(SeriesMutationResult.LocalDeleted(SeriesIdentity(CalendarId(2), 10), RecurrenceScope.ALL, selected), result)
            assertTrue(f.provider.events.all { it["deleted"] == "1" })
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(result, f.writer.recover("all-delete"))
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun unsupportedCreationFollowingAndTemporalCommandsDispatchNoBatchOrJournal() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val commands = listOf(
                SeriesCommand.OccurrenceEdit(EventPatch(title = FieldChange.Set("Would need new row"))),
                SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(RecurrenceKey.Timed(selected.recurrence!!.originalStart), fields = EventPatch(title = FieldChange.Set("Tail")))),
                SeriesCommand.Delete(RecurrenceScope.FOLLOWING),
                SeriesCommand.WholeEdit(RecurrenceEditIntent.WholeSeriesEdit(editable.snapshot.start, duration = RecurrenceDurationChange.TimedElapsed(java.time.Duration.ofHours(2))))
            )
            commands.forEachIndexed { index, command ->
                val result = f.writer.mutate("unsupported-$index", selected, editable.token, command)
                assertTrue(result is SeriesMutationResult.Unsupported || result is SeriesMutationResult.PlanningReview)
                assertNull(f.store.operation("unsupported-$index"))
            }
            val existing = f.editable(moved)
            assertTrue(f.writer.mutate("unsupported-time", moved, existing.token, SeriesCommand.OccurrenceEdit(EventPatch(
                time = FieldChange.Set(EventTime.Timed(moved.instanceStart.plusSeconds(60), moved.instanceEnd.plusSeconds(60)))))) is SeriesMutationResult.Unsupported)
            assertEquals(0, f.provider.batchCount)
            assertEquals(3, f.provider.events.size)
        }
    }

    @Test fun capabilitiesArePerScopeAndDoNotRepresentRuntimeWriteGrant() = runBlocking {
        fixture { f ->
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val source = (f.source.loadForEdit(selected) as SeriesReadResult.Ready).series
            assertTrue(source.scopes.isEmpty())
            val editable = f.editable()
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.ALL).edit)
            assertEquals(AccessDecision.ALLOWED, editable.scopes.getValue(RecurrenceScope.ALL).delete)
            assertFalse(EditableField.TIME in editable.scopes.getValue(RecurrenceScope.ALL).fields)
            assertFalse(EditableField.ZONE in editable.allowedFields)
            assertEquals(SeriesActionLimitation.UNSUPPORTED, editable.scopes.getValue(RecurrenceScope.THIS).limitation)
            assertEquals(AccessDecision.DENIED, editable.scopes.getValue(RecurrenceScope.FOLLOWING).delete)
            assertEquals(common(MutationResult.PermissionRequired), f.writer.mutate("no-write", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("No write")))))
            assertEquals(0, f.provider.batchCount)
        }
    }

    @Test fun staleTokensAndBatchRacesDoNotOverwriteMasterOrExceptionChanges() = runBlocking {
        fixture { f ->
            var editable = f.editable()
            f.provider.events[0]["description"] = "External edit"
            assertTrue((f.writer.mutate("stale", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Blocked")))) as SeriesMutationResult.Common).outcome is MutationResult.Conflict)
            assertEquals(0, f.provider.batchCount)
            editable = f.editable()
            f.provider.beforeBatch = { f.provider.events[1]["title"] = "Remote override" }
            assertTrue((f.writer.mutate("race", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Blocked")))) as SeriesMutationResult.Common).outcome is MutationResult.Conflict)
            assertEquals("Master", f.provider.events[0]["title"])
            assertEquals("Remote override", f.provider.events[1]["title"])
            assertEquals("FAILED", f.store.operation("race")!!.phase)
        }
    }

    @Test fun graphMembershipAndPrivateChildAssertionsCatchConcurrentInsertionsAndChanges() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            f.provider.beforeBatch = { f.provider.events += exception(13, 4, 40) }
            val result = f.writer.mutate("graph-race", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Blocked"))))
            assertTrue((result as SeriesMutationResult.Common).outcome is MutationResult.Conflict)
            assertEquals("Master", f.provider.events[0]["title"])
            assertEquals(4, f.provider.events.size)
        }
        fixture { f ->
            val editable = f.editable()
            f.provider.beforeBatch = { f.provider.reminders.single { it["_id"] == "51" }["opaque_child"] = "Remote email metadata" }
            val result = f.writer.mutate("child-race", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Blocked"))))
            assertTrue((result as SeriesMutationResult.Common).outcome is MutationResult.Conflict)
            assertEquals("Master", f.provider.events[0]["title"])
            assertEquals("Remote email metadata", f.provider.reminders.single { it["_id"] == "51" }["opaque_child"])
        }
    }

    @Test fun atomicRollbackCanOnlyRetryExactOriginalCommandAfterFreshChecks() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val command = whole(editable, EventPatch(title = FieldChange.Set("Retried"), alertMinutes = FieldChange.Set(listOf(15))))
            f.provider.failReminder = true
            assertEquals(common(MutationResult.Unavailable), f.writer.mutate("retry", selected, editable.token, command))
            assertEquals("Master", f.provider.events[0]["title"])
            assertEquals(listOf("10"), f.provider.reminders.filter { it["event_id"] == "10" && it["method"] == "1" }.map { it["minutes"] })
            assertEquals(common(MutationResult.SafeToRetry("retry")), f.writer.recover("retry"))
            assertTrue((f.writer.mutate("retry", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Changed payload")))) as SeriesMutationResult.Common).outcome is MutationResult.Conflict)
            f.provider.failReminder = false
            assertTrue(f.writer.mutate("retry", selected, editable.token, command) is SeriesMutationResult.LocalSaved)
            assertEquals("Retried", f.provider.events[0]["title"])
            assertEquals(2, f.provider.batchCount)
        }
    }

    @Test fun uncertainProviderAcknowledgementNeverReplaysEvenWhenMatchingFieldsExist() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val command = whole(editable, EventPatch(title = FieldChange.Set("Committed unknown")))
            f.provider.lostAcknowledgement = true
            assertTrue((f.writer.mutate("lost", selected, editable.token, command) as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals("Committed unknown", f.provider.events[0]["title"])
            assertEquals("ATTEMPTED", f.store.operation("lost")!!.phase)
            assertEquals(common(MutationResult.Uncertain("lost", null)), f.writer.recover("lost"))
            assertTrue((f.writer.mutate("lost", selected, editable.token, command) as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun committedReceiptSurvivesDatabaseReopenAndReadOnlyRecovery() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            f.provider.failReadsAfterCommit = true
            val command = whole(editable, EventPatch(title = FieldChange.Set("Known commit")))
            assertTrue((f.writer.mutate("committed", selected, editable.token, command) as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals("COMMITTED", f.store.operation("committed")!!.phase)
            f.provider.failReadsAfterCommit = false
            f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            f.store.close()
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), f.store.databaseName)
            try {
                val source = CalendarSeriesReader(f.resolver, f.reader, reopened, f.permissions::contains)
                val recovered = CalendarSeriesWriter(f.resolver, source, reopened, f.permissions::contains)
                assertEquals(SeriesMutationResult.LocalSaved(SeriesIdentity(CalendarId(2), 10), selected), recovered.recover("committed"))
                assertEquals("VERIFIED", reopened.operation("committed")!!.phase)
                assertEquals(1, f.provider.batchCount)
            } finally { reopened.close() }
        }
    }

    @Test fun repeatedOperationIsIdempotentButKeepAndExplicitDefaultRemainDifferentCommands() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val command = whole(editable, EventPatch(title = FieldChange.Set("Once")))
            val result = f.writer.mutate("same", selected, editable.token, command)
            assertTrue(result is SeriesMutationResult.LocalSaved)
            assertEquals(result, f.writer.mutate("same", selected, editable.token, command))
            assertTrue((f.writer.mutate("same", selected, editable.token, whole(editable,
                EventPatch(title = FieldChange.Set("Once"), privacy = FieldChange.Set(null)))) as SeriesMutationResult.Common).outcome is MutationResult.Conflict)
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun explicitMetadataAndAlertValidationDoesNotRewriteUnknownKeepValues() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            assertEquals(common(MutationResult.ValidationFailed(setOf(InvalidEventField.AVAILABILITY, InvalidEventField.PRIVACY, InvalidEventField.ALERTS))),
                f.writer.mutate("invalid", selected, editable.token, whole(editable, EventPatch(availability = FieldChange.Set(77), privacy = FieldChange.Set(99), alertMinutes = FieldChange.Set(listOf(-1))))))
            assertEquals(0, f.provider.batchCount)
            assertTrue(f.writer.mutate("clear", selected, editable.token, whole(editable, EventPatch(privacy = FieldChange.Set(null), alertMinutes = FieldChange.Set(emptyList())))) is SeriesMutationResult.LocalSaved)
            assertEquals("0", f.provider.events[0]["accessLevel"])
            assertEquals("77", f.provider.events[0]["availability"])
            assertEquals("1", f.provider.events[0]["hasAlarm"])
            assertTrue(f.provider.reminders.filter { it["event_id"] == "10" }.all { it["method"] != "1" })
        }
    }

    @Test fun freshRuntimePermissionsAndCalendarRightsAreCheckedBeforeDispatch() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            f.provider.afterQuery = { path -> if (path == "attendees") f.permissions.remove(Manifest.permission.WRITE_CALENDAR) }
            assertEquals(common(MutationResult.PermissionRequired), f.writer.mutate("revoked", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Blocked")))))
            assertEquals(0, f.provider.batchCount)
            assertNull(f.store.operation("revoked"))
            f.provider.afterQuery = null; f.permissions += Manifest.permission.WRITE_CALENDAR
            f.provider.calendar["calendar_access_level"] = "100"
            f.provider.calls.clear()
            assertEquals(common(MutationResult.Denied(MutationDenial.CALENDAR_READ_ONLY)), f.writer.mutate("readonly", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Blocked")))))
            assertEquals(listOf("calendars"), f.provider.calls.map { it.path })
            f.permissions.clear(); f.provider.calls.clear()
            assertEquals(common(MutationResult.PermissionRequired), f.writer.recover("missing"))
            assertTrue(f.provider.calls.isEmpty())
        }
    }

    @Test fun corruptContradictoryOrMissingJournalEvidenceNeverPermitsRetryOrClaimsSaved() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            val command = whole(editable, EventPatch(title = FieldChange.Set("Receipt")))
            assertTrue(f.writer.mutate("receipt", selected, editable.token, command) is SeriesMutationResult.LocalSaved)
            val raw = f.store.operation("receipt")!!.resultRef!!
            for (payload in listOf("invalid-json", JSONObject(raw).put("scope", "THIS").toString(),
                JSONObject(raw).put("targets", org.json.JSONArray(listOf(11))).toString(),
                JSONObject(raw).apply { getJSONObject("eventUids").put("10", JSONObject.NULL) }.toString())) {
                f.store.writableDatabase.execSQL("UPDATE mutation_journal SET result_ref = ? WHERE id = ?", arrayOf(payload, "receipt"))
                assertTrue((f.writer.recover("receipt") as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            }
            f.store.writableDatabase.execSQL("UPDATE mutation_journal SET result_ref = ? WHERE id = ?", arrayOf(raw, "receipt"))
            f.store.writableDatabase.delete("edit_snapshots", null, null)
            assertTrue((f.writer.recover("receipt") as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals(common(MutationResult.Unavailable), f.writer.recover("missing"))
            f.store.prepare("unknown", "signature", "UNRECOGNIZED_SERIES_KIND")
            assertEquals(common(MutationResult.Uncertain("unknown", null)), f.writer.recover("unknown"))
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun oversizedCompleteBatchAndPathologicalGraphStopBeforeJournalDispatch() = runBlocking {
        fixture { f ->
            f.provider.events[0]["future_private_column"] = "x".repeat(300 * 1024)
            val editable = f.editable()
            assertTrue(f.writer.mutate("oversized", selected, editable.token, whole(editable,
                EventPatch(title = FieldChange.Set("Too large to assert safely")))) is SeriesMutationResult.PlanningReview)
            assertEquals(0, f.provider.batchCount)
            assertNull(f.store.operation("oversized"))
        }
        fixture { f ->
            val editable = f.editable()
            repeat(50) { f.provider.events += exception(20L + it, 10L + it, 100L + it) }
            val limited = CalendarSeriesReader(f.resolver, f.reader, f.store, f.permissions::contains, budget = RecurrencePlanningBudget(maxExceptions = 1))
            val writer = CalendarSeriesWriter(f.resolver, limited, f.store, f.permissions::contains)
            f.provider.calls.clear()
            assertTrue(writer.mutate("graph-budget", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("No prefix")))) is SeriesMutationResult.PlanningReview)
            assertEquals(0, f.provider.calls.count { it.path == "attendees" || it.path == "reminders" })
            assertEquals(0, f.provider.batchCount)
            assertNull(f.store.operation("graph-budget"))
        }
    }

    @Test fun blankIdentityPinsRemainUnknownAndCanBeEnrichedAfterKnownCommit() = runBlocking {
        fixture { f ->
            f.provider.events[1]["_sync_id"] = ""
            f.provider.events[1]["uid2445"] = ""
            val editable = f.editable(moved)
            val result = f.writer.mutate("enrichment", moved, editable.token, SeriesCommand.OccurrenceEdit(EventPatch(title = FieldChange.Set("Known local edit"))))
            assertTrue(result is SeriesMutationResult.LocalSaved)
            f.provider.events[1]["_sync_id"] = "newly-assigned-cloud-id"
            f.provider.events[1]["uid2445"] = "newly-visible-provider-uid"
            assertEquals(result, f.writer.recover("enrichment"))
            assertEquals(1, f.provider.batchCount)
        }
    }

    @Test fun replacementCalendarOrKnownEventUidChangeCannotRecoverOldAccountCommit() = runBlocking {
        fixture { f ->
            val editable = f.editable()
            assertTrue(f.writer.mutate("account", selected, editable.token, whole(editable, EventPatch(title = FieldChange.Set("Old account")))) is SeriesMutationResult.LocalSaved)
            f.provider.calendar["account_name"] = "replacement@example.invalid"
            assertTrue((f.writer.recover("account") as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            f.provider.calendar["account_name"] = "owner@example.invalid"
            f.provider.events[0]["uid2445"] = "replacement-event-uid"
            assertTrue((f.writer.recover("account") as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals(1, f.provider.batchCount)
        }
    }
}
