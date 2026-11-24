package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.PlannedRing
import alarmclock.vanbaelen.dev.core.Sunrise
import alarmclock.vanbaelen.dev.core.Timing
import alarmclock.vanbaelen.dev.core.WeatherPlan
import alarmclock.vanbaelen.dev.data.AgendaInfo
import alarmclock.vanbaelen.dev.data.WeatherInfo
import alarmclock.vanbaelen.dev.ui.AppColors
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.DayTokens
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.NightTokens
import alarmclock.vanbaelen.dev.ui.rainColor
import alarmclock.vanbaelen.dev.ui.screens.NightDisplay
import alarmclock.vanbaelen.dev.ui.screens.sunTimes
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/** Ringing, snoozed from this screen, or stopped here (then the good-morning screen shows). */
private sealed interface WakePhase {
    data object Ringing : WakePhase
    data class Snoozed(val alarmId: String) : WakePhase
    data class Stopped(val ring: PlannedRing, val at: Long) : WakePhase
}

/** Window brightness while snoozed from this screen ("Dimmed while you snooze"). */
private const val SNOOZE_BRIGHTNESS = 0.05f
/** The good-morning screen closes by itself after this long. */
private const val MORNING_MS = 10 * 60_000L
private val SnoozeTokens = NightTokens.copy(bg = Color(0xFF120904))
private val Tnum = TextStyle(fontFeatureSettings = "tnum")

/**
 * The wake-up screen (docs/design.md, Night display). A sunrise alarm brightens from black to warm
 * white; when the night display already showed its sunrise, it starts bright. Swipe up to snooze;
 * snoozed here, it stays dimmed with "Snoozing until"; stopped, it shows the good-morning screen
 * with today's weather and what runs after the alarm.
 */
@Composable
fun WakeScreen(
    onSnooze: () -> Unit,
    onStop: () -> Unit,
    onBrightness: (Float) -> Unit,
    onLightBars: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val ringing by app.engine.ringing.collectAsState()
    val scheduled by app.engine.scheduled.collectAsState()
    var phase by remember { mutableStateOf<WakePhase>(WakePhase.Ringing) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(ringing) { if (ringing != null) phase = WakePhase.Ringing }
    val snoozed = (phase as? WakePhase.Snoozed)?.let { p -> scheduled.firstOrNull { it.snooze && it.alarmId == p.alarmId } }
    LaunchedEffect(ringing, phase, snoozed) {
        if (ringing != null) return@LaunchedEffect
        when (val p = phase) {
            WakePhase.Ringing -> onClose()
            is WakePhase.Snoozed -> if (snoozed == null) {
                // The snooze may still be on its way to the schedule; gone means stopped elsewhere.
                delay(1_000)
                if (app.engine.scheduled.value.none { it.snooze && it.alarmId == p.alarmId }) onClose()
            }
            is WakePhase.Stopped -> {
                delay(MORNING_MS)
                onClose()
            }
        }
    }

    val ring = ringing
    when (val p = phase) {
        WakePhase.Ringing -> if (ring != null) {
            Ringing(ring, now, onBrightness, onLightBars,
                onSnooze = {
                    phase = WakePhase.Snoozed(ring.alarmId)
                    onSnooze()
                },
                onStop = {
                    phase = WakePhase.Stopped(ring, System.currentTimeMillis())
                    onStop()
                },
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        is WakePhase.Snoozed -> if (snoozed != null) {
            LaunchedEffect(Unit) {
                onBrightness(SNOOZE_BRIGHTNESS)
                onLightBars(false)
            }
            WakeLayout(
                colors = SnoozeTokens,
                time = Format.clock(context, localTime(now)),
                headline = stringResource(R.string.snoozing_until, Format.time(context, snoozed.ringAt)),
                nudge = stringResource(R.string.swipe_snooze_again),
                status = stringResource(R.string.wake_status_snoozed),
                onSnooze = {
                    // Snooze again: one more snooze length after the current one.
                    val again = app.engine.snooze(snoozed, snoozed.snoozeMinutes.coerceIn(1, 60), Instant.ofEpochMilli(max(System.currentTimeMillis(), snoozed.ringAt)))
                    RingingService.notifySnoozed(context, again)
                },
                onStop = {
                    phase = WakePhase.Stopped(snoozed, System.currentTimeMillis())
                    runCatching { RingingService.dismissSnoozedIntent(context, snoozed).send() }
                },
            )
        } else {
            Box(Modifier.fillMaxSize().background(SnoozeTokens.bg))
        }
        is WakePhase.Stopped -> Morning(p.ring, p.at, onBrightness, onLightBars, onClose)
    }
}

private fun localTime(epochMs: Long): LocalTime = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()

@Composable
private fun Ringing(
    ring: PlannedRing,
    now: Long,
    onBrightness: (Float) -> Unit,
    onLightBars: (Boolean) -> Unit,
    onSnooze: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val sunrise = ring.sunriseMinutes > 0
    val shown = NightDisplay.sunriseShown == (ring.alarmId to ring.ringAt)
    val top = Sunrise.cap(ring)
    val p = remember(ring) { Animatable(if (sunrise && !shown) 0f else top) }
    LaunchedEffect(ring) {
        if (p.value < top) p.animateTo(top, tween(Sunrise.SHORT_MS.toInt(), easing = LinearEasing))
    }
    LaunchedEffect(ring) {
        if (!sunrise) {
            onBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            onLightBars(false)
            return@LaunchedEffect
        }
        snapshotFlow { (Sunrise.brightness(p.value) * 50).roundToInt() }.distinctUntilChanged().collect { onBrightness(it / 50f) }
    }
    val colors = if (sunrise) {
        val ink = Color(Sunrise.ink(p.value))
        NightTokens.copy(bg = Color(Sunrise.background(p.value)), ink = ink, muted = ink.copy(alpha = 0.8f))
    } else {
        NightTokens
    }
    val light = colors.bg.luminance() > 0.5f
    LaunchedEffect(light) { if (sunrise) onLightBars(light) }
    val t = localTime(now)
    WakeLayout(
        colors = colors,
        time = Format.clock(context, t),
        headline = ring.label.ifBlank { Format.greeting(context, t.hour) },
        nudge = stringResource(R.string.swipe_snooze, ring.snoozeMinutes),
        status = if (sunrise && p.value < top) stringResource(R.string.wake_status_sunrise) else null,
        onSnooze = onSnooze,
        onStop = onStop,
    )
}

/** Time and headline in the middle, the swipe-up hint, status and Stop at the bottom. */
@Composable
private fun WakeLayout(
    colors: AppColors,
    time: String,
    headline: String,
    nudge: String,
    status: String?,
    onSnooze: () -> Unit,
    onStop: () -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    // The gesture keeps running while the sunrise recomposes this every frame.
    val snooze by rememberUpdatedState(onSnooze)
    val snoozeLabel = stringResource(R.string.snooze)
    val bounce = rememberInfiniteTransition(label = "nudge")
    val nudgeY by bounce.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(keyframes { durationMillis = 2_400; 0f at 0; -6f at 720; 0f at 1_440 }, RepeatMode.Restart),
        label = "nudgeY",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (drag < -60.dp.toPx()) snooze()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag = (drag + amount).coerceAtMost(0f)
                    },
                )
            }
            .semantics { customActions = listOf(CustomAccessibilityAction(snoozeLabel) { onSnooze(); true }) }
            .safeDrawingPadding(),
    ) {
        val big = with(LocalDensity.current) { minOf(120.dp, maxWidth * 0.26f).toSp() }
        Column(
            Modifier.fillMaxSize().graphicsLayer { translationY = drag * 0.4f }.padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                time,
                style = Tnum.copy(fontSize = big, fontWeight = FontWeight.ExtraLight, letterSpacing = (-0.045).em, lineHeight = big),
                color = colors.ink,
                maxLines = 1,
            )
            Text(headline, style = Tnum.copy(fontSize = 16.sp), color = colors.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .graphicsLayer { translationY = nudgeY.dp.toPx() }
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onSnooze)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(AppIcons.up, null, tint = colors.ink, modifier = Modifier.size(22.dp))
                Text(nudge, style = Tnum.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = colors.ink)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.weight(1f).graphicsLayer { alpha = 0.8f }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status != null) {
                        Icon(AppIcons.sunrise, null, tint = colors.ink, modifier = Modifier.size(16.dp))
                        Text(status, style = Tnum.copy(fontSize = 14.sp), color = colors.ink)
                    }
                }
                Box(
                    Modifier
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(colors.accent)
                        .clickable(role = Role.Button, onClick = onStop)
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.stop), style = Tnum.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = colors.onAccent)
                }
            }
        }
    }
}

/** Dp per hour of the timeline in portrait (down) and landscape (sideways). */
private const val PORTRAIT_HOUR = 46
private const val LANDSCAPE_HOUR = 76

/** One hour of the weather column; [icon] and [chance] are null when the forecast has no entry for it. */
private class HourCell(val hour: Int, val icon: ImageVector?, val temp: String, val chance: Double?)

/** An appointment of today, in minutes of the day. */
private class DayEvent(val name: String, val place: String?, val start: Int, val end: Int, val startMs: Long, val weather: HourCell?)

/**
 * Today from the hour the alarm was stopped to the evening: the forecast by the hour, the
 * appointments, when it rains and when it is dark. All times are minutes of the day.
 */
private class DayData(
    val t0: Int,
    val t1: Int,
    val hours: List<HourCell>,
    val events: List<DayEvent>,
    val nowMin: Int,
    val rain: IntRange?,
    val dark: List<IntRange>,
    val next: Int,
)

private fun buildDay(w: WeatherInfo?, agenda: AgendaInfo?, at: Long, zone: ZoneId): DayData {
    val now = Instant.ofEpochMilli(at).atZone(zone)
    val date = now.toLocalDate()
    val t0 = now.hour * 60
    val t1 = maxOf(22, now.hour + 4).coerceAtMost(24) * 60
    fun minuteOf(i: Instant) = i.atZone(zone).let { it.hour * 60 + it.minute }
    val byHour = w?.hourly.orEmpty().mapNotNull { f -> f.at?.atZone(zone)?.takeIf { it.toLocalDate() == date }?.let { it.hour to f } }.toMap()
    val hours = if (w == null) {
        emptyList()
    } else {
        (t0 / 60 until t1 / 60).map { h ->
            val f = byHour[h]
            HourCell(h, f?.let { AppIcons.weather(it.condition ?: w.condition) }, Format.degrees(f?.temperature), f?.precipitationProbability)
        }
    }
    val events = agenda?.events.orEmpty().mapNotNull { e ->
        val starts = e.startsAt ?: return@mapNotNull null
        val s = starts.atZone(zone)
        if (s.toLocalDate() != date) return@mapNotNull null
        val start = s.hour * 60 + s.minute
        val end = e.endsAt?.atZone(zone)?.takeIf { it.toLocalDate() == date }?.let { it.hour * 60 + it.minute }?.coerceAtLeast(start) ?: start
        DayEvent(e.summary, e.location?.takeIf { it.isNotBlank() }, start, end, starts.toEpochMilli(), hours.firstOrNull { it.hour == s.hour })
    }.filter { it.start in t0 until t1 }.sortedBy { it.start }
    val rainFrom = w?.let { WeatherPlan.rainFrom(it.hourly, date, zone) }?.takeIf { it.toEpochMilli() >= at - 3_600_000L }
    val dryAt = if (w != null && rainFrom != null) WeatherPlan.dryAgain(w.hourly, rainFrom) else null
    val rain = rainFrom?.let { r -> minuteOf(r).coerceIn(t0, t1) until (dryAt?.takeIf { it.atZone(zone).toLocalDate() == date }?.let { minuteOf(it) } ?: t1).coerceIn(t0, t1) }
    // sun.sun gives the next sunrise (tomorrow's once it is up), so only today's count.
    val rise = w?.sunrise?.takeIf { it.atZone(zone).toLocalDate() == date }?.let { minuteOf(it) }
    val set = w?.sunset?.takeIf { it.atZone(zone).toLocalDate() == date }?.let { minuteOf(it) }
    val dark = listOfNotNull(
        rise?.takeIf { it > t0 }?.let { t0 until it.coerceAtMost(t1) },
        set?.takeIf { it < t1 }?.let { it.coerceAtLeast(t0) until t1 },
    )
    val nowMin = now.hour * 60 + now.minute
    return DayData(t0, t1, hours, events, nowMin, rain, dark, events.indexOfFirst { it.end >= nowMin })
}

private fun clockOf(context: android.content.Context, minutes: Int): String = Format.time(context, LocalTime.of(minutes / 60 % 24, minutes % 60))

/**
 * After Stop: today on one timeline, weather and calendar side by side. The weather and the
 * appointments of today come from Home Assistant (when set up and the phone is unlocked); tap an
 * appointment and its hours light up in the weather. Up to three scripts as buttons, and Done.
 * Light after a sunrise, dark otherwise.
 */
@Composable
private fun Morning(ring: PlannedRing, stoppedAt: Long, onBrightness: (Float) -> Unit, onLightBars: (Boolean) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val t = if (ring.sunriseMinutes > 0) DayTokens else NightTokens
    val zone = ZoneId.systemDefault()
    val stopped = Instant.ofEpochMilli(stoppedAt).atZone(zone)
    // Before the first unlock only the engine works: no settings, Room or Home Assistant.
    val unlocked = remember { app.isUserUnlocked() && app.prefs.account.value.loggedIn }
    var weather by remember { mutableStateOf(if (unlocked) app.night.weather.value else null) }
    var agenda by remember { mutableStateOf<AgendaInfo?>(null) }
    var buttons by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var ran by remember { mutableStateOf(emptySet<String>()) }
    var picked by remember { mutableIntStateOf(-1) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onBrightness(if (t.dark) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 1f)
        onLightBars(!t.dark)
    }
    LaunchedEffect(Unit) {
        if (!unlocked) return@LaunchedEffect
        val s = app.prefs.settings.value
        if (s.weatherEntity != null) {
            app.night.refreshWeather()
            weather = app.night.weather.value
        }
        agenda = app.night.agendaOf(stopped.toLocalDate(), zone)
        val names = app.repository.scripts.first().associate { it.entityId to it.name }
        buttons = s.morningButtons.map { it to (names[it] ?: it) }
    }
    LaunchedEffect(done) {
        // "Have a good day" shows for a moment, then the screen closes.
        if (done) {
            delay(4_000)
            onDone()
        }
    }

    val day = remember(weather, agenda) { buildDay(weather, agenda, stoppedAt, zone) }
    val sel = picked.takeIf { it in day.events.indices } ?: day.next
    val subline = stringResource(R.string.morning_subline, Format.longDate(context, stopped.toLocalDate()), Format.time(context, stopped.toLocalTime()))
    val scheduled by app.engine.scheduled.collectAsState()
    val nextAlarm = scheduled.filter { !it.snooze && it.ringAt > stoppedAt }.minByOrNull { it.ringAt }

    BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()) {
        val wide = maxWidth > maxHeight
        if (wide) {
            Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(subline, style = Tnum.copy(fontSize = 12.sp), color = t.muted)
                        MorningHead(t, weather, stoppedAt, stopped.hour, compact = true)
                    }
                    MorningButtons(t, buttons, ran, wide = true) { id ->
                        ran = ran + id
                        scope.launch { if (app.night.runScript(id) != null) ran = ran - id }
                    }
                    DoneButton(t) { done = true }
                }
                LandscapeTimeline(t, day, sel, { picked = it }, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subline, style = Tnum.copy(fontSize = 14.sp), color = t.muted)
                MorningHead(t, weather, stoppedAt, stopped.hour, compact = false)
                PortraitTimeline(t, day, sel, { picked = it }, Modifier.weight(1f).fillMaxWidth())
                MorningButtons(t, buttons, ran, wide = false) { id ->
                    ran = ran + id
                    scope.launch { if (app.night.runScript(id) != null) ran = ran - id }
                }
                DoneButton(t, Modifier.fillMaxWidth()) { done = true }
            }
        }
        if (done) {
            Column(
                Modifier.fillMaxSize().background(t.bg).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.morning_good_day), style = Tnum.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = t.ink)
                nextAlarm?.let { Text(stringResource(R.string.morning_next_alarm, Format.next(context, it.ringAt)), style = Tnum.copy(fontSize = 14.sp), color = t.muted) }
                Box(
                    Modifier.padding(top = 8.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, t.line, RoundedCornerShape(22.dp)).clickable(role = Role.Button) { done = false }.padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.morning_back), style = Tnum.copy(fontSize = 14.sp), color = t.ink) }
            }
        }
    }
}

@Composable
private fun DoneButton(t: AppColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(1.dp, t.off, RoundedCornerShape(26.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.done), style = Tnum.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = t.ink)
    }
}

/** The weather now and what to expect, or just the greeting when there is no forecast. */
@Composable
private fun MorningHead(t: AppColors, w: WeatherInfo?, at: Long, hour: Int, compact: Boolean) {
    val context = LocalContext.current
    if (w == null) {
        Text(Format.greeting(context, hour), style = Tnum.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = t.ink)
        return
    }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val day = WeatherPlan.day(w.daily, today, zone)
    val rain = WeatherPlan.rainFrom(w.hourly, today, zone)?.takeIf { it.toEpochMilli() >= at - 3_600_000L }
    val dry = rain?.let { WeatherPlan.dryAgain(w.hourly, it) }
    val cold = WeatherPlan.cold(day?.templow ?: w.temperature, w.unit)
    val rainText = rain?.let {
        if (dry != null) stringResource(R.string.morning_rain, Format.time(context, it.toEpochMilli()), Format.time(context, dry.toEpochMilli()))
        else stringResource(R.string.weather_rain_around, Format.time(context, it.toEpochMilli()))
    }
    val advice = when {
        cold && rain != null -> stringResource(R.string.advice_coat_umbrella)
        cold -> stringResource(R.string.advice_coat)
        rain != null -> stringResource(R.string.advice_umbrella)
        else -> ""
    }
    val sunrise = w.sunrise?.takeIf { it.atZone(zone).toLocalDate() == today }?.let { Format.time(context, it.toEpochMilli()) }
    val high = day?.temperature?.let { Format.degrees(it) }
    val facts = when {
        high != null && sunrise != null -> stringResource(R.string.morning_high_sunrise, high, sunrise)
        high != null -> stringResource(R.string.weather_high, high)
        sunrise != null -> stringResource(R.string.sunrise_at, sunrise)
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(AppIcons.weather(w.condition), null, tint = t.ink, modifier = Modifier.size(if (compact) 40.dp else 52.dp))
        Text(
            Format.degrees(w.temperature),
            style = Tnum.copy(fontSize = if (compact) 60.sp else 92.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-0.05).em, lineHeight = if (compact) 56.sp else 84.sp),
            color = t.ink,
        )
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Format.condition(context, w.condition), style = Tnum.copy(fontSize = if (compact) 15.sp else 17.sp, fontWeight = FontWeight.SemiBold), color = t.ink)
            if (rainText != null || advice.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        if (rainText != null) withStyle(SpanStyle(color = rainColor(t), fontWeight = FontWeight.SemiBold)) { append(rainText) }
                        if (rainText != null && advice.isNotEmpty()) append(" ")
                        append(advice)
                    },
                    style = Tnum.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    color = t.ink,
                )
            }
            if (facts != null) Text(facts, style = Tnum.copy(fontSize = 13.sp), color = t.muted)
        }
    }
}

/** The scripts chosen in Settings as buttons: a tap runs one. */
@Composable
private fun MorningButtons(t: AppColors, buttons: List<Pair<String, String>>, ran: Set<String>, wide: Boolean, onRun: (String) -> Unit) {
    if (buttons.isEmpty()) return
    Row(if (wide) Modifier else Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (id, name) ->
            val on = id in ran
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .then(if (wide) Modifier.width(96.dp) else Modifier.weight(1f))
                    .heightIn(min = 52.dp)
                    .clip(shape)
                    .background(if (on) t.soft else Color.Transparent)
                    .border(1.dp, if (on) t.accent else t.line, shape)
                    .clickable(role = Role.Button) { onRun(id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(name, style = Tnum.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (on) R.string.morning_started else R.string.morning_run), style = Tnum.copy(fontSize = 11.sp), color = t.muted, maxLines = 1)
            }
        }
    }
}

/** The weather by the hour (left) beside the appointments (right), down the day. */
@Composable
private fun PortraitTimeline(t: AppColors, day: DayData, sel: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val rain = rainColor(t)
    fun py(m: Int) = (12f + (m - day.t0) / 60f * PORTRAIT_HOUR).dp
    val timelineLabel = stringResource(R.string.morning_timeline)
    val height = py(day.t1) + 64.dp
    val chosen = day.events.getOrNull(sel)
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.morning_weather).uppercase(), style = Tnum.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em), color = t.muted, modifier = Modifier.padding(start = 38.dp).width(76.dp))
            Text(stringResource(R.string.morning_calendar).uppercase(), style = Tnum.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em), color = t.muted, modifier = Modifier.padding(start = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(height).semantics { contentDescription = timelineLabel }) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
                day.dark.forEach { r ->
                    val top = if (r.first == day.t0) 0.dp else py(r.first)
                    val bottom = if (r.last + 1 >= day.t1) height else py(r.last + 1)
                    Box(Modifier.padding(start = 34.dp).offset(y = top).width(80.dp).height(bottom - top).background(t.soft))
                }
                day.rain?.let { r ->
                    Box(Modifier.fillMaxWidth().padding(start = 34.dp).offset(y = py(r.first)).height(py(r.last + 1) - py(r.first)).background(rain.copy(alpha = 0.09f)))
                    Box(Modifier.padding(start = 34.dp).offset(y = py(r.first)).width(3.dp).height(py(r.last + 1) - py(r.first)).background(rain.copy(alpha = 0.6f)))
                }
                chosen?.let { e ->
                    val from = e.start / 60 * 60
                    val to = maxOf((maxOf(e.end, e.start + 1) + 59) / 60 * 60, from + 60)
                    Box(Modifier.padding(start = 38.dp).offset(y = py(from) - 12.dp).width(74.dp).height(py(to) - py(from) + 6.dp).border(1.5.dp, t.accent, RoundedCornerShape(8.dp)))
                }
                day.hours.forEach { h ->
                    Row(Modifier.fillMaxWidth().offset(y = py(h.hour * 60) - 9.dp).height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.clock(context, LocalTime.of(h.hour % 24, 0)), style = Tnum.copy(fontSize = 12.sp), color = t.muted, maxLines = 1, softWrap = false, modifier = Modifier.width(34.dp))
                        Row(Modifier.width(80.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            h.icon?.let { Icon(it, null, tint = t.ink, modifier = Modifier.size(16.dp)) }
                            Text(h.temp, style = Tnum.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = t.ink, maxLines = 1)
                            h.chance?.takeIf { it >= 10 }?.let { Text("${it.roundToInt()}%", style = Tnum.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = rain, maxLines = 1) }
                        }
                        Box(Modifier.weight(1f).height(1.dp).background(t.line.copy(alpha = 0.7f)))
                    }
                }
                // Now: the time, a line and a dot (each placed on its own: a zero-height wrapper would squash them).
                Text(clockOf(context, day.nowMin), style = Tnum.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = t.accent, maxLines = 1, softWrap = false, modifier = Modifier.offset(y = py(day.nowMin) - 8.dp))
                Box(Modifier.padding(start = 34.dp).fillMaxWidth().offset(y = py(day.nowMin) - 1.dp).height(2.dp).background(t.accent))
                Box(Modifier.offset(x = 30.dp, y = py(day.nowMin) - 5.dp).size(10.dp).clip(CircleShape).background(t.accent))
                day.events.forEachIndexed { i, e ->
                    val on = i == sel
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 122.dp)
                            .offset(y = py(e.start))
                            .heightIn(min = maxOf(50f, (e.end - e.start) / 60f * PORTRAIT_HOUR).dp)
                            .clip(shape)
                            .background(if (on) t.surface else t.soft)
                            .border(1.dp, if (on) t.accent else Color.Transparent, shape)
                            .selectable(selected = on, role = Role.Button) { onSelect(i) }
                            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                            Text(clockOf(context, e.start), style = Tnum.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = if (i == day.next) t.accent else t.muted)
                            Text(e.name, style = Tnum.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (i == day.next && e.start > day.nowMin) Text(stringResource(R.string.morning_in, Format.until(context, (e.start - day.nowMin) * 60_000L, 0L)), style = Tnum.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = t.accent, maxLines = 1)
                        }
                        EventFacts(t, e)
                    }
                }
            }
        }
    }
}

/** The place and the weather around an appointment. */
@Composable
private fun EventFacts(t: AppColors, e: DayEvent) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(e.place.orEmpty(), style = Tnum.copy(fontSize = 12.sp), color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        e.weather?.let { w ->
            w.icon?.let { Icon(it, null, tint = t.ink, modifier = Modifier.size(13.dp)) }
            Text(w.temp, style = Tnum.copy(fontSize = 12.sp), color = t.ink, maxLines = 1)
        }
    }
}

/** The weather by the hour (top) above the appointments, along the day; scrolls sideways. */
@Composable
private fun LandscapeTimeline(t: AppColors, day: DayData, sel: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val rain = rainColor(t)
    fun lx(m: Int) = (30f + (m - day.t0) / 60f * LANDSCAPE_HOUR).dp
    val timelineLabel = stringResource(R.string.morning_timeline)
    val width = lx(day.t1) + 170.dp
    val chosen = day.events.getOrNull(sel)
    Row(modifier) {
        Box(Modifier.width(68.dp).fillMaxHeight()) {
            Text(stringResource(R.string.morning_weather).uppercase(), style = Tnum.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em), color = t.muted, modifier = Modifier.offset(y = 30.dp))
            Text(stringResource(R.string.morning_calendar).uppercase(), style = Tnum.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em), color = t.muted, modifier = Modifier.offset(y = 120.dp))
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(width).fillMaxHeight().semantics { contentDescription = timelineLabel }) {
                day.dark.forEach { r ->
                    val left = if (r.first == day.t0) 0.dp else lx(r.first)
                    val right = if (r.last + 1 >= day.t1) width else lx(r.last + 1)
                    Box(Modifier.offset(x = left).width(right - left).height(74.dp).background(t.soft))
                }
                day.rain?.let { r ->
                    Box(Modifier.offset(x = lx(r.first)).width(lx(r.last + 1) - lx(r.first)).fillMaxHeight().background(rain.copy(alpha = 0.09f)))
                    Text(stringResource(R.string.weather_rainy), style = Tnum.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), color = rain, modifier = Modifier.align(Alignment.BottomStart).offset(x = lx(r.first)).width(lx(r.last + 1) - lx(r.first)).padding(bottom = 6.dp))
                }
                chosen?.let { e ->
                    val from = e.start / 60 * 60
                    val to = maxOf((maxOf(e.end, e.start + 1) + 59) / 60 * 60, from + 60)
                    Box(Modifier.offset(x = lx(from) - 30.dp, y = 4.dp).width(lx(to) - lx(from)).height(66.dp).border(1.5.dp, t.accent, RoundedCornerShape(10.dp)))
                }
                Box(Modifier.fillMaxWidth().offset(y = 78.dp).height(1.dp).background(t.line))
                day.hours.forEach { h ->
                    Column(Modifier.offset(x = lx(h.hour * 60) - 28.dp, y = 8.dp).width(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(Format.clock(context, LocalTime.of(h.hour % 24, 0)), style = Tnum.copy(fontSize = 11.sp), color = t.muted, maxLines = 1, softWrap = false)
                        if (h.icon != null) Icon(h.icon, null, tint = t.ink, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp))
                        Text(h.temp, style = Tnum.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = t.ink, maxLines = 1)
                        Text(h.chance?.takeIf { it >= 10 }?.let { "${it.roundToInt()}%" }.orEmpty(), style = Tnum.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = rain, maxLines = 1, modifier = Modifier.height(12.dp))
                    }
                }
                day.events.forEachIndexed { i, e ->
                    val on = i == sel
                    val shape = RoundedCornerShape(14.dp)
                    Box(Modifier.offset(x = lx(e.start), y = 76.dp).width(maxOf(5f, (e.end - e.start) / 60f * LANDSCAPE_HOUR).dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (on || i == day.next) t.accent else t.muted))
                    Column(
                        Modifier
                            .offset(x = lx(e.start), y = 92.dp)
                            .width(150.dp)
                            .clip(shape)
                            .background(if (on) t.surface else t.soft)
                            .border(1.dp, if (on) t.accent else Color.Transparent, shape)
                            .selectable(selected = on, role = Role.Button) { onSelect(i) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(clockOf(context, e.start), style = Tnum.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = if (i == day.next) t.accent else t.muted)
                            if (i == day.next && e.start > day.nowMin) Text(stringResource(R.string.morning_in, Format.until(context, (e.start - day.nowMin) * 60_000L, 0L)), style = Tnum.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = t.accent, maxLines = 1)
                        }
                        Text(e.name, style = Tnum.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        EventFacts(t, e)
                    }
                }
                Box(Modifier.offset(x = lx(day.nowMin) - 1.dp).fillMaxHeight().width(2.dp).background(t.accent))
                Box(Modifier.offset(x = lx(day.nowMin) - 5.dp, y = 73.dp).size(10.dp).clip(CircleShape).background(t.accent))
            }
        }
    }
}
