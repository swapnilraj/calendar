package dev.swapnilraj.calendar.data.calendar

import android.app.Application
import android.content.ContentValues
import dev.swapnilraj.calendar.core.*
import java.time.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OrdinaryCreateReceiptCodecTest {
    private val calendar = mapOf("_id" to "2", "account_name" to "owner@example.invalid", "account_type" to "com.google", "ownerAccount" to "owner@example.invalid",
        "calendar_access_level" to "700", "allowedReminders" to "1", "maxReminders" to "5", "canModifyTimeZone" to "1", "calendar_timezone" to "UTC", "allowedAvailability" to "0,1,2", "_sync_id" to "calendar-A")
    private val uid = "b0428257-dcf9-4bd4-9018-54a6e84e4b56@daylight.invalid"
    private fun receipt(recurring: Boolean = false, allDay: Boolean = false, through: Boolean = false): OrdinaryCreateReceipt {
        val zone = ZoneId.of("America/New_York")
        val time: EventTime = if (allDay) EventTime.AllDay(CalendarDateRange(LocalDate.of(2028, 3, 10), LocalDate.of(2028, 3, 12)))
            else EventTime.Timed(Instant.parse("2028-03-10T14:00:00Z"), Instant.parse("2028-03-10T15:00:00Z"))
        val draft = EventDraft(CalendarId(2), time, if (allDay) null else zone, "Synthetic frozen create", "Synthetic room", "Synthetic notes", listOf(10, 30),
            targetIdentity = CreateSixJson.calendarIdentity(calendar), recurrence = if (!recurring) null else RecurrenceSpec(RecurrencePattern.Daily,
                end = if (through) RecurrenceEnd.ThroughDate(LocalDate.of(2028, 3, 14)) else RecurrenceEnd.Count(3)))
        val command = CalendarWriter.frozenDraftJson(draft, 1024 * 1024)
        val start = if (allDay) RecurrenceStart.AllDay((time as EventTime.AllDay).dates.start) else (time as EventTime.Timed).start.atZone(zone).let { RecurrenceStart.Timed(it.toLocalDateTime(), zone, it.offset) }
        val plan = if (recurring) (RecurrenceCreation.compile(start, time, draft.recurrence!!) as RecurrenceCreationResult.Ready).plan else null
        val firstStart = if (time is EventTime.Timed) time.start else (time as EventTime.AllDay).dates.start.atStartOfDay(ZoneOffset.UTC).toInstant()
        val firstEnd = if (time is EventTime.Timed) time.end else (time as EventTime.AllDay).dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant()
        val values = ContentValues().apply {
            put("calendar_id", 2); put("dtstart", firstStart.toEpochMilli()); if (recurring) putNull("dtend") else put("dtend", firstEnd.toEpochMilli())
            put("allDay", if (allDay) 1 else 0); put("eventTimezone", if (allDay) "UTC" else zone.id); put("eventEndTimezone", if (allDay) "UTC" else zone.id)
            if (plan == null) putNull("duration") else { put("duration", plan.durationRaw); put("rrule", plan.ruleRaw) }
            put("hasAlarm", 1); put("title", draft.title); put("eventLocation", draft.location); put("description", draft.description)
        }
        return OrdinaryCreateReceipt("codec-operation", command.toString(), calendar, OrdinaryCreatePlan.freeze(values, draft, plan, uid)).also { it.validate() }
    }
    private fun operation(r: OrdinaryCreateReceipt, json: JSONObject = r.prepared(), phase: String = "PREPARED", id: String = r.operationId) = CalendarLocalStore.Operation(id, r.signature, "CREATE", phase, json.toString())
    private fun rejects(block: () -> Unit) { assertThrows(Exception::class.java, block) }

    @Test fun oneOffAndRecurringRoundTripExactNativePlanAndExistingCommandFingerprint() {
        for (recurring in listOf(false, true)) for (allDay in listOf(false, true)) {
            val r = receipt(recurring, allDay); val parsed = OrdinaryCreateReceipt.parse(operation(r), 1024 * 1024).second
            assertEquals(r.commandRaw, parsed.commandRaw); assertEquals(r.signature, parsed.signature)
            assertEquals(r.plan.json().toString(), parsed.plan.json().toString()); assertEquals(uid, parsed.plan.event["uid2445"])
            assertEquals(CreateSixJson.hash("CREATE" + r.commandRaw), r.signature)
        }
    }

    @Test fun frozenUntilWitnessUsesOriginalLocalAndOffsetFactsWithoutZoneLookup() {
        for (allDay in listOf(false, true)) {
            val r = receipt(true, allDay, true)
            val parsed = OrdinaryCreateReceipt.parse(operation(r), 1024 * 1024).second
            assertEquals(r.plan.event["rrule"], parsed.plan.event["rrule"])
            assertEquals(r.plan.firstStart, parsed.plan.firstStart); assertEquals(r.plan.firstEnd, parsed.plan.firstEnd)
        }
    }

    @Test fun digestBindsOperationUidCalendarAndNativeValuesIndependentlyOfCommandHash() {
        val r = receipt(true)
        rejects { OrdinaryCreateReceipt.parse(operation(r, id = "foreign-operation"), 1024 * 1024) }
        for (field in listOf("uid", "event", "calendarSnapshot", "planDigest")) {
            val j = r.prepared()
            when (field) {
                "uid" -> j.getJSONObject("plan").put("uid", "6747f80a-bd46-449f-ae10-43cc7c421ae7@daylight.invalid")
                "event" -> j.getJSONObject("plan").getJSONObject("event").put("title", "Changed")
                "calendarSnapshot" -> j.getJSONObject(field).put("maxReminders", "99")
                else -> j.put(field, "0".repeat(64))
            }
            rejects { OrdinaryCreateReceipt.parse(operation(r, j), 1024 * 1024) }
        }
    }

    @Test fun strictFieldsAndScalarsRejectUnknownCoercionsWrongNullFormsAndFractionalIds() {
        val r = receipt()
        val mutations: List<(JSONObject) -> Unit> = listOf({ it.put("unknown", true) }, { it.put("version", "6") },
            { it.getJSONObject("identity").put("calendar", 2.5) },
            { it.getJSONObject("plan").put("allDay", "false") }, { it.getJSONObject("plan").getJSONObject("event").put("dtstart", 123) },
            { it.getJSONObject("plan").remove("recurrenceFacts") }, { it.getJSONObject("plan").put("extra", "value") })
        for (mutate in mutations) { val j = r.prepared(); mutate(j); rejects { OrdinaryCreateReceipt.parse(operation(r, j), 1024 * 1024) } }
        val doubleOnWire = operation(r).copy(resultRef = r.prepared().toString().replace("\"planDigestVersion\":1", "\"planDigestVersion\":1.0"))
        rejects { OrdinaryCreateReceipt.parse(doubleOnWire, 1024 * 1024) }
    }

    @Test fun allDayCommandZoneStillRequiresNullableStringWithRecomputedSignatureAndDigest() {
        for (recurring in listOf(false, true)) {
            val original = receipt(recurring = recurring, allDay = true)
            for (malformed in listOf(JSONObject().put("id", "UTC"), true, 17, JSONArray(listOf("UTC")))) {
                val altered = original.copy(commandRaw = original.command.put("zone", malformed).toString())
                val wire = altered.prepared()
                assertNotEquals(original.signature, altered.signature)
                assertNotEquals(original.prepared().getString("planDigest"), wire.getString("planDigest"))
                rejects { OrdinaryCreateReceipt.parse(operation(altered, wire), 1024 * 1024) }
            }
            val stringZone = original.copy(commandRaw = original.command.put("zone", "Historical/Removed_Zone").toString())
            assertEquals("UTC", OrdinaryCreateReceipt.parse(operation(stringZone), 1024 * 1024).second.plan.event["eventTimezone"])
        }
    }

    @Test fun immutableProtocolRejectsNativeValueChangesEvenWithANewDigest() {
        val r = receipt()
        for (field in listOf("uid2445", "eventTimezone", "dtstart", "description")) {
            val altered = r.copy(plan = r.plan.copy(event = r.plan.event + (field to "changed")))
            rejects { OrdinaryCreateReceipt.parse(operation(altered), 1024 * 1024) }
        }
        val altered = r.copy(plan = r.plan.copy(reminders = r.plan.reminders.reversed()))
        rejects { OrdinaryCreateReceipt.parse(operation(altered), 1024 * 1024) }
    }

    @Test fun storedFieldOrderingDoesNotChangeVersionedDigestButCommandBytesRemainBound() {
        val r = receipt(true); val j = r.prepared(); val original = j.getJSONObject("calendarSnapshot")
        j.put("calendarSnapshot", JSONObject().apply { original.keys().asSequence().toList().reversed().forEach { put(it, original.get(it)) } })
        assertEquals(r.plan.uid, OrdinaryCreateReceipt.parse(operation(r, j), 1024 * 1024).second.plan.uid)
        val command = j.getJSONObject("command")
        j.put("command", JSONObject().apply { command.keys().asSequence().toList().reversed().forEach { put(it, command.get(it)) } })
        rejects { OrdinaryCreateReceipt.parse(operation(r, j), 1024 * 1024) }
    }

    @Test fun committedUnknownAckIsReadableButCannotPretendToBeVerified() {
        val r = receipt(true); val j = r.prepared().put("stage", "committed").put("master", 17).put("ackComplete", false)
        assertEquals(uid, OrdinaryCreateReceipt.parse(operation(r, j, "COMMITTED"), 1024 * 1024).second.plan.uid)
        rejects { OrdinaryCreateReceipt.parse(operation(r, j, "VERIFIED"), 1024 * 1024) }
        rejects { OrdinaryCreateReceipt.parse(operation(r, j, "FAILED"), 1024 * 1024) }
        rejects { OrdinaryCreateReceipt.parse(operation(r, phase = "COMMITTED"), 1024 * 1024) }
    }

    @Test fun completeVerifiedProofBindsCanonicalChildrenToTheFrozenResultLayout() {
        val r = receipt(true)
        val results = arrayOf(android.content.ContentProviderResult(1), android.content.ContentProviderResult(android.net.Uri.parse("content://com.android.calendar/events/17")),
            android.content.ContentProviderResult(android.net.Uri.parse("content://com.android.calendar/reminders/21")), android.content.ContentProviderResult(android.net.Uri.parse("content://com.android.calendar/reminders/22")))
        val j = r.prepared().put("stage", "committed").put("master", 17).put("ackComplete", true)
            .put("ackFacts", CalendarCreateAcknowledgments.capture(results, 4)).put("reminderIds", JSONArray(listOf(21, 22)))
            .put("firstRef", CreateSixJson.ref(r.plan.ref(r.identity.calendarId, 17)))
        assertEquals(uid, OrdinaryCreateReceipt.parse(operation(r, j, "VERIFIED"), 1024 * 1024).second.plan.uid)
        j.put("reminderIds", JSONArray(listOf(22, 21)))
        rejects { OrdinaryCreateReceipt.parse(operation(r, j, "VERIFIED"), 1024 * 1024) }
    }

    @Test fun storedSizeAndNestedStructureBudgetRejectBeforeMaterializingAnUnboundedPlan() {
        val r = receipt()
        rejects { OrdinaryCreateReceipt.parse(operation(r), 128) }
        val j = r.prepared().put("command", "[".repeat(200))
        rejects { OrdinaryCreateReceipt.parse(operation(r, j), 1024 * 1024) }
    }
    @Test fun decodingHistoricalFrozenZoneNeedsNoCurrentlyInstalledZoneIdentifier() {
        val r = receipt(true, through = true)
        val command = r.command.put("zone", "Historical/Removed_Zone").toString()
        val plan = r.plan.copy(event = r.plan.event + mapOf("eventTimezone" to "Historical/Removed_Zone", "eventEndTimezone" to "Historical/Removed_Zone"))
        val historical = r.copy(commandRaw = command, plan = plan)
        assertEquals("Historical/Removed_Zone", OrdinaryCreateReceipt.parse(operation(historical), 1024 * 1024).second.plan.event["eventTimezone"])
    }

}
