package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.Draft
import alarmclock.vanbaelen.dev.core.Limits
import alarmclock.vanbaelen.dev.core.QuickAlarm
import alarmclock.vanbaelen.dev.core.Sunrise
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.data.ScriptRow
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.NightBright
import alarmclock.vanbaelen.dev.ui.NightGlow
import alarmclock.vanbaelen.dev.ui.NightSelected
import alarmclock.vanbaelen.dev.ui.tokens
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Width of the night line, in frame units. */
private const val LINE_W = 796

/** Dots that are ends of sleep cycles. */
private val CycleDot = AllOffLine

/**
 * Quick alarm (docs/design.md, Night display): a one-time alarm in two steps. 1 Time & sound:
 * slide along the night, then sunrise screen, vibration and sound. 2 Actions: put scripts on a
 * line around the alarm. Saved like any alarm, without repeat days, so it rings once.
 */
@Composable
internal fun QuickAlarmPanel(
    f: NightFrame,
    night: NightViewModel,
    settings: LocalSettings,
    now: Long,
    zone: ZoneId,
    onClose: (toClock: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val t = tokens
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scripts by night.scripts.collectAsState()
    val agenda by night.agenda.collectAsState()
    val scheduled by night.scheduled.collectAsState()
    val alarms by night.alarms.collectAsState()

    // The slider starts at the next 5-minute mark, and at 07:00 (or its far end) when that is in reach.
    val first = remember { QuickAlarm.first(Instant.ofEpochMilli(now).atZone(zone)) }
    var step by remember {
        val seven = first.toLocalDate().atTime(7, 0).atZone(zone).let { if (it.isAfter(first)) it else it.plusDays(1) }
        mutableIntStateOf((Duration.between(first, seven).toMinutes() / QuickAlarm.STEP).toInt().coerceIn(0, QuickAlarm.STEPS))
    }
    var page by remember { mutableIntStateOf(1) }
    var sunrise by remember { mutableStateOf(true) }
    var vibrate by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf<String?>(null) }
    var skip by remember { mutableStateOf(true) }
    var placed by remember { mutableStateOf(emptyMap<String, Int>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var set by remember { mutableStateOf<QuickSet?>(null) }
    var saving by remember { mutableStateOf(false) }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            sound = uri?.toString()
        }
    }

    val ring = first.plusMinutes(step * QuickAlarm.STEP.toLong())
    val ringMs = ring.toInstant().toEpochMilli()
    val minutes = ((ringMs - now) / 60_000L).toInt()
    val timeText = Format.clock(context, ring.toLocalTime())
    val names = scripts.associate { it.entityId to it.name }
    val cycle = QuickAlarm.nearCycle(minutes)
    val skippable = QuickAlarm.skippable(scheduled, alarms, ringMs, now)
    val skipAlarm = skippable?.let { alarms[it.alarmId] }
    val skipName = skipAlarm?.let { a -> listOfNotNull(a.label.takeIf { it.isNotBlank() }, Format.time(context, a.time)).joinToString(" ") }
    val skipping = skip && skippable != null
    fun dur(m: Int) = Format.until(context, m * 60_000L, 0L)
    val inLine = stringResource(R.string.quick_in, Format.until(context, ringMs, now), Format.dayTitle(context, ring.toLocalDate()).lowercase())
    val schedule = placed.entries.sortedBy { it.value }.let { sorted ->
        if (sorted.isEmpty()) {
            stringResource(R.string.quick_no_actions)
        } else {
            (sorted.map { it.value to (names[it.key] ?: it.key) } + (0 to stringResource(R.string.quick_alarm_word)))
                .sortedBy { it.first }
                .joinToString(" · ") { (off, name) -> Format.time(context, ring.plusMinutes(off.toLong()).toLocalTime()) + " " + name }
        }
    }
    // The next appointment, and how long after (or before) waking up it starts.
    val event = agenda?.events?.firstOrNull { e -> e.startsAt?.isAfter(first.toInstant()) == true }
    val eventMin = event?.startsAt?.let { Duration.between(first.toInstant(), it).toMinutes().toInt() }
    val gap = eventMin?.let { it - step * QuickAlarm.STEP }
    val gapColor = when {
        gap == null -> t.muted
        gap < 0 -> t.danger
        gap < 60 -> t.warn
        else -> t.accent
    }
    val gapLine = when {
        gap == null -> ""
        gap == 0 -> stringResource(R.string.quick_gap_same)
        gap < 0 -> stringResource(R.string.quick_gap_before, dur(-gap))
        else -> stringResource(R.string.quick_gap_after, dur(gap))
    }
    val eventClock = event?.startsAt?.let { Format.time(context, it.toEpochMilli()) }
    val eventText = event?.let { e -> e.startsAt?.let { s -> e.summary + ", " + Format.dayTitle(context, s.atZone(zone).toLocalDate()) + " " + eventClock } }
    val defaultSoundName = stringResource(R.string.sound_default)
    val soundText = remember(sound, settings.defaultSoundUri) { soundName(context, sound ?: settings.defaultSoundUri) } ?: defaultSoundName
    // Resolved while composing, not inside lambdas (lint: LocalContextGetResourceValueCall).
    val quickName = stringResource(R.string.quick_alarm)
    val sliderLabel = stringResource(R.string.quick_slider)

    fun save() {
        if (saving) return
        saving = true
        val draft = Draft(
            label = quickName,
            time = DateTimeFormatter.ofPattern("HH:mm").format(ring),
            actions = placed.map { (id, off) -> QuickAlarm.action(id, off) },
        )
        scope.launch {
            set = night.saveQuick(draft, sound, vibrate, sunrise, if (skipping) skippable?.alarmId else null)
            saving = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Breadcrumb, the two steps, and Set.
        Row(Modifier.fillMaxWidth().padding(horizontal = f.dp(14), vertical = f.dp(6)), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                NightPill(f, stringResource(R.string.night_display), icon = AppIcons.back, muted = true) { onClose(false) }
                Text("/", style = nightText(f, 15), color = t.muted)
                Text(stringResource(R.string.quick_alarm), style = nightText(f, 15, FontWeight.SemiBold), color = NightBright, modifier = Modifier.padding(horizontal = f.dp(8)))
            }
            Row(
                Modifier.clip(RoundedCornerShape(f.dp(24))).border(1.dp, t.line, RoundedCornerShape(f.dp(24))).padding(f.dp(3)),
                horizontalArrangement = Arrangement.spacedBy(f.dp(4)),
            ) {
                StepTab(f, 1, stringResource(R.string.quick_step_time), page == 1) { page = 1 }
                StepTab(f, 2, stringResource(R.string.quick_step_actions, placed.size), page == 2) { page = 2 }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                NightPill(f, stringResource(R.string.quick_set_for, timeText), fill = t.accent, textColor = t.onAccent) { save() }
            }
        }

        if (page == 1) {
            Row(
                Modifier.offset(x = f.dp(24), y = f.dp(60)).fillMaxWidth().padding(end = f.dp(48)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(f.dp(22)),
            ) {
                Text(
                    timeText,
                    style = nightText(f, 96, FontWeight.ExtraLight).copy(letterSpacing = (-0.045).em, lineHeight = f.sp(96)),
                    color = NightBright,
                    maxLines = 1,
                    softWrap = false,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(f.dp(4))) {
                    Text(inLine, style = nightText(f, 15), color = NightBright, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(
                        if (cycle != null) stringResource(R.string.quick_cycle_near, cycle) else stringResource(R.string.quick_cycle_between),
                        style = nightText(f, 13),
                        color = if (cycle != null) t.accent else t.muted,
                    )
                    Text(schedule, style = nightText(f, 13), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (skipName != null) {
                    Row(
                        Modifier.heightIn(min = 44.dp).toggleable(value = skip, role = Role.Switch) { skip = it },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(f.dp(10)),
                    ) {
                        Text(stringResource(R.string.quick_skip, skipName), style = nightText(f, 13), color = t.ink, textAlign = TextAlign.End)
                        SwitchMark(f, skip)
                    }
                }
            }

            Row(
                Modifier.offset(x = f.dp(24), y = f.dp(184)).fillMaxWidth().padding(end = f.dp(48)),
                horizontalArrangement = Arrangement.spacedBy(f.dp(8)),
            ) {
                QuickOption(
                    f, AppIcons.sunrise, stringResource(R.string.sunrise_screen),
                    if (sunrise) stringResource(R.string.quick_sunrise_on, Sunrise.DEFAULT_MINUTES) else stringResource(R.string.off),
                    on = sunrise, arrow = false, modifier = Modifier.weight(1f),
                ) { sunrise = !sunrise }
                QuickOption(
                    f, AppIcons.vibrate, stringResource(R.string.vibrate),
                    stringResource(if (vibrate) R.string.state_on else R.string.off),
                    on = vibrate, arrow = false, modifier = Modifier.weight(1f),
                ) { vibrate = !vibrate }
                QuickOption(f, AppIcons.sound, stringResource(R.string.sound), soundText, on = false, arrow = false, modifier = Modifier.weight(1f)) {
                    soundPicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, (sound ?: settings.defaultSoundUri)?.let(Uri::parse)),
                    )
                }
                QuickOption(
                    f, AppIcons.script, stringResource(R.string.quick_actions),
                    if (placed.isEmpty()) stringResource(R.string.quick_none_yet) else stringResource(R.string.quick_on_line, placed.size),
                    on = false, arrow = true, modifier = Modifier.weight(1f),
                ) { page = 2 }
            }

            // The night from now: slide the handle; dots are ends of sleep cycles, the band shows the actions.
            var lineW by remember { mutableFloatStateOf(1f) }
            fun slideTo(x: Float) {
                val s = QuickAlarm.stepAt(x / lineW)
                if (s != step) {
                    step = s
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
            val fraction = QuickAlarm.fraction(step)
            val lead = (first.toInstant().toEpochMilli() - now) / 60_000f
            val offsets = placed.values
            Box(
                Modifier
                    .offset(x = f.dp(24), y = f.dp(262))
                    .size(f.dp(LINE_W), f.dp(112))
                    .onSizeChanged { lineW = it.width.toFloat() }
                    .pointerInput(Unit) { detectDragGestures(onDragStart = { slideTo(it.x) }, onDrag = { change, _ -> change.consume(); slideTo(change.position.x) }) }
                    .pointerInput(Unit) { detectTapGestures { slideTo(it.x) } }
                    .semantics {
                        contentDescription = sliderLabel
                        stateDescription = "$timeText, $inLine"
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, QuickAlarm.STEPS - 1)
                        setProgress { target ->
                            step = QuickAlarm.stepAt(target)
                            true
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val y = f.dp(52).toPx()
                    drawLine(t.line, Offset(0f, y), Offset(w, y), 2.dp.toPx())
                    drawLine(AllOffLine, Offset(0f, y), Offset(w * fraction, y), 2.dp.toPx())
                    if (offsets.isNotEmpty()) {
                        val a = w * QuickAlarm.fraction(step + offsets.min() / QuickAlarm.STEP)
                        val b = w * QuickAlarm.fraction(step + offsets.max() / QuickAlarm.STEP)
                        drawRoundRect(t.accent, Offset(a, f.dp(78).toPx()), Size(maxOf(b - a, 4.dp.toPx()), f.dp(4).toPx()), CornerRadius(2.dp.toPx()), alpha = 0.45f)
                    }
                    QuickAlarm.CYCLE_ENDS.forEach { m ->
                        val fr = (m - lead) / QuickAlarm.RANGE
                        if (fr in 0f..1f) drawCircle(if (cycle == QuickAlarm.CYCLE_ENDS.indexOf(m) + 1) t.accent else CycleDot, f.dp(3).toPx(), Offset(w * fr, f.dp(39).toPx()))
                    }
                    if (eventMin != null && eventMin in 0..QuickAlarm.RANGE) {
                        val ex = w * eventMin / QuickAlarm.RANGE
                        drawRoundRect(NightBright, Offset(ex - f.dp(7).toPx(), f.dp(46).toPx()), Size(f.dp(14).toPx(), f.dp(14).toPx()), CornerRadius(f.dp(4).toPx()), style = Stroke(2.dp.toPx()))
                        drawLine(
                            gapColor, Offset(w * fraction, f.dp(79).toPx()), Offset(ex, f.dp(79).toPx()), 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())), alpha = 0.8f,
                        )
                    }
                    skippable?.let { r ->
                        val rx = w * ((r.ringAt - first.toInstant().toEpochMilli()) / 60_000f / QuickAlarm.RANGE)
                        if (rx in 0f..w) drawCircle(if (skipping) CycleDot else t.ink, f.dp(6).toPx(), Offset(rx, y), style = Stroke(2.dp.toPx()))
                    }
                }
                // Time marks every two hours on the hour.
                for (k in 0..QuickAlarm.STEPS) {
                    val at = first.plusMinutes(k * QuickAlarm.STEP.toLong())
                    if (at.minute == 0 && at.hour % 2 == 0) {
                        Text(
                            Format.time(context, at.toLocalTime()),
                            style = nightText(f, 12),
                            color = t.muted,
                            modifier = Modifier.centeredAt(f.dp(LINE_W * k.toFloat() / QuickAlarm.STEPS), f.dp(90)),
                        )
                    }
                }
                if (eventClock != null && eventMin != null && eventMin in 0..QuickAlarm.RANGE) {
                    Text(
                        "▢ $eventClock",
                        style = nightText(f, 11, FontWeight.SemiBold),
                        color = NightBright,
                        modifier = Modifier.centeredAt(f.dp(LINE_W * eventMin.toFloat() / QuickAlarm.RANGE), f.dp(22)),
                    )
                }
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(f.dp(6))) {
                    if (eventText != null) {
                        Icon(AppIcons.calendar, null, tint = t.muted, modifier = Modifier.size(f.dp(15)))
                        Text(stringResource(R.string.quick_next_event), style = nightText(f, 13), color = t.muted)
                        Text(eventText, style = nightText(f, 13, FontWeight.Medium), color = NightBright, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(gapLine, style = nightText(f, 13, FontWeight.SemiBold), color = gapColor, maxLines = 1)
                    }
                }
                Box(
                    Modifier
                        .offset(x = f.dp(LINE_W * fraction - 20f), y = f.dp(33))
                        .size(f.dp(40))
                        .clip(CircleShape)
                        .background(t.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.slideH, null, tint = t.onAccent, modifier = Modifier.size(f.dp(18)))
                }
            }
        } else {
            ActionsPage(
                f = f,
                scripts = scripts,
                timeText = timeText,
                ring = ring,
                placed = placed,
                selected = selected,
                onPlaced = { placed = it },
                onSelect = { selected = it },
            )
        }

        set?.let { done ->
            Column(
                Modifier.fillMaxSize().background(t.bg).padding(horizontal = f.dp(24)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(f.dp(8), Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.quick_set_title), style = nightText(f, 13, FontWeight.SemiBold), color = t.muted)
                Text(
                    timeText,
                    style = nightText(f, 112, FontWeight.ExtraLight).copy(letterSpacing = (-0.045).em, lineHeight = f.sp(112)),
                    color = NightGlow,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(stringResource(R.string.quick_rings_once, inLine), style = nightText(f, 15), color = NightBright)
                Text(
                    listOfNotNull(if (sunrise) stringResource(R.string.sunrise_screen) else null, soundText, if (vibrate) stringResource(R.string.vibrate).lowercase() else null).joinToString(" · "),
                    style = nightText(f, 13),
                    color = t.muted,
                )
                if (eventText != null) {
                    Text(stringResource(R.string.quick_next, eventText, gapLine), style = nightText(f, 13), color = NightBright, textAlign = TextAlign.Center)
                }
                Text(schedule, style = nightText(f, 13), color = t.muted, textAlign = TextAlign.Center)
                if (done.skipped != null && skipName != null) {
                    Text(stringResource(R.string.quick_skip_note, skipName), style = nightText(f, 13), color = t.muted)
                }
                Row(Modifier.padding(top = f.dp(12)), horizontalArrangement = Arrangement.spacedBy(f.dp(10))) {
                    NightPill(f, stringResource(R.string.quick_undo), outlined = true) {
                        scope.launch {
                            night.undoQuick(done)
                            set = null
                        }
                    }
                    NightPill(f, stringResource(R.string.quick_back), fill = t.accent, textColor = t.onAccent) { onClose(true) }
                }
            }
        }
    }
}

/** A tab of the two steps: a number in a ring and its name. */
@Composable
private fun StepTab(f: NightFrame, n: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier
            .heightIn(min = maxOf(44.dp, f.dp(38)))
            .clip(RoundedCornerShape(f.dp(19)))
            .background(if (selected) NightSelected else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(start = f.dp(6), end = f.dp(14)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(f.dp(8)),
    ) {
        Box(Modifier.size(f.dp(26)).border(1.dp, if (selected) t.accent else t.line, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = nightText(f, 12, FontWeight.SemiBold), color = if (selected) NightBright else t.muted)
        }
        Text(label, style = nightText(f, 14, FontWeight.Medium), color = if (selected) NightBright else t.muted)
    }
}

/** A setting of the quick alarm: a switch-like tile, or (with [arrow]) a way to the next step. */
@Composable
private fun QuickOption(f: NightFrame, icon: ImageVector, name: String, state: String, on: Boolean, arrow: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(16))
    Row(
        modifier
            .heightIn(min = maxOf(48.dp, f.dp(54)))
            .clip(shape)
            .background(if (on) NightSelected else Color.Transparent)
            .border(1.dp, if (arrow) AllOffLine else if (on) t.accent else t.line, shape)
            .clickable(role = if (arrow) Role.Button else Role.Switch, onClick = onClick)
            .padding(horizontal = f.dp(12), vertical = f.dp(6)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(f.dp(10)),
    ) {
        Icon(icon, null, tint = if (on || arrow) t.accent else t.muted, modifier = Modifier.size(f.dp(20)))
        Column(Modifier.weight(1f)) {
            Text(name, style = nightText(f, 14, FontWeight.Medium), color = NightBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, style = nightText(f, 12), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (arrow) Icon(AppIcons.chevron, null, tint = t.accent, modifier = Modifier.size(f.dp(16)))
    }
}

/**
 * Step 2: the actions on a line from an hour before to half an hour after the alarm. Tap a script
 * below to put it on the line, slide an action along it to move it, take it away again.
 */
@Composable
private fun ActionsPage(
    f: NightFrame,
    scripts: List<ScriptRow>,
    timeText: String,
    ring: java.time.ZonedDateTime,
    placed: Map<String, Int>,
    selected: String?,
    onPlaced: (Map<String, Int>) -> Unit,
    onSelect: (String?) -> Unit,
) {
    val context = LocalContext.current
    val t = tokens
    val haptic = LocalHapticFeedback.current
    val rows = scripts.associateBy { it.entityId }
    val span = (QuickAlarm.OFFSET_TO - QuickAlarm.OFFSET_FROM).toFloat()
    fun frac(offset: Int) = (offset - QuickAlarm.OFFSET_FROM) / span
    val current by rememberUpdatedState(placed)
    var trackW by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<String?>(null) }

    fun offsetAt(x: Float): Int = (((x / trackW).coerceIn(0f, 1f) * span + QuickAlarm.OFFSET_FROM) / QuickAlarm.STEP).roundToInt() * QuickAlarm.STEP
    fun moveTo(id: String, offset: Int) {
        val free = QuickAlarm.freeSlot(offset.coerceIn(QuickAlarm.OFFSET_FROM, QuickAlarm.OFFSET_TO), current.filterKeys { it != id }.values)
        if (current[id] != free) {
            onPlaced(current + (id to free))
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    fun add(id: String) {
        if (current.size >= Limits.ACTIONS_PER_ALARM) return
        onPlaced(current + (id to QuickAlarm.freeSlot(QuickAlarm.DEFAULT_OFFSET, current.values)))
        onSelect(id)
    }
    fun timeOf(offset: Int) = Format.time(context, ring.plusMinutes(offset.toLong()).toLocalTime())

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.offset(x = f.dp(24), y = f.dp(60)), horizontalArrangement = Arrangement.spacedBy(f.dp(14)), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.quick_actions_around, timeText), style = nightText(f, 17, FontWeight.SemiBold), color = NightBright)
            Text(stringResource(R.string.quick_actions_hint), style = nightText(f, 13), color = t.muted)
        }

        // The line: ticks every 15 minutes, the alarm on top, the actions as round buttons.
        Box(
            Modifier
                .offset(x = f.dp(40), y = f.dp(96))
                .size(f.dp(764), f.dp(150))
                .onSizeChanged { trackW = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val near = current.minByOrNull { abs(frac(it.value) * trackW - p.x) }
                            dragging = near?.takeIf { abs(frac(it.value) * trackW - p.x) < 28.dp.toPx() }?.key
                            dragging?.let(onSelect)
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                        onDrag = { change, _ ->
                            change.consume()
                            dragging?.let { moveTo(it, offsetAt(change.position.x)) }
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val y = f.dp(76).toPx()
                drawLine(t.line, Offset(0f, y), Offset(w, y), 2.dp.toPx())
                if (current.isNotEmpty()) {
                    drawLine(AllOffLine, Offset(w * frac(minOf(0, current.values.min())), y), Offset(w * frac(maxOf(0, current.values.max())), y), 2.dp.toPx())
                }
                for (o in QuickAlarm.OFFSET_FROM..QuickAlarm.OFFSET_TO step 15) drawLine(t.line, Offset(w * frac(o), y - 5.dp.toPx()), Offset(w * frac(o), y + 5.dp.toPx()), 1.dp.toPx())
                drawLine(t.accent, Offset(w * frac(0), f.dp(30).toPx()), Offset(w * frac(0), y), 2.dp.toPx())
            }
            for (o in QuickAlarm.OFFSET_FROM..QuickAlarm.OFFSET_TO step 15) {
                Text(timeOf(o), style = nightText(f, 12), color = t.muted, modifier = Modifier.centeredAt(f.dp(764f * frac(o)), f.dp(128)))
            }
            Row(
                Modifier.centeredAt(f.dp(764f * frac(0)), f.dp(2)).clip(RoundedCornerShape(f.dp(14))).background(t.accent).padding(horizontal = f.dp(10), vertical = f.dp(4)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(f.dp(5)),
            ) {
                Icon(AppIcons.bell, null, tint = t.onAccent, modifier = Modifier.size(f.dp(14)))
                Text(timeText, style = nightText(f, 13, FontWeight.SemiBold), color = t.onAccent)
            }
            placed.forEach { (id, off) ->
                val on = selected == id
                val label = rows[id]?.name ?: id
                Box(
                    Modifier
                        .offset(x = f.dp(764f * frac(off) - 22f), y = f.dp(55))
                        .size(f.dp(44))
                        .clip(CircleShape)
                        .background(if (on) t.accent else NightSelected)
                        .border(1.5.dp, t.accent, CircleShape)
                        .semantics { contentDescription = "$label, ${timeOf(off)}" }
                        .clickable(role = Role.Button) { onSelect(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.mdi(rows[id]?.iconPath) ?: AppIcons.script, null, tint = if (on) t.onAccent else NightBright, modifier = Modifier.size(f.dp(20)))
                }
                Text(timeOf(off), style = nightText(f, 11), color = if (on) t.accent else t.muted, modifier = Modifier.centeredAt(f.dp(764f * frac(off)), f.dp(102)))
            }
        }

        // The chosen action: where it is, a nudge either way, or take it away.
        Row(
            Modifier.offset(x = f.dp(40), y = f.dp(250)).fillMaxWidth().padding(end = f.dp(80)).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(f.dp(10)),
        ) {
            val id = selected?.takeIf { it in placed }
            if (id != null) {
                val off = placed.getValue(id)
                Icon(AppIcons.mdi(rows[id]?.iconPath) ?: AppIcons.script, null, tint = t.accent, modifier = Modifier.size(f.dp(18)))
                Text(rows[id]?.name ?: id, style = nightText(f, 14, FontWeight.Medium), color = NightBright, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(timeOf(off) + " · " + Format.relative(context, off, null), style = nightText(f, 14), color = t.muted, modifier = Modifier.weight(1f))
                NightPill(f, "−5", outlined = true, description = stringResource(R.string.quick_earlier)) { moveTo(id, off - QuickAlarm.STEP) }
                NightPill(f, "+5", outlined = true, description = stringResource(R.string.quick_later)) { moveTo(id, off + QuickAlarm.STEP) }
                NightPill(f, stringResource(R.string.quick_take_away), outlined = true) {
                    onPlaced(placed - id)
                    onSelect(null)
                }
            } else {
                Text(stringResource(R.string.quick_pick_hint), style = nightText(f, 13), color = t.muted)
            }
        }

        // The scripts that are not on the line yet.
        val tray = scripts.filter { it.entityId !in placed }
        Row(
            Modifier
                .offset(x = f.dp(40), y = f.dp(306))
                .size(f.dp(764), f.dp(56))
                .clip(RoundedCornerShape(f.dp(28)))
                .border(1.dp, t.line, RoundedCornerShape(f.dp(28)))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = f.dp(8)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(f.dp(8)),
        ) {
            tray.forEach { s -> NightPill(f, s.name, icon = AppIcons.mdi(s.iconPath) ?: AppIcons.script, outlined = true) { add(s.entityId) } }
            if (scripts.isEmpty()) Text(stringResource(R.string.quick_no_scripts), style = nightText(f, 13), color = t.muted, modifier = Modifier.padding(start = f.dp(10)))
            else if (tray.isEmpty()) Text(stringResource(R.string.quick_tray_empty), style = nightText(f, 13), color = t.muted, modifier = Modifier.padding(start = f.dp(10)))
        }
    }
}
