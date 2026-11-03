package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.data.ChecklistInfo
import alarmclock.vanbaelen.dev.data.ChecklistRow
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.NightBright
import alarmclock.vanbaelen.dev.ui.NightSelected
import alarmclock.vanbaelen.dev.ui.tokens
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The checklist's own colours (mockup "Checklist on the night display").
private val ProgressTrack = Color(0xFF1D140B)

/**
 * The bedtime checklist on the night display (540 × 350, right of the small clock): the list's
 * items in two columns to tick, and when all is done "Sleep well" with Dim the screen.
 */
@Composable
internal fun ChecklistPanel(f: NightFrame, night: NightViewModel, info: ChecklistInfo?, waiting: String, modifier: Modifier = Modifier) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(22))
    var showList by remember { mutableStateOf(false) }
    val open = info?.open
    LaunchedEffect(open) { if (open != 0) showList = false }
    Column(
        modifier
            .size(f.dp(540), f.dp(350))
            .clip(shape)
            .border(1.dp, t.line, shape)
            // Taps between the items do not reach the screen behind.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = f.dp(18), vertical = f.dp(16)),
        verticalArrangement = Arrangement.spacedBy(f.dp(10)),
    ) {
        if (info == null) {
            Text(waiting, style = nightText(f, 13), color = t.muted)
            return@Column
        }
        val done = info.items.size - info.open
        Column {
            Text(stringResource(R.string.checklist_title), style = nightText(f, 18, FontWeight.SemiBold), color = NightBright)
            Text(
                stringResource(R.string.checklist_progress, done, info.items.size, info.name),
                style = nightText(f, 13),
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val fraction by animateFloatAsState(if (info.items.isEmpty()) 0f else done.toFloat() / info.items.size, tween(350), label = "progress")
        Box(Modifier.fillMaxWidth().height(f.dp(3)).clip(RoundedCornerShape(f.dp(2))).background(ProgressTrack)) {
            Box(Modifier.fillMaxWidth(fraction).height(f.dp(3)).clip(RoundedCornerShape(f.dp(2))).background(t.accent))
        }
        if (info.items.isEmpty()) {
            Text(stringResource(R.string.checklist_empty), style = nightText(f, 13), color = t.muted)
        } else if (info.open == 0 && !showList) {
            AllDone(f, onDim = night::closeChecklist, onShowList = { showList = true })
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(f.dp(8))) {
                info.items.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(f.dp(8))) {
                        pair.forEach { row -> ChecklistItem(f, row, Modifier.weight(1f)) { night.tick(row.item.uid, !row.done) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One item to tick: a box with a tick, the item, and what ticking it runs. */
@Composable
private fun ChecklistItem(f: NightFrame, row: ChecklistRow, modifier: Modifier, onToggle: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(f.dp(16))
    val border by animateColorAsState(if (row.done) NightSelected else t.line, tween(200), label = "itemBorder")
    val box by animateColorAsState(if (row.done) t.accent else Color.Transparent, tween(200), label = "box")
    Row(
        modifier
            .heightIn(min = maxOf(48.dp, f.dp(60)))
            .clip(shape)
            .border(1.dp, border, shape)
            .toggleable(value = row.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = f.dp(12), vertical = f.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(f.dp(12)),
    ) {
        Box(
            Modifier
                .size(f.dp(26))
                .clip(RoundedCornerShape(f.dp(8)))
                .background(box)
                .border(2.dp, if (row.done) t.accent else AllOffLine, RoundedCornerShape(f.dp(8))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.check, null, tint = t.onAccent, modifier = Modifier.size(f.dp(16)).graphicsLayer { alpha = if (row.done) 1f else 0f })
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.item.summary,
                style = nightText(f, 15).copy(textDecoration = if (row.done) TextDecoration.LineThrough else TextDecoration.None),
                color = if (row.done) t.muted else NightBright,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.runs?.let { stringResource(R.string.checklist_runs, it) } ?: stringResource(R.string.checklist_tap),
                style = nightText(f, 12),
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Everything ticked: "Sleep well", Dim the screen (the resting clock) or the list again. */
@Composable
private fun AllDone(f: NightFrame, onDim: () -> Unit, onShowList: () -> Unit) {
    val t = tokens
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(f.dp(12), Alignment.CenterVertically),
    ) {
        Icon(AppIcons.moon, null, tint = t.accent, modifier = Modifier.size(f.dp(40)))
        Text(stringResource(R.string.checklist_done), style = nightText(f, 20, FontWeight.Medium), color = NightBright, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(f.dp(8))) {
            NightPill(f, stringResource(R.string.checklist_dim), fill = t.accent, textColor = t.onAccent, onClick = onDim)
            NightPill(f, stringResource(R.string.checklist_show_again), outlined = true, onClick = onShowList)
        }
    }
}

/** The line under the small clock while the checklist shows. */
@Composable
internal fun checklistIntro(nightStart: String): String = stringResource(R.string.checklist_intro, Format.time(LocalContext.current, nightStart))
