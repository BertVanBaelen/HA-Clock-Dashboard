package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.ui.AppType
import alarmclock.vanbaelen.dev.ui.tokens
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/** Motion curves of the design (docs/design.md, Motion). */
val EaseSoft = CubicBezierEasing(0.22f, 0.8f, 0.24f, 1f)
val EaseMorph = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

enum class BannerKind { INFO, WARNING, ERROR }

/** A status card with a coloured left stripe and an optional action (sync state, updates, problems); the trailing lambda is the action. */
@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, action: String? = null, secondary: String? = null, onSecondary: () -> Unit = {}, onAction: () -> Unit = {}) {
    val t = tokens
    val stripe = when (kind) {
        BannerKind.INFO -> t.accent
        BannerKind.WARNING -> t.warn
        BannerKind.ERROR -> t.danger
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line, shape),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = if (action == null && secondary == null) 12.dp else 0.dp)) {
            Text(text, style = AppType.label, color = t.ink, modifier = Modifier.padding(end = 8.dp))
            if (action != null || secondary != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary, color = t.muted) }
                    if (action != null) TextButton(onClick = onAction) { Text(action, color = t.accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = tokens.muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

/** Material 3 time picker in a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state) },
    )
}

/** New content slides up 14 dp and fades in (Rise, 340 ms). */
@Composable
fun Rise(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(340, easing = EaseSoft)) }
    Box(modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 14.dp.toPx() }) { content() }
}

/** Strength 1..0 of the accent ring after [token] changes to a non-null value (Flash, 1.8 s). */
@Composable
fun rememberFlash(token: Any?): Float {
    val p = remember { Animatable(0f) }
    LaunchedEffect(token) {
        if (token == null) return@LaunchedEffect
        p.snapTo(1f)
        p.animateTo(0f, keyframes { durationMillis = 1800; 1f at 810; 0f at 1800 })
    }
    return p.value
}

/** The design's switch: 46×28 track, 22 knob that moves 18. */
@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = tokens
    val track by animateColorAsState(if (checked) t.accent else t.off, tween(250), label = "track")
    val knob by animateColorAsState(if (checked) t.onAccent else t.knob, tween(250), label = "knob")
    val x by animateDpAsState(if (checked) 18.dp else 0.dp, tween(300, easing = EaseSoft), label = "knobX")
    Box(
        modifier
            .size(56.dp, 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(track).padding(3.dp)) {
            Box(Modifier.offset(x = x).size(22.dp).clip(CircleShape).background(knob))
        }
    }
}

/** A round 44 icon button. */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, background: Color = Color.Transparent, tint: Color = tokens.muted, size: Dp = 20.dp) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(background).clickable(onClick = onClick, role = Role.Button).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size))
    }
}

/** A small outlined pill, for list shortcuts such as "Skip next". */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    val t = tokens
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.heightIn(min = 36.dp).clip(shape).border(1.dp, if (accent) t.accent else t.line, shape).clickable(onClick = onClick, role = Role.Button).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppType.secondary, fontWeight = FontWeight.Medium, color = if (accent) t.accent else t.ink)
    }
}

/** A 40 high chip with an optional icon; [selected] fills it with `soft`. */
@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false) {
    val t = tokens
    val shape = RoundedCornerShape(20.dp)
    val bg by animateColorAsState(if (selected) t.soft else Color.Transparent, tween(200), label = "chip")
    Row(
        modifier.height(40.dp).clip(shape).background(bg).border(1.dp, t.line, shape).clickable(onClick = onClick, role = Role.Button).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = t.ink, modifier = Modifier.size(16.dp))
        Text(text, style = AppType.secondary, fontWeight = FontWeight.Medium, color = t.ink, maxLines = 1)
    }
}

/** Sheet buttons: [ButtonKind.PRIMARY] ink, [ButtonKind.ACCENT] accent, [ButtonKind.OUTLINE] bordered. */
enum class ButtonKind { PRIMARY, ACCENT, OUTLINE }

@Composable
fun SheetButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.OUTLINE, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false) {
    val t = tokens
    val shape = RoundedCornerShape(if (kind == ButtonKind.PRIMARY) 18.dp else 16.dp)
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY -> t.ink to t.bg
        ButtonKind.ACCENT -> t.accent to t.onAccent
        ButtonKind.OUTLINE -> Color.Transparent to (if (danger) t.danger else t.ink)
    }
    Row(
        modifier
            .heightIn(min = if (kind == ButtonKind.PRIMARY) 54.dp else 52.dp)
            .clip(shape)
            .background(bg)
            .then(if (kind == ButtonKind.OUTLINE) Modifier.border(1.dp, t.line, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text(
            text,
            style = AppType.body,
            fontWeight = if (kind == ButtonKind.OUTLINE) FontWeight.Medium else FontWeight.SemiBold,
            color = fg,
            textAlign = TextAlign.Center,
        )
    }
}

/** A flash ring around [shape], see [rememberFlash]. */
fun Modifier.flashRing(strength: Float, color: Color, shape: Shape): Modifier =
    if (strength <= 0f) this else this.border(2.dp, color.copy(alpha = strength), shape)
