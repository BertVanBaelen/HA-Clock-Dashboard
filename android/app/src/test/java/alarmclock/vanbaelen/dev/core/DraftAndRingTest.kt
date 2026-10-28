package alarmclock.vanbaelen.dev.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DraftAndRingTest {
    private val brussels = "Europe/Brussels"

    @Test
    fun validationMatchesPanel() {
        val ok = Draft(time = "06:45", actions = listOf(Drafts.newAction("script.a")))
        assertTrue(Drafts.validate(ok).isEmpty())
        assertEquals(DraftError.INVALID_TIME, Drafts.validate(ok.copy(time = "24:00"))[Drafts.ALARM_KEY])
        assertEquals(DraftError.INVALID_TIME, Drafts.validate(ok.copy(time = "7:00"))[Drafts.ALARM_KEY])
        val a = Drafts.newAction("")
        assertEquals(DraftError.CHOOSE_SCRIPT, Drafts.validate(ok.copy(actions = listOf(a)))[a.key])
        val b = Drafts.newAction("script.a").copy(offsetMinutes = 721)
        assertEquals(DraftError.MINUTES_RANGE, Drafts.validate(ok.copy(actions = listOf(b)))[b.key])
        val c = Drafts.withTiming(Drafts.newAction("script.a"), Timing.AFTER_DISMISS).copy(delayMinutes = 121)
        assertEquals(DraftError.DELAY_RANGE, Drafts.validate(ok.copy(actions = listOf(c)))[c.key])
        val d1 = Drafts.newAction("script.a")
        val d2 = Drafts.newAction("script.a")
        assertEquals(DraftError.DUPLICATE_ACTION, Drafts.validate(ok.copy(actions = listOf(d1, d2)))[d2.key])
        assertEquals(DraftError.SNOOZE_RANGE, Drafts.validate(ok.copy(snoozeMinutes = 31))[Drafts.ALARM_KEY])
    }

    @Test
    fun withTimingResetsFields() {
        val a = Drafts.newAction("script.a")
        val at = Drafts.withTiming(a, Timing.AT)
        assertNull(at.offsetMinutes)
        assertNull(at.delayMinutes)
        val dismiss = Drafts.withTiming(a, Timing.AFTER_DISMISS)
        assertEquals(0, dismiss.delayMinutes)
        assertEquals(10, Drafts.withTiming(at, Timing.AFTER).offsetMinutes)
    }

    @Test
    fun bodyKeepsActionIdsAndRevision() {
        val alarm = Alarm(
            id = "a", time = "06:45", repeatDays = listOf("tue", "mon"), revision = 3,
            actions = listOf(AlarmAction("x", "script.a", "before", 10, null, true)),
        )
        val input = Drafts.toInput(Drafts.edit(alarm))
        assertEquals(listOf("mon", "tue"), input.repeatDays)
        assertEquals("x", input.actions.single().id)
        assertEquals(3, input.revision)
        val json = ApiJson.encodeToString(AlarmInput.serializer(), Drafts.toInput(Drafts.copy(Drafts.fromAlarm(alarm))))
        assertTrue(json, !json.contains("\"id\"") && !json.contains("revision") && json.contains("\"snooze_minutes\":null"))
    }

    @Test
    fun missedBeforeWarning() {
        // Now 06:40 local on a Wednesday; a 10 min "before" action for 06:45 is already past.
        val now = Instant.parse("2026-10-14T04:40:00Z")
        val action = Drafts.newAction("script.a")
        val draft = Draft(time = "06:45", repeatDays = listOf(Weekday.WED), actions = listOf(action))
        assertEquals(listOf(action), Drafts.missedBeforeActions(draft, brussels, now))
        assertTrue(Drafts.missedBeforeActions(draft.copy(time = "07:00"), brussels, now).isEmpty())
        assertTrue(Drafts.missedBeforeActions(draft.copy(enabled = false), brussels, now).isEmpty())
        assertTrue(Drafts.missedBeforeActions(draft, null, now).isEmpty())
        assertTrue(Drafts.missedBeforeActions(draft.copy(skipNext = true), brussels, now).isEmpty())
    }

    private fun spec(time: String, days: List<String>, revision: Int = 1, skipped: String? = null, haZone: String? = brussels) =
        RingSpec("a", revision, "Wake", time, days, true, skipped, 9, haTimeZone = haZone)

    @Test
    fun ringPlanAtHomeUsesHaOccurrenceId() {
        val now = Instant.parse("2026-10-14T10:16:00Z")
        val ring = RingPlanner.plan(listOf(spec("06:45", listOf("mon", "tue", "wed", "thu", "fri"))), emptyMap(), ZoneId.of(brussels), now).single()
        assertEquals(Instant.parse("2026-10-15T04:45:00Z").toEpochMilli(), ring.ringAt)
        assertEquals("a_20261015T044500Z", ring.occurrenceId)
    }

    @Test
    fun ringPlanKeepsVibrationChoiceAlsoWhenSnoozed() {
        val now = Instant.parse("2026-10-14T10:16:00Z")
        val zone = ZoneId.of(brussels)
        assertTrue(RingPlanner.next(spec("06:45", listOf("thu")), null, zone, now)!!.vibrate)
        val silent = RingPlanner.next(spec("06:45", listOf("thu")).copy(vibrate = false), null, zone, now)!!
        assertEquals(false, silent.vibrate)
        assertEquals(false, silent.copy(snooze = true).vibrate)
    }

    @Test
    fun ringPlanAwayRingsAtLocalWallClockWithoutReports() {
        val now = Instant.parse("2026-10-14T10:16:00Z")
        val ny = ZoneId.of("America/New_York")
        val ring = RingPlanner.plan(listOf(spec("06:45", listOf("thu"))), emptyMap(), ny, now).single()
        assertEquals(Instant.parse("2026-10-15T10:45:00Z").toEpochMilli(), ring.ringAt)
        assertNull(ring.occurrenceId)
    }

    @Test
    fun skippedDateOnlySkipsOnce() {
        val zone = ZoneId.of(brussels)
        val beforeSkip = Instant.parse("2026-10-14T10:16:00Z")
        val skipped = Schedule.skippedDate("06:45", Weekday.parse(listOf("thu", "fri")), true, zone, beforeSkip)
        val first = RingPlanner.next(spec("06:45", listOf("thu", "fri"), skipped = skipped.toString()), null, zone, beforeSkip)!!
        assertEquals(Instant.parse("2026-10-16T04:45:00Z").toEpochMilli(), first.ringAt)
        // After the skipped Thursday passed without a sync, Friday still rings and so does the next Thursday.
        val later = Instant.parse("2026-10-16T05:00:00Z")
        val next = RingPlanner.next(spec("06:45", listOf("thu", "fri"), skipped = skipped.toString()), null, zone, later)!!
        assertEquals(Instant.parse("2026-10-22T04:45:00Z").toEpochMilli(), next.ringAt)
    }

    @Test
    fun oneTimeRingsOncePerRevision() {
        val zone = ZoneId.of(brussels)
        val now = Instant.parse("2026-10-14T10:16:00Z")
        val fired = FiredInfo(now.toEpochMilli(), revision = 1, oneTime = true)
        assertNull(RingPlanner.next(spec("06:45", emptyList()), fired, zone, now))
        assertTrue(RingPlanner.next(spec("06:45", emptyList(), revision = 2), fired, zone, now) != null)
    }

    @Test
    fun earlyDismissedOccurrenceDoesNotRing() {
        val zone = ZoneId.of(brussels)
        val now = Instant.parse("2026-10-15T04:00:00Z")
        val dismissed = Instant.parse("2026-10-15T04:45:00Z").toEpochMilli()
        val repeating = spec("06:45", listOf("thu", "fri")).copy(dismissedAt = dismissed)
        val ring = RingPlanner.next(repeating, null, zone, now)!!
        assertEquals(Instant.parse("2026-10-16T04:45:00Z").toEpochMilli(), ring.ringAt)
        assertEquals("a_20261016T044500Z", ring.occurrenceId)
        assertNull(RingPlanner.next(spec("06:45", emptyList()).copy(dismissedAt = dismissed), null, zone, now))
        // A dismissal of an older occurrence does not affect the next one.
        val old = repeating.copy(dismissedAt = Instant.parse("2026-10-09T04:45:00Z").toEpochMilli())
        assertEquals(dismissed, RingPlanner.next(old, null, zone, now)!!.ringAt)
    }

    @Test
    fun missedRings() {
        val now = Instant.parse("2026-10-14T10:16:00Z")
        val rings = listOf(
            PlannedRing("a", now.minusSeconds(3600).toEpochMilli(), null, "", "06:00", 9),
            PlannedRing("b", now.plusSeconds(3600).toEpochMilli(), null, "", "06:00", 9),
        )
        assertEquals(listOf("a"), RingPlanner.missed(rings, now).map { it.alarmId })
    }
}
