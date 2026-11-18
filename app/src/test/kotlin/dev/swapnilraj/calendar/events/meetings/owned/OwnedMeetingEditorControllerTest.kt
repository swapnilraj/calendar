package dev.swapnilraj.calendar.events.meetings.owned

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields
import dev.swapnilraj.calendar.ui.events.meetings.owned.*
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
class OwnedMeetingEditorControllerTest {
    @Test fun openingClosingAndCollisionNeverMutateProviderOrReplaceOneDraft()=runBlocking {
        val f=OwnedFixture();val c=f.open();val exact=f.record()
        assertEquals(0,f.calls.size);assertEquals(0,f.permissions);assertFalse(c.canSave);assertTrue(c.prepareDismiss())
        assertEquals(exact,f.record());assertEquals(OwnedMeetingPreparation.RetainedForReview,prepareOwnedMeetingDraft(f.source,"owned",ZoneId.of("Asia/Tokyo"),f.store,f.clock))
        assertEquals(exact,f.record());assertEquals(1,f.store.rows.size)
    }
    @Test fun factoryRejectsMismatchedCanonicalTimeRecurringOrUnprovenOwnerBeforeAnyRecordWrite()=runBlocking {
        for(kind in 0..3) {
            val f=OwnedFixture();val d=f.source.details
            val source=when(kind) {
                0 -> f.source.copy(details=d.copy(occurrence=d.occurrence.copy(time=EventTime.Timed(f.start.plusSeconds(1),f.start.plusSeconds(3601)))))
                1 -> f.source.copy(details=d.copy(occurrence=d.occurrence.copy(ref=f.ref.copy(recurrence=RecurrenceRef(f.ref.eventId,f.start)))))
                2 -> f.source.copy(details=d.copy(calendar=f.calendar.copy(owner="other@example.test")))
                else -> f.source.copy(details=d.copy(isOrganizer=false))
            }
            assertEquals(OwnedMeetingPreparation.Unavailable,prepareOwnedMeetingDraft(source,"owned",ZoneOffset.UTC,f.store,f.clock))
            assertTrue(f.store.rows.isEmpty());assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun titleOnlyPreservesNullZoneDefaultAlertsUnknownMetadataAndGuestResponses()=runBlocking {
        val f=OwnedFixture();f.source=f.source.copy(details=f.source.details.copy(eventZone=null,privacy=99,availability=99,reminders=listOf(EventReminder(-1,1),EventReminder(15,2))))
        val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Changed"))});c.save()
        val command=f.calls.single().command as MeetingCommand.EditOwned
        assertEquals(FieldChange.Set("Changed"),command.patch.title)
        assertEquals(FieldChange.Keep,command.patch.time);assertEquals(FieldChange.Keep,command.patch.eventZone)
        assertEquals(FieldChange.Keep,command.patch.alertMinutes);assertEquals(FieldChange.Keep,command.patch.privacy);assertEquals(FieldChange.Keep,command.patch.availability)
        assertTrue(command.guests.isEmpty());assertNull(OwnedMeetingDraftCodec.decode(f.record()).baseline.zone)
    }
    @Test fun sourceNullZoneRendererSurvivesReopenAndSameDisplayedZoneIsExplicitAuthoredIntent()=runBlocking {
        val f=OwnedFixture();f.source=f.source.copy(details=f.source.details.copy(eventZone=null));val c=f.open()
        assertEquals("Asia/Tokyo",c.state.fields!!.event.zone)
        val restored=f.controller(f.launch());restored.load();assertEquals(c.state.fields,restored.state.fields)
        restored.changeFields(restored.state.fields!!.let {it.copy(event=it.event.copy(startTime="19:00",endTime="20:00"))})
        assertFalse(restored.canSave);restored.save();assertTrue(f.calls.isEmpty())
        restored.selectZone("Asia/Tokyo");assertEquals("SET",restored.state.fields!!.zoneIntent);restored.save()
        val patch=(f.calls.single().command as MeetingCommand.EditOwned).patch
        assertEquals(FieldChange.Set(ZoneId.of("Asia/Tokyo")),patch.eventZone)
        assertEquals(FieldChange.Set(EventTime.Timed(Instant.parse("2028-06-15T10:00:00Z"),Instant.parse("2028-06-15T11:00:00Z"))),patch.time)
    }
    @Test fun unknownZoneWithoutZoneRightAndAllDayToTimedCannotBorrowTemporalRights()=runBlocking {
        val f=OwnedFixture();f.source=f.source.copy(details=f.source.details.copy(eventZone=null),scopes=mapOf(RecurrenceScope.THIS to f.rights.copy(event=f.rights.event.copy(fields=f.rights.event.fields-EditableField.ZONE))))
        val c=f.open();assertFalse(c.canEdit(EditableField.TIME));assertFalse(c.canEdit(EditableField.ZONE));val old=f.record()
        c.selectZone("UTC");c.setAllDay(true);assertEquals(old,f.record());assertTrue(f.calls.isEmpty())
        val g=OwnedFixture();val dates=EventTime.AllDay(CalendarDateRange(LocalDate.parse("2028-06-15"),LocalDate.parse("2028-06-17")))
        val ref=g.ref.copy(instanceStart=Instant.parse("2028-06-15T00:00:00Z"),instanceEnd=Instant.parse("2028-06-17T00:00:00Z"))
        g.source=g.source.copy(details=g.source.details.copy(occurrence=g.source.details.occurrence.copy(ref=ref,time=dates)))
        val allDay=g.open();assertFalse(allDay.canChangeKind);val exact=g.record();allDay.setAllDay(false);assertEquals(exact,g.record())
    }
    @Test fun ownedGuestDeltaUsesOnlyIssuedOpaqueRefsAndLeavesOtherRowsUnrepresented()=runBlocking {
        val f=OwnedFixture();val c=f.open();val raw=c.state.fields!!
        c.changeFields(raw.copy(guests=raw.guests.mapIndexed {i,g->if(i==0) g.copy(type="OPTIONAL") else g.copy(removed=true)},added=listOf(MeetingCreationGuestFields(email="new@example.test",name="New",type="REQUIRED"))))
        c.save();val command=f.calls.single().command as MeetingCommand.EditOwned
        assertEquals(EventPatch(),command.patch)
        assertEquals(listOf(MeetingGuestChange.ChangeType(f.rows[0].ref,MeetingGuestType.OPTIONAL),MeetingGuestChange.Remove(f.rows[1].ref),MeetingGuestChange.Add(MeetingGuestInput("new@example.test",MeetingGuestType.REQUIRED,"New"))),command.guests)
        assertTrue(command.guests.none {it is MeetingGuestChange.ChangeType && it.ref.value.contains("self")})
    }
    @Test fun exactNoopsAndReturningKnownGuestRoleToOriginalNeverChangeDraftOrSubmit()=runBlocking {
        val f=OwnedFixture();val c=f.open();val exact=f.record()
        c.changeFields(c.state.fields!!.copy());assertEquals(exact,f.record());assertFalse(c.canSave)
        c.changeFields(c.state.fields!!.let {it.copy(guests=it.guests.mapIndexed {i,g->if(i==0) g.copy(type="REQUIRED") else g})})
        assertEquals(exact,f.record());assertFalse(c.canSave);assertTrue(f.calls.isEmpty())
    }
    @Test fun unknownExistingGuestValuesStayReadonlyButNeverBlockUnrelatedTitlePatch()=runBlocking {
        val f=OwnedFixture();val opaque=f.rows[0].copy(attendee=f.rows[0].attendee.copy(type=999,status=999,relationship=999),allowed=emptySet())
        f.source=f.source.copy(scopes=mapOf(RecurrenceScope.THIS to f.rights.copy(guests=listOf(opaque,f.rows[1]))))
        val c=f.open();val original=c.state.fields!!;c.changeFields(original.copy(guests=original.guests.mapIndexed {i,g->if(i==0) g.copy(removed=true) else g}))
        assertEquals(original,c.state.fields);c.changeFields(original.copy(event=original.event.copy(title="Allowed")));c.save()
        assertTrue((f.calls.single().command as MeetingCommand.EditOwned).guests.isEmpty())
    }
    @Test fun foreignDuplicateSelectorsUnsupportedTypesAndPartialNewPeopleNeverFreeze()=runBlocking {
        for(kind in 0..5) {
            val f=OwnedFixture();val c=f.open();val original=c.state.fields!!
            val raw=when(kind) {
                0 -> original.copy(guests=original.guests.mapIndexed {i,g->if(i==0) g.copy(ref=MeetingGuestRef("foreign")) else g})
                1 -> original.copy(guests=listOf(original.guests[0],original.guests[0]))
                2 -> original.copy(guests=original.guests.mapIndexed {i,g->if(i==0) g.copy(type="RESOURCE") else g})
                3 -> original.copy(added=listOf(MeetingCreationGuestFields(email="partial@",type="REQUIRED")))
                4 -> original.copy(added=listOf(MeetingCreationGuestFields(email=f.calendar.owner!!,type="REQUIRED")))
                else -> original.copy(added=listOf(MeetingCreationGuestFields(email=f.rows[0].attendee.email,type="OPTIONAL")))
            }
            c.changeFields(raw);c.save();assertTrue("kind=$kind",f.calls.isEmpty());assertFalse(c.frozen)
        }
    }
    @Test fun availabilityAlwaysReadonlyAndForbiddenEventFieldsAreNotPartiallyAccepted()=runBlocking {
        val f=OwnedFixture();val c=f.open();val exact=f.record();val raw=c.state.fields!!
        assertFalse(c.canEdit(EditableField.AVAILABILITY))
        c.changeFields(raw.copy(event=raw.event.copy(title="Would change",availability="1")));assertEquals(exact,f.record())
        c.changeFields(raw.copy(event=raw.event.copy(calendarId="99")));assertEquals(exact,f.record())
        c.changeFields(raw.copy(event=raw.event.copy(recurrence=RecurrenceEditorFields(frequency="DAILY"))));assertEquals(exact,f.record());assertTrue(f.calls.isEmpty())
    }
    @Test fun sourceTokenNamespaceStatusRightsAndGuestResponseDriftDuringPermissionNeverRebase()=runBlocking {
        for(kind in 0..4) {
            val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Raw draft"))});val exact=f.record()
            f.permissionGate=CompletableDeferred();val save=launch(start=CoroutineStart.UNDISPATCHED) {c.save()}
            f.source=when(kind) {
                0 -> f.source.copy(token=EditToken("new-uid-source"))
                1 -> f.source.copy(details=f.source.details.copy(calendar=f.calendar.copy(providerId="new-namespace")))
                2 -> f.source.copy(details=f.source.details.copy(status=2))
                3 -> f.source.copy(scopes=mapOf(RecurrenceScope.THIS to f.rights.copy(event=f.rights.event.copy(fields=emptySet()))))
                else -> f.source.copy(scopes=mapOf(RecurrenceScope.THIS to f.rights.copy(guests=f.rows.mapIndexed {i,g->if(i==0) g.copy(attendee=g.attendee.copy(status=2)) else g})))
            }
            f.permissionGate!!.complete(Unit);save.join()
            assertEquals(OwnedMeetingProblem.SOURCE_CHANGED,c.state.problem);assertFalse(c.frozen);assertEquals(exact,f.record());assertTrue(f.calls.isEmpty())
        }
    }
    @Test fun grantingWriteNeverAutosubmitsAndTwoSavesCannotDispatchTwice()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))});f.granted=false;c.save()
        assertFalse(c.frozen);f.granted=true;assertTrue(f.calls.isEmpty())
        f.permissionGate=CompletableDeferred();val first=launch(start=CoroutineStart.UNDISPATCHED) {c.save()};c.save();assertEquals(2,f.permissions)
        f.permissionGate!!.complete(Unit);first.join();c.save();assertEquals(1,f.calls.size)
    }
    @Test fun partialRawPersistenceFailureRetainsContinuedTypingAndBlocksCloseUntilExactFlush()=runBlocking {
        val f=OwnedFixture();val c=f.open();f.store.unknownBefore=true
        c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="First"))})
        c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Latest",description="Partial draft"))})
        assertEquals("Latest",c.state.fields!!.event.title);assertFalse(c.prepareDismiss());assertTrue(f.calls.isEmpty())
        f.store.unknownBefore=false;c.flushDraft();assertTrue(c.prepareDismiss())
        assertEquals("Latest",OwnedMeetingDraftCodec.decode(f.record()).fields.event.title)
    }
    @Test fun cancelIsExplicitStatusCommandAndNeverAppliesUnfinishedEventOrGuestChanges()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(startDate="2028-"),added=listOf(MeetingCreationGuestFields(email="partial@")))})
        assertFalse(c.canSave);assertTrue(c.canCancel);c.confirmCancellation();assertTrue(f.calls.isEmpty())
        c.requestCancellation();c.dismissCancellation();assertTrue(f.calls.isEmpty());assertEquals(0,f.permissions)
        c.requestCancellation();c.confirmCancellation();assertEquals(MeetingCommand.CancelOwned(RecurrenceScope.THIS),f.calls.single().command)
        assertTrue(OwnedMeetingDraftCodec.decode(f.record()).fields.added.isNotEmpty())
    }
    @Test fun sourceFreeRecoveryWithoutWriteRetainsExactFrozenCommandAndMatchingProofAloneRetries()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))});c.save()
        val exact=f.record();val frozen=OwnedMeetingDraftCodec.decode(exact).frozen!!;val reads=f.reads;val reopened=f.controller(f.launch());reopened.load()
        assertEquals(reads,f.reads);f.granted=false;f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry("wrong"));reopened.recover();assertFalse(reopened.state.retryReady)
        f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry(frozen.operationId));reopened.recover();assertTrue(reopened.state.retryReady);assertEquals(1,f.calls.size)
        reopened.retryOriginal();assertEquals(1,f.calls.size);f.granted=true;reopened.retryOriginal();assertEquals(f.calls.first(),f.calls.last());assertEquals(exact,f.record());assertEquals(reads,f.reads)
    }
    @Test fun knownCancellationRecoveryAndAcknowledgmentNeedReadButNeverRequestWritePermission()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.requestCancellation();c.confirmCancellation();val exact=f.record();val reads=f.reads;val permissions=f.permissions
        f.granted=false;f.source=f.source.copy(token=EditToken("new-source-ignored-for-recovery"))
        f.recovery=MeetingMutationResult.LocalCancelled(f.ref,RecurrenceScope.THIS);val reopened=f.controller(f.launch());reopened.load();reopened.recover()
        assertEquals(f.recovery,reopened.state.localOutcome);assertEquals(reads,f.reads);assertEquals(permissions,f.permissions);assertEquals(exact,f.record())
        reopened.acknowledgeLocalOutcome();assertTrue(reopened.state.acknowledged);assertEquals(permissions,f.permissions);assertEquals(1,f.calls.size)
    }
    @Test fun cancelledFreezeCommitAlwaysAdoptsAndRecoversWithoutProviderDispatch()=runBlocking {
        for(unknown in listOf(false,true)) {
            val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))})
            val committed=CompletableDeferred<Unit>();val ack=CompletableDeferred<Unit>();f.store.unknownAfter=unknown
            f.store.afterCas={r->if(r?.let {OwnedMeetingDraftCodec.decode(it).frozen}!=null) {committed.complete(Unit);ack.await()}}
            val save=launch(start=CoroutineStart.UNDISPATCHED) {c.save()};committed.await();val exact=f.record();save.cancel();ack.complete(Unit);save.join()
            assertTrue(c.frozen);assertEquals(exact,f.record());assertTrue(f.calls.isEmpty());f.store.afterCas={}
            val reads=f.reads;val reopened=f.controller(f.launch());reopened.load();assertEquals(reads,f.reads);assertTrue(reopened.state.recoveryRequired)
        }
    }
    @Test fun changedDraftRaceCannotOverwriteFrozenSuccessorOrDispatchStaleRaw()=runBlocking {
        val f=OwnedFixture();val first=f.open();val second=f.controller(f.launch());second.load()
        first.changeFields(first.state.fields!!.let {it.copy(event=it.event.copy(title="Winner"))});first.save();val exact=f.record()
        second.changeFields(second.state.fields!!.let {it.copy(event=it.event.copy(title="Stale"))});second.save()
        assertEquals(exact,f.record());assertEquals(1,f.calls.size);assertEquals(OwnedMeetingProblem.DRAFT_CHANGED,second.state.problem)
    }
    @Test fun wrongOutcomeKindScopeIdentityAndBoundsStayUncertainForEditAndCancellation()=runBlocking {
        for(cancel in listOf(false,true)) for(kind in 0..4) {
            val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))})
            val wrong=when(kind) {1->f.ref.copy(calendarId=CalendarId(3));2->f.ref.copy(eventId=99);3->f.ref.copy(instanceEnd=f.ref.instanceEnd.plusSeconds(1));else->f.ref}
            f.next=if(cancel xor (kind==0)) MeetingMutationResult.LocalCancelled(wrong,if(kind==4) RecurrenceScope.ALL else RecurrenceScope.THIS) else MeetingMutationResult.LocalSaved(wrong,if(kind==4) RecurrenceScope.ALL else RecurrenceScope.THIS)
            if(cancel) {c.requestCancellation();c.confirmCancellation()} else c.save()
            assertNull(c.state.localOutcome);assertEquals(OwnedMeetingProblem.UNCERTAIN,c.state.problem);assertEquals(1,f.calls.size)
        }
    }
    @Test fun timeEditLocalSuccessRequiresExactTypedBoundsAndCleanupBeforeAcknowledgment()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(startTime="10:00",endTime="11:00"))})
        val expected=f.ref.copy(instanceStart=f.start.plusSeconds(3600),instanceEnd=f.start.plusSeconds(7200))
        f.next=MeetingMutationResult.LocalSaved(expected,RecurrenceScope.THIS);c.save();assertEquals(f.next,c.state.localOutcome);assertFalse(c.state.acknowledged)
        c.acknowledgeLocalOutcome();assertTrue(c.state.acknowledged);assertTrue(f.store.rows.isEmpty());assertEquals(1,f.calls.size)
    }
    @Test fun maskDuringInFlightWriteScrubsPublicStateButRetainsOutcomeAndNeedsNoSourceForFrozenRestore()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))});f.mutateGate=CompletableDeferred();f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS)
        val save=launch(start=CoroutineStart.UNDISPATCHED) {c.save()};assertEquals(1,f.calls.size);c.observeReadPermission(false)
        assertNull(c.state.fields);assertNull(c.state.localOutcome);assertTrue(c.guestRows.isEmpty());assertEquals("",c.calendarLabel)
        f.mutateGate!!.complete(Unit);save.join();assertNull(c.state.localOutcome);val reads=f.reads
        c.observeReadPermission(true);c.refreshSource();assertEquals(reads,f.reads);assertEquals(f.next,c.state.localOutcome)
        c.acknowledgeLocalOutcome();assertTrue(c.state.acknowledged)
    }
    @Test fun readLossBeforeFreezeBlocksSaveUntilGrantAndFreshExplicitSave()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))});val exact=f.record();f.permissionGate=CompletableDeferred()
        val save=launch(start=CoroutineStart.UNDISPATCHED) {c.save()};c.observeReadPermission(false);f.permissionGate!!.complete(Unit);save.join()
        assertEquals(exact,f.record());assertFalse(c.frozen);assertTrue(f.calls.isEmpty());c.observeReadPermission(true);assertFalse(c.canSave)
        c.refreshSource();assertTrue(c.canSave);assertTrue(f.calls.isEmpty());c.save();assertEquals(1,f.calls.size)
    }
    @Test fun frozenDiscardNeedsExactRetirementAndCleanupUnknownAckNeverAdoptsSuccessor()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))});c.save();val frozen=OwnedMeetingDraftCodec.decode(f.record()).frozen!!
        c.requestDiscard();c.confirmDiscard();assertFalse(c.state.discarded);assertEquals(frozen.retirement(),f.retiredRequest)
        f.retired=true;f.store.failAfterDelete=true;c.requestDiscard();c.confirmDiscard();assertFalse(c.state.discarded);assertTrue(f.store.rows.isEmpty())
        f.store.failRead=false;c.requestDiscard();c.confirmDiscard();assertTrue(c.state.discarded);assertEquals(1,f.calls.size)
        val g=OwnedFixture();val other=g.open();other.changeFields(other.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))});g.next=MeetingMutationResult.LocalSaved(g.ref,RecurrenceScope.THIS);other.save()
        val successor=g.record().copy(updatedAt=g.clock.instant().plusSeconds(1));g.store.rows["owned"]=successor;other.acknowledgeLocalOutcome();g.store.rows.clear();other.acknowledgeLocalOutcome()
        assertFalse(other.state.acknowledged);assertEquals(OwnedMeetingProblem.DRAFT_CHANGED,other.state.problem)
    }
    @Test fun frozenHistoricalRawNeverReparsesAndMalformedFutureRecordsRemainExactReviewOnly()=runBlocking {
        val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Typed change"))});c.save()
        val doc=OwnedMeetingDraftCodec.decode(f.record());val raw=doc.fields.copy(event=doc.fields.event.copy(startDate="historical",zone="historical zone"),added=listOf(MeetingCreationGuestFields(email=" historical ",type="OLD")))
        val exact=OwnedMeetingDraftCodec.record(doc.copy(fields=raw,frozen=doc.frozen!!.copy(fingerprint=OwnedJson.fingerprint(raw))),f.clock.instant());f.store.rows["owned"]=exact
        val reads=f.reads;val reopened=f.controller(f.launch());reopened.load();f.recovery=MeetingMutationResult.Common(MutationResult.SafeToRetry(doc.operationId));reopened.recover();reopened.retryOriginal()
        assertEquals(f.calls.first(),f.calls.last());assertEquals(exact,f.record());assertEquals(reads,f.reads)
        val broken=exact.copy(payload=JSONObject(exact.payload).put("schema",99).toString());f.store.rows["owned"]=broken
        assertNull(OwnedMeetingDraftCodec.describe(broken));val locked=f.controller(c.launch);locked.load();locked.recover();locked.save();assertEquals(broken,f.record());assertEquals(OwnedMeetingProblem.DRAFT_REVIEW,locked.state.problem)
    }
    @Test fun sourceHintAwaitNeverDropsAcceptedTypingEvenWhenReadAccessOrSourceChanges()=runBlocking {
        for(kind in 0..2) {
            val f=OwnedFixture();val c=f.open();f.readGate=CompletableDeferred()
            val refresh=launch(start=CoroutineStart.UNDISPATCHED) {c.refreshSource()}
            val typing=launch(start=CoroutineStart.UNDISPATCHED) {c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Latest while reading"))})}
            assertFalse(typing.isCompleted)
            if(kind==1) c.observeReadPermission(false)
            if(kind==2) f.source=f.source.copy(token=EditToken("foreign-source-after-hint"))
            f.readGate!!.complete(Unit);refresh.join();typing.join()
            assertEquals("Latest while reading",OwnedMeetingDraftCodec.decode(f.record()).fields.event.title);assertTrue(f.calls.isEmpty())
            if(kind==1) {c.observeReadPermission(true);c.refreshSource()}
            if(kind==2) {assertFalse(c.canSave);assertEquals(OwnedMeetingProblem.SOURCE_CHANGED,c.state.problem);assertEquals(EditToken("owned-source-A"),OwnedMeetingDraftCodec.decode(f.record()).baseline.token)}
            else {assertTrue(c.canSave);c.save();assertEquals(1,f.calls.size)}
        }
    }
    @Test fun authoredGapAndUnchosenFoldBlockButChosenOffsetFreezesExactElapsedInterval()=runBlocking {
        val f=OwnedFixture();f.source=f.source.copy(details=f.source.details.copy(eventZone=ZoneId.of("America/New_York")));val c=f.open()
        c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(startDate="2028-03-12",endDate="2028-03-12",startTime="02:30",endTime="04:30"))});c.save();assertTrue(f.calls.isEmpty())
        c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(startDate="2028-11-05",endDate="2028-11-05",startTime="01:30",endTime="02:30"))});c.save();assertTrue(f.calls.isEmpty())
        c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(startOffset="-04:00",endOffset="-05:00"))});c.save()
        val expected=EventTime.Timed(Instant.parse("2028-11-05T05:30:00Z"),Instant.parse("2028-11-05T07:30:00Z"))
        assertEquals(FieldChange.Set(expected),(f.calls.single().command as MeetingCommand.EditOwned).patch.time)
    }
    @Test fun frozenRawHashIdentityAndTypedCommandTamperRemainExactReviewOnly()=runBlocking {
        for(kind in 0..3) {
            val f=OwnedFixture();val c=f.open();c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Change"))});c.save();val exact=f.record();val j=JSONObject(exact.payload)
            when(kind) {
                0 -> j.getJSONObject("fields").getJSONObject("event").put("title","tampered")
                1 -> j.getJSONObject("frozen").put("fingerprintSchema",2)
                2 -> j.getJSONObject("frozen").getJSONObject("command").put("scope","ALL")
                else -> j.getJSONObject("baseline").getJSONObject("identity").put("providerId","foreign")
            }
            val broken=exact.copy(payload=j.toString());f.store.rows["owned"]=broken;val reads=f.reads
            assertNull(OwnedMeetingDraftCodec.describe(broken));val locked=f.controller(c.launch);locked.load();locked.recover();locked.retryOriginal()
            assertEquals(OwnedMeetingProblem.DRAFT_REVIEW,locked.state.problem);assertEquals(broken,f.record());assertEquals(reads,f.reads);assertEquals(1,f.calls.size)
        }
    }
    @Test fun provenLeaseLossRequiresExplicitNonmutatingLeaveForRawFrozenAndKnownOutcomes()=runBlocking {
        for(kind in 0..2) {
            val f=OwnedFixture();val c=f.open()
            if(kind==2) f.next=MeetingMutationResult.LocalSaved(f.ref,RecurrenceScope.THIS)
            if(kind!=0) {c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Saved command"))});c.save()}
            val successor=f.record().copy(updatedAt=f.clock.instant().plusSeconds(1));f.store.rows["owned"]=successor
            when(kind) {
                0 -> c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Latest unstored input"))})
                1 -> c.recover()
                2 -> c.acknowledgeLocalOutcome()
            }
            assertTrue(c.canLeaveAfterLeaseLoss);assertFalse(c.prepareDismiss())
            assertEquals(when(kind) {0->OwnedMeetingLeaveWarning.UNSAVED_INPUT;1->OwnedMeetingLeaveWarning.UNCERTAIN_REQUEST;else->OwnedMeetingLeaveWarning.LOCAL_OPERATION},c.leaveWarning)
            c.refreshSource();assertEquals(OwnedMeetingProblem.DRAFT_CHANGED,c.state.problem);assertFalse(c.canSave);assertFalse(c.canRecover)
            val loads=f.store.loads;val changes=f.store.changes;val reads=f.reads;val calls=f.calls.toList();val retired=f.retiredRequest
            assertTrue(c.confirmLeaveAfterLeaseLoss());assertFalse(c.confirmLeaveAfterLeaseLoss())
            assertEquals(loads,f.store.loads);assertEquals(changes,f.store.changes);assertEquals(reads,f.reads);assertEquals(calls,f.calls);assertEquals(retired,f.retiredRequest)
            assertEquals(successor,f.record());assertFalse(c.state.acknowledged);assertFalse(c.state.discarded)
            c.save();c.recover();c.requestDiscard();c.confirmDiscard();assertEquals(successor,f.record());assertEquals(calls,f.calls)
        }
    }
    @Test fun uncertainStorageOrInFlightActionNeverAuthorizesLeavingAsProvenLeaseLoss()=runBlocking {
        val f=OwnedFixture();val c=f.open();f.store.unknownBefore=true;c.changeFields(c.state.fields!!.let {it.copy(event=it.event.copy(title="Unstored"))})
        assertFalse(c.canLeaveAfterLeaseLoss);assertFalse(c.confirmLeaveAfterLeaseLoss())
        f.store.unknownBefore=false;c.flushDraft();f.permissionGate=CompletableDeferred()
        val save=launch(start=CoroutineStart.UNDISPATCHED) {c.save()}
        assertFalse(c.confirmLeaveAfterLeaseLoss());f.permissionGate!!.complete(Unit);save.join()
        assertEquals(1,f.calls.size);assertFalse(c.state.discarded)
    }
}

internal class OwnedFixture {
    val clock=Clock.fixed(Instant.parse("2028-06-01T00:00:00Z"),ZoneOffset.UTC)
    val start=Instant.parse("2028-06-15T09:00:00Z");val ref=EventRef(CalendarId(2),42,start,start.plusSeconds(3600))
    val calendar=CalendarInfo(CalendarId(2),"Work",0xff336699.toInt(),CalendarAccount("owner@example.test","Fixture"),"owner@example.test",ZoneOffset.UTC,700,true,true,providerId="calendar-A")
    val rows=listOf(MeetingGuestRow(MeetingGuestRef("opaque-a"),EventAttendee("a@example.test","A",1,1,1),MeetingGuestAction.entries.toSet()),MeetingGuestRow(MeetingGuestRef("opaque-b"),EventAttendee("b@example.test","B",4,1,2),MeetingGuestAction.entries.toSet()))
    val rights=MeetingScopeAccess(event=SeriesScopeCapabilities(AccessDecision.ALLOWED,EditableField.entries.toSet(),AccessDecision.ALLOWED),respond=AccessDecision.DENIED,addGuests=AccessDecision.ALLOWED,allowedGuestTypes=MeetingGuestType.entries.toSet(),guests=rows)
    var source=EditableMeeting(EventDetails(EventOccurrence(ref,EventTime.Timed(ref.instanceStart,ref.instanceEnd),"Planning",calendar.color),calendar,organizer=calendar.owner,eventZone=ZoneOffset.UTC,
        attendees=rows.map {it.attendee},reminders=listOf(EventReminder(10,1)),status=1,isOrganizer=true),EditToken("owned-source-A"),mapOf(RecurrenceScope.THIS to rights))
    val store=OwnedStore();var reads=0;var permissions=0;var granted=true
    var permissionGate:CompletableDeferred<Unit>?=null;var mutateGate:CompletableDeferred<Unit>?=null;var readGate:CompletableDeferred<Unit>?=null
    data class Call(val id:String,val selected:EventRef,val token:EditToken,val command:MeetingCommand)
    val calls=mutableListOf<Call>();var next:MeetingMutationResult=MeetingMutationResult.Common(MutationResult.Unavailable)
    var recovery:MeetingMutationResult=MeetingMutationResult.Common(MutationResult.Unavailable)
    var retired=false;var retiredRequest:RetirableOperation?=null
    val retirement=object:OperationRetirement {
        override suspend fun retire(request:RetirableOperation):OperationRetirementResult {retiredRequest=request;return if(retired) OperationRetirementResult.Retired(request.operationId) else OperationRetirementResult.Blocked(request.operationId,RetirementBlockReason.MAY_HAVE_DISPATCHED)}
        override suspend fun inspect(request:RetirableOperation)=if(retired) OperationRetirementResult.Retired(request.operationId) else OperationRetirementResult.NotRetired(request.operationId)
    }
    val meetings=object:CalendarMeetings {
        override suspend fun loadForCreate(target:CalendarIdentity):MeetingReadResult<MeetingCreation> =error("wrong feature")
        override suspend fun loadForEdit(selected:EventRef):MeetingReadResult<EditableMeeting> {reads++;readGate?.await();return MeetingReadResult.Ready(source)}
        override suspend fun create(operationId:String,token:EditToken,draft:EventDraft,guests:List<MeetingGuestInput>):MeetingMutationResult=error("must not create")
        override suspend fun mutate(operationId:String,selected:EventRef,token:EditToken,command:MeetingCommand):MeetingMutationResult {
            assertNotNull(OwnedMeetingDraftCodec.decode(record()).frozen);calls+=Call(operationId,selected,token,command);mutateGate?.await();return next
        }
        override suspend fun recover(operationId:String)=recovery
    }
    fun record()=store.rows.getValue("owned")
    fun launch()=OwnedMeetingDraftCodec.describe(record())!!
    fun controller(launch:OwnedMeetingLaunch)=OwnedMeetingEditorController(launch,meetings,store,{permissions++;permissionGate?.await();granted},retirement,clock)
    suspend fun open():OwnedMeetingEditorController {
        val launch=(prepareOwnedMeetingDraft(source,"owned",ZoneId.of("Asia/Tokyo"),store,clock) as OwnedMeetingPreparation.Ready).launch
        return controller(launch).also {it.load()}
    }
}
internal class OwnedStore:AtomicDraftStore {
    val rows=mutableMapOf<String,DraftRecord>();var unknownBefore=false;var unknownAfter=false;var failRead=false;var failAfterDelete=false
    var loads=0;var changes=0
    var afterCas:suspend(DraftRecord?)->Unit={}
    override suspend fun load(id:String):DraftRecord? {loads++;if(failRead) error("unreadable");return rows[id]}
    override suspend fun list()=rows.values.toList()
    override suspend fun upsert(record:DraftRecord):Unit=error("no blind upsert")
    override suspend fun delete(id:String):Unit=error("no blind delete")
    override suspend fun compareAndSet(id:String,expected:DraftRecord?,replacement:DraftRecord?):DraftChangeResult {
        changes++
        if(unknownBefore) return DraftChangeResult.Unavailable
        if(rows[id]!=expected) return DraftChangeResult.Changed
        if(replacement==null) {rows.remove(id);if(failAfterDelete) failRead=true} else rows[id]=replacement
        afterCas(replacement)
        return if(unknownAfter) DraftChangeResult.Unavailable else DraftChangeResult.Applied
    }
}
