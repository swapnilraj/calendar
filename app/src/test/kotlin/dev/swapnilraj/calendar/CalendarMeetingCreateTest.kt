package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentValues
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CalendarMeetingCreateTest {
    private val start=Instant.parse("2026-11-18T09:00:00Z")
    private val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("Europe/London"),"Synthetic meeting","Room","Notes",listOf(10,30),1,2,identity)
    private val guests=listOf(MeetingGuestInput("required@example.invalid",MeetingGuestType.REQUIRED,"Required guest"),MeetingGuestInput("optional@example.invalid",MeetingGuestType.OPTIONAL))
    private data class Fixture(val p:CalendarRecurringCreateTest.Provider,val writer:CalendarMeetingWriter,val store:CalendarLocalStore,val permissions:MutableSet<String>,val retirement:CalendarOperationRetirement)
    private suspend fun fixture(block:suspend(Fixture)->Unit){
        val context=RuntimeEnvironment.getApplication();val p=CalendarRecurringCreateTest.Provider().apply{nativeMeetingMode=true
            db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")}
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val store=CalendarLocalStore(context,"meeting-create-${UUID.randomUUID()}.db")
        val permission=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val writer=CalendarMeetingWriter(context.contentResolver,reader,store,permission::contains)
        try{block(Fixture(p,writer,store,permission,CalendarOperationRetirement(store)))}finally{scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.db.close();context.deleteDatabase(store.databaseName)}
    }
    private suspend fun Fixture.creation()=(writer.loadForCreate(identity) as MeetingReadResult.Ready).value
    private fun common(result:MutationResult)=MeetingMutationResult.Common(result)

    @Test fun eligibilityIsReadOnlyAndPublishesOnlyKnownSupportedTypesBeforeOneAtomicCreate()=runBlocking{fixture{f->
        f.permissions.remove(Manifest.permission.WRITE_CALENDAR);val creation=f.creation()
        assertEquals(identity,creation.calendar.identity);assertEquals(MeetingGuestType.entries.toSet(),creation.allowedGuestTypes)
        assertEquals(0,f.p.batchCount);assertEquals(0,f.p.count("events"));assertTrue(f.p.queries.all{it.path=="calendars"})
        assertEquals(common(MutationResult.PermissionRequired),f.writer.create("permission",creation.token,draft,guests));assertNull(f.store.operationHeader("permission"))
        f.permissions+=Manifest.permission.WRITE_CALENDAR
        val outcome=f.writer.create("create",creation.token,draft,guests)
        assertTrue(outcome is MeetingMutationResult.LocalSaved)
        val result=outcome as MeetingMutationResult.LocalSaved
        assertEquals(RecurrenceScope.THIS,result.scope);assertNull(result.selected.recurrence);assertEquals(start,result.selected.instanceStart)
        assertEquals("VERIFIED",f.store.operation("create")!!.phase);assertEquals(1,f.p.batchCount);assertEquals(3,f.p.count("attendees"));assertEquals(2,f.p.count("reminders"))
        val sent=f.p.insertedEventValues.single();assertEquals(1,sent.getAsInteger("eventStatus"));assertEquals(1,sent.getAsInteger("hasAttendeeData"))
        assertFalse(sent.containsKey("selfAttendeeStatus"));assertFalse(sent.containsKey("isOrganizer"));assertFalse(sent.containsKey("hasAlarm"))
        assertEquals("1",f.p.value("selfAttendeeStatus"));assertEquals("1",f.p.value("hasAlarm"));assertEquals("1",f.p.value("eventStatus"))
        f.p.db.rawQuery("SELECT attendeeEmail,attendeeRelationship,attendeeType,attendeeStatus FROM attendees ORDER BY _id",null).use{rows->
            assertTrue(rows.moveToNext());assertEquals(identity.owner,rows.getString(0));assertEquals(2,rows.getInt(1));assertEquals(0,rows.getInt(2));assertEquals(1,rows.getInt(3))
            assertTrue(rows.moveToNext());assertEquals(1,rows.getInt(2));assertEquals(3,rows.getInt(3));assertTrue(rows.moveToNext());assertEquals(2,rows.getInt(2));assertEquals(3,rows.getInt(3))}
        assertTrue(f.p.queries.filter{it.path=="attendees"}.all{it.fields!=null&&"rrule" !in it.fields})
    }}

    @Test fun nativeSelfStatusTrapReallyCreatesAnImplicitAttendeeButWriterDoesNotUseIt()=runBlocking{fixture{f->
        val id=f.p.insert(CalendarContract.Events.CONTENT_URI,ContentValues().apply{put("calendar_id",2);put("dtstart",start.toEpochMilli());put("dtend",start.plusSeconds(3600).toEpochMilli());put("allDay",0);put("eventTimezone","UTC");put("selfAttendeeStatus",1)})
        assertNotNull(id);assertEquals(1,f.p.count("attendees"));assertNull(f.p.value("eventStatus"))
        f.p.db.execSQL("DELETE FROM attendees");f.p.db.execSQL("DELETE FROM events");f.p.insertedEventValues.clear()
        val token=f.creation().token
        assertTrue(f.writer.create("safe",token,draft,guests) is MeetingMutationResult.LocalSaved);assertEquals(3,f.p.count("attendees"))
    }}

    @Test fun exactCalendarNamespaceAndOwnerPolicyNeverAdoptReusedOrDelegatedDestinations()=runBlocking{
        listOf("ownerAccount" to "delegate@example.invalid","account_name" to "replacement@example.invalid","_sync_id" to "calendar-B").forEach{(column,value)->fixture{f->
            val token=f.creation().token;f.p.setCalendar(column,value)
            assertTrue(f.writer.create("race",token,draft,guests) is MeetingMutationResult.Common);assertEquals(0,f.p.batchCount);assertNull(f.store.operationHeader("race"))
        }}
        fixture{f->f.p.setCalendar("calendar_access_level","600");assertTrue(f.writer.loadForCreate(identity) is MeetingReadResult.Denied);assertTrue(f.p.queries.none{it.path=="events"})}
        fixture{f->assertFalse(f.writer.loadForCreate(identity.copy(providerId="foreign")) is MeetingReadResult.Ready)}
    }

    @Test fun knownResourceAllowedTypeDoesNotBlockSupportedPeopleAndNoneOnlyIsUntyped()=runBlocking{fixture{f->
        f.p.setCalendar("allowedAttendeeTypes","0,1,2,3");val creation=f.creation();assertEquals(MeetingGuestType.entries.toSet(),creation.allowedGuestTypes)
        assertTrue(f.writer.create("resource-policy",creation.token,draft,guests) is MeetingMutationResult.LocalSaved)
    };fixture{f->
        f.p.setCalendar("allowedAttendeeTypes","0");val creation=f.creation();assertEquals(setOf(MeetingGuestType.UNSPECIFIED),creation.allowedGuestTypes)
        assertTrue(f.writer.create("untyped",creation.token,draft,listOf(MeetingGuestInput("guest@example.invalid",MeetingGuestType.UNSPECIFIED))) is MeetingMutationResult.LocalSaved)
    };listOf(null,"","1,2","0,bad","0,99").forEach{types->fixture{f->f.p.setCalendar("allowedAttendeeTypes",types);assertTrue(f.writer.loadForCreate(identity) is MeetingReadResult.NeedsReview);assertEquals(0,f.p.batchCount)}}}

    @Test fun invalidGuestInputsAndConflictingDuplicatesNeverPrepareOrMutate()=runBlocking{
        val invalid=listOf(listOf(MeetingGuestInput("bad address",MeetingGuestType.REQUIRED)),
            listOf(MeetingGuestInput("OWNER@example.invalid",MeetingGuestType.REQUIRED)),
            listOf(MeetingGuestInput("guest@example.invalid",MeetingGuestType.REQUIRED),MeetingGuestInput("GUEST@example.invalid",MeetingGuestType.OPTIONAL)),
            listOf(MeetingGuestInput("guest@example.invalid",MeetingGuestType.REQUIRED,"one"),MeetingGuestInput("GUEST@example.invalid",MeetingGuestType.REQUIRED,"two")),
            listOf(MeetingGuestInput("guest@example.invalid",MeetingGuestType.REQUIRED,"bad\nname")))
        invalid.forEach{inputs->fixture{f->val token=f.creation().token;assertTrue(f.writer.create("invalid",token,draft,inputs) is MeetingMutationResult.Invalid);assertNull(f.store.operationHeader("invalid"));assertEquals(0,f.p.batchCount)}}
        fixture{f->val token=f.creation().token;assertTrue(f.writer.create("dedup",token,draft,listOf(guests[0],guests[0].copy(email=" REQUIRED@example.invalid "))) is MeetingMutationResult.LocalSaved);assertEquals(2,f.p.count("attendees"))}
        fixture{f->val token=f.creation().token;assertTrue(f.writer.create("self-only",token,draft,emptyList()) is MeetingMutationResult.LocalSaved);assertEquals(1,f.p.count("attendees"))}
    }

    @Test fun allDayUsesUtcExclusiveDatesAndUnversionedRecurringRequestsNeverFallBackToOneOff()=runBlocking{fixture{f->
        val token=f.creation().token;val allDay=draft.copy(time=EventTime.AllDay(CalendarDateRange(LocalDate.of(2026,11,18),LocalDate.of(2026,11,21))),eventZone=null)
        val saved=f.writer.create("dates",token,allDay,guests) as MeetingMutationResult.LocalSaved
        assertEquals(Instant.parse("2026-11-18T00:00:00Z"),saved.selected.instanceStart);assertEquals(Instant.parse("2026-11-21T00:00:00Z"),saved.selected.instanceEnd);assertEquals("UTC",f.p.value("eventTimezone"))
    };fixture{f->f.p.setCalendar("canModifyTimeZone",null);val token=f.creation().token;assertTrue(f.writer.create("recurring",token,draft.copy(recurrence=RecurrenceSpec(RecurrencePattern.Daily)),guests) is MeetingMutationResult.NeedsReview);assertNull(f.store.operationHeader("recurring"));assertEquals(0,f.p.batchCount)}}

    @Test fun atomicAttendeeAndReminderFailuresRollBackAndRetryOnlyExactFrozenPayload()=runBlocking{
        listOf(true,false).forEach{attendee->fixture{f->val token=f.creation().token;f.p.failAttendee=attendee;f.p.failReminder=!attendee
            assertEquals(common(MutationResult.Unavailable),f.writer.create("retry",token,draft,guests));assertEquals("FAILED",f.store.operation("retry")!!.phase)
            assertEquals(0,f.p.count("events"));assertEquals(0,f.p.count("attendees"));assertEquals(0,f.p.count("reminders"))
            assertEquals(common(MutationResult.SafeToRetry("retry")),f.writer.recover("retry"))
            assertEquals(common(MutationResult.Conflict(null)),f.writer.create("retry",token,draft,guests.reversed()))
            f.p.failAttendee=false;f.p.failReminder=false;assertTrue(f.writer.create("retry",token,draft,guests) is MeetingMutationResult.LocalSaved);assertEquals(2,f.p.batchCount)
        }}
    }

    @Test fun lostOrMalformedInsertAcknowledgementsNeverSearchForRowsOrReplayCreate()=runBlocking{
        listOf("lost","event","attendee").forEach{mode->fixture{f->val token=f.creation().token
            f.p.lostAck=mode=="lost";f.p.malformedAck=mode=="event";f.p.malformedAttendeeAck=mode=="attendee"
            assertTrue((f.writer.create("uncertain",token,draft,guests) as MeetingMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals("ATTEMPTED",f.store.operation("uncertain")!!.phase);f.p.queries.clear();f.store.close()
            assertEquals(common(MutationResult.Uncertain("uncertain",null)),f.writer.recover("uncertain"));assertTrue(f.p.queries.isEmpty())
            f.writer.create("uncertain",token,draft,guests);assertEquals(1,f.p.batchCount);assertTrue(f.p.queries.isEmpty())
        }}
    }

    @Test fun committedPostReadFailuresRecoverReadOnlyAndReplacementUidBlocksBeforePrivateQueries()=runBlocking{fixture{f->
        val token=f.creation().token;f.p.failReadsAfterCommit=true
        assertTrue(f.writer.create("known",token,draft,guests) is MeetingMutationResult.Common);assertEquals("COMMITTED",f.store.operation("known")!!.phase)
        f.p.failReadsAfterCommit=false;f.permissions.remove(Manifest.permission.WRITE_CALENDAR);f.store.close()
        assertTrue(f.writer.recover("known") is MeetingMutationResult.LocalSaved);assertEquals(1,f.p.batchCount)
        f.p.setCalendar("_sync_id","calendar-B");f.p.queries.clear()
        assertEquals(common(MutationResult.Uncertain("known",1)),f.writer.recover("known"));assertTrue(f.p.queries.all{it.path=="calendars"})
    }}

    @Test fun wrongNativeEndAndPermissionRevocationCannotAcknowledgeOrDoubleSubmit()=runBlocking{fixture{f->
        val token=f.creation().token;f.p.afterCommit={f.p.endDelta=1000}
        assertEquals(common(MutationResult.Uncertain("end",1)),f.writer.create("end",token,draft,guests));assertEquals("COMMITTED",f.store.operation("end")!!.phase)
        f.p.afterCommit=null;f.p.endDelta=0;assertTrue(f.writer.recover("end") is MeetingMutationResult.LocalSaved)
        f.permissions.remove(Manifest.permission.READ_CALENDAR);assertEquals(common(MutationResult.PermissionRequired),f.writer.recover("end"));assertEquals(1,f.p.batchCount)
    }}

    @Test fun meetingCreateRetirementUsesExactEncoderAndStopsLateSavesWithoutPermissionsOrReads()=runBlocking{fixture{f->
        val token=f.creation().token;val request=RetirableOperation.MeetingCreate("retired",token,draft,guests)
        assertEquals(OperationRetirementResult.Retired("retired"),f.retirement.retire(request));assertEquals(OperationRetirementResult.Conflict("retired"),f.retirement.inspect(request.copy(guests=guests.reversed())))
        f.permissions.clear();f.p.queries.clear();assertEquals(common(MutationResult.Retired("retired")),f.writer.create("retired",token,draft,guests));assertTrue(f.p.queries.isEmpty());assertEquals(0,f.p.batchCount)
    }}

    @Test fun stalePolicyNamespaceBatchRaceAndGuestLimitFailBeforeJournalOrRollback()=runBlocking{fixture{f->
        val token=f.creation().token;f.p.setCalendar("allowedAttendeeTypes","0,1")
        assertEquals(common(MutationResult.Conflict(null)),f.writer.create("changed",token,draft,guests));assertNull(f.store.operationHeader("changed"))
    };fixture{f->val token=f.creation().token;f.p.beforeBatch={f.p.setCalendar("_sync_id","calendar-B")}
        assertEquals(common(MutationResult.Unavailable),f.writer.create("race",token,draft,guests));assertEquals(0,f.p.count("events"));assertEquals("FAILED",f.store.operation("race")!!.phase)
    };fixture{f->val token=f.creation().token
        val tooMany=List(201){MeetingGuestInput("guest$it@example.invalid",MeetingGuestType.REQUIRED)}
        assertTrue(f.writer.create("limit",token,draft,tooMany) is MeetingMutationResult.NeedsReview);assertNull(f.store.operationHeader("limit"));assertEquals(0,f.p.batchCount)
    }}
    @Test fun hiddenSyncOffCalendarStillUsesExplicitOwnerRightsAndBlankEventUidEnrichesWithoutRewrite()=runBlocking{fixture{f->
        f.p.setCalendar("visible","0");f.p.setCalendar("sync_events","0")
        val creation=f.creation();assertFalse(creation.calendar.providerVisible);assertFalse(creation.calendar.syncEnabled)
        f.p.afterCommit={f.p.db.execSQL("UPDATE events SET _sync_id='' ")}
        assertTrue(f.writer.create("local",creation.token,draft,guests) is MeetingMutationResult.LocalSaved)
        assertTrue(JSONObject(f.store.operation("local")!!.resultRef!!).isNull("_sync_id"))
        f.p.afterCommit=null;f.p.db.execSQL("UPDATE events SET _sync_id='known-created-uid'")
        assertTrue(f.writer.recover("local") is MeetingMutationResult.LocalSaved)
        assertEquals("known-created-uid",JSONObject(f.store.operation("local")!!.resultRef!!).getString("_sync_id"));assertEquals(1,f.p.batchCount)
        f.p.db.execSQL("UPDATE events SET _sync_id='foreign-replacement'")
        assertEquals(common(MutationResult.Uncertain("local",1)),f.writer.recover("local"))
    }}

    @Test fun boundedParcelAndInvalidProviderZoneRefuseBeforeJournalOrBatch()=runBlocking{fixture{f->
        val token=f.creation().token
        val many=List(200){MeetingGuestInput("guest$it@example.invalid",MeetingGuestType.REQUIRED,"N".repeat(256))}
        assertTrue(f.writer.create("parcel",token,draft,many) is MeetingMutationResult.NeedsReview)
        assertNull(f.store.operationHeader("parcel"));assertEquals(0,f.p.batchCount)
        assertEquals(common(MutationResult.ValidationFailed(setOf(InvalidEventField.ZONE))),f.writer.create("zone",token,draft.copy(eventZone=ZoneOffset.ofHoursMinutes(5,30)),guests))
        assertNull(f.store.operationHeader("zone"))
    }}

    @Test fun learnedEventUidCannotDowngradeToNullBlankOrUnavailableProjection()=runBlocking{
        for(field in listOf("_sync_id","uid2445"))for(missing in listOf<String?>(null,""))fixture{f->
            val token=f.creation().token;f.p.afterCommit={f.p.db.execSQL("UPDATE events SET $field='known-created-identity'")}
            assertTrue(f.writer.create("pinned",token,draft,guests) is MeetingMutationResult.LocalSaved)
            f.p.afterCommit=null;f.p.db.execSQL("UPDATE events SET $field = ?",arrayOf(missing));f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("pinned",1)),f.writer.recover("pinned"))
            assertTrue(f.p.queries.none{it.path=="attendees"});assertEquals("known-created-identity",JSONObject(f.store.operation("pinned")!!.resultRef!!).getString(field))
        }
        fixture{f->val token=f.creation().token;f.p.afterCommit={f.p.db.execSQL("UPDATE events SET uid2445='known-uid2445'")}
            assertTrue(f.writer.create("projection",token,draft,guests) is MeetingMutationResult.LocalSaved)
            f.p.afterCommit=null;f.p.rejectEventUidProjection=true;f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("projection",1)),f.writer.recover("projection"));assertTrue(f.p.queries.none{it.path=="attendees"})
        }
    }

}
