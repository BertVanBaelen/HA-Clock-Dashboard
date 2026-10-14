package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.App
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.Alarm
import alarmclock.vanbaelen.dev.core.AlarmWarning
import alarmclock.vanbaelen.dev.core.Draft
import alarmclock.vanbaelen.dev.core.DraftAction
import alarmclock.vanbaelen.dev.core.DraftError
import alarmclock.vanbaelen.dev.core.Drafts
import alarmclock.vanbaelen.dev.core.Limits
import alarmclock.vanbaelen.dev.core.PendingEdits
import alarmclock.vanbaelen.dev.core.Timing
import alarmclock.vanbaelen.dev.core.Weekday
import alarmclock.vanbaelen.dev.data.Problem
import alarmclock.vanbaelen.dev.data.SaveResult
import alarmclock.vanbaelen.dev.data.ScriptRow
import alarmclock.vanbaelen.dev.data.SyncStateRow
import alarmclock.vanbaelen.dev.data.problemOf
import alarmclock.vanbaelen.dev.ui.Format
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Editor state; mirrors the panel's AlarmEditor (AFR-ALM-02/03). */
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val draft = MutableStateFlow(Drafts.empty())
    /** The alarm as loaded (null for a new one). */
    var original: Alarm? = null
        private set
    val sound = MutableStateFlow<String?>(null)
    val errors = MutableStateFlow<Map<String, DraftError>>(emptyMap())
    val saving = MutableStateFlow(false)
    val conflict = MutableStateFlow(false)
    val warnings = MutableStateFlow<List<AlarmWarning>>(emptyList())
    val loadingScripts = MutableStateFlow(false)
    val scriptProblem = MutableStateFlow<Problem?>(null)
    val scripts: StateFlow<List<ScriptRow>> = app.repository.scripts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val syncState: StateFlow<SyncStateRow> = app.repository.syncState.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStateRow())
    private var loaded = false

    fun load(id: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            if (id != null) {
                val alarm = app.repository.effectiveAlarms().firstOrNull { it.alarm.id == id }?.alarm
                original = alarm
                if (alarm != null) draft.value = Drafts.edit(alarm)
                sound.value = app.db.dao().sounds().firstOrNull { it.alarmId == id }?.soundUri
            }
            loadingScripts.value = true
            scriptProblem.value = app.repository.refreshScripts()
            loadingScripts.value = false
        }
    }

    fun update(transform: (Draft) -> Draft) {
        draft.value = transform(draft.value)
        if (errors.value.isNotEmpty()) errors.value = Drafts.validate(draft.value)
    }

    fun duplicate() {
        original = null
        draft.value = Drafts.copy(draft.value)
    }

    fun reload() = viewModelScope.launch {
        val id = draft.value.id ?: return@launch
        conflict.value = false
        val alarm = runCatching { app.repository.reload(id) }.getOrNull()
        original = alarm
        if (alarm != null) draft.value = Drafts.edit(alarm)
    }

    /** Result: null while staying in the editor, otherwise the message for the list. */
    fun save(onDone: (String?) -> Unit, onError: (String) -> Unit) {
        val d = draft.value
        val found = Drafts.validate(d)
        errors.value = found
        if (found.isNotEmpty()) return
        viewModelScope.launch {
            saving.value = true
            val result = app.repository.save(d)
            saving.value = false
            val time = Format.time(app, d.time)
            when (result) {
                is SaveResult.Saved -> {
                    saveSound(result.alarm.id)
                    val ok = app.getString(if (d.id == null) R.string.alarm_created else R.string.alarm_saved, time)
                    if (result.warnings.isEmpty()) onDone(ok) else warnings.value = result.warnings
                }
                is SaveResult.Queued -> {
                    saveSound(result.alarmId)
                    onDone(app.getString(R.string.saved_offline))
                }
                is SaveResult.Failed -> if (result.problem == Problem.CONFLICT) conflict.value = true
                else onError(Format.problem(app, result.problem, result.message))
            }
        }
    }

    private suspend fun saveSound(alarmId: String) {
        app.repository.setSound(alarmId, sound.value)
    }

    fun delete(onDone: (String?) -> Unit, onError: (String) -> Unit) {
        val alarm = original ?: return onDone(null)
        viewModelScope.launch {
            when (val result = app.repository.delete(alarm)) {
                is SaveResult.Saved -> onDone(app.getString(R.string.alarm_deleted))
                is SaveResult.Queued -> onDone(app.getString(R.string.saved_offline))
                is SaveResult.Failed -> if (result.problem == Problem.CONFLICT) conflict.value = true
                else onError(Format.problem(app, result.problem, result.message))
            }
        }
    }

    fun runNow(action: DraftAction, onMessage: (String) -> Unit) {
        val alarmId = draft.value.id ?: return
        val actionId = action.id ?: return
        viewModelScope.launch {
            val text = try {
                val r = app.repository.runNow(alarmId, actionId)
                app.getString(R.string.run_result, listOfNotNull(r.result, r.reason).joinToString(": "))
            } catch (e: Exception) {
                Format.problem(app, problemOf(e))
            }
            onMessage(text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(id: String?, onDone: (String?) -> Unit) {
    val vm: EditorViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(id) { vm.load(id) }
    val draft by vm.draft.collectAsState()
    val errors by vm.errors.collectAsState()
    val saving by vm.saving.collectAsState()
    val conflict by vm.conflict.collectAsState()
    val warnings by vm.warnings.collectAsState()
    val scripts by vm.scripts.collectAsState()
    val loadingScripts by vm.loadingScripts.collectAsState()
    val scriptProblem by vm.scriptProblem.collectAsState()
    val state by vm.syncState.collectAsState()
    val sound by vm.sound.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val show: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            vm.sound.value = uri?.toString()
        }
    }

    val isNew = draft.id == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.new_alarm else R.string.edit_alarm)) },
                navigationIcon = {
                    IconButton(onClick = { onDone(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) }
                },
                actions = {
                    TextButton(enabled = !saving, onClick = { vm.save(onDone, show) }) {
                        Text(stringResource(if (saving) R.string.saving else R.string.save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (conflict) {
                Banner(stringResource(R.string.conflict), BannerKind.ERROR, stringResource(R.string.reload)) { vm.reload() }
            }
            TextButton(onClick = { pickTime = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(Format.time(context, draft.time), fontSize = 64.sp)
            }
            errors[Drafts.ALARM_KEY]?.let {
                Hint(Format.draftError(context, it), MaterialTheme.colorScheme.error, Modifier.padding(horizontal = 16.dp))
            }
            OutlinedTextField(
                value = draft.label,
                onValueChange = { v -> vm.update { it.copy(label = v.take(Limits.LABEL_LENGTH)) } },
                label = { Text(stringResource(R.string.label)) },
                placeholder = { Text(stringResource(R.string.label_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            SectionTitle(stringResource(R.string.repeat))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Weekday.entries.forEach { day ->
                    FilterChip(
                        selected = day in draft.repeatDays,
                        onClick = { vm.update { Drafts.toggleDay(it, day) } },
                        label = { Text(Format.dayName(context, day)) },
                    )
                }
            }
            Hint(
                if (draft.repeatDays.isEmpty()) stringResource(R.string.repeat_hint) else Format.days(context, draft.repeatDays),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = { v -> vm.update { it.copy(enabled = v) } })
            }
            OutlinedTextField(
                value = draft.snoozeMinutes?.toString() ?: "",
                onValueChange = { v -> vm.update { it.copy(snoozeMinutes = v.filter(Char::isDigit).take(2).toIntOrNull()) } },
                label = { Text(stringResource(R.string.snooze_minutes)) },
                placeholder = { Text(stringResource(R.string.snooze_default, state.defaultSnooze)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sound))
                    Hint(soundName(context, sound) ?: stringResource(R.string.sound_default))
                }
                TextButton(onClick = {
                    soundPicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound?.let(Uri::parse)),
                    )
                }) { Text(stringResource(R.string.choose)) }
            }

            SectionTitle(stringResource(R.string.actions))
            if (draft.actions.isEmpty()) Hint(stringResource(R.string.no_actions), modifier = Modifier.padding(horizontal = 16.dp))
            val missedBefore = Drafts.missedBeforeActions(draft, state.timeZone ?: ZoneId.systemDefault().id, Instant.now()).map { it.key }.toSet()
            draft.actions.forEach { action ->
                ActionCard(
                    action = action,
                    scripts = scripts,
                    error = errors[action.key]?.let { Format.draftError(context, it) },
                    missed = action.key in missedBefore,
                    canRun = action.id != null && draft.id != null && !PendingEdits.isTempId(draft.id!!),
                    onChange = { changed -> vm.update { d -> d.copy(actions = d.actions.map { if (it.key == action.key) changed else it }) } },
                    onRemove = { vm.update { d -> d.copy(actions = d.actions.filter { it.key != action.key }) } },
                    onRun = { vm.runNow(action, show) },
                )
            }
            when {
                loadingScripts && scripts.isEmpty() -> Hint(stringResource(R.string.loading_scripts), modifier = Modifier.padding(16.dp))
                scripts.isEmpty() && scriptProblem == null ->
                    Hint(stringResource(R.string.no_scripts, state.actionLabel ?: "alarm"), modifier = Modifier.padding(16.dp))
                scripts.isEmpty() -> Hint(Format.problem(context, scriptProblem!!), modifier = Modifier.padding(16.dp))
            }
            if (draft.actions.size < Limits.ACTIONS_PER_ALARM) {
                OutlinedButton(
                    onClick = { vm.update { it.copy(actions = it.actions + Drafts.newAction(scripts.firstOrNull()?.entityId ?: "")) } },
                    modifier = Modifier.padding(16.dp),
                ) { Text(stringResource(R.string.add_action)) }
            } else {
                Hint(stringResource(R.string.action_limit, Limits.ACTIONS_PER_ALARM), modifier = Modifier.padding(16.dp))
            }

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isNew) {
                    OutlinedButton(onClick = { vm.duplicate() }) { Text(stringResource(R.string.duplicate)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (pickTime) {
        TimeDialog(runCatching { LocalTime.parse(draft.time) }.getOrDefault(LocalTime.of(7, 0)), { pickTime = false }) { t ->
            pickTime = false
            vm.update { it.copy(time = "%02d:%02d".format(t.hour, t.minute)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.delete_hint)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onDone, show) }) {
                    Text(stringResource(R.string.yes_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (warnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.warnings.value = emptyList(); onDone(null) },
            title = { Text(stringResource(R.string.saved_with_warnings)) },
            text = { Text(warnings.joinToString("\n\n") { w -> warningText(context, w, draft, scripts) }) },
            confirmButton = { TextButton(onClick = { vm.warnings.value = emptyList(); onDone(null) }) { Text(stringResource(R.string.close)) } },
        )
    }
}

private fun scriptName(scripts: List<ScriptRow>, entityId: String) = scripts.firstOrNull { it.entityId == entityId }?.name ?: entityId

private fun warningText(context: android.content.Context, w: AlarmWarning, draft: Draft, scripts: List<ScriptRow>): String {
    if (w.code != "before_missed") return w.message
    val action = draft.actions.firstOrNull { it.id == w.alarmActionId }
    return if (action != null) {
        context.getString(R.string.before_missed, action.offsetMinutes ?: 0, scriptName(scripts, action.scriptEntityId))
    } else {
        w.message
    }
}

internal fun soundName(context: android.content.Context, uri: String?): String? = uri?.let {
    runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull()
}

@Composable
private fun ActionCard(
    action: DraftAction,
    scripts: List<ScriptRow>,
    error: String?,
    missed: Boolean,
    canRun: Boolean,
    onChange: (DraftAction) -> Unit,
    onRemove: () -> Unit,
    onRun: () -> Unit,
) {
    val context = LocalContext.current
    var scriptMenu by remember { mutableStateOf(false) }
    var timingMenu by remember { mutableStateOf(false) }
    val unavailable = scripts.isNotEmpty() && action.scriptEntityId.isNotEmpty() && scripts.none { it.entityId == action.scriptEntityId }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.script), style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(onClick = { scriptMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (action.scriptEntityId.isEmpty()) stringResource(R.string.pick_script) else scriptName(scripts, action.scriptEntityId))
                }
                DropdownMenu(expanded = scriptMenu, onDismissRequest = { scriptMenu = false }) {
                    scripts.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name) },
                            onClick = { scriptMenu = false; onChange(action.copy(scriptEntityId = s.entityId)) },
                        )
                    }
                }
            }
            if (unavailable) {
                Hint(stringResource(R.string.unavailable) + ": " + stringResource(R.string.unavailable_hint), MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.timing), style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(onClick = { timingMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(Format.timing(context, action.timing)) }
                DropdownMenu(expanded = timingMenu, onDismissRequest = { timingMenu = false }) {
                    Timing.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(Format.timing(context, t)) }, onClick = { timingMenu = false; onChange(Drafts.withTiming(action, t)) })
                    }
                }
            }
            if (action.timing.needsOffset) {
                OutlinedTextField(
                    value = action.offsetMinutes?.toString() ?: "",
                    onValueChange = { v -> onChange(action.copy(offsetMinutes = v.filter(Char::isDigit).take(4).toIntOrNull())) },
                    label = { Text(stringResource(R.string.minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (action.timing == Timing.AFTER_DISMISS) {
                OutlinedTextField(
                    value = action.delayMinutes?.toString() ?: "",
                    onValueChange = { v -> onChange(action.copy(delayMinutes = v.filter(Char::isDigit).take(3).toIntOrNull())) },
                    label = { Text(stringResource(R.string.delay)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (missed) {
                Hint(
                    stringResource(R.string.before_missed, action.offsetMinutes ?: 0, scriptName(scripts, action.scriptEntityId)),
                    MaterialTheme.colorScheme.tertiary,
                )
            }
            error?.let { Hint(it, MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), Modifier.weight(1f))
                Switch(checked = action.enabled, onCheckedChange = { onChange(action.copy(enabled = it)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (canRun) TextButton(onClick = onRun) { Text(stringResource(R.string.run_now)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
            }
        }
    }
}

