package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.Routes
import alarmclock.vanbaelen.dev.core.Alarm
import alarmclock.vanbaelen.dev.core.Draft
import alarmclock.vanbaelen.dev.core.DraftAction
import alarmclock.vanbaelen.dev.core.Drafts
import alarmclock.vanbaelen.dev.core.Limits
import alarmclock.vanbaelen.dev.core.PendingEdits
import alarmclock.vanbaelen.dev.core.Timeline
import alarmclock.vanbaelen.dev.core.signedOffset
import alarmclock.vanbaelen.dev.data.Problem
import alarmclock.vanbaelen.dev.data.ScriptRow
import alarmclock.vanbaelen.dev.ui.AlarmItem
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.AppType
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import alarmclock.vanbaelen.dev.ui.tokens
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

// Horizontal timeline geometry, in dp (docs/design.md, Landscape).
private const val PAD_X = 24f
private const val MARK = 40f
private const val LANE_GAP = 44f
private const val AXIS_Y = 92f
private const val LABEL_Y = 98f
private const val HEIGHT_TWO_LANES = 118f
private const val HEIGHT_THREE_LANES = 160f
/** Tops of the lanes: 0 and 1 hang above the axis, 2 below the clock labels. */
private val LANE_TOP = floatArrayOf(44f, 0f, 118f)

/**
 * Landscape alarms (docs/design.md, Landscape): the rail, a 220 dp list and the open alarm's detail;
 * the time page takes the whole screen. Uses the same [EditorViewModel] as portrait, without the morph.
 */
@Composable
fun LandscapeAlarms(vm: MainViewModel, nav: NavController, editor: EditorViewModel, snackbar: SnackbarHostState) {
    val t = tokens
    val scope = rememberCoroutineScope()
    val items by vm.items.collectAsState()
    val open by editor.isOpen.collectAsState()
    val draft by editor.draft.collectAsState()
    val mode by editor.mode.collectAsState()
    val scripts by editor.scripts.collectAsState()
    val changed by editor.changed.collectAsState()
    var timePage by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    // The open alarm was opened by this screen, not picked by the user.
    var auto by remember { mutableStateOf(false) }
    // The alarm to select again once the list has caught up with a save.
    var follow by remember { mutableStateOf<String?>(null) }
    // The alarm just deleted: never selected again, even while the list still shows it.
    var deleted by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(open, items, follow, deleted) {
        if (open || items.isEmpty()) return@LaunchedEffect
        val id = follow
        // Restarted by every list update, so this waits until the saved or deleted alarm has caught up in the list.
        if (id != null || deleted != null) delay(300)
        val target = items.firstOrNull { it.alarm.id == id } ?: items.firstOrNull { it.alarm.id != deleted } ?: return@LaunchedEffect
        auto = true
        editor.open(target.alarm, pick = false)
        follow = null
        deleted = null
    }
    DisposableEffect(Unit) {
        onDispose {
            // Rotating back to portrait with an untouched auto-opened alarm lands on the list.
            if (auto && editor.isOpen.value && !editor.changed.value && editor.mode.value == DetailMode.VIEW) editor.close()
        }
    }

    fun guarded(action: () -> Unit) {
        if (open && changed) discard = action else action()
    }
    fun select(alarm: Alarm) {
        if (open && draft.id == alarm.id) return
        guarded {
            auto = false
            editor.open(alarm, pick = false)
        }
    }
    val onSaved: (String?) -> Unit = { message ->
        message?.let(vm::message)
        follow = editor.savedId
        editor.close()
    }
    val onDeleted: (String?) -> Unit = { message ->
        message?.let(vm::message)
        deleted = editor.draft.value.id
        editor.close()
    }
    val onMessage: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    BackHandler(enabled = timePage || (open && mode != DetailMode.VIEW)) {
        if (timePage) timePage = false else editor.cancelMode()
    }

    Box(Modifier.fillMaxSize().background(t.bg)) {
        if (timePage && open) {
            LandTime(
                draft = draft,
                scripts = scripts,
                onCancel = { timePage = false },
                onDone = { d ->
                    editor.update { it.copy(time = d.time, repeatDays = d.repeatDays, label = d.label) }
                    timePage = false
                },
            )
        } else {
            Row(Modifier.fillMaxSize()) {
                LandscapeRail(Routes.LIST, nav)
                AlarmColumn(
                    vm = vm,
                    nav = nav,
                    items = items,
                    selectedId = if (open) draft.id else null,
                    onSelect = { select(it) },
                    onNew = {
                        guarded {
                            auto = false
                            editor.open(null, pick = false)
                        }
                    },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
                ) {
                    if (open) LandDetail(editor, onTime = { timePage = true }, onSaved = onSaved, onDeleted = onDeleted, onMessage = onMessage)
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 72.dp),
        )
    }

    discard?.let { action ->
        AlertDialog(
            onDismissRequest = { discard = null },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_text)) },
            confirmButton = {
                TextButton(onClick = { discard = null; action() }) { Text(stringResource(R.string.discard), color = t.danger) }
            },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
}

/** The list column: title, sync and add, the banners and compact alarm cards. */
@Composable
private fun AlarmColumn(
    vm: MainViewModel,
    nav: NavController,
    items: List<AlarmItem>,
    selectedId: String?,
    onSelect: (Alarm) -> Unit,
    onNew: () -> Unit,
) {
    val t = tokens
    val state by vm.syncState.collectAsState()
    val account by vm.account.collectAsState()
    val missed by vm.missed.collectAsState()
    val problems by vm.problems.collectAsState()
    val pendingEdits by vm.pendingEdits.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val update by vm.updateState.collectAsState()
    // The mini timelines report positions for the portrait morph; nothing reads them here.
    val anchors = remember { MorphAnchors() }

    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .drawBehind { drawLine(t.line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.title),
                style = AppType.bodyStrong.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = t.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (syncing) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = t.muted, strokeWidth = 2.dp)
                }
            } else {
                RoundIconButton(AppIcons.sync, stringResource(R.string.sync_now), vm::sync)
            }
            RoundIconButton(AppIcons.plus, stringResource(R.string.add_alarm), onNew, background = t.soft, tint = t.ink, size = 18.dp)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Banners(vm, nav, state, account.needsLogin, missed.size, missed.lastOrNull(), problems.size, problems.firstOrNull()?.seq, update)
                SyncStatus(state.lastSyncAt, state.problem, pendingEdits)
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_alarms),
                        textAlign = TextAlign.Center,
                        style = AppType.body,
                        color = t.muted,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
            items(items, key = { it.alarm.id }) { item ->
                CompactCard(item, vm, anchors, current = item.alarm.id == selectedId, onClick = { onSelect(item.alarm) })
            }
        }
    }
}

@Composable
private fun CompactCard(item: AlarmItem, vm: MainViewModel, anchors: MorphAnchors, current: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val t = tokens
    val alarm = item.alarm
    val offsets = alarm.actions.mapNotNull { it.signedOffset }
    val shape = RoundedCornerShape(14.dp)
    val content = if (alarm.enabled) 1f else 0.5f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (current) t.surface else Color.Transparent)
            .border(1.dp, if (current) t.accent else t.line, shape)
            .clickable(onClick = onClick)
            .semantics { selected = current }
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Format.time(context, alarm.time),
                style = AppType.listTime.copy(fontSize = 24.sp, lineHeight = 28.sp),
                color = t.ink.copy(alpha = content),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            AppSwitch(alarm.enabled, { vm.setEnabled(alarm, it) }, stringResource(R.string.enabled))
        }
        Column(Modifier.padding(end = 10.dp).graphicsLayer { alpha = content }) {
            Text(
                listOf(alarm.label, Format.days(context, alarm.days)).filter { it.isNotBlank() }.joinToString(" · "),
                style = AppType.small,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val flags = listOfNotNull(
                item.snoozed?.let { stringResource(R.string.snoozed_until, Format.next(context, it.ringAt)) },
                if (alarm.skipNext && alarm.repeating) stringResource(R.string.skipped) else null,
                if (item.effective.conflict) stringResource(R.string.not_saved) else if (item.effective.pending) stringResource(R.string.not_synced) else null,
            )
            if (flags.isNotEmpty()) Text(flags.joinToString(" · "), style = AppType.small, color = t.muted, modifier = Modifier.padding(top = 2.dp))
            if (offsets.isNotEmpty()) MiniTimeline(alarm.id, offsets, alarm.enabled, hidden = false, anchors = anchors)
            val soon = item.next?.takeIf { it.occurrenceId != null && it.ringAt - System.currentTimeMillis() in 0..3_600_000L }
            val canSkip = alarm.enabled && alarm.repeating
            if (canSkip || soon != null) {
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (soon != null) Pill(stringResource(R.string.dismiss_now), { vm.dismissEarly(soon) }, accent = true)
                    if (canSkip) Pill(stringResource(if (alarm.skipNext) R.string.undo_skip else R.string.skip_next), { vm.setSkip(alarm, !alarm.skipNext) })
                }
            }
        }
    }
}

/** The detail pane: time, tabs, Save and ⋯; the horizontal timeline or Wake-up; the bottom bar. */
@Composable
private fun LandDetail(
    vm: EditorViewModel,
    onTime: () -> Unit,
    onSaved: (String?) -> Unit,
    onDeleted: (String?) -> Unit,
    onMessage: (String) -> Unit,
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
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val isNew = draft.id == null
    val selected = draft.actions.firstOrNull { it.key == selectedKey }?.takeIf { mode == DetailMode.VIEW && tab == DetailTab.TIMELINE }

    // A placed or moved action stays selected, like the mockup.
    LaunchedEffect(flash) { flash?.let { selectedKey = it.first } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onTime)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    Format.time(context, draft.time),
                    style = AppType.detailTime.copy(fontSize = 48.sp, lineHeight = 52.sp),
                    color = t.ink,
                    maxLines = 1,
                )
                Text(
                    listOf(draft.label.ifBlank { stringResource(R.string.label) }, Format.days(context, draft.repeatDays), stringResource(R.string.land_time_hint))
                        .joinToString(" · "),
                    style = AppType.secondary,
                    color = t.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.width(200.dp).padding(top = 4.dp)) {
                Segmented(
                    first = stringResource(R.string.tab_timeline),
                    second = stringResource(R.string.tab_wake),
                    secondSelected = tab == DetailTab.WAKE,
                    onSelect = { wake -> vm.showTab(if (wake) DetailTab.WAKE else DetailTab.TIMELINE) },
                )
            }
            // Hidden while picking or placing, so an unplaced action is never dropped by saving.
            if ((isNew || changed) && mode == DetailMode.VIEW) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(t.accent)
                        .clickable(enabled = !saving, role = Role.Button) { vm.save(onSaved, onMessage) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(if (saving) R.string.saving else R.string.save), style = AppType.body, fontWeight = FontWeight.SemiBold, color = t.onAccent)
                }
            }
            if (!isNew) {
                Box(Modifier.padding(top = 4.dp)) {
                    RoundIconButton(AppIcons.more, stringResource(R.string.more), onClick = { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; vm.duplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = t.danger) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }

        if (conflict) Banner(stringResource(R.string.conflict), BannerKind.ERROR, stringResource(R.string.reload)) { vm.reload() }
        if (errors.isNotEmpty()) {
            Banner(errors.values.distinct().joinToString("\n") { Format.draftError(context, it) }, BannerKind.ERROR)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == DetailTab.WAKE) {
                Rise(Modifier.fillMaxSize()) { WakeSettings(vm, landscape = true) }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    HorizontalTimeline(
                        draft = draft,
                        scripts = scripts,
                        mode = mode,
                        placing = placing,
                        selectedKey = selected?.key,
                        flash = flash,
                        onSelect = { selectedKey = it.key },
                        onDrag = { o -> vm.movePlacing { it.copy(offset = o) } },
                    )
                    DismissStrip(draft, scripts, mode, placing, selected?.key, onSelect = { selectedKey = it.key })
                }
            }
        }

        if (tab == DetailTab.TIMELINE) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .drawBehind { drawLine(t.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
                verticalArrangement = Arrangement.Center,
            ) {
                when (mode) {
                    DetailMode.VIEW -> key("view", selected?.key) {
                        Rise {
                            LandViewBar(draft, selected, scripts, loadingScripts, scriptProblem, state.actionLabel, vm, onDeselect = { selectedKey = null })
                        }
                    }
                    DetailMode.PICK -> key("pick") {
                        Rise { LandPickBar(scripts, loadingScripts, scriptProblem, state.actionLabel, vm::pick, vm::cancelMode) }
                    }
                    DetailMode.PLACE -> placing?.let { p ->
                        key("place") { Rise { LandPlaceBar(p, draft, scripts, state.timeZone, vm, onMessage) } }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.delete_hint)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onDeleted, onMessage) }) {
                    Text(stringResource(R.string.yes_delete), color = t.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (warnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.warnings.value = emptyList(); onSaved(null) },
            title = { Text(stringResource(R.string.saved_with_warnings)) },
            text = { Text(warnings.joinToString("\n\n") { w -> warningText(context, w, draft, scripts) }) },
            confirmButton = { TextButton(onClick = { vm.warnings.value = emptyList(); onSaved(null) }) { Text(stringResource(R.string.close)) } },
        )
    }
}

private fun scriptIcon(script: ScriptRow?) = AppIcons.mdi(script?.iconPath) ?: AppIcons.script

/** Places the element centred on [x] with its top at [y] (dp, in the parent). */
private fun Modifier.centredAt(x: Float, y: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { p.place(x.dp.roundToPx() - p.width / 2, y.dp.roundToPx()) }
}

/**
 * The actions around the alarm on a horizontal axis: ticks with clock times, the alarm in the middle
 * and the actions as 40 dp icons on up to three lanes. In place mode a tap or drag sets the offset.
 */
@Composable
private fun HorizontalTimeline(
    draft: Draft,
    scripts: List<ScriptRow>,
    mode: DetailMode,
    placing: Placing?,
    selectedKey: String?,
    flash: Pair<String, Int>?,
    onSelect: (DraftAction) -> Unit,
    onDrag: (Int) -> Unit,
) {
    val context = LocalContext.current
    val t = tokens
    val placingNow = if (mode == DetailMode.PLACE) placing else null
    val placingAround = placingNow != null && !placingNow.afterDismiss
    val around = draft.actions.filter { it.signedOffset != null }.sortedBy { it.signedOffset }
    val range = Timeline.range(around.mapNotNull { it.signedOffset } + listOfNotNull(placingNow?.takeIf { placingAround }?.offset))
    val drag by rememberUpdatedState(onDrag)
    // The range can change while dragging; reading it here keeps the gesture alive.
    val dragRange by rememberUpdatedState(range)
    val markAlpha = { a: DraftAction ->
        when {
            placingNow == null -> if (a.enabled) 1f else 0.5f
            a.key == placingNow.key -> 0f
            else -> 0.35f
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth.value
        val x = { offset: Int -> PAD_X + Timeline.fraction(offset, range) * (width - 2 * PAD_X) }
        val lanes = Timeline.lanes(around.map { x(it.signedOffset ?: 0) }, LANE_GAP)
        val height = if (lanes.any { it == 2 }) HEIGHT_THREE_LANES else HEIGHT_TWO_LANES
        Box(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .pointerInput(placingAround) {
                    if (!placingAround) return@pointerInput
                    val pad = PAD_X.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        drag(Timeline.offsetAtFraction((down.position.x - pad) / (size.width - 2 * pad), dragRange))
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            drag(Timeline.offsetAtFraction((change.position.x - pad) / (size.width - 2 * pad), dragRange))
                            change.consume()
                        }
                    }
                },
        ) {
            // Axis, ticks, stems and the alarm line.
            Canvas(Modifier.matchParentSize()) {
                val axis = AXIS_Y.dp.toPx()
                drawLine(t.line, Offset(0f, axis), Offset(size.width, axis), 1.dp.toPx())
                val major = Timeline.majorStep(range)
                for (o in -range..range step Timeline.minorStep(range)) {
                    val h = (if (o % major == 0) 5 else 3).dp.toPx()
                    val tx = x(o).dp.toPx()
                    drawLine(t.line, Offset(tx, axis - h), Offset(tx, axis + h), 1.dp.toPx())
                }
                around.forEachIndexed { i, a ->
                    val top = LANE_TOP[lanes[i]]
                    val from = if (lanes[i] == 2) AXIS_Y else top + MARK
                    val to = if (lanes[i] == 2) top else AXIS_Y
                    val sx = x(a.signedOffset ?: 0).dp.toPx()
                    drawLine(t.line.copy(alpha = markAlpha(a)), Offset(sx, from.dp.toPx()), Offset(sx, to.dp.toPx()), 1.dp.toPx())
                }
                val zero = x(0).dp.toPx()
                drawLine(t.accent.copy(alpha = 0.85f), Offset(zero, 0f), Offset(zero, LABEL_Y.dp.toPx()), 2.dp.toPx())
            }

            // Clock labels on the major ticks that have room, and the alarm's own time.
            val major = Timeline.majorStep(range)
            val every = max(1, ceil(64f / (major * (width - 2 * PAD_X) / (2 * range))).toInt())
            for (o in -range..range step major) {
                if ((o / major) % every == 0 && abs(x(o) - x(0)) >= 48f) {
                    Text(Format.shifted(context, draft.time, o), style = AppType.small, color = t.muted, modifier = Modifier.centredAt(x(o), LABEL_Y))
                }
            }
            Text(
                Format.time(context, draft.time),
                style = AppType.small,
                fontWeight = FontWeight.Bold,
                color = t.accent,
                modifier = Modifier.centredAt(x(0), LABEL_Y).background(t.bg).padding(horizontal = 4.dp),
            )

            if (draft.actions.isEmpty() && mode == DetailMode.VIEW) {
                Box(Modifier.fillMaxWidth().padding(top = 36.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.TopCenter) {
                    Text(
                        stringResource(R.string.land_simple),
                        style = AppType.label,
                        color = t.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.background(t.bg).padding(horizontal = 8.dp),
                    )
                }
            }

            around.forEachIndexed { i, a ->
                key(a.key) {
                    val script = scripts.firstOrNull { it.entityId == a.scriptEntityId }
                    val unavailable = scripts.isNotEmpty() && script == null
                    val on = a.key == selectedKey
                    val strength = rememberFlash(flash?.takeIf { it.first == a.key })
                    val label = "${script?.name ?: a.scriptEntityId}, ${Format.relative(context, a.signedOffset, a.delayMinutes)}"
                    Box(
                        Modifier
                            .centredAt(x(a.signedOffset ?: 0), LANE_TOP[lanes[i]])
                            .graphicsLayer { alpha = markAlpha(a) }
                            .size(MARK.dp)
                            .clip(CircleShape)
                            .background(if (on) t.accentSoft else t.surface)
                            .border(1.5.dp, if (on) t.accent else t.line, CircleShape)
                            .flashRing(strength, t.accent, CircleShape)
                            .clickable(enabled = mode == DetailMode.VIEW, role = Role.Button) { onSelect(a) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (unavailable) AppIcons.warning else scriptIcon(script),
                            null,
                            tint = if (unavailable) t.warn else t.ink,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // The action being placed: dashed guide, accent icon and its clock time follow the offset.
            if (placingNow != null && !placingNow.afterDismiss) {
                val gx by animateFloatAsState(x(placingNow.offset), tween(120), label = "ghost")
                val script = scripts.firstOrNull { it.entityId == placingNow.scriptEntityId }
                Canvas(Modifier.matchParentSize()) {
                    val px = gx.dp.toPx()
                    drawLine(
                        t.accent,
                        Offset(px, 0f),
                        Offset(px, LABEL_Y.dp.toPx()),
                        1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    )
                }
                Box(
                    Modifier.centredAt(gx, 0f).size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(t.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(scriptIcon(script), null, tint = t.onAccent, modifier = Modifier.size(20.dp))
                }
                Text(
                    Format.shifted(context, draft.time, placingNow.offset),
                    style = AppType.small,
                    fontWeight = FontWeight.Bold,
                    color = t.onAccent,
                    modifier = Modifier.centredAt(gx, LABEL_Y).clip(RoundedCornerShape(8.dp)).background(t.accent).padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** "After you dismiss": the after-dismiss actions as chips under the timeline. */
@Composable
private fun DismissStrip(draft: Draft, scripts: List<ScriptRow>, mode: DetailMode, placing: Placing?, selectedKey: String?, onSelect: (DraftAction) -> Unit) {
    val context = LocalContext.current
    val t = tokens
    val placingNow = if (mode == DetailMode.PLACE) placing?.takeIf { it.afterDismiss } else null
    val dismissed = draft.actions.filter { it.signedOffset == null }.sortedBy { it.delayMinutes ?: 0 }
    if (dismissed.isEmpty() && placingNow == null) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.after_you_dismiss), style = AppType.small, fontWeight = FontWeight.SemiBold, color = t.muted)
        dismissed.forEach { a ->
            key(a.key) {
                if (placingNow != null && placingNow.key == a.key) {
                    GhostChip(placingNow, scripts)
                } else {
                    val script = scripts.firstOrNull { it.entityId == a.scriptEntityId }
                    val alpha = when {
                        mode == DetailMode.PLACE -> 0.35f
                        !a.enabled -> 0.5f
                        else -> 1f
                    }
                    Chip(
                        "${script?.name ?: a.scriptEntityId} · ${if (a.enabled) Format.relative(context, null, a.delayMinutes) else stringResource(R.string.off)}",
                        { if (mode == DetailMode.VIEW) onSelect(a) },
                        Modifier.graphicsLayer { this.alpha = alpha },
                        icon = scriptIcon(script),
                        selected = a.key == selectedKey,
                    )
                }
            }
        }
        if (placingNow != null && dismissed.none { it.key == placingNow.key }) GhostChip(placingNow, scripts)
    }
}

@Composable
private fun GhostChip(p: Placing, scripts: List<ScriptRow>) {
    val context = LocalContext.current
    val t = tokens
    val script = scripts.firstOrNull { it.entityId == p.scriptEntityId }
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(t.accent).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(scriptIcon(script), null, tint = t.onAccent, modifier = Modifier.size(16.dp))
        Text(
            "${script?.name ?: p.scriptEntityId} · ${Format.relative(context, null, p.delay)}",
            style = AppType.secondary,
            fontWeight = FontWeight.Medium,
            color = t.onAccent,
            maxLines = 1,
        )
    }
}

/** View mode: Add action and the hint, or the selected action with Remove, Move and Close. */
@Composable
private fun LandViewBar(
    draft: Draft,
    selected: DraftAction?,
    scripts: List<ScriptRow>,
    loading: Boolean,
    problem: Problem?,
    actionLabel: String?,
    vm: EditorViewModel,
    onDeselect: () -> Unit,
) {
    val context = LocalContext.current
    val t = tokens
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selected != null) {
            val script = scripts.firstOrNull { it.entityId == selected.scriptEntityId }
            Icon(scriptIcon(script), null, tint = t.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(scriptName(scripts, selected.scriptEntityId), selected.signedOffset?.let { Format.shifted(context, draft.time, it) }).joinToString(" · "),
                    style = AppType.bodyStrong,
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        script?.description?.takeIf { it.isNotBlank() },
                        if (selected.enabled) Format.relative(context, selected.signedOffset, selected.delayMinutes) else stringResource(R.string.off),
                    ).joinToString(" · "),
                    style = AppType.small,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SheetButton(
                stringResource(R.string.remove),
                {
                    vm.update { d -> d.copy(actions = d.actions.filter { it.key != selected.key }) }
                    onDeselect()
                },
                Modifier.height(44.dp),
            )
            SheetButton(stringResource(R.string.land_move), { vm.edit(selected) }, Modifier.height(44.dp), ButtonKind.ACCENT)
            RoundIconButton(AppIcons.close, stringResource(R.string.close), onDeselect)
        } else {
            when {
                scripts.isEmpty() -> Hint(scriptsMessage(loading, problem, actionLabel), t.muted, Modifier.weight(1f))
                draft.actions.size >= Limits.ACTIONS_PER_ALARM -> Hint(stringResource(R.string.action_limit, Limits.ACTIONS_PER_ALARM), t.muted, Modifier.weight(1f))
                else -> {
                    SheetButton(stringResource(R.string.add_action), vm::startPick, Modifier.height(46.dp), ButtonKind.PRIMARY, icon = AppIcons.plus)
                    Text(stringResource(R.string.timeline_hint), style = AppType.secondary, color = t.muted, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Pick mode: the scripts as a sideways row of chips, and Cancel. */
@Composable
private fun LandPickBar(scripts: List<ScriptRow>, loading: Boolean, problem: Problem?, actionLabel: String?, onPick: (ScriptRow) -> Unit, onCancel: () -> Unit) {
    val t = tokens
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (scripts.isEmpty()) {
            Hint(scriptsMessage(loading, problem, actionLabel), t.muted, Modifier.weight(1f))
        } else {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                scripts.forEach { s -> Chip(s.name, { onPick(s) }, icon = scriptIcon(s)) }
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = t.muted) }
    }
}

/**
 * Place mode: Around the alarm | After dismiss, Run now and Enabled on the first row; earlier / later,
 * the readout, Cancel and Place here / Done on the second. Remove is on the selected action.
 */
@Composable
private fun LandPlaceBar(p: Placing, draft: Draft, scripts: List<ScriptRow>, timeZone: String?, vm: EditorViewModel, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val t = tokens
    val name = scriptName(scripts, p.scriptEntityId)
    val existing = draft.actions.firstOrNull { it.key == p.key }
    val candidate = vm.placed(p)
    val trial = draft.copy(actions = if (existing != null) draft.actions.map { if (it.key == p.key) candidate else it } else draft.actions + candidate)
    val missed = Drafts.missedBeforeActions(trial, timeZone ?: ZoneId.systemDefault().id, Instant.now()).any { it.key == candidate.key }
    val canRun = existing?.id != null && draft.id != null && !PendingEdits.isTempId(draft.id)
    val step = { delta: Int ->
        vm.movePlacing {
            if (it.afterDismiss) it.copy(delay = (it.delay + delta).coerceIn(Limits.DELAY_MIN, Limits.DELAY_MAX))
            else it.copy(offset = (it.offset + delta).coerceIn(-Limits.OFFSET_MAX, Limits.OFFSET_MAX))
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Box(Modifier.widthIn(max = 280.dp)) {
                    Segmented(
                        first = stringResource(R.string.around_alarm),
                        second = stringResource(R.string.timing_after_dismiss),
                        secondSelected = p.afterDismiss,
                        onSelect = { second -> vm.movePlacing { it.copy(afterDismiss = second) } },
                    )
                }
            }
            if (canRun && existing != null) Pill(stringResource(R.string.run_now), { vm.runNow(existing, onMessage) })
            Text(stringResource(R.string.enabled), style = AppType.secondary, color = t.ink)
            AppSwitch(p.enabled, { on -> vm.movePlacing { it.copy(enabled = on) } }, stringResource(R.string.enabled))
        }
        if (missed) {
            Text(stringResource(R.string.before_missed, -p.offset, name), style = AppType.small, color = t.warn, maxLines = 2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIconButton(AppIcons.back, stringResource(R.string.earlier), { step(-1) }, Modifier.border(1.dp, t.line, CircleShape), tint = t.ink, size = 18.dp)
            RoundIconButton(AppIcons.chevron, stringResource(R.string.later), { step(1) }, Modifier.border(1.dp, t.line, CircleShape), tint = t.ink, size = 18.dp)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                val readout = when {
                    p.afterDismiss && p.delay == 0 -> stringResource(R.string.readout_dismiss_now)
                    p.afterDismiss -> stringResource(R.string.readout_dismiss_after, p.delay)
                    else -> "${Format.shifted(context, draft.time, p.offset)}, ${Format.relative(context, p.offset, null)}"
                }
                Text("$name · $readout", style = AppType.bodyStrong, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!p.afterDismiss) Text(stringResource(R.string.land_drag_hint), style = AppType.small, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SheetButton(stringResource(R.string.cancel), vm::cancelMode, Modifier.height(44.dp))
            SheetButton(stringResource(if (p.key == null) R.string.place_here else R.string.done), vm::commitPlace, Modifier.height(44.dp), ButtonKind.ACCENT)
        }
    }
}

/**
 * The time page (mockup "setting the time"): hour and minute drums, Repeat, Name and how the actions
 * move. Edits a copy; [onDone] gets it, Cancel or back drops it.
 */
@Composable
private fun LandTime(draft: Draft, scripts: List<ScriptRow>, onCancel: () -> Unit, onDone: (Draft) -> Unit) {
    val context = LocalContext.current
    val t = tokens
    var local by remember { mutableStateOf(draft) }
    val time = runCatching { LocalTime.parse(local.time) }.getOrDefault(LocalTime.of(7, 0))
    // Reads the copy when called, so several steps within one frame all count.
    fun bump(hours: Int, minutes: Int) {
        val now = runCatching { LocalTime.parse(local.time) }.getOrDefault(LocalTime.of(7, 0))
        local = local.copy(time = LocalTime.of((now.hour + hours + 24) % 24, (now.minute + minutes + 60) % 60).toString())
    }
    val title = AppType.small.copy(letterSpacing = 0.06.em)

    Column(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onCancel, role = Role.Button).heightIn(min = 44.dp).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.back, null, tint = t.muted, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.title), style = AppType.body, color = t.muted)
            }
            Text("/", style = AppType.body, color = t.muted)
            Text(
                draft.label.ifBlank { stringResource(R.string.alarm) },
                style = AppType.body,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp),
            )
            Text("/", style = AppType.body, color = t.muted)
            Text(stringResource(R.string.land_time), style = AppType.bodyStrong, color = t.ink, modifier = Modifier.padding(horizontal = 8.dp))
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Drum(time.hour, 24, { bump(it, 0) }, stringResource(R.string.land_hour_earlier), stringResource(R.string.land_hour_later))
                    Text(":", style = AppType.body.copy(fontSize = 64.sp, lineHeight = 64.sp), fontWeight = FontWeight.ExtraLight, color = t.muted, modifier = Modifier.padding(horizontal = 6.dp))
                    Drum(time.minute, 60, { bump(0, it) }, stringResource(R.string.earlier), stringResource(R.string.later))
                }
                Text(
                    stringResource(R.string.land_drum_hint),
                    style = AppType.secondary,
                    color = t.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.repeat).uppercase(), style = title, fontWeight = FontWeight.SemiBold, color = t.muted)
                    DayToggles(local.repeatDays) { day -> local = Drafts.toggleDay(local, day) }
                    if (local.repeatDays.isEmpty()) Hint(stringResource(R.string.repeat_hint), t.muted)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.label).uppercase(), style = title, fontWeight = FontWeight.SemiBold, color = t.muted)
                    OutlinedTextField(
                        value = local.label,
                        onValueChange = { v -> local = local.copy(label = v.take(Limits.LABEL_LENGTH)) },
                        placeholder = { Text(stringResource(R.string.label_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (draft.actions.isNotEmpty()) {
                    val around = draft.actions.filter { it.signedOffset != null }.sortedBy { it.signedOffset }
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.surface).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            pluralStringResource(R.plurals.land_time_actions, draft.actions.size, draft.actions.size),
                            style = AppType.label,
                            fontWeight = FontWeight.Medium,
                            color = t.ink,
                        )
                        val first = around.firstOrNull()
                        val last = around.lastOrNull()
                        if (first != null && last != null) {
                            val at = { a: DraftAction -> Format.shifted(context, local.time, a.signedOffset ?: 0) }
                            Text(
                                if (around.size == 1) {
                                    stringResource(R.string.land_time_one, at(first), scriptName(scripts, first.scriptEntityId))
                                } else {
                                    stringResource(R.string.land_time_first_last, at(first), scriptName(scripts, first.scriptEntityId), at(last), scriptName(scripts, last.scriptEntityId))
                                },
                                style = AppType.secondary,
                                color = t.muted,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    SheetButton(stringResource(R.string.cancel), onCancel, Modifier.height(48.dp))
                    SheetButton(stringResource(R.string.land_time_done, Format.time(context, local.time)), { onDone(local) }, Modifier.height(48.dp), ButtonKind.ACCENT)
                }
            }
        }
    }
}

/** A wheel of [count] values (wrapping) with the previous and next value; drag 28 dp or use the arrows. [onStep] gets ±1. */
@Composable
private fun Drum(value: Int, count: Int, onStep: (Int) -> Unit, lessLabel: String, moreLabel: String) {
    val t = tokens
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }
    val stepTo by rememberUpdatedState(onStep)
    // Content comes in from the side it moved from: 34 dp, 260 ms, ease-soft.
    val step = { d: Int ->
        stepTo(d)
        scope.launch {
            slide.snapTo(if (d > 0) 34f else -34f)
            slide.animateTo(0f, tween(260, easing = EaseSoft))
        }
        Unit
    }
    val two = { n: Int -> ((n % count + count) % count).toString().padStart(2, '0') }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIconButton(AppIcons.up, lessLabel, { step(-1) }, tint = t.muted)
        Box(
            Modifier
                .size(132.dp, 176.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(t.surface)
                .pointerInput(Unit) {
                    val stepPx = 28.dp.toPx()
                    var moved = 0f
                    detectVerticalDragGestures(onDragStart = { moved = 0f }) { change, dy ->
                        change.consume()
                        moved += dy
                        while (moved <= -stepPx) { moved += stepPx; step(1) }
                        while (moved >= stepPx) { moved -= stepPx; step(-1) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.graphicsLayer {
                    translationY = slide.value.dp.toPx()
                    alpha = 1f - abs(slide.value) / 34f * 0.7f
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(two(value - 1), style = AppType.body.copy(fontSize = 24.sp, lineHeight = 30.sp), fontWeight = FontWeight.Light, color = t.muted.copy(alpha = 0.6f))
                Text(two(value), style = AppType.body.copy(fontSize = 84.sp, lineHeight = 88.sp, letterSpacing = (-0.04).em), fontWeight = FontWeight.ExtraLight, color = t.ink)
                Text(two(value + 1), style = AppType.body.copy(fontSize = 24.sp, lineHeight = 30.sp), fontWeight = FontWeight.Light, color = t.muted.copy(alpha = 0.6f))
            }
        }
        RoundIconButton(AppIcons.down, moreLabel, { step(1) }, tint = t.muted)
    }
}
