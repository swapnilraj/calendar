package dev.swapnilraj.calendar.events

import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.CalendarLocalStore
import dev.swapnilraj.calendar.data.calendar.CalendarOperationRetirement
import dev.swapnilraj.calendar.ui.events.*
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
@Config(sdk = [35])
class OrdinaryAtomicSqlTest {
    @Test fun separateSqlConnectionsFenceFrozenOperationBeforeExactDiscardAndNeverRebindMissingRoute() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val name = "ordinary-${UUID.randomUUID()}.db"
        val one = CalendarLocalStore(context, name); val two = CalendarLocalStore(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val clock = Clock.fixed(Instant.parse("2030-06-15T00:00:00.123456789Z"), ZoneOffset.UTC)
            val draft = EventDraft(CalendarId(2), EventTime.Timed(Instant.parse("2030-06-15T09:00:00Z"), Instant.parse("2030-06-15T10:00:00Z")), ZoneOffset.UTC,
                "Synthetic SQLite event", targetIdentity = eventTestIdentity())
            val launch = (prepareNewDraft(draft, "sql-draft", true, one, clock) as DraftPreparationResult.Ready).launch
            val writer = object : CalendarMutations {
                var calls = 0
                override suspend fun loadForEdit(ref: EventRef): EditReadResult = error("Recovery must not load a source")
                override suspend fun create(operationId: String, draft: EventDraft): MutationResult { calls++; return MutationResult.PermissionRequired }
                override suspend fun update(operationId: String, ref: EventRef, token: EditToken, patch: EventPatch): MutationResult = error("Not an update")
                override suspend fun delete(operationId: String, ref: EventRef, token: EditToken): MutationResult = error("Not a delete")
                override suspend fun recover(operationId: String): MutationResult = MutationResult.Unavailable
            }
            fun open(value: EditorDraftLaunch, storage: AtomicDraftStore, permission: suspend () -> Boolean = { true }) = EventEditorController(draft, null, value.draftId,
                writer, storage, scope, clock, recoveryOnly = value.needsRecovery, requireStoredDraft = true, expectedGeneration = value.generation,
                retirement = CalendarOperationRetirement(two), requestWritePermission = permission)
            val first = open(launch, one); withTimeout(10_000) { while (first.state.loading) yield() }
            first.change { it.copy(description = "Exact nanosecond row") }; assertTrue(first.flushDraft())
            assertEquals(clock.instant(), two.load("sql-draft")!!.updatedAt)
            first.save(); withTimeout(10_000) { while (first.state.saving) yield() }
            val frozen = two.load("sql-draft")!!; val recoveryLaunch = DraftBrowserCodec.read(frozen).launch!!
            assertTrue(recoveryLaunch.needsRecovery); assertEquals(1, writer.calls)
            val recovered = open(recoveryLaunch, two, permission = { error("Retirement needs no provider permission") })
            withTimeout(10_000) { while (recovered.state.loading) yield() }
            assertTrue(recovered.discardDraft()); assertNull(one.load("sql-draft"))
            val request = FrozenEditorSubmission.decode(org.json.JSONObject(frozen.payload).getJSONObject("submission")).retirementRequest()
            assertEquals(OperationRetirementResult.Retired(first.pendingOperationId), CalendarOperationRetirement(one).inspect(request))
            first.recover(); withTimeout(10_000) { while (first.state.saving) yield() }; first.retryOriginal(); first.save()
            assertEquals(1, writer.calls)
            val missing = open(launch, one); withTimeout(10_000) { while (missing.state.loading) yield() }
            assertTrue(missing.state.draftReadFailed); missing.save(); assertEquals(1, writer.calls)
        } finally { scope.cancel(); one.close(); two.close(); context.deleteDatabase(name) }
    }
}
