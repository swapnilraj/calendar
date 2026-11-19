package dev.swapnilraj.calendar.events.meetings.create

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.*
import java.time.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecurringMeetingCreationControllerTest {
    private suspend fun MeetingCreationFixture.recurring():MeetingCreationController {
        source=source.copy(recurringCreation=AccessDecision.ALLOWED)
        return ready().also {it.changeFields(it.state.fields!!.event.copy(recurrence=daily()))}
    }
    private fun daily(count:String="3")=RecurrenceEditorFields(frequency="DAILY",endMode="COUNT",count=count)

    @Test fun newRawDraftUsesSchemaTwoButHasNoBorrowedRecurrenceAuthority()=runBlocking {
        val f=MeetingCreationFixture();val c=f.open();val doc=MeetingCreationDraftCodec.decode(f.record())
        assertEquals(2,doc.schema);assertNull(doc.authority);assertFalse(c.canEditRepeat)
        c.changeFields(c.state.fields!!.event.copy(recurrence=daily()));val exact=f.record();c.save()
        assertEquals(exact,f.record());assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
    }

    @Test fun repeatWithoutIssuedRightRemainsRawAndUnsupportedRatherThanOneOff()=runBlocking {
        for(right in AccessDecision.entries.filter {it!=AccessDecision.ALLOWED}) {
            val f=MeetingCreationFixture();f.source=f.source.copy(recurringCreation=right);val c=f.ready()
            c.changeFields(c.state.fields!!.event.copy(recurrence=daily()));val exact=f.record();c.save();c.confirmRecurringCreate()
            assertFalse(c.canEditRepeat);assertEquals(MeetingCreationProblem.RECURRENCE_UNSUPPORTED,c.state.problem)
            assertEquals(exact,f.record());assertNull(c.state.recurrenceReview);assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        }
    }

    @Test fun saveReviewsTypedCandidateBeforePermissionAndOnlyExplicitConfirmFreezesAndCreates()=runBlocking {
        val f=MeetingCreationFixture();val c=f.recurring();val exact=f.record();c.save()
        val review=c.state.recurrenceReview!!;assertEquals(RecurrenceEnd.Count(3),review.spec.end)
        assertEquals(f.start,review.plan.dtstart);assertEquals("PT3600S",review.plan.durationRaw)
        assertEquals(0,f.permissions);assertTrue(f.calls.isEmpty());assertEquals(exact,f.record());assertFalse(c.frozen)
        c.confirmRecurringCreate();val frozen=MeetingCreationDraftCodec.decode(f.record()).frozen!!
        assertEquals(review.spec,frozen.draft.recurrence);assertEquals(frozen.draft,f.calls.single().draft)
        assertEquals(frozen.guests,f.calls.single().guests);assertEquals(frozen.operationId,f.calls.single().id)
        assertNull(c.state.recurrenceReview);assertTrue(c.frozen)
    }

    @Test fun cancelReviewDoesNotRequestPermissionOrWriteAndRawReopenRequiresNewReview()=runBlocking {
        val f=MeetingCreationFixture();val c=f.recurring();c.save();val exact=f.record()
        c.dismissRecurrenceReview();c.confirmRecurringCreate();assertNull(c.state.recurrenceReview)
        val reopened=f.controller(f.launch());reopened.load();assertNull(reopened.state.recurrenceReview);reopened.confirmRecurringCreate()
        assertFalse(reopened.frozen);assertEquals(exact,f.record());assertEquals(0,f.permissions);assertTrue(f.calls.isEmpty())
        reopened.save();assertNotNull(reopened.state.recurrenceReview)
    }

    @Test fun exactNoopFieldsGuestsPolicyAndCalendarCallbacksKeepReviewAndRevision()=runBlocking {
        val f=MeetingCreationFixture();val c=f.recurring();c.save();val review=c.state.recurrenceReview;val exact=f.record()
        c.changeFields(c.state.fields!!.event.copy(calendarId="foreign",targetIdentity=f.calendar.identity.copy(providerId="foreign")))
        c.changeGuests(c.state.fields!!.guests.toList());c.acceptFixedGuestPolicy(true);c.selectCalendar(f.calendar)
        assertSame(review,c.state.recurrenceReview);assertEquals(exact,f.record())
        c.confirmRecurringCreate();assertEquals(1,f.calls.size)
    }

    @Test fun everyGenuineRawOrAuthorityChangeInvalidatesReviewedCommandEvenIfPersistenceFails()=runBlocking {
        for(change in 0..5) {
            val f=MeetingCreationFixture();val c=f.recurring();c.save();f.store.unknownBefore=change==5
            when(change) {
                0 -> c.changeFields(c.state.fields!!.event.copy(title="Changed"))
                1 -> c.changeFields(c.state.fields!!.event.copy(recurrence=daily("4")))
                2 -> c.changeGuests(c.state.fields!!.guests.map {it.copy(name="Changed")})
                3 -> c.acceptFixedGuestPolicy(false)
                4 -> {f.source=f.source.copy(token=EditToken("new-source"));c.selectCalendar(f.calendar)}
                5 -> c.changeFields(c.state.fields!!.event.copy(description="Latest failed draft write"))
            }
            assertNull(c.state.recurrenceReview);c.confirmRecurringCreate();assertTrue(f.calls.isEmpty());assertFalse(c.frozen)
        }
    }

    @Test fun permissionGrantAloneNeverFreezesAndSourceRightsAreRecheckedAfterPermissionWait()=runBlocking {
        val f=MeetingCreationFixture();val c=f.recurring();c.save();f.granted=false
        c.confirmRecurringCreate();assertEquals(MeetingCreationProblem.PERMISSION,c.state.problem)
        assertFalse(c.frozen);assertTrue(f.calls.isEmpty());f.granted=true;assertTrue(f.calls.isEmpty())
        c.confirmRecurringCreate();assertEquals(1,f.calls.size)
        for(change in 0..3) {
            val g=MeetingCreationFixture();val other=g.recurring();other.save();val exact=g.record()
            g.permissionGate=CompletableDeferred();val save=launch(start=CoroutineStart.UNDISPATCHED) {other.confirmRecurringCreate()}
            g.source=when(change) {
                0 -> g.source.copy(recurringCreation=AccessDecision.UNKNOWN)
                1 -> g.source.copy(token=EditToken("changed"))
                2 -> g.source.copy(calendar=g.calendar.copy(providerId="changed"))
                else -> g.source.copy(allowedGuestTypes=setOf(MeetingGuestType.OPTIONAL))
            }
            g.permissionGate!!.complete(Unit);save.join()
            assertEquals(MeetingCreationProblem.SOURCE_CHANGED,other.state.problem);assertNull(other.state.recurrenceReview)
            assertFalse(other.frozen);assertEquals(exact,g.record());assertTrue(g.calls.isEmpty())
        }
    }

    @Test fun doubleConfirmIsSingleFlightAndAStaleRawRevisionCannotFreeze()=runBlocking {
        val f=MeetingCreationFixture();val c=f.recurring();c.save();f.permissionGate=CompletableDeferred()
        val first=launch(start=CoroutineStart.UNDISPATCHED) {c.confirmRecurringCreate()};c.confirmRecurringCreate()
        assertEquals(1,f.permissions);f.permissionGate!!.complete(Unit);first.join();c.confirmRecurringCreate();assertEquals(1,f.calls.size)
        val g=MeetingCreationFixture();val stale=g.recurring();stale.save();val original=g.record()
        val successor=original.copy(updatedAt=original.updatedAt.plusSeconds(1));g.store.rows["draft"]=successor
        stale.confirmRecurringCreate();assertEquals(MeetingCreationProblem.STALE_DRAFT,stale.state.problem)
        assertEquals(successor,g.record());assertTrue(g.calls.isEmpty())
    }

    @Test fun countLimitsAndInclusiveThroughDateAreKeptWithoutInventedDefaults()=runBlocking {
        for(count in listOf("1","10000","0","10001","partial")) {
            val f=MeetingCreationFixture();val c=f.recurring();c.changeFields(c.state.fields!!.event.copy(recurrence=daily(count)));val exact=f.record();c.save()
            if(count in listOf("1","10000")) assertEquals(RecurrenceEnd.Count(count.toInt()),c.state.recurrenceReview!!.spec.end)
            else assertNull(c.state.recurrenceReview)
            assertEquals(exact,f.record());assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        }
        val f=MeetingCreationFixture();val c=f.recurring();c.changeFields(c.state.fields!!.event.copy(recurrence=daily().copy(endMode="DATE",throughDate="2028-06-17")))
        c.save();assertEquals(RecurrenceEnd.ThroughDate(LocalDate.parse("2028-06-17")),c.state.recurrenceReview!!.spec.end)
        c.confirmRecurringCreate();assertEquals(RecurrenceEnd.ThroughDate(LocalDate.parse("2028-06-17")),f.calls.single().draft.recurrence!!.end)
    }

    @Test fun multiDayAllDayUsesUtcDateBoundsAndAcceptsOnlyExactRecurringAllResult()=runBlocking {
        val f=MeetingCreationFixture();val c=f.recurring();c.changeFields(c.state.fields!!.event.copy(allDay=true,startDate="2028-06-15",endDate="2028-06-17",zone="Pacific/Honolulu"))
        c.save();val review=c.state.recurrenceReview!!;assertEquals("P3D",review.plan.durationRaw);assertEquals(ZoneOffset.UTC,review.plan.eventZone)
        val begin=Instant.parse("2028-06-15T00:00:00Z");val end=Instant.parse("2028-06-18T00:00:00Z")
        val ref=EventRef(f.calendar.id,42,begin,end,RecurrenceRef(42,begin));f.next=MeetingMutationResult.LocalSaved(ref,RecurrenceScope.ALL)
        c.confirmRecurringCreate();assertEquals(ref,c.state.localSaved)
        assertEquals(EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-15"),LocalDate.parse("2028-06-18"))),f.calls.single().draft.time)
    }

    @Test fun elapsedDstAndLordHoweDurationsAndExplicitFoldOffsetsRemainAuthored()=runBlocking {
        val cases=listOf(
            listOf("America/New_York","2028-03-12","01:30","04:30","","","PT7200S"),
            listOf("America/New_York","2028-11-05","01:30","02:30","-04:00","-05:00","PT7200S"),
            listOf("Australia/Lord_Howe","2028-10-01","01:45","03:15","","","PT3600S"))
        for(case in cases) {
            val f=MeetingCreationFixture();val c=f.recurring();c.changeFields(c.state.fields!!.event.copy(zone=case[0],startDate=case[1],endDate=case[1],startTime=case[2],endTime=case[3],startOffset=case[4],endOffset=case[5]))
            c.save();assertEquals(case.toString(),case[6],c.state.recurrenceReview!!.plan.durationRaw)
            val reviewed=c.state.recurrenceReview!!.plan.firstTime;c.confirmRecurringCreate();assertEquals(reviewed,f.calls.single().draft.time)
        }
    }

    @Test fun gapsUnchosenFoldsPreviewTransitionsAndExcludedSeedNeverOfferCreation()=runBlocking {
        for(kind in 0..3) {
            val f=MeetingCreationFixture();val c=f.recurring()
            c.changeFields(when(kind) {
                0 -> c.state.fields!!.event.copy(zone="America/New_York",startDate="2028-03-12",endDate="2028-03-12",startTime="02:30",endTime="04:30")
                1 -> c.state.fields!!.event.copy(zone="America/New_York",startDate="2028-11-05",endDate="2028-11-05",startTime="01:30",endTime="02:30")
                2 -> c.state.fields!!.event.copy(zone="America/New_York",startDate="2028-11-04",endDate="2028-11-04",startTime="01:30",endTime="02:30")
                else -> c.state.fields!!.event.copy(recurrence=RecurrenceEditorFields(frequency="WEEKLY",weekdays=listOf("TUESDAY"),endMode="COUNT",count="3"))
            })
            val exact=f.record();c.save();assertNull("kind=$kind",c.state.recurrenceReview);c.confirmRecurringCreate()
            assertEquals(exact,f.record());assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        }
    }

    @Test fun malformedRecurringSuccessScopeParentOriginalCalendarOrBoundsStaysUncertain()=runBlocking {
        for(kind in 0..6) {
            val f=MeetingCreationFixture();val c=f.recurring();c.save()
            val ref=f.ref.copy(recurrence=RecurrenceRef(f.ref.eventId,f.start))
            val wrong=when(kind) {
                0 -> ref.copy(recurrence=null)
                1 -> ref.copy(recurrence=RecurrenceRef(999,f.start))
                2 -> ref.copy(recurrence=RecurrenceRef(ref.eventId,f.start.plusSeconds(1)))
                3 -> ref.copy(calendarId=CalendarId(3))
                4 -> ref.copy(instanceEnd=ref.instanceEnd.plusSeconds(1))
                5 -> ref.copy(eventId=0,recurrence=RecurrenceRef(0,f.start))
                else -> ref
            }
            f.next=MeetingMutationResult.LocalSaved(wrong,if(kind==6) RecurrenceScope.THIS else RecurrenceScope.ALL)
            c.confirmRecurringCreate();assertNull(c.state.localSaved);assertEquals(MeetingCreationProblem.UNCERTAIN,c.state.problem)
            val exact=f.record();c.save();c.confirmRecurringCreate();assertEquals(1,f.calls.size);assertEquals(exact,f.record())
        }
    }

    @Test fun cancelledCommittedRecurringFreezeAdoptsExactCommandAndNeverDispatchesBeforeExplicitRecovery()=runBlocking {
        for(unknown in listOf(false,true)) {
            val f=MeetingCreationFixture();val c=f.recurring();c.save();val committed=CompletableDeferred<Unit>();val ack=CompletableDeferred<Unit>()
            f.store.unknownAfter=unknown;f.store.afterCas={_,record ->if(record?.let {MeetingCreationDraftCodec.decode(it).frozen}!=null) {committed.complete(Unit);ack.await()}}
            val job=launch(start=CoroutineStart.UNDISPATCHED) {c.confirmRecurringCreate()};committed.await();val exact=f.record()
            job.cancel();ack.complete(Unit);job.join();assertTrue(c.frozen);assertTrue(f.calls.isEmpty());assertEquals(exact,f.record())
            f.store.afterCas={_,_->};val reads=f.reads;val reopened=f.controller(f.launch());reopened.load();assertEquals(reads,f.reads)
            val frozen=MeetingCreationDraftCodec.decode(exact).frozen!!;f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry(frozen.operationId))
            reopened.recover();assertTrue(reopened.state.retryReady);assertTrue(f.calls.isEmpty());reopened.retryOriginal()
            assertEquals(frozen.draft,f.calls.single().draft);assertEquals(frozen.token,f.calls.single().token);assertEquals(exact,f.record());assertEquals(reads,f.reads)
        }
    }

    @Test fun frozenSchemaTwoRecoversHistoricalUnparseableRawAndRetiresOriginalTypedRecurrence()=runBlocking {
        val f=MeetingCreationFixture();val c=f.recurring();c.save();c.confirmRecurringCreate();val original=MeetingCreationDraftCodec.decode(f.record())
        val raw=original.fields.copy(event=original.fields.event.copy(startDate="historical date",zone="historical zone",recurrence=daily("old count")),
            guests=original.fields.guests.map {it.copy(email=" historical email ",type="OLD_TYPE")})
        val historical=MeetingCreationDraftCodec.record(original.copy(fields=raw,frozen=original.frozen!!.copy(rawFingerprint=MeetingCreationDraftCodec.fingerprint(raw))),f.clock.instant())
        f.store.rows["draft"]=historical;val reads=f.reads;val reopened=f.controller(f.launch());reopened.load();reopened.save();assertEquals(reads,f.reads)
        f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry(original.operationId));reopened.recover();reopened.retryOriginal()
        assertEquals(f.calls.first(),f.calls.last());assertEquals(historical,f.record());assertEquals(reads,f.reads)
        f.retired=true;reopened.requestDiscard();reopened.confirmDiscard()
        assertEquals(original.frozen.retirement(),f.retiredRequest);assertTrue(reopened.state.discarded)
    }

    @Test fun unknownSchemaOrCapabilityAndMissingTypedRecurrenceNeverFallBackOrRewriteFrozenRecord()=runBlocking {
        for(kind in 0..4) {
            val f=MeetingCreationFixture();val c=f.recurring();c.save();c.confirmRecurringCreate();val old=f.record();val json=JSONObject(old.payload)
            when(kind) {
                0 -> json.put("schema",3)
                1 -> json.getJSONObject("authority").put("recurringCreation","UNKNOWN")
                2 -> json.getJSONObject("authority").put("recurringCreation","FUTURE")
                3 -> json.getJSONObject("frozen").getJSONObject("draft").remove("recurrence")
                else -> json.getJSONObject("frozen").put("schema",1)
            }
            val broken=old.copy(payload=json.toString());f.store.rows["draft"]=broken;val reads=f.reads
            assertNull(MeetingCreationDraftCodec.describe(broken));val reopened=f.controller(c.launch);reopened.load();reopened.recover();reopened.retryOriginal();reopened.requestDiscard();reopened.confirmDiscard()
            assertEquals(MeetingCreationProblem.DRAFT_REVIEW,reopened.state.problem);assertEquals(broken,f.record());assertEquals(reads,f.reads);assertEquals(1,f.calls.size)
        }
    }

    @Test fun schemaOneFrozenWireAndCommandRemainExactAcrossReadRetryAndRetirement()=runBlocking {
        val f=MeetingCreationFixture();f.ready().save();val original=MeetingCreationDraftCodec.decode(f.record())
        val legacy=MeetingCreationDraftCodec.record(original.copy(schema=1),f.clock.instant());val json=JSONObject(legacy.payload)
        assertEquals(1,json.getInt("schema"));assertFalse(json.getJSONObject("authority").has("recurringCreation"))
        assertFalse(json.getJSONObject("frozen").getJSONObject("draft").has("recurrence"))
        assertEquals(legacy,MeetingCreationDraftCodec.record(MeetingCreationDraftCodec.decode(legacy),legacy.updatedAt))
        f.store.rows["draft"]=legacy;f.source=f.source.copy(recurringCreation=AccessDecision.ALLOWED,token=EditToken("new-v2-source"))
        val reads=f.reads;val reopened=f.controller(f.launch());reopened.load();assertEquals(reads,f.reads);assertEquals(legacy,f.record())
        f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry(original.operationId));reopened.recover();reopened.retryOriginal()
        assertEquals(f.calls.first(),f.calls.last());assertEquals(legacy,f.record());assertEquals(reads,f.reads)
        f.retired=true;reopened.requestDiscard();reopened.confirmDiscard();assertEquals(original.frozen!!.retirement(),f.retiredRequest)
    }

    @Test fun schemaOneRawDoesNotUpgradeOnReadOrTypingOnlyExplicitFreshCalendarSelection()=runBlocking {
        val f=MeetingCreationFixture();val c=f.open();val original=MeetingCreationDraftCodec.decode(f.record())
        f.store.rows["draft"]=MeetingCreationDraftCodec.record(original.copy(schema=1),f.clock.instant())
        val exact=f.record();val reopened=f.controller(f.launch());reopened.load();assertEquals(exact,f.record());assertFalse(reopened.canEditRepeat)
        reopened.changeFields(reopened.state.fields!!.event.copy(title="Still raw schema one"));assertEquals(1,MeetingCreationDraftCodec.decode(f.record()).schema)
        val raw=reopened.state.fields!!.event;f.source=f.source.copy(recurringCreation=AccessDecision.ALLOWED)
        reopened.selectCalendar(f.calendar);assertEquals(2,MeetingCreationDraftCodec.decode(f.record()).schema)
        assertTrue(reopened.canEditRepeat);assertEquals(raw,reopened.state.fields!!.event)
    }
}
