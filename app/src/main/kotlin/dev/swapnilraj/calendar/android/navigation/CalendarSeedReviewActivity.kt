package dev.swapnilraj.calendar.android.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.swapnilraj.calendar.core.DraftRecord
import dev.swapnilraj.calendar.ui.EventWriteRequest
import dev.swapnilraj.calendar.ui.EventWriteWorkflow
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Private explicit CREATE continuation. Opening, editing raw input and Cancel never write events. */
class CalendarSeedReviewActivity : ComponentActivity() {
    private val services get() = (application as? CalendarSeedReviewOwner)?.calendarSeedReviewServices
    private var result by mutableStateOf<CalendarSeedReviewResult?>(null)
    private var busy by mutableStateOf(false)
    private var request: CalendarSeedReviewRequest? = null
    private var restoreGeneration: String? = null
    private var restoreOnly = false
    private var native: EventWriteRequest? = null
    private var frame = 0L
    private var edits = 0L
    private var durable: CalendarSeedReviewSnapshot? = null
    private val ordering = Mutex()
    private val reloads = MutableStateFlow(0L)
    private val readPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }
    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Save is a new gesture after grant. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        request = try { CalendarSeedReviewCodec.decode(intent?.getStringExtra(EXTRA_REQUEST)) } catch (_: RuntimeException) { null }
        restoreGeneration = try { savedInstanceState?.getString(STATE_GENERATION) ?: intent?.getStringExtra(EXTRA_GENERATION) } catch (_: RuntimeException) { null }
        restoreOnly = savedInstanceState != null || restoreGeneration != null
        native = try { savedInstanceState?.getString(STATE_NATIVE)?.let(EventWriteRequest::decode) } catch (_: RuntimeException) { null }
        setContent {
            DaylightTheme {
                val displayed = result
                val current = services
                if (displayed is CalendarSeedReviewResult.Editor && current != null &&
                    (hasRead() && displayed.environment.calendarReadGranted || displayed.launch.needsRecovery)) {
                    val editor = native
                    if (editor != null) EventWriteWorkflow(editor, current.mutations, current.drafts,
                        if (hasRead() && displayed.environment.calendarReadGranted) displayed.environment.calendars else emptyList(),
                        displayed.environment.use24Hour, onDismiss = ::finish,
                        onSaved = { setResult(RESULT_OK); finish() }, onDeleted = { finish() },
                        recurringCreationSupported = displayed.environment.recurringCreationSupported, retirement = current.retirement,
                        requestWritePermission = {
                            if (!hasRead()) { reload(); false }
                            else if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) true
                            else { writePermission.launch(Manifest.permission.WRITE_CALENDAR); false }
                        },
                        onReplacedLaunch = { launch ->
                            native = EventWriteRequest.fromLaunch(launch, editor.date, editor.zone)
                            if (hasRead()) result = CalendarSeedReviewResult.Editor(launch, displayed.environment) else reload()
                        })
                } else CalendarSeedReviewScreen(displayed, busy, ::finish, ::reload,
                    { readPermission.launch(Manifest.permission.READ_CALENDAR) }, ::edit, ::continueReview)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val current = services
                if (current == null) { result = CalendarSeedReviewResult.Unavailable; return@repeatOnLifecycle }
                combine(reloads, current.changes.onStart { emit(Unit) }) { _, _ -> Unit }.collectLatest {
                    val epoch = ++frame
                    result = null
                    val value = request
                    if (value == null) { result = CalendarSeedReviewResult.Unavailable; return@collectLatest }
                    try {
                        val loaded = ordering.withLock {
                            native?.let { route -> route.generation?.let { current.resumeEditor(route.draftId, it, hasRead()) } }
                                ?: current.open(value, restoreGeneration, restoreOnly, hasRead())
                        }
                        if (frame == epoch) {
                            if (hasRead() || loaded is CalendarSeedReviewResult.Editor && loaded.launch.needsRecovery) present(loaded)
                            else result = CalendarSeedReviewResult.PermissionRequired
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (frame == epoch) result = CalendarSeedReviewResult.Unavailable }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); reload() }
    override fun onStop() { frame++; result = null; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        restoreGeneration?.let { outState.putString(STATE_GENERATION, it) }
        native?.let { outState.putString(STATE_NATIVE, it.encode()) }
        super.onSaveInstanceState(outState)
    }
    private fun hasRead() = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun reload() { frame++; result = null; reloads.value++ }
    private fun present(value: CalendarSeedReviewResult) {
        busy = false
        when (value) {
            is CalendarSeedReviewResult.Review -> { durable = value.snapshot; restoreGeneration = value.snapshot.generation }
            is CalendarSeedReviewResult.NeedsMeetingEditor -> { durable = value.snapshot; restoreGeneration = value.snapshot.generation }
            is CalendarSeedReviewResult.Editor -> {
                // The fallback is navigation-only; a prepared ordinary row already has proven dates.
                val date = runCatching { LocalDate.parse(value.launch.rawDate) }.getOrNull() ?: native?.date
                    ?: request?.seed?.occurrence?.begin?.atZone(value.environment.displayZone)?.toLocalDate()
                    ?: LocalDate.of(2000, 1, 1)
                native = EventWriteRequest.fromLaunch(value.launch, date, value.environment.displayZone)
            }
            else -> Unit
        }
        result = value
    }

    private fun edit(transform: (CalendarSeedReviewFields) -> CalendarSeedReviewFields) {
        val displayed = result as? CalendarSeedReviewResult.Review ?: return
        if (displayed.unverifiedInput) return
        if (!hasRead()) { reload(); return }
        val fields = transform(displayed.snapshot.fields)
        val sequence = ++edits; val epoch = frame
        result = displayed.copy(snapshot = displayed.snapshot.copy(fields = fields), problem = null)
        busy = true
        lifecycleScope.launch {
            ordering.withLock {
                val current = durable ?: return@withLock
                val next = services?.update(current, fields)
                if (next != null) durable = next
                if (sequence == edits && frame == epoch) {
                    busy = false
                    if (next == null) {
                        durable = null
                        result = displayed.copy(snapshot = displayed.snapshot.copy(fields = fields), unverifiedInput = true)
                    }
                    else result = displayed.copy(snapshot = next, problem = null)
                }
            }
        }
    }

    private fun continueReview() {
        val displayed = result as? CalendarSeedReviewResult.Review ?: return
        if (busy || displayed.unverifiedInput || !hasRead()) return
        val epoch = frame
        busy = true
        lifecycleScope.launch {
            try {
                val next = ordering.withLock {
                    val current = durable ?: return@withLock CalendarSeedReviewResult.Stale
                    services?.prepare(current, displayed.environment) ?: CalendarSeedReviewResult.Unavailable
                }
                if (frame == epoch) {
                    if (hasRead()) present(next) else result = CalendarSeedReviewResult.PermissionRequired
                }
            } finally { if (frame == epoch) busy = false }
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "dev.swapnilraj.calendar.SEED_REVIEW_REQUEST"
        private const val EXTRA_GENERATION = "dev.swapnilraj.calendar.SEED_REVIEW_GENERATION"
        private const val STATE_GENERATION = "seedReviewGeneration"
        private const val STATE_NATIVE = "seedReviewNative"
        fun intent(context: Context, sessionId: String, seed: CalendarDraftSeed, reviewedZone: java.time.ZoneId): Intent =
            Intent(context, CalendarSeedReviewActivity::class.java).putExtra(EXTRA_REQUEST,
                CalendarSeedReviewCodec.encode(CalendarSeedReviewRequest(sessionId, seed, reviewedZone)))

        fun intentFromDraft(context: Context, record: DraftRecord): Intent? = try {
            val document = CalendarSeedCompletionDocument.decode(record)
            intent(context, document.request.sessionId, document.request.seed, document.request.reviewedZone)
                .putExtra(EXTRA_GENERATION, document.generation)
        } catch (_: Exception) { null }

        /** Repair the interrupted local handoff before generic browser navigation may expose Save. */
        fun intentForPendingNative(context: Context, nativeRecord: DraftRecord, completionRecord: DraftRecord?): Intent? = try {
            val completion = CalendarSeedCompletionDocument.decode(requireNotNull(completionRecord))
            require(completion.preparedFields != null && completion.request.draftId == nativeRecord.id)
            val native = dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument.decode(nativeRecord)
            require(!native.legacy && native.launch.kind == dev.swapnilraj.calendar.ui.events.EditorDraftKind.CREATE && native.launch.ref == null && native.generation != null)
            intentFromDraft(context, completionRecord)
        } catch (_: Exception) { null }
    }
}
