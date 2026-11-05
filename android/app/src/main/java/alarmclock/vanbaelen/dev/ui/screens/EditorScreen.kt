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
import alarmclock.vanbaelen.dev.core.Sunrise
import alarmclock.vanbaelen.dev.core.Timeline
import alarmclock.vanbaelen.dev.core.Timing
import alarmclock.vanbaelen.dev.core.Weekday
import alarmclock.vanbaelen.dev.core.afterDismiss
import alarmclock.vanbaelen.dev.core.atOffset
import alarmclock.vanbaelen.dev.core.signedOffset
import alarmclock.vanbaelen.dev.data.Problem
import alarmclock.vanbaelen.dev.data.SaveResult
import alarmclock.vanbaelen.dev.data.ScriptRow
import alarmclock.vanbaelen.dev.data.SyncStateRow
import alarmclock.vanbaelen.dev.data.problemOf
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.AppType
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.tokens
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** What the bottom area of the alarm detail shows (docs/design.md, Alarm detail). */
enum class DetailMode { VIEW, PICK, PLACE }

/** A row of the Wake-up tab that opens its editor right below it. */
enum class DetailField { NAME, REPEAT, SNOOZE, SUNRISE }

/** The two tabs of an alarm: its actions on the timeline, and how it wakes you. */
enum class DetailTab { TIMELINE, WAKE }

/** An action being placed: [key] null for a new one, [offset] in signed minutes, [delay] after dismiss. */
data class Placing(
    val key: String?,
    val scriptEntityId: String,
    val afterDismiss: Boolean,
    val offset: Int,
    val delay: Int,
    val enabled: Boolean,
)

/** Detail state; one instance, reset by [open] each time an alarm opens (AFR-ALM-02/03). */
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    /** True while the detail is shown; survives configuration changes. */
    val isOpen = MutableStateFlow(false)
    val draft = MutableStateFlow(Drafts.empty())
    /** The draft as opened, to tell whether anything changed. */
    val base = MutableStateFlow(Drafts.empty())
    /** The alarm as loaded (null for a new one). */
    var original: Alarm? = null
        private set
    val sound = MutableStateFlow<String?>(null)
    val baseSound = MutableStateFlow<String?>(null)
    /** Vibrate while ringing; kept on this phone like the sound. */
    val vibrate = MutableStateFlow(true)
    val baseVibrate = MutableStateFlow(true)
    /** Sunrise minutes before the alarm (0 = off); kept on this phone like the sound. */
    val sunrise = MutableStateFlow(0)
    val baseSunrise = MutableStateFlow(0)
    /** How far the sunrise goes towards white, in percent; kept on this phone like the sound. */
    val bright = MutableStateFlow(100)
    val baseBright = MutableStateFlow(100)
    val tab = MutableStateFlow(DetailTab.TIMELINE)
    val mode = MutableStateFlow(DetailMode.VIEW)
    val placing = MutableStateFlow<Placing?>(null)
    val inline = MutableStateFlow<DetailField?>(null)
    /** Anything differs from what was opened: Save shows, and leaving asks first. */
    val changed: StateFlow<Boolean> = combine(
        combine(draft, base) { a, b -> a != b },
        combine(sound, baseSound) { a, b -> a != b },
        combine(vibrate, baseVibrate) { a, b -> a != b },
        combine(sunrise, baseSunrise) { a, b -> a != b },
        combine(bright, baseBright) { a, b -> a != b },
    ) { a, b, c, d, e -> a || b || c || d || e }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Action key to flash, with a counter so the same card can flash again. */
    val flash = MutableStateFlow<Pair<String, Int>?>(null)
    val errors = MutableStateFlow<Map<String, DraftError>>(emptyMap())
    val saving = MutableStateFlow(false)
    val conflict = MutableStateFlow(false)
    val warnings = MutableStateFlow<List<AlarmWarning>>(emptyList())
    val loadingScripts = MutableStateFlow(false)
    val scriptProblem = MutableStateFlow<Problem?>(null)
    val scripts: StateFlow<List<ScriptRow>> = app.repository.scripts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val syncState: StateFlow<SyncStateRow> = app.repository.syncState.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStateRow())
    /** Id of the alarm the last save stored (or queued), so landscape can keep it selected. */
    var savedId: String? = null
        private set
    private var session = 0
    private var flashes = 0

    /** Opens [alarm] (null: a new one); [pick] starts in pick mode ("With actions"). */
    fun open(alarm: Alarm?, pick: Boolean) {
        val current = ++session
        original = alarm
        val d = alarm?.let(Drafts::edit) ?: Drafts.empty()
        draft.value = d
        base.value = d
        sound.value = null
        baseSound.value = null
        vibrate.value = true
        baseVibrate.value = true
        sunrise.value = 0
        baseSunrise.value = 0
        bright.value = 100
        baseBright.value = 100
        tab.value = DetailTab.TIMELINE
        errors.value = emptyMap()
        saving.value = false
        conflict.value = false
        warnings.value = emptyList()
        mode.value = if (pick) DetailMode.PICK else DetailMode.VIEW
        placing.value = null
        inline.value = null
        flash.value = null
        isOpen.value = true
        viewModelScope.launch {
            if (alarm != null) {
                val local = app.db.dao().sounds().firstOrNull { it.alarmId == alarm.id }
                if (current != session) return@launch
                sound.value = local?.soundUri
                baseSound.value = local?.soundUri
                vibrate.value = local?.vibrate ?: true
                baseVibrate.value = vibrate.value
                sunrise.value = local?.sunrise ?: 0
                baseSunrise.value = sunrise.value
                bright.value = local?.sunriseBright ?: 100
                baseBright.value = bright.value
            }
            loadingScripts.value = true
            val problem = app.repository.refreshScripts()
            if (current == session) scriptProblem.value = problem
            loadingScripts.value = false
        }
    }

    fun close() {
        session++
        isOpen.value = false
    }

    fun update(transform: (Draft) -> Draft) {
        draft.value = transform(draft.value)
        if (errors.value.isNotEmpty()) errors.value = Drafts.validate(draft.value)
    }

    fun toggleInline(field: DetailField) {
        inline.value = if (inline.value == field) null else field
    }

    /** Switching tabs drops an unplaced action, like Cancel. */
    fun showTab(next: DetailTab) {
        if (next == tab.value) return
        cancelMode()
        inline.value = null
        tab.value = next
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
        if (alarm != null) {
            draft.value = Drafts.edit(alarm)
            base.value = draft.value
        }
    }

    fun startPick() {
        inline.value = null
        placing.value = null
        mode.value = DetailMode.PICK
    }

    /** A picked script starts 15 minutes before the alarm. */
    fun pick(script: ScriptRow) {
        placing.value = Placing(null, script.entityId, afterDismiss = false, offset = Timeline.PICK_OFFSET, delay = 0, enabled = true)
        mode.value = DetailMode.PLACE
    }

    fun edit(action: DraftAction) {
        inline.value = null
        placing.value = Placing(
            key = action.key,
            scriptEntityId = action.scriptEntityId,
            afterDismiss = action.timing == Timing.AFTER_DISMISS,
            offset = action.signedOffset ?: Timeline.PICK_OFFSET,
            delay = action.delayMinutes ?: 0,
            enabled = action.enabled,
        )
        mode.value = DetailMode.PLACE
    }

    fun movePlacing(transform: (Placing) -> Placing) {
        placing.value = placing.value?.let(transform)
    }

    fun cancelMode() {
        mode.value = DetailMode.VIEW
        placing.value = null
    }

    /** The action as it would be after placing [p]. */
    fun placed(p: Placing): DraftAction {
        val start = draft.value.actions.firstOrNull { it.key == p.key } ?: Drafts.newAction(p.scriptEntityId)
        val moved = if (p.afterDismiss) start.afterDismiss(p.delay) else start.atOffset(p.offset)
        return moved.copy(enabled = p.enabled)
    }

    fun commitPlace() {
        val p = placing.value ?: return
        val a = placed(p)
        update { d -> d.copy(actions = if (p.key != null) d.actions.map { if (it.key == p.key) a else it } else d.actions + a) }
        mode.value = DetailMode.VIEW
        placing.value = null
        flash.value = a.key to ++flashes
    }

    fun removePlacing() {
        val key = placing.value?.key ?: return
        update { d -> d.copy(actions = d.actions.filter { it.key != key }) }
        cancelMode()
    }

    /** Result: null while staying in the detail, otherwise the message for the list. */
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
                    savedId = result.alarm.id
                    saveSound(result.alarm.id)
                    val ok = app.getString(if (d.id == null) R.string.alarm_created else R.string.alarm_saved, time)
                    if (result.warnings.isEmpty()) onDone(ok) else warnings.value = result.warnings
                }
                is SaveResult.Queued -> {
                    savedId = result.alarmId
                    saveSound(result.alarmId)
                    onDone(app.getString(R.string.saved_offline))
                }
                is SaveResult.Failed -> if (result.problem == Problem.CONFLICT) conflict.value = true
                else onError(Format.problem(app, result.problem, result.message))
            }
        }
    }

    private suspend fun saveSound(alarmId: String) {
        app.repository.setLocal(alarmId, sound.value, vibrate.value, sunrise.value, bright.value)
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

/**
 * The alarm detail: header, the Timeline and Wake-up tabs, and the bottom area for adding and
 * placing actions (docs/design.md). [onClose] gets the message for the list, if any.
 */
@Composable
fun AlarmDetail(
    vm: EditorViewModel,
    anchors: MorphAnchors,
    cardsAlpha: () -> Float,
    railAlpha: () -> Float,
    onClose: (String?) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val t = tokens
    val draft by vm.draft.collectAsState()
    val changed by vm.changed.collectAsState()
    val tab by vm.tab.collectAsState()
    val mode by vm.mode.collectAsState()
    val placing by vm.placing.collectAsState()
    val flash by vm.flash.collectAsState()
    val errors by vm.errors.collectAsState()
    val saving by vm.saving.collectAsState()
    val conflict by vm.conflict.collectAsState()
    val warnings by vm.warnings.collectAsState()
    val scripts by vm.scripts.collectAsState()
    val loadingScripts by vm.loadingScripts.collectAsState()
    val scriptProblem by vm.scriptProblem.collectAsState()
    val state by vm.syncState.collectAsState()
    var pickTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val isNew = draft.id == null
    val back: () -> Unit = {
        when {
            mode != DetailMode.VIEW -> vm.cancelMode()
            changed -> confirmDiscard = true
            else -> onClose(null)
        }
    }
    BackHandler(onBack = back)

    val days = Format.days(context, draft.repeatDays)

    Box(
        modifier
            .fillMaxSize()
            .background(t.bg)
            // Keeps touches away from the list underneath while both are shown.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Breadcrumb, Save and the ⋯ menu.
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = back, role = Role.Button).heightIn(min = 44.dp).padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.back, null, tint = t.muted, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.title), style = AppType.body, color = t.muted)
                }
                Text("/", style = AppType.body, color = t.muted)
                Text(Format.time(context, draft.time), style = AppType.bodyStrong, color = t.ink, modifier = Modifier.padding(horizontal = 10.dp))
                Spacer(Modifier.weight(1f))
                // Hidden while picking or placing, so an unplaced action is never dropped by saving.
                if ((isNew || changed) && mode == DetailMode.VIEW) {
                    Box(
                        Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(t.accent)
                            .clickable(enabled = !saving, role = Role.Button) { vm.save(onClose, onMessage) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(if (saving) R.string.saving else R.string.save), style = AppType.body, fontWeight = FontWeight.SemiBold, color = t.onAccent)
                    }
                }
                if (!isNew) {
                    Box {
                        RoundIconButton(AppIcons.more, stringResource(R.string.more), onClick = { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; vm.duplicate() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = t.danger) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                }
            }

            // Time, name · days, tabs.
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(t.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Format.time(context, draft.time),
                        style = AppType.detailTime,
                        color = t.ink,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { pickTime = true },
                    )
                    Spacer(Modifier.width(12.dp).weight(1f))
                    Text(
                        "${draft.label.ifBlank { stringResource(R.string.label) }} · $days",
                        style = AppType.secondary,
                        color = t.muted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Segmented(
                    first = stringResource(R.string.tab_timeline),
                    second = stringResource(R.string.tab_wake),
                    secondSelected = tab == DetailTab.WAKE,
                    onSelect = { wake -> vm.showTab(if (wake) DetailTab.WAKE else DetailTab.TIMELINE) },
                )
            }

            if (conflict) Banner(stringResource(R.string.conflict), BannerKind.ERROR, stringResource(R.string.reload)) { vm.reload() }
            if (errors.isNotEmpty()) {
                Banner(errors.values.distinct().joinToString("\n") { Format.draftError(context, it) }, BannerKind.ERROR)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == DetailTab.WAKE) {
                    Rise(Modifier.fillMaxSize()) { WakeSettings(vm, landscape = false) }
                } else if (mode == DetailMode.VIEW && draft.actions.isEmpty()) {
                    Rise(Modifier.fillMaxSize()) { AddActions(vm::startPick) }
                } else {
                    TimelineBody(
                        draft = draft,
                        scripts = scripts,
                        mode = mode,
                        placing = placing,
                        flash = flash,
                        anchors = anchors,
                        cardsAlpha = cardsAlpha,
                        railAlpha = railAlpha,
                        onEdit = vm::edit,
                        onDrag = { o -> vm.movePlacing { it.copy(offset = o) } },
                    )
                }
            }

            if (tab == DetailTab.TIMELINE) Column(
                Modifier.fillMaxWidth().drawBehind { drawLine(t.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
            ) {
                when (mode) {
                    DetailMode.VIEW -> if (draft.actions.isNotEmpty()) {
                        key("view") {
                            Rise {
                                ViewBar(draft, scripts.isEmpty(), loadingScripts, scriptProblem, state.actionLabel, vm::startPick)
                            }
                        }
                    }
                    DetailMode.PICK -> key("pick") {
                        Rise { PickBar(scripts, loadingScripts, scriptProblem, state.actionLabel, vm::pick, vm::cancelMode) }
                    }
                    DetailMode.PLACE -> placing?.let { p ->
                        key("place") { Rise { PlaceBar(p, draft, scripts, state.timeZone, vm, onMessage) } }
                    }
                }
            }
        }
    }

    if (pickTime) {
        TimeDialog(runCatching { LocalTime.parse(draft.time) }.getOrDefault(LocalTime.of(7, 0)), { pickTime = false }) { time ->
            pickTime = false
            vm.update { it.copy(time = "%02d:%02d".format(time.hour, time.minute)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.delete_hint)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onClose, onMessage) }) {
                    Text(stringResource(R.string.yes_delete), color = t.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onClose(null) }) { Text(stringResource(R.string.discard), color = t.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
    if (warnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.warnings.value = emptyList(); onClose(null) },
            title = { Text(stringResource(R.string.saved_with_warnings)) },
            text = { Text(warnings.joinToString("\n\n") { w -> warningText(context, w, draft, scripts) }) },
            confirmButton = { TextButton(onClick = { vm.warnings.value = emptyList(); onClose(null) }) { Text(stringResource(R.string.close)) } },
        )
    }
}

/** Name, repeat, snooze or sunrise start, edited in place under its row. */
@Composable
private fun InlineEditorView(field: DetailField, draft: Draft, defaultSnooze: Int, vm: EditorViewModel) {
    val context = LocalContext.current
    val t = tokens
    when (field) {
        DetailField.NAME -> OutlinedTextField(
            value = draft.label,
            onValueChange = { v -> vm.update { it.copy(label = v.take(Limits.LABEL_LENGTH)) } },
            placeholder = { Text(stringResource(R.string.label_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        DetailField.REPEAT -> Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DayToggles(draft.repeatDays) { day -> vm.update { Drafts.toggleDay(it, day) } }
            Hint(if (draft.repeatDays.isEmpty()) stringResource(R.string.repeat_hint) else Format.days(context, draft.repeatDays), t.muted)
        }
        DetailField.SNOOZE -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val value = draft.snoozeMinutes ?: defaultSnooze
            val set = { n: Int -> vm.update { it.copy(snoozeMinutes = n.coerceIn(Limits.SNOOZE_MIN, Limits.SNOOZE_MAX)) } }
            RoundIconButton(AppIcons.minus, stringResource(R.string.decrease), { set(value - 1) }, Modifier.border(1.dp, t.line, CircleShape), tint = t.ink, size = 18.dp)
            Text(stringResource(R.string.dur_min, value), style = AppType.bodyStrong, color = if (draft.snoozeMinutes == null) t.muted else t.ink)
            RoundIconButton(AppIcons.plus, stringResource(R.string.increase), { set(value + 1) }, Modifier.border(1.dp, t.line, CircleShape), tint = t.ink, size = 18.dp)
            Spacer(Modifier.weight(1f))
            if (draft.snoozeMinutes != null) Pill(stringResource(R.string.snooze_use_default, defaultSnooze), { vm.update { it.copy(snoozeMinutes = null) } })
        }
        DetailField.SUNRISE -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val sunrise by vm.sunrise.collectAsState()
            Sunrise.CHOICES.filter { it > 0 }.forEach { minutes ->
                val on = minutes == sunrise
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(shape)
                        .background(if (on) t.accent else t.bg)
                        .border(1.dp, if (on) t.accent else t.line, shape)
                        .clickable(role = Role.RadioButton) { vm.sunrise.value = minutes },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.dur_min, minutes), style = AppType.secondary, fontWeight = FontWeight.SemiBold, color = if (on) t.onAccent else t.ink)
                }
            }
        }
    }
}

/** Seven day toggles (circles up to 44), for Repeat. */
@Composable
internal fun DayToggles(days: List<Weekday>, onToggle: (Weekday) -> Unit) {
    val context = LocalContext.current
    val t = tokens
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Weekday.entries.forEach { day ->
            val on = day in days
            Box(
                Modifier
                    .weight(1f)
                    .sizeIn(maxWidth = 44.dp)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (on) t.accent else t.bg)
                    .border(1.dp, if (on) t.accent else t.line, CircleShape)
                    .clickable(role = Role.Checkbox) { onToggle(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(Format.dayName(context, day, TextStyle.NARROW), style = AppType.secondary, fontWeight = FontWeight.SemiBold, color = if (on) t.onAccent else t.ink)
            }
        }
    }
}

/** The empty timeline of a simple alarm: what it is, and the dashed "Add actions" card. */
@Composable
private fun AddActions(onClick: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 32.dp)) {
        Text(stringResource(R.string.simple_alarm_note), style = AppType.label, color = t.muted)
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .padding(top = 20.dp)
                .fillMaxWidth()
                .clip(shape)
                .drawBehind {
                    drawRoundRect(
                        color = t.muted,
                        cornerRadius = CornerRadius(18.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(AppIcons.plus, null, tint = t.ink, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.add_actions), style = AppType.bodyStrong, color = t.ink)
            }
            Text(stringResource(R.string.add_actions_hint), style = AppType.secondary, color = t.muted)
        }
    }
}

/**
 * How the alarm wakes you (docs/design.md, Alarm settings): name and repeat (portrait only; landscape
 * edits them on the time page), sound, sunrise and snooze. [landscape] puts the groups in two columns.
 */
@Composable
fun WakeSettings(vm: EditorViewModel, landscape: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val t = tokens
    val draft by vm.draft.collectAsState()
    val sound by vm.sound.collectAsState()
    val vibrate by vm.vibrate.collectAsState()
    val sunrise by vm.sunrise.collectAsState()
    val bright by vm.bright.collectAsState()
    val inline by vm.inline.collectAsState()
    val state by vm.syncState.collectAsState()

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
    val pickSound = {
        soundPicker.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound?.let(Uri::parse)),
        )
    }

    val editor: @Composable (DetailField) -> Unit = { field ->
        if (inline == field) key(field) { Rise(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { InlineEditorView(field, draft, state.defaultSnooze, vm) } }
    }
    val alarmGroup: @Composable () -> Unit = {
        WakeGroup(stringResource(R.string.group_alarm)) {
            WakeRow(stringResource(R.string.label), draft.label.ifBlank { stringResource(R.string.label_placeholder) }, { vm.toggleInline(DetailField.NAME) })
            editor(DetailField.NAME)
            HorizontalDivider(color = t.line)
            WakeRow(stringResource(R.string.repeat), Format.days(context, draft.repeatDays), { vm.toggleInline(DetailField.REPEAT) })
            editor(DetailField.REPEAT)
        }
    }
    val soundGroup: @Composable () -> Unit = {
        WakeGroup(stringResource(R.string.sound)) {
            WakeRow(stringResource(R.string.sound), soundName(context, sound) ?: stringResource(R.string.sound_default), pickSound)
            HorizontalDivider(color = t.line)
            WakeRow(stringResource(R.string.vibrate), "", { vm.vibrate.value = !vibrate }) {
                AppSwitch(vibrate, { vm.vibrate.value = it }, stringResource(R.string.vibrate))
            }
        }
    }
    val sunriseGroup: @Composable () -> Unit = {
        WakeGroup(stringResource(R.string.sunrise), note = stringResource(R.string.sunrise_hint)) {
            val set = { on: Boolean -> vm.sunrise.value = if (on) Sunrise.DEFAULT_MINUTES else 0 }
            WakeRow(stringResource(R.string.sunrise_screen), "", { set(sunrise == 0) }) {
                AppSwitch(sunrise > 0, set, stringResource(R.string.sunrise_screen))
            }
            if (sunrise > 0) {
                HorizontalDivider(color = t.line)
                WakeRow(stringResource(R.string.sunrise_starts), stringResource(R.string.sunrise_starts_value, sunrise), { vm.toggleInline(DetailField.SUNRISE) })
                editor(DetailField.SUNRISE)
                HorizontalDivider(color = t.line)
                BrightRow(bright) { vm.bright.value = it }
            }
        }
    }
    val snoozeGroup: @Composable () -> Unit = {
        WakeGroup(stringResource(R.string.snooze), note = stringResource(R.string.snooze_swipe_note)) {
            val value = draft.snoozeMinutes?.let { stringResource(R.string.dur_min, it) } ?: stringResource(R.string.snooze_default_value, state.defaultSnooze)
            WakeRow(stringResource(R.string.snooze_for), value, { vm.toggleInline(DetailField.SNOOZE) })
            editor(DetailField.SNOOZE)
        }
    }

    val scroll = modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp)
    if (landscape) {
        Row(scroll, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { soundGroup(); snoozeGroup() }
            Column(Modifier.weight(1f)) { sunriseGroup() }
        }
    } else {
        Column(scroll, verticalArrangement = Arrangement.spacedBy(18.dp)) { alarmGroup(); soundGroup(); sunriseGroup(); snoozeGroup() }
    }
}

/** A titled card of rows, with an optional note below it. */
@Composable
private fun WakeGroup(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(16.dp)
    Column {
        Text(
            title.uppercase(),
            style = AppType.small.copy(letterSpacing = 0.08.em),
            fontWeight = FontWeight.SemiBold,
            color = t.muted,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.line, shape), content = content)
        if (note != null) Text(note, style = AppType.small, color = t.muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
    }
}

/** A tappable row: label, current value and a chevron, or a [trailing] control such as a switch. */
@Composable
private fun WakeRow(label: String, value: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = if (trailing == null) 12.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = AppType.body, color = t.ink, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = AppType.body, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
        if (trailing != null) trailing() else Icon(AppIcons.chevron, null, tint = t.muted, modifier = Modifier.size(16.dp))
    }
}

/** Brightest point of the sunrise: a slider in 5 % steps, with the colours it reaches. */
@Composable
private fun BrightRow(bright: Int, onChange: (Int) -> Unit) {
    val t = tokens
    val strip = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.brightest_point), style = AppType.body, color = t.ink, modifier = Modifier.weight(1f))
            Text("$bright%", fontSize = 20.sp, fontWeight = FontWeight.Light, color = t.ink)
        }
        Slider(
            value = bright.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = Sunrise.BRIGHT_MIN.toFloat()..Sunrise.BRIGHT_MAX.toFloat(),
            steps = (Sunrise.BRIGHT_MAX - Sunrise.BRIGHT_MIN) / Sunrise.BRIGHT_STEP - 1,
            colors = SliderDefaults.colors(
                thumbColor = t.ink,
                activeTrackColor = t.accent,
                inactiveTrackColor = t.line,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.clip(strip).border(1.dp, t.line, strip)) {
                repeat(6) { i -> Box(Modifier.size(22.dp, 30.dp).background(Color(Sunrise.background(i / 5f * bright / 100f)))) }
            }
            Text(
                stringResource(
                    when {
                        bright >= 90 -> R.string.bright_all
                        bright <= 30 -> R.string.bright_low
                        else -> R.string.bright_mid
                    },
                ),
                style = AppType.small,
                color = t.muted,
            )
        }
    }
}

/** Scripts are loading or missing: the text to show instead of the add or pick controls. */
@Composable
internal fun scriptsMessage(loading: Boolean, problem: Problem?, actionLabel: String?): String {
    val context = LocalContext.current
    return when {
        loading -> stringResource(R.string.loading_scripts)
        problem != null -> Format.problem(context, problem)
        else -> stringResource(R.string.no_scripts, actionLabel ?: "alarm_action")
    }
}

@Composable
private fun ViewBar(draft: Draft, noScripts: Boolean, loading: Boolean, problem: Problem?, actionLabel: String?, onAdd: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 16.dp)) {
        when {
            noScripts -> Hint(scriptsMessage(loading, problem, actionLabel), t.muted, Modifier.fillMaxWidth())
            draft.actions.size >= Limits.ACTIONS_PER_ALARM -> Hint(stringResource(R.string.action_limit, Limits.ACTIONS_PER_ALARM), t.muted)
            else -> {
                SheetButton(stringResource(R.string.add_action), onAdd, Modifier.fillMaxWidth(), ButtonKind.PRIMARY, icon = AppIcons.plus)
                Text(
                    stringResource(R.string.timeline_hint),
                    style = AppType.small,
                    color = t.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PickBar(scripts: List<ScriptRow>, loading: Boolean, problem: Problem?, actionLabel: String?, onPick: (ScriptRow) -> Unit, onCancel: () -> Unit) {
    val t = tokens
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.what_happens), style = AppType.bodyStrong, color = t.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = t.muted) }
        }
        if (scripts.isEmpty()) {
            Hint(scriptsMessage(loading, problem, actionLabel), t.muted, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                scripts.forEach { s ->
                    val shape = RoundedCornerShape(16.dp)
                    Column(
                        Modifier
                            .width(92.dp)
                            .heightIn(min = 84.dp)
                            .clip(shape)
                            .background(t.surface)
                            .border(1.dp, t.line, shape)
                            .clickable(role = Role.Button) { onPick(s) }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(AppIcons.mdi(s.iconPath) ?: AppIcons.script, null, tint = t.ink, modifier = Modifier.size(20.dp))
                        Text(s.name, style = AppType.secondary, fontWeight = FontWeight.SemiBold, color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceBar(p: Placing, draft: Draft, scripts: List<ScriptRow>, timeZone: String?, vm: EditorViewModel, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val t = tokens
    val name = scriptName(scripts, p.scriptEntityId)
    val existing = draft.actions.firstOrNull { it.key == p.key }
    val candidate = vm.placed(p)
    val trial = draft.copy(actions = if (existing != null) draft.actions.map { if (it.key == p.key) candidate else it } else draft.actions + candidate)
    val missed = Drafts.missedBeforeActions(trial, timeZone ?: ZoneId.systemDefault().id, Instant.now()).any { it.key == candidate.key }
    val canRun = existing?.id != null && draft.id != null && !PendingEdits.isTempId(draft.id)

    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segmented(
            first = stringResource(R.string.around_alarm),
            second = stringResource(R.string.timing_after_dismiss),
            secondSelected = p.afterDismiss,
            onSelect = { second -> vm.movePlacing { it.copy(afterDismiss = second) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val step = { delta: Int ->
                vm.movePlacing {
                    if (it.afterDismiss) it.copy(delay = (it.delay + delta).coerceIn(Limits.DELAY_MIN, Limits.DELAY_MAX))
                    else it.copy(offset = (it.offset + delta).coerceIn(-Limits.OFFSET_MAX, Limits.OFFSET_MAX))
                }
            }
            RoundIconButton(AppIcons.up, stringResource(R.string.earlier), { step(-1) }, Modifier.border(1.dp, t.line, CircleShape), tint = t.ink, size = 18.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val readout = when {
                    p.afterDismiss && p.delay == 0 -> stringResource(R.string.readout_dismiss_now)
                    p.afterDismiss -> stringResource(R.string.readout_dismiss_after, p.delay)
                    p.offset < 0 -> stringResource(R.string.readout_before, -p.offset)
                    p.offset == 0 -> stringResource(R.string.readout_at)
                    else -> stringResource(R.string.readout_after, p.offset)
                }
                Text(readout, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = t.ink, textAlign = TextAlign.Center)
                Text(
                    if (p.afterDismiss) stringResource(R.string.runs_after_dismiss, name)
                    else stringResource(R.string.runs_at, name, Format.shifted(context, draft.time, p.offset)),
                    style = AppType.small,
                    color = t.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            RoundIconButton(AppIcons.down, stringResource(R.string.later), { step(1) }, Modifier.border(1.dp, t.line, CircleShape), tint = t.ink, size = 18.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.enabled), style = AppType.body, color = t.ink)
            AppSwitch(p.enabled, { on -> vm.movePlacing { it.copy(enabled = on) } }, stringResource(R.string.enabled))
            Spacer(Modifier.weight(1f))
            if (canRun && existing != null) Pill(stringResource(R.string.run_now), { vm.runNow(existing, onMessage) })
        }
        if (missed) {
            Text(stringResource(R.string.before_missed, -p.offset, name), style = AppType.small, color = t.warn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.key != null) SheetButton(stringResource(R.string.remove), vm::removePlacing, danger = true)
            SheetButton(stringResource(R.string.cancel), vm::cancelMode)
            SheetButton(stringResource(if (p.key == null) R.string.place_here else R.string.done), vm::commitPlace, Modifier.weight(1f), ButtonKind.ACCENT)
        }
    }
}

/** Two-way segmented control: "Around the alarm" | "After dismiss", and the Timeline | Wake-up tabs. */
@Composable
internal fun Segmented(first: String, second: String, secondSelected: Boolean, onSelect: (Boolean) -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(t.soft).padding(3.dp)) {
        listOf(false to first, true to second).forEach { (isSecond, text) ->
            val selected = isSecond == secondSelected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) t.surface else t.soft)
                    .clickable(role = Role.Tab) { onSelect(isSecond) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = AppType.secondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) t.ink else t.muted)
            }
        }
    }
}

internal fun scriptName(scripts: List<ScriptRow>, entityId: String) = scripts.firstOrNull { it.entityId == entityId }?.name ?: entityId

internal fun warningText(context: android.content.Context, w: AlarmWarning, draft: Draft, scripts: List<ScriptRow>): String {
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
