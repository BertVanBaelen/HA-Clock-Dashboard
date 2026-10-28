package alarmclock.vanbaelen.dev.core

import kotlin.math.abs
import kotlin.math.max

/** Geometry of the alarm timeline (docs/design.md, Alarm detail); all lengths in dp. */
object Timeline {
    /** Space above the first and below the last minute. */
    const val PAD = 20f
    const val CARD_HEIGHT = 32f
    const val CARD_GAP = 2f
    /** Width of the mini timeline in the list. */
    const val MINI_WIDTH = 180f
    /** Offset of a newly picked action: 15 min before the alarm. */
    const val PICK_OFFSET = -15
    /** Dragging snaps to this many minutes. */
    const val DRAG_SNAP = 5

    /** Minutes shown on each side: at least 60, else the farthest offset rounded up to 15. */
    fun range(offsets: List<Int>): Int {
        val far = offsets.maxOfOrNull { abs(it) } ?: 0
        return max(60, (far + 14) / 15 * 15)
    }

    fun pxPerMin(range: Int): Float = if (range <= 120) 6f else 720f / range

    fun majorStep(range: Int): Int = when {
        range <= 60 -> 15
        range <= 180 -> 30
        else -> 60
    }

    fun minorStep(range: Int): Int = when {
        range <= 60 -> 5
        range <= 180 -> 15
        else -> 30
    }

    /** Vertical position of [offset] minutes. */
    fun y(offset: Int, range: Int): Float = PAD + (offset + range) * pxPerMin(range)

    fun height(range: Int): Float = 2 * PAD + 2 * range * pxPerMin(range)

    /** Offset for a vertical position, snapped to [snap] minutes and clamped to the range. */
    fun offsetAt(y: Float, range: Int, snap: Int = DRAG_SNAP): Int {
        val raw = (y - PAD) / pxPerMin(range) - range
        val snapped = Math.round(raw / snap) * snap
        return snapped.coerceIn(-range, range)
    }

    /**
     * Card tops for cards centred on [centres] (in timeline order): a card that would overlap the
     * previous one is pushed down, so cards never cover each other. The rail dots stay true.
     */
    fun cardTops(centres: List<Float>): List<Float> {
        val tops = ArrayList<Float>(centres.size)
        var last = Float.NEGATIVE_INFINITY
        for (c in centres) {
            val top = max(c - CARD_HEIGHT / 2, last + CARD_HEIGHT + CARD_GAP)
            tops += top
            last = top
        }
        return tops
    }

    /** Position on the mini timeline in 0..1 (clamped to 2..98 %, like the mockup). */
    fun miniFraction(offset: Int, range: Int): Float =
        ((offset + range).toFloat() / (2 * range)).coerceIn(0.02f, 0.98f)
}

/** Signed minutes from the alarm (before negative), null for `after_dismiss`. */
val DraftAction.signedOffset: Int?
    get() = signed(timing, offsetMinutes)

val AlarmAction.signedOffset: Int?
    get() = signed(Timing.fromApi(timing), offsetMinutes)

private fun signed(timing: Timing, offset: Int?): Int? = when (timing) {
    Timing.BEFORE -> -(offset ?: 0)
    Timing.AT -> 0
    Timing.AFTER -> offset ?: 0
    Timing.AFTER_DISMISS -> null
}

/** The action moved to [offset] signed minutes around the alarm (0 = at the alarm). */
fun DraftAction.atOffset(offset: Int): DraftAction = when {
    offset < 0 -> copy(timing = Timing.BEFORE, offsetMinutes = -offset, delayMinutes = null)
    offset == 0 -> copy(timing = Timing.AT, offsetMinutes = null, delayMinutes = null)
    else -> copy(timing = Timing.AFTER, offsetMinutes = offset, delayMinutes = null)
}

/** The action moved to after the dismissal, [delay] minutes later. */
fun DraftAction.afterDismiss(delay: Int): DraftAction =
    copy(timing = Timing.AFTER_DISMISS, offsetMinutes = null, delayMinutes = delay)

/** Counts for the list summary "2 before · 1 with · 1 after · 1 after dismiss". */
data class ActionSummary(val before: Int, val with: Int, val after: Int, val afterDismiss: Int) {
    val isEmpty: Boolean get() = before + with + after + afterDismiss == 0

    companion object {
        fun of(actions: List<AlarmAction>): ActionSummary {
            val offsets = actions.map { it.signedOffset }
            return ActionSummary(
                before = offsets.count { it != null && it < 0 },
                with = offsets.count { it == 0 },
                after = offsets.count { it != null && it > 0 },
                afterDismiss = offsets.count { it == null },
            )
        }
    }
}
