package dev.swapnilraj.calendar.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class OrdinaryCompoundEditsTest {
    private val account = CalendarAccount("owner@example.invalid", "com.google")
    private val calendar = CalendarInfo(CalendarId(3), "Fixture", 123, account, "owner@example.invalid", ZoneId.of("UTC"), 700, true, true, providerId = "provider:opaque")
    private val start = Instant.parse("2026-11-20T09:00:00.123Z")
    private val ref = EventRef(calendar.id, 7, start, start.plusSeconds(3600))
    private val details = EventDetails(EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Fixture", 123), calendar,
        description = "Opaque imported notes", eventZone = ZoneId.of("UTC"), canEdit = AccessDecision.ALLOWED, isOrganizer = true)
    private val token = CompoundEditSourceToken(OrdinaryCompoundEditV1.SOURCE_PREFIX + "opaque:not-a-uuid")
    private val revision = CompoundEditRevision("ordinary:namespace", 4)
    private val binding = CompoundEditDraftBinding("edit:legacy/opaque:7", "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", token)
    private val colors = EventColorObservation(ColorCell.Text("unknown raw override"), ColorCell.SqlNull, ColorCell.Text("123"), ColorCell.Text("123"))
    private val paletteRevision = ColorReadRevision("palette:namespace", 80)
    private fun palette(key: String = "a", color: String = "-16711936", stamp: String = "stamp:a", cal: CalendarIdentity = calendar.identity,
        rev: ColorReadRevision = paletteRevision): EventColorPalette = (EventColorPalette.build(EventPaletteContext(cal, rev, ColorPaletteStamp(stamp)),
            listOf(EventPaletteEntry(cal.account, EventPaletteType.EVENT, key, color))) as EventPaletteBuild.Ready).palette
    private fun source(d: EventDetails = details, fields: Set<EditableField> = EditableField.entries.toSet(), cells: EventColorObservation? = colors,
        rights: EventColorRights = EventColorRights(AccessDecision.ALLOWED, AccessDecision.ALLOWED),
        palette: CompoundPaletteObservation = CompoundPaletteObservation.Unavailable,
        rules: CompoundEditAuthoringRules = CompoundEditAuthoringRules.capture(setOf(0,1,2), AccessDecision.ALLOWED, 4, 0),
        sourceToken: CompoundEditSourceToken = token, sourceRevision: CompoundEditRevision = revision): CompoundEditableEvent =
        (CompoundEditableEvent.capture(d, sourceToken, sourceRevision, fields, cells, rights, palette, rules) as CompoundEditSourceCapture.Ready).source
    private fun intent(s: CompoundEditableEvent, patch: EventPatch = EventPatch(), color: EventColorChoice = EventColorChoice.Keep,
        b: CompoundEditDraftBinding = binding): CompoundEditIntentV1 = (CompoundEditIntentV1.capture(s, b, patch, color) as CompoundEditIntentCapture.Ready).intent
    private fun ready(result: CompoundEditPlanResult): CompoundEditPlanResult.Ready = result as CompoundEditPlanResult.Ready
    private fun problem(expected: CompoundEditProblem, result: CompoundEditPlanResult) = assertEquals(expected, (result as CompoundEditPlanResult.Review).problem)

    @Test fun noChangeRequiresExactCurrentAcknowledgedBinding() {
        val s = source(); val i = intent(s)
        assertEquals(CompoundEditPlanResult.NoChange, CompoundEditPlans.resolve(s, binding, i))
        problem(CompoundEditProblem.STALE_BINDING, CompoundEditPlans.resolve(s, binding.copy(rawRevision="00000000-0000-0000-0000-000000000003"), i))
        problem(CompoundEditProblem.STALE_BINDING, CompoundEditPlans.resolve(s, binding.copy(draftId="other:draft"), i))
    }
    @Test fun noChangeStillRejectsSourceRevisionOrNamespaceReplacement() {
        val old = source(); val i = intent(old)
        problem(CompoundEditProblem.STALE_SOURCE, CompoundEditPlans.resolve(source(sourceRevision=revision.copy(generation=5)), binding, i))
        problem(CompoundEditProblem.STALE_SOURCE, CompoundEditPlans.resolve(source(sourceRevision=revision.copy(namespace="other")), binding, i))
        val changed = details.copy(calendar=calendar.copy(providerId="replacement"))
        problem(CompoundEditProblem.STALE_SOURCE, CompoundEditPlans.resolve(source(d=changed), binding, i))
    }
    @Test fun changedTokenCannotBeRelabeledAsCurrentBinding() {
        val s = source(); val other = source(sourceToken=CompoundEditSourceToken(OrdinaryCompoundEditV1.SOURCE_PREFIX+"other"))
        problem(CompoundEditProblem.STALE_BINDING, CompoundEditPlans.resolve(other, binding, intent(s)))
        assertEquals(CompoundEditProblem.STALE_BINDING, (CompoundEditIntentV1.capture(other,binding) as CompoundEditIntentCapture.Review).problem)
    }
    @Test fun noChangeCanBeReadWithoutEditRightsButAuthoredChangeCannot() {
        val s = source(d=details.copy(canEdit=AccessDecision.UNKNOWN))
        assertEquals(CompoundEditPlanResult.NoChange, CompoundEditPlans.resolve(s,binding,intent(s)))
        problem(CompoundEditProblem.FIELD_NOT_ALLOWED, CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(title=FieldChange.Set("new")))))
    }
    @Test fun keepDoesNotEraseOtherSparseFieldsOrRequireAnyPaletteState() {
        val foreign = palette(cal=calendar.identity.copy(account=CalendarAccount("other","type")))
        val observations = listOf(CompoundPaletteObservation.Loading, CompoundPaletteObservation.PermissionRequired,
            CompoundPaletteObservation.Unavailable,CompoundPaletteObservation.LimitExceeded,CompoundPaletteObservation.Unsupported,
            CompoundPaletteObservation.Ready(paletteRevision,foreign),CompoundPaletteObservation.Ready(paletteRevision.copy(generation=2),palette()))
        val patch = EventPatch(title=FieldChange.Set("new"), description=FieldChange.Set("notes"))
        for (observation in observations) {
            val s=source(cells=null,rights=EventColorRights(),palette=observation)
            val result=ready(CompoundEditPlans.resolve(s,binding,intent(s,patch)))
            assertEquals(patch,result.ordinary);assertNull(result.colorKey)
        }
    }
    @Test fun resetIsExplicitEmptyKeyAndIgnoresForeignOrUnavailablePalette() {
        for(observation in listOf(CompoundPaletteObservation.Unavailable,CompoundPaletteObservation.Loading,
            CompoundPaletteObservation.Ready(paletteRevision,palette(cal=calendar.identity.copy(providerId="foreign"))))) {
            val s=source(palette=observation)
            val result=ready(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(title=FieldChange.Set("new")),EventColorChoice.CalendarDefault)))
            assertEquals("",result.colorKey);assertEquals(FieldChange.Set("new"),result.ordinary.title)
        }
    }
    @Test fun resetRequiresProjectionAndIndependentResetRight() {
        val missing=source(cells=colors.copy(eventColor=ColorCell.Missing))
        val denied=source(rights=EventColorRights(AccessDecision.ALLOWED,AccessDecision.UNKNOWN))
        val absent=source(cells=null)
        for(s in listOf(missing,denied,absent)) {
            val r=CompoundEditPlans.resolve(s,binding,intent(s,color=EventColorChoice.CalendarDefault))
            problem(CompoundEditProblem.COLOR_REVIEW,r)
        }
        val s=source(cells=colors.copy(eventColor=ColorCell.Text("not an integer")))
        assertEquals("",ready(CompoundEditPlans.resolve(s,binding,intent(s,color=EventColorChoice.CalendarDefault))).colorKey)
    }
    @Test fun namedChoiceUsesIndependentCurrentPaletteRevisionAndStamp() {
        val p=palette();val s=source(palette=CompoundPaletteObservation.Ready(paletteRevision,p))
        val i=intent(s,EventPatch(location=FieldChange.Set("place")),EventColorChoice.Named(p.select("a")!!))
        val r=ready(CompoundEditPlans.resolve(s,binding,i));assertEquals("a",r.colorKey);assertEquals(FieldChange.Set("place"),r.ordinary.location)
        assertNotEquals(revision.namespace,paletteRevision.namespace)
        val recolored=source(palette=CompoundPaletteObservation.Ready(paletteRevision,palette(color="123")))
        problem(CompoundEditProblem.COLOR_REVIEW,CompoundEditPlans.resolve(recolored,binding,i))
        val restamped=source(palette=CompoundPaletteObservation.Ready(paletteRevision,palette(stamp="new")))
        problem(CompoundEditProblem.COLOR_REVIEW,CompoundEditPlans.resolve(restamped,binding,i))
    }
    @Test fun namedRejectsForeignMissingAndInconsistentlyIssuedPaletteForEntirePlan() {
        val p=palette();val base=source(palette=CompoundPaletteObservation.Ready(paletteRevision,p))
        val i=intent(base,EventPatch(title=FieldChange.Set("must not escape")),EventColorChoice.Named(p.select("a")!!))
        val candidates=listOf(source(),source(palette=CompoundPaletteObservation.Ready(paletteRevision.copy(generation=99),p)),
            source(palette=CompoundPaletteObservation.Ready(paletteRevision,palette(cal=calendar.identity.copy(providerId="other")))))
        for(s in candidates) problem(CompoundEditProblem.COLOR_REVIEW,CompoundEditPlans.resolve(s,binding,i))
    }
    @Test fun namedDoesNotInferChooseRightFromResetOrEditableOrdinaryFields() {
        val p=palette();val s=source(rights=EventColorRights(AccessDecision.UNKNOWN,AccessDecision.ALLOWED),palette=CompoundPaletteObservation.Ready(paletteRevision,p))
        problem(CompoundEditProblem.COLOR_REVIEW,CompoundEditPlans.resolve(s,binding,intent(s,color=EventColorChoice.Named(p.select("a")!!))))
    }
    @Test fun opaqueZeroAndWhitespacePaletteKeysRemainExplicitNamedChoices() {
        for(key in listOf("0"," ","opaque:key/not-a-uuid")) {
            val p=palette(key);val s=source(palette=CompoundPaletteObservation.Ready(paletteRevision,p))
            assertEquals(key,ready(CompoundEditPlans.resolve(s,binding,intent(s,color=EventColorChoice.Named(p.select(key)!!)))).colorKey)
        }
    }
    @Test fun eachPartialOrUnknownColorCellIsPreservedUnderKeep() {
        for(cell in listOf(ColorCell.Missing,ColorCell.SqlNull,ColorCell.Text(""),ColorCell.Text("raw unknown"),ColorCell.Text("0"))) {
            val s=source(cells=EventColorObservation(cell,cell,cell,cell))
            val r=ready(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(title=FieldChange.Set("new")))))
            assertNull(r.colorKey);assertEquals(cell,s.colorObservation!!.eventColor)
        }
    }
    @Test fun sourceSnapshotsChildrenAndAllowedFieldsBeforeCallerMutation() {
        val guests=mutableListOf(EventAttendee("owner@example.invalid",null,0,2,0));val reminders=mutableListOf(EventReminder(10,1))
        val fields=mutableSetOf(EditableField.TITLE)
        val s=source(d=details.copy(attendees=guests,reminders=reminders),fields=fields)
        guests.clear();reminders.add(EventReminder(20,2));fields.clear()
        assertEquals(1,s.details.attendees.size);assertEquals(1,s.details.reminders.size);assertEquals(setOf(EditableField.TITLE),s.allowedFields)
        assertThrows(UnsupportedOperationException::class.java) {(s.details.attendees as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java) {(s.details.reminders as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java) {(s.allowedFields as MutableSet).clear()}
    }
    @Test fun commandAndResolvedPlanFreezeCallerAlertList() {
        val alerts=mutableListOf(10,20);val s=source();val i=intent(s,EventPatch(alertMinutes=FieldChange.Set(alerts)))
        alerts.clear();alerts.add(-1)
        val result=ready(CompoundEditPlans.resolve(s,binding,i))
        assertEquals(listOf(10,20),(result.ordinary.alertMinutes as FieldChange.Set).value)
        assertThrows(UnsupportedOperationException::class.java) {((i.ordinary.alertMinutes as FieldChange.Set).value as MutableList).clear()}
    }
    @Test fun authoringRulesSnapshotAvailabilityInput() {
        val allowed=mutableSetOf(0,1);val rules=CompoundEditAuthoringRules.capture(allowed)
        allowed.clear();assertEquals(setOf(0,1),rules.allowedAvailability)
        assertThrows(UnsupportedOperationException::class.java) {(rules.allowedAvailability as MutableSet).clear()}
    }
    @Test fun sourceRejectsWrongCalendarBoundsAndRecurrenceWithoutIssuingModel() {
        val wrong=listOf(details.copy(calendar=calendar.copy(id=CalendarId(4))),
            details.copy(occurrence=details.occurrence.copy(time=EventTime.Timed(start,start.plusSeconds(30)))),
            details.copy(recurrenceRule="FREQ=DAILY"))
        for(d in wrong) assertTrue(CompoundEditableEvent.capture(d,token,revision,emptySet()) is CompoundEditSourceCapture.Review)
    }
    @Test fun sourceYearAndPrecisionAreNotReauthoredForUnrelatedSparseEdit() {
        val point=Instant.parse("2026-11-20T09:00:00.123Z")
        val d=details.copy(occurrence=details.occurrence.copy(ref=ref.copy(instanceStart=point,instanceEnd=point),time=EventTime.Timed(point,point)),
            eventZone=null,description="x".repeat(70_000),privacy=Int.MAX_VALUE,availability=-9)
        val s=source(d=d,cells=null)
        val r=ready(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(title=FieldChange.Set("new")))))
        assertSame(FieldChange.Keep,r.ordinary.time);assertSame(FieldChange.Keep,r.ordinary.eventZone)
        assertEquals(point,s.details.occurrence.ref.instanceStart);assertEquals(70_000,s.details.description.length)
        assertNull(s.details.eventZone);assertEquals(Int.MAX_VALUE,s.details.privacy)
    }
    @Test fun sourceCellBudgetAdmitsLargeImportedNotesButAuthoredCellBudgetDoesNot() {
        val s=source(d=details.copy(description="x".repeat(262_144)))
        assertEquals(262_144,s.details.description.length)
        val excessive=CompoundEditableEvent.capture(details.copy(description="x".repeat(262_145)),token,revision,emptySet())
        assertEquals(CompoundEditProblem.LIMIT_EXCEEDED,(excessive as CompoundEditSourceCapture.Review).problem)
        val c=CompoundEditIntentV1.capture(s,binding,EventPatch(description=FieldChange.Set("x".repeat(65_537))))
        assertEquals(CompoundEditProblem.LIMIT_EXCEEDED,(c as CompoundEditIntentCapture.Review).problem)
        val exactly=intent(s,EventPatch(description=FieldChange.Set("x".repeat(65_536))))
        assertTrue(CompoundEditPlans.resolve(s,binding,exactly) is CompoundEditPlanResult.Ready)
    }
    @Test fun sourceAggregatePreflightRunsBeforeAnyChildCopy() {
        val children=object:AbstractList<EventAttendee>() {
            override val size=1
            override fun get(index:Int):EventAttendee=error("metadata budget must fail before child read")
        }
        val result=CompoundEditableEvent.capture(details.copy(description="x".repeat(4000),attendees=children),token,revision,emptySet(),limits=CompoundEditLimits(sourceUnits=100))
        assertEquals(CompoundEditProblem.LIMIT_EXCEEDED,(result as CompoundEditSourceCapture.Review).problem)
    }
    @Test fun lyingCollectionSizeCannotBypassSourceRowLimit() {
        var iterators=0
        val children=object:AbstractList<EventAttendee>() {
            override val size=0
            override fun get(index:Int)=EventAttendee("owner",null,0,2,0)
            override fun iterator():Iterator<EventAttendee> {iterators++;return List(3){get(it)}.iterator()}
        }
        val result=CompoundEditableEvent.capture(details.copy(attendees=children),token,revision,emptySet(),limits=CompoundEditLimits(attendees=2))
        assertEquals(CompoundEditProblem.LIMIT_EXCEEDED,(result as CompoundEditSourceCapture.Review).problem)
        assertEquals(1,iterators)
    }
    @Test fun intentPreflightBoundsAlertsAndAggregateBeforeCopy() {
        var iterators=0
        val alerts=object:AbstractList<Int>() {
            override val size=0
            override fun get(index:Int)=index
            override fun iterator():Iterator<Int>{iterators++;return listOf(1,2,3).iterator()}
        }
        val r=CompoundEditIntentV1.capture(source(),binding,EventPatch(alertMinutes=FieldChange.Set(alerts)),limits=CompoundEditLimits(alerts=2))
        assertEquals(CompoundEditProblem.LIMIT_EXCEEDED,(r as CompoundEditIntentCapture.Review).problem);assertEquals(1,iterators)
        val combined=CompoundEditIntentV1.capture(source(),binding,EventPatch(title=FieldChange.Set("x".repeat(400)),description=FieldChange.Set("y".repeat(400))),limits=CompoundEditLimits(intentUnits=700))
        assertEquals(CompoundEditProblem.LIMIT_EXCEEDED,(combined as CompoundEditIntentCapture.Review).problem)
    }
    @Test fun limitsCannotRaiseHardCeilingsOrBecomeNegative() {
        assertThrows(IllegalArgumentException::class.java) {CompoundEditLimits(sourceCellUnits=262_145)}
        assertThrows(IllegalArgumentException::class.java) {CompoundEditLimits(authoredCellUnits=65_537)}
        assertThrows(IllegalArgumentException::class.java) {CompoundEditLimits(alerts=4001)}
        assertThrows(IllegalArgumentException::class.java) {CompoundEditLimits(sourceUnits=Long.MAX_VALUE)}
    }
    @Test fun explicitNullableMetadataIsDistinctFromKeepAndRequiresAuthoredConstraints() {
        val s=source();val r=ready(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(privacy=FieldChange.Set(null),availability=FieldChange.Set(null)))))
        assertEquals(FieldChange.Set<Int?>(null),r.ordinary.privacy);assertEquals(FieldChange.Set<Int?>(null),r.ordinary.availability)
        val unknown=source(rules=CompoundEditAuthoringRules.capture())
        problem(CompoundEditProblem.INVALID_AVAILABILITY,CompoundEditPlans.resolve(unknown,binding,intent(unknown,EventPatch(availability=FieldChange.Set(null)))))
    }
    @Test fun changedFieldNeedsItsOwnIssuedOrdinaryRight() {
        val s=source(fields=setOf(EditableField.TITLE))
        problem(CompoundEditProblem.FIELD_NOT_ALLOWED,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(description=FieldChange.Set("new")))))
        assertTrue(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(title=FieldChange.Set("new")))) is CompoundEditPlanResult.Ready)
    }
    @Test fun importedPointCanBeKeptButNotNewlyAuthoredByTimeOrZone() {
        val d=details.copy(occurrence=details.occurrence.copy(ref=ref.copy(instanceEnd=start),time=EventTime.Timed(start,start)))
        val s=source(d=d)
        problem(CompoundEditProblem.INVALID_TIME,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(time=FieldChange.Set(EventTime.Timed(start,start))))))
        problem(CompoundEditProblem.INVALID_TIME,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(eventZone=FieldChange.Set(ZoneId.of("UTC"))))))
    }
    @Test fun authoredTimeRejectsSubMillisecondAndOutsideNativeYearBounds() {
        val s=source()
        for(begin in listOf(start.plusNanos(1),Instant.parse("0000-01-01T00:00:00Z"),Instant.parse("+10000-01-01T00:00:00Z"))) {
            problem(CompoundEditProblem.INVALID_TIME,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(time=FieldChange.Set(EventTime.Timed(begin,begin.plusSeconds(60)))))))
        }
        val valid=EventTime.Timed(start.plusSeconds(60),start.plusSeconds(120))
        assertTrue(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(time=FieldChange.Set(valid)))) is CompoundEditPlanResult.Ready)
    }
    @Test fun authoredTimeNeedsProviderCompatibleKnownZoneButUnrelatedEditDoesNot() {
        val s=source(d=details.copy(eventZone=null))
        problem(CompoundEditProblem.INVALID_ZONE,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(time=FieldChange.Set(details.occurrence.time)))))
        problem(CompoundEditProblem.INVALID_ZONE,CompoundEditPlans.resolve(source(),binding,intent(source(),EventPatch(eventZone=FieldChange.Set(ZoneOffset.ofHours(5))))))
        problem(CompoundEditProblem.INVALID_ZONE,CompoundEditPlans.resolve(source(),binding,intent(source(),EventPatch(eventZone=FieldChange.Set(null)))))
        assertTrue(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(location=FieldChange.Set("new")))) is CompoundEditPlanResult.Ready)
    }
    @Test fun allDayAuthoredBoundariesAndKindChangeRequireValidYearsAndZoneRight() {
        val date=LocalDate.of(2026,11,20);val time=EventTime.AllDay(CalendarDateRange(date,date.plusDays(2)))
        val s=source(fields=setOf(EditableField.TIME))
        problem(CompoundEditProblem.FIELD_NOT_ALLOWED,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(time=FieldChange.Set(time)))))
        val all=source();assertTrue(CompoundEditPlans.resolve(all,binding,intent(all,EventPatch(time=FieldChange.Set(time)))) is CompoundEditPlanResult.Ready)
        val invalid=EventTime.AllDay(CalendarDateRange(LocalDate.of(9999,12,31),LocalDate.of(10000,1,1)))
        problem(CompoundEditProblem.INVALID_TIME,CompoundEditPlans.resolve(all,binding,intent(all,EventPatch(time=FieldChange.Set(invalid)))))
    }
    @Test fun alertsNeedKnownMethodMaximumAndPreservedOtherMethodCount() {
        val unknown=source(rules=CompoundEditAuthoringRules.capture())
        problem(CompoundEditProblem.INVALID_ALERTS,CompoundEditPlans.resolve(unknown,binding,intent(unknown,EventPatch(alertMinutes=FieldChange.Set(listOf(10))))))
        assertTrue(CompoundEditPlans.resolve(unknown,binding,intent(unknown,EventPatch(alertMinutes=FieldChange.Set(emptyList())))) is CompoundEditPlanResult.Ready)
        val capacity=source(rules=CompoundEditAuthoringRules.capture(alertMethod=AccessDecision.ALLOWED,maxReminders=2,preservedNonAlertReminders=1))
        problem(CompoundEditProblem.INVALID_ALERTS,CompoundEditPlans.resolve(capacity,binding,intent(capacity,EventPatch(alertMinutes=FieldChange.Set(listOf(10,20))))))
        for(values in listOf(listOf(-1),listOf(10,10))) problem(CompoundEditProblem.INVALID_ALERTS,CompoundEditPlans.resolve(source(),binding,intent(source(),EventPatch(alertMinutes=FieldChange.Set(values)))))
    }
    @Test fun availabilityAndPrivacyValidationAffectOnlyExplicitSet() {
        val s=source(d=details.copy(availability=900,privacy=900))
        assertTrue(CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(title=FieldChange.Set("new")))) is CompoundEditPlanResult.Ready)
        problem(CompoundEditProblem.INVALID_AVAILABILITY,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(availability=FieldChange.Set(9)))))
        problem(CompoundEditProblem.INVALID_PRIVACY,CompoundEditPlans.resolve(s,binding,intent(s,EventPatch(privacy=FieldChange.Set(9)))))
    }
    @Test fun opaqueIdentifiersAreNotParsedAsUuidButNewDraftGenerationsAre() {
        assertEquals("edit:legacy/opaque:7",binding.draftId)
        assertEquals("ordinary:namespace",revision.namespace)
        assertThrows(IllegalArgumentException::class.java) {CompoundEditDraftBinding("edit:7","not-a-uuid",binding.rawRevision,token)}
        assertThrows(IllegalArgumentException::class.java) {CompoundEditSourceToken("old-edit-token")}
        assertThrows(IllegalArgumentException::class.java) {CompoundEditRevision("namespace",-1)}
    }
}
