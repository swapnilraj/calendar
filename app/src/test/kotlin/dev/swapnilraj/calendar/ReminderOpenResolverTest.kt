package dev.swapnilraj.calendar

import android.content.ComponentName
import dev.swapnilraj.calendar.android.navigation.*
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderOpenResolverTest {
    private val now = Instant.parse("2026-11-18T09:00:00Z")
    private val calendar = CalendarInfo(CalendarId(3), "Fixture calendar", 1,
        CalendarAccount("fixture@example.invalid", "com.google"), "fixture@example.invalid", ZoneOffset.UTC,
        700, true, true, providerId = "fixture-calendar-uid")
    private val identity = requireNotNull(CalendarReminderSource.calendarIdentity(calendar))
    private val ref = EventRef(calendar.id, 7, now, now.plusSeconds(3600), RecurrenceRef(7, now))
    private val occurrence = EventOccurrence(ref, EventTime.Timed(ref.instanceStart, ref.instanceEnd), "Synthetic appointment", 1)
    private val candidate = ReminderCandidate(ReminderKey.of(ref, 0), identity, occurrence, now, "fixture-event-uid")
    private class Source(var result: ReminderResolutionResult) : ReminderSource {
        var reads = 0
        override suspend fun loadDueWindow(window: InstantBounds, configuration: ReminderConfiguration): ReminderSourceResult = error("Open must resolve its exact key")
        override suspend fun resolve(keys: Set<ReminderKey>, configuration: ReminderConfiguration): ReminderResolutionResult { reads++; return result }
    }
    private inner class Fixture : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val database = "open-${UUID.randomUUID()}.db"
        val store = CalendarLocalStore(context, database)
        val ledger = SqliteReminderLedger(store)
        var config = ReminderConfiguration(true, setOf(calendar.id), ZoneOffset.UTC, expectedCalendarIdentities = mapOf(calendar.id to identity))
        val source = Source(ReminderResolutionResult.Ready(mapOf(candidate.key to ReminderResolution.Eligible(candidate))))
        var detailReads = 0
        var details: suspend (EventRef) -> CalendarReadState<EventDetails> = { selected ->
            detailReads++
            CalendarReadState.Ready(EventDetails(candidate.occurrence.copy(ref = selected,
                time = EventTime.Timed(selected.instanceStart, selected.instanceEnd)), calendar))
        }
        val resolver = ReminderOpenResolver(source, ledger, { config }, { details(it) })
        suspend fun posted(acknowledge: Boolean = true): ReminderPostIdentity {
            val window = InstantBounds(now.minusSeconds(1), now.plusSeconds(1))
            ledger.reconcile(ReminderSourceSnapshot(window, listOf(candidate), setOf(calendar.id), mapOf(calendar.id to identity)),
                setOf(calendar.id), mapOf(candidate.key to ReminderResolution.Eligible(candidate)), now)
            val claim = ledger.claimDue(now, 1).single()
            if (acknowledge) assertTrue(ledger.acknowledge(claim, ReminderDeliveryOutcome.Posted(now)))
            return requireNotNull(claim.record.postIdentity)
        }
        override fun close() { store.close(); context.deleteDatabase(database) }
    }
    @Test fun openFollowsOriginalOccurrenceKeyToMovedExceptionInsteadOfOldEventNumber() = runBlocking {
        Fixture().use { f ->
            val post = f.posted()
            val movedRef = ref.copy(eventId = 99, instanceStart = now.plusSeconds(86400), instanceEnd = now.plusSeconds(90000))
            f.source.result = ReminderResolutionResult.Ready(mapOf(candidate.key to ReminderResolution.Eligible(candidate.copy(
                occurrence = occurrence.copy(ref = movedRef, time = EventTime.Timed(movedRef.instanceStart, movedRef.instanceEnd))))))
            val opened = f.resolver.resolve(post) as CalendarReadState.Ready
            assertEquals(movedRef, opened.value.occurrence.ref)
            assertEquals(1, f.detailReads)
            assertEquals(post, f.ledger.findByAlias(post.alias)?.postIdentity)
        }
    }
    @Test fun stalePostAndReplacementNamespaceNeverFetchPrivateDetails() = runBlocking {
        Fixture().use { f ->
            val post = f.posted()
            assertEquals(CalendarReadState.Unavailable, f.resolver.resolve(post.copy(token = "old-token")))
            assertEquals(0, f.source.reads)
            val replacement = candidate.copy(sourceCalendarIdentity = identity.copy(providerUidFingerprint = "replacement-uid"))
            f.source.result = ReminderResolutionResult.Ready(mapOf(candidate.key to ReminderResolution.Eligible(replacement)))
            assertEquals(CalendarReadState.Unavailable, f.resolver.resolve(post))
            assertEquals(0, f.detailReads)
        }
    }
    @Test fun aMatchingInterruptedPostCanOpenWithoutInventingADeliveryAcknowledgement() = runBlocking {
        Fixture().use { f ->
            val post = f.posted(acknowledge = false)
            assertTrue(f.resolver.resolve(post) is CalendarReadState.Ready)
            assertEquals(ReminderDeliveryState.POSTING, f.ledger.findByAlias(post.alias)?.state)
        }
    }
    @Test fun currentDetailsNamespaceAndChangedConfigurationAreRecheckedBeforePublication() = runBlocking {
        Fixture().use { f ->
            val post = f.posted()
            f.details = { f.detailReads++; CalendarReadState.Ready(EventDetails(occurrence, calendar.copy(providerId = "reused-id"))) }
            assertEquals(CalendarReadState.Unavailable, f.resolver.resolve(post))
            f.details = { f.config = f.config.copy(enabled = false); CalendarReadState.Ready(EventDetails(occurrence, calendar)) }
            assertEquals(CalendarReadState.Unavailable, f.resolver.resolve(post))
        }
    }
    @Test fun postRetiredWhileDetailsLoadCannotPublishAndPermissionFailureIsExplicit() = runBlocking {
        Fixture().use { f ->
            val post = f.posted()
            f.source.result = ReminderResolutionResult.PermissionRequired
            assertEquals(CalendarReadState.PermissionRequired, f.resolver.resolve(post))
            assertEquals(0, f.detailReads)
            f.source.result = ReminderResolutionResult.Ready(mapOf(candidate.key to ReminderResolution.Eligible(candidate)))
            f.details = {
                val record = requireNotNull(f.ledger.findByAlias(post.alias))
                assertTrue(f.ledger.dismiss(record.version, post.token))
                CalendarReadState.Ready(EventDetails(occurrence, calendar))
            }
            assertEquals(CalendarReadState.Unavailable, f.resolver.resolve(post))
        }
    }
    @Test fun activityDecoderRequiresCanonicalUriAndCompletePostProof() {
        val context = RuntimeEnvironment.getApplication()
        val activity = ComponentName(context, MainActivity::class.java)
        val targets = ReminderIntentTargets(activity, ComponentName(context, ReminderActionReceiver::class.java))
        val value = ReminderIdentity(ref, now, "generation-nonce", "post-token")
        val intent = ReminderIntents.intent(context, targets, value, ReminderIntents.ACTION_OPEN)
        assertEquals(value.postIdentityOrNull(), ReminderOpenDecoder.decode(context, intent, activity))
        intent.data = intent.data!!.buildUpon().appendPath("changed").build()
        assertNull(ReminderOpenDecoder.decode(context, intent, activity))
        val legacy = ReminderIntents.intent(context, targets, ReminderIdentity(ref, now), ReminderIntents.ACTION_OPEN)
        assertNull(ReminderOpenDecoder.decode(context, legacy, activity))
    }
}
