package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CalendarOwnedMeetingWriterTest {
    private val start=Instant.parse("2026-11-18T09:00:00Z")
    private val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("Europe/London"),"Synthetic owned meeting","Room","Notes",listOf(10),1,2,identity)
    private val inputs=listOf(MeetingGuestInput("required@example.invalid",MeetingGuestType.REQUIRED,"Required"),MeetingGuestInput("optional@example.invalid",MeetingGuestType.OPTIONAL))
    internal class Provider:ContentProvider(){
        val base=CalendarRecurringCreateTest.Provider().apply{nativeMeetingMode=true}
        val db get()=base.db
        data class Write(val path:String,val values:Set<String>,val selection:String?)
        val writes=mutableListOf<Write>();val guestQueries=mutableListOf<List<String>?>();val guestReadCounts=mutableListOf<Int>()
        val guestGuards=mutableListOf<Pair<String?,List<String>>>()
        var batchCount=0;var failGuest=false;var lostAck=false;var badGuestAck=false;var beforeBatch:(()->Unit)?=null;var afterBatch:(()->Unit)?=null
        var committed=false;var failReadsAfterCommit=false;var beforeGuestRead:(()->Unit)?=null
        init{
            db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2,3'")
            db.execSQL("ALTER TABLE events ADD COLUMN opaque_event TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN dirty INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE attendees ADD COLUMN opaque_guest TEXT")
            db.execSQL("ALTER TABLE reminders ADD COLUMN opaque_reminder TEXT")
            // Native Attendees queries expose joined Events.UID_2445 for exact-parent guards.
            db.execSQL("CREATE VIEW owned_attendees AS SELECT a.*,e.calendar_id,e.organizer,e.uid2445,e.selfAttendeeStatus,e.rrule,e.dtstart,e.dtend FROM attendees a JOIN events e ON a.event_id=e._id")
        }
        override fun onCreate()=true
        override fun getType(uri:Uri):String?=null
        override fun query(uri:Uri,fields:Array<out String>?,where:String?,args:Array<out String>?,sort:String?):Cursor{
            if(committed&&failReadsAfterCommit)error("Synthetic read unavailable")
            if(uri.pathSegments.first()!="attendees")return base.query(uri,fields,where,args,sort)
            guestQueries+=fields?.toList();beforeGuestRead?.also{beforeGuestRead=null}?.invoke()
            guestGuards+=where to args.orEmpty().toList()
            val item=uri.lastPathSegment?.toLongOrNull();val selection=listOfNotNull(item?.let{"_id = ?"},where?.let{"($it)"}).joinToString(" AND ").takeIf(String::isNotEmpty)
            return db.query("owned_attendees",fields,selection,(listOfNotNull(item?.toString())+args.orEmpty()).toTypedArray(),null,null,sort).also{guestReadCounts+=it.count}
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri{
            writes+=Write(uri.pathSegments.first(),values!!.keySet(),null)
            if(failGuest&&uri.pathSegments.first()=="attendees")throw OperationApplicationException("Synthetic guest rollback")
            return base.insert(uri,values)
        }
        override fun update(uri:Uri,values:ContentValues?,where:String?,args:Array<out String>?):Int{
            val path=uri.pathSegments.first();check(uri.pathSegments.size==1&&where!=null){"Native collection update required"};check(!values!!.containsKey("selfAttendeeStatus"))
            writes+=Write(path,values.keySet(),where)
            if(path=="attendees"){
                check(values.keySet()==setOf("attendeeType"));if(failGuest)throw OperationApplicationException("Synthetic guest rollback")
                db.query("attendees",arrayOf("attendeeEmail"),where,args,null,null,null).use{c->while(c.moveToNext())check(c.getString(0)!="owner@example.invalid")}
            }
            val count=db.update(path,values,where,args)
            if(path=="events")db.execSQL("UPDATE events SET dirty=1")else db.execSQL("UPDATE events SET dirty=1 WHERE _id IN (SELECT event_id FROM attendees)")
            return count
        }
        override fun delete(uri:Uri,where:String?,args:Array<out String>?):Int{
            val path=uri.pathSegments.first();check(path in setOf("attendees","reminders")&&uri.pathSegments.size==1&&where!=null)
            writes+=Write(path,emptySet(),where)
            if(path=="attendees"&&failGuest)throw OperationApplicationException("Synthetic guest rollback")
            val count=db.delete(path,where,args)
            if(path=="reminders")db.execSQL("UPDATE events SET hasAlarm=EXISTS(SELECT 1 FROM reminders WHERE event_id=events._id)")
            db.execSQL("UPDATE events SET dirty=1");return count
        }
        override fun applyBatch(ops:ArrayList<ContentProviderOperation>):Array<ContentProviderResult>{
            batchCount++;beforeBatch?.also{beforeBatch=null}?.invoke();db.beginTransaction()
            val results=try{super.applyBatch(ops).also{db.setTransactionSuccessful()}}finally{db.endTransaction()}
            committed=true;afterBatch?.invoke();if(lostAck)error("Synthetic lost acknowledgement")
            if(badGuestAck)results.indices.forEach{i->if(results[i].uri?.pathSegments?.firstOrNull()=="attendees")results[i]=ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/attendees/invalid"))}
            return results
        }
        fun value(table:String,key:String,id:Long)=db.query(table,arrayOf(key),"_id=?",arrayOf(id.toString()),null,null,null).use{check(it.moveToFirst());it.getString(0)}
        fun count(table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)}
    }
    internal data class Fixture(val p:Provider,val writer:CalendarMeetingWriter,val reader:CalendarReader,val store:CalendarLocalStore,val grants:MutableSet<String>,val selected:EventRef)
    internal suspend fun fixture(block:suspend(Fixture)->Unit){
        val context=RuntimeEnvironment.getApplication();val p=Provider();org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val store=CalendarLocalStore(context,"owned-meeting-${UUID.randomUUID()}.db")
        val grants=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR);val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val writer=CalendarMeetingWriter(context.contentResolver,reader,store,grants::contains)
        try{
            val creation=(writer.loadForCreate(identity) as MeetingReadResult.Ready).value
            val outcome=writer.create("seed",creation.token,draft,inputs)
            assertTrue(outcome.toString(),outcome is MeetingMutationResult.LocalSaved)
            val seed=outcome as MeetingMutationResult.LocalSaved
            p.db.execSQL("UPDATE events SET _sync_id='owned-event-A',uid2445='owned-public-A',opaque_event='Synthetic opaque event'")
            p.db.execSQL("UPDATE attendees SET opaque_guest='Synthetic opaque guest'")
            p.batchCount=0;p.committed=false;p.writes.clear();p.guestQueries.clear();p.base.queries.clear()
            block(Fixture(p,writer,reader,store,grants,seed.selected))
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.db.close();context.deleteDatabase(store.databaseName)}
    }
    internal suspend fun Fixture.edit():EditableMeeting{val r=writer.loadForEdit(selected);assertTrue(r.toString(),r is MeetingReadResult.Ready);return(r as MeetingReadResult.Ready).value}
    private fun edit(patch:EventPatch=EventPatch(),guests:List<MeetingGuestChange> = emptyList())=MeetingCommand.EditOwned(RecurrenceScope.THIS,patch,guests)
    private fun common(result:MutationResult)=MeetingMutationResult.Common(result)
    private fun Fixture.rows()=p.db.rawQuery("SELECT _id,attendeeEmail,attendeeStatus,attendeeType,opaque_guest FROM attendees ORDER BY _id",null).use{c->buildList{while(c.moveToNext())add(List(c.columnCount){if(c.isNull(it))null else c.getString(it)})}}

    @Test fun nativeJoinedUidGuardMatchesOnlyTheExactParentThroughDocumentedGuestFields()=runBlocking{fixture{f->
        val createdUid=org.json.JSONObject(f.store.operation("seed")!!.resultRef!!).getJSONObject("plan").getJSONObject("event").getString("uid2445")
        assertTrue(f.p.guestGuards.any{(selection,args)->selection?.contains("uid2445 = ?")==true&&args.lastOrNull()==createdUid})
        val guard="event_id = ? AND calendar_id = ? AND organizer = ? AND uid2445 = ?"
        fun count(uid:String)=RuntimeEnvironment.getApplication().contentResolver.query(CalendarContract.Attendees.CONTENT_URI,
            CalendarMeetingWriter.attendeeFields,guard,arrayOf(f.selected.eventId.toString(),identity.calendarId.value.toString(),identity.owner!!,uid),null)!!.use{cursor->
            assertEquals(CalendarMeetingWriter.attendeeFields.toList(),cursor.columnNames.toList())
            cursor.count
        }
        assertEquals(3,count("owned-public-A"));assertEquals(0,count("foreign-parent-uid"))
        assertTrue(f.p.guestQueries.all{fields->fields!=null&&fields.all{it in CalendarMeetingWriter.attendeeFields}})
        assertEquals(0,f.p.batchCount)
    }}

    @Test fun eligibilityIsReadOnlyAndUnknownClonePolicyDoesNotBlockOwnedSparseEdits()=runBlocking{fixture{f->
        f.grants.remove(Manifest.permission.WRITE_CALENDAR);val e=f.edit();assertTrue(e.token.value.startsWith("meeting-owned-edit-v1:"));val rights=e.scopes.getValue(RecurrenceScope.THIS)
        assertEquals(AccessDecision.ALLOWED,rights.event.edit);assertEquals(AccessDecision.ALLOWED,rights.addGuests);assertEquals(AccessDecision.DENIED,rights.respond);assertEquals(2,rights.guests.size)
        assertEquals(0,f.p.batchCount);assertTrue(f.p.guestQueries.all{fields->fields!=null&&fields.all{it in CalendarMeetingWriter.attendeeFields}})
        assertEquals(common(MutationResult.PermissionRequired),f.writer.mutate("no-write",f.selected,e.token,edit(EventPatch(title=FieldChange.Set("Changed")))));assertNull(f.store.operation("no-write"))
        f.grants+=Manifest.permission.WRITE_CALENDAR
        val before=f.rows();assertTrue(f.writer.mutate("title",f.selected,e.token,edit(EventPatch(title=FieldChange.Set("Changed")))) is MeetingMutationResult.LocalSaved)
        assertEquals(before,f.rows());assertEquals("Synthetic opaque event",f.p.value("events","opaque_event",f.selected.eventId));assertEquals("owned-event-A",f.p.value("events","_sync_id",f.selected.eventId))
        assertEquals(setOf("title"),f.p.writes.single().values)
    }}
    @Test fun guestDeltaAddsInvitedChangesOnlyTypeRemovesExactGuestAndPreservesOtherResponsesAndOpaqueColumns()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE attendees SET attendeeStatus=2 WHERE attendeeEmail='required@example.invalid'")
        val e=f.edit();val required=e.scopes.getValue(RecurrenceScope.THIS).guests.single{it.attendee.email=="required@example.invalid"};val optional=e.scopes.getValue(RecurrenceScope.THIS).guests.single{it.attendee.email=="optional@example.invalid"}
        val old=f.rows();val changes=listOf(MeetingGuestChange.ChangeType(required.ref,MeetingGuestType.OPTIONAL),MeetingGuestChange.Remove(optional.ref),MeetingGuestChange.Add(MeetingGuestInput("added@example.invalid",MeetingGuestType.REQUIRED,"Added")))
        val result=f.writer.mutate("guest-delta",f.selected,e.token,edit(guests=changes));assertTrue(result.toString(),result is MeetingMutationResult.LocalSaved)
        val now=f.rows();assertEquals(old.first(),now.first());val changed=now.single{it[1]=="required@example.invalid"};assertEquals("2",changed[2]);assertEquals("2",changed[3]);assertEquals("Synthetic opaque guest",changed[4])
        assertFalse(now.any{it[1]=="optional@example.invalid"});val added=now.single{it[1]=="added@example.invalid"};assertEquals("3",added[2]);assertEquals("1",added[3]);assertNull(added[4])
        assertEquals("1",f.p.value("events","selfAttendeeStatus",f.selected.eventId));assertEquals("VERIFIED",f.store.operation("guest-delta")!!.phase)
        f.writer.mutate("guest-delta",f.selected,e.token,edit(guests=changes));assertEquals(1,f.p.batchCount)
    }}
    @Test fun organizerCancellationChangesStatusOnlyAndNeverDeletesOrDeclinesAttendees()=runBlocking{fixture{f->
        val e=f.edit();val before=f.rows();val result=f.writer.mutate("cancel",f.selected,e.token,MeetingCommand.CancelOwned(RecurrenceScope.THIS))
        assertEquals(MeetingMutationResult.LocalCancelled(f.selected,RecurrenceScope.THIS),result);assertEquals(before,f.rows());assertEquals("2",f.p.value("events","eventStatus",f.selected.eventId));assertEquals("1",f.p.value("events","selfAttendeeStatus",f.selected.eventId));assertEquals("0",f.p.value("events","deleted",f.selected.eventId));assertEquals(setOf("eventStatus"),f.p.writes.single().values)
    }}
    @Test fun rescheduleUsesActualNativeBoundsAndPreservesUnchangedPrivacyGuestResponsesAndEndZone()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE events SET availability=77,eventEndTimezone='America/New_York'")
        val e=f.edit();val before=f.rows();val time=EventTime.Timed(start.plusSeconds(7200),start.plusSeconds(10800))
        val result=f.writer.mutate("time",f.selected,e.token,edit(EventPatch(time=FieldChange.Set(time))))
        assertEquals(MeetingMutationResult.LocalSaved(f.selected.copy(instanceStart=time.start,instanceEnd=time.end),RecurrenceScope.THIS),result)
        assertEquals("77",f.p.value("events","availability",f.selected.eventId));assertEquals("2",f.p.value("events","accessLevel",f.selected.eventId));assertEquals("America/New_York",f.p.value("events","eventEndTimezone",f.selected.eventId));assertEquals(before,f.rows())
    }}
    @Test fun nullZoneAndUnknownMetadataSurviveTitleAndAlertOnlyChangesAndNonAlertRowsKeepTheirIds()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE events SET eventTimezone=NULL,eventEndTimezone=NULL,availability=77")
        f.p.db.execSQL("INSERT INTO reminders(event_id,minutes,method,opaque_reminder) VALUES(?,30,2,'Synthetic nonalert')",arrayOf(f.selected.eventId))
        val e=f.edit();val guests=f.rows();val result=f.writer.mutate("null-zone",f.selected,e.token,edit(EventPatch(title=FieldChange.Set("Title"),alertMinutes=FieldChange.Set(listOf(15)))))
        assertTrue(result.toString(),result is MeetingMutationResult.LocalSaved);assertNull(f.p.value("events","eventTimezone",f.selected.eventId));assertNull(f.p.value("events","eventEndTimezone",f.selected.eventId));assertEquals(guests,f.rows())
        f.p.db.rawQuery("SELECT opaque_reminder FROM reminders WHERE method=2",null).use{c->assertTrue(c.moveToFirst());assertEquals("Synthetic nonalert",c.getString(0))}
    }}
    @Test fun changedSourceOrForgedGuestRefCannotDispatchAndRepeatedAddressesNeverResetExistingResponse()=runBlocking{
        for(mode in 0..2)fixture{f->val e=f.edit();val guest=e.scopes.getValue(RecurrenceScope.THIS).guests.first();val command=when(mode){
            0->{f.p.db.execSQL("UPDATE attendees SET attendeeStatus=2 WHERE attendeeEmail='required@example.invalid'");edit(guests=listOf(MeetingGuestChange.ChangeType(guest.ref,MeetingGuestType.OPTIONAL)))}
            1->edit(guests=listOf(MeetingGuestChange.Remove(MeetingGuestRef("forged"))))
            else->edit(guests=listOf(MeetingGuestChange.Add(MeetingGuestInput(" REQUIRED@example.invalid ",MeetingGuestType.REQUIRED))))}
            val result=f.writer.mutate("invalid",f.selected,e.token,command);assertTrue(result.toString(),result is MeetingMutationResult.Invalid||result==common(MutationResult.Conflict(null)));assertNull(f.store.operation("invalid"));assertEquals(0,f.p.batchCount)
        }
    }
    @Test fun rollbackLeavesAllOriginalRowsAndExactFrozenRetryWorksButLostOrMalformedAckNeverReplays()=runBlocking{
        for(mode in 0..2)fixture{f->val e=f.edit();val before=f.rows();val command=edit(EventPatch(title=FieldChange.Set("Atomic title")),listOf(MeetingGuestChange.Add(MeetingGuestInput("new@example.invalid",MeetingGuestType.OPTIONAL))))
            when(mode){0->f.p.failGuest=true;1->f.p.lostAck=true;2->f.p.badGuestAck=true}
            val result=f.writer.mutate("boundary",f.selected,e.token,command)
            if(mode==0){assertEquals(common(MutationResult.Unavailable),result);assertEquals(before,f.rows());assertEquals("FAILED",f.store.operation("boundary")!!.phase);assertEquals(common(MutationResult.SafeToRetry("boundary")),f.writer.recover("boundary"));f.p.failGuest=false;assertTrue(f.writer.mutate("boundary",f.selected,e.token,command) is MeetingMutationResult.LocalSaved)}
            else {assertTrue(result is MeetingMutationResult.Common&&result.outcome is MutationResult.Uncertain);assertEquals("ATTEMPTED",f.store.operation("boundary")!!.phase);val count=f.p.batchCount;assertTrue((f.writer.recover("boundary") as MeetingMutationResult.Common).outcome is MutationResult.Uncertain);f.writer.mutate("boundary",f.selected,e.token,command);assertEquals(count,f.p.batchCount)}
        }
    }
    @Test fun knownCommitRecoveryIsSourceFreeReadOnlyAndNeverAdoptsNamespaceOrUidLoss()=runBlocking{fixture{f->
        val e=f.edit();f.p.failReadsAfterCommit=true
        f.writer.mutate("known",f.selected,e.token,edit(guests=listOf(MeetingGuestChange.Add(MeetingGuestInput("known@example.invalid",MeetingGuestType.OPTIONAL)))))
        assertEquals("COMMITTED",f.store.operation("known")!!.phase);f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots")
        f.p.failReadsAfterCommit=false;f.grants.remove(Manifest.permission.WRITE_CALENDAR)
        assertTrue(f.writer.recover("known") is MeetingMutationResult.LocalSaved);f.p.db.execSQL("UPDATE events SET uid2445=NULL")
        assertEquals(common(MutationResult.Uncertain("known",f.selected.eventId)),f.writer.recover("known"));assertEquals(1,f.p.batchCount)
    }}
    @Test fun strictOwnershipUnknownFlagsAndResourceGuestPolicyRefuseWithoutRewritingUntouchedRows()=runBlocking{
        for(mode in 0..4)fixture{f->when(mode){0->f.p.db.execSQL("UPDATE events SET isOrganizer=NULL,organizer='other@example.invalid'")
            1->f.p.db.execSQL("UPDATE calendars SET ownerAccount='alias@example.invalid'");2->f.p.db.execSQL("UPDATE events SET hasAttendeeData=NULL")
            3->f.p.db.execSQL("INSERT INTO attendees(event_id,attendeeEmail,attendeeRelationship,attendeeType,attendeeStatus) VALUES(?,'OWNER@example.invalid',2,0,1)",arrayOf(f.selected.eventId))
            4->f.p.db.execSQL("UPDATE calendars SET allowedAttendeeTypes=NULL")}
            val r=f.writer.loadForEdit(f.selected)
            if(mode==4){assertTrue(r.toString(),r is MeetingReadResult.Ready);val e=(r as MeetingReadResult.Ready).value;assertEquals(AccessDecision.DENIED,e.scopes.getValue(RecurrenceScope.THIS).addGuests);assertEquals(AccessDecision.ALLOWED,e.scopes.getValue(RecurrenceScope.THIS).event.edit)}
            else assertFalse(r.toString(),r is MeetingReadResult.Ready)
            assertEquals(0,f.p.batchCount)
        }
    }
    @Test fun resourceGuestRemainsReadOnlyAndPermissionLossOrWrongScopeHaveNoJournalOrProviderWrites()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE attendees SET attendeeType=3 WHERE attendeeEmail='required@example.invalid'");val e=f.edit();val resource=e.scopes.getValue(RecurrenceScope.THIS).guests.single{it.attendee.email=="required@example.invalid"};assertTrue(resource.allowed.isEmpty())
        assertTrue(f.writer.mutate("resource",f.selected,e.token,edit(guests=listOf(MeetingGuestChange.Remove(resource.ref)))) is MeetingMutationResult.Invalid);assertNull(f.store.operation("resource"))
        assertTrue(f.writer.mutate("all",f.selected,e.token,MeetingCommand.CancelOwned(RecurrenceScope.ALL)) is MeetingMutationResult.Unsupported);assertNull(f.store.operation("all"))
        f.grants.clear();assertEquals(MeetingReadResult.PermissionRequired,f.writer.loadForEdit(f.selected));assertEquals(0,f.p.batchCount)
    }}
    @Test fun retirementFencesExactOwnedCommandWithoutProviderReadsAndCommittedOrAttemptedOutcomesCannotBeRetired()=runBlocking{
        for(mode in 0..2)fixture{f->val e=f.edit();val command=edit(EventPatch(title=FieldChange.Set("Retirable")))
            if(mode==1){f.p.lostAck=true;f.writer.mutate("retire",f.selected,e.token,command)}else if(mode==2)f.writer.mutate("retire",f.selected,e.token,command)
            val retirement=CalendarOperationRetirement(f.store);val result=retirement.retire(RetirableOperation.Meeting("retire",f.selected,e.token,command))
            if(mode==0){assertEquals(OperationRetirementResult.Retired("retire"),result);f.grants.clear();assertEquals(common(MutationResult.Retired("retire")),f.writer.mutate("retire",f.selected,e.token,command));assertEquals(0,f.p.batchCount)}else assertTrue(result is OperationRetirementResult.Blocked)
        }
    }
    @Test fun authoredUnsupportedZoneDefaultReminderAndMetadataAreRejectedBeforeJournal()=runBlocking {
        for(mode in 0..2)fixture{f->val e=f.edit();val patch=when(mode){0->EventPatch(eventZone=FieldChange.Set(ZoneId.of("+05:30")));1->EventPatch(alertMinutes=FieldChange.Set(listOf(-1)));else->EventPatch(availability=FieldChange.Set(77))}
            val result=f.writer.mutate("invalid-authoring",f.selected,e.token,edit(patch));assertTrue(result is MeetingMutationResult.Common&&result.outcome is MutationResult.ValidationFailed);assertNull(f.store.operation("invalid-authoring"));assertEquals(0,f.p.batchCount)
        }
    }
    @Test fun namespaceReplacementAndConcurrentGuestChangesCannotAdoptOrOverwriteAnotherSource()=runBlocking {
        fixture{f->val e=f.edit();f.p.base.setCalendar("_sync_id","replacement-calendar");f.p.guestQueries.clear();f.p.base.queries.clear()
            assertEquals(common(MutationResult.Conflict(null)),f.writer.mutate("namespace-reuse",f.selected,e.token,edit(EventPatch(title=FieldChange.Set("Must not write")))))
            assertNull(f.store.operation("namespace-reuse"));assertTrue(f.p.guestQueries.isEmpty());assertTrue(f.p.base.queries.none{it.path=="events"});assertEquals(0,f.p.batchCount)}
        fixture{f->val e=f.edit();f.p.beforeBatch={f.p.db.execSQL("UPDATE attendees SET attendeeStatus=2 WHERE attendeeEmail='required@example.invalid'")}
            val result=f.writer.mutate("race",f.selected,e.token,edit(EventPatch(title=FieldChange.Set("Must rollback"))))
            assertEquals(common(MutationResult.Unavailable),result);assertEquals("FAILED",f.store.operation("race")!!.phase);assertEquals("Synthetic owned meeting",f.p.value("events","title",f.selected.eventId))
            f.p.db.rawQuery("SELECT attendeeStatus FROM attendees WHERE attendeeEmail='required@example.invalid'",null).use{c->assertTrue(c.moveToFirst());assertEquals(2,c.getInt(0))}}
    }
    @Test fun oversizedOwnedReadIssuesNoAdditionalSnapshotAndGuestVisibilityRaceCannotReadForeignRows()=runBlocking{fixture{f->
        val before=f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots",null).use{it.moveToFirst();it.getInt(0)}
        val limited=CalendarMeetingWriter(RuntimeEnvironment.getApplication().contentResolver,f.reader,f.store,f.grants::contains,readBudgets=ProviderReadBudgets(maxDetailChildren=1))
        assertEquals(MeetingReadResult.LimitExceeded,limited.loadForEdit(f.selected));assertEquals(before,f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots",null).use{it.moveToFirst();it.getInt(0)})
        f.p.guestReadCounts.clear();f.p.beforeGuestRead={f.p.db.execSQL("UPDATE events SET calendar_id=99,organizer='foreign@example.invalid'")}
        assertFalse(f.writer.loadForEdit(f.selected) is MeetingReadResult.Ready);assertEquals(listOf(0),f.p.guestReadCounts);assertEquals(0,f.p.batchCount)
    }}
    @Test fun knownZeroAttendeeDataRemainsEditableButNeverGrantsGuestMutationAndNullStatusIsPreserved()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE events SET hasAttendeeData=0,eventStatus=NULL")
        val e=f.edit();assertEquals(AccessDecision.DENIED,e.scopes.getValue(RecurrenceScope.THIS).addGuests);assertTrue(e.scopes.getValue(RecurrenceScope.THIS).guests.all{it.allowed.isEmpty()})
        assertTrue(f.writer.mutate("zero-data-guest",f.selected,e.token,edit(guests=listOf(MeetingGuestChange.Add(MeetingGuestInput("new@example.invalid",MeetingGuestType.OPTIONAL))))) is MeetingMutationResult.NeedsReview);assertNull(f.store.operation("zero-data-guest"))
        assertTrue(f.writer.mutate("zero-data-title",f.selected,e.token,edit(EventPatch(title=FieldChange.Set("Sparse title")))) is MeetingMutationResult.LocalSaved)
        assertNull(f.p.value("events","eventStatus",f.selected.eventId));assertEquals("0",f.p.value("events","hasAttendeeData",f.selected.eventId))
    }}

    @Test fun additionalOrganizerIdentityNeverGrantsOwnedMutationEvenWithOwnerCalendarAndOrganizerFlag()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE attendees SET attendeeRelationship=2 WHERE attendeeEmail='required@example.invalid'")
        assertTrue(f.writer.loadForEdit(f.selected) is MeetingReadResult.NeedsReview);assertEquals(0,f.p.batchCount)
    }}

    @Test fun readableSourceWhoseMinimalAssertedBatchExceedsParcelBudgetIssuesNoTokenOrJournal()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE events SET title=?,description=?",arrayOf("x".repeat(40_000),"y".repeat(40_000)))
        withContext(Dispatchers.IO+readBudgetContext(ProviderReadBudgets().mutation)){
            val captured=OwnedMeetingProvider(RuntimeEnvironment.getApplication().contentResolver,f.grants::contains,ProviderReadBudgets()).capture(f.selected)
            assertTrue(captured.canonical(ProviderReadBudgets().maxCanonicalBytes).length>80_000)
        }
        val snapshots=f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots",null).use{it.moveToFirst();it.getInt(0)}
        val journal=f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal",null).use{it.moveToFirst();it.getInt(0)}
        val result=f.writer.loadForEdit(f.selected);assertTrue(result is MeetingReadResult.NeedsReview)
        assertEquals(snapshots,f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM edit_snapshots",null).use{it.moveToFirst();it.getInt(0)})
        assertEquals(journal,f.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal",null).use{it.moveToFirst();it.getInt(0)});assertEquals(0,f.p.batchCount)
    }}

}
