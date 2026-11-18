package dev.swapnilraj.calendar

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument
import dev.swapnilraj.calendar.ui.CalendarDateIntents
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Actual application owners, exported/private activities, real reader/writer/local SQL and native
 * URI predicates. Only authorized synthetic one-off rows; no Google, OS task-stack or device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicCalendarEntryJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val title = "Synthetic incoming appointment"
    private val sourceTitle = "Synthetic linked appointment"
    private val start = Instant.parse("2028-01-03T09:00:00Z")
    private val none = CalendarSelection.Explicit(emptySet())

    @Test fun manifestResolvesTypedUntypedEventAndDateViewsAndMimeOnlyInsertButKeepsContinuationPrivate() = fixture { f ->
        val event = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 7)
        val date = Uri.parse("content://${CalendarContract.AUTHORITY}/time/${start.toEpochMilli()}")
        listOf(Intent(Intent.ACTION_VIEW, event), Intent(Intent.ACTION_VIEW).setDataAndType(event, "vnd.android.cursor.item/event"),
            Intent(Intent.ACTION_EDIT, event), Intent(Intent.ACTION_EDIT).setDataAndType(event, "vnd.android.cursor.item/event"),
            Intent(Intent.ACTION_VIEW, date), Intent(Intent.ACTION_VIEW).setDataAndType(date, "vnd.android.cursor.item/event"),
            Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/event")).forEach { intent ->
            val matches = f.app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("Manifest must resolve $intent", matches.any { it.activityInfo.name == CalendarEntryActivity::class.java.name })
        }
        assertFalse(f.app.packageManager.getActivityInfo(ComponentName(f.app, CalendarSeedReviewActivity::class.java), 0).exported)
        assertFalse(f.app.packageManager.getActivityInfo(ComponentName(f.app, CalendarEditReviewActivity::class.java), 0).exported)
        assertNoWrite(f)
    }

    @Test fun publicViewUsesExactNativeDetailsIndependentlyOfFiltersAndRefusesReusedNamespaceAfterReadRecovery() = fixture(seed = true) { f ->
        val entry = launch(f, Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 7)))
        shown(sourceTitle); shown("Synthetic room")
        assertNone(f); assertNoWrite(f)
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR)
        compose.runOnUiThread { entry.pause().stop() }
        compose.onAllNodesWithText(sourceTitle).assertCountEquals(0)
        compose.runOnUiThread { entry.restart().start().resume() }
        shown(f.app.getString(R.string.events_permission_required))
        f.provider.setCalendar("_sync_id", "replacement-B")
        shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        compose.runOnUiThread { entry.pause().stop().restart().start().resume() }
        shown(f.app.getString(R.string.calendar_entry_stale))
        compose.runOnUiThread { entry.recreate() }
        shown(f.app.getString(R.string.calendar_entry_stale))
        compose.onAllNodesWithText(sourceTitle).assertCountEquals(0)
        assertNone(f); assertNoWrite(f)
    }

    @Test fun publicDateRequiresGestureThenMainConsumesDisplayZoneDateOnceWithoutChangingFilters() = fixture(zone = ZoneId.of("Pacific/Kiritimati")) { f ->
        val instant = Instant.parse("2028-01-03T23:00:00Z")
        val date = instant.atZone(ZoneId.of("Pacific/Kiritimati")).toLocalDate()
        val entry = launch(f, Intent(Intent.ACTION_VIEW, Uri.parse("content://${CalendarContract.AUTHORITY}/time/${instant.toEpochMilli()}")))
        shown("Open calendar date"); compose.onNodeWithText(date.toString()).assertExists()
        assertNull(shadowOf(entry.get()).nextStartedActivity)
        compose.onNodeWithText("Review details").performClick()
        val intent = nextIntent(entry)
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        val navigation = requireNotNull(CalendarDateIntents.decode(f.app, intent))
        assertEquals(instant, navigation.instant)
        stop(entry)
        val main = launchMain(f, intent)
        try { shown("January 2028") } catch (failure: Throwable) {
            val toolbar = compose.onNodeWithTag("calendar_phone_month").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
            throw AssertionError("Date hint decoded, toolbar=$toolbar", failure)
        }
        compose.onNodeWithTag("calendar_phone_month").performClick()
        shownTag("calendar_month_navigator"); monthDay(date).assertIsSelected()
        monthDay(date.plusDays(1)).performClick()
        compose.runOnUiThread { main.recreate() }
        shownTag("calendar_phone_month"); compose.onNodeWithTag("calendar_phone_month").performClick()
        monthDay(date.plusDays(1)).assertIsSelected()
        assertNone(f); assertNoWrite(f)
    }

    @Test fun mimeInsertArrivalCancelAndWriteGrantNeverSubmitButFreshSaveCommitsAndOldContinuationCannotReseed() = fixture(write = false) { f ->
        val public = launch(f, insert())
        shown("Review new event"); assertNoWrite(f)
        compose.onNodeWithText("Review details").performClick()
        val privateIntent = nextIntent(public); stop(public)
        var editor = launchSeed(f, privateIntent)
        shown("New event"); assertNoWrite(f)
        val native = records(f).single { it.id.startsWith("incoming:") }
        val receipt = records(f).single { it.id.startsWith("incoming-review:") }
        assertTrue(isCalendarSeedNativeHandoffVerified(native, receipt))
        compose.onNodeWithContentDescription("Close event editor").performClick()
        awaitFinishing(editor, f); assertNoWrite(f); stop(editor)
        editor = launchSeed(f, privateIntent)
        shown("New event")
        chooseCalendar()
        save()
        shown(f.app.getString(R.string.phone_event_permission))
        assertArrayEquals(arrayOf(Manifest.permission.WRITE_CALENDAR), shadowOf(editor.get()).lastRequestedPermission.requestedPermissions)
        assertNoWrite(f)
        assertFalse(OrdinaryDraftDocument.decode(records(f).single { it.id.startsWith("incoming:") }).submitted)
        shadowOf(f.app).grantPermissions(Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { editor.pause().stop().restart().start().resume() }
        shown("Save"); compose.waitForIdle(); assertNoWrite(f)
        save()
        awaitFinishing(editor, f)
        assertSaved(f); stop(editor)
        launchSeed(f, privateIntent)
        shown(f.app.getString(R.string.seed_review_stale))
        assertEquals(1, f.provider.batchCount); assertEquals(1, f.provider.count("events"))
        assertNone(f)
    }

    @Test fun actualDraftBrowserReopensRawCompletionAndVerifiedNativeOnlyWhileMissingOrCorruptReceiptFailsClosed() = fixture { f ->
        val public = launch(f, insert(complete = false))
        shown("Review new event"); compose.onNodeWithText("Review details").performClick()
        val privateIntent = nextIntent(public); stop(public)
        val review = launchSeed(f, privateIntent)
        shownTag("seed_start_date")
        compose.onNodeWithTag("seed_start_date").assertTextEquals("", f.app.getString(R.string.seed_review_start_date))
        compose.onNodeWithTag("seed_start_time").assertTextEquals("", f.app.getString(R.string.seed_review_start_time))
        compose.onNodeWithText("Close").performScrollTo().performClick(); awaitFinishing(review, f); stop(review)
        val main = launchMain(f)
        openDrafts(); shown(title)
        compose.onNodeWithText("Open draft").performClick()
        val browserIntent = nextIntent(main); stop(main)
        val completed = launchSeed(f, browserIntent)
        shownTag("seed_start_date")
        listOf("seed_start_date" to "2028-01-03", "seed_end_date" to "2028-01-03", "seed_start_time" to "09:00", "seed_end_time" to "10:00", "seed_zone" to "UTC").forEach { (tag, value) ->
            compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                records(f).singleOrNull { it.id.startsWith("incoming-review:") }?.payload?.contains(value) == true
            }
        }
        compose.onNodeWithTag("seed_continue").performScrollTo().performClick()
        shown("New event"); assertNoWrite(f)
        val native = records(f).single { it.id.startsWith("incoming:") }
        val receipt = records(f).single { it.id.startsWith("incoming-review:") }
        assertTrue(isCalendarSeedNativeHandoffVerified(native, receipt))
        compose.onNodeWithContentDescription("Close event editor").performClick(); awaitFinishing(completed, f); stop(completed)
        val browser = launchMain(f)
        openDrafts(); shown("Open draft")
        compose.onNodeWithText("Open draft").performClick(); shown("New event")
        compose.onNodeWithContentDescription("Close event editor").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("New event").fetchSemanticsNodes().isEmpty() }
        shownTag("calendar_phone_month")
        val beforeCorruption = runBlocking { f.app.calendarLocalStore.load(native.id) }!!
        assertEquals(DraftChangeResult.Applied, runBlocking { f.app.calendarLocalStore.compareAndSet(receipt.id, receipt, receipt.copy(payload = "{")) })
        openDrafts(); shown(f.app.getString(R.string.events_drafts_unreadable))
        compose.onAllNodesWithText("Open draft").assertCountEquals(0)
        closeBrowser()
        val corrupt = runBlocking { f.app.calendarLocalStore.load(receipt.id) }!!
        assertEquals(DraftChangeResult.Applied, runBlocking { f.app.calendarLocalStore.compareAndSet(receipt.id, corrupt, null) })
        openDrafts(); shown(f.app.getString(R.string.events_drafts_unreadable))
        compose.onAllNodesWithText("Open draft").assertCountEquals(0)
        assertEquals(beforeCorruption.payload, runBlocking { f.app.calendarLocalStore.load(native.id) }!!.payload)
        assertNoWrite(f); assertNone(f)
        closeBrowser(); stop(browser)
        launchSeed(f, privateIntent)
        shown(f.app.getString(R.string.seed_review_unavailable))
        compose.onAllNodesWithText("Save").assertCountEquals(0)
        assertEquals(beforeCorruption.payload, runBlocking { f.app.calendarLocalStore.load(native.id) }!!.payload)
        assertNoWrite(f)
    }

    @Test fun fixedDarkSettingAlsoAppliesToActualPrivateIncomingReviewOnLightDevice() = fixture { f ->
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO,
            f.app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        runBlocking { f.app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false,
            theme = SettingsTheme.DARK, displayZone = DisplayZoneOption.Fixed(ZoneId.of("UTC")))) }
        val public = launch(f, insert(complete = false))
        shown("Review new event"); compose.onNodeWithText("Review details").performClick()
        val privateIntent = nextIntent(public); stop(public)
        launchSeed(f, privateIntent); shownTag("seed_start_date"); compose.waitForIdle()
        val pixels = compose.onNodeWithTag("calendar_seed_review").captureToImage().asAndroidBitmap()
        val background = pixels.getPixel(pixels.width - 2, 2)
        assertEquals("Theme assertion must sample an opaque rendered surface", 255, android.graphics.Color.alpha(background))
        assertTrue("Private review must use fixed app DARK rather than the light OS",
            android.graphics.Color.red(background) < 80 && android.graphics.Color.green(background) < 80 && android.graphics.Color.blue(background) < 80)
        assertNoWrite(f)
    }

    @Test fun committedCreateSurvivesReadLossAndRecreationWithFrozenRecoveryAndNoSecondBatch() = fixture { f ->
        val public = launch(f, insert())
        shown("Review new event"); compose.onNodeWithText("Review details").performClick()
        val privateIntent = nextIntent(public); stop(public)
        val editor = launchSeed(f, privateIntent)
        shown("New event")
        chooseCalendar()
        f.provider.failReadsAfterCommit = true
        save()
        shown(f.app.getString(R.string.phone_event_uncertain))
        val frozen = records(f).single { it.id.startsWith("incoming:") }
        assertTrue(OrdinaryDraftDocument.decode(frozen).submitted)
        assertEquals(1, f.provider.batchCount)
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        compose.runOnUiThread { editor.recreate() }
        shown(f.app.getString(R.string.phone_event_check_save))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(frozen.payload, runBlocking { f.app.calendarLocalStore.load(frozen.id) }!!.payload)
        checkResult(f)
        compose.waitForIdle(); assertEquals(1, f.provider.batchCount)
        f.provider.failReadsAfterCommit = false
        shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        compose.runOnUiThread { editor.pause().stop().restart().start().resume() }
        shown(f.app.getString(R.string.phone_event_check_save))
        assertEquals(1, f.provider.batchCount)
        checkResult(f)
        awaitFinishing(editor, f); assertSaved(f)
        assertNull(shadowOf(editor.get()).lastRequestedPermission)
    }

    private fun insert(complete: Boolean = true) = Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.dir/event")
        .putExtra(CalendarContract.Events.TITLE, title).putExtra(CalendarContract.Events.EVENT_LOCATION, "Synthetic arriving room")
        .putExtra(CalendarContract.Events.DESCRIPTION, "Synthetic arriving notes").apply { if (complete) {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli()); putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plusSeconds(3600).toEpochMilli())
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } }
    private fun chooseCalendar() {
        val choose = hasText("Choose a calendar") and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) { compose.onAllNodes(choose).fetchSemanticsNodes().size == 1 }
        compose.onNode(choose).performScrollTo().performClick()
        val calendar = hasText("Synthetic calendar") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.waitUntil(15_000) { compose.onAllNodes(calendar and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNode(calendar).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(calendar).fetchSemanticsNodes().isEmpty() }
    }
    private fun save() {
        val action = hasText("Save") and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) { compose.onAllNodes(action).fetchSemanticsNodes().size == 1 }
        compose.onNode(action).performClick() // Fixed phone toolbar, outside the scrolling form.
    }
    private fun checkResult(f: Fixture) {
        val action = hasText(f.app.getString(R.string.phone_event_check_save)) and hasClickAction() and isEnabled()
        compose.waitUntil(15_000) { compose.onAllNodes(action).fetchSemanticsNodes().size == 1 }
        compose.onNode(action).performScrollTo().performClick()
    }
    private fun shown(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun shownTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun monthDay(date: LocalDate) = compose.onNodeWithTag("calendar_month_date_${YearMonth.from(date)}_$date")
    private fun records(f: Fixture) = runBlocking { f.app.calendarLocalStore.list() }
    private fun openDrafts() {
        shownTag("calendar_phone_month"); compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Drafts").performClick(); shown("Drafts")
    }
    private fun closeBrowser() {
        val root = isRoot() and hasAnyDescendant(hasText("Refresh drafts"))
        // A real upward sheet gesture exposes its footer; the underlying closed drawer still has
        // another composed Close, so select only the browser's own visible button.
        compose.onNode(root).performTouchInput { swipeUp(startY = height - 48f, endY = 48f) }
        compose.waitForIdle()
        compose.onNode(hasText("Close") and hasAnyAncestor(hasAnyDescendant(hasText("Refresh drafts")))).assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Refresh drafts").fetchSemanticsNodes().isEmpty() }
    }
    private fun assertNoWrite(f: Fixture) {
        assertEquals(0, f.provider.batchCount)
        f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    private fun assertNone(f: Fixture) = assertEquals(none, runBlocking { f.app.settings.calendarSelection.first() })
    private fun assertSaved(f: Fixture) {
        assertEquals(1, f.provider.batchCount); assertEquals(1, f.provider.count("events"))
        assertEquals(title, f.provider.value("title")); assertEquals("Synthetic arriving room", f.provider.value("eventLocation"))
        assertEquals(start.toEpochMilli().toString(), f.provider.value("dtstart")); assertEquals(start.plusSeconds(3600).toEpochMilli().toString(), f.provider.value("dtend"))
        assertTrue(records(f).none { it.id.startsWith("incoming:") })
    }
    private fun <A: ComponentActivity> awaitFinishing(activity: ActivityController<A>, f: Fixture) {
        try { compose.waitUntil(15_000) {
            compose.waitForIdle(); compose.onAllNodesWithText("New event").fetchSemanticsNodes(); activity.get().isFinishing
        } } catch (failure: Throwable) {
            val phases = f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT phase FROM mutation_journal", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val kinds = records(f).map { record -> if (record.id.startsWith("incoming:")) "native:" + OrdinaryDraftDocument.decode(record).submitted else "receipt" }
            val errors = listOf(R.string.phone_event_permission, R.string.phone_event_uncertain, R.string.events_drafts_unreadable,
                R.string.seed_review_permission, R.string.seed_review_unavailable, R.string.seed_review_stale).filter {
                compose.onAllNodesWithText(f.app.getString(it)).fetchSemanticsNodes().isNotEmpty()
            }.map { f.app.resources.getResourceEntryName(it) }
            throw AssertionError("Activity did not finish; phases=$phases, draftKinds=$kinds, errors=$errors", failure)
        }
    }
    private fun <A: ComponentActivity> nextIntent(activity: ActivityController<A>): Intent {
        var next: Intent? = null
        compose.waitUntil(15_000) { compose.waitForIdle(); if (next == null) next = shadowOf(activity.get()).nextStartedActivity; next != null }
        return requireNotNull(next)
    }
    private fun launch(f: Fixture, intent: Intent) = Robolectric.buildActivity(CalendarEntryActivity::class.java, intent).also { controller ->
        compose.runOnUiThread { controller.setup().visible() }; f.activities += controller
    }
    private fun launchSeed(f: Fixture, intent: Intent) = Robolectric.buildActivity(CalendarSeedReviewActivity::class.java, intent).also { controller ->
        compose.runOnUiThread { controller.setup().visible() }; f.activities += controller
    }
    private fun launchMain(f: Fixture, intent: Intent = Intent(f.app, MainActivity::class.java)) = Robolectric.buildActivity(MainActivity::class.java, intent).also { controller ->
        compose.runOnUiThread { controller.setup().visible() }; f.activities += controller
    }
    private fun stop(activity: ActivityController<out ComponentActivity>) { compose.runOnUiThread { activity.pause().stop().destroy() } }
    private class Fixture(val app: CalendarApp, val provider: NativeRecurringCreateJourneyProvider) {
        val activities = mutableListOf<ActivityController<out ComponentActivity>>()
    }
    private fun fixture(seed: Boolean = false, write: Boolean = true, zone: ZoneId = ZoneId.of("UTC"), block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        if (write) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CALENDAR) else shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val provider = NativeRecurringCreateJourneyProvider()
        if (seed) provider.seedOneOff(start, start.plusSeconds(3600), sourceTitle)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings()); app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, displayZone = DisplayZoneOption.Fixed(zone)))
            val metadata = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setDefaultCalendar(null, metadata.value))
            assertTrue(app.settings.setCalendarSelection(none, metadata.value))
        }
        val f = Fixture(app, provider)
        try { block(f) } finally {
            f.activities.asReversed().forEach { controller -> if (!controller.get().isDestroyed) stop(controller) }
            provider.db.close()
        }
    }
}
