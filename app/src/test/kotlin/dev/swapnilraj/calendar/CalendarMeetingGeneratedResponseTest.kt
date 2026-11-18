package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.SyntheticCloneProviderPolicy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarMeetingGeneratedResponseTest {
    private val start=Instant.parse("2026-11-18T09:00:00Z")
    private val selected=EventRef(CalendarId(2),10,start.plusSeconds(86400),start.plusSeconds(90000),RecurrenceRef(10,start.plusSeconds(86400)))
    private data class Fixture(val p: CalendarMeetingSeriesWriterTest.Provider,val writer: CalendarMeetingWriter,val reader: CalendarReader,val store: CalendarLocalStore,val permissions: MutableSet<String>)
    private suspend fun fixture(providerClonePolicy:ProviderClonePolicy=SyntheticCloneProviderPolicy,block:suspend (Fixture)->Unit) {
        val context=RuntimeEnvironment.getApplication();val p=CalendarMeetingSeriesWriterTest.Provider(start);p.enableGenerated()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val name="generated-response-${UUID.randomUUID()}.db"
        val store=CalendarLocalStore(context,name);val permissions=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val writer=CalendarMeetingWriter(context.contentResolver,reader,store,permissions::contains,providerClonePolicy=providerClonePolicy)
        try {block(Fixture(p,writer,reader,store,permissions))} finally {scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.db.close();context.deleteDatabase(name)}
    }
    private suspend fun Fixture.edit(ref:EventRef=selected):EditableMeeting {
        val result=writer.loadForEdit(ref);assertTrue(result.toString(),result is MeetingReadResult.Ready);return (result as MeetingReadResult.Ready).value
    }
    private fun respond(scope:RecurrenceScope=RecurrenceScope.THIS,response:MeetingResponse=MeetingResponse.YES)=MeetingCommand.Respond(scope,response)
    private fun common(result:MutationResult)=MeetingMutationResult.Common(result)
    private fun CalendarMeetingSeriesWriterTest.Provider.count(table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)}

    @Test fun visibleReadOnlyDiscoveryIssuesCompositeWithBothScopesButHiddenKeepsOldAllWithoutAudit()=runBlocking {
        for(visibility in listOf("1","0",null))fixture {f->
            f.p.set("events","guestsCanSeeGuests",visibility,10);f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            val edit=f.edit();assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.ALL).respond)
            assertEquals(if(visibility=="1")AccessDecision.ALLOWED else AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.THIS).respond)
            assertTrue(edit.token.value.startsWith(if(visibility=="1")"meeting-generated-edit-v1:" else "meeting-series-edit-v1:"))
            if(visibility!="1")assertFalse(f.p.queries.any{it.path=="attendees"&&it.fields==null})
            assertEquals(0,f.p.batchCount)
        }
    }
    @Test fun eachResponseInsertsOneNativeExceptionPreservingMasterGuestsRawRulesAndDefaultReminders()=runBlocking {
        for(response in MeetingResponse.entries)fixture {f->
            f.p.set("events","rrule","FREQ=DAILY;COUNT=100",10);f.p.set("events","exdate","20261231T090000Z",10)
            val edit=f.edit();val before=f.p.count("events")
            val result=f.writer.mutate("new-this",selected,edit.token,respond(response=response))
            assertTrue(result.toString(),result is MeetingMutationResult.LocalSaved)
            val ref=(result as MeetingMutationResult.LocalSaved).selected;assertEquals(RecurrenceScope.THIS,result.scope);assertEquals(selected.copy(eventId=ref.eventId),ref)
            assertEquals(before+1,f.p.count("events"));assertEquals("3",f.p.value("events","selfAttendeeStatus",10));assertEquals("1",f.p.value("events","eventStatus",ref.eventId))
            assertEquals(CalendarMeetingWriter.responseStatus(response).toString(),f.p.value("events","selfAttendeeStatus",ref.eventId))
            assertNull(f.p.value("events","_sync_id",ref.eventId));assertEquals("uid-10",f.p.value("events","uid2445",ref.eventId));assertNull(f.p.value("events","rrule",ref.eventId))
            assertEquals("FREQ=DAILY;COUNT=100",f.p.value("events","rrule",10));assertEquals("20261231T090000Z",f.p.value("events","exdate",10))
            assertEquals("3",f.p.value("attendees","attendeeStatus",110));assertEquals("1",f.p.value("attendees","attendeeStatus",999))
            f.p.db.rawQuery("SELECT minutes,method FROM reminders WHERE event_id=?",arrayOf(ref.eventId.toString())).use{c->assertTrue(c.moveToFirst());assertEquals(-1,c.getInt(0));assertEquals(0,c.getInt(1));assertFalse(c.moveToNext())}
            assertEquals(setOf("dtstart","dtend","eventTimezone","allDay","originalInstanceTime","originalAllDay"),f.p.correctionValues.single().keySet())
            assertEquals("VERIFIED",f.store.operation("new-this")!!.phase)
        }
    }
    @Test fun compositeAllConsumesBaseWithoutFullGuestRequeriesAndKeepsExactCallerFingerprint()=runBlocking {fixture {f->
        val edit=f.edit();f.p.queries.clear()
        val result=f.writer.mutate("all-composite",selected,edit.token,respond(RecurrenceScope.ALL))
        assertTrue(result.toString(),result is MeetingMutationResult.LocalSaved)
        assertFalse(f.p.queries.any{it.path=="attendees"&&(it.fields==null||it.where=="event_id = ?")})
        assertTrue(f.store.operation("all-composite")!!.resultRef!!.contains(edit.token.value))
        f.p.queries.clear();assertTrue(f.writer.recover("all-composite") is MeetingMutationResult.LocalSaved)
        assertFalse(f.p.queries.any{it.path=="attendees"&&it.fields==null})
    }}
    @Test fun unsupportedOpaquePropertiesAndDeletedIdentityProfilesKeepBaseAllAndNeverJournalGeneratedCommand()=runBlocking {
        for(mode in 0..4)fixture {f->
            when(mode){0->f.p.set("events","opaque_event","Unknown event",10);1->f.p.set("attendees","opaque_child","Unknown child",110)
                2->f.p.set("events","hasExtendedProperties","1",10)
                3->f.p.db.execSQL("INSERT INTO extendedproperties VALUES(9999,10,'x','opaque')")
                4->f.p.set("events","originalInstanceTime",null,14)}
            val edit=f.edit();assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.ALL).respond);assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.THIS).respond)
            assertTrue(f.writer.mutate("unsupported",selected,edit.token,respond()) is MeetingMutationResult.Unsupported)
            assertNull(f.store.operation("unsupported"));assertEquals(0,f.p.batchCount)
        }
    }
    @Test fun deletedLocalOrSyncOnlyExceptionOwningOriginalKeyPreventsGeneratedCreation()=runBlocking {
        for(syncOnly in listOf(false,true))fixture {f->
            f.p.set("events","originalInstanceTime",selected.instanceStart.toEpochMilli().toString(),14)
            if(syncOnly)f.p.set("events","original_id",null,14)
            val edit=f.edit();assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.THIS).respond)
            assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.ALL).respond);assertEquals(0,f.p.batchCount)
        }
    }
    @Test fun invalidAckAlwaysStaysAttemptedAcrossReopenAndNeverSearchesOrReplays()=runBlocking {
        val bad=listOf<(Uri)->Uri>({Uri.parse("content://foreign.invalid/exception/10/1000")},{Uri.parse("content://${CalendarContract.AUTHORITY}/events/1000")},
            {Uri.parse("content://${CalendarContract.AUTHORITY}/exception/11/1000")},{it.buildUpon().appendQueryParameter("x","1").build()},
            {it.buildUpon().fragment("x").build()},{Uri.parse("content://${CalendarContract.AUTHORITY}/exception/10/01000")})
        for(change in bad)fixture {f->
            val edit=f.edit();f.p.generatedAck=change
            val first=f.writer.mutate("bad-ack",selected,edit.token,respond());assertTrue(first.toString(),first is MeetingMutationResult.Common && first.outcome is MutationResult.Uncertain)
            assertEquals("ATTEMPTED",f.store.operation("bad-ack")!!.phase);f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("bad-ack",null)),f.writer.recover("bad-ack"));assertTrue(f.p.queries.isEmpty())
            f.writer.mutate("bad-ack",selected,edit.token,respond());assertEquals(1,f.p.batchCount)
        }
    }
    @Test fun committedRecoveryNeedsNoEditSnapshotRequiresActualInstanceAndRetainsKnownSyncProof()=runBlocking {fixture {f->
        val edit=f.edit();f.p.delayGeneratedInstance=true
        val pending=f.writer.mutate("known",selected,edit.token,respond());assertTrue(pending.toString(),pending is MeetingMutationResult.Common && pending.outcome is MutationResult.Uncertain)
        val id=JSONObject(f.store.operation("known")!!.resultRef!!).getLong("event");assertEquals("COMMITTED",f.store.operation("known")!!.phase)
        f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots");f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        f.p.db.execSQL("INSERT INTO instances VALUES(?,?,?)",arrayOf(id,selected.instanceStart.toEpochMilli(),selected.instanceEnd.toEpochMilli()))
        f.p.set("events","_sync_id","new-response-sync",id)
        assertTrue(f.writer.recover("known") is MeetingMutationResult.LocalSaved)
        f.p.set("events","_sync_id",null,id);assertEquals(common(MutationResult.Uncertain("known",id)),f.writer.recover("known"));assertEquals(1,f.p.batchCount)
    }}
    @Test fun correctionFailureRollsBackCloneAndPermitsOnlyExactExplicitRetry()=runBlocking {fixture {f->
        val edit=f.edit();val events=f.p.count("events");val children=f.p.count("attendees");f.p.failCorrection=true
        assertEquals(common(MutationResult.Unavailable),f.writer.mutate("rollback",selected,edit.token,respond()))
        assertEquals(events,f.p.count("events"));assertEquals(children,f.p.count("attendees"));assertEquals("FAILED",f.store.operation("rollback")!!.phase)
        assertEquals(common(MutationResult.SafeToRetry("rollback")),f.writer.recover("rollback"))
        f.p.failCorrection=false;assertTrue(f.writer.mutate("rollback",selected,edit.token,respond()) is MeetingMutationResult.LocalSaved)
    }}
    @Test fun sourceRaceRollsBackBeforeInsertionAndLostAckNeverEnablesReplay()=runBlocking {
        for(lost in listOf(false,true))fixture {f->
            val edit=f.edit();val before=f.p.count("events")
            if(lost)f.p.lostAck=true else f.p.beforeBatch={f.p.set("events","description","External change",10)}
            val outcome=f.writer.mutate("race",selected,edit.token,respond())
            if(lost){assertTrue(outcome is MeetingMutationResult.Common&&outcome.outcome is MutationResult.Uncertain);assertEquals(before+1,f.p.count("events"));assertEquals("ATTEMPTED",f.store.operation("race")!!.phase)}
            else {assertEquals(common(MutationResult.Unavailable),outcome);assertEquals(before,f.p.count("events"));assertEquals("FAILED",f.store.operation("race")!!.phase)}
        }
    }
    @Test fun observedDaylightIntervalAndMultiDayAllDayBoundsArePreservedWithoutReadingNominalDuration()=runBlocking {
        for(allDay in listOf(false,true))fixture {f->
            val begin=if(allDay)Instant.parse("2026-11-19T00:00:00Z")else selected.instanceStart
            val end=begin.plusSeconds(if(allDay)3*86400L else 23*3600L)
            f.p.set("events","duration","P1D",10)
            if(allDay){f.p.db.execSQL("UPDATE events SET allDay=1,originalAllDay=CASE WHEN original_id IS NULL THEN NULL ELSE 1 END,originalInstanceTime=originalInstanceTime-32400000");f.p.set("events","dtstart",Instant.parse("2026-11-18T00:00:00Z").toEpochMilli().toString(),10)}
            f.p.db.execSQL("UPDATE instances SET begin=?,end=? WHERE event_id=10",arrayOf(begin.toEpochMilli(),end.toEpochMilli()))
            val ref=selected.copy(instanceStart=begin,instanceEnd=end,recurrence=RecurrenceRef(10,begin));val edit=f.edit(ref)
            val outcome=f.writer.mutate("actual-bounds",ref,edit.token,respond());assertTrue(outcome.toString(),outcome is MeetingMutationResult.LocalSaved)
            val saved=(outcome as MeetingMutationResult.LocalSaved).selected;assertEquals(ref.copy(eventId=saved.eventId),saved);assertEquals("P1D",f.p.value("events","duration",10))
        }
    }
    @Test fun exactRetirementFencesMissingAndFailedCommandsButCannotRetireAttemptedOrCommitted()=runBlocking {
        for(mode in 0..3)fixture {f->
            val edit=f.edit();val cmd=respond();val id="retire-generated"
            when(mode){1->{f.p.failCorrection=true;f.writer.mutate(id,selected,edit.token,cmd)}
                2->{f.p.lostAck=true;f.writer.mutate(id,selected,edit.token,cmd)}
                3->{f.p.delayGeneratedInstance=true;f.writer.mutate(id,selected,edit.token,cmd)}}
            val retirement=CalendarOperationRetirement(f.store)
            val outcome=retirement.retire(RetirableOperation.Meeting(id,selected,edit.token,cmd))
            if(mode<2){assertEquals(OperationRetirementResult.Retired(id),outcome);val batches=f.p.batchCount
                f.permissions.clear();assertEquals(common(MutationResult.Retired(id)),f.writer.mutate(id,selected,edit.token,cmd));assertEquals(common(MutationResult.Retired(id)),f.writer.recover(id));assertEquals(batches,f.p.batchCount)}
            else assertTrue(outcome.toString(),outcome is OperationRetirementResult.Blocked)
        }
    }
    @Test fun currentPermissionsAndNamespaceAreRecheckedBeforeDispatchAndRecovery()=runBlocking {
        fixture {f->val edit=f.edit();f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertEquals(common(MutationResult.PermissionRequired),f.writer.mutate("denied",selected,edit.token,respond()));assertNull(f.store.operation("denied"));assertEquals(0,f.p.batchCount)
            f.permissions.clear();assertEquals(MeetingReadResult.PermissionRequired,f.writer.loadForEdit(selected))}
        fixture {f->val edit=f.edit();f.p.delayGeneratedInstance=true
            f.writer.mutate("namespace",selected,edit.token,respond());val id=JSONObject(f.store.operation("namespace")!!.resultRef!!).getLong("event")
            f.p.set("calendars","_sync_id","replacement-calendar",2);f.p.queries.clear()
            assertEquals(common(MutationResult.Uncertain("namespace",id)),f.writer.recover("namespace"))
            assertFalse(f.p.queries.any{it.path=="events"||it.path=="attendees"});assertEquals(1,f.p.batchCount)}
    }
    @Test fun inheritedUidLossAndUnexpectedNativeBoundsRemainCommittedUncertain()=runBlocking {
        for(changeUid in listOf(false,true))fixture {f->val edit=f.edit()
            f.p.generatedEndDelta=1
            val outcome=f.writer.mutate("verify",selected,edit.token,respond());assertTrue(outcome is MeetingMutationResult.Common&&outcome.outcome is MutationResult.Uncertain)
            val id=JSONObject(f.store.operation("verify")!!.resultRef!!).getLong("event")
            if(changeUid)f.p.set("events","uid2445",null,id)
            else f.p.db.execSQL("UPDATE instances SET end=? WHERE event_id=?",arrayOf(selected.instanceEnd.toEpochMilli(),id))
            val recovered=f.writer.recover("verify")
            if(changeUid)assertEquals(common(MutationResult.Uncertain("verify",id)),recovered)else assertTrue(recovered.toString(),recovered is MeetingMutationResult.LocalSaved)
            assertEquals(1,f.p.batchCount)
        }
    }

    @Test fun corruptOrForeignCompositeAuthorityNeverFallsBackToBaseOrReadsPrivateProviderRows()=runBlocking {
        for(mode in 0..2)for(scope in listOf(RecurrenceScope.THIS,RecurrenceScope.ALL))fixture {f->
            val edit=f.edit();val json=JSONObject(f.store.edit(edit.token.value)!!)
            when(mode){0->json.put("version",999);1->json.getJSONObject("base").put("feature","UNKNOWN_SOURCE")
                2->json.getJSONObject("base").getJSONArray("events").getJSONObject(0).put("calendar_id","999")}
            val raw=json.toString();val forged=EditToken(MeetingGeneratedSource.token(raw));f.store.saveEdit(forged.value,raw);f.p.queries.clear()
            val result=f.writer.mutate("corrupt",selected,forged,respond(scope))
            assertTrue(result.toString(),result is MeetingMutationResult.Common&&result.outcome==MutationResult.Unavailable)
            assertEquals(0,f.p.batchCount);assertTrue(f.p.queries.isEmpty());assertNull(f.store.operation("corrupt"))
        }
    }
    @Test fun genuineAuditIoFailureNeverAdvertisesReadyOrPersistsAuthority()=runBlocking {fixture {f->
        f.p.failPath="reminders";assertEquals(MeetingReadResult.Unavailable,f.writer.loadForEdit(selected))
        f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots",null).use{c->c.moveToFirst();assertEquals(0,c.getInt(0))};assertEquals(0,f.p.batchCount)
    }}

    @Test fun missingOrDuplicateSelfEvidenceAndNullableEventStatusAreNeverGuessed()=runBlocking {
        for(mode in 0..2)fixture {f->
            when(mode){0->f.p.db.execSQL("DELETE FROM attendees WHERE _id=110");1->f.p.addGuest(10,998," ACTOR@example.invalid ")
                2->f.p.set("events","eventStatus",null,10)}
            if(mode<2){assertTrue(f.writer.loadForEdit(selected) is MeetingReadResult.NeedsReview);assertEquals(0,f.p.batchCount)}
            else {val edit=f.edit();val result=f.writer.mutate("nullable-status",selected,edit.token,respond(response=MeetingResponse.NO))
                assertTrue(result.toString(),result is MeetingMutationResult.LocalSaved);assertNull(f.p.value("events","eventStatus",(result as MeetingMutationResult.LocalSaved).selected.eventId))}
        }
    }

    @Test fun unverifiedGeneratedClonePolicyLeavesAllSelfOnlyAndNeverAuditsOrJournalsClone()=runBlocking {fixture(providerClonePolicy=ProviderClonePolicy.Unverified){f->
        val edit=f.edit();assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.THIS).respond);assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.ALL).respond)
        assertFalse(f.p.queries.any{it.path=="attendees"&&it.fields==null})
        assertTrue(f.writer.mutate("unverified-generated",selected,edit.token,respond()) is MeetingMutationResult.NeedsReview)
        assertNull(f.store.operation("unverified-generated"));assertEquals(0,f.p.batchCount)
        assertTrue(f.writer.mutate("inplace-all",selected,edit.token,respond(RecurrenceScope.ALL)) is MeetingMutationResult.LocalSaved)
    }}
    @Test fun generatedProfileRevocationAtPreparedAndAttemptedBoundaryNeverDispatchesOrReplays()=runBlocking {
        for(stopAt in listOf("PREPARED","ATTEMPTED")){
            var store:CalendarLocalStore?=null;var denied=true
            val policy=ProviderClonePolicy{op->if(denied&&store?.operationHeader("generated-profile")?.phase==stopAt)null else SyntheticCloneProviderPolicy.currentProfile(op)}
            fixture(providerClonePolicy=policy){f->store=f.store;val edit=f.edit();val frozen=respond()
                val outcome=f.writer.mutate("generated-profile",selected,edit.token,frozen)
                assertEquals(stopAt,f.store.operation("generated-profile")!!.phase);assertEquals(0,f.p.batchCount);denied=false
                if(stopAt=="PREPARED"){assertTrue(outcome is MeetingMutationResult.NeedsReview);assertTrue(f.writer.mutate("generated-profile",selected,edit.token,frozen) is MeetingMutationResult.LocalSaved)}
                else {assertEquals(common(MutationResult.Uncertain("generated-profile",null)),outcome);assertEquals(common(MutationResult.Uncertain("generated-profile",null)),f.writer.mutate("generated-profile",selected,edit.token,frozen));assertEquals(0,f.p.batchCount)}
            }
        }
    }
    @Test fun profileLossAfterKnownGeneratedCommitKeepsReadOnlyRecoveryAndCompositeAllAvailable()=runBlocking {
        var admitted=true;val policy=ProviderClonePolicy{op->if(admitted)SyntheticCloneProviderPolicy.currentProfile(op)else null}
        fixture(providerClonePolicy=policy){f->val edit=f.edit();admitted=false
            assertTrue(f.writer.mutate("existing-composite-all",selected,edit.token,respond(RecurrenceScope.ALL)) is MeetingMutationResult.LocalSaved)
            assertEquals(1,f.p.batchCount)
        }
        admitted=true
        fixture(providerClonePolicy=policy){f->val edit=f.edit();f.p.delayGeneratedInstance=true
            f.writer.mutate("known-generated-profile",selected,edit.token,respond());val id=JSONObject(f.store.operation("known-generated-profile")!!.resultRef!!).getLong("event")
            assertEquals("COMMITTED",f.store.operation("known-generated-profile")!!.phase)
            admitted=false;f.p.delayGeneratedInstance=false;f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            f.p.db.execSQL("INSERT INTO instances VALUES(?,?,?)",arrayOf(id,selected.instanceStart.toEpochMilli(),selected.instanceEnd.toEpochMilli()))
            assertTrue(f.writer.recover("known-generated-profile") is MeetingMutationResult.LocalSaved);assertEquals(1,f.p.batchCount)
        }
    }

}
