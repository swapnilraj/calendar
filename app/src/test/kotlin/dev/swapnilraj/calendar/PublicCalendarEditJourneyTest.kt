package dev.swapnilraj.calendar

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.SavedReminderSettings
import dev.swapnilraj.calendar.ui.EventWriteRequest
import dev.swapnilraj.calendar.ui.EventWriteWorkflow
import dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument
import dev.swapnilraj.calendar.ui.settings.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** Real application composition, source reader/writer, private SQL and actual activity hosts.
 * Dedicated synthetic rows only: this does not certify Android task stacks or Google delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w393dp-h851dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicCalendarEditJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val sourceTitle = "Synthetic external edit source"
    private val proposedTitle = "Synthetic external edit suggestion"
    private val start = Instant.parse("2028-01-03T09:00:00Z")
    private val none = CalendarSelection.Explicit(emptySet())

    @Test fun manifestAcceptsTypedAndUntypedEditAndKeepsReviewActivityPrivate() = fixture { f ->
        val event = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 7)
        listOf(Intent(Intent.ACTION_EDIT, event), Intent(Intent.ACTION_EDIT).setDataAndType(event, "vnd.android.cursor.item/event")).forEach { intent ->
            assertTrue(f.app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == CalendarEntryActivity::class.java.name && it.activityInfo.exported })
        }
        assertFalse(f.app.packageManager.getActivityInfo(ComponentName(f.app, CalendarEditReviewActivity::class.java), 0).exported)
        assertNoWrite(f)
    }

    @Test fun externalArrivalAndBothCloseGesturesNeverCreateNativeDraftOrWriteProvider() = fixture { f ->
        val first = entry(f)
        shown(f.app.getString(R.string.calendar_entry_edit_review)); shown(sourceTitle)
        assertNull(shadowOf(first.get()).nextStartedActivity)
        assertTrue(records(f).isEmpty()); assertNoWrite(f)
        compose.onNodeWithText(f.app.getString(R.string.widget_setup_cancel)).performClick()
        assertTrue(first.get().isFinishing); stop(first)
        val second = entry(f)
        val privateIntent = review(f, second)
        val review = editHost(f, privateIntent)
        shown(f.app.getString(R.string.incoming_edit_review_apply))
        assertEquals(1, records(f).size); assertTrue(records(f).single().id.startsWith("incoming-edit-review:"))
        assertNoWrite(f)
        compose.onNodeWithText(f.app.getString(R.string.incoming_edit_review_close)).performScrollTo().performClick()
        assertTrue(review.get().isFinishing)
        assertTrue(records(f).none { it.id.startsWith("incoming-edit:") }); assertNoWrite(f); assertFilters(f)
    }

    @Test fun explicitReviewApplySaveUsesOriginalAuthorityAndUpdatesOnlySuggestedTitleOnce() = fixture { f ->
        val before = f.provider.snapshot("events").single()
        val children = f.provider.snapshot("reminders")
        val public = entry(f); val privateIntent = review(f, public)
        val editor = editHost(f, privateIntent)
        apply(f)
        val native = records(f).single { it.id.startsWith("incoming-edit:") }
        val doc = OrdinaryDraftDocument.decode(native)
        val receipt = records(f).single { it.id.startsWith("incoming-edit-review:") }
        assertTrue(isCalendarEditHandoffReceipt(receipt)); assertFalse(receipt.payload.contains(sourceTitle))
        assertTrue(doc.sourceBaseline.contains(sourceTitle)); assertFalse(doc.sourceBaseline.contains(proposedTitle))
        assertEquals(proposedTitle, doc.fields.title); assertFalse(doc.hasRequest)
        assertEquals(7L, doc.launch.ref!!.eventId); assertEquals(start, doc.launch.ref!!.instanceStart)
        assertNoWrite(f)
        save(); finished(editor)
        assertEquals(1, f.provider.batchCount); assertEquals(1, f.provider.count("events"))
        val after = f.provider.snapshot("events").single()
        assertEquals(proposedTitle, after["title"])
        assertEquals(before.filterKeys { it != "title" }, after.filterKeys { it != "title" })
        assertEquals(children, f.provider.snapshot("reminders")); assertTrue(f.provider.snapshot("attendees").isEmpty())
        assertNull(runBlocking { f.app.calendarLocalStore.load(native.id) })
        assertEquals(receipt, runBlocking { f.app.calendarLocalStore.load(receipt.id) })
        stop(editor)
        editHost(f, privateIntent); shown(f.app.getString(R.string.incoming_edit_review_consumed))
        assertEquals(1, f.provider.batchCount); assertFilters(f)
    }

    @Test fun actualBrowserAlwaysReopensPrivateEditHostAndRetainsMissingOrCorruptReceiptAsUnreadable() = fixture { f ->
        val public = entry(f); val privateIntent = review(f, public)
        val review = editHost(f, privateIntent)
        shown(f.app.getString(R.string.incoming_edit_review_apply))
        compose.onNodeWithText(f.app.getString(R.string.incoming_edit_review_close)).performScrollTo().performClick(); finished(review); stop(review)
        val firstMain = main(f)
        openDrafts(); shown(proposedTitle)
        compose.onNodeWithText(f.app.getString(R.string.events_drafts_open)).performClick()
        val rawIntent = nextIntent(firstMain)
        assertEquals(CalendarEditReviewActivity::class.java.name, rawIntent.component!!.className)
        stop(firstMain)
        val editor = editHost(f, rawIntent); apply(f)
        val native = records(f).single { it.id.startsWith("incoming-edit:") }
        val receipt = records(f).single { it.id.startsWith("incoming-edit-review:") }
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_close)).performClick(); finished(editor); stop(editor)
        val secondMain = main(f)
        openDrafts(); shown(proposedTitle)
        compose.onNodeWithText(f.app.getString(R.string.events_drafts_open)).performClick()
        val nativeIntent = nextIntent(secondMain)
        assertEquals(CalendarEditReviewActivity::class.java.name, nativeIntent.component!!.className)
        // A generic editor must never appear within MainActivity for this draft prefix.
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).assertDoesNotExist()
        stop(secondMain)
        val reopened = editHost(f, nativeIntent); shown(f.app.getString(R.string.events_editor_edit))
        assertEquals(OrdinaryDraftDocument.decode(native).generation, OrdinaryDraftDocument.decode(records(f).single { it.id == native.id }).generation)
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_close)).performClick(); finished(reopened); stop(reopened)
        val current = requireNotNull(runBlocking { f.app.calendarLocalStore.load(native.id) })
        val broken = receipt.copy(payload = "{")
        assertEquals(DraftChangeResult.Applied, runBlocking { f.app.calendarLocalStore.compareAndSet(receipt.id, receipt, broken) })
        val thirdMain = main(f)
        openDrafts(); shown(f.app.getString(R.string.events_drafts_unreadable))
        compose.onAllNodesWithText(f.app.getString(R.string.events_drafts_open)).assertCountEquals(0)
        assertEquals(current, runBlocking { f.app.calendarLocalStore.load(native.id) })
        assertEquals(broken, runBlocking { f.app.calendarLocalStore.load(receipt.id) })
        stop(thirdMain)
        assertEquals(DraftChangeResult.Applied, runBlocking { f.app.calendarLocalStore.compareAndSet(receipt.id, broken, null) })
        main(f); openDrafts(); shown(f.app.getString(R.string.events_drafts_unreadable))
        compose.onAllNodesWithText(f.app.getString(R.string.events_drafts_open)).assertCountEquals(0)
        assertEquals(current, runBlocking { f.app.calendarLocalStore.load(native.id) })
        assertNoWrite(f); assertFilters(f)
    }

    @Test fun genericWorkflowCannotMountEvenAValidIncomingEditNativeDraft() = fixture { f ->
        val public = entry(f); val privateIntent = review(f, public)
        val editor = editHost(f, privateIntent); apply(f)
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_close)).performClick(); finished(editor); stop(editor)
        val native = records(f).single { it.id.startsWith("incoming-edit:") }
        val launch = OrdinaryDraftDocument.decode(native).launch
        val host = Robolectric.buildActivity(ComponentActivity::class.java)
        f.activities += host
        compose.runOnUiThread {
            host.setup().visible()
            host.get().setContent {
                EventWriteWorkflow(EventWriteRequest.fromLaunch(launch, start.atOffset(ZoneOffset.UTC).toLocalDate(), ZoneOffset.UTC),
                    f.app.calendarWriter, f.app.calendarLocalStore, emptyList(), true, {}, {}, {}, retirement = f.app.operationRetirement)
            }
        }
        shown(f.app.getString(R.string.events_drafts_unreadable))
        compose.onNodeWithContentDescription(f.app.getString(R.string.phone_event_title)).assertDoesNotExist()
        compose.onAllNodesWithText("Save").assertCountEquals(0)
        assertEquals(native, runBlocking { f.app.calendarLocalStore.load(native.id) }); assertNoWrite(f)
    }

    @Test fun committedEditRecreatesWithoutReadAccessAndRecoversWithoutRevalidatingExternalReviewOrAnotherBatch() = fixture { f ->
        val public = entry(f); val privateIntent = review(f, public)
        val editor = editHost(f, privateIntent); apply(f)
        f.provider.failReadsAfterCommit = true
        save(); shown(f.app.getString(R.string.phone_event_uncertain))
        val frozen = records(f).single { it.id.startsWith("incoming-edit:") }
        val doc = OrdinaryDraftDocument.decode(frozen)
        assertTrue(doc.hasRequest); assertEquals(1, f.provider.batchCount)
        shadowOf(f.app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        compose.waitForIdle(); val queries = f.provider.queries.size
        compose.runOnUiThread { editor.recreate() }
        shown(f.app.getString(R.string.phone_event_check_save))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(queries, f.provider.queries.size)
        assertEquals(frozen, runBlocking { f.app.calendarLocalStore.load(frozen.id) })
        assertEquals(1, f.provider.batchCount)
        f.provider.failReadsAfterCommit = false
        shadowOf(f.app).grantPermissions(Manifest.permission.READ_CALENDAR)
        compose.runOnUiThread { editor.pause().stop().restart().start().resume() }
        shown(f.app.getString(R.string.phone_event_check_save))
        val recover = hasText(f.app.getString(R.string.phone_event_check_save)) and hasClickAction() and isEnabled()
        compose.onNode(recover).performScrollTo().performClick(); finished(editor)
        assertEquals(1, f.provider.batchCount); assertEquals(proposedTitle, f.provider.value("title"))
        assertNull(runBlocking { f.app.calendarLocalStore.load(frozen.id) })
        assertTrue(isCalendarEditHandoffReceipt(records(f).single()))
        stop(editor); editHost(f, privateIntent); shown(f.app.getString(R.string.incoming_edit_review_consumed))
        assertEquals(1, f.provider.batchCount); assertFilters(f)
    }

    private fun intent() = Intent(Intent.ACTION_EDIT, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 7))
        .putExtra(CalendarContract.Events.TITLE, proposedTitle)
    private fun entry(f: Fixture) = host(f, CalendarEntryActivity::class.java, intent())
    private fun editHost(f: Fixture, intent: Intent) = host(f, CalendarEditReviewActivity::class.java, intent)
    private fun main(f: Fixture) = host(f, MainActivity::class.java, Intent(f.app, MainActivity::class.java))
    private fun review(f: Fixture, entry: ActivityController<CalendarEntryActivity>): Intent {
        shown(f.app.getString(R.string.calendar_entry_continue))
        compose.onNodeWithText(f.app.getString(R.string.calendar_entry_continue)).performClick()
        return nextIntent(entry).also { assertEquals(CalendarEditReviewActivity::class.java.name, it.component!!.className); stop(entry) }
    }
    private fun apply(f: Fixture) {
        shown(f.app.getString(R.string.incoming_edit_review_apply))
        compose.onNodeWithTag("incoming_edit_apply").performScrollTo().performClick()
        shown(f.app.getString(R.string.events_editor_edit))
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(f.app.getString(R.string.phone_event_title)) and hasSetTextAction() and isEnabled()).fetchSemanticsNodes().size == 1 }
    }
    private fun save() = compose.onNode(hasText("Save") and hasClickAction() and isEnabled()).performClick()
    private fun shown(value: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun records(f: Fixture) = runBlocking { f.app.calendarLocalStore.list() }
    private fun openDrafts() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("calendar_phone_month").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Drafts").performClick(); shown("Drafts")
    }
    private fun <A: ComponentActivity> finished(activity: ActivityController<A>) {
        compose.waitUntil(15_000) { compose.waitForIdle(); activity.get().isFinishing }
    }
    private fun <A: ComponentActivity> nextIntent(activity: ActivityController<A>): Intent {
        var next: Intent? = null
        compose.waitUntil(15_000) { compose.waitForIdle(); if (next == null) next = shadowOf(activity.get()).nextStartedActivity; next != null }
        return requireNotNull(next)
    }
    private fun <A: ComponentActivity> host(f: Fixture, type: Class<A>, intent: Intent): ActivityController<A> = Robolectric.buildActivity(type, intent).also {
        compose.runOnUiThread { it.setup().visible() }; f.activities += it
    }
    private fun stop(activity: ActivityController<out ComponentActivity>) { compose.runOnUiThread { activity.pause().stop().destroy() } }
    private fun assertNoWrite(f: Fixture) {
        assertEquals(0, f.provider.batchCount)
        f.app.calendarLocalStore.readableDatabase.rawQuery("SELECT COUNT(*) FROM mutation_journal", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    private fun assertFilters(f: Fixture) = assertEquals(none, runBlocking { f.app.settings.calendarSelection.first() })
    private class Fixture(val app: CalendarApp, val provider: NativeRecurringCreateJourneyProvider) {
        val activities = mutableListOf<ActivityController<out ComponentActivity>>()
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as CalendarApp
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = NativeRecurringCreateJourneyProvider(allowUpdates = true)
        provider.seedOneOff(start, start.plusSeconds(3600), sourceTitle)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        runBlocking {
            app.settings.saveReminderSettings(app.settings.readReminderSettings(), SavedReminderSettings()); app.reminderRuntime.onResume().join()
            app.settings.setDisplaySettings(CalendarSettingsOptions(weatherVisible = false, theme = SettingsTheme.LIGHT, displayZone = DisplayZoneOption.Fixed(ZoneOffset.UTC)))
            val calendars = app.calendarReader.readCalendars() as CalendarReadState.Ready
            assertTrue(app.settings.setDefaultCalendar(null, calendars.value)); assertTrue(app.settings.setCalendarSelection(none, calendars.value))
        }
        val f = Fixture(app, provider)
        try { block(f) } finally {
            f.activities.asReversed().forEach { if (!it.get().isDestroyed) stop(it) }
            provider.db.close()
        }
    }
}
