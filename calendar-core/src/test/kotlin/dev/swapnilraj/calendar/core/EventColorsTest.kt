package dev.swapnilraj.calendar.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class EventColorsTest {
    private val calendar = CalendarIdentity(CalendarId(2), CalendarAccount("synthetic", "local"), "owner", "provider")
    private val source = ColorReadRevision("source", 1)
    private val paletteContext = EventPaletteContext(calendar, source, ColorPaletteStamp("palette"))
    private val ref = EventRef(calendar.calendarId, 10, Instant.parse("2026-06-01T10:00:00Z"),
        Instant.parse("2026-06-01T11:00:00Z"), RecurrenceRef(100, Instant.parse("2026-05-01T10:00:00Z")))
    private val context = EventColorChoiceContext(calendar, EventColorTarget.ExistingOccurrence(ref), source,
        EventColorSourceToken("color-source"), "draft", 1)
    private fun text(value: String) = ColorCell.Text(value)
    private val observation = EventColorObservation(text("-1"), text("key"), text("4294967295"), text("0"))
    private fun entry(key: String = "key", raw: String = "4294967295", account: CalendarAccount = calendar.account,
        type: EventPaletteType = EventPaletteType.EVENT) = EventPaletteEntry(account, type, key, raw)
    private fun palette(entries: Collection<EventPaletteEntry> = listOf(entry()), ctx: EventPaletteContext = paletteContext) =
        (EventColorPalette.build(ctx, entries) as EventPaletteBuild.Ready).palette
    private fun editable(obs: EventColorObservation = observation, rights: EventColorRights = EventColorRights(),
        read: EventPaletteRead = EventPaletteRead.Ready(palette()), ctx: EventColorChoiceContext = context) =
        EditableEventColor(ctx, obs, rights, read)
    private fun resolve(current: EditableEventColor, choice: EventColorChoice, issued: EventColorChoiceContext = current.context) =
        EventColorPlans.resolve(current, EventColorIntentV1(issued, choice))
    private fun review(actual: EventColorPlanResult, problem: EventColorProblem) =
        assertEquals(EventColorPlanResult.Review(problem), actual)

    @Test fun signedAndUnsignedArgbAliasesShareBitsWithoutLosingRawObservation() {
        assertEquals(Argb32.fromProviderInteger("-1"), Argb32.fromProviderInteger("4294967295"))
        assertEquals(-1, Argb32.fromProviderInteger("4294967295")!!.bits)
        assertNotEquals(text("-1"), text("4294967295"))
        assertEquals(EventColorCurrent.Named("key", Argb32.fromProviderInteger("-1")!!),
            EventColorCurrentSelection.describe(editable()))
    }
    @Test fun argbAcceptsExact32BitBoundsZeroAndTransparentValues() {
        for (raw in listOf("-2147483648", "2147483647", "2147483648", "4294967295", "0", "255"))
            assertNotNull(Argb32.fromProviderInteger(raw))
        assertEquals(Int.MIN_VALUE, Argb32.fromProviderInteger("2147483648")!!.bits)
        assertEquals(0, Argb32.fromProviderInteger("0")!!.bits)
    }
    @Test fun outOfRangeAndNoncanonicalArgbNeverWrapsIntoSwatches() {
        for (raw in listOf("-2147483649", "4294967296", "9223372036854775807", "9223372036854775808",
            "-9223372036854775808", "+1", "01", "-0", " 1", "1.0", "0xffffffff", ""))
            assertNull(raw, Argb32.fromProviderInteger(raw))
    }
    @Test fun keepPreservesMissingNullEmptyAndUnknownRawWithoutPaletteOrRights() {
        for (cell in listOf(ColorCell.Missing, ColorCell.SqlNull, text(""), text("unparsed"), text("4294967296"))) {
            val obs = EventColorObservation(cell, cell, cell, cell)
            val current = editable(obs, read = EventPaletteRead.Unavailable)
            assertEquals(EventColorPlanResult.NoChange, resolve(current, EventColorChoice.Keep))
            assertSame(obs, current.observation)
            assertEquals(cell, current.observation.eventColor)
        }
    }
    @Test fun missingAndSqlNullAndEmptyAreNotCollapsed() {
        assertNotEquals(ColorCell.Missing, ColorCell.SqlNull)
        assertNotEquals(ColorCell.SqlNull, text(""))
        assertNotEquals(ColorCell.Missing, text(""))
    }
    @Test fun explicitDefaultIsExactEmptyEvenWithStaleRawOverrideAndAlreadyEmptyKey() {
        val current = editable(observation.copy(eventColorKey = text("")), EventColorRights(resetToDefault = AccessDecision.ALLOWED))
        val plan = resolve(current, EventColorChoice.CalendarDefault) as EventColorPlanResult.ExistingRowKeyUpdate
        assertEquals("", plan.key)
        assertEquals(context, plan.context)
        assertEquals(text("-1"), current.observation.eventColor)
    }
    @Test fun explicitResetCanReplacePresentUnknownRawUnderIssuedRight() {
        val obs = observation.copy(eventColor = text("unparsed-provider-color"), eventColorKey = text("unknown-key"))
        val current = editable(obs, EventColorRights(resetToDefault = AccessDecision.ALLOWED), EventPaletteRead.Unsupported)
        assertEquals("", (resolve(current, EventColorChoice.CalendarDefault) as EventColorPlanResult.ExistingRowKeyUpdate).key)
        assertSame(obs, current.observation)
    }
    @Test fun missingProjectionNeverBecomesExplicitResetAuthority() {
        for (obs in listOf(observation.copy(eventColor = ColorCell.Missing), observation.copy(eventColorKey = ColorCell.Missing),
            observation.copy(displayColor = ColorCell.Missing), observation.copy(calendarColor = ColorCell.Missing)))
            review(resolve(editable(obs, EventColorRights(resetToDefault = AccessDecision.ALLOWED)), EventColorChoice.CalendarDefault),
                EventColorProblem.MISSING_PROJECTION)
    }
    @Test fun defaultAndNamedRequireTheirOwnIssuedRightsAndDefaultUnknownNeverGrants() {
        val named = EventColorChoice.Named(palette().select("key")!!)
        for (right in listOf(AccessDecision.UNKNOWN, AccessDecision.DENIED)) {
            review(resolve(editable(rights = EventColorRights(right, right)), named), EventColorProblem.RIGHTS_UNPROVEN)
            review(resolve(editable(rights = EventColorRights(right, right)), EventColorChoice.CalendarDefault), EventColorProblem.RIGHTS_UNPROVEN)
        }
        review(resolve(editable(rights = EventColorRights(chooseKey = AccessDecision.ALLOWED)), EventColorChoice.CalendarDefault),
            EventColorProblem.RIGHTS_UNPROVEN)
        assertEquals(EventColorRights(), editable().rights)
    }
    @Test fun namedSelectionUsesExactOpaqueKeyAndNeverWritesArgb() {
        val palette = palette(listOf(entry("0"), entry("  ", "0")))
        val current = editable(rights = EventColorRights(chooseKey = AccessDecision.ALLOWED), read = EventPaletteRead.Ready(palette))
        for (key in listOf("0", "  ")) {
            val plan = resolve(current, EventColorChoice.Named(palette.select(key)!!)) as EventColorPlanResult.ExistingRowKeyUpdate
            assertEquals(key, plan.key)
        }
        assertNull(palette.select("key"))
        assertNull(palette.select(""))
    }
    @Test fun paletteRejectsDuplicateEqualOrConflictingKeys() {
        for (second in listOf(entry(), entry(raw = "0")))
            assertEquals(EventPaletteBuild.Review(EventColorProblem.DUPLICATE_KEY), EventColorPalette.build(paletteContext, listOf(entry(), second)))
    }
    @Test fun paletteRejectsForeignAccountNameTypeAndNonEventRows() {
        for (candidate in listOf(entry(account = calendar.account.copy(name = "other")), entry(account = calendar.account.copy(type = "other")),
            entry(type = EventPaletteType.CALENDAR), entry(type = EventPaletteType.UNKNOWN)))
            assertEquals(EventPaletteBuild.Review(EventColorProblem.FOREIGN_PALETTE), EventColorPalette.build(paletteContext, listOf(candidate)))
    }
    @Test fun emptyKeyAndInvalidArgbAreNotSelectablePaletteRows() {
        assertEquals(EventPaletteBuild.Review(EventColorProblem.EMPTY_KEY), EventColorPalette.build(paletteContext, listOf(entry(""))))
        assertEquals(EventPaletteBuild.Review(EventColorProblem.INVALID_ARGB), EventColorPalette.build(paletteContext, listOf(entry(raw = "4294967296"))))
    }
    @Test fun validEmptyPaletteIsDistinctFromUnavailableAndSupportsNoNamedChoices() {
        val empty = palette(emptyList())
        assertTrue(empty.entries.isEmpty()); assertNull(empty.select("key"))
        val current = editable(rights = EventColorRights(resetToDefault = AccessDecision.ALLOWED), read = EventPaletteRead.Ready(empty))
        assertTrue(resolve(current, EventColorChoice.CalendarDefault) is EventColorPlanResult.ExistingRowKeyUpdate)
        for (state in listOf(EventPaletteRead.Loading, EventPaletteRead.Unavailable, EventPaletteRead.PermissionRequired,
            EventPaletteRead.Unsupported, EventPaletteRead.LimitExceeded))
            review(resolve(current.copy(palette = state, rights = EventColorRights(chooseKey = AccessDecision.ALLOWED)),
                EventColorChoice.Named(palette().select("key")!!)), EventColorProblem.PALETTE_UNAVAILABLE)
    }
    @Test fun everyChangedSourceDraftAndCalendarContextRejectsEvenKeep() {
        val changedCalendars = listOf(calendar.copy(account = calendar.account.copy(name = "other")),
            calendar.copy(account = calendar.account.copy(type = "other")), calendar.copy(owner = "other"), calendar.copy(providerId = "other"))
        val changed = changedCalendars.map { context.copy(calendar = it) } + listOf(
            context.copy(source = source.copy(namespace = "other")), context.copy(source = source.copy(generation = 2)),
            context.copy(sourceToken = EventColorSourceToken("other")), context.copy(draftSession = "other"), context.copy(draftRevision = 2),
            context.copy(target = EventColorTarget.ExistingOccurrence(ref.copy(instanceStart = ref.instanceStart.plusSeconds(1)))),
            context.copy(target = EventColorTarget.ExistingOccurrence(ref.copy(instanceEnd = ref.instanceEnd.plusSeconds(1)))),
            context.copy(calendar = calendar.copy(calendarId = CalendarId(3)),
                target = EventColorTarget.ExistingOccurrence(ref.copy(calendarId = CalendarId(3)))),
            context.copy(target = EventColorTarget.ExistingOccurrence(ref.copy(eventId = 11))),
            context.copy(target = EventColorTarget.ExistingOccurrence(ref.copy(recurrence = ref.recurrence!!.copy(originalStart = ref.instanceStart)))),
            context.copy(target = EventColorTarget.ExistingOccurrence(ref.copy(recurrence = ref.recurrence!!.copy(masterEventId = 101)))),
            context.copy(target = EventColorTarget.ExistingMaster(SeriesIdentity(calendar.calendarId, 100))))
        for (issued in changed) review(resolve(editable(), EventColorChoice.Keep, issued), EventColorProblem.STALE_CONTEXT)
    }
    @Test fun contextCannotCopyInAForeignCalendarNumberOrInvalidRevision() {
        for (action in listOf<() -> Unit>(
            { context.copy(calendar = calendar.copy(calendarId = CalendarId(3))) },
            { context.copy(draftRevision = -1) }, { context.copy(draftSession = " ") },
            { context.copy(source = ColorReadRevision("", 1)) }
        )) try { action(); fail("Invalid context accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun removedOrRecoloredOrReissuedPaletteRequiresReview() {
        val old = palette().select("key")!!
        for (replacement in listOf(palette(emptyList()), palette(listOf(entry(raw = "0"))),
            palette(ctx = paletteContext.copy(stamp = ColorPaletteStamp("new"))))) {
            val current = editable(rights = EventColorRights(chooseKey = AccessDecision.ALLOWED), read = EventPaletteRead.Ready(replacement))
            review(resolve(current, EventColorChoice.Named(old)), EventColorProblem.STALE_PALETTE)
        }
    }
    @Test fun sameAccountPaletteCannotCrossFullCalendarOrReadContext() {
        for (replacement in listOf(palette(ctx = paletteContext.copy(calendar = calendar.copy(providerId = "other"))),
            palette(ctx = paletteContext.copy(calendar = calendar.copy(calendarId = CalendarId(3)))),
            palette(ctx = paletteContext.copy(source = source.copy(generation = 2))))) {
            val current = editable(rights = EventColorRights(chooseKey = AccessDecision.ALLOWED), read = EventPaletteRead.Ready(replacement))
            review(resolve(current, EventColorChoice.Named(replacement.select("key")!!)), EventColorProblem.STALE_PALETTE)
        }
    }
    @Test fun forgedSelectionColorAndForeignStampDoNotSubstituteForCurrentEntry() {
        val correct = palette().select("key")!!
        for (forged in listOf(correct.copy(expectedColor = Argb32.fromProviderInteger("0")!!),
            correct.copy(context = paletteContext.copy(stamp = ColorPaletteStamp("other")))))
            review(resolve(editable(rights = EventColorRights(chooseKey = AccessDecision.ALLOWED)), EventColorChoice.Named(forged)),
                EventColorProblem.STALE_PALETTE)
    }
    @Test fun currentDefaultRequiresNullRawOverrideAndRetainsUnknownPreview() {
        for (key in listOf(ColorCell.SqlNull, text(""))) {
            val obs = EventColorObservation(ColorCell.SqlNull, key, ColorCell.SqlNull, text("unknown"))
            assertEquals(EventColorCurrent.CalendarDefault(null), EventColorCurrentSelection.describe(editable(obs)))
        }
        assertTrue(EventColorCurrentSelection.describe(editable(observation.copy(eventColorKey = text("")))) is EventColorCurrent.RawOverride)
    }
    @Test fun unresolvedCurrentStateNeverRepairsUnknownOrInconsistentRaw() {
        for (obs in listOf(observation.copy(eventColorKey = text("absent-key")), observation.copy(eventColor = ColorCell.SqlNull),
            observation.copy(eventColor = text("0")), observation.copy(eventColorKey = ColorCell.Missing),
            observation.copy(displayColor = text("0")))) {
            val current = editable(obs)
            assertEquals(EventColorCurrent.Unresolved, EventColorCurrentSelection.describe(current))
            assertSame(obs, current.observation)
        }
    }
    @Test fun rawOverrideAndCalendarDefaultNeverInferDefaultFromZeroOrNamedKeyZero() {
        assertTrue(EventColorCurrentSelection.describe(editable(observation.copy(eventColor = text("0"), eventColorKey = text(""),
            displayColor = text("0")))) is EventColorCurrent.RawOverride)
        val palette = palette(listOf(entry("0", "0")))
        val current = editable(observation.copy(eventColor = text("0"), eventColorKey = text("0"), displayColor = text("0")),
            read = EventPaletteRead.Ready(palette))
        assertEquals(EventColorCurrent.Named("0", Argb32.fromProviderInteger("0")!!), EventColorCurrentSelection.describe(current))
    }
    @Test fun currentSelectionRejectsStalePaletteWithoutNormalizingObservation() {
        val obs = observation
        val current = editable(obs, read = EventPaletteRead.Ready(palette(ctx = paletteContext.copy(source = source.copy(generation = 2)))))
        assertEquals(EventColorCurrent.Unresolved, EventColorCurrentSelection.describe(current))
        assertSame(obs, current.observation)
    }
    @Test fun paletteOwnsItsCollectionAndExposesNoMutableOutput() {
        val rows = mutableListOf(entry())
        val palette = palette(rows)
        rows.clear()
        assertNotNull(palette.select("key"))
        try { (palette.entries as MutableList<EventPaletteEntry>).clear(); fail("Mutable palette") }
        catch (_: UnsupportedOperationException) { }
    }
    @Test fun paletteCountLimitRejectsBeforeAnyTraversal() {
        var reads = 0
        val huge = object : AbstractList<EventPaletteEntry>() {
            override val size = 257
            override fun get(index: Int): EventPaletteEntry { reads++; error("Must not traverse") }
        }
        assertEquals(EventPaletteBuild.Review(EventColorProblem.LIMIT_EXCEEDED), EventColorPalette.build(paletteContext, huge))
        assertEquals(0, reads)
    }
    @Test fun aggregateLimitChargesRepeatedAliasesBeforeCopyingOrDuplicateHashing() {
        var reads = 0
        val shared = entry("x".repeat(4096))
        val rows = object : AbstractList<EventPaletteEntry>() {
            override val size = 256
            override fun get(index: Int): EventPaletteEntry { reads++; return shared }
        }
        assertEquals(EventPaletteBuild.Review(EventColorProblem.LIMIT_EXCEEDED), EventColorPalette.build(paletteContext, rows))
        assertTrue(reads in 1..17)
    }
    @Test fun aggregateLimitAcceptsExactBoundaryAndRejectsOneAdditionalUnit() {
        // Fixture context costs40 units; each account costs14 and the colour "0" costs1.
        val rows = (1..15).map { entry(it.toString().padEnd(4096, 'x'), "0") } + entry("last".padEnd(3816, 'x'), "0")
        assertTrue(EventColorPalette.build(paletteContext, rows) is EventPaletteBuild.Ready)
        val larger = rows.dropLast(1) + rows.last().copy(key = rows.last().key + "x")
        assertEquals(EventPaletteBuild.Review(EventColorProblem.LIMIT_EXCEEDED), EventColorPalette.build(paletteContext, larger))
    }
    @Test fun exactEntryCountAndScalarBoundsHavePositiveControls() {
        assertEquals(256, palette((1..256).map { entry(it.toString()) }).entries.size)
        assertNotNull(palette(listOf(entry("x".repeat(4096)))).select("x".repeat(4096)))
        for (action in listOf<() -> Unit>({ text("x".repeat(4097)) }, { entry("x".repeat(4097)) },
            { ColorPaletteStamp("x".repeat(4097)) }, { context.copy(draftSession = "x".repeat(4097)) }))
            try { action(); fail("Oversized scalar accepted") } catch (_: IllegalArgumentException) { }
    }
}
