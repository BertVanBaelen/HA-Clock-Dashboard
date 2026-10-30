package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.Bedroom
import alarmclock.vanbaelen.dev.core.HaState
import alarmclock.vanbaelen.dev.core.NightAlarm
import alarmclock.vanbaelen.dev.core.NightWidget
import alarmclock.vanbaelen.dev.core.Timing
import alarmclock.vanbaelen.dev.core.WeatherPlan
import alarmclock.vanbaelen.dev.core.signedOffset
import alarmclock.vanbaelen.dev.data.HomeInfo
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.data.WeatherInfo
import alarmclock.vanbaelen.dev.ui.AlarmItem
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.tokens
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** The row of widget cards, scrolling sideways, right of the small clock. */
@Composable
internal fun NightWidgets(
    f: NightFrame,
    night: NightViewModel,
    settings: LocalSettings,
    battery: BatteryState,
    upcoming: NightAlarm?,
    planItem: AlarmItem?,
    target: LocalDate,
    zone: ZoneId,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = tokens
    val weather by night.weather.collectAsState()
    val home by night.home.collectAsState()
    val agenda by night.agenda.collectAsState()
    val problem by night.problem.collectAsState()
    val scripts by night.scripts.collectAsState()
    val context = LocalContext.current
    val waiting = problem?.let { Format.problem(context, it) } ?: stringResource(R.string.loading)
    val widgets = enabledWidgets(settings)

    Row(
        modifier
            .width(f.dp(844 - 280))
            .height(f.dp(300))
            .horizontalScroll(rememberScrollState())
            .padding(end = f.dp(24)),
        horizontalArrangement = Arrangement.spacedBy(f.dp(12)),
    ) {
        if (widgets.isEmpty()) {
            Text(stringResource(R.string.widgets_none), style = nightText(f, 14), color = t.muted, modifier = Modifier.width(f.dp(300)))
        }
        widgets.forEach { widget ->
            when (widget) {
                NightWidget.WEATHER -> WidgetCard(f, Format.dayTitle(context, target)) {
                    when {
                        settings.weatherEntity == null -> SetupHint(f, stringResource(R.string.widget_weather_setup), onSettings)
                        weather == null -> Muted(f, waiting)
                        else -> WeatherBody(f, weather!!, target, zone)
                    }
                }
                NightWidget.PLAN -> WidgetCard(f, stringResource(R.string.widget_plan_title)) {
                    PlanBody(f, night, upcoming, planItem, scripts.associate { it.entityId to it.name }, zone)
                }
                NightWidget.HOME -> WidgetCard(
                    f,
                    stringResource(R.string.widget_home_title),
                    width = 420,
                    status = home?.rows?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.name} ${Format.entityState(context, it)}" },
                ) {
                    val info = home
                    when {
                        !settings.hasBedroom -> SetupHint(f, stringResource(R.string.widget_home_setup), onSettings)
                        info == null -> Muted(f, waiting)
                        else -> BedroomBody(f, night, info)
                    }
                }
                NightWidget.CALENDAR -> WidgetCard(f, stringResource(R.string.widget_calendar_title)) {
                    val info = agenda
                    when {
                        settings.calendarEntities.isEmpty() -> SetupHint(f, stringResource(R.string.widget_calendar_setup), onSettings)
                        info == null -> Muted(f, waiting)
                        info.events.isEmpty() -> Muted(f, stringResource(R.string.widget_calendar_empty, Format.dayTitle(context, info.date)))
                        else -> {
                            val first = info.events.first()
                            first.startsAt?.let { Text(Format.time(context, it.toEpochMilli()), style = nightText(f, 30, FontWeight.Light), color = t.ink) }
                            Text(first.summary, style = nightText(f, 15, FontWeight.Medium), color = t.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val more = info.events.size - 1
                            val details = listOfNotNull(
                                first.location?.takeIf { it.isNotBlank() },
                                if (more > 0) pluralStringResource(R.plurals.more_events, more, more) else null,
                            )
                            if (details.isNotEmpty()) Muted(f, details.joinToString(". ") + ".")
                        }
                    }
                }
                NightWidget.BATTERY -> WidgetCard(f, stringResource(R.string.widget_battery_title)) {
                    Text("${battery.percent}%", style = nightText(f, 30, FontWeight.Light), color = t.ink)
                    Box(Modifier.fillMaxWidth().height(f.dp(8)).clip(RoundedCornerShape(f.dp(4))).background(t.soft)) {
                        Box(Modifier.fillMaxWidth(battery.percent / 100f).height(f.dp(8)).clip(RoundedCornerShape(f.dp(4))).background(t.accent))
                    }
                    Muted(
                        f,
                        when {
                            battery.full -> stringResource(R.string.battery_full)
                            battery.plugged && battery.fullAt != null -> stringResource(R.string.battery_full_at, Format.time(context, battery.fullAt))
                            battery.plugged -> stringResource(R.string.battery_charging)
                            else -> stringResource(R.string.battery_not_charging)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherBody(f: NightFrame, info: WeatherInfo, date: LocalDate, zone: ZoneId) {
    val context = LocalContext.current
    val t = tokens
    val day = WeatherPlan.day(info.daily, date, zone)
    val rain = WeatherPlan.rainFrom(info.hourly, date, zone)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(f.dp(10))) {
        Icon(AppIcons.weather(day?.condition ?: info.condition), null, tint = t.ink, modifier = Modifier.size(f.dp(30)))
        val low = day?.templow
        val high = day?.temperature ?: info.temperature
        Text(
            if (low != null) "${Format.degrees(low)} → ${Format.degrees(high)}" else Format.degrees(high),
            style = nightText(f, 30, FontWeight.Light),
            color = t.ink,
            maxLines = 1,
        )
    }
    Text(
        if (rain != null) stringResource(R.string.weather_rain_around, Format.time(context, rain.toEpochMilli()))
        else Format.condition(context, day?.condition ?: info.condition),
        style = nightText(f, 14),
        color = t.ink,
    )
    val advice = listOfNotNull(
        when {
            WeatherPlan.cold(day?.templow, info.unit) && rain != null -> stringResource(R.string.advice_coat_umbrella)
            WeatherPlan.cold(day?.templow, info.unit) -> stringResource(R.string.advice_coat)
            rain != null -> stringResource(R.string.advice_umbrella)
            else -> null
        },
        sunTimes(context, info, date, zone),
    )
    if (advice.isNotEmpty()) Muted(f, advice.joinToString(" "))
}

/** "Sunrise at 08:02, sunset at 17:31." for [date], from sun.sun. */
internal fun sunTimes(context: android.content.Context, info: WeatherInfo, date: LocalDate, zone: ZoneId): String? {
    val rise = info.sunrise?.takeIf { it.atZone(zone).toLocalDate() == date }
    val set = info.sunset?.takeIf { it.atZone(zone).toLocalDate() == date }
    return when {
        rise != null && set != null -> context.getString(R.string.sun_times, Format.time(context, rise.toEpochMilli()), Format.time(context, set.toEpochMilli()))
        rise != null -> context.getString(R.string.sunrise_at, Format.time(context, rise.toEpochMilli()))
        else -> null
    }
}

// The Bedroom widget's own colours (mockup "Bedroom widget — states and rules").
private val Bright = Color(0xFFFFE9D2)
private val LampFill = Color(0xFF6E3F17)
private val HeatFill = Color(0xFF6E2A12)
private val AllOffLine = Color(0xFF6A4A2C)
private val AllOffDone = Color(0xFF1A1209)

/** Night lamp and heating as rows to slide, then "All lights off" (with undo) at the bottom. */
@Composable
private fun ColumnScope.BedroomBody(f: NightFrame, night: NightViewModel, info: HomeInfo) {
    val context = LocalContext.current
    val lightsOff by night.lightsOff.collectAsState()
    info.lamp?.let { LampRow(f, night, it) }
    val heating = info.heating
    val room = info.temperature
    if (heating != null) {
        // The room now: the chosen temperature sensor, else the thermostat's own reading.
        val now = room?.let { roomTemperature(context, it) } ?: heating.number("current_temperature")?.let { Format.degreesFine(context, it) }
        HeatingRow(f, night, heating, now)
    } else if (room != null) {
        SlideRow(
            f,
            name = stringResource(R.string.entity_room_temperature),
            hint = room.name,
            value = roomTemperature(context, room),
            icon = AppIcons.thermometer,
            fraction = 0f,
            fill = HeatFill,
            fillAlpha = 1f,
            steps = 0,
            enabled = room.available,
            onSlide = null,
        )
    }
    Spacer(Modifier.weight(1f))
    AllOffButton(f, undo = lightsOff != null) { night.toggleAllOff(info.lamp?.entityId) }
}

@Composable
private fun LampRow(f: NightFrame, night: NightViewModel, lamp: HaState) {
    val dimmable = Bedroom.dimmable(lamp)
    val actual = Bedroom.lampPercent(lamp)
    var shown by remember(lamp) { mutableIntStateOf(actual) }
    var sliding by remember { mutableStateOf(false) }
    SlideRow(
        f,
        name = stringResource(R.string.bedroom_lamp),
        hint = when {
            !lamp.available -> stringResource(R.string.state_unavailable)
            sliding && shown > actual -> stringResource(R.string.bedroom_brighter)
            sliding && shown < actual -> stringResource(R.string.bedroom_softer)
            else -> stringResource(if (dimmable) R.string.bedroom_lamp_hint else R.string.bedroom_switch_hint)
        },
        value = when {
            shown == 0 -> stringResource(R.string.state_off)
            dimmable -> "$shown%"
            else -> stringResource(R.string.state_on)
        },
        icon = AppIcons.lamp,
        fraction = shown / 100f,
        fill = LampFill,
        fillAlpha = 0.45f + 0.55f * shown / 100f,
        steps = if (dimmable) 100 / Bedroom.LAMP_STEP - 1 else 0,
        enabled = lamp.available,
        onSlide = { fraction ->
            sliding = true
            val v = Bedroom.lampAt(fraction, dimmable)
            (v != shown).also { shown = v }
        },
        onDone = {
            sliding = false
            if (shown != actual) night.setLamp(lamp.entityId, shown)
        },
    )
}

/** The thermostat's target (the big number) and the room now (the small line). */
@Composable
private fun HeatingRow(f: NightFrame, night: NightViewModel, heating: HaState, now: String?) {
    val context = LocalContext.current
    val target = Bedroom.target(heating)
    val range = Bedroom.heatRange(heating, target)
    val step = Bedroom.step(heating)
    var shown by remember(heating) { mutableStateOf(target) }
    SlideRow(
        f,
        name = stringResource(R.string.bedroom_heating),
        hint = if (now != null) stringResource(R.string.bedroom_heating_now, now) else stringResource(R.string.bedroom_heating_hint),
        value = shown?.let { Format.degreesFine(context, it) } ?: Format.entityState(context, heating),
        icon = AppIcons.thermometer,
        fraction = shown?.let { Bedroom.heatFraction(it, range) } ?: 0f,
        fill = HeatFill,
        fillAlpha = 1f,
        steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
        enabled = heating.available && target != null,
        onSlide = { fraction ->
            val v = Bedroom.heatAt(fraction, range, step)
            (v != shown).also { shown = v }
        },
        onDone = { shown?.let { if (it != target) night.setHeating(heating.entityId, it) } },
    )
}

/**
 * A 76 high row to slide anywhere on: the fill and the number follow the finger, letting go sets
 * it (no Save). A tap sets it where it was touched; a vertical swipe is left to the screen.
 * [onSlide] gets the position 0..1 and returns whether the value moved a step (a soft tick).
 */
@Composable
private fun SlideRow(
    f: NightFrame,
    name: String,
    hint: String,
    value: String,
    icon: ImageVector,
    fraction: Float,
    fill: Color,
    fillAlpha: Float,
    steps: Int,
    enabled: Boolean,
    onSlide: ((Float) -> Boolean)?,
    onDone: () -> Unit = {},
) {
    val t = tokens
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(f.dp(22))
    val slide by rememberUpdatedState(onSlide)
    val done by rememberUpdatedState(onDone)
    val interactive = enabled && onSlide != null
    fun move(position: Float) {
        if (slide?.invoke(position) == true) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(f.dp(76))
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(shape)
            .border(1.dp, t.line, shape)
            .drawBehind {
                val w = size.width * fraction
                drawRect(fill, size = Size(w, size.height), alpha = fillAlpha)
                val edge = 2.dp.toPx()
                drawRect(t.accent, topLeft = Offset((w - edge).coerceAtLeast(0f), 0f), size = Size(edge, size.height), alpha = fillAlpha)
            }
            .then(
                if (!interactive) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                if (drag != null) {
                                    move(drag.position.x / size.width)
                                    horizontalDrag(drag.id) { change ->
                                        change.consume()
                                        move(change.position.x / size.width)
                                    }
                                    done()
                                } else {
                                    val up = currentEvent.changes.firstOrNull { it.id == down.id && it.changedToUp() }
                                    if (up != null) {
                                        up.consume()
                                        move(down.position.x / size.width)
                                        done()
                                    }
                                }
                            }
                        }
                        .semantics(mergeDescendants = true) {
                            stateDescription = value
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps.coerceAtLeast(0))
                            setProgress { target ->
                                slide?.invoke(target)
                                done()
                                true
                            }
                        }
                },
            ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = f.dp(18)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(f.dp(10)),
        ) {
            Icon(icon, null, tint = Bright, modifier = Modifier.size(f.dp(22)))
            Column(Modifier.weight(1f)) {
                Text(name, style = nightText(f, 15, FontWeight.Medium), color = Bright, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint, style = nightText(f, 12), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = nightText(f, 32, FontWeight.ExtraLight).copy(lineHeight = f.sp(32)), color = Bright, maxLines = 1)
        }
    }
}

/** Turns off every light; for a few seconds after, it reads "tap to undo". */
@Composable
private fun AllOffButton(f: NightFrame, undo: Boolean, onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(25))
    val bg by animateColorAsState(if (undo) AllOffDone else Color.Transparent, tween(250), label = "allOffBg")
    val fg by animateColorAsState(if (undo) t.muted else Bright, tween(250), label = "allOffFg")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(48.dp, f.dp(50)))
            .clip(shape)
            .background(bg)
            .border(1.dp, if (undo) t.line else AllOffLine, shape)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(f.dp(8), Alignment.CenterHorizontally),
    ) {
        Icon(AppIcons.lightsOff, null, tint = fg, modifier = Modifier.size(f.dp(18)))
        Text(stringResource(if (undo) R.string.bedroom_all_off_undo else R.string.bedroom_all_off), style = nightText(f, 15, FontWeight.Medium), color = fg)
    }
}

/** A row of the plan: an action, or the alarm itself at offset 0. */
private data class PlanRow(val offset: Int, val name: String, val isAlarm: Boolean)

/** The next alarm with its actions around it, and a switch to skip it. */
@Composable
private fun ColumnScope.PlanBody(f: NightFrame, night: NightViewModel, upcoming: NightAlarm?, item: AlarmItem?, names: Map<String, String>, zone: ZoneId) {
    val context = LocalContext.current
    val t = tokens
    if (upcoming == null || item == null) {
        Muted(f, stringResource(R.string.no_next_alarm))
        return
    }
    val alarm = item.alarm
    val alarmName = alarm.label.ifBlank { stringResource(R.string.alarm) }
    val rows = (
        alarm.actions.filter { it.enabled && it.signedOffset != null }.map { PlanRow(it.signedOffset!!, names[it.scriptEntityId] ?: it.scriptEntityId, false) } +
            PlanRow(0, alarmName, true)
        ).sortedWith(compareBy<PlanRow>({ it.offset }, { if (it.isAlarm) 0 else 1 }))
    val shown = if (rows.size > 7) rows.take(6) else rows
    Column(Modifier.graphicsLayer { alpha = if (upcoming.skipped) 0.35f else 1f }, verticalArrangement = Arrangement.spacedBy(f.dp(6))) {
        shown.forEach { r ->
            val color = if (r.isAlarm) t.accent else t.ink
            val weight = if (r.isAlarm) FontWeight.SemiBold else FontWeight.Normal
            Row(horizontalArrangement = Arrangement.spacedBy(f.dp(10))) {
                Text(
                    Format.time(context, upcoming.ringAt + r.offset * 60_000L),
                    style = nightText(f, 13, weight),
                    color = if (r.isAlarm) t.accent else t.muted,
                    modifier = Modifier.width(f.dp(44)),
                )
                Text(r.name, style = nightText(f, 13, weight), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (rows.size > shown.size) Muted(f, pluralStringResource(R.plurals.more_actions, rows.size - shown.size, rows.size - shown.size))
        val afterDismiss = alarm.actions.count { it.enabled && it.timing == Timing.AFTER_DISMISS.api }
        if (afterDismiss > 0) Muted(f, pluralStringResource(R.plurals.after_dismiss_count, afterDismiss, afterDismiss))
    }
    Spacer(Modifier.weight(1f))
    if (alarm.repeating && alarm.enabled) {
        val day = Instant.ofEpochMilli(upcoming.ringAt).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val label = when (day) {
            today -> stringResource(R.string.skip_today)
            today.plusDays(1) -> stringResource(R.string.skip_tomorrow)
            else -> stringResource(R.string.skip_next)
        }
        SwitchRow(f, label, checked = upcoming.skipped, border = true) { skip -> night.setSkip(alarm, skip) }
    }
}

/** Turning widgets on and off, and starting by itself (the night display's "Edit widgets"). */
@Composable
internal fun WidgetChoice(f: NightFrame, night: NightViewModel, settings: LocalSettings, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val t = tokens
    Column(
        modifier
            .width(f.dp(844 - 280 - 24))
            .height(f.dp(310))
            .verticalScroll(rememberScrollState())
            .padding(bottom = f.dp(16)),
        verticalArrangement = Arrangement.spacedBy(f.dp(10)),
    ) {
        ChoiceTile(
            f,
            title = stringResource(R.string.night_auto),
            text = stringResource(R.string.night_auto_desc, Format.time(context, settings.nightStart), Format.time(context, settings.nightEnd)),
            checked = settings.nightAutoStart,
            icon = null,
            modifier = Modifier.fillMaxWidth(),
        ) { night.updateSettings { it.copy(nightAutoStart = !it.nightAutoStart) } }
        Text(stringResource(R.string.widgets_intro), style = nightText(f, 13), color = t.muted)
        NightWidget.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(f.dp(8))) {
                pair.forEach { w ->
                    val on = w in settings.nightWidgets
                    ChoiceTile(f, widgetName(w), widgetText(w), on, widgetIcon(w), Modifier.weight(1f)) {
                        night.updateSettings { s -> s.copy(nightWidgets = if (on) s.nightWidgets - w else s.nightWidgets + w) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(f.dp(8))) {
            Text(stringResource(R.string.widgets_entities_hint), style = nightText(f, 13), color = t.muted, modifier = Modifier.weight(1f))
            NightPill(f, stringResource(R.string.settings), outlined = true, onClick = onSettings)
        }
    }
}

@Composable
private fun widgetName(w: NightWidget): String = stringResource(
    when (w) {
        NightWidget.WEATHER -> R.string.widget_weather
        NightWidget.PLAN -> R.string.widget_plan
        NightWidget.HOME -> R.string.widget_home
        NightWidget.CALENDAR -> R.string.widget_calendar
        NightWidget.BATTERY -> R.string.widget_battery
    },
)

@Composable
private fun widgetText(w: NightWidget): String = stringResource(
    when (w) {
        NightWidget.WEATHER -> R.string.widget_weather_text
        NightWidget.PLAN -> R.string.widget_plan_text
        NightWidget.HOME -> R.string.widget_home_text
        NightWidget.CALENDAR -> R.string.widget_calendar_text
        NightWidget.BATTERY -> R.string.widget_battery_text
    },
)

private fun widgetIcon(w: NightWidget): ImageVector = when (w) {
    NightWidget.WEATHER -> AppIcons.cloud
    NightWidget.PLAN -> AppIcons.bell
    NightWidget.HOME -> AppIcons.home
    NightWidget.CALENDAR -> AppIcons.calendar
    NightWidget.BATTERY -> AppIcons.battery
}

/**
 * A 204 × 288 card (420 wide for Bedroom) with an uppercase title and an optional [status] line
 * beside it; taps on it do not reach the screen behind.
 */
@Composable
private fun WidgetCard(f: NightFrame, title: String, width: Int = 204, status: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(20))
    Column(
        Modifier
            .width(f.dp(width))
            .height(f.dp(288))
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(f.dp(16)),
        verticalArrangement = Arrangement.spacedBy(f.dp(10)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(f.dp(12))) {
            Text(
                title.uppercase(),
                style = nightText(f, 11, FontWeight.SemiBold).copy(letterSpacing = 0.08.em),
                color = t.muted,
                maxLines = 1,
            )
            if (status != null) {
                Text(
                    status,
                    style = nightText(f, 11),
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        content()
    }
}

@Composable
private fun Muted(f: NightFrame, text: String) {
    Text(text, style = nightText(f, 13), color = tokens.muted)
}

@Composable
private fun SetupHint(f: NightFrame, text: String, onSettings: () -> Unit) {
    Muted(f, text)
    NightPill(f, stringResource(R.string.settings), outlined = true, onClick = onSettings)
}

/** A label and a switch, the whole row toggles; [border] draws a line above it. */
@Composable
private fun SwitchRow(f: NightFrame, label: String, checked: Boolean, enabled: Boolean = true, border: Boolean = false, onChange: (Boolean) -> Unit) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(48.dp, f.dp(48)))
            .then(if (border) Modifier.drawBehind { drawLine(t.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = nightText(f, 14), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        SwitchMark(f, checked)
    }
}

/** A widget (or "Start by itself") with its switch; the whole tile toggles. */
@Composable
private fun ChoiceTile(
    f: NightFrame,
    title: String,
    text: String,
    checked: Boolean,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(14))
    val border by animateColorAsState(if (checked || icon == null) t.line else Color.Transparent, tween(250), label = "tileBorder")
    val content = if (checked || icon == null) 1f else 0.55f
    Row(
        modifier
            .heightIn(min = maxOf(56.dp, f.dp(56)))
            .clip(shape)
            .background(t.surface)
            .border(1.dp, border, shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = f.dp(12), vertical = f.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(f.dp(10)),
    ) {
        if (icon != null) Icon(icon, null, tint = t.ink, modifier = Modifier.size(f.dp(20)).graphicsLayer { alpha = content })
        Column(Modifier.weight(1f).graphicsLayer { alpha = content }) {
            Text(title, style = nightText(f, 14, FontWeight.Medium), color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = nightText(f, 12), color = t.muted, maxLines = if (icon == null) 3 else 1, overflow = TextOverflow.Ellipsis)
        }
        SwitchMark(f, checked)
    }
}

/** The switch drawing only (its row is the control): a 38 × 24 track and an 18 knob. */
@Composable
private fun SwitchMark(f: NightFrame, checked: Boolean) {
    val t = tokens
    val track by animateColorAsState(if (checked) t.accent else t.off, tween(250), label = "track")
    val knob by animateColorAsState(if (checked) t.onAccent else t.knob, tween(250), label = "knob")
    val x by animateDpAsState(if (checked) f.dp(14) else 0.dp, tween(300, easing = EaseSoft), label = "knobX")
    Box(Modifier.size(f.dp(38), f.dp(24)).clip(RoundedCornerShape(f.dp(12))).background(track).padding(f.dp(3))) {
        Box(Modifier.offset(x = x).size(f.dp(18)).clip(CircleShape).background(knob))
    }
}

/** A room temperature from a sensor (its state) or a thermostat (its current temperature). */
private fun roomTemperature(context: android.content.Context, s: HaState): String {
    val value = if (s.domain == "climate") s.number("current_temperature") else s.state.toDoubleOrNull()
    return value?.let { Format.degreesFine(context, it) } ?: Format.entityState(context, s)
}
