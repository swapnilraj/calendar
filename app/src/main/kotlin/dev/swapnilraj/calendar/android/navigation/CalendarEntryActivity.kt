package dev.swapnilraj.calendar.android.navigation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.CalendarReadState
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/** Public Android entry only. The original intent never becomes a native mutation command. */
class CalendarEntryActivity : ComponentActivity() {
    private var state by mutableStateOf<CalendarEntryState>(CalendarEntryState.Loading)
    private var session: CalendarEntrySession? = null
    private var savedEvidencePresent = false
    private var savedEvidence: String? = null
    private var invalidSavedEvidence = false
    private var reviewed = false
    private var frame = 0L
    private var presentation = 0L
    private val reloads = MutableStateFlow(0L)
    private var reviewJob: Job? = null
    private val owner get() = application as? CalendarEntryOwner
    private val readPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readSavedEvidence(savedInstanceState)
        val provider = owner
        setContent {
            val displayed = state
            val epoch = presentation
            val reviewable = !reviewed && (displayed is CalendarEntryState.Result &&
                (displayed.resolution is CalendarEntryResolution.Date || displayed.resolution is CalendarEntryResolution.CreateSeed) ||
                displayed is CalendarEntryState.Details && displayed.resolved.proposedEdit != null)
            val actions = CalendarEntryActions(::finish, ::refresh,
                { readPermission.launch(Manifest.permission.READ_CALENDAR) },
                if (reviewable) ({ review(displayed, epoch) }) else null,
                { value ->
                    if (displayed is CalendarEntryState.Details && state === displayed && presentation == epoch &&
                        checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) openLink(value)
                })
            if (provider != null) provider.calendarEntryContent.Render(displayed, actions)
            else BasicCalendarEntryScreen(displayed, actions)
        }
        if (provider == null) { state = CalendarEntryState.Result(null, CalendarEntryResolution.Unavailable); return }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(reloads, provider.calendarEntryServices.changes.onStart { emit(Unit) }) { version, _ -> version }
                    .collectLatest {
                        val currentFrame = frame; val epoch = ++presentation
                        state = CalendarEntryState.Loading
                        try {
                            val zone = readZone(provider, currentFrame, epoch) ?: return@collectLatest
                            val active = session ?: decodeSession(zone).also { session = it } ?: return@collectLatest
                            val resolved = provider.calendarEntryServices.resolver.resolve(active, zone)
                            if (!current(currentFrame, epoch)) return@collectLatest
                            if (resolved is CalendarEntryResolution.Event) {
                                when (val read = provider.calendarEntryServices.resolver.readDetails(active, resolved, zone)) {
                                    is CalendarReadState.Ready -> if (current(currentFrame, epoch)) state = CalendarEntryState.Details(active.id, read.value, resolved, zone)
                                    CalendarReadState.PermissionRequired -> publish(currentFrame, epoch, active.id, CalendarEntryResolution.PermissionRequired)
                                    CalendarReadState.LimitExceeded -> publish(currentFrame, epoch, active.id, CalendarEntryResolution.LimitExceeded)
                                    else -> publish(currentFrame, epoch, active.id, CalendarEntryResolution.Unavailable)
                                }
                            } else publish(currentFrame, epoch, active.id, resolved)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { publish(currentFrame, epoch, session?.id, CalendarEntryResolution.Unavailable) }
                    }
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onStop() {
        presentation++; state = CalendarEntryState.Loading
        reviewJob?.cancel()
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        frame++; session = null; savedEvidencePresent = false; savedEvidence = null; invalidSavedEvidence = false; reviewed = false
        refresh()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val active = session
        if (active != null) outState.putString(SESSION, CalendarEntrySessionCodec.encode(active))
        else if (savedEvidencePresent) outState.putString(SESSION, savedEvidence)
        if (invalidSavedEvidence) outState.putBoolean(INVALID_SAVED, true)
        outState.putBoolean(REVIEWED, reviewed)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun readSavedEvidence(saved: Bundle?) {
        if (saved == null) return
        try {
            savedEvidencePresent = saved.containsKey(SESSION)
            val raw = if (savedEvidencePresent) saved.get(SESSION) else null
            savedEvidence = raw as? String
            invalidSavedEvidence = savedEvidencePresent && raw !is String
            if (saved.containsKey(REVIEWED)) reviewed = saved.get(REVIEWED) as? Boolean ?: run { invalidSavedEvidence = true; true }
            if (saved.containsKey(INVALID_SAVED)) {
                val invalid = saved.get(INVALID_SAVED)
                if (invalid !is Boolean || invalid) invalidSavedEvidence = true
            }
        } catch (_: RuntimeException) { invalidSavedEvidence = true }
    }

    private fun decodeSession(zone: ZoneId): CalendarEntrySession? {
        if (invalidSavedEvidence) { state = CalendarEntryState.Result(null, CalendarEntryResolution.Unavailable); return null }
        val route = CalendarIntentDecoder(zone).decode(intent)
        if (!savedEvidencePresent) return CalendarEntrySession.create(route)
        return when (val restored = CalendarEntrySessionCodec.restore(route, savedEvidence)) {
            is CalendarEntryRestore.Ready -> restored.session
            CalendarEntryRestore.Unavailable -> { state = CalendarEntryState.Result(null, CalendarEntryResolution.Unavailable); null }
        }
    }

    private suspend fun readZone(provider: CalendarEntryOwner, currentFrame: Long, epoch: Long): ZoneId? =
        when (val zone = withContext(Dispatchers.IO) { provider.calendarEntryServices.readZone() }) {
            is CalendarReadState.Ready -> zone.value.takeIf { current(currentFrame, epoch) }
            CalendarReadState.Loading -> null
            CalendarReadState.PermissionRequired -> { publish(currentFrame, epoch, session?.id, CalendarEntryResolution.PermissionRequired); null }
            CalendarReadState.LimitExceeded -> { publish(currentFrame, epoch, session?.id, CalendarEntryResolution.LimitExceeded); null }
            else -> { publish(currentFrame, epoch, session?.id, CalendarEntryResolution.Unavailable); null }
        }
    private fun current(currentFrame: Long, epoch: Long) = frame == currentFrame && presentation == epoch && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun publish(currentFrame: Long, epoch: Long, id: String?, result: CalendarEntryResolution) {
        if (current(currentFrame, epoch)) state = CalendarEntryState.Result(id, result)
    }
    private fun refresh() {
        presentation++; state = CalendarEntryState.Loading; reviewJob?.cancel()
        reloads.update { it + 1 }
    }

    /** A button gesture, never arrival/resume, invokes Root's native review/navigation handler. */
    private fun review(displayed: CalendarEntryState, epoch: Long) {
        if (reviewed || presentation != epoch || state !== displayed || reviewJob?.isActive == true) return
        val provider = owner ?: return
        val active = session ?: return
        val currentFrame = frame
        val expected = when (displayed) {
            is CalendarEntryState.Result -> displayed.resolution
            is CalendarEntryState.Details -> displayed.resolved
            CalendarEntryState.Loading -> return
        }
        reviewJob = lifecycleScope.launch {
            try {
                val zone = readZone(provider, currentFrame, epoch) ?: return@launch
                val fresh = provider.calendarEntryServices.resolver.resolve(active, zone)
                if (!current(currentFrame, epoch) || reviewed) return@launch
                val matches = when (expected) {
                    is CalendarEntryResolution.Date -> fresh == expected
                    is CalendarEntryResolution.CreateSeed -> fresh == expected
                    is CalendarEntryResolution.Event -> fresh is CalendarEntryResolution.Event && fresh.ref == expected.ref &&
                        bindingMatches(expected.binding, fresh.binding) && fresh.proposedEdit != null
                    else -> false
                }
                if (!matches) { publish(currentFrame, epoch, active.id, CalendarEntryResolution.Stale); return@launch }
                if (provider.calendarEntryReviewHandler.open(this@CalendarEntryActivity, CalendarEntryReviewCommand(active.id, fresh, zone))) {
                    reviewed = true; finish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(currentFrame, epoch, active.id, CalendarEntryResolution.Unavailable) }
        }
    }
    private fun openLink(value: String) {
        val uri = try { Uri.parse(value) } catch (_: RuntimeException) { return }
        if (uri.scheme?.lowercase() !in setOf("http", "https", "geo", "mailto", "tel")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.navigation_no_link_handler, Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(this, R.string.navigation_link_unavailable, Toast.LENGTH_SHORT).show() }
    }
    companion object {
        internal const val SESSION = "calendar_entry.session.v1"
        private const val REVIEWED = "calendar_entry.reviewed"
        private const val INVALID_SAVED = "calendar_entry.invalid_saved"
    }
}
