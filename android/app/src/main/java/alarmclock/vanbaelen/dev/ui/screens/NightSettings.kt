package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.ClockSize
import alarmclock.vanbaelen.dev.core.NightBrightness
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.NightBright
import alarmclock.vanbaelen.dev.ui.NightGlow
import alarmclock.vanbaelen.dev.ui.NightSelected
import alarmclock.vanbaelen.dev.ui.SuperdimGlow
import alarmclock.vanbaelen.dev.ui.SuperdimInk
import alarmclock.vanbaelen.dev.ui.tokens
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay

// The preview's frame and the Dim colours (mockup "Night display settings with live preview").
private val PreviewFrame = Color(0xFF1C140D)
private val DimGlow = Color(0xFF8A5A2E)
private val DimInk = Color(0xFF6E4A2A)

/** Width of the preview, in frame units; it shows the 844 wide night display scaled down. */
private const val PREVIEW_W = 410

/**
 * Clock size, Always on / Off until tapped and Normal / Dim / Superdim (the night display's
 * "Display", opened from Edit widgets), with a live preview to tap.
 */
@Composable
internal fun DisplaySettings(f: NightFrame, night: NightViewModel, settings: LocalSettings, clock: String, alarm: String?, modifier: Modifier = Modifier) {
    val t = tokens
    Row(modifier.size(f.dp(844), f.dp(390))) {
        Column(
            Modifier
                .width(f.dp(380))
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = f.dp(28), end = f.dp(20), top = f.dp(20), bottom = f.dp(20)),
            verticalArrangement = Arrangement.spacedBy(f.dp(16)),
        ) {
            Text(stringResource(R.string.display_title), style = nightText(f, 18, FontWeight.SemiBold), color = NightBright)
            Segments(
                f,
                title = stringResource(R.string.display_clock_size),
                help = stringResource(R.string.display_clock_size_help),
                options = ClockSize.entries.map { it to it.name },
                selected = settings.clockSize,
            ) { size -> night.updateSettings { it.copy(clockSize = size) } }
            Segments(
                f,
                title = stringResource(R.string.display_screen),
                help = stringResource(R.string.display_screen_help),
                options = listOf(false to stringResource(R.string.display_always_on), true to stringResource(R.string.display_off_until_tap)),
                selected = settings.screenOffUntilTap,
            ) { off -> night.updateSettings { it.copy(screenOffUntilTap = off) } }
            Segments(
                f,
                title = stringResource(R.string.display_brightness),
                help = stringResource(R.string.display_brightness_help),
                options = NightBrightness.entries.map { it to brightnessName(it) },
                selected = settings.nightBrightness,
            ) { level -> night.updateSettings { it.copy(nightBrightness = level) } }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(end = f.dp(24)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(f.dp(12), Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.display_preview), style = nightText(f, 12), color = t.muted)
            Preview(f, settings, clock, alarm)
            Text(
                summary(settings),
                style = nightText(f, 13),
                color = t.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(f.dp(380)),
            )
        }
    }
}

/** A titled row of choices, one selected, with a help line below. */
@Composable
private fun <T> Segments(f: NightFrame, title: String, help: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(16))
    Column(verticalArrangement = Arrangement.spacedBy(f.dp(6))) {
        Text(title.uppercase(), style = nightText(f, 12, FontWeight.SemiBold).copy(letterSpacing = 0.06.em), color = t.muted)
        Row(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, t.line, shape).padding(f.dp(4)).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(f.dp(4)),
        ) {
            options.forEach { (value, label) ->
                val on = value == selected
                val bg by animateColorAsState(if (on) NightSelected else Color.Transparent, tween(200), label = "segment")
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = maxOf(44.dp, f.dp(44)))
                        .clip(RoundedCornerShape(f.dp(12)))
                        .background(bg)
                        .selectable(selected = on, role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = nightText(f, 14, FontWeight.SemiBold), color = if (on) NightBright else t.muted, maxLines = 1)
                }
            }
        }
        Text(help, style = nightText(f, 12), color = t.muted)
    }
}

/**
 * The night display at [PREVIEW_W] wide with the chosen size and brightness. With Off until
 * tapped it is dark but for the alarm sign; a tap shows the clock for a few seconds.
 */
@Composable
private fun Preview(f: NightFrame, settings: LocalSettings, clock: String, alarm: String?) {
    val k = PREVIEW_W / 844f
    var peek by remember { mutableIntStateOf(0) }
    var peeking by remember { mutableStateOf(false) }
    LaunchedEffect(peek) {
        if (peek == 0) return@LaunchedEffect
        peeking = true
        delay(PREVIEW_PEEK_MS)
        peeking = false
    }
    val visible = !settings.screenOffUntilTap || peeking
    val clockA by animateFloatAsState(if (visible) 1f else 0f, tween(500), label = "previewClock")
    val (glow, ink) = when (settings.nightBrightness) {
        NightBrightness.NORMAL -> NightGlow to tokens.muted
        NightBrightness.DIM -> DimGlow to DimInk
        NightBrightness.SUPERDIM -> SuperdimGlow to SuperdimInk
    }
    val superdim = settings.nightBrightness == NightBrightness.SUPERDIM
    val size = settings.clockSize.frame
    val shape = RoundedCornerShape(f.dp(22))
    val description = stringResource(R.string.display_preview_hint)
    Box(
        Modifier
            .size(f.dp(PREVIEW_W), f.dp(190))
            .clip(shape)
            .background(Color.Black)
            .border(f.dp(6), PreviewFrame, shape)
            .clickable(role = Role.Button) { if (settings.screenOffUntilTap) peek++ }
            .semantics { contentDescription = description },
    ) {
        Column(
            Modifier.align(Alignment.Center).graphicsLayer { alpha = clockA },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(f.dp(6)),
        ) {
            Text(
                clock,
                style = nightText(f, size * k, if (superdim) FontWeight.Thin else FontWeight.ExtraLight)
                    .copy(letterSpacing = (-0.045).em, lineHeight = f.sp(size * k)),
                color = glow,
                maxLines = 1,
            )
            if (alarm != null) Text(stringResource(R.string.night_alarm_short, alarm), style = nightText(f, maxOf(8f, 15 * k * size / 132f)), color = ink)
        }
        if (alarm != null) {
            AlarmSign(
                f,
                alarm,
                scale = 8f / 11f,
                color = if (superdim) SuperdimInk else DimInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = f.dp(12), bottom = f.dp(9)).graphicsLayer { alpha = 1f - clockA },
            )
        }
    }
}

/** The tiny bell and alarm time that stays lit while the screen is off until tapped. */
@Composable
internal fun AlarmSign(f: NightFrame, time: String, color: Color, modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(f.dp(5 * scale))) {
        Icon(AppIcons.bell, null, tint = color, modifier = Modifier.size(f.dp(11 * scale)))
        Text(time, style = nightText(f, 11 * scale), color = color)
    }
}

@Composable
internal fun brightnessName(level: NightBrightness): String = stringResource(
    when (level) {
        NightBrightness.NORMAL -> R.string.brightness_normal
        NightBrightness.DIM -> R.string.brightness_dim
        NightBrightness.SUPERDIM -> R.string.brightness_superdim
    },
)

/** "Screen stays dark. Clock size L, dim." under the preview. */
@Composable
private fun summary(s: LocalSettings): String {
    val level = stringResource(
        when (s.nightBrightness) {
            NightBrightness.NORMAL -> R.string.display_summary_normal
            NightBrightness.DIM -> R.string.display_summary_dim
            NightBrightness.SUPERDIM -> R.string.display_summary_superdim
        },
    )
    val text = stringResource(R.string.display_summary, s.clockSize.name, level)
    return if (s.screenOffUntilTap) stringResource(R.string.display_summary_dark, text) else text
}

/** How long a tap shows the clock in the preview. */
private const val PREVIEW_PEEK_MS = 3_000L
