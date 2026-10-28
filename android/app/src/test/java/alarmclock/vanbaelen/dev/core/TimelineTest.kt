package alarmclock.vanbaelen.dev.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineTest {
    @Test
    fun rangeIsAtLeastAnHourAndRoundsToQuarters() {
        assertEquals(60, Timeline.range(emptyList()))
        assertEquals(60, Timeline.range(listOf(-30, 55)))
        assertEquals(75, Timeline.range(listOf(-61)))
        assertEquals(120, Timeline.range(listOf(120)))
        assertEquals(720, Timeline.range(listOf(-720)))
    }

    @Test
    fun scaleShrinksForLongRanges() {
        assertEquals(6f, Timeline.pxPerMin(60), 0f)
        assertEquals(6f, Timeline.pxPerMin(120), 0f)
        assertEquals(2f, Timeline.pxPerMin(360), 0f)
        assertEquals(760f, Timeline.height(60), 0f)
        assertEquals(Timeline.height(120), Timeline.height(720), 0f)
    }

    @Test
    fun dragSnapsAndClamps() {
        assertEquals(0, Timeline.offsetAt(Timeline.y(0, 60), 60))
        assertEquals(-15, Timeline.offsetAt(Timeline.y(-14, 60), 60))
        assertEquals(60, Timeline.offsetAt(10_000f, 60))
        assertEquals(-60, Timeline.offsetAt(-10f, 60))
    }

    @Test
    fun overlappingCardsArePushedDown() {
        val tops = Timeline.cardTops(listOf(100f, 110f, 300f))
        assertEquals(84f, tops[0], 0f)
        assertEquals(84f + Timeline.CARD_HEIGHT + Timeline.CARD_GAP, tops[1], 0f)
        assertEquals(284f, tops[2], 0f)
    }

    @Test
    fun signedOffsetsAndMoves() {
        val a = Drafts.newAction("script.lights")
        assertEquals(-10, a.signedOffset)
        assertEquals(Timing.AT, a.atOffset(0).timing)
        assertNull(a.atOffset(0).offsetMinutes)
        assertEquals(Timing.AFTER, a.atOffset(5).timing)
        assertEquals(5, a.atOffset(5).offsetMinutes)
        assertNull(a.afterDismiss(3).signedOffset)
        assertEquals(3, a.afterDismiss(3).delayMinutes)
    }

    @Test
    fun summaryCountsPerSide() {
        val actions = listOf(
            AlarmAction("1", "script.a", "before", 30),
            AlarmAction("2", "script.b", "at"),
            AlarmAction("3", "script.c", "after", 5),
            AlarmAction("4", "script.d", "after_dismiss", delayMinutes = 0),
        )
        assertEquals(ActionSummary(1, 1, 1, 1), ActionSummary.of(actions))
    }
}
