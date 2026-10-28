package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.Draft
import alarmclock.vanbaelen.dev.core.DraftAction
import alarmclock.vanbaelen.dev.core.Timeline
import alarmclock.vanbaelen.dev.core.signedOffset
import alarmclock.vanbaelen.dev.data.ScriptRow
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.AppType
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.tokens
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The vertical timeline around the alarm and the "After you dismiss" section (docs/design.md).
 * Reports the alarm dot and the viewport top to [anchors] for the morph animation.
 */
@Composable
internal fun TimelineBody(
    draft: Draft,
    scripts: List<ScriptRow>,
    mode: DetailMode,
    placing: Placing?,
    flash: Pair<String, Int>?,
    anchors: MorphAnchors,
    cardsAlpha: () -> Float,
    railAlpha: () -> Float,
    onEdit: (DraftAction) -> Unit,
    onDrag: (Int) -> Unit,
) {
    val context = LocalContext.current
    val localDensity = LocalDensity.current
    val t = tokens
    val placingNow = if (mode == DetailMode.PLACE) placing else null
    val placingAround = placingNow != null && !placingNow.afterDismiss
    val around = draft.actions.filter { it.signedOffset != null }.sortedBy { it.signedOffset }
    val dismissed = draft.actions.filter { it.signedOffset == null }.sortedBy { it.delayMinutes ?: 0 }
    val range = Timeline.range(around.mapNotNull { it.signedOffset } + listOfNotNull(placingNow?.takeIf { placingAround }?.offset))
    val scroll = rememberScrollState(with(localDensity) { (Timeline.y(0, range) - 260f).coerceAtLeast(0f).dp.roundToPx() })
    val drag by rememberUpdatedState(onDrag)
    // The range can shrink while dragging; reading it here keeps the gesture alive.
    val dragRange by rememberUpdatedState(range)

    // A newly picked script: bring its place into view.
    LaunchedEffect(mode, placingNow?.key) {
        val p = placingNow ?: return@LaunchedEffect
        if (p.key == null && !p.afterDismiss) {
            scroll.animateScrollTo(with(localDensity) { (Timeline.y(p.offset, range) - 180f).coerceAtLeast(0f).dp.roundToPx() })
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { anchors.viewportTop = it.positionInRoot().y }
            .verticalScroll(scroll, enabled = !placingAround)
            .padding(horizontal = 24.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Timeline.height(range).dp)
                .onGloballyPositioned { c ->
                    val p = c.positionInRoot()
                    anchors.dot = with(localDensity) { Offset(p.x + 60.5.dp.toPx(), p.y + Timeline.y(0, range).dp.toPx()) }
                }
                .pointerInput(placingAround) {
                    if (!placingAround) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        drag(Timeline.offsetAt(down.position.y / density, dragRange))
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            drag(Timeline.offsetAt(change.position.y / density, dragRange))
                            change.consume()
                        }
                    }
                },
        ) {
            // Rail, ticks and the alarm line.
            Canvas(Modifier.matchParentSize()) {
                val railX = 60.5.dp.toPx()
                drawLine(t.line, Offset(railX, Timeline.PAD.dp.toPx()), Offset(railX, (Timeline.height(range) - Timeline.PAD).dp.toPx()), 1.dp.toPx())
                val minor = Timeline.minorStep(range)
                var o = -range
                while (o <= range) {
                    val y = Timeline.y(o, range).dp.toPx()
                    drawLine(t.line, Offset(57.dp.toPx(), y), Offset(64.dp.toPx(), y), 1.dp.toPx())
                    o += minor
                }
                val alarmY = Timeline.y(0, range).dp.toPx()
                drawLine(t.accent.copy(alpha = 0.9f), Offset(0f, alarmY), Offset(size.width, alarmY), 2.dp.toPx())
                drawCircle(t.accent, 6.5.dp.toPx(), Offset(railX, alarmY))
            }
            // Action dots on the rail.
            Canvas(Modifier.matchParentSize().graphicsLayer { alpha = railAlpha() }) {
                val railX = 60.5.dp.toPx()
                around.forEach { a ->
                    val alpha = when {
                        placingNow == null -> 1f
                        a.key == placingNow.key -> 0f
                        else -> 0.35f
                    }
                    drawCircle(t.ink.copy(alpha = alpha), 4.5.dp.toPx(), Offset(railX, Timeline.y(a.signedOffset ?: 0, range).dp.toPx()))
                }
            }
            val major = Timeline.majorStep(range)
            var tick = -range
            while (tick <= range) {
                if (tick != 0) {
                    Text(
                        Format.shifted(context, draft.time, tick),
                        style = AppType.small,
                        color = t.muted,
                        modifier = Modifier.offset(y = (Timeline.y(tick, range) - 8f).dp),
                    )
                }
                tick += major
            }
            Text(
                Format.time(context, draft.time),
                style = AppType.small,
                fontWeight = FontWeight.Bold,
                color = t.accent,
                modifier = Modifier.offset(y = (Timeline.y(0, range) - 18f).dp).background(t.bg).padding(end = 4.dp),
            )

            // Action cards, pushed down where they would overlap.
            Box(Modifier.matchParentSize().graphicsLayer { alpha = cardsAlpha() }) {
                val tops = Timeline.cardTops(around.map { Timeline.y(it.signedOffset ?: 0, range) })
                around.forEachIndexed { i, a ->
                    key(a.key) {
                        val top by animateFloatAsState(tops[i], tween(350, easing = EaseSoft), label = "cardTop")
                        val alpha by animateFloatAsState(
                            when {
                                placingNow == null -> 1f
                                a.key == placingNow.key -> 0f
                                else -> 0.35f
                            },
                            tween(250),
                            label = "cardAlpha",
                        )
                        ActionCard(
                            action = a,
                            scripts = scripts,
                            flash = rememberFlash(flash?.takeIf { it.first == a.key }),
                            onClick = if (mode == DetailMode.VIEW) ({ onEdit(a) }) else null,
                            modifier = Modifier
                                .offset(y = top.dp)
                                .padding(start = 74.dp)
                                .fillMaxWidth()
                                .height(Timeline.CARD_HEIGHT.dp)
                                .graphicsLayer { this.alpha = alpha },
                        )
                    }
                }
            }

            if (placingAround) PlaceGhost(placingNow!!, draft, scripts, range)
        }

        if (dismissed.isNotEmpty() || placingNow?.afterDismiss == true) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.after_you_dismiss), style = AppType.bodyStrong, color = t.ink)
                dismissed.forEach { a ->
                    key(a.key) {
                        if (placingNow != null && placingNow.afterDismiss && placingNow.key == a.key) {
                            DismissGhost(placingNow, scripts)
                        } else {
                            ActionCard(
                                action = a,
                                scripts = scripts,
                                flash = rememberFlash(flash?.takeIf { it.first == a.key }),
                                onClick = if (mode == DetailMode.VIEW) ({ onEdit(a) }) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .graphicsLayer { alpha = if (placingNow == null) 1f else 0.35f },
                                cardsAlpha = cardsAlpha,
                            )
                        }
                    }
                }
                if (placingNow != null && placingNow.afterDismiss && dismissed.none { it.key == placingNow.key }) DismissGhost(placingNow, scripts)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One action on the timeline or in the dismiss section. */
@Composable
private fun ActionCard(
    action: DraftAction,
    scripts: List<ScriptRow>,
    flash: Float,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    cardsAlpha: () -> Float = { 1f },
) {
    val context = LocalContext.current
    val t = tokens
    val script = scripts.firstOrNull { it.entityId == action.scriptEntityId }
    val unavailable = scripts.isNotEmpty() && script == null
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .graphicsLayer { alpha = cardsAlpha() }
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line, shape)
            .flashRing(flash, t.accent, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val content = if (action.enabled) 1f else 0.5f
        Icon(
            if (unavailable) AppIcons.warning else AppIcons.mdi(script?.iconPath) ?: AppIcons.script,
            null,
            tint = (if (unavailable) t.warn else t.ink).copy(alpha = content),
            modifier = Modifier.size(16.dp),
        )
        Text(
            script?.name ?: action.scriptEntityId,
            style = AppType.secondary,
            fontWeight = FontWeight.SemiBold,
            color = (if (unavailable) t.warn else t.ink).copy(alpha = content),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            script?.description.orEmpty(),
            style = AppType.secondary,
            color = t.muted.copy(alpha = content),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (action.enabled) Format.relative(context, action.signedOffset, action.delayMinutes) else stringResource(R.string.off),
            style = AppType.small,
            color = t.muted,
            maxLines = 1,
        )
    }
}

/** Guide line, knob, run time and the accent card that follow the offset while placing. */
@Composable
private fun PlaceGhost(p: Placing, draft: Draft, scripts: List<ScriptRow>, range: Int) {
    val context = LocalContext.current
    val t = tokens
    val gy by animateFloatAsState(Timeline.y(p.offset, range), tween(120), label = "guide")
    val script = scripts.firstOrNull { it.entityId == p.scriptEntityId }
    Canvas(Modifier.fillMaxSize()) {
        val y = gy.dp.toPx()
        drawLine(
            t.accent,
            Offset(0f, y),
            Offset(size.width, y),
            1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
    Text(
        Format.shifted(context, draft.time, p.offset),
        style = AppType.small,
        fontWeight = FontWeight.Bold,
        color = t.accent,
        modifier = Modifier.offset(y = (gy - 8f).dp).background(t.bg).padding(end = 4.dp),
    )
    Box(
        Modifier
            .offset(x = 52.dp, y = (gy - 8.5f).dp)
            .size(17.dp)
            .clip(CircleShape)
            .background(t.bg)
            .padding(3.dp)
            .clip(CircleShape)
            .background(t.accent),
    )
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .offset(y = (gy - 18f).dp)
            .padding(start = 74.dp)
            .fillMaxWidth()
            .height(36.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(t.accent)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.mdi(script?.iconPath) ?: AppIcons.script, null, tint = t.onAccent, modifier = Modifier.size(16.dp))
        Text(
            script?.name ?: p.scriptEntityId,
            style = AppType.secondary,
            fontWeight = FontWeight.SemiBold,
            color = t.onAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(AppIcons.upDown, null, tint = t.onAccent, modifier = Modifier.size(16.dp))
    }
}

/** The accent card of an action being placed after the dismissal. */
@Composable
private fun DismissGhost(p: Placing, scripts: List<ScriptRow>) {
    val context = LocalContext.current
    val t = tokens
    val script = scripts.firstOrNull { it.entityId == p.scriptEntityId }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(shape).background(t.accent).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppIcons.mdi(script?.iconPath) ?: AppIcons.script, null, tint = t.onAccent, modifier = Modifier.size(16.dp))
        Text(
            script?.name ?: p.scriptEntityId,
            style = AppType.secondary,
            fontWeight = FontWeight.SemiBold,
            color = t.onAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(Format.relative(context, null, p.delay), style = AppType.small, color = t.onAccent)
    }
}
