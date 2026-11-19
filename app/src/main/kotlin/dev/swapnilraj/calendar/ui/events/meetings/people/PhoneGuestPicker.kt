package dev.swapnilraj.calendar.ui.events.meetings.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.contacts.*
import dev.swapnilraj.calendar.ui.events.meetings.create.MeetingCreationGuestFields
import kotlinx.coroutines.*

/** One opening only. No recipient validation, normalization, persistence or calendar authority. */
internal class GuestPickerSearchSession(private val source: DeviceContactSuggestionSource?, private val scope: CoroutineScope) {
    var query by mutableStateOf(""); private set
    var optedIn by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var result by mutableStateOf<DeviceContactSearchResult>(DeviceContactSearchResult.Disabled); private set
    private var permission = false
    private var closed = false
    private var generation = 0L
    private var job: Job? = null
    fun changeQuery(value: String) { if (!closed && query != value) { query=value; restart() } }
    fun observePermission(value: Boolean) { if (!closed && permission != value) { permission=value; restart() } }
    fun optIn() { if (!closed && source != null && !optedIn) { optedIn=true; restart() } }
    fun close() { closed=true; optedIn=false; query=""; generation++; job?.cancel(); loading=false; result=DeviceContactSearchResult.Disabled }
    private fun restart() {
        val ticket=++generation
        job?.cancel(); loading=false
        result=when { !optedIn || source==null -> DeviceContactSearchResult.Disabled
            !permission -> DeviceContactSearchResult.PermissionRequired
            else -> DeviceContactSearchResult.QueryRequired }
        if (!optedIn || !permission || source==null || closed) return
        val text=query
        job=scope.launch {
            delay(350)
            if(ticket!=generation || closed) return@launch
            loading=true
            val answer=try { source.search(text, enabled=true) }
                catch(e:CancellationException) { throw e }
                catch(_:Exception) { DeviceContactSearchResult.Unavailable }
            ensureActive()
            if(ticket==generation && !closed && optedIn && permission) {
                result=if(answer is DeviceContactSearchResult.Ready) answer.copy(items=answer.items.distinct()) else answer
                loading=false
            }
        }
    }
}

/** Additions only; existing owned guests and their opaque refs never enter this presentation. */
@Composable
internal fun PhoneGuestPicker(rows: List<MeetingCreationGuestFields>, enabled: Boolean,
    source: DeviceContactSuggestionSource?, contactsPermissionGranted: Boolean, requestContactsPermission: () -> Unit,
    onAdd: (String, String?) -> Unit, onDone: ((Boolean) -> Unit) -> Unit, onClose: () -> Unit) {
    val focusManager=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val title=stringResource(R.string.guest_picker_title)
    val scope=rememberCoroutineScope()
    val session=remember(source) { GuestPickerSearchSession(source,scope) }
    var finishing by remember(session) { mutableStateOf(false) }
    var flushFailed by remember(session) { mutableStateOf(false) }
    var closed by remember(session) { mutableStateOf(false) }
    fun close() { if(!finishing) { closed=true; session.close(); onClose() } }
    DisposableEffect(session) { onDispose { closed=true; session.close() } }
    LaunchedEffect(session,contactsPermissionGranted) { session.observePermission(contactsPermissionGranted) }
    BackHandler { close() }
    val active=enabled && !finishing && !closed
    val proseStyle=LocalTextStyle.current.copy(textDirection=TextDirection.ContentOrLtr)
    Surface(Modifier.fillMaxSize().semantics {paneTitle=title}) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=::close,enabled=!finishing) {Text(stringResource(R.string.guest_picker_back))}
                Text(title,Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleLarge)
                TextButton(enabled=active,onClick={finishing=true;flushFailed=false;onDone { saved ->
                    if(!closed) {finishing=false;if(saved) close() else flushFailed=true}
                }}) {Text(stringResource(R.string.phone_event_done))}
            }
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(session.query,{session.changeQuery(it)},enabled=active,singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email,imeAction=ImeAction.Done),
                        keyboardActions=KeyboardActions(onDone={focusManager.clearFocus();keyboard?.hide()}),
                        label={Text(stringResource(R.string.guest_picker_search))},textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.ContentOrLtr),
                        modifier=Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled=active && session.query.isNotEmpty(),onClick={session.changeQuery("")}) {Text(stringResource(R.string.guest_picker_clear))}
                        TextButton(enabled=active && session.query.isNotEmpty(),onClick={onAdd(session.query,null);session.changeQuery("")}) {Text(stringResource(R.string.guest_picker_add_email))}
                    }
                    Text(stringResource(R.string.guest_picker_manual_help),style=MaterialTheme.typography.bodySmall.copy(textDirection=TextDirection.ContentOrLtr))
                }
                if(source!=null) item {
                    if(!session.optedIn) TextButton(enabled=active,onClick={session.optIn();if(!contactsPermissionGranted) requestContactsPermission()}) {Text(stringResource(R.string.guest_picker_enable_contacts))}
                    else if(!contactsPermissionGranted) {
                        Text(stringResource(R.string.guest_picker_permission_help),style=proseStyle)
                        TextButton(enabled=active,onClick=requestContactsPermission) {Text(stringResource(R.string.guest_picker_permission_retry))}
                    }
                }
                if(session.optedIn && contactsPermissionGranted) {
                    if(session.loading) item {Text(stringResource(R.string.guest_picker_loading),Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
                    else when(val result=session.result) {
                        is DeviceContactSearchResult.Ready -> {
                            item {Text(stringResource(if(result.items.isEmpty()) R.string.guest_picker_no_results else R.string.guest_picker_results),style=MaterialTheme.typography.titleSmall)}
                            items(result.items) { candidate ->
                                // Existing raw drafts store names as String: null becomes empty only at append.
                                // Keep both source tuples visible; this feedback may mark both as added.
                                val alreadyAdded=rows.any {it.email==candidate.email && it.name==candidate.name.orEmpty()}
                                OutlinedButton(enabled=active && !alreadyAdded,onClick={onAdd(candidate.email,candidate.name)},modifier=Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        candidate.name?.takeIf(String::isNotEmpty)?.let {Text(it)}
                                        Text(candidate.email,style=LocalTextStyle.current.copy(textDirection=TextDirection.ContentOrLtr))
                                        if(alreadyAdded) Text(stringResource(R.string.guest_picker_added))
                                    }
                                }
                            }
                            if(result.truncated) item {Text(stringResource(R.string.guest_picker_refine),style=proseStyle)}
                        }
                        DeviceContactSearchResult.Unavailable, DeviceContactSearchResult.LimitExceeded -> item {Text(stringResource(R.string.guest_picker_unavailable),style=proseStyle)}
                        DeviceContactSearchResult.PermissionRequired -> item {Text(stringResource(R.string.guest_picker_permission_help),style=proseStyle)}
                        DeviceContactSearchResult.InvalidQuery -> item {Text(stringResource(R.string.guest_picker_invalid_query),style=proseStyle)}
                        else -> item {Text(stringResource(R.string.guest_picker_query_help),style=proseStyle)}
                    }
                }
                if(rows.isNotEmpty()) {
                    item {Text(stringResource(R.string.guest_picker_draft_rows),style=MaterialTheme.typography.titleSmall)}
                    items(rows,key={it.rowId}) {row->Column {Text(row.email,style=LocalTextStyle.current.copy(textDirection=TextDirection.ContentOrLtr));if(row.name.isNotEmpty()) Text(row.name)}}
                }
                if(flushFailed) item {Text(stringResource(R.string.guest_picker_flush_failed),style=proseStyle,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
            }
        }
    }
}
