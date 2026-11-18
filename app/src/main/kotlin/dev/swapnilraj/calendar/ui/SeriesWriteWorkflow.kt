package dev.swapnilraj.calendar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.ui.events.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Navigation contains identity only. Fields, source tokens and commands remain in private drafts. */
internal data class SeriesScopeRequest(val selected: EventRef, val action: SeriesEditorAction) {
    fun encode(): String = JSONObject().put("ref", SeriesJson.ref(selected)).put("action", action.name).toString()
    companion object {
        fun decode(raw: String): SeriesScopeRequest? = runCatching { JSONObject(raw).let {
            require(it.keys().asSequence().toSet() == setOf("ref", "action"))
            SeriesScopeRequest(SeriesJson.ref(it.getJSONObject("ref")), SeriesEditorAction.valueOf(it.getString("action")))
        } }.getOrNull()
    }
}

internal data class SeriesWriteRequest(val id: String, val selected: EventRef, val scope: RecurrenceScope,
    val action: SeriesEditorAction, val generation: String? = null) {
    fun encode(): String = JSONObject().put("id", id).put("ref", SeriesJson.ref(selected)).put("scope", scope.name)
        .put("action", action.name).apply { generation?.let { put("generation", it) } }.toString()
    fun matches(launch: SeriesEditorLaunch): Boolean = id == launch.draftId && selected == launch.selected &&
        scope == launch.scope && action == launch.action && generation == launch.generation
    companion object {
        fun from(launch: SeriesEditorLaunch) = SeriesWriteRequest(launch.draftId, launch.selected, launch.scope, launch.action, launch.generation)
        fun decode(raw: String): SeriesWriteRequest? = runCatching { JSONObject(raw).let {
            val keys = it.keys().asSequence().toSet()
            require(keys == setOf("id", "ref", "scope", "action") || keys == setOf("id", "ref", "scope", "action", "generation"))
            val generation = if (it.has("generation")) (it.get("generation") as? String)?.also { value ->
                require(UUID.fromString(value).toString() == value)
            } ?: error("Invalid generation") else null
            SeriesWriteRequest(it.getString("id").also { id -> require(id.isNotBlank()) }, SeriesJson.ref(it.getJSONObject("ref")),
                RecurrenceScope.valueOf(it.getString("scope")), SeriesEditorAction.valueOf(it.getString("action")), generation)
        } }.getOrNull()
    }
}

private data class OpenedSeriesDraft(val launch: SeriesEditorLaunch, val calendar: CalendarIdentity?)

internal class PermissionAwareSeriesMutations(private val delegate: CalendarSeriesMutations,
    private val hasWritePermission: () -> Boolean, private val requestWritePermission: () -> Unit) : CalendarSeriesMutations by delegate {
    override suspend fun mutate(operationId: String, selected: EventRef, token: EditToken, command: SeriesCommand): SeriesMutationResult {
        if (!hasWritePermission()) { requestWritePermission(); return SeriesMutationResult.Common(MutationResult.PermissionRequired) }
        return delegate.mutate(operationId, selected, token, command)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SeriesScopeWorkflow(request: SeriesScopeRequest?, mutations: CalendarSeriesMutations, drafts: DraftStore,
    onDismiss: () -> Unit, onPrepared: (SeriesEditorLaunch) -> Unit, clock: Clock = Clock.systemUTC()) {
    var retry by remember(request) { mutableIntStateOf(0) }
    var preparing by remember(request) { mutableStateOf(false) }
    var failed by remember(request) { mutableStateOf(false) }
    val source by produceState<SeriesReadResult?>(null, request, mutations, retry) {
        value = null
        value = if (request == null) SeriesReadResult.Unavailable else readSeries(mutations, request.selected)
    }
    val ready = (source as? SeriesReadResult.Ready)?.series?.takeIf { request != null && it.selectedDetails.occurrence.ref == request.selected && it.snapshot.validatedSelection == request.selected }
    val scope = rememberCoroutineScope()
    if (ready != null && request != null) {
        if (!preparing) SeriesScopeSheet(ready, request.action, onDismiss) { chosen ->
            if (canPrepareSeriesScope(ready, chosen, request.action) && !preparing) scope.launch {
                preparing = true
                try {
                    val identity = UUID.nameUUIDFromBytes(SeriesJson.ref(request.selected).toString().toByteArray(Charsets.UTF_8))
                    val prepared = prepareSeriesDraft(ready, chosen, request.action, "series:$identity:$chosen:${request.action}", drafts, clock)
                    if (prepared is SeriesDraftPreparationResult.Ready) onPrepared(prepared.launch) else failed = true
                } finally { preparing = false }
            }
        } else SeriesRouteNotice(null, onDismiss, {})
    } else SeriesRouteNotice(source, onDismiss, { retry++ })
    if (failed) AlertDialog(onDismissRequest = { failed = false }, text = { Text(stringResource(R.string.navigation_draft_unavailable)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.events_close)) } })
}

internal suspend fun readSeries(mutations: CalendarSeriesMutations, selected: EventRef): SeriesReadResult = try {
    mutations.loadForEdit(selected).let { result ->
        if (result is SeriesReadResult.Ready && (result.series.selectedDetails.occurrence.ref != selected || result.series.snapshot.validatedSelection != selected))
            SeriesReadResult.Unavailable else result
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { SeriesReadResult.Unavailable }

@Composable
internal fun SeriesWriteWorkflow(request: SeriesWriteRequest?, mutations: CalendarSeriesMutations, drafts: DraftStore,
    onDismiss: () -> Unit, onSaved: (SeriesMutationResult.LocalSaved) -> Unit,
    onDeleted: (SeriesMutationResult.LocalDeleted) -> Unit, clock: Clock = Clock.systemUTC(),
    requestWritePermission: (suspend () -> Boolean)? = null,
    onReplacedLaunch: ((SeriesEditorLaunch) -> Unit)? = null) {
    var retry by remember(request) { mutableIntStateOf(0) }
    var loading by remember(request, retry) { mutableStateOf(true) }
    val stored by produceState<OpenedSeriesDraft?>(null, request, drafts, retry) {
        loading = true
        value = try {
            withContext(Dispatchers.IO) { request?.let { requested -> drafts.load(requested.id)?.let { record ->
                val document = SeriesDraftCodec.decode(record)
                val launch = document.launch()
                if (requested.matches(launch)) OpenedSeriesDraft(launch, document.context?.calendar) else null
            } } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        loading = false
    }
    val launch = stored?.launch?.takeIf { request?.matches(it) == true }
    if (request == null || launch == null) {
        SeriesRouteNotice(if (loading) null else SeriesReadResult.Unavailable, onDismiss, { retry++ })
        return
    }
    var replacement by remember(request) { mutableStateOf<SeriesDraftPreparationResult.Ready?>(null) }
    var replacementSource by remember(request) { mutableStateOf<EditableSeries?>(null) }
    var replacementVersion by remember(request) { mutableIntStateOf(0) }
    var preparationFailed by remember(request) { mutableStateOf(false) }
    var preparing by remember(request) { mutableStateOf(false) }
    val source by produceState<SeriesReadResult?>(null, request, mutations, launch.needsRecovery) {
        value = null
        if (!launch.needsRecovery) value = readSeries(mutations, request.selected)
    }
    if (!launch.needsRecovery && source == null && replacementSource == null) {
        SeriesRouteNotice(null, onDismiss, {})
        return
    }
    val effectiveLaunch = replacement?.launch ?: launch
    val effectiveSource = replacementSource ?: (source as? SeriesReadResult.Ready)?.series
    val scope = rememberCoroutineScope()
    val permission by rememberUpdatedState(requestWritePermission)
    val replacementHandler by rememberUpdatedState(onReplacedLaunch)
    val controller = remember(request, effectiveLaunch, effectiveSource, mutations, drafts, replacementVersion) {
        SeriesEventEditorController(effectiveLaunch, effectiveSource, mutations, drafts, scope,
            recoveryOnly = effectiveLaunch.needsRecovery, clock = clock,
            requestWritePermission = requestWritePermission?.let { { permission?.invoke() ?: false } })
    }
    SeriesEventEditorSheet(controller, onDismiss, onSaved, onDeleted, onReview = if (onReplacedLaunch == null) null else { result ->
        if (result is SeriesReadResult.Ready && result.series.selectedDetails.occurrence.ref == request.selected &&
            result.series.snapshot.validatedSelection == request.selected && stored?.calendar != null &&
            result.series.selectedDetails.calendar?.identity == stored?.calendar && !preparing) scope.launch {
            preparing = true
            try {
                val prepared = prepareSeriesDraft(result.series, request.scope, request.action, request.id, drafts, clock)
                if (prepared is SeriesDraftPreparationResult.Ready) {
                    replacementSource = result.series; replacement = prepared; replacementVersion++
                    replacementHandler?.invoke(prepared.launch)
                }
                else preparationFailed = true
            } finally { preparing = false }
        }
    })
    if (preparationFailed) AlertDialog(onDismissRequest = { preparationFailed = false }, text = { Text(stringResource(R.string.navigation_draft_unavailable)) },
        confirmButton = { TextButton(onClick = { preparationFailed = false }) { Text(stringResource(R.string.events_close)) } })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SeriesRouteNotice(result: SeriesReadResult?, onDismiss: () -> Unit, onRetry: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(when (result) {
                null -> R.string.events_loading
                SeriesReadResult.PermissionRequired -> R.string.events_permission_required
                is SeriesReadResult.Denied -> R.string.series_scope_read_only
                is SeriesReadResult.NeedsReview -> R.string.series_needs_review
                else -> R.string.events_drafts_unreadable
            }))
            if (result == null) CircularProgressIndicator() else TextButton(onClick = onRetry) { Text(stringResource(R.string.events_retry)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.events_close)) }
        }
    }
}
