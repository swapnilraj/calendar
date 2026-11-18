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
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.EventWriteRequest
import dev.swapnilraj.calendar.ui.EventWriteWorkflow
import dev.swapnilraj.calendar.ui.events.OrdinaryDraftDocument
import dev.swapnilraj.calendar.ui.theme.DaylightTheme
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Non-exported continuation of an explicit public EDIT review. It accepts no new provider command. */
class CalendarEditReviewActivity : ComponentActivity() {
    private val services get() = (application as? CalendarEditReviewOwner)?.calendarEditReviewServices
    private var result by mutableStateOf<CalendarEditReviewResult?>(null)
    private var busy by mutableStateOf(false)
    private var request: CalendarEditReviewRequest? = null
    private var session: String? = null
    private var reviewGeneration: String? = null
    private var nativeGeneration: String? = null
    private var restoreOnly = false
    private var native: EventWriteRequest? = null
    private var frame = 0L
    private val ordering = Mutex()
    private val reloads = MutableStateFlow(0L)
    private val readPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }
    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Another explicit Save is required. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        try {
            request = CalendarEditReviewCodec.decode(intent?.getStringExtra(REQUEST))
            session = request?.sessionId ?: uuid(intent?.getStringExtra(SESSION))
            reviewGeneration = uuid(savedInstanceState?.getString(REVIEW_GENERATION) ?: intent?.getStringExtra(REVIEW_GENERATION))
            nativeGeneration = uuid(savedInstanceState?.getString(NATIVE_GENERATION) ?: intent?.getStringExtra(NATIVE_GENERATION))
            restoreOnly = savedInstanceState != null || reviewGeneration != null
            require(request == null || !intent.hasExtra(SESSION) && !intent.hasExtra(NATIVE_GENERATION))
        } catch (_: RuntimeException) { session = null; request = null }
        setContent {
            ReviewTheme {
                val displayed = result
                val current = services
                if (displayed is CalendarEditReviewResult.Editor && current != null) {
                    val port = remember(current, displayed.launch.generation, displayed.originalTokenHash, displayed.originalBaselineHash) {
                        object : CalendarMutations by current.mutations {
                            override suspend fun loadForEdit(ref: EventRef): EditReadResult =
                                if (ref == displayed.launch.ref) current.loadForNative(displayed) else EditReadResult.Unavailable
                            override suspend fun recover(operationId: String): MutationResult {
                                val value = current.mutations.recover(operationId)
                                if (value == MutationResult.PermissionRequired && !hasRead() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                                    readPermission.launch(Manifest.permission.READ_CALENDAR)
                                return value
                            }
                        }
                    }
                    native?.let { route ->
                        EventWriteWorkflow(route, port, current.drafts,
                            if (hasRead() && displayed.environment.calendarReadGranted) displayed.environment.calendars else emptyList(),
                            displayed.environment.use24Hour, onDismiss = ::finish, onSaved = { setResult(RESULT_OK); finish() }, onDeleted = { finish() },
                            retirement = current.retirement, incomingEditReview = displayed,
                            presentationVisible = hasRead() && displayed.environment.calendarReadGranted || displayed.sourceFree,
                            requestWritePermission = {
                                if (!hasRead()) { readPermission.launch(Manifest.permission.READ_CALENDAR); false }
                                else if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) true
                                else { writePermission.launch(Manifest.permission.WRITE_CALENDAR); false }
                            })
                        // No unguarded replacement callback: a separate generic draft must not
                        // escape this session's event-UID/source binding. Typed discard remains.
                    }
                } else CalendarEditReviewScreen(displayed, busy, ::finish, ::reload,
                    { readPermission.launch(Manifest.permission.READ_CALENDAR) }, ::applySuggestions)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val current = services
                if (current == null) { result = CalendarEditReviewResult.Unavailable; return@repeatOnLifecycle }
                var observedReload: Long? = null
                combine(reloads, current.changes.onStart { emit(Unit) }) { revision, _ -> revision }.collectLatest { revision ->
                    val explicit = observedReload != revision; observedReload = revision
                    val mounted = result as? CalendarEditReviewResult.Editor
                    if (!explicit && mounted != null && native != null) {
                        refreshMounted(current, mounted)
                        return@collectLatest
                    }
                    val epoch = ++frame
                    result = null
                    try {
                        val loaded = ordering.withLock {
                            val id = session ?: return@withLock CalendarEditReviewResult.Unavailable
                            val generation = nativeGeneration
                            if (generation != null) {
                                val review = reviewGeneration ?: return@withLock CalendarEditReviewResult.Unavailable
                                current.resumeNative(id, generation, review, hasRead())
                            } else request?.let { current.open(it, reviewGeneration, restoreOnly, hasRead()) } ?: CalendarEditReviewResult.Unavailable
                        }
                        if (frame == epoch) present(loaded)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (frame == epoch) result = CalendarEditReviewResult.Unavailable }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); reload() }
    override fun onStop() { frame++; result = null; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        reviewGeneration?.let { outState.putString(REVIEW_GENERATION, it) }
        nativeGeneration?.let { outState.putString(NATIVE_GENERATION, it) }
        super.onSaveInstanceState(outState)
    }
    @Composable private fun ReviewTheme(content: @Composable () -> Unit) {
        val owner = application as? CalendarEditReviewOwner
        if (owner == null) DaylightTheme(content = content) else owner.CalendarEditReviewTheme(content)
    }
    private fun hasRead() = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun reload() { frame++; result = null; reloads.value++ }
    private fun present(value: CalendarEditReviewResult) {
        busy = false
        when (value) {
            is CalendarEditReviewResult.Editor -> {
                reviewGeneration = value.reviewGeneration; nativeGeneration = value.launch.generation
                val selected = requireNotNull(value.launch.ref)
                val date = runCatching { LocalDate.parse(value.launch.rawDate) }.getOrNull() ?: selected.instanceStart.atZone(value.environment.displayZone).toLocalDate()
                native = EventWriteRequest.fromLaunch(value.launch, date, value.environment.displayZone)
            }
            is CalendarEditReviewResult.Review -> reviewGeneration = value.snapshot.generation
            is CalendarEditReviewResult.Unsupported -> reviewGeneration = value.snapshot.generation
            else -> Unit
        }
        result = if (!hasRead() && value is CalendarEditReviewResult.Editor && !value.sourceFree) CalendarEditReviewResult.PermissionRequired else value
    }
    private suspend fun refreshMounted(current: CalendarEditReviewServices, mounted: CalendarEditReviewResult.Editor) {
        val epoch = frame; val route = native
        if (!hasRead()) {
            // Mask the existing Dialog before the app-owned frozen-status check can suspend.
            // Removing the workflow here would cancel this controller's own pending write/cleanup.
            result = mounted.copy(environment = mounted.environment.copy(calendars = emptyList(), calendarReadGranted = false))
        }
        val previous = result
        val refreshed = current.refreshMounted(mounted, hasRead())
        if (frame != epoch || native != route || result !== previous || isFinishing) return
        result = when (refreshed) {
            is CalendarEditReviewServices.MountedRefresh.Ready -> mounted.copy(environment = refreshed.environment, sourceFree = refreshed.sourceFree)
            CalendarEditReviewServices.MountedRefresh.PermissionRequired -> CalendarEditReviewResult.PermissionRequired
            CalendarEditReviewServices.MountedRefresh.Stale -> CalendarEditReviewResult.Stale
            CalendarEditReviewServices.MountedRefresh.Unavailable -> CalendarEditReviewResult.Unavailable
        }
    }
    private fun applySuggestions() {
        val displayed = result as? CalendarEditReviewResult.Review ?: return
        if (busy) return
        if (!hasRead()) { readPermission.launch(Manifest.permission.READ_CALENDAR); return }
        val epoch = frame; busy = true
        lifecycleScope.launch {
            try {
                val loaded = ordering.withLock { services?.applySuggestions(displayed.snapshot, hasRead()) ?: CalendarEditReviewResult.Unavailable }
                if (frame == epoch) present(loaded)
            } finally { if (frame == epoch) busy = false }
        }
    }

    companion object {
        private const val REQUEST = "dev.swapnilraj.calendar.EDIT_REVIEW_REQUEST"
        private const val SESSION = "dev.swapnilraj.calendar.EDIT_REVIEW_SESSION"
        private const val REVIEW_GENERATION = "dev.swapnilraj.calendar.EDIT_REVIEW_GENERATION"
        private const val NATIVE_GENERATION = "dev.swapnilraj.calendar.EDIT_NATIVE_GENERATION"
        private fun uuid(value: String?): String? = value?.also { require(UUID.fromString(it).toString() == it) }
        fun intent(context: Context, request: CalendarEditReviewRequest): Intent = Intent(context, CalendarEditReviewActivity::class.java)
            .putExtra(REQUEST, CalendarEditReviewCodec.encode(request))
        fun intentFromReview(context: Context, record: DraftRecord): Intent? = try {
            val doc = CalendarEditReviewDocument.decode(record)
            intent(context, doc.request).putExtra(REVIEW_GENERATION, doc.generation)
        } catch (_: Exception) { null }
        /** Every incoming-edit native route goes here, even with a valid receipt. This static proof
         * never replaces the fresh UID/namespace/token check before an unfrozen native mount. */
        fun intentForNative(context: Context, nativeRecord: DraftRecord, companion: DraftRecord?): Intent? = try {
            requireNotNull(companion)
            val native = OrdinaryDraftDocument.decode(nativeRecord)
            val session: String; val generation: String
            if (isCalendarEditHandoffReceipt(companion)) {
                val receipt = CalendarEditHandoffReceipt.decode(companion); require(receipt.matches(nativeRecord))
                session = receipt.session; generation = receipt.generation
            } else {
                val doc = CalendarEditReviewDocument.decode(companion)
                val candidate = OrdinaryDraftDocument.decode(requireNotNull(doc.prepared))
                require(nativeRecord.id == doc.request.nativeId && native.generation == candidate.generation && native.launch.ref == doc.request.selected &&
                    native.sourceBaseline == doc.sourceBaseline && native.sourceToken == doc.sourceToken)
                session = doc.request.sessionId; generation = doc.generation
            }
            Intent(context, CalendarEditReviewActivity::class.java).putExtra(SESSION, session).putExtra(REVIEW_GENERATION, generation)
                .putExtra(NATIVE_GENERATION, requireNotNull(native.generation))
        } catch (_: Exception) { null }
    }
}
