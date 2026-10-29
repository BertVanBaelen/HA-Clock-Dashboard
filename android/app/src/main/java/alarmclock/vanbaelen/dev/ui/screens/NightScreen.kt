package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.App
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.Alarm
import alarmclock.vanbaelen.dev.core.NightAlarm
import alarmclock.vanbaelen.dev.core.NightPlan
import alarmclock.vanbaelen.dev.core.NightWidget
import alarmclock.vanbaelen.dev.core.Schedule
import alarmclock.vanbaelen.dev.core.Sunrise
import alarmclock.vanbaelen.dev.core.Timeline
import alarmclock.vanbaelen.dev.core.signedOffset
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.data.SaveResult
import alarmclock.vanbaelen.dev.data.ScriptRow
import alarmclock.vanbaelen.dev.ui.AlarmItem
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.LocalAppColors
import alarmclock.vanbaelen.dev.ui.MainViewModel
import alarmclock.vanbaelen.dev.ui.NightGlow
import alarmclock.vanbaelen.dev.ui.NightTokens
import alarmclock.vanbaelen.dev.ui.tokens
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min
import kotlin.random.Random

/** Night display state the ringing screen reads (same process). */
object NightDisplay {
    /** Alarm id and ring time whose sunrise the night display showed: the ringing screen then starts bright. */
    @Volatile
    var sunriseShown: Pair<String, Long>? = null
}

/** Resting clock, widgets open, or choosing widgets (docs/design.md, Night display). */
enum class NightMode { IDLE, WIDGETS, EDIT }

class NightViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val mode = MutableStateFlow(NightMode.IDLE)
    /** A short message at the bottom of the screen, cleared after a moment. */
    val flash = MutableStateFlow<String?>(null)
    val scripts: StateFlow<List<ScriptRow>> = app.repository.scripts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val weather = app.night.weather
    val home = app.night.home
    val agenda = app.night.agenda
    val problem = app.night.problem

    /** A tap on the screen: open the widgets, hide them, or leave the widget choice. */
    fun tap() {
        mode.value = when (mode.value) {
            NightMode.IDLE -> NightMode.WIDGETS
            NightMode.WIDGETS -> NightMode.IDLE
            NightMode.EDIT -> NightMode.WIDGETS
        }
    }

    suspend fun refresh(date: LocalDate) = app.night.refresh(date, ZoneId.systemDefault())

    fun updateSettings(transform: (LocalSettings) -> LocalSettings) = app.prefs.updateSettings(transform)

    fun setNightLight(entityId: String, on: Boolean) {
        viewModelScope.launch { app.night.setSwitch(entityId, on)?.let { flash.value = Format.problem(app, it) } }
    }

    fun setSkip(alarm: Alarm, skip: Boolean) {
        viewModelScope.launch {
            val result = app.repository.setSkip(alarm, skip)
            flash.value = if (result is SaveResult.Failed) {
                Format.problem(app, result.problem, result.message)
            } else {
                app.getString(if (skip) R.string.skipped else R.string.skip_undone)
            }
        }
    }

    /** Alarm id to the time its "Skip next" skips, for enabled repeating alarms. */
    fun skippedTimes(items: List<AlarmItem>, zone: ZoneId, now: Instant): Map<String, Long> {
        val marks = app.engine.store.skips
        return items.mapNotNull { item ->
            val a = item.alarm
            if (!a.enabled || !a.skipNext || !a.repeating || !Schedule.isValidTime(a.time)) return@mapNotNull null
            val date = marks[a.id]?.takeIf { it.revision == a.revision }?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: Schedule.skippedDate(a.time, a.days, true, zone, now)
                ?: return@mapNotNull null
            NightPlan.skippedAt(a.time, date, zone)?.let { a.id to it.toEpochMilli() }
        }.toMap()
    }
}

/** The mockup's 844 × 390 landscape frame, scaled to the screen; sizes ignore the font scale so the layout holds. */
internal class NightFrame(val scale: Float, private val density: Density) {
    fun dp(v: Number): Dp = (v.toFloat() * scale).dp
    fun sp(v: Number): TextUnit = with(density) { (v.toFloat() * scale).dp.toSp() }
}

/** Text style with tabular figures at a frame size. */
internal fun nightText(f: NightFrame, size: Number, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFeatureSettings = "tnum", fontSize = f.sp(size), lineHeight = f.sp(size.toFloat() * 1.4f), fontWeight = weight)

/** Charge level from the battery broadcast; [fullAt] when the phone expects to be full. */
data class BatteryState(val percent: Int, val plugged: Boolean, val full: Boolean, val fullAt: Long?)

private fun mix(a: Float, b: Float, p: Float) = a + (b - a) * p

/**
 * Bedside clock (AFR-NGT-01, docs/design.md, Night display): black, dim and landscape, the screen
 * kept on, the clock shifting a little against burn-in. A tap or swipe up shows widgets. A sunrise
 * alarm brightens the screen before it rings. A ringing alarm takes over; when it is stopped
 * (not snoozed) the night display closes.
 */
@Composable
fun NightScreen(vm: MainViewModel, onExit: () -> Unit, onRinging: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val night: NightViewModel = viewModel()
    val ringing by vm.ringing.collectAsState()
    val scheduled by vm.scheduled.collectAsState()
    val items by vm.items.collectAsState()
    val settings by vm.settings.collectAsState()
    val mode by night.mode.collectAsState()
    val flash by night.flash.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var shift by remember { mutableStateOf(Offset.Zero) }
    var rang by remember { mutableStateOf<String?>(null) }
    val battery = rememberBattery()
    val zone = ZoneId.systemDefault()

    DisposableEffect(Unit) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        val orientation = activity?.requestedOrientation
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // Recreated for another configuration change: the new screen sets everything again.
            if (activity?.isChangingConfigurations == true) return@onDispose
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            bars?.show(WindowInsetsCompat.Type.systemBars())
            if (orientation != null) activity?.requestedOrientation = orientation
            if ((context.applicationContext as App).engine.ringing.value == null) NightDisplay.sunriseShown = null
        }
    }
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            now = System.currentTimeMillis()
            if (++ticks % 180 == 0) shift = Offset(Random.nextInt(-10, 11).toFloat(), Random.nextInt(-6, 7).toFloat())
            delay(1_000)
        }
    }
    LaunchedEffect(ringing) {
        val ring = ringing
        if (ring != null) {
            rang = ring.alarmId
            onRinging()
        } else if (rang != null) {
            // Stopped means getting up; snoozed means back to sleep.
            delay(1_000)
            if (vm.scheduled.value.none { it.snooze && it.alarmId == rang }) onExit() else rang = null
        }
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(1_800)
            night.flash.value = null
        }
    }
    BackHandler {
        if (mode == NightMode.EDIT) night.mode.value = NightMode.WIDGETS else onExit()
    }

    // The sunrise before an alarm: colours and window brightness follow its progress.
    val sunrise = Sunrise.running(scheduled, now)
    SideEffect { if (sunrise != null) NightDisplay.sunriseShown = sunrise.first.alarmId to sunrise.first.ringAt }
    val brightness = sunrise?.let { Sunrise.brightness(it.second) } ?: Sunrise.NIGHT_BRIGHTNESS
    LaunchedEffect(brightness) {
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
    }
    val bg by animateColorAsState(sunrise?.let { Color(Sunrise.background(it.second)) } ?: NightTokens.bg, tween(1_000), label = "bg")
    val ink by animateColorAsState(sunrise?.let { Color(Sunrise.ink(it.second)) } ?: NightTokens.ink, tween(1_000), label = "ink")
    val palette = if (sunrise == null) NightTokens else NightTokens.copy(
        bg = bg, surface = bg, ink = ink, muted = ink.copy(alpha = 0.75f), line = ink.copy(alpha = 0.3f), soft = ink.copy(alpha = 0.1f),
    )
    val glow = if (sunrise == null) NightGlow else ink

    // The alarm the screen is about, and the day the widgets look ahead to.
    val skipped = remember(items, scheduled) { night.skippedTimes(items, zone, Instant.now()) }
    val upcoming = NightPlan.upcoming(scheduled, skipped, now)
    val nextNormal = scheduled.firstOrNull { !it.snooze && it.ringAt > now }
    val snoozed = scheduled.filter { it.snooze && it.ringAt > now }.minByOrNull { it.ringAt }
    val planItem = upcoming?.let { u -> items.firstOrNull { it.alarm.id == u.alarmId } }
    val target = NightPlan.targetDate(nextNormal?.ringAt, ZonedDateTime.now(zone))
    val sources = listOf(
        settings.nightWidgets, settings.weatherEntity, settings.calendarEntity,
        settings.roomTemperatureEntity, settings.nightLightEntity, settings.homeEntities,
    )
    LaunchedEffect(target, sources) {
        while (true) {
            night.refresh(target)
            delay(15 * 60_000L)
        }
    }
    val alarmLine = when {
        snoozed != null && (upcoming == null || snoozed.ringAt <= upcoming.ringAt) ->
            stringResource(R.string.snoozed_until, Format.time(context, snoozed.ringAt))
        upcoming == null -> stringResource(R.string.no_next_alarm)
        upcoming.skipped -> nextNormal?.let { stringResource(R.string.night_skipped_next, Format.ring(context, it.ringAt, now)) }
            ?: stringResource(R.string.night_skipped_none)
        else -> stringResource(R.string.night_alarm_line, Format.ring(context, upcoming.ringAt, now), Format.until(context, upcoming.ringAt, now))
    }
    val batteryLine = if (battery.plugged) stringResource(R.string.night_charging, battery.percent) else stringResource(R.string.night_battery, battery.percent)

    CompositionLocalProvider(LocalAppColors provides palette) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.bg)
                .pointerInput(Unit) { detectTapGestures(onTap = { night.tap() }) }
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = {
                            val limit = 70.dp.toPx()
                            if (dy < -limit) night.mode.value = NightMode.WIDGETS else if (dy > limit) night.mode.value = NightMode.IDLE
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            dy += amount
                        },
                    )
                }
                .windowInsetsPadding(WindowInsets.displayCutout),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val f = NightFrame(min(maxWidth / 844.dp, maxHeight / 390.dp), LocalDensity.current)
                val dim = when {
                    sunrise != null -> 1f
                    settings.nightDimmer -> 0.45f
                    else -> 0.9f
                }
                val contentAlpha by animateFloatAsState(dim, tween(250), label = "dim")
                Box(Modifier.size(f.dp(844), f.dp(390)).graphicsLayer { alpha = contentAlpha }) {
                    NightContent(
                        f = f,
                        night = night,
                        mode = mode,
                        settings = settings,
                        now = now,
                        zone = zone,
                        shift = shift,
                        glow = glow,
                        alarmLine = alarmLine,
                        batteryLine = batteryLine,
                        battery = battery,
                        upcoming = upcoming,
                        planItem = planItem,
                        target = target,
                        flash = flash,
                        onSettings = onSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun NightContent(
    f: NightFrame,
    night: NightViewModel,
    mode: NightMode,
    settings: LocalSettings,
    now: Long,
    zone: ZoneId,
    shift: Offset,
    glow: Color,
    alarmLine: String,
    batteryLine: String,
    battery: BatteryState,
    upcoming: NightAlarm?,
    planItem: AlarmItem?,
    target: LocalDate,
    flash: String?,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val t = tokens
    val idle = mode == NightMode.IDLE
    val w by animateFloatAsState(if (idle) 0f else 1f, tween(600, easing = EaseSoft), label = "clock")
    val idleA by animateFloatAsState(if (idle) 1f else 0f, tween(300), label = "idle")
    val sideA by animateFloatAsState(if (idle) 0f else 1f, tween(350, delayMillis = if (idle) 0 else 200), label = "side")
    val cardsA by animateFloatAsState(if (mode == NightMode.WIDGETS) 1f else 0f, tween(350, delayMillis = if (mode == NightMode.WIDGETS) 150 else 0), label = "cards")
    val editA by animateFloatAsState(if (mode == NightMode.EDIT) 1f else 0f, tween(300), label = "edit")
    var clockSize by remember { mutableStateOf(IntSize.Zero) }
    val shiftX = f.dp(shift.x)
    val shiftY = f.dp(shift.y)

    Box(Modifier.fillMaxSize()) {
        // The clock: big and centred while resting, small at the top left with the widgets.
        Text(
            Format.clock(context, Instant.ofEpochMilli(now).atZone(zone).toLocalTime()),
            style = nightText(f, 176, FontWeight.ExtraLight).copy(letterSpacing = (-0.045).em, lineHeight = f.sp(176)),
            color = glow,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .onSizeChanged { clockSize = it }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    val idleX = (f.dp(844).toPx() - clockSize.width) / 2f + shiftX.toPx()
                    val idleY = f.dp(44).toPx() + shiftY.toPx()
                    translationX = mix(idleX, f.dp(32).toPx(), w)
                    translationY = mix(idleY, f.dp(30).toPx(), w)
                    scaleX = 1f - 0.56f * w
                    scaleY = scaleX
                },
        )

        // Resting: next alarm, its actions on a line, and the hint.
        Row(
            Modifier
                .fillMaxWidth()
                .offset(x = shiftX, y = f.dp(240) + shiftY)
                .graphicsLayer { alpha = idleA },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.bell, null, tint = t.muted, modifier = Modifier.size(f.dp(16)))
            Spacer(Modifier.width(f.dp(8)))
            Text(alarmLine, style = nightText(f, 16), color = t.muted)
        }
        planItem?.let { item ->
            val offsets = item.alarm.actions.filter { it.enabled }.mapNotNull { it.signedOffset }
            if (offsets.isNotEmpty() && upcoming != null) {
                NightTimeline(
                    f, offsets, upcoming.ringAt,
                    Modifier.offset(x = f.dp(222) + shiftX, y = f.dp(296) + shiftY).graphicsLayer { alpha = idleA * if (upcoming.skipped) 0.3f else 1f },
                )
            }
        }
        Text(
            stringResource(R.string.night_hint),
            style = nightText(f, 12),
            color = t.muted,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = -f.dp(24), y = f.dp(20)).graphicsLayer { alpha = 0.75f * idleA },
        )

        // With the widgets: alarm and battery under the small clock, the dimmer, and the buttons.
        Column(
            Modifier.offset(x = f.dp(32), y = f.dp(112)).width(f.dp(230)).graphicsLayer { alpha = sideA },
            verticalArrangement = Arrangement.spacedBy(f.dp(8)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(f.dp(8))) {
                Icon(AppIcons.bell, null, tint = t.ink, modifier = Modifier.size(f.dp(15)))
                Text(alarmLine, style = nightText(f, 14), color = t.ink)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(f.dp(8))) {
                Icon(AppIcons.bolt, null, tint = t.muted, modifier = Modifier.size(f.dp(15)))
                Text(batteryLine, style = nightText(f, 13), color = t.muted)
            }
        }
        if (!idle) {
            NightPill(
                f,
                stringResource(if (settings.nightDimmer) R.string.night_dimmer_on else R.string.night_dimmer),
                icon = if (settings.nightDimmer) AppIcons.eyeClosed else AppIcons.eye,
                outlined = true,
                fill = if (settings.nightDimmer) t.soft else Color.Transparent,
                description = stringResource(R.string.night_dimmer_hint),
                modifier = Modifier.offset(x = f.dp(24), y = f.dp(318)).graphicsLayer { alpha = sideA },
            ) { night.updateSettings { it.copy(nightDimmer = !it.nightDimmer) } }
            Row(
                Modifier.align(Alignment.TopEnd).offset(x = -f.dp(16), y = f.dp(10)).graphicsLayer { alpha = sideA },
                horizontalArrangement = Arrangement.spacedBy(f.dp(6)),
            ) {
                if (mode == NightMode.WIDGETS) {
                    NightPill(f, stringResource(R.string.night_edit_widgets), muted = true) { night.mode.value = NightMode.EDIT }
                    NightPill(f, stringResource(R.string.night_hide), icon = AppIcons.moon, outlined = true) { night.mode.value = NightMode.IDLE }
                } else {
                    NightPill(f, stringResource(R.string.done), fill = t.accent, textColor = t.onAccent) { night.mode.value = NightMode.WIDGETS }
                }
            }
        }
        if (mode == NightMode.WIDGETS || cardsA > 0f) {
            NightWidgets(
                f = f,
                night = night,
                settings = settings,
                battery = battery,
                upcoming = upcoming,
                planItem = planItem,
                target = target,
                zone = zone,
                onSettings = onSettings,
                modifier = Modifier
                    .offset(x = f.dp(280), y = f.dp(66))
                    .graphicsLayer {
                        alpha = cardsA
                        translationY = (1f - cardsA) * f.dp(16).toPx()
                    },
            )
        }
        if (mode == NightMode.EDIT || editA > 0f) {
            WidgetChoice(
                f = f,
                night = night,
                settings = settings,
                onSettings = onSettings,
                modifier = Modifier.offset(x = f.dp(280), y = f.dp(66)).graphicsLayer { alpha = editA },
            )
        }
        Text(
            flash ?: "",
            style = nightText(f, 14),
            color = t.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().offset(y = f.dp(356)).graphicsLayer { alpha = if (flash != null) 1f else 0f },
        )
    }
}

/** The next alarm's actions as dots on a 400 wide line, with the first action, the alarm and the last action labelled. */
@Composable
private fun NightTimeline(f: NightFrame, offsets: List<Int>, ringAt: Long, modifier: Modifier) {
    val context = LocalContext.current
    val t = tokens
    val range = Timeline.range(offsets)
    Box(modifier.size(f.dp(400), f.dp(44))) {
        Canvas(Modifier.offset(y = f.dp(8)).size(f.dp(400), f.dp(1))) {
            drawLine(t.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            offsets.forEach { o -> drawCircle(t.muted, f.dp(3).toPx(), Offset(Timeline.miniFraction(o, range) * size.width, 0f)) }
            drawCircle(t.accent, f.dp(5.5f).toPx(), Offset(size.width / 2f, 0f))
        }
        NightPlan.edgeLabels(offsets, range).forEach { o ->
            TimelineLabel(f, Format.time(context, ringAt + o * 60_000L), Timeline.miniFraction(o, range), accent = false)
        }
        TimelineLabel(f, Format.time(context, ringAt), 0.5f, accent = true)
    }
}

@Composable
private fun TimelineLabel(f: NightFrame, text: String, fraction: Float, accent: Boolean) {
    val t = tokens
    Text(
        text,
        style = nightText(f, 12, if (accent) FontWeight.SemiBold else FontWeight.Normal),
        color = if (accent) t.accent else t.muted,
        modifier = Modifier.centeredAt(f.dp(400 * fraction), f.dp(22)),
    )
}

/** Places the element with its horizontal centre at [x] and its top at [y]. */
private fun Modifier.centeredAt(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    layout(placeable.width, placeable.height) { placeable.place(x.roundToPx() - placeable.width / 2, y.roundToPx()) }
}

/** The night display's pill buttons (Dimmer, Edit widgets, Hide, Done); at least 44 high. */
@Composable
internal fun NightPill(
    f: NightFrame,
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    outlined: Boolean = false,
    muted: Boolean = false,
    fill: Color = Color.Transparent,
    textColor: Color? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(24))
    val color = textColor ?: if (muted) t.muted else t.ink
    Row(
        modifier
            .heightIn(min = maxOf(44.dp, f.dp(44)))
            .clip(shape)
            .background(fill)
            .then(if (outlined) Modifier.border(1.dp, t.line, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = f.dp(16)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(f.dp(6)),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(f.dp(16)))
        Text(text, style = nightText(f, 14, if (fill == t.accent) FontWeight.SemiBold else FontWeight.Medium), color = color)
    }
}

/** Charge level and charging state, kept up to date while shown. */
@Composable
private fun rememberBattery(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readBattery(context, null)) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                state = readBattery(c, intent)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

internal fun readBattery(context: Context, intent: Intent?): BatteryState {
    val i = intent ?: ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val remaining = if (plugged) context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1L else -1L
    return BatteryState(
        percent = if (level >= 0 && scale > 0) level * 100 / scale else 0,
        plugged = plugged,
        full = status == BatteryManager.BATTERY_STATUS_FULL,
        fullAt = remaining.takeIf { it > 0 }?.let { System.currentTimeMillis() + it },
    )
}

/** Widgets the user turned on, in the fixed order. */
internal fun enabledWidgets(settings: LocalSettings): List<NightWidget> = NightWidget.entries.filter { it in settings.nightWidgets }
