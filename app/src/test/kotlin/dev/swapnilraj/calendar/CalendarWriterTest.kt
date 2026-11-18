package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarWriterTest {
    private class Provider : ContentProvider() {
        val calendar = mutableMapOf<String, String?>("_id" to "2", "account_name" to "owner@example.invalid", "account_type" to "com.google",
            "ownerAccount" to "owner@example.invalid", "calendar_displayName" to "Synthetic", "calendar_color" to "12345",
            "calendar_timezone" to "Europe/London", "calendar_access_level" to "700", "allowedReminders" to "0,1,2", "maxReminders" to "5",
            "visible" to "1", "sync_events" to "1")
        val events = mutableListOf<MutableMap<String, String?>>()
        val reminders = mutableListOf<MutableMap<String, String?>>()
        val attendees = mutableListOf<MutableMap<String, String?>>()
        var nextId = 10L
        var batchCount = 0
        var failReminder = false
        var lostAcknowledgement = false
        var failReadsAfterCommit = false
        var committed = false
        var beforeBatch: (() -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        private fun table(uri: Uri): MutableList<MutableMap<String, String?>> = when (uri.pathSegments.first()) {
            "events" -> events
            "reminders" -> reminders
            "attendees" -> attendees
            else -> error("Unexpected write table")
        }
        private fun selected(row: Map<String, String?>, uri: Uri, selection: String?, args: Array<out String>?): Boolean {
            uri.pathSegments.lastOrNull()?.toLongOrNull()?.let { if (row["_id"] != it.toString()) return false }
            if (selection == null) return true
            val columns = Regex("([A-Za-z_][A-Za-z_0-9]*)\\s*=\\s*\\?").findAll(selection).map { it.groupValues[1] }.toList()
            return columns.withIndex().all { (index, key) -> row[key] == args?.get(index) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (failReadsAfterCommit && committed) error("Synthetic post-commit provider unavailable")
            val rows: List<Map<String, String?>> = when (uri.pathSegments.first()) {
                "calendars" -> listOf(calendar).filter { selected(it, uri, selection, args) }
                "instances" -> events.filter { it["deleted"] != "1" }.map { event -> event + mapOf(
                    "event_id" to event["_id"], "begin" to event["dtstart"], "end" to event["dtend"],
                    "displayColor" to "12345", "selfAttendeeStatus" to "0") }.filter { row ->
                        val columns = Regex("([A-Za-z_][A-Za-z_0-9]*)\\s*=\\s*\\?").findAll(selection.orEmpty()).map { it.groupValues[1] }.toList()
                        columns.withIndex().all { (index, key) -> row[key] == args?.get(index) }
                    }
                else -> table(uri).filter { selected(it, uri, selection, args) }
            }
            val fields = projection ?: arrayOf("_id")
            return MatrixCursor(fields).apply { rows.forEach { row -> addRow(fields.map { row[it] }.toTypedArray()) } }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            if (uri.pathSegments.first() == "reminders" && failReminder) throw OperationApplicationException("Synthetic rollback")
            val id = nextId++
            val row = mutableMapOf<String, String?>("_id" to id.toString())
            values!!.keySet().forEach { row[it] = values.get(it)?.toString() }
            if (uri.pathSegments.first() == "events") {
                row.putIfAbsent("organizer", "owner@example.invalid"); row["isOrganizer"] = "1"
                row["hasAttendeeData"] = "0"; row["guestsCanSeeGuests"] = "1"; row["deleted"] = "0"; row["eventStatus"] = "1"
            }
            table(uri).add(row)
            return ContentUris.withAppendedId(uri, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            val rows = table(uri).filter { selected(it, uri, selection, args) }
            rows.forEach { row -> values!!.keySet().forEach { row[it] = values.get(it)?.toString() } }
            return rows.size
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val rows = table(uri).filter { selected(it, uri, selection, args) }.toSet()
            if (uri.pathSegments.first() == "events") rows.forEach { it["deleted"] = "1" }
            else table(uri).removeAll(rows)
            return rows.size
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batchCount++
            beforeBatch?.also { beforeBatch = null }?.invoke()
            val oldEvents = events.map { it.toMutableMap() }
            val oldReminders = reminders.map { it.toMutableMap() }
            val oldAttendees = attendees.map { it.toMutableMap() }
            val oldId = nextId
            val results = try { super.applyBatch(operations) } catch (failure: Exception) {
                events.clear(); events.addAll(oldEvents); reminders.clear(); reminders.addAll(oldReminders)
                attendees.clear(); attendees.addAll(oldAttendees); nextId = oldId
                throw failure
            }
            committed = true
            if (lostAcknowledgement) throw IllegalStateException("Synthetic lost acknowledgement after commit")
            return results
        }
    }

    private val draft = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2026-11-17T09:00:00Z"),
        Instant.parse("2026-11-17T10:00:00Z")), ZoneId.of("Europe/London"), "Synthetic appointment", alertMinutes = listOf(10))
    private suspend fun fixture(block: suspend (Provider, CalendarWriter, CalendarLocalStore, MutableSet<String>) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val name = "writer-test-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarWriter(context.contentResolver, reader, store, permissions::contains)
        try { block(provider, writer, store, permissions) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); context.deleteDatabase(name) }
    }

    @Test fun createsUpdatesDeletesAndPreservesUnrepresentedMetadataAndNonAlertReminders() = runBlocking {
        fixture { provider, writer, _, _ ->
            val created = writer.create("create", draft) as MutationResult.LocalSaved
            assertEquals(1, provider.events.size)
            val event = provider.events.single()
            assertEquals("Europe/London", event["eventTimezone"])
            event["sync_data1"] = "unrepresented provider metadata"
            provider.reminders += mutableMapOf("_id" to "99", "event_id" to created.ref.eventId.toString(), "minutes" to "30", "method" to "2")
            val editable = (writer.loadForEdit(created.ref) as EditReadResult.Ready).event
            assertTrue(writer.update("update", created.ref, editable.token, EventPatch(title = FieldChange.Set("Changed"),
                alertMinutes = FieldChange.Set(listOf(15)))) is MutationResult.LocalSaved)
            assertEquals("Changed", event["title"])
            assertEquals("unrepresented provider metadata", event["sync_data1"])
            assertTrue(provider.reminders.any { it["method"] == "2" && it["minutes"] == "30" })
            assertTrue(provider.reminders.any { it["method"] == "1" && it["minutes"] == "15" })
            val fresh = (writer.loadForEdit(created.ref) as EditReadResult.Ready).event
            assertEquals(MutationResult.LocalDeleted(created.ref), writer.delete("delete", created.ref, fresh.token))
            assertEquals("1", event["deleted"])
        }
    }

    @Test fun selfAttendeeIsNotAnotherGuestAndRecurrenceIsExplicitlyDenied() = runBlocking {
        fixture { provider, writer, _, _ ->
            val ref = (writer.create("self-create", draft) as MutationResult.LocalSaved).ref
            provider.events.single()["hasAttendeeData"] = "1"
            provider.attendees += mutableMapOf("_id" to "70", "event_id" to ref.eventId.toString(),
                "attendeeEmail" to "owner@example.invalid", "attendeeRelationship" to "1", "attendeeStatus" to "1", "attendeeType" to "1")
            assertTrue(writer.loadForEdit(ref) is EditReadResult.Ready)
            provider.events.single()["rrule"] = "FREQ=DAILY"
            provider.events.single()["dtend"] = null
            assertEquals(EditReadResult.Denied(MutationDenial.RECURRENCE), writer.loadForEdit(ref))
        }
    }

    @Test fun explicitTimezoneRestrictionAndSplitTimezoneEventsAreRespected() = runBlocking {
        fixture { provider, writer, _, _ ->
            provider.calendar["canModifyTimeZone"] = "0"
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE)), writer.create("wrong-zone", draft.copy(eventZone = ZoneId.of("America/New_York"))))
            val ref = (writer.create("allowed-zone", draft) as MutationResult.LocalSaved).ref
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertFalse(EditableField.ZONE in editable.allowedFields)
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE)), writer.update("change-zone", ref, editable.token,
                EventPatch(eventZone = FieldChange.Set(ZoneId.of("America/New_York")))))
            provider.events.single()["eventEndTimezone"] = "America/New_York"
            assertEquals(EditReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT), writer.loadForEdit(ref))
        }
    }

    @Test fun allDayWritesUtcDatesWithExclusiveEnd() = runBlocking {
        fixture { provider, writer, _, _ ->
            val allDay = draft.copy(time = EventTime.AllDay(CalendarDateRange(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 19))), eventZone = null)
            assertTrue(writer.create("all-day", allDay) is MutationResult.LocalSaved)
            val event = provider.events.single()
            assertEquals("UTC", event["eventTimezone"])
            assertEquals(Instant.parse("2026-11-17T00:00:00Z").toEpochMilli().toString(), event["dtstart"])
            assertEquals(Instant.parse("2026-11-19T00:00:00Z").toEpochMilli().toString(), event["dtend"])
        }
    }

    @Test fun repeatedOperationIdReturnsRecordedOutcomeWithoutAnotherWrite() = runBlocking {
        fixture { provider, writer, _, _ ->
            val result = writer.create("same-id", draft)
            assertTrue(result is MutationResult.LocalSaved)
            assertEquals(result, writer.create("same-id", draft))
            assertEquals(1, provider.batchCount)
            assertTrue(writer.create("same-id", draft.copy(title = "Different")) is MutationResult.Conflict)
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun lostAcknowledgementRemainsUncertainAcrossWriterAndStoreRecreation() = runBlocking {
        fixture { provider, writer, store, permissions ->
            provider.lostAcknowledgement = true
            assertEquals(MutationResult.Uncertain("uncertain", null), writer.create("uncertain", draft))
            assertEquals(1, provider.events.size)
            provider.lostAcknowledgement = false
            val context = RuntimeEnvironment.getApplication()
            val reopened = CalendarLocalStore(context, store.databaseName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val second = CalendarWriter(context.contentResolver, CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0), reopened, permissions::contains)
                assertEquals(MutationResult.Uncertain("uncertain", null), second.create("uncertain", draft))
                assertEquals(1, provider.batchCount)
                assertEquals(1, provider.events.size)
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); reopened.close() }
        }
    }

    @Test fun knownCommittedResultCanBeRecoveredWithoutAnotherProviderWrite() = runBlocking {
        fixture { provider, writer, _, _ ->
            provider.failReadsAfterCommit = true
            val uncertain = writer.create("committed", draft) as MutationResult.Uncertain
            assertNotNull(uncertain.knownEventId)
            assertEquals(1, provider.batchCount)
            provider.failReadsAfterCommit = false
            val recovered = writer.recover("committed") as MutationResult.LocalSaved
            assertEquals(uncertain.knownEventId, recovered.ref.eventId)
            assertEquals(recovered, writer.recover("committed"))
            assertEquals(1, provider.batchCount)
            assertEquals(1, provider.events.size)
        }
    }

    @Test fun journalRecoveryDistinguishesDefiniteFailureFromCorruptCommittedMetadata() = runBlocking {
        fixture { provider, writer, store, _ ->
            store.prepare("prepared", "test-signature", "CREATE")
            assertEquals(MutationResult.SafeToRetry("prepared"), writer.recover("prepared"))
            store.mark("prepared", "FAILED")
            assertEquals(MutationResult.SafeToRetry("prepared"), writer.recover("prepared"))
            store.mark("prepared", "COMMITTED", "not-json")
            assertEquals(MutationResult.Uncertain("prepared", null), writer.recover("prepared"))
            assertEquals(0, provider.batchCount)
        }
    }

    @Test fun journalConnectionsUseFullSynchronousDurability() = runBlocking {
        fixture { _, _, store, _ ->
            fun assertFull(database: android.database.sqlite.SQLiteDatabase) {
                database.rawQuery("PRAGMA synchronous", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(2, it.getInt(0))
                }
            }
            assertFull(store.writableDatabase)
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), store.databaseName)
            try { assertFull(reopened.writableDatabase) } finally { reopened.close() }
        }
    }

    @Test fun preDispatchJournalFailureOffersOnlyAnExplicitSameCommandRetry() = runBlocking {
        fixture { provider, writer, store, permissions ->
            // Simulate failure after PREPARED is durable but before ATTEMPTED can be claimed.
            store.writableDatabase.execSQL("""CREATE TRIGGER interrupt_dispatch BEFORE UPDATE OF phase ON mutation_journal
                WHEN NEW.phase = 'ATTEMPTED' BEGIN SELECT RAISE(FAIL, 'Synthetic pre-dispatch interruption'); END""")
            assertEquals(MutationResult.Unavailable, writer.create("prepared-retry", draft))
            assertEquals(0, provider.batchCount)
            assertTrue(provider.events.isEmpty())
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.SafeToRetry("prepared-retry"), writer.recover("prepared-retry"))
            assertEquals(0, provider.batchCount) // Recovery never dispatches.
            assertEquals(MutationResult.PermissionRequired, writer.create("prepared-retry", draft))
            permissions += Manifest.permission.WRITE_CALENDAR
            store.writableDatabase.execSQL("DROP TRIGGER interrupt_dispatch")
            assertTrue(writer.create("prepared-retry", draft.copy(title = "Altered payload")) is MutationResult.Conflict)
            assertEquals(0, provider.batchCount)
            assertTrue(writer.create("prepared-retry", draft) is MutationResult.LocalSaved)
            assertEquals(1, provider.batchCount)
            assertEquals(1, provider.events.size)
        }
    }

    @Test fun rolledBackBatchOffersRetryButUnknownAndContradictoryJournalStatesDoNot() = runBlocking {
        fixture { provider, writer, store, _ ->
            provider.failReminder = true
            assertEquals(MutationResult.Unavailable, writer.create("failed-retry", draft))
            assertTrue(provider.events.isEmpty())
            assertEquals(MutationResult.SafeToRetry("failed-retry"), writer.recover("failed-retry"))
            assertEquals(1, provider.batchCount)
            provider.failReminder = false
            assertTrue(writer.create("failed-retry", draft) is MutationResult.LocalSaved)
            assertEquals(2, provider.batchCount)
            assertEquals(1, provider.events.size)

            assertEquals(MutationResult.Unavailable, writer.recover("missing-operation"))
            store.prepare("unknown", "synthetic-signature", "CREATE")
            store.mark("unknown", "ATTEMPTED")
            assertEquals(MutationResult.Uncertain("unknown", null), writer.recover("unknown"))
            store.mark("unknown", "FUTURE_UNRECOGNIZED_PHASE")
            assertEquals(MutationResult.Uncertain("unknown", null), writer.recover("unknown"))
            store.mark("unknown", "PREPARED", "not-valid-result-metadata")
            assertEquals(MutationResult.Uncertain("unknown", null), writer.recover("unknown"))
            store.prepare("unknown-kind", "synthetic-signature", "UNRECOGNIZED_KIND")
            assertEquals(MutationResult.Uncertain("unknown-kind", null), writer.recover("unknown-kind"))
            assertEquals(2, provider.batchCount)
            assertEquals(1, provider.events.size)
        }
    }

    @Test fun safeRetryDoesNotBypassAnEditTokenConflict() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("existing", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val patch = EventPatch(title = FieldChange.Set("Desired edit"))
            store.writableDatabase.execSQL("""CREATE TRIGGER interrupt_dispatch BEFORE UPDATE OF phase ON mutation_journal
                WHEN NEW.phase = 'ATTEMPTED' BEGIN SELECT RAISE(FAIL, 'Synthetic pre-dispatch interruption'); END""")
            assertEquals(MutationResult.Unavailable, writer.update("retry-edit", ref, token, patch))
            assertEquals(MutationResult.SafeToRetry("retry-edit"), writer.recover("retry-edit"))
            assertEquals(1, provider.batchCount)
            provider.events.single()["title"] = "Remote edit while interrupted"
            store.writableDatabase.execSQL("DROP TRIGGER interrupt_dispatch")
            assertTrue(writer.update("retry-edit", ref, token, patch) is MutationResult.Conflict)
            assertEquals(1, provider.batchCount)
            assertEquals("Remote edit while interrupted", provider.events.single()["title"])
        }
    }

    @Test fun sourceChangesAndRaceInsideBatchAreConflicts() = runBlocking {
        fixture { provider, writer, _, _ ->
            val ref = (writer.create("create", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            provider.events.single()["title"] = "Remote change"
            assertTrue(writer.update("stale", ref, token, EventPatch(title = FieldChange.Set("Overwrite"))) is MutationResult.Conflict)
            val fresh = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            provider.beforeBatch = { provider.events.single()["description"] = "Concurrent edit" }
            assertTrue(writer.update("race", ref, fresh, EventPatch(title = FieldChange.Set("Overwrite"))) is MutationResult.Conflict)
            assertEquals("Remote change", provider.events.single()["title"])
            assertEquals("Concurrent edit", provider.events.single()["description"])
        }
    }

    @Test fun failedReminderInsertRollsBackTheEventAndCanBeRetriedExplicitly() = runBlocking {
        fixture { provider, writer, _, _ ->
            provider.failReminder = true
            assertEquals(MutationResult.Unavailable, writer.create("rollback", draft))
            assertTrue(provider.events.isEmpty())
            assertTrue(provider.reminders.isEmpty())
            provider.failReminder = false
            assertTrue(writer.create("rollback", draft) is MutationResult.LocalSaved)
            assertEquals(1, provider.events.size)
        }
    }

    @Test fun readOnlyPermissionCanDiscoverEditEligibilityAndRecoverWithoutWriting() = runBlocking {
        fixture { provider, writer, store, permissions ->
            // Seed an existing appointment without using the mutation adapter under test.
            val uri = provider.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, 2L)
                put(CalendarContract.Events.DTSTART, Instant.parse("2026-11-17T09:00:00Z").toEpochMilli())
                put(CalendarContract.Events.DTEND, Instant.parse("2026-11-17T10:00:00Z").toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, "Europe/London")
                put(CalendarContract.Events.TITLE, "Synthetic existing appointment")
                put(CalendarContract.Events.ALL_DAY, 0)
            })
            val ref = EventRef(CalendarId(2), ContentUris.parseId(uri), Instant.parse("2026-11-17T09:00:00Z"), Instant.parse("2026-11-17T10:00:00Z"))
            store.prepare("known", "fixture-signature", "CREATE")
            store.mark("known", "COMMITTED", "{\"calendar\":2,\"event\":${ref.eventId},\"start\":\"${ref.instanceStart}\",\"end\":\"${ref.instanceEnd}\"}")
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertEquals(EditableField.entries.toSet(), editable.allowedFields)
            assertEquals(editable.token, (writer.loadForEdit(ref) as EditReadResult.Ready).event.token)
            assertEquals(MutationResult.LocalSaved(ref), writer.recover("known"))
            assertEquals(MutationResult.PermissionRequired, writer.create("read-only-create", draft))
            assertEquals(MutationResult.PermissionRequired, writer.update("read-only-update", ref, editable.token, EventPatch(title = FieldChange.Set("No write"))))
            assertEquals(MutationResult.PermissionRequired, writer.delete("read-only-delete", ref, editable.token))
            assertEquals(0, provider.batchCount)
            assertEquals("Synthetic existing appointment", provider.events.single()["title"])
            permissions.remove(Manifest.permission.READ_CALENDAR)
            permissions += Manifest.permission.WRITE_CALENDAR
            assertEquals(EditReadResult.PermissionRequired, writer.loadForEdit(ref))
            assertEquals(MutationResult.PermissionRequired, writer.recover("known"))
            assertEquals(MutationResult.PermissionRequired, writer.create("no-read-create", draft))
            assertEquals(MutationResult.PermissionRequired, writer.update("no-read-update", ref, editable.token, EventPatch()))
            assertEquals(MutationResult.PermissionRequired, writer.delete("no-read-delete", ref, editable.token))
            assertEquals(0, provider.batchCount)
        }
    }

    @Test fun permissionsInvalidOffsetsAndUnknownRightsNeverWrite() = runBlocking {
        fixture { provider, writer, _, permissions ->
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.PermissionRequired, writer.create("permission", draft))
            permissions += Manifest.permission.WRITE_CALENDAR
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ALERTS)), writer.create("sentinel", draft.copy(alertMinutes = listOf(-1))))
            provider.calendar["calendar_access_level"] = null
            assertEquals(MutationResult.Denied(MutationDenial.CALENDAR_READ_ONLY), writer.create("acl", draft))
            assertEquals(0, provider.batchCount)
        }
    }

    @Test fun invitationsAndUnknownGuestVisibilityAreNotEditedAsPrivateAppointments() = runBlocking {
        fixture { provider, writer, _, _ ->
            val ref = (writer.create("create", draft) as MutationResult.LocalSaved).ref
            provider.events.single()["organizer"] = "other@example.invalid"
            assertEquals(EditReadResult.Denied(MutationDenial.OWNERSHIP_UNKNOWN), writer.loadForEdit(ref))
            provider.events.single()["organizer"] = "owner@example.invalid"
            provider.events.single()["hasAttendeeData"] = "1"
            provider.events.single()["guestsCanSeeGuests"] = "0"
            assertEquals(EditReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT), writer.loadForEdit(ref))
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun draftsRetainVersionAndInvalidRawInputAcrossDatabaseReopen() = runBlocking {
        fixture { _, _, store, _ ->
            val raw = DraftRecord("unfinished", 7, "{\"end\":\"invalid\",\"title\":\"\"}", Instant.parse("2026-11-17T12:00:00Z"))
            store.upsert(raw)
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), store.databaseName)
            try {
                assertEquals(raw, reopened.load(raw.id))
                assertEquals(listOf(raw), reopened.list())
                val later = raw.copy(id = "later", updatedAt = raw.updatedAt.plusNanos(1))
                reopened.upsert(later)
                assertEquals(listOf(later, raw), reopened.list())
                reopened.delete(raw.id)
                assertNull(store.load(raw.id))
            } finally { reopened.close() }
        }
    }
}
