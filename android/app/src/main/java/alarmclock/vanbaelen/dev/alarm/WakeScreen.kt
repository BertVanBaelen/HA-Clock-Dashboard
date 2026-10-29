package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.PlannedRing
import alarmclock.vanbaelen.dev.core.Sunrise
import alarmclock.vanbaelen.dev.core.Timing
import alarmclock.vanbaelen.dev.core.WeatherPlan
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    val p = remember(ring) { Animatable(if (sunrise && !shown) 0f else 1f) }
    LaunchedEffect(ring) {
        if (p.value < 1f) p.animateTo(1f, tween(Sunrise.SHORT_MS.toInt(), easing = LinearEasing))
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
        status = if (sunrise && p.value < 1f) stringResource(R.string.wake_status_sunrise) else null,
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

/**
 * After Stop: "Good morning", the date and when the alarm was stopped, today's weather by the
 * hour (from Home Assistant, when set up and the phone is unlocked) and the after-dismiss actions.
 * Light after a sunrise, dark otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Morning(ring: PlannedRing, stoppedAt: Long, onBrightness: (Float) -> Unit, onLightBars: (Boolean) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val t = if (ring.sunriseMinutes > 0) DayTokens else NightTokens
    val zone = ZoneId.systemDefault()
    val stopped = Instant.ofEpochMilli(stoppedAt).atZone(zone)
    // Before the first unlock only the engine works: no settings, Room or Home Assistant.
    val unlocked = remember { app.isUserUnlocked() && app.prefs.account.value.loggedIn }
    var weather by remember { mutableStateOf(if (unlocked) app.night.weather.value else null) }
    var actions by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        onBrightness(if (t.dark) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 1f)
        onLightBars(!t.dark)
    }
    LaunchedEffect(Unit) {
        if (!unlocked || app.prefs.settings.value.weatherEntity == null) return@LaunchedEffect
        app.night.refreshWeather()
        weather = app.night.weather.value
    }
    LaunchedEffect(Unit) {
        // Away, Home Assistant runs no actions for this phone's alarms.
        if (!unlocked || ring.occurrenceId == null) return@LaunchedEffect
        val alarm = app.repository.effectiveAlarms().firstOrNull { it.alarm.id == ring.alarmId }?.alarm ?: return@LaunchedEffect
        val names = app.repository.scripts.first().associate { it.entityId to it.name }
        actions = alarm.actions
            .filter { it.enabled && it.timing == Timing.AFTER_DISMISS.api }
            .sortedBy { it.delayMinutes ?: 0 }
            .map { (names[it.scriptEntityId] ?: it.scriptEntityId) to (it.delayMinutes ?: 0) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()) {
        val wide = maxWidth > maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(Format.greeting(context, stopped.hour), style = Tnum.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em), color = t.ink)
                    Text(
                        stringResource(R.string.morning_subline, Format.longDate(context, stopped.toLocalDate()), Format.time(context, stopped.toLocalTime())),
                        style = Tnum.copy(fontSize = 14.sp),
                        color = t.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Box(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.5.dp, t.off, RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button, onClick = onDone)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.done), style = Tnum.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = t.ink)
                }
            }
            weather?.let { w ->
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        WeatherNow(t, w, stoppedAt, Modifier.width(230.dp))
                        HourGrid(t, w, stoppedAt, Modifier.weight(1f))
                    }
                } else {
                    WeatherNow(t, w, stoppedAt, Modifier.fillMaxWidth())
                    HourGrid(t, w, stoppedAt, Modifier.fillMaxWidth())
                }
            }
            val chips = listOfNotNull(
                weather?.sunrise?.takeIf { it.atZone(zone).toLocalDate() == stopped.toLocalDate() }
                    ?.let { stringResource(R.string.chip_sunrise, Format.time(context, it.toEpochMilli())) },
                weather?.sunset?.takeIf { it.atZone(zone).toLocalDate() == stopped.toLocalDate() }
                    ?.let { stringResource(R.string.chip_sunset, Format.time(context, it.toEpochMilli())) },
                weather?.windSpeed?.let { stringResource(R.string.chip_wind, "${it.roundToInt()} ${weather?.windUnit.orEmpty()}".trim()) },
            ) + actions.map { (name, delay) ->
                if (delay == 0) stringResource(R.string.chip_action_now, name) else stringResource(R.string.chip_action_later, name, delay)
            }
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { text ->
                        Text(
                            text,
                            style = Tnum.copy(fontSize = 13.sp),
                            color = t.ink,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(t.soft).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Now: big temperature, the day in words, high / low / feels like and advice. */
@Composable
private fun WeatherNow(t: AppColors, w: WeatherInfo, at: Long, modifier: Modifier) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val day = WeatherPlan.day(w.daily, today, zone)
    val rain = WeatherPlan.rainFrom(w.hourly, today, zone)?.takeIf { it.toEpochMilli() >= at - 3_600_000L }
    val dry = rain?.let { WeatherPlan.dryAgain(w.hourly, it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AppIcons.weather(w.condition), null, tint = t.ink, modifier = Modifier.size(44.dp))
            Text(Format.degrees(w.temperature), style = Tnum.copy(fontSize = 76.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = (-0.04).em, lineHeight = 76.sp), color = t.ink)
        }
        Text(
            if (rain != null) stringResource(R.string.weather_rain_from, Format.condition(context, w.condition), Format.time(context, rain.toEpochMilli()))
            else Format.condition(context, w.condition),
            style = Tnum.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            color = t.ink,
        )
        val facts = listOfNotNull(
            day?.temperature?.let { stringResource(R.string.weather_high, Format.degrees(it)) },
            day?.templow?.let { stringResource(R.string.weather_low, Format.degrees(it)) },
            w.apparent?.let { stringResource(R.string.weather_feels, Format.degrees(it)) },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = Tnum.copy(fontSize = 14.sp), color = t.muted)
        val cold = WeatherPlan.cold(day?.templow ?: w.temperature, w.unit)
        val advice = listOfNotNull(
            when {
                cold && rain != null -> stringResource(R.string.advice_coat_umbrella)
                cold -> stringResource(R.string.advice_coat)
                rain != null -> stringResource(R.string.advice_umbrella)
                else -> null
            },
            dry?.let { stringResource(R.string.advice_dry_after, Format.time(context, it.toEpochMilli())) },
        )
        if (advice.isNotEmpty()) {
            Text(
                advice.joinToString(" "),
                style = Tnum.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = t.ink,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.soft).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Now and the next six hours: time, icon, temperature and a rain bar with its chance. */
@Composable
private fun HourGrid(t: AppColors, w: WeatherInfo, at: Long, modifier: Modifier) {
    val context = LocalContext.current
    val rain = rainColor(t)
    val from = Instant.ofEpochMilli(at)
    val first = w.hourly.firstOrNull { f -> f.at?.let { !it.isBefore(from.minusSeconds(3_600)) } == true }
    val columns = listOf(Triple(stringResource(R.string.now), w.condition to w.temperature, first?.precipitationProbability)) +
        WeatherPlan.hoursAfter(w.hourly, from.plusSeconds(1_800), 6).map { f ->
            Triple(Format.time(context, (f.at ?: from).toEpochMilli()), (f.condition ?: w.condition) to f.temperature, f.precipitationProbability)
        }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        columns.forEachIndexed { i, (label, now, chance) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (i == 0) t.soft else Color.Transparent)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, style = Tnum.copy(fontSize = 13.sp, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal), color = t.muted, maxLines = 1)
                Icon(AppIcons.weather(now.first), null, tint = t.ink, modifier = Modifier.size(26.dp))
                Text(Format.degrees(now.second), style = Tnum.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = t.ink)
                Box(Modifier.height(40.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(10.dp).height(max(3f, ((chance ?: 0.0) * 0.4).toFloat()).dp).clip(RoundedCornerShape(3.dp)).background(rain))
                }
                Text(chance?.let { "${it.roundToInt()}%" } ?: "–", style = Tnum.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = rain)
            }
        }
    }
}
