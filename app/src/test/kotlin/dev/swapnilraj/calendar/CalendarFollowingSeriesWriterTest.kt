package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.content.ContentValues
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.SyntheticCloneProviderPolicy
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
class CalendarFollowingSeriesWriterTest {
    private val start=Instant.parse("2026-11-17T09:00:00Z")
    private val zone=ZoneId.of("Europe/London")
    private data class Fixture(val p:CalendarRecurringCreateTest.Provider,val writer:CalendarSeriesWriter,val source:CalendarSeriesReader,
        val reader:CalendarReader,val store:CalendarLocalStore,val permissions:MutableSet<String>,val selected:EventRef)
    private suspend fun fixture(allDay:Boolean=false,ordinal:Int=2,providerClonePolicy:ProviderClonePolicy=SyntheticCloneProviderPolicy,block:suspend(Fixture)->Unit){
        val context=RuntimeEnvironment.getApplication();val p=CalendarRecurringCreateTest.Provider().apply{nativeMeetingMode=true;expandInstances=true;allowUpdates=true
            db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")}
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val store=CalendarLocalStore(context,"following-${UUID.randomUUID()}.db")
        val permissions=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val ordinary=CalendarWriter(context.contentResolver,reader,store,permissions::contains)
        val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
        val time=if(allDay)EventTime.AllDay(CalendarDateRange(LocalDate.of(2026,11,17),LocalDate.of(2026,11,20)))else EventTime.Timed(start,start.plusSeconds(3600))
        val seed=ordinary.create("seed",EventDraft(CalendarId(2),time,if(allDay)null else zone,"Prefix","Room","Notes",listOf(10),1,2,identity,RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(8)))) as MutationResult.LocalSaved
        p.db.execSQL("UPDATE events SET _sync_id='prefix-sync',uid2445='prefix-public-uid'")
        val selected=seed.ref.copy(instanceStart=seed.ref.instanceStart.plusSeconds(ordinal*86400L),instanceEnd=seed.ref.instanceEnd.plusSeconds(ordinal*86400L),recurrence=RecurrenceRef(seed.ref.eventId,seed.ref.instanceStart.plusSeconds(ordinal*86400L)))
        val source=CalendarSeriesReader(context.contentResolver,reader,store,permissions::contains)
        val writer=CalendarSeriesWriter(context.contentResolver,source,store,permissions::contains,providerClonePolicy=providerClonePolicy)
        p.batchCount=0;p.committed=false;p.queries.clear()
        try{block(Fixture(p,writer,source,reader,store,permissions,selected))}finally{scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.db.close();context.deleteDatabase(store.databaseName)}
    }
    private suspend fun Fixture.editable()=(writer.loadForEdit(selected) as SeriesReadResult.Ready).series
    private fun command(edit:EditableSeries,fields:EventPatch=EventPatch(),rule:RecurrenceRuleChanges=RecurrenceRuleChanges(),
        duration:RecurrenceDurationChange=RecurrenceDurationChange.KeepRaw,newStart:FieldChange<RecurrenceStart> = FieldChange.Keep):SeriesCommand.FollowingEdit {
        val original=edit.snapshot.validatedSelection!!.recurrence!!.originalStart
        val key=if(edit.snapshot.start is RecurrenceStart.AllDay)RecurrenceKey.Date(original.atOffset(ZoneOffset.UTC).toLocalDate())else RecurrenceKey.Timed(original)
        return SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(key,fields,newStart,duration,rule))
    }
    private fun event(p:CalendarRecurringCreateTest.Provider,id:Long):Map<String,String?> = p.db.rawQuery("SELECT * FROM events WHERE _id = ?",arrayOf(id.toString())).use{c->assertTrue(c.moveToFirst());c.columnNames.indices.associate{c.columnNames[it] to if(c.isNull(it))null else c.getString(it)}}
    private fun common(result:MutationResult)=SeriesMutationResult.Common(result)
    private fun expandedRows(f:Fixture):List<Map<String,String?>> {
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().apply{
            android.content.ContentUris.appendId(this,f.selected.instanceStart.minusSeconds(3*86400).toEpochMilli())
            android.content.ContentUris.appendId(this,f.selected.instanceStart.plusSeconds(10*86400).toEpochMilli())
        }.build()
        return RuntimeEnvironment.getApplication().contentResolver.query(uri,arrayOf("event_id","begin","end"),null,null,null)!!.use{c->
            buildList{while(c.moveToNext())add(c.columnNames.associateWith{key->c.getString(c.getColumnIndexOrThrow(key))})}
        }
    }

    @Test fun nonzeroPrefixRepeatsExactRawContextAndExplicitNullsWithoutTouchingOtherFields()=runBlocking{fixture{f->
        f.p.db.execSQL("ALTER TABLE events ADD COLUMN opaque_oem TEXT")
        f.p.db.execSQL("UPDATE events SET duration='PT1H',eventTimezone='America/New_York',eventEndTimezone=NULL WHERE _id=1")
        val before=event(f.p,1);val edit=f.editable()
        assertTrue(f.writer.mutate("raw-context",f.selected,edit.token,command(edit)) is SeriesMutationResult.LocalSaved)
        val sent=f.p.updatedEventValues.single{it.first==1L}.second
        val context=setOf("dtstart","dtend","duration","eventTimezone","allDay","originalInstanceTime","originalAllDay")
        assertEquals(context+setOf("rrule","exdate"),sent.keySet());assertTrue(sent.containsKey("exdate"));assertNull(sent.get("exdate"))
        context.forEach{key->assertEquals("Exact source field $key",before[key],sent.getAsString(key));assertTrue(sent.containsKey(key))}
        assertNull(sent.get("dtend"));assertNull(sent.get("originalInstanceTime"));assertNull(sent.get("originalAllDay"))
        assertEquals("PT1H",sent.getAsString("duration"));assertEquals("America/New_York",sent.getAsString("eventTimezone"))
        assertEquals(before.filterKeys{it!="rrule"},event(f.p,1).filterKeys{it!="rrule"})
        assertFalse(sent.containsKey("eventEndTimezone"));assertFalse(sent.containsKey("opaque_oem"));assertEquals(1,f.p.batchCount)
    }}

    @Test fun allDayPrefixContextKeepsUtcWholeDateAndRawMultiDayDuration()=runBlocking{fixture(allDay=true){f->
        val before=event(f.p,1);val edit=f.editable()
        assertTrue(f.writer.mutate("all-day-context",f.selected,edit.token,command(edit)) is SeriesMutationResult.LocalSaved)
        val sent=f.p.updatedEventValues.single{it.first==1L}.second
        assertEquals(before["dtstart"],sent.getAsString("dtstart"));assertEquals("1",sent.getAsString("allDay"))
        assertEquals("UTC",sent.getAsString("eventTimezone"));assertEquals("P3D",sent.getAsString("duration"))
        assertTrue(sent.containsKey("dtend"));assertNull(sent.get("dtend"))
        assertEquals(before.filterKeys{it!="rrule"},event(f.p,1).filterKeys{it!="rrule"})
    }}

    /** Models the documented sparse-DTSTART early return only. Actual device/provider cache and
     * Google adapter acceptance remain NOT_RUN; this fake is a regression trap, not certification. */
    @Test fun warmedSyntheticCacheDropsFuturePrefixInstancesAndKeepsOneCompleteUnion()=runBlocking{
        for(allDay in listOf(false,true))fixture(allDay=allDay){f->
            f.p.cacheExpandedTemporalRows=true
            assertEquals(8,expandedRows(f).size)
            val edit=f.editable()
            assertTrue(f.writer.mutate("warm-prefix",f.selected,edit.token,command(edit,EventPatch(title=FieldChange.Set("Independent tail")))) is SeriesMutationResult.LocalSaved)
            val rows=expandedRows(f)
            assertEquals(8,rows.size);assertEquals(2,rows.count{it["event_id"]=="1"});assertEquals(6,rows.count{it["event_id"]=="2"})
            assertEquals(8,rows.map{it["begin"]}.distinct().size)
            assertEquals(1,f.p.batchCount)
        }
    }

    @Test fun syntheticWarmCacheNegativeControlReproducesTheSparseUpdateDefect()=runBlocking{fixture{f->
        f.p.cacheExpandedTemporalRows=true
        assertEquals(8,expandedRows(f).size)
        val uri=android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,1)
        f.p.update(uri,ContentValues().apply{put("rrule","FREQ=DAILY;COUNT=2")},null,null)
        assertEquals(8,expandedRows(f).size)
        val source=event(f.p,1)
        val context=ContentValues().apply{
            listOf("dtstart","dtend","duration","eventTimezone","allDay","originalInstanceTime","originalAllDay").forEach{key->source[key]?.let{put(key,it)}?:putNull(key)}
            put("rrule","FREQ=DAILY;COUNT=2")
        }
        f.p.update(uri,context,null,null)
        assertEquals(2,expandedRows(f).size)
    }}

    @Test fun countSplitPreservesPrefixAndCreatesIndependentUidWithActualFirstInstance()=runBlocking{fixture{f->
        val edit=f.editable();assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit);assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).recurrence)
        val outcome=f.writer.mutate("split",f.selected,edit.token,command(edit,EventPatch(title=FieldChange.Set("Tail"))))
        val result=outcome as SeriesMutationResult.LocalSaved
        assertEquals(2L,result.identity.masterEventId);assertEquals(2L,result.selected!!.eventId);assertEquals(f.selected.instanceStart,result.selected!!.instanceStart)
        assertEquals(RecurrenceRef(2,f.selected.instanceStart),result.selected!!.recurrence)
        val prefix=event(f.p,1);val tail=event(f.p,2)
        assertEquals(edit.snapshot.ruleRaw!!.replace("COUNT=8","COUNT=2"),prefix["rrule"]);assertEquals(edit.snapshot.ruleRaw!!.replace("COUNT=8","COUNT=6"),tail["rrule"])
        assertEquals("Prefix",prefix["title"]);assertEquals("Tail",tail["title"]);assertEquals("prefix-public-uid",prefix["uid2445"]);assertEquals("prefix-sync",prefix["_sync_id"])
        assertNotEquals(prefix["uid2445"],tail["uid2445"]);assertEquals(tail["uid2445"],UUID.fromString(tail["uid2445"]).toString());assertNull(tail["_sync_id"])
        assertNull(tail["dtend"]);assertEquals("PT3600S",tail["duration"]);assertEquals("0",tail["hasAttendeeData"]);assertEquals(1,f.p.batchCount)
        val sent=f.p.insertedEventValues.last();assertFalse(sent.containsKey("_sync_id"));assertFalse(sent.containsKey("selfAttendeeStatus"));assertFalse(sent.containsKey("isOrganizer"));assertFalse(sent.containsKey("hasAlarm"))
        assertEquals("VERIFIED",f.store.operation("split")!!.phase)
    }}

    @Test fun titleOnlyFollowingSplitPreservesDistinctNullAndEmptyEndZones()=runBlocking {
        val losses=mutableListOf<String>()
        for(endZone in listOf("America/New_York",null,""))fixture(providerClonePolicy=SyntheticCloneProviderPolicy){f->
            f.p.db.execSQL("UPDATE events SET eventEndTimezone=? WHERE _id=1",arrayOf(endZone))
            val before=event(f.p,1)
            if(!endZone.isNullOrBlank()){
                assertEquals("Distinct end zones are explicitly unsupported before editing",SeriesReadResult.Denied(MutationDenial.UNSUPPORTED_EVENT),f.writer.loadForEdit(f.selected))
                assertEquals(before,event(f.p,1));assertEquals(0,f.p.batchCount)
                return@fixture
            }
            val edit=f.editable()
            assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit)
            val outcome=f.writer.mutate("end-zone",f.selected,edit.token,command(edit,EventPatch(title=FieldChange.Set("Tail title"))))
            assertTrue("Title-only split must be admitted by the explicit synthetic policy: $outcome",outcome is SeriesMutationResult.LocalSaved)
            val saved=outcome as SeriesMutationResult.LocalSaved
            val prefix=event(f.p,1);val tail=event(f.p,saved.identity.masterEventId)
            assertEquals("Prefix changes only its recurrence limit",before.filterKeys{it!="rrule"},prefix.filterKeys{it!="rrule"})
            assertEquals("Tail title",tail["title"]);assertEquals(before["eventTimezone"],tail["eventTimezone"])
            if(before["eventEndTimezone"]!=tail["eventEndTimezone"])losses+="source=$endZone tail=${tail["eventEndTimezone"]}"
            assertEquals(1,f.p.batchCount)
        }
        assertTrue("Metadata-only split must preserve raw end zone: $losses",losses.isEmpty())
    }

    @Test fun legacyPreparedCanonicalEndZoneIsNotRebasedOntoFreshMetadataPreservation()=runBlocking{fixture{f->
        f.p.db.execSQL("UPDATE events SET eventEndTimezone=NULL WHERE _id=1")
        val edit=f.editable();val frozen=command(edit,EventPatch(title=FieldChange.Set("Legacy issued tail")))
        f.p.failReminder=true;assertEquals(common(MutationResult.Unavailable),f.writer.mutate("legacy-zone",f.selected,edit.token,frozen))
        val issued=JSONObject(f.store.operation("legacy-zone")!!.resultRef!!)
        val source=issued.optJSONObject("source")?:JSONObject(issued.getString("sourceRaw"))
        val material=issued.optJSONObject("material")?:issued.getJSONObject("plan").let{p->JSONObject().put("whole",p.get("whole")).put("ordinal",p.get("ordinal"))
            .put("prefix",p.get("prefix")).put("tail",p.get("tail")).put("prefixReminders",p.get("prefixReminders")).put("tailReminders",p.get("tailReminders")).put("tailAttendees",p.get("tailAttendees"))}
        val canonical=source.getJSONObject("master").getString("eventTimezone")
        require(source.getJSONObject("master").isNull("exdate")||source.getJSONObject("master").getString("exdate").isEmpty())
        material.getJSONObject("tail").remove("exdate") // Not present in historical2 native material.
        material.getJSONObject("tail").put("eventEndTimezone",canonical)
        val legacy=JSONObject().put("version",2).put("feature","SERIES_FOLLOWING_EDIT_V2").put("kind","SERIES_V1_FOLLOWING_EDIT").put("signature",issued.get("signature"))
            .put("token",issued.get("token")).put("command",issued.get("command")).put("source",source).put("uid",material.getJSONObject("tail").get("uid2445")).put("material",material).put("stage","prepared").toString()
        f.store.forceOperationForTest("legacy-zone","FAILED",legacy)
        f.p.failReminder=false
        f.p.beforeBatch={assertEquals("ATTEMPTED",f.store.operation("legacy-zone")!!.phase);assertEquals(legacy,f.store.operation("legacy-zone")!!.resultRef)}
        assertEquals(common(MutationResult.Uncertain("legacy-zone",2)),f.writer.mutate("legacy-zone",f.selected,edit.token,frozen))
        assertEquals(canonical,event(f.p,2)["eventEndTimezone"]);assertNull(event(f.p,1)["eventEndTimezone"])
        assertEquals(2,f.p.batchCount);assertEquals(2,JSONObject(f.store.operation("legacy-zone")!!.resultRef!!).getInt("version"))
    }}

    @Test fun ruleOnlyFollowingKeepsRawNullAndEmptyEndZoneWhenTemporalFactsAreKept()=runBlocking {
        for(endZone in listOf(null,""))fixture{f->
            f.p.db.execSQL("UPDATE events SET eventEndTimezone=? WHERE _id=1",arrayOf(endZone))
            val before=event(f.p,1);val edit=f.editable()
            val frozen=command(edit,rule=RecurrenceRuleChanges(end=FieldChange.Set(RecurrenceEnd.Count(3))))
            val saved=f.writer.mutate("rule-zone",f.selected,edit.token,frozen) as SeriesMutationResult.LocalSaved
            val prefix=event(f.p,1);val tail=event(f.p,saved.identity.masterEventId)
            assertEquals(before.filterKeys{it!="rrule"},prefix.filterKeys{it!="rrule"})
            assertEquals(endZone,tail["eventEndTimezone"]);assertEquals(before["eventTimezone"],tail["eventTimezone"])
            assertTrue(tail["rrule"]!!.contains("COUNT=3"));assertEquals(1,f.p.batchCount)
        }
    }

    @Test fun literalUntilAndIndependentChangedLimitRemainExact()=runBlocking{fixture{f->
        val raw="WKST=SU;FREQ=DAILY;UNTIL=20261125T103017Z"
        f.p.db.execSQL("UPDATE events SET rrule = ?",arrayOf(raw));val edit=f.editable()
        assertTrue(f.writer.mutate("until",f.selected,edit.token,command(edit)) is SeriesMutationResult.LocalSaved)
        assertEquals("WKST=SU;FREQ=DAILY;UNTIL=20261118T090000Z",event(f.p,1)["rrule"]);assertEquals(raw,event(f.p,2)["rrule"])
    };fixture{f->val edit=f.editable();val result=f.writer.mutate("end",f.selected,edit.token,command(edit,rule=RecurrenceRuleChanges(end=FieldChange.Set(RecurrenceEnd.Count(3))))) as SeriesMutationResult.LocalSaved
        assertEquals(2L,result.identity.masterEventId);assertTrue(event(f.p,2)["rrule"]!!.contains("COUNT=3"));assertTrue(event(f.p,1)["rrule"]!!.contains("COUNT=2"))
    }}

    @Test fun firstOrdinalUsesAllEquivalentAndNeverAllocatesTailUidOrCopiesOpaquePayload()=runBlocking{fixture(ordinal=0){f->
        f.p.db.execSQL("ALTER TABLE events ADD COLUMN opaque_oem TEXT DEFAULT 'Keep original'")
        val edit=f.editable();val before=f.p.insertedEventValues.size
        val result=f.writer.mutate("whole",f.selected,edit.token,command(edit,EventPatch(title=FieldChange.Set("Whole")))) as SeriesMutationResult.LocalSaved
        assertEquals(1L,result.identity.masterEventId);assertEquals(1,f.p.count("events"));assertEquals(before,f.p.insertedEventValues.size)
        assertEquals("prefix-public-uid",event(f.p,1)["uid2445"]);assertEquals("Keep original",event(f.p,1)["opaque_oem"])
        assertTrue(JSONObject(f.store.operation("whole")!!.resultRef!!).isNull("uid"))
    }}

    @Test fun allDayOverlappingInstancesKeepFullPrefixDurationAndUtcTailDays()=runBlocking{fixture(allDay=true){f->
        val edit=f.editable();val result=f.writer.mutate("dates",f.selected,edit.token,command(edit,duration=RecurrenceDurationChange.AllDayDays(4))) as SeriesMutationResult.LocalSaved
        assertEquals(Instant.parse("2026-11-19T00:00:00Z"),result.selected!!.instanceStart);assertEquals(4*86400L,Duration.between(result.selected!!.instanceStart,result.selected!!.instanceEnd).seconds)
        assertEquals("P3D",event(f.p,1)["duration"]);assertEquals("P4D",event(f.p,2)["duration"]);assertEquals("UTC",event(f.p,2)["eventTimezone"])
    }}

    @Test fun selfOrganizerAndNonAlertDefaultRemindersAreCopiedWithNewIdsOnly()=runBlocking{fixture{f->
        f.p.insert(CalendarContract.Attendees.CONTENT_URI,ContentValues().apply{put("event_id",1);put("attendeeEmail","owner@example.invalid");put("attendeeName","Owner");put("attendeeStatus",1);put("attendeeRelationship",2);put("attendeeType",0)})
        f.p.insert(CalendarContract.Reminders.CONTENT_URI,ContentValues().apply{put("event_id",1);put("minutes",-1);put("method",0)})
        f.p.insert(CalendarContract.Reminders.CONTENT_URI,ContentValues().apply{put("event_id",1);put("minutes",30);put("method",2)})
        val edit=f.editable();assertTrue(f.writer.mutate("children",f.selected,edit.token,command(edit,EventPatch(alertMinutes=FieldChange.Set(listOf(15,45))))) is SeriesMutationResult.LocalSaved)
        assertEquals(2,f.p.count("attendees"));f.p.db.rawQuery("SELECT _id,event_id,attendeeStatus FROM attendees ORDER BY _id",null).use{c->c.moveToFirst();val old=c.getLong(0);assertEquals(1,c.getInt(1));assertTrue(c.moveToNext());assertNotEquals(old,c.getLong(0));assertEquals(2,c.getInt(1));assertEquals(1,c.getInt(2))}
        f.p.db.rawQuery("SELECT minutes,method FROM reminders WHERE event_id=2 ORDER BY method,minutes",null).use{c->val rows=mutableListOf<Pair<Int,Int>>();while(c.moveToNext())rows+=c.getInt(0) to c.getInt(1);assertEquals(listOf(-1 to 0,15 to 1,45 to 1,30 to 2),rows)}
        assertEquals("1",event(f.p,2)["selfAttendeeStatus"])
    }}

    @Test fun tailAnchorZonePatternAndDurationUseTypedOriginalBoundaryNotVisibleDate()=runBlocking{fixture{f->
        val edit=f.editable();val ny=ZoneId.of("America/New_York")
        val result=f.writer.mutate("changes",f.selected,edit.token,command(edit,rule=RecurrenceRuleChanges(pattern=FieldChange.Set(RecurrencePattern.Weekly(setOf(DayOfWeek.THURSDAY)))),
            duration=RecurrenceDurationChange.TimedElapsed(Duration.ofMinutes(90)),newStart=FieldChange.Set(RecurrenceStart.Timed(LocalDateTime.of(2026,11,19,11,0),ny)))) as SeriesMutationResult.LocalSaved
        assertEquals(Instant.parse("2026-11-19T16:00:00Z"),result.selected!!.instanceStart);assertEquals(5400L,Duration.between(result.selected!!.instanceStart,result.selected!!.instanceEnd).seconds)
        assertEquals("America/New_York",event(f.p,2)["eventTimezone"]);assertTrue(event(f.p,2)["rrule"]!!.contains("COUNT=6"));assertEquals("PT3600S",event(f.p,1)["duration"])
    }}

    @Test fun opaquePublicSyncAndChildDataExtendedPropertiesAndExceptionsDeclineBeforeJournal()=runBlocking{
        listOf("sync_data1","opaque_oem").forEach{column->fixture{f->f.p.db.execSQL("ALTER TABLE events ADD COLUMN $column TEXT DEFAULT 'Unknown payload'");val edit=f.editable()
            assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit)
            assertTrue(f.writer.mutate("opaque",f.selected,edit.token,command(edit,EventPatch(title=FieldChange.Set("No")))) is SeriesMutationResult.PlanningReview);assertNull(f.store.operationHeader("opaque"));assertEquals(0,f.p.batchCount)}}
        fixture{f->f.p.db.execSQL("INSERT INTO extendedproperties(event_id,name,value) VALUES(1,'opaque','private')");f.p.db.execSQL("UPDATE events SET hasExtendedProperties=1");val edit=f.editable();assertTrue(f.writer.mutate("properties",f.selected,edit.token,command(edit)) is SeriesMutationResult.PlanningReview);assertNull(f.store.operationHeader("properties"))}
        fixture{f->f.p.db.execSQL("ALTER TABLE reminders ADD COLUMN opaque TEXT DEFAULT 'Unknown child'");val edit=f.editable();assertTrue(f.writer.mutate("child",f.selected,edit.token,command(edit)) is SeriesMutationResult.PlanningReview);assertEquals(0,f.p.batchCount)}
    }

    @Test fun failedBatchKeepsOnePreparedUidAcrossExplicitRetryAndRejectsChangedFrozenCommand()=runBlocking{fixture{f->
        val edit=f.editable();val frozen=command(edit,EventPatch(title=FieldChange.Set("Tail")));f.p.failReminder=true
        assertEquals(common(MutationResult.Unavailable),f.writer.mutate("retry",f.selected,edit.token,frozen));assertEquals(1,f.p.count("events"));assertTrue(event(f.p,1)["rrule"]!!.contains("COUNT=8"))
        val uid=JSONObject(f.store.operation("retry")!!.resultRef!!).getJSONObject("plan").getJSONObject("tail").getString("uid2445");assertEquals("FAILED",f.store.operation("retry")!!.phase)
        assertEquals(common(MutationResult.SafeToRetry("retry")),f.writer.recover("retry"))
        assertEquals(common(MutationResult.Conflict(null)),f.writer.mutate("retry",f.selected,edit.token,command(edit,EventPatch(title=FieldChange.Set("Different")))))
        f.p.failReminder=false;val saved=f.writer.mutate("retry",f.selected,edit.token,frozen) as SeriesMutationResult.LocalSaved
        assertEquals(uid,event(f.p,saved.identity.masterEventId)["uid2445"]);assertEquals(2,f.p.batchCount)
    }}

    @Test fun lostOrMalformedAcknowledgementNeverSearchesByGeneratedUidOrReplaysSplit()=runBlocking{
        for(malformed in listOf(false,true))fixture{f->val edit=f.editable();val frozen=command(edit);f.p.lostAck=!malformed;f.p.malformedLastEventAck=malformed
            assertTrue((f.writer.mutate("lost",f.selected,edit.token,frozen) as SeriesMutationResult.Common).outcome is MutationResult.Uncertain)
            assertEquals(2,f.p.count("events"));assertEquals("ATTEMPTED",f.store.operation("lost")!!.phase);f.p.queries.clear();f.store.close()
            assertEquals(common(MutationResult.Uncertain("lost",null)),f.writer.recover("lost"));assertTrue(f.p.queries.isEmpty())
            f.writer.mutate("lost",f.selected,edit.token,frozen);assertEquals(1,f.p.batchCount);assertTrue(f.p.queries.isEmpty())
        }
    }

    @Test fun knownCommitRecoveryIsSelfContainedReadOnlyAndRejectsNamespaceReplacement()=runBlocking{fixture{f->
        val edit=f.editable();f.p.failReadsAfterCommit=true
        assertEquals(common(MutationResult.Uncertain("known",2)),f.writer.mutate("known",f.selected,edit.token,command(edit)))
        assertEquals("COMMITTED",f.store.operation("known")!!.phase)
        f.p.failReadsAfterCommit=false;f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots");f.permissions.remove(Manifest.permission.WRITE_CALENDAR);f.store.close()
        assertTrue(f.writer.recover("known") is SeriesMutationResult.LocalSaved);assertEquals(1,f.p.batchCount)
        f.p.setCalendar("_sync_id","calendar-B");f.p.queries.clear()
        assertEquals(common(MutationResult.Uncertain("known",null)),f.writer.recover("known"));assertTrue(f.p.queries.all{it.path=="calendars"})
    }}

    @Test fun malformedPreparedMaterializationNeverRegeneratesUidAndCanStillBeRetired()=runBlocking{fixture{f->
        val edit=f.editable();val frozen=command(edit);f.p.failReminder=true
        f.writer.mutate("bad",f.selected,edit.token,frozen)
        val receipt=JSONObject(f.store.operation("bad")!!.resultRef!!)
        receipt.getJSONObject("plan").getJSONObject("tail").put("uid2445",JSONObject.NULL)
        f.store.forceOperationForTest("bad","FAILED",receipt.toString());f.p.failReminder=false
        assertEquals(common(MutationResult.Uncertain("bad",null)),f.writer.mutate("bad",f.selected,edit.token,frozen));assertEquals(1,f.p.batchCount)
        val retirement=CalendarOperationRetirement(f.store);val request=RetirableOperation.Series("bad",f.selected,edit.token,frozen)
        assertEquals(OperationRetirementResult.Retired("bad"),retirement.retire(request));f.permissions.clear();f.p.queries.clear()
        assertEquals(common(MutationResult.Retired("bad")),f.writer.mutate("bad",f.selected,edit.token,frozen));assertTrue(f.p.queries.isEmpty())
    }}

    @Test fun delayedWrongNativeEndAndLearnedTailSyncDowngradeRemainKnownUncertain()=runBlocking{fixture{f->
        val edit=f.editable();f.p.afterCommit={f.p.endDelta=1000}
        assertEquals(common(MutationResult.Uncertain("end",2)),f.writer.mutate("end",f.selected,edit.token,command(edit)))
        assertEquals("COMMITTED",f.store.operation("end")!!.phase);f.p.afterCommit=null;f.p.endDelta=0
        assertTrue(f.writer.recover("end") is SeriesMutationResult.LocalSaved)
        f.p.db.execSQL("UPDATE events SET _sync_id='tail-sync' WHERE _id=2");assertTrue(f.writer.recover("end") is SeriesMutationResult.LocalSaved)
        f.p.db.execSQL("UPDATE events SET _sync_id=NULL WHERE _id=2");assertEquals(common(MutationResult.Uncertain("end",2)),f.writer.recover("end"))
    }}
    @Test fun independentHelpersReusePreparedUidAndConcurrentRequestsNeverInsertTwoTails()=runBlocking{fixture{f->
        val edit=f.editable();val frozen=command(edit);f.p.failReminder=true
        assertEquals(common(MutationResult.Unavailable),f.writer.mutate("prepared",f.selected,edit.token,frozen))
        val uid=JSONObject(f.store.operation("prepared")!!.resultRef!!).getJSONObject("plan").getJSONObject("tail").getString("uid2445");f.p.failReminder=false
        val two=CalendarLocalStore(RuntimeEnvironment.getApplication(),f.store.databaseName)
        try{val source=CalendarSeriesReader(RuntimeEnvironment.getApplication().contentResolver,f.reader,two,f.permissions::contains)
            val writer=CalendarSeriesWriter(RuntimeEnvironment.getApplication().contentResolver,source,two,f.permissions::contains,providerClonePolicy=SyntheticCloneProviderPolicy)
            val saved=writer.mutate("prepared",f.selected,edit.token,frozen) as SeriesMutationResult.LocalSaved
            assertEquals(uid,event(f.p,saved.identity.masterEventId)["uid2445"]);assertEquals(uid,JSONObject(two.operation("prepared")!!.resultRef!!).getJSONObject("plan").getJSONObject("tail").getString("uid2445"))
        }finally{two.close()}
    };fixture{f->
        val edit=f.editable();val frozen=command(edit);val two=CalendarLocalStore(RuntimeEnvironment.getApplication(),f.store.databaseName)
        try{val writer=CalendarSeriesWriter(RuntimeEnvironment.getApplication().contentResolver,CalendarSeriesReader(RuntimeEnvironment.getApplication().contentResolver,f.reader,two,f.permissions::contains),two,f.permissions::contains,providerClonePolicy=SyntheticCloneProviderPolicy)
            val gate=CompletableDeferred<Unit>();val one=async(Dispatchers.IO){gate.await();f.writer.mutate("race",f.selected,edit.token,frozen)}
            val other=async(Dispatchers.IO){gate.await();writer.mutate("race",f.selected,edit.token,frozen)}
            gate.complete(Unit);val results=listOf(one.await(),other.await())
            assertTrue(results.any{it is SeriesMutationResult.LocalSaved}||f.writer.recover("race") is SeriesMutationResult.LocalSaved)
            assertEquals(1,f.p.batchCount);assertEquals(2,f.p.count("events"));assertEquals(JSONObject(f.store.operation("race")!!.resultRef!!).getJSONObject("plan").getJSONObject("tail").getString("uid2445"),event(f.p,2)["uid2445"])
        }finally{two.close()}
    }}

    @Test fun dateSetsAndCompleteExistingExceptionGraphsKeepFollowingEditDisabled()=runBlocking{
        fixture{f->f.p.db.execSQL("UPDATE events SET exdate='20261118T090000Z'");val edit=f.editable()
            assertEquals(AccessDecision.ALLOWED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit)
            assertTrue(f.writer.mutate("exdate-metadata",f.selected,edit.token,command(edit)) is SeriesMutationResult.LocalSaved)
            assertEquals(4,JSONObject(f.store.operation("exdate-metadata")!!.resultRef!!).getInt("version"));assertEquals(1,f.p.batchCount)
        }
        listOf("rdate" to "20261130T090000Z","exrule" to "FREQ=WEEKLY;BYDAY=SU").forEach{(key,value)->fixture{f->
            f.p.db.execSQL("UPDATE events SET $key = ?",arrayOf(value));val edit=f.editable()
            assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit)
            assertTrue(f.writer.mutate("dateset",f.selected,edit.token,command(edit)) is SeriesMutationResult.PlanningReview);assertNull(f.store.operationHeader("dateset"));assertEquals(0,f.p.batchCount)
        }}
        fixture{f->f.p.insert(CalendarContract.Events.CONTENT_URI,ContentValues().apply{put("calendar_id",2);put("dtstart",start.plusSeconds(4*86400).toEpochMilli());put("dtend",start.plusSeconds(4*86400+3600).toEpochMilli());put("eventTimezone","Europe/London");put("eventEndTimezone","Europe/London");put("allDay",0);put("original_id",1);put("originalInstanceTime",start.plusSeconds(4*86400).toEpochMilli());put("originalAllDay",0)})
            val edit=f.editable();assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit)
            assertTrue(f.writer.mutate("exception",f.selected,edit.token,command(edit)) is SeriesMutationResult.PlanningReview);assertEquals(0,f.p.batchCount);assertNull(f.store.operationHeader("exception"))
        }
    }

    @Test fun emptyNativeExpansionNeverFabricatesTailRefAndKnownRecoveryKeepsItsIdOnReadFailure()=runBlocking{fixture{f->
        val edit=f.editable();f.p.afterCommit={f.p.delayedInstances=true}
        assertEquals(common(MutationResult.Uncertain("deferred",2)),f.writer.mutate("deferred",f.selected,edit.token,command(edit)))
        assertEquals("COMMITTED",f.store.operation("deferred")!!.phase)
        f.p.afterCommit=null;f.p.delayedInstances=false;f.p.failReadsAfterCommit=true
        assertEquals(common(MutationResult.Uncertain("deferred",2)),f.writer.recover("deferred"))
        f.p.failReadsAfterCommit=false;assertTrue(f.writer.recover("deferred") is SeriesMutationResult.LocalSaved);assertEquals(1,f.p.batchCount)
    }}

    @Test fun conflictingObservedNativeDurationRequiresReviewBeforeMaterializingAnyUid()=runBlocking{fixture{f->
        f.p.endDelta=1000
        val observed=f.selected.copy(instanceEnd=f.selected.instanceEnd.plusSeconds(1))
        val edit=(f.writer.loadForEdit(observed) as SeriesReadResult.Ready).series
        assertEquals(AccessDecision.DENIED,edit.scopes.getValue(RecurrenceScope.FOLLOWING).edit)
        assertTrue(f.writer.mutate("duration-mismatch",observed,edit.token,command(edit)) is SeriesMutationResult.PlanningReview)
        assertNull(f.store.operationHeader("duration-mismatch"));assertEquals(0,f.p.batchCount)
    }}

    @Test fun unverifiedTailProfileDisablesActualSplitButKeepsOrdinalZeroAndDeleteCapabilities()=runBlocking {
        for(ordinal in listOf(0,2))fixture(ordinal=ordinal,providerClonePolicy=ProviderClonePolicy.Unverified){f->
            val edit=f.editable();val following=edit.scopes.getValue(RecurrenceScope.FOLLOWING)
            assertEquals(AccessDecision.ALLOWED,following.delete)
            assertEquals(if(ordinal==0)AccessDecision.ALLOWED else AccessDecision.DENIED,following.edit)
            val result=f.writer.mutate("unverified-tail",f.selected,edit.token,command(edit,fields=EventPatch(title=FieldChange.Set("Synthetic guarded tail"))))
            if(ordinal==0){assertTrue(result.toString(),result is SeriesMutationResult.LocalSaved);assertEquals(1,f.p.db.rawQuery("SELECT COUNT(*) FROM events",null).use{it.moveToFirst();it.getInt(0)})}
            else {assertTrue(result.toString(),result is SeriesMutationResult.PlanningReview);assertNull(f.store.operation("unverified-tail"));assertEquals(0,f.p.batchCount)}
        }
    }
    @Test fun tailProfileIsFreshAtPreparedRetryAndAttemptBoundaryWithoutRevivingUnknownDispatch()=runBlocking {
        for(stopAt in listOf("PREPARED","ATTEMPTED")){
            var store:CalendarLocalStore?=null;var denied=true
            val policy=ProviderClonePolicy{op->if(denied&&store?.operationHeader("tail-profile")?.phase==stopAt)null else SyntheticCloneProviderPolicy.currentProfile(op)}
            fixture(providerClonePolicy=policy){f->store=f.store;val edit=f.editable();val frozen=command(edit)
                val outcome=f.writer.mutate("tail-profile",f.selected,edit.token,frozen)
                assertEquals(stopAt,f.store.operation("tail-profile")!!.phase);assertEquals(0,f.p.batchCount)
                denied=false
                if(stopAt=="PREPARED"){assertTrue(outcome is SeriesMutationResult.PlanningReview);assertTrue(f.writer.mutate("tail-profile",f.selected,edit.token,frozen) is SeriesMutationResult.LocalSaved)}
                else {assertEquals(common(MutationResult.Uncertain("tail-profile",null)),outcome);assertEquals(common(MutationResult.Uncertain("tail-profile",null)),f.writer.mutate("tail-profile",f.selected,edit.token,frozen));assertEquals(0,f.p.batchCount)}
            }
        }
    }
    @Test fun unverifiedTailPolicyStillAllowsKnownCommitRecoveryWithReadPermissionOnly()=runBlocking {
        var admitted=true;val policy=ProviderClonePolicy{op->if(admitted)SyntheticCloneProviderPolicy.currentProfile(op)else null}
        fixture(providerClonePolicy=policy){f->val edit=f.editable();f.p.failReadsAfterCommit=true
            val outcome=f.writer.mutate("known-tail-profile",f.selected,edit.token,command(edit));assertTrue(outcome is SeriesMutationResult.Common&&outcome.outcome is MutationResult.Uncertain)
            assertEquals("COMMITTED",f.store.operation("known-tail-profile")!!.phase)
            admitted=false;f.p.failReadsAfterCommit=false;f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            assertTrue(f.writer.recover("known-tail-profile") is SeriesMutationResult.LocalSaved);assertEquals(1,f.p.batchCount)
        }
    }

}
