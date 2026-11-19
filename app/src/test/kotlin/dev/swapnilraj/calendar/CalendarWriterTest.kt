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
            "visible" to "1", "sync_events" to "1", "allowedAvailability" to "0,1,2", "_sync_id" to "calendar-A")
        val events = mutableListOf<MutableMap<String, String?>>()
        val reminders = mutableListOf<MutableMap<String, String?>>()
        val attendees = mutableListOf<MutableMap<String, String?>>()
        var nextId = 10L
        var batchCount = 0
        var failReminder = false
        var lostAcknowledgement = false
        var malformedReminderAcknowledgement = false
        var failReadsAfterCommit = false
        var committed = false
        var beforeBatch: (() -> Unit)? = null
        var onQuery: (() -> Unit)? = null
        val queryCounts = mutableMapOf<String, Int>()
        fun resetQueryCounts() = queryCounts.clear()
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
            val parent = row["event_id"]?.let { id -> events.singleOrNull { it["_id"] == id } }
            fun value(key: String) = if (row.containsKey(key)) row[key] else parent?.get(key)
            if (Regex("([A-Za-z_][A-Za-z_0-9]*)\\s+IS NULL").findAll(selection).any { value(it.groupValues[1]) != null }) return false
            return columns.withIndex().all { (index, key) -> value(key) == args?.get(index) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            onQuery?.invoke()
            if (failReadsAfterCommit && committed) error("Synthetic post-commit provider unavailable")
            val tableName = uri.pathSegments.first()
            queryCounts[tableName] = (queryCounts[tableName] ?: 0) + 1
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
            return if (malformedReminderAcknowledgement) results.map { result ->
                if (result.uri?.pathSegments?.firstOrNull() == "reminders") ContentProviderResult(Uri.parse("content://foreign/reminders/999")) else result
            }.toTypedArray() else results
        }
    }

    private val draft = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2026-11-17T09:00:00Z"),
        Instant.parse("2026-11-17T10:00:00Z")), ZoneId.of("Europe/London"), "Synthetic appointment", alertMinutes = listOf(10),
        targetIdentity = CalendarIdentity(CalendarId(2), CalendarAccount("owner@example.invalid", "com.google"), "owner@example.invalid", "calendar-A"))
    private suspend fun fixture(readBudgets: ProviderReadBudgets = ProviderReadBudgets(), block: suspend (Provider, CalendarWriter, CalendarLocalStore, MutableSet<String>) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val name = "writer-test-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, name, readBudgets = readBudgets)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0)
        val writer = CalendarWriter(context.contentResolver, reader, store, permissions::contains, readBudgets = readBudgets)
        try { block(provider, writer, store, permissions) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); store.close(); context.deleteDatabase(name) }
    }

    @Test fun mismatchingAuthoredPatternNeverFallsBackToOneOffOrStartsJournal() = runBlocking {
        fixture { provider, writer, _, _ ->
            val result = writer.create("unsupported-series", draft.copy(recurrence = RecurrenceSpec(RecurrencePattern.Weekly(setOf(java.time.DayOfWeek.MONDAY)))))
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.RECURRENCE)), result)
            assertEquals(0, provider.batchCount)
            assertTrue(provider.events.isEmpty())
            assertEquals(MutationResult.Unavailable, writer.recover("unsupported-series"))
        }
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

    @Test fun legacyUnboundJournalNeverOffersRetryOrAcknowledgesCommittedMetadata() = runBlocking {
        fixture { provider, writer, store, _ ->
            store.prepare("prepared", "test-signature", "CREATE")
            assertEquals(MutationResult.Uncertain("prepared", null), writer.recover("prepared"))
            store.forceOperationForTest("prepared", "FAILED")
            assertEquals(MutationResult.Uncertain("prepared", null), writer.recover("prepared"))
            store.forceOperationForTest("prepared", "COMMITTED", "not-json")
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
            store.forceOperationForTest("unknown", "ATTEMPTED")
            assertEquals(MutationResult.Uncertain("unknown", null), writer.recover("unknown"))
            store.forceOperationForTest("unknown", "FUTURE_UNRECOGNIZED_PHASE")
            assertEquals(MutationResult.Uncertain("unknown", null), writer.recover("unknown"))
            store.forceOperationForTest("unknown", "PREPARED", "not-valid-result-metadata")
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
            val ref = (writer.create("known", draft) as MutationResult.LocalSaved).ref
            provider.batchCount = 0
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertEquals(EditableField.entries.toSet(), editable.allowedFields)
            assertEquals(editable.token, (writer.loadForEdit(ref) as EditReadResult.Ready).event.token)
            assertEquals(MutationResult.LocalSaved(ref), writer.recover("known"))
            assertEquals(MutationResult.PermissionRequired, writer.create("read-only-create", draft))
            assertEquals(MutationResult.PermissionRequired, writer.update("read-only-update", ref, editable.token, EventPatch(title = FieldChange.Set("No write"))))
            assertEquals(MutationResult.PermissionRequired, writer.delete("read-only-delete", ref, editable.token))
            assertEquals(0, provider.batchCount)
            assertEquals(draft.title, provider.events.single()["title"])
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

    @Test fun freeBusyEligibilityStopsBeforeAnyPrivateEventOrChildQuery() = runBlocking {
        fixture { provider, writer, _, permissions ->
            val ref = (writer.create("seed-free-busy", draft) as MutationResult.LocalSaved).ref
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            provider.calendar["calendar_access_level"] = CalendarContract.Calendars.CAL_ACCESS_FREEBUSY.toString()
            provider.resetQueryCounts()
            assertEquals(EditReadResult.Denied(MutationDenial.CALENDAR_READ_ONLY), writer.loadForEdit(ref))
            assertEquals(1, provider.queryCounts["calendars"])
            assertEquals(0, provider.queryCounts["events"] ?: 0)
            assertEquals(0, provider.queryCounts["reminders"] ?: 0)
            assertEquals(0, provider.queryCounts["attendees"] ?: 0)
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun unknownGuestVisibilityDoesNotTrustEmailOrNoAttendeeFlag() = runBlocking {
        for (flag in listOf<String?>(null, "0", "2")) fixture { provider, writer, _, permissions ->
            val ref = (writer.create("seed-unknown", draft) as MutationResult.LocalSaved).ref
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            provider.events.single()["isOrganizer"] = flag
            provider.events.single()["guestsCanSeeGuests"] = flag
            provider.events.single()["hasAttendeeData"] = "0"
            // An inconsistent provider can still contain a guest; do not use the zero flag as proof.
            provider.attendees += mutableMapOf("_id" to "90", "event_id" to ref.eventId.toString(),
                "attendeeEmail" to "hidden@example.invalid", "attendeeRelationship" to "1")
            provider.resetQueryCounts()
            assertTrue(writer.loadForEdit(ref) is EditReadResult.Denied)
            assertEquals(0, provider.queryCounts["reminders"] ?: 0)
            assertEquals(0, provider.queryCounts["attendees"] ?: 0)
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun explicitOrganizerOrVisibleGuestsPermitAuthoritativeChildChecks() = runBlocking {
        for ((organizerFlag, visibilityFlag) in listOf("1" to "0", null to "1")) fixture { provider, writer, _, permissions ->
            val ref = (writer.create("seed-proven", draft) as MutationResult.LocalSaved).ref
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            provider.events.single()["isOrganizer"] = organizerFlag
            provider.events.single()["guestsCanSeeGuests"] = visibilityFlag
            provider.resetQueryCounts()
            assertTrue(writer.loadForEdit(ref) is EditReadResult.Ready)
            assertTrue((provider.queryCounts["attendees"] ?: 0) > 0)
            assertTrue((provider.queryCounts["reminders"] ?: 0) > 0)
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun staleTokenAfterCalendarDowngradeDoesNotTriggerPrivateSourceQueries() = runBlocking {
        fixture { provider, writer, _, _ ->
            val ref = (writer.create("seed-downgrade", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            provider.calendar["calendar_access_level"] = CalendarContract.Calendars.CAL_ACCESS_FREEBUSY.toString()
            provider.resetQueryCounts()
            assertEquals(MutationResult.Denied(MutationDenial.CALENDAR_READ_ONLY), writer.update("blocked-update", ref, token,
                EventPatch(title = FieldChange.Set("Must not write"))))
            assertEquals(0, provider.queryCounts["events"] ?: 0)
            assertEquals(0, provider.queryCounts["reminders"] ?: 0)
            assertEquals(0, provider.queryCounts["attendees"] ?: 0)
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun availabilityAndPrivacyRoundTripAndExplicitNullResetToProviderDefaults() = runBlocking {
        fixture { provider, writer, _, _ ->
            val ref = (writer.create("metadata-create", draft.copy(availability = 2, privacy = 2)) as MutationResult.LocalSaved).ref
            var editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertEquals(2, editable.details.availability)
            assertEquals(2, editable.details.privacy)
            assertTrue(EditableField.AVAILABILITY in editable.allowedFields)
            assertTrue(EditableField.PRIVACY in editable.allowedFields)
            assertTrue(writer.update("metadata-update", ref, editable.token, EventPatch(
                availability = FieldChange.Set(1), privacy = FieldChange.Set(3))) is MutationResult.LocalSaved)
            editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertEquals(1, editable.details.availability)
            assertEquals(3, editable.details.privacy)
            assertTrue(writer.update("metadata-reset", ref, editable.token, EventPatch(
                availability = FieldChange.Set(null), privacy = FieldChange.Set(null))) is MutationResult.LocalSaved)
            assertEquals("0", provider.events.single()["availability"])
            assertEquals("0", provider.events.single()["accessLevel"])
        }
    }

    @Test fun unknownMetadataKeepSurvivesOtherEditsAndExplicitUnknownWritesAreRejected() = runBlocking {
        fixture { provider, writer, _, _ ->
            val ref = (writer.create("metadata-seed", draft) as MutationResult.LocalSaved).ref
            val event = provider.events.single()
            assertFalse(event.containsKey("availability"))
            assertFalse(event.containsKey("accessLevel"))
            event["availability"] = "77"; event["accessLevel"] = "88"
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertEquals(77, editable.details.availability)
            assertEquals(88, editable.details.privacy)
            assertTrue(writer.update("keep-unknown", ref, editable.token, EventPatch(
                title = FieldChange.Set("Still unknown"), alertMinutes = FieldChange.Set(listOf(15)))) is MutationResult.LocalSaved)
            assertEquals("77", event["availability"])
            assertEquals("88", event["accessLevel"])
            val fresh = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            val batches = provider.batchCount
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.AVAILABILITY, InvalidEventField.PRIVACY)),
                writer.update("reject-unknown", ref, fresh.token, EventPatch(availability = FieldChange.Set(77), privacy = FieldChange.Set(88))))
            assertEquals(batches, provider.batchCount)
        }
    }

    @Test fun explicitAvailabilityRequiresKnownCalendarPolicyWhilePrivacyUsesWritableRights() = runBlocking {
        for (raw in listOf<String?>(null, "", "0,broken", "0,99")) fixture { provider, writer, _, _ ->
            provider.calendar["allowedAvailability"] = raw
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.AVAILABILITY)),
                writer.create("unknown-policy-create", draft.copy(availability = 0)))
            assertEquals(0, provider.batchCount)
            val ref = (writer.create("privacy-create", draft.copy(privacy = 2)) as MutationResult.LocalSaved).ref
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertFalse(EditableField.AVAILABILITY in editable.allowedFields)
            assertTrue(EditableField.PRIVACY in editable.allowedFields)
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.AVAILABILITY)),
                writer.update("unknown-policy-update", ref, editable.token, EventPatch(availability = FieldChange.Set(null))))
            assertEquals(1, provider.batchCount)
            assertTrue(writer.update("privacy-only", ref, editable.token, EventPatch(privacy = FieldChange.Set(3))) is MutationResult.LocalSaved)
            assertEquals("3", provider.events.single()["accessLevel"])
        }
        fixture { provider, writer, _, _ ->
            provider.calendar["allowedAvailability"] = "1"
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.AVAILABILITY)), writer.create("not-allowed", draft.copy(availability = 0)))
            assertTrue(writer.create("allowed", draft.copy(availability = 1)) is MutationResult.LocalSaved)
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.PRIVACY)), writer.create("bad-privacy", draft.copy(privacy = -1)))
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun metadataAndCalendarAvailabilityChangesInvalidateTokensAndAtomicAssertions() = runBlocking {
        fixture { provider, writer, _, _ ->
            val ref = (writer.create("conflict-seed", draft.copy(availability = 0, privacy = 2)) as MutationResult.LocalSaved).ref
            var editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            provider.events.single()["accessLevel"] = "3"
            assertTrue(writer.update("changed-privacy", ref, editable.token, EventPatch(title = FieldChange.Set("Blocked"))) is MutationResult.Conflict)
            assertEquals(1, provider.batchCount)
            editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            provider.calendar["allowedAvailability"] = "0,1"
            assertTrue(writer.update("changed-policy", ref, editable.token, EventPatch(availability = FieldChange.Set(1))) is MutationResult.Conflict)
            assertEquals(1, provider.batchCount)
            editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            provider.beforeBatch = { provider.calendar["allowedAvailability"] = "0" }
            assertTrue(writer.update("policy-race", ref, editable.token, EventPatch(availability = FieldChange.Set(1))) is MutationResult.Conflict)
            assertEquals("0", provider.events.single()["availability"])
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun metadataChangesStillRequireRuntimeWriteAndCalendarWriteRights() = runBlocking {
        fixture { provider, writer, _, permissions ->
            val ref = (writer.create("rights-seed", draft.copy(privacy = 2)) as MutationResult.LocalSaved).ref
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertTrue(EditableField.PRIVACY in editable.allowedFields)
            assertTrue(EditableField.AVAILABILITY in editable.allowedFields)
            assertEquals(MutationResult.PermissionRequired, writer.update("no-write", ref, editable.token,
                EventPatch(availability = FieldChange.Set(1), privacy = FieldChange.Set(3))))
            permissions += Manifest.permission.WRITE_CALENDAR
            provider.calendar["calendar_access_level"] = "200"
            assertEquals(MutationResult.Denied(MutationDenial.CALENDAR_READ_ONLY), writer.update("read-calendar", ref, editable.token,
                EventPatch(privacy = FieldChange.Set(3))))
            assertEquals(1, provider.batchCount)
            assertEquals("2", provider.events.single()["accessLevel"])
        }
    }

    @Test fun boundCreateSignatureIncludesNamespaceWhileKeepRetainsUpdateSignature() = runBlocking {
        fixture { provider, writer, store, _ ->
            fun refPayload(ref: EventRef) = org.json.JSONObject().put("calendar", ref.calendarId.value).put("event", ref.eventId)
                .put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString())
            fun signature(kind: String, payload: org.json.JSONObject) = java.security.MessageDigest.getInstance("SHA-256")
                .digest((kind + payload.toString()).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val time = draft.time as EventTime.Timed
            val oldCreate = refPayload(EventRef(draft.calendarId, 0, time.start, time.end))
                .put("allDay", false).put("zone", draft.eventZone!!.id).put("title", draft.title)
                .put("location", draft.location).put("description", draft.description).put("alerts", org.json.JSONArray(draft.alertMinutes))
                .put("targetIdentity", org.json.JSONObject().put("calendar", 2).put("name", "owner@example.invalid")
                    .put("type", "com.google").put("owner", "owner@example.invalid").put("providerId", "calendar-A"))
            val ref = (writer.create("legacy-create", draft) as MutationResult.LocalSaved).ref
            assertEquals(signature("CREATE", oldCreate), store.operation("legacy-create")!!.signature)
            assertTrue(writer.create("legacy-create", draft.copy(availability = null, privacy = null)) is MutationResult.LocalSaved)
            assertEquals(1, provider.batchCount)
            assertTrue(writer.create("legacy-create", draft.copy(privacy = 0)) is MutationResult.Conflict)
            val editable = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            val oldUpdate = refPayload(ref).put("token", editable.token.value).put("title", "Legacy edit")
            assertTrue(writer.update("legacy-update", ref, editable.token, EventPatch(title = FieldChange.Set("Legacy edit"))) is MutationResult.LocalSaved)
            assertEquals(signature("UPDATE", oldUpdate), store.operation("legacy-update")!!.signature)
            assertTrue(writer.update("legacy-update", ref, editable.token, EventPatch(title = FieldChange.Set("Legacy edit"), privacy = FieldChange.Set(null))) is MutationResult.Conflict)
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun createRejectsUnboundUnknownOrReusedCalendarNamespacesBeforeJournal() = runBlocking {
        fixture { provider, writer, store, _ ->
            val invalid = MutationResult.ValidationFailed(setOf(InvalidEventField.CALENDAR))
            val variants = listOf(draft.copy(targetIdentity = null),
                draft.copy(targetIdentity = draft.targetIdentity!!.copy(calendarId = CalendarId(3))),
                draft.copy(targetIdentity = draft.targetIdentity!!.copy(account = CalendarAccount("", "com.google"))),
                draft.copy(targetIdentity = draft.targetIdentity!!.copy(account = CalendarAccount("owner@example.invalid", ""))))
            variants.forEachIndexed { index, value ->
                assertEquals(invalid, writer.create("invalid-$index", value))
                assertNull(store.operation("invalid-$index"))
            }
            listOf("account_name", "account_type", "ownerAccount", "_sync_id").forEach { field ->
                val old = provider.calendar[field]
                provider.calendar[field] = "replacement"
                assertEquals(invalid, writer.create("reused-$field", draft))
                assertNull(store.operation("reused-$field"))
                provider.calendar[field] = old
            }
            provider.calendar["_sync_id"] = null
            assertEquals(invalid, writer.create("uid-dropped", draft))
            assertNull(store.operation("uid-dropped"))
            assertEquals(0, provider.batchCount)
            assertEquals(0, provider.queryCounts["events"] ?: 0)
        }
    }

    @Test fun nullableUidMustMatchExactlyWhileCosmeticMetadataDoesNotChangeDestination() = runBlocking {
        fixture { provider, writer, store, _ ->
            provider.calendar["_sync_id"] = null
            val unEnriched = draft.copy(targetIdentity = draft.targetIdentity!!.copy(providerId = null))
            assertTrue(writer.create("local", unEnriched) is MutationResult.LocalSaved)
            provider.calendar["_sync_id"] = "enriched"
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.CALENDAR)), writer.create("enrichment", unEnriched))
            assertNull(store.operation("enrichment"))
            provider.calendar["_sync_id"] = "calendar-A"
            provider.calendar["calendar_displayName"] = "Renamed"
            provider.calendar["calendar_color"] = "987"
            provider.calendar["visible"] = "0"
            assertTrue(writer.create("cosmetic", draft) is MutationResult.LocalSaved)
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun failedFrozenCreateCannotAdoptReplacementAccountAndPreparedProofIsAtomic() = runBlocking {
        fixture { provider, writer, store, _ ->
            provider.failReminder = true
            assertEquals(MutationResult.Unavailable, writer.create("failed", draft))
            val before = store.operation("failed")!!
            assertTrue(before.resultRef!!.contains("targetIdentity"))
            assertEquals(MutationResult.SafeToRetry("failed"), writer.recover("failed"))
            provider.calendar["account_name"] = "other@example.invalid"
            provider.failReminder = false
            assertEquals(MutationResult.Conflict(null), writer.create("failed", draft))
            assertEquals(before, store.operation("failed"))
            assertEquals(1, provider.batchCount)
            assertTrue(provider.events.isEmpty())
        }
    }

    @Test fun everyCommittedReceiptRefusesCalendarNamespaceReuseBeforePrivateReads() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("created", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            assertTrue(writer.update("updated", ref, token, EventPatch(title = FieldChange.Set("Changed"))) is MutationResult.LocalSaved)
            val fresh = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            assertTrue(writer.delete("deleted", ref, fresh) is MutationResult.LocalDeleted)
            val legacy = org.json.JSONObject().put("calendar", 2).put("event", ref.eventId)
                .put("start", ref.instanceStart.toString()).put("end", ref.instanceEnd.toString())
            store.prepare("legacy", "legacy", "DELETE")
            store.forceOperationForTest("legacy", "COMMITTED", legacy.toString())
            provider.calendar["account_name"] = "replacement@example.invalid"
            provider.calendar["_sync_id"] = "replacement"
            provider.resetQueryCounts()
            listOf("created", "updated", "deleted", "legacy").forEach {
                assertEquals(MutationResult.Uncertain(it, null), writer.recover(it))
            }
            assertEquals(0, provider.queryCounts["events"] ?: 0)
            assertEquals(3, provider.batchCount)
        }
    }

    @Test fun matchingBoundReceiptRecoversReadOnlyAfterReopenButLegacyPlainRefDoesNot() = runBlocking {
        fixture { provider, writer, store, permissions ->
            val ref = (writer.create("created", draft) as MutationResult.LocalSaved).ref
            val receipt = org.json.JSONObject(store.operation("created")!!.resultRef!!)
            store.prepare("legacy", "old-signature", "CREATE")
            store.forceOperationForTest("legacy", "COMMITTED", receipt.getJSONObject("firstRef").toString())
            val reopened = CalendarLocalStore(RuntimeEnvironment.getApplication(), store.databaseName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                permissions.remove(Manifest.permission.WRITE_CALENDAR)
                val resolver = RuntimeEnvironment.getApplication().contentResolver
                val second = CalendarWriter(resolver, CalendarReader(resolver, scope, stopTimeoutMillis = 0), reopened, permissions::contains)
                assertEquals(MutationResult.LocalSaved(ref), second.recover("created"))
                assertEquals(MutationResult.Uncertain("legacy", null), second.recover("legacy"))
                assertEquals(1, provider.batchCount)
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); reopened.close() }
        }
    }

    @Test fun namespaceAssertionPreventsCreateWhenAccountChangesAtDispatch() = runBlocking {
        fixture { provider, writer, store, _ ->
            provider.beforeBatch = { provider.calendar["account_name"] = "replacement@example.invalid" }
            assertEquals(MutationResult.Unavailable, writer.create("race", draft))
            assertEquals("FAILED", store.operation("race")!!.phase)
            assertTrue(provider.events.isEmpty())
            assertTrue(provider.reminders.isEmpty())
            assertEquals(1, provider.batchCount)
            val frozen = store.operation("race")!!
            assertEquals(MutationResult.Conflict(null), writer.create("race", draft))
            assertEquals(frozen, store.operation("race"))
            assertEquals(1, provider.batchCount)
        }
    }

    @Test fun foreignDeletedEventIdAndForgedReceiptNeverAcknowledgeSuccess() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("created", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            assertTrue(writer.delete("deleted", ref, token) is MutationResult.LocalDeleted)
            provider.events.single()["calendar_id"] = "99"
            assertEquals(MutationResult.Uncertain("deleted", null), writer.recover("deleted"))
            val receipt = org.json.JSONObject(store.operation("created")!!.resultRef!!)
            receipt.getJSONObject("identity").put("name", "replacement@example.invalid")
            store.forceOperationForTest("created", "COMMITTED", receipt.toString())
            assertEquals(MutationResult.Uncertain("created", null), writer.recover("created"))
            receipt.getJSONObject("identity").put("name", "owner@example.invalid").remove("providerId")
            store.forceOperationForTest("created", "COMMITTED", receipt.toString())
            assertEquals(MutationResult.Uncertain("created", null), writer.recover("created"))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun oversizedMutationSourceFailsBeforeTokenOrNewJournalAndNeverClipsText() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("created", draft) as MutationResult.LocalSaved).ref
            val original = "private".repeat(10_000)
            provider.events.single()["description"] = original
            assertEquals(EditReadResult.LimitExceeded, writer.loadForEdit(ref))
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            assertEquals(MutationResult.Conflict(null), writer.update("oversized", ref, EditToken("missing"), EventPatch(title = FieldChange.Set("changed"))))
            assertNull(store.operation("oversized"))
            assertEquals(original, provider.events.single()["description"])
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
    @Test fun authoredBareAndCustomOffsetZonesRefuseCreateBeforeJournalOrBatch() = runBlocking {
        for (zone in listOf(ZoneId.of("+05:30"), ZoneId.of("GMT+05:30"))) fixture { provider, writer, store, _ ->
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE)), writer.create("unsupported-zone", draft.copy(eventZone = zone)))
            assertNull(store.operationHeader("unsupported-zone")); assertEquals(0, provider.batchCount); assertTrue(provider.events.isEmpty())
        }
    }

    @Test fun explicitZoneOrTimePatchCannotAuthorUnsupportedNativeZoneEvenWhenTimeOtherwiseValid() = runBlocking {
        for (zone in listOf(ZoneId.of("+05:30"), ZoneId.of("GMT+05:30"))) fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            var edit = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            val before = provider.batchCount
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE)), writer.update("zone-set", ref, edit.token, EventPatch(eventZone = FieldChange.Set(zone))))
            assertNull(store.operationHeader("zone-set")); assertEquals(before, provider.batchCount)
            provider.events.single()["eventTimezone"] = zone.id; provider.events.single()["eventEndTimezone"] = zone.id
            edit = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertEquals(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE)), writer.update("time-set", ref, edit.token,
                EventPatch(time = FieldChange.Set(EventTime.Timed(ref.instanceStart, ref.instanceEnd.plusSeconds(60))))))
            assertNull(store.operationHeader("time-set")); assertEquals(before, provider.batchCount)
        }
    }

    @Test fun importedUnsupportedZoneSurvivesUnrelatedTitleAndAlertEditsWithOpaqueMetadata() = runBlocking {
        for (zone in listOf("+05:30", "GMT+05:30")) fixture { provider, writer, _, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val event = provider.events.single(); event["eventTimezone"] = zone; event["eventEndTimezone"] = zone
            event["sync_data1"] = "Opaque imported metadata"
            val edit = (writer.loadForEdit(ref) as EditReadResult.Ready).event
            assertTrue(writer.update("keep-zone", ref, edit.token, EventPatch(title = FieldChange.Set("Edited title"), alertMinutes = FieldChange.Set(listOf(15)))) is MutationResult.LocalSaved)
            assertEquals(zone, event["eventTimezone"]); assertEquals(zone, event["eventEndTimezone"])
            assertEquals("Opaque imported metadata", event["sync_data1"]); assertEquals("Edited title", event["title"])
            assertEquals(ref.instanceStart.toEpochMilli().toString(), event["dtstart"])
            assertEquals(ref.instanceEnd.toEpochMilli().toString(), event["dtend"])
        }
    }

    @Test fun receipt4UpdateRecoversAfterSnapshotPurgeAndReopenWithReadPermissionOnly() = runBlocking {
        fixture { provider, writer, store, permissions ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            provider.events.single()["uid2445"] = "source-uid@probe.invalid"
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            provider.committed = false; provider.failReadsAfterCommit = true
            val patch = EventPatch(title = FieldChange.Set("Changed"), alertMinutes = FieldChange.Set(listOf(15, 30)))
            assertEquals(MutationResult.Uncertain("edit", null), writer.update("edit", ref, token, patch))
            val recorded = store.operation("edit")!!
            assertEquals("COMMITTED", recorded.phase)
            assertEquals(4, org.json.JSONObject(recorded.resultRef!!).getInt("version"))
            store.writableDatabase.delete("edit_snapshots", null, null)
            provider.failReadsAfterCommit = false; permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val context = RuntimeEnvironment.getApplication(); val reopened = CalendarLocalStore(context, store.databaseName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val second = CalendarWriter(context.contentResolver, CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0), reopened, permissions::contains)
                assertEquals(MutationResult.LocalSaved(ref), second.recover("edit"))
                assertEquals(MutationResult.LocalSaved(ref), second.recover("edit"))
                assertEquals("Changed", provider.events.single()["title"])
                assertEquals(listOf("15", "30"), provider.reminders.map { it["minutes"] })
                assertEquals(2, provider.batchCount)
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); reopened.close() }
        }
    }

    @Test fun receipt4DeleteRecoversReadOnlyAfterSnapshotPurgeForTombstoneAndAbsentRow() = runBlocking {
        for (hardDelete in listOf(false, true)) fixture { provider, writer, store, permissions ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            provider.events.single()["uid2445"] = "source-uid@probe.invalid"
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            provider.committed = false; provider.failReadsAfterCommit = true
            assertEquals(MutationResult.Uncertain("delete", null), writer.delete("delete", ref, token))
            assertEquals("COMMITTED", store.operation("delete")!!.phase)
            store.writableDatabase.delete("edit_snapshots", null, null)
            if (hardDelete) provider.events.clear()
            provider.failReadsAfterCommit = false; permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.LocalDeleted(ref), writer.recover("delete"))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4PostCommitAuthoredDriftAndSameCalendarUidReplacementRemainUncertain() = runBlocking {
        for (field in listOf("title", "eventTimezone", "uid2445", "_sync_id")) fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            provider.events.single()["uid2445"] = "original@probe.invalid"
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, token, EventPatch(title = FieldChange.Set("Changed"))))
            provider.events.single()[field] = "replacement"
            store.writableDatabase.delete("edit_snapshots", null, null)
            assertEquals(MutationResult.Uncertain("edit", ref.eventId), writer.recover("edit"))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4ReadbackRequiresExactReminderPayloadAndMembership() = runBlocking {
        for (change in listOf("payload", "extra", "missing")) fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, token, EventPatch(alertMinutes = FieldChange.Set(listOf(20)))))
            when (change) {
                "payload" -> provider.reminders.single()["minutes"] = "21"
                "extra" -> provider.reminders += provider.reminders.single().toMutableMap().apply { put("_id", "999") }
                else -> provider.reminders.clear()
            }
            store.writableDatabase.delete("edit_snapshots", null, null)
            assertEquals(MutationResult.Uncertain("edit", ref.eventId), writer.recover("edit"))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4PreparedWinnerSurvivesSnapshotPurgeAndOnlyExactExplicitRetryDispatches() = runBlocking {
        fixture { provider, writer, store, permissions ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val patch = EventPatch(title = FieldChange.Set("Frozen"))
            store.writableDatabase.execSQL("""CREATE TRIGGER interrupt_dispatch BEFORE UPDATE OF phase ON mutation_journal
                WHEN NEW.phase = 'ATTEMPTED' BEGIN SELECT RAISE(FAIL, 'Synthetic interruption'); END""")
            assertEquals(MutationResult.Unavailable, writer.update("edit", ref, token, patch))
            val winner = store.operation("edit")!!.resultRef
            store.writableDatabase.delete("edit_snapshots", null, null)
            permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.SafeToRetry("edit"), writer.recover("edit"))
            assertEquals(MutationResult.PermissionRequired, writer.update("edit", ref, token, patch))
            permissions += Manifest.permission.WRITE_CALENDAR
            assertEquals(MutationResult.Conflict(null), writer.update("edit", ref, token, patch.copy(title = FieldChange.Set("Other"))))
            assertEquals(winner, store.operation("edit")!!.resultRef)
            store.writableDatabase.execSQL("DROP TRIGGER interrupt_dispatch")
            val context = RuntimeEnvironment.getApplication(); val reopened = CalendarLocalStore(context, store.databaseName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val second = CalendarWriter(context.contentResolver, CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0), reopened, permissions::contains)
                assertEquals(MutationResult.LocalSaved(ref), second.update("edit", ref, token, patch))
                assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, token, patch))
                assertEquals(2, provider.batchCount)
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); reopened.close() }
        }
    }

    @Test fun receipt4AttemptedUnknownNeverReplaysAndRetirementStillFencesPreparedCommand() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val patch = EventPatch(title = FieldChange.Set("Frozen"))
            provider.lostAcknowledgement = true
            assertEquals(MutationResult.Uncertain("unknown", null), writer.update("unknown", ref, token, patch))
            store.writableDatabase.delete("edit_snapshots", null, null)
            provider.lostAcknowledgement = false
            assertEquals(MutationResult.Uncertain("unknown", null), writer.recover("unknown"))
            assertEquals(MutationResult.Uncertain("unknown", null), writer.update("unknown", ref, token, patch))
            assertEquals(2, provider.batchCount)
            val next = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val retirement = CalendarOperationRetirement(store)
            assertEquals(OperationRetirementResult.Retired("retired"), retirement.retire(RetirableOperation.Update("retired", ref, next, patch)))
            assertEquals(MutationResult.Retired("retired"), writer.update("retired", ref, next, patch))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4PreservesNullZoneOpaqueMetadataAndNonAlertRowsAcrossReadOnlyRecovery() = runBlocking {
        fixture { provider, writer, store, permissions ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val row = provider.events.single(); row["eventTimezone"] = null; row["eventEndTimezone"] = null
            row["availability"] = "77"; row["accessLevel"] = "88"; row["sync_data1"] = "Opaque"
            provider.reminders += mutableMapOf("_id" to "901", "event_id" to ref.eventId.toString(), "method" to "2", "minutes" to "-1")
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, token, EventPatch(title = FieldChange.Set("Changed"))))
            store.writableDatabase.delete("edit_snapshots", null, null); permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(MutationResult.LocalSaved(ref), writer.recover("edit"))
            assertNull(row["eventTimezone"]); assertEquals("77", row["availability"]); assertEquals("88", row["accessLevel"]); assertEquals("Opaque", row["sync_data1"])
            assertEquals("-1", provider.reminders.single { it["method"] == "2" }["minutes"])
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4ForgedNativePlanAndNamespaceCannotAcknowledgeOrRetry() = runBlocking {
        for (field in listOf("plan", "identity", "sourceRaw")) fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, token, EventPatch(title = FieldChange.Set("Changed"))))
            val j = org.json.JSONObject(store.operation("edit")!!.resultRef!!)
            when (field) {
                "plan" -> j.getJSONObject("plan").getJSONObject("changes").put("title", "Forged")
                "identity" -> j.getJSONObject("identity").put("name", "foreign@probe.invalid")
                else -> j.put("sourceRaw", "{}")
            }
            store.forceOperationForTest("edit", "COMMITTED", j.toString())
            assertEquals(MutationResult.Uncertain("edit", null), writer.recover("edit"))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4IncompleteChildAcknowledgementKeepsKnownCommitWithoutGuessingOrReplay() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            provider.malformedReminderAcknowledgement = true
            val patch = EventPatch(alertMinutes = FieldChange.Set(listOf(25)))
            assertEquals(MutationResult.Uncertain("edit", ref.eventId), writer.update("edit", ref, token, patch))
            val recorded = store.operation("edit")!!
            assertEquals("COMMITTED", recorded.phase)
            val j = org.json.JSONObject(recorded.resultRef!!)
            assertFalse(j.getBoolean("ackComplete")); assertFalse(j.has("reminderIds"))
            store.writableDatabase.delete("edit_snapshots", null, null)
            provider.malformedReminderAcknowledgement = false
            assertEquals(MutationResult.Uncertain("edit", ref.eventId), writer.recover("edit"))
            assertEquals(MutationResult.Uncertain("edit", ref.eventId), writer.update("edit", ref, token, patch))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4DefiniteRollbackPreservesFrozenNativeRetryWithoutSnapshots() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val patch = EventPatch(title = FieldChange.Set("Frozen"), alertMinutes = FieldChange.Set(listOf(25)))
            provider.failReminder = true
            assertEquals(MutationResult.Unavailable, writer.update("edit", ref, token, patch))
            assertEquals("FAILED", store.operation("edit")!!.phase)
            store.writableDatabase.delete("edit_snapshots", null, null)
            assertEquals(MutationResult.SafeToRetry("edit"), writer.recover("edit"))
            assertEquals(draft.title, provider.events.single()["title"])
            provider.failReminder = false
            assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, token, patch))
            assertEquals(3, provider.batchCount)
        }
    }

    @Test fun legacyV2EditReceiptAndOldProjectionTokenRemainCompatibleAndSnapshotDependent() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val fresh = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val source = org.json.JSONObject(store.edit(fresh.value)!!)
            source.getJSONObject("event").remove("uid2445"); source.getJSONObject("event").remove("_sync_id")
            val raw = source.toString()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(("EDIT" + raw).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val legacy = EditToken(hash); store.saveEdit(hash, raw)
            val patch = EventPatch(title = FieldChange.Set("Legacy compatible"))
            assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, legacy, patch))
            val receipt = org.json.JSONObject(store.operation("edit")!!.resultRef!!)
            assertEquals(4, receipt.getInt("version"))
            assertEquals(raw, receipt.getString("sourceRaw"))
            val old = org.json.JSONObject().put("version", 2).put("stage", "committed").put("kind", "UPDATE")
                .put("signature", receipt.getString("signature")).put("command", receipt.getJSONObject("command"))
                .put("identity", receipt.getJSONObject("identity")).put("ref", receipt.getJSONObject("ref"))
            store.prepare("old", receipt.getString("signature"), "UPDATE")
            store.forceOperationForTest("old", "COMMITTED", old.toString())
            assertEquals(MutationResult.LocalSaved(ref), writer.recover("old"))
            store.writableDatabase.delete("edit_snapshots", null, null)
            assertEquals(MutationResult.Uncertain("old", null), writer.recover("old"))
            assertEquals(MutationResult.LocalSaved(ref), writer.recover("edit"))
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun receipt4AllDayEditRecoveryUsesFrozenUtcBoundsAfterDisplayZoneTravel() = runBlocking {
        fixture { provider, writer, store, permissions ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val time = EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-03-11"), LocalDate.parse("2028-03-14")))
            val saved = writer.update("edit", ref, token, EventPatch(time = FieldChange.Set(time))) as MutationResult.LocalSaved
            assertEquals(Instant.parse("2028-03-11T00:00:00Z"), saved.ref.instanceStart)
            assertEquals(Instant.parse("2028-03-14T00:00:00Z"), saved.ref.instanceEnd)
            store.writableDatabase.delete("edit_snapshots", null, null); permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val prior = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))
                assertEquals(saved, writer.recover("edit"))
            } finally { java.util.TimeZone.setDefault(prior) }
            assertEquals("UTC", provider.events.single()["eventTimezone"])
            assertEquals(2, provider.batchCount)
        }
    }

    @Test fun independentWriterCannotReplayTheWinningAttemptedEdit() = runBlocking {
        fixture { provider, writer, store, permissions ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val patch = EventPatch(title = FieldChange.Set("One winner"))
            val context = RuntimeEnvironment.getApplication(); val reopened = CalendarLocalStore(context, store.databaseName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val second = CalendarWriter(context.contentResolver, CalendarReader(context.contentResolver, scope, stopTimeoutMillis = 0), reopened, permissions::contains)
                var competing: MutationResult? = null
                provider.beforeBatch = { competing = runBlocking { second.update("edit", ref, token, patch) } }
                assertEquals(MutationResult.LocalSaved(ref), writer.update("edit", ref, token, patch))
                assertEquals(MutationResult.Uncertain("edit", null), competing)
                assertEquals(MutationResult.LocalSaved(ref), second.update("edit", ref, token, patch))
                assertEquals(2, provider.batchCount)
                assertEquals("One winner", provider.events.single()["title"])
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); reopened.close() }
        }
    }

    @Test fun cancellationAfterDurableV4PreparationCannotCrossTheProviderBoundary() = runBlocking {
        fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val patch = EventPatch(title = FieldChange.Set("Must stay local"))
            val task = async(start = CoroutineStart.LAZY) { writer.update("edit", ref, token, patch) }
            provider.onQuery = { if (store.operationHeader("edit")?.phase == "PREPARED") task.cancel() }
            task.start()
            try { task.await(); fail("Cancelled edit must not return a save") } catch (_: CancellationException) { }
            provider.onQuery = null
            assertEquals("PREPARED", store.operation("edit")!!.phase)
            assertEquals(MutationResult.SafeToRetry("edit"), writer.recover("edit"))
            assertEquals(draft.title, provider.events.single()["title"])
            assertEquals(1, provider.batchCount)
        }
    }
    @Test fun ordinaryTimeAuthoringRejectsNonNativePrecisionAndRetainsExactMillis() = runBlocking {
        val base = draft.time as EventTime.Timed
        val negativeStart = Instant.parse("1969-12-31T23:59:59.500Z")
        val negativeEnd = Instant.parse("1970-01-01T00:00:00.500Z")
        val cases = listOf(
            Triple("START_NANOS", EventTime.Timed(base.start.plusNanos(1), base.end), false),
            Triple("END_NANOS", EventTime.Timed(base.start, base.end.plusNanos(1)), false),
            Triple("NEGATIVE_START_NANOS", EventTime.Timed(negativeStart.plusNanos(1), negativeEnd), false),
            Triple("EXACT_MILLIS", EventTime.Timed(base.start.plusMillis(1), base.end.plusMillis(2)), true),
            Triple("NEGATIVE_EXACT_MILLIS", EventTime.Timed(negativeStart, negativeEnd), true),
        )
        val failures = mutableListOf<String>()
        for ((label, candidate, exact) in cases) fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val before = provider.events.map { it.toMap() }
            val batches = provider.batchCount
            val result = writer.update("precision", ref, token, EventPatch(time = FieldChange.Set(candidate)))
            val delta = provider.batchCount - batches
            val saved = result as? MutationResult.LocalSaved
            val nativeExact = saved?.let { value ->
                val event = provider.events.single()
                value.ref.instanceStart == Instant.ofEpochMilli(requireNotNull(event["dtstart"]).toLong()) &&
                    value.ref.instanceEnd == Instant.ofEpochMilli(requireNotNull(event["dtend"]).toLong())
            }
            val phase = store.operationHeader("precision")?.phase
            println("Receipt4 precision authoring: kind=UPDATE case=$label exact=$exact result=${result.javaClass.simpleName} batches=$delta phase=$phase nativeExact=$nativeExact")
            if (exact) {
                val expected = MutationResult.LocalSaved(ref.copy(instanceStart = candidate.start, instanceEnd = candidate.end))
                if (result != expected || delta != 1 || nativeExact != true || phase != "VERIFIED")
                    failures += "$label: exact millisecond edit was not verified exactly"
            } else if (result != MutationResult.ValidationFailed(setOf(InvalidEventField.TIME)) || delta != 0 ||
                phase != null || before != provider.events.map { it.toMap() }) {
                failures += "$label: result=${result.javaClass.simpleName} batches=$delta phase=$phase nativeExact=$nativeExact"
            }
        }
        for ((label, candidate, exact) in cases) fixture { provider, writer, store, _ ->
            val result = writer.create("precision-create", draft.copy(time = candidate))
            val saved = result as? MutationResult.LocalSaved
            val nativeExact = saved?.let { value ->
                val event = provider.events.single()
                value.ref.instanceStart == Instant.ofEpochMilli(requireNotNull(event["dtstart"]).toLong()) &&
                    value.ref.instanceEnd == Instant.ofEpochMilli(requireNotNull(event["dtend"]).toLong())
            }
            val phase = store.operationHeader("precision-create")?.phase
            println("Receipt4 precision authoring: kind=CREATE case=$label exact=$exact result=${result.javaClass.simpleName} batches=${provider.batchCount} phase=$phase nativeExact=$nativeExact")
            if (exact) {
                if (saved == null || saved.ref.instanceStart != candidate.start || saved.ref.instanceEnd != candidate.end ||
                    provider.batchCount != 1 || nativeExact != true || phase != "VERIFIED")
                    failures += "CREATE/$label: exact millisecond create was not verified exactly"
            } else if (result != MutationResult.ValidationFailed(setOf(InvalidEventField.TIME)) || provider.batchCount != 0 ||
                phase != null || provider.events.isNotEmpty()) {
                failures += "CREATE/$label: result=${result.javaClass.simpleName} batches=${provider.batchCount} phase=$phase"
            }
        }
        assertTrue("Non-native precision crossed ordinary authoring: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test fun frozenNonNativePrecisionNeverAuthorizesRetryOrAcknowledgesANativeIdentity() = runBlocking {
        val failures = mutableListOf<String>()
        val variants = listOf("ORIGINAL_START", "ORIGINAL_END", "RESULT_START", "RESULT_END", "NEGATIVE_RESULT_START")
        for (variant in variants) for (phase in listOf("PREPARED", "ATTEMPTED", "COMMITTED")) fixture { provider, writer, store, _ ->
            val ref = (writer.create("seed", draft) as MutationResult.LocalSaved).ref
            val token = (writer.loadForEdit(ref) as EditReadResult.Ready).event.token
            val validTime = EventTime.Timed(ref.instanceStart.plusSeconds(60), ref.instanceEnd.plusSeconds(60))
            val validPatch = EventPatch(time = FieldChange.Set(validTime))
            val calibration = async(start = CoroutineStart.LAZY) { writer.update("calibration", ref, token, validPatch) }
            provider.onQuery = { if (store.operationHeader("calibration")?.phase == "PREPARED") calibration.cancel() }
            calibration.start()
            try { calibration.await(); fail("Calibration must stop before provider dispatch") }
            catch (_: CancellationException) { }
            finally { provider.onQuery = null }
            val template = requireNotNull(store.operation("calibration"))
            assertEquals("PREPARED", template.phase); assertEquals(1, provider.batchCount)
            val (prepared, issued) = OrdinaryEditReceipt.parse(template)
            val original = when (variant) {
                "ORIGINAL_START" -> ref.copy(instanceStart = ref.instanceStart.plusNanos(1))
                "ORIGINAL_END" -> ref.copy(instanceEnd = ref.instanceEnd.plusNanos(1))
                else -> ref
            }
            val result = when (variant) {
                "RESULT_START" -> issued.result.copy(instanceStart = issued.result.instanceStart.plusNanos(1))
                "RESULT_END" -> issued.result.copy(instanceEnd = issued.result.instanceEnd.plusNanos(1))
                "NEGATIVE_RESULT_START" -> issued.result.copy(instanceStart = Instant.parse("1969-12-31T23:59:59.500000001Z"),
                    instanceEnd = Instant.parse("1970-01-01T00:00:00.500Z"))
                else -> issued.result
            }
            val time = EventTime.Timed(result.instanceStart, result.instanceEnd)
            val patch = EventPatch(time = FieldChange.Set(time))
            val command = org.json.JSONObject(issued.command.toString()).put("start", original.instanceStart.toString())
                .put("end", original.instanceEnd.toString()).put("time", time.toString())
            val signature = CalendarWriter.updateFingerprint(original, token, patch, 1024 * 1024).second
            val changes = issued.changes + mapOf("dtstart" to result.instanceStart.toEpochMilli().toString(),
                "dtend" to result.instanceEnd.toEpochMilli().toString())
            // Reconstruct a self-consistent previously issued v4 frame, not a broken checksum.
            // Native rows remain millisecond values; an invalid original/result must stay uncertain.
            val forged = issued.copy(command = command, changes = changes, result = result)
            val metadata = forged.prepared(signature, prepared.getJSONObject("identity"))
            store.prepare("frozen-precision", signature, "UPDATE", metadata.toString())
            if (phase == "COMMITTED") {
                metadata.put("ackComplete", true).put("reminderIds", org.json.JSONArray())
                    .put("stage", "committed").put("ref", OrdinaryEditReceipt.refJson(result))
                provider.events.single().putAll(changes) // Explicit synthetic prior native commit.
            }
            store.forceOperationForTest("frozen-precision", phase, metadata.toString())
            val before = provider.events.map { it.toMap() }
            provider.queryCounts.clear()
            val batches = provider.batchCount
            val recovered = writer.recover("frozen-precision")
            val submitted = writer.update("frozen-precision", original, token, patch)
            val afterPhase = store.operationHeader("frozen-precision")?.phase
            val delta = provider.batchCount - batches
            println("Receipt4 frozen precision: case=$variant phase=$phase recover=${recovered.javaClass.simpleName} submit=${submitted.javaClass.simpleName} batches=$delta after=$afterPhase queries=${provider.queryCounts.values.sum()}")
            val expected = MutationResult.Uncertain("frozen-precision", null)
            val safeSubmission = submitted == expected || phase == "PREPARED" && submitted == MutationResult.Unavailable
            if (recovered != expected || !safeSubmission || delta != 0 || afterPhase != phase ||
                provider.queryCounts.isNotEmpty() || before != provider.events.map { it.toMap() }) {
                failures += "$variant/$phase: recover=${recovered.javaClass.simpleName} submit=${submitted.javaClass.simpleName} batches=$delta after=$afterPhase"
            }
        }
        assertTrue("Non-native frozen identity was accepted: ${failures.joinToString("; ")}", failures.isEmpty())
    }

}
