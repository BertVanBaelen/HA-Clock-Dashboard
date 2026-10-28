package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.Routes
import alarmclock.vanbaelen.dev.core.ActionSummary
import alarmclock.vanbaelen.dev.core.Alarm
import alarmclock.vanbaelen.dev.core.ReportPolicy
import alarmclock.vanbaelen.dev.core.Timeline
import alarmclock.vanbaelen.dev.core.UpdateBlock
import alarmclock.vanbaelen.dev.core.signedOffset
import alarmclock.vanbaelen.dev.data.SyncProblem
import alarmclock.vanbaelen.dev.ui.AlarmItem
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.AppType
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import alarmclock.vanbaelen.dev.ui.tokens
import alarmclock.vanbaelen.dev.update.UpdateState
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/** Positions (root coordinates) that the list and the detail report for the morph animation. */
class MorphAnchors {
    /** Centre of each alarm's mini timeline in the list. */
    val lines = HashMap<String, Offset>()
    /** Alarm dot on the detail rail. */
    var dot: Offset? = null
    /** Top of the detail's timeline viewport; the morph is clipped there. */
    var viewportTop: Float = 0f
    /** Origin of the screen host. */
    var origin: Offset = Offset.Zero
}

/** One run of the morph: the dots, both scales and both ends, in host coordinates. */
private data class Morph(
    val alarmId: String,
    val offsets: List<Int>,
    val listRange: Int,
    val detailRange: Int,
    val on: Boolean,
    val from: Offset,
    val to: Offset,
    val clip: Float,
)

private fun animationsOff(context: Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun mix(a: Float, b: Float, p: Float) = a + (b - a) * p

/**
 * Alarm list and alarm detail in one host, so the mini timeline of an alarm can morph into the
 * detail rail (docs/design.md, Motion). AFR-ALM-01, AFR-SYN-04.
 */
@Composable
fun AlarmListScreen(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val t = tokens
    val editor: EditorViewModel = viewModel()
    val open by editor.isOpen.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val anchors = remember { MorphAnchors() }
    val reduced = remember { animationsOff(context) }
    val progress = remember { Animatable(0f) }
    val listAlpha = remember { Animatable(if (open) 0f else 1f) }
    val detailAlpha = remember { Animatable(if (open) 1f else 0f) }
    val cardsAlpha = remember { Animatable(1f) }
    val railAlpha = remember { Animatable(1f) }
    var morph by remember { mutableStateOf<Morph?>(null) }
    var hiddenLine by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    suspend fun crossFade(toDetail: Boolean) = coroutineScope {
        launch { listAlpha.animateTo(if (toDetail) 0f else 1f, tween(300)) }
        launch { detailAlpha.animateTo(if (toDetail) 1f else 0f, tween(if (toDetail) 300 else 250)) }
    }

    fun openDetail(alarm: Alarm?, pick: Boolean) {
        if (busy) return
        busy = true
        anchors.dot = null
        editor.open(alarm, pick)
        scope.launch {
            try {
                cardsAlpha.snapTo(1f)
                railAlpha.snapTo(1f)
                val offsets = alarm?.actions?.mapNotNull { it.signedOffset }.orEmpty()
                val from = alarm?.let { anchors.lines[it.id] }
                if (reduced || alarm == null || offsets.isEmpty() || from == null) {
                    crossFade(toDetail = true)
                    return@launch
                }
                detailAlpha.snapTo(0f)
                cardsAlpha.snapTo(0f)
                railAlpha.snapTo(0f)
                progress.snapTo(0f)
                // Wait until the detail is laid out and reports its alarm dot.
                val to = withTimeoutOrNull(500) {
                    while (anchors.dot == null) withFrameNanos { }
                    withFrameNanos { }
                    anchors.dot
                }
                if (to == null) {
                    cardsAlpha.snapTo(1f)
                    railAlpha.snapTo(1f)
                    crossFade(toDetail = true)
                    return@launch
                }
                val range = Timeline.range(offsets)
                hiddenLine = alarm.id
                morph = Morph(alarm.id, offsets, range, range, alarm.enabled, from - anchors.origin, to - anchors.origin, anchors.viewportTop - anchors.origin.y)
                coroutineScope {
                    launch { listAlpha.animateTo(0f, tween(250)) }
                    launch { detailAlpha.animateTo(1f, tween(350, delayMillis = 200)) }
                    launch { cardsAlpha.animateTo(1f, tween(300, delayMillis = 650)) }
                    launch { railAlpha.animateTo(1f, tween(100, delayMillis = 720)) }
                    progress.animateTo(1f, tween(750, easing = EaseMorph))
                }
            } finally {
                morph = null
                hiddenLine = null
                busy = false
            }
        }
    }

    fun closeDetail(message: String?) {
        message?.let(vm::message)
        if (busy) return
        busy = true
        scope.launch {
            try {
                val draft = editor.draft.value
                val item = draft.id?.let { id -> vm.items.value.firstOrNull { it.alarm.id == id } }
                val offsets = item?.alarm?.actions?.mapNotNull { it.signedOffset }.orEmpty()
                val detailOffsets = draft.actions.mapNotNull { it.signedOffset }
                val from = item?.let { anchors.lines[it.alarm.id] }
                val to = anchors.dot
                if (reduced || item == null || editor.mode.value != DetailMode.VIEW || offsets.isEmpty() || detailOffsets.isEmpty() || from == null || to == null) {
                    crossFade(toDetail = false)
                } else {
                    hiddenLine = item.alarm.id
                    morph = Morph(
                        item.alarm.id, offsets, Timeline.range(offsets), Timeline.range(detailOffsets), item.alarm.enabled,
                        from - anchors.origin, to - anchors.origin, anchors.viewportTop - anchors.origin.y,
                    )
                    progress.snapTo(1f)
                    coroutineScope {
                        launch { detailAlpha.animateTo(0f, tween(200)) }
                        launch { listAlpha.animateTo(1f, tween(300, delayMillis = 500)) }
                        progress.animateTo(0f, tween(750, easing = EaseMorph))
                    }
                }
            } finally {
                morph = null
                hiddenLine = null
                editor.close()
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(t.bg).onGloballyPositioned { anchors.origin = it.positionInRoot() }) {
        AlarmList(
            vm = vm,
            nav = nav,
            anchors = anchors,
            hiddenLine = hiddenLine,
            onOpen = { openDetail(it, pick = false) },
            onNew = { pick -> openDetail(null, pick) },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha.value },
        )
        if (open) {
            AlarmDetail(
                vm = editor,
                anchors = anchors,
                cardsAlpha = { cardsAlpha.value },
                railAlpha = { railAlpha.value },
                onClose = ::closeDetail,
                onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
                modifier = Modifier.graphicsLayer { alpha = detailAlpha.value },
            )
        }
        morph?.let { m -> MorphOverlay(m) { progress.value } }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (open) 8.dp else 72.dp))
    }
}

/** The moving line and dots between the list card and the detail rail. */
@Composable
private fun MorphOverlay(m: Morph, progress: () -> Float) {
    val t = tokens
    Canvas(Modifier.fillMaxSize()) {
        val p = progress()
        val pos = Offset(mix(m.from.x, m.to.x, p), mix(m.from.y, m.to.y, p))
        val kList = Timeline.MINI_WIDTH.dp.toPx() / (2 * m.listRange)
        val kDetail = Timeline.pxPerMin(m.detailRange).dp.toPx()
        val k = mix(kList, kDetail, p)
        val half = mix(m.listRange.toFloat(), m.detailRange.toFloat(), p) * k
        clipRect(top = mix(0f, m.clip, p)) {
            withTransform({
                translate(pos.x, pos.y)
                rotate(90f * p, Offset.Zero)
            }) {
                drawLine(t.line, Offset(-half, 0f), Offset(half, 0f), 1.dp.toPx())
                val dot = lerp(t.muted, t.ink, p)
                m.offsets.forEach { o -> drawCircle(dot, mix(3f, 4.5f, p).dp.toPx(), Offset(o * k, 0f)) }
                drawCircle(if (m.on) t.accent else lerp(t.muted, t.accent, p), mix(5f, 6.5f, p).dp.toPx(), Offset.Zero)
            }
        }
    }
}

@Composable
private fun AlarmList(
    vm: MainViewModel,
    nav: NavController,
    anchors: MorphAnchors,
    hiddenLine: String?,
    onOpen: (Alarm) -> Unit,
    onNew: (pick: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val t = tokens
    val items by vm.items.collectAsState()
    val state by vm.syncState.collectAsState()
    val account by vm.account.collectAsState()
    val missed by vm.missed.collectAsState()
    val problems by vm.problems.collectAsState()
    val pendingEdits by vm.pendingEdits.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val update by vm.updateState.collectAsState()
    val next by vm.nextRing.collectAsState()
    var creating by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    Column(modifier.statusBarsPadding()) {
        // Header: title, next ring, sync and add.
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.title), style = AppType.screenTitle, color = t.ink)
                val subtitle = next?.let { r ->
                    val at = if (r.ringAt - now < 86_400_000L) {
                        Format.time(context, Instant.ofEpochMilli(r.ringAt).atZone(ZoneId.systemDefault()).toLocalTime())
                    } else {
                        Format.next(context, r.ringAt)
                    }
                    stringResource(R.string.next_in, at, Format.until(context, r.ringAt, now))
                } ?: stringResource(R.string.no_alarms_on)
                Text(subtitle, style = AppType.label, color = t.muted, modifier = Modifier.padding(top = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (syncing) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = t.muted, strokeWidth = 2.dp)
                    }
                } else {
                    RoundIconButton(AppIcons.sync, stringResource(R.string.sync_now), vm::sync)
                }
                val rotation by animateFloatAsState(if (creating) 45f else 0f, tween(300, easing = EaseSoft), label = "plus")
                RoundIconButton(
                    AppIcons.plus,
                    stringResource(R.string.add_alarm),
                    { creating = !creating },
                    Modifier.graphicsLayer { rotationZ = rotation },
                    background = t.soft,
                    tint = t.ink,
                )
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Banners(vm, nav, state, account.needsLogin, missed.size, missed.lastOrNull(), problems.size, problems.firstOrNull()?.seq, update)
                SyncStatus(state.lastSyncAt, state.problem, pendingEdits)
            }
            if (creating) {
                item(key = "create") {
                    Rise {
                        NewAlarmCard(onCancel = { creating = false }, onPick = { pick -> creating = false; onNew(pick) })
                    }
                }
            }
            if (items.isEmpty() && !creating) {
                item {
                    Text(
                        stringResource(R.string.no_alarms),
                        textAlign = TextAlign.Center,
                        style = AppType.body,
                        color = t.muted,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            items(items, key = { it.alarm.id }) { item ->
                AlarmCard(item, vm, anchors, hidden = hiddenLine == item.alarm.id, onClick = { onOpen(item.alarm) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        BottomNav(nav)
    }
}

@Composable
private fun Banners(
    vm: MainViewModel,
    nav: NavController,
    state: alarmclock.vanbaelen.dev.data.SyncStateRow,
    needsLogin: Boolean,
    missedCount: Int,
    lastMissed: alarmclock.vanbaelen.dev.data.MissedAlarmRow?,
    problemCount: Int,
    firstProblem: Long?,
    update: UpdateState,
) {
    val context = LocalContext.current
    val away = ReportPolicy.isAway(state.timeZone, ZoneId.systemDefault().id) || state.serverAway
    if (needsLogin || state.problem == SyncProblem.LOGIN) {
        Banner(stringResource(R.string.login_again_text), BannerKind.ERROR, stringResource(R.string.login_again)) { nav.navigate(Routes.SETUP) }
    }
    when (state.problem) {
        SyncProblem.DASHBOARD_OLD -> Banner(stringResource(R.string.dashboard_too_old), BannerKind.ERROR)
        SyncProblem.APP_OLD -> Banner(stringResource(R.string.app_too_old), BannerKind.ERROR)
    }
    vm.updateInfo(state)?.let { info ->
        val text = when (update) {
            is UpdateState.Downloading -> stringResource(R.string.update_downloading, ((update.progress ?: 0f) * 100).toInt())
            UpdateState.Installing -> stringResource(R.string.update_installing)
            is UpdateState.Blocked -> stringResource(if (update.reason == UpdateBlock.RINGING) R.string.update_blocked_ringing else R.string.update_blocked_soon)
            UpdateState.NeedsPermission -> stringResource(R.string.update_needs_permission)
            UpdateState.ChecksumMismatch -> stringResource(R.string.update_checksum)
            is UpdateState.Failed -> stringResource(R.string.update_failed)
            UpdateState.Idle -> stringResource(R.string.update_available_text, info.version ?: "")
        }
        val action = when (update) {
            UpdateState.NeedsPermission -> stringResource(R.string.allow)
            is UpdateState.Downloading, UpdateState.Installing -> null
            else -> stringResource(R.string.update)
        }
        Banner(text, BannerKind.INFO, action) {
            if (update == UpdateState.NeedsPermission) {
                vm.resetUpdate()
                runCatching { context.startActivity((context.applicationContext as alarmclock.vanbaelen.dev.App).updater.unknownSourcesIntent()) }
            } else {
                vm.installUpdate(info)
            }
        }
    }
    if (away) Banner(stringResource(R.string.away_banner), BannerKind.WARNING)
    lastMissed?.let { m ->
        val text = if (missedCount == 1) {
            stringResource(R.string.missed_one, m.label.ifBlank { stringResource(R.string.alarm) }, Format.next(context, m.ringAt))
        } else {
            pluralStringResource(R.plurals.missed_many, missedCount, missedCount)
        }
        Banner(text, BannerKind.WARNING, stringResource(R.string.close)) { vm.clearMissed() }
    }
    firstProblem?.let { seq ->
        Banner(pluralStringResource(R.plurals.edits_problem, problemCount, problemCount), BannerKind.ERROR, stringResource(R.string.review)) {
            nav.navigate(Routes.conflict(seq))
        }
    }
}

@Composable
private fun SyncStatus(lastSyncAt: Long?, problem: String?, pendingEdits: Int) {
    val context = LocalContext.current
    val last = lastSyncAt?.let { stringResource(R.string.last_synced, Format.ago(context, it)) } ?: stringResource(R.string.never_synced)
    val text = when (problem) {
        SyncProblem.OFFLINE -> stringResource(R.string.offline) + " · " + last
        SyncProblem.HA_OFFLINE -> stringResource(R.string.ha_offline)
        SyncProblem.ERROR -> stringResource(R.string.sync_failed) + " · " + last
        else -> last
    } + if (pendingEdits > 0) " · " + pluralStringResource(R.plurals.pending_edits, pendingEdits, pendingEdits) else ""
    Hint(text, tokens.muted, Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
}

/** "New alarm": a simple alarm, or one that starts with the action picker. */
@Composable
private fun NewAlarmCard(onCancel: () -> Unit, onPick: (Boolean) -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line, shape)
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.new_alarm), style = AppType.bodyStrong, color = t.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = t.muted) }
        }
        Choice(stringResource(R.string.simple_alarm), stringResource(R.string.simple_alarm_hint)) { onPick(false) }
        Choice(stringResource(R.string.with_actions), stringResource(R.string.with_actions_hint)) { onPick(true) }
    }
}

@Composable
private fun Choice(title: String, text: String, onClick: () -> Unit) {
    val t = tokens
    Column(
        Modifier
            .fillMaxWidth()
            .padding(end = 8.dp)
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(t.soft)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = AppType.bodyStrong, color = t.ink)
        Text(text, style = AppType.secondary, color = t.muted)
    }
}

@Composable
private fun AlarmCard(item: AlarmItem, vm: MainViewModel, anchors: MorphAnchors, hidden: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val t = tokens
    val alarm = item.alarm
    val offsets = alarm.actions.mapNotNull { it.signedOffset }
    val shape = RoundedCornerShape(22.dp)
    val content by animateFloatAsState(if (alarm.enabled) 1f else 0.5f, tween(250), label = "enabled")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line, shape)
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .graphicsLayer { alpha = content },
        ) {
            Row {
                Text(Format.time(context, alarm.time), style = AppType.listTime, color = t.ink, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(10.dp))
                Text(
                    alarm.label,
                    style = AppType.label,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                "${Format.days(context, alarm.days)} · ${Format.summary(context, ActionSummary.of(alarm.actions))}",
                style = AppType.secondary,
                color = t.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            val status = when {
                item.snoozed != null -> stringResource(R.string.snoozed_until, Format.next(context, item.snoozed.ringAt))
                !alarm.enabled -> stringResource(R.string.off)
                item.next != null -> stringResource(R.string.next_ring, Format.next(context, item.next.ringAt))
                else -> null
            }
            val flags = listOfNotNull(
                status,
                if (alarm.skipNext && alarm.repeating) stringResource(R.string.skipped) else null,
                if (item.effective.conflict) stringResource(R.string.not_saved) else if (item.effective.pending) stringResource(R.string.not_synced) else null,
            )
            if (flags.isNotEmpty()) Text(flags.joinToString(" · "), style = AppType.small, color = t.muted, modifier = Modifier.padding(top = 2.dp))
            if (offsets.isNotEmpty()) MiniTimeline(alarm.id, offsets, alarm.enabled, hidden, anchors)
            val soon = item.next?.takeIf { it.occurrenceId != null && it.ringAt - System.currentTimeMillis() in 0..3_600_000L }
            val canSkip = alarm.enabled && alarm.repeating
            if (canSkip || soon != null) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (soon != null) Pill(stringResource(R.string.dismiss_now), { vm.dismissEarly(soon) }, accent = true)
                    if (canSkip) {
                        Pill(stringResource(if (alarm.skipNext) R.string.undo_skip else R.string.skip_next), { vm.setSkip(alarm, !alarm.skipNext) })
                    }
                }
            }
        }
        AppSwitch(alarm.enabled, { vm.setEnabled(alarm, it) }, stringResource(R.string.enabled))
    }
}

/** The alarm's actions as dots on a 180 dp line, with the alarm in the middle. */
@Composable
private fun MiniTimeline(alarmId: String, offsets: List<Int>, on: Boolean, hidden: Boolean, anchors: MorphAnchors) {
    val t = tokens
    val range = Timeline.range(offsets)
    DisposableEffect(alarmId) { onDispose { anchors.lines.remove(alarmId) } }
    Canvas(
        Modifier
            .padding(top = 12.dp)
            .size(Timeline.MINI_WIDTH.dp, 10.dp)
            .onGloballyPositioned { c ->
                val p = c.positionInRoot()
                anchors.lines[alarmId] = Offset(p.x + c.size.width / 2f, p.y + c.size.height / 2f)
            }
            .graphicsLayer { alpha = if (hidden) 0f else 1f },
    ) {
        val cy = size.height / 2
        drawLine(t.line, Offset(0f, cy), Offset(size.width, cy), 1.dp.toPx())
        offsets.forEach { o -> drawCircle(t.muted, 3.dp.toPx(), Offset(Timeline.miniFraction(o, range) * size.width, cy)) }
        drawCircle(if (on) t.accent else t.muted, 5.dp.toPx(), Offset(size.width / 2, cy))
    }
}

/** Alarms · Night · Settings, with an accent bar above the current item. */
@Composable
private fun BottomNav(nav: NavController) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(t.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavItem(stringResource(R.string.nav_alarms), current = true) {}
        NavItem(stringResource(R.string.nav_night), current = false) { nav.navigate(Routes.NIGHT) }
        NavItem(stringResource(R.string.settings), current = false) { nav.navigate(Routes.SETTINGS) }
    }
}

@Composable
private fun RowScope.NavItem(label: String, current: Boolean, onClick: () -> Unit) {
    val t = tokens
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = current },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(18.dp, 2.dp).clip(RoundedCornerShape(1.dp)).background(if (current) t.accent else Color.Transparent))
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = AppType.small,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) t.ink else t.muted,
        )
    }
}
