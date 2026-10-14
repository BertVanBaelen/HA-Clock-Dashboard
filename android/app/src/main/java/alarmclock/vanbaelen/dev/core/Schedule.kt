package alarmclock.vanbaelen.dev.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Weekday names as used by the API ("mon".."sun"); never numbers (see Alarm decisions). */
enum class Weekday(val api: String, val dayOfWeek: DayOfWeek) {
    MON("mon", DayOfWeek.MONDAY),
    TUE("tue", DayOfWeek.TUESDAY),
    WED("wed", DayOfWeek.WEDNESDAY),
    THU("thu", DayOfWeek.THURSDAY),
    FRI("fri", DayOfWeek.FRIDAY),
    SAT("sat", DayOfWeek.SATURDAY),
    SUN("sun", DayOfWeek.SUNDAY);

    companion object {
        fun fromApi(value: String): Weekday? = entries.firstOrNull { it.api == value }
        fun of(date: LocalDate): Weekday = entries.first { it.dayOfWeek == date.dayOfWeek }

        /** API list in canonical order (mon..sun), unknown values dropped. */
        fun parse(values: Collection<String>): List<Weekday> = entries.filter { day -> values.contains(day.api) }
    }
}

/** Action timing as used by the API. */
enum class Timing(val api: String) {
    BEFORE("before"),
    AT("at"),
    AFTER("after"),
    AFTER_DISMISS("after_dismiss");

    val needsOffset: Boolean get() = this == BEFORE || this == AFTER

    companion object {
        fun fromApi(value: String): Timing = entries.firstOrNull { it.api == value } ?: AT
    }
}

/**
 * Port of alarm_actions/shared/src/schedule.ts (TR-SCH-01, ATR-SYN-01). Verified against
 * docs/test-vectors/schedule.json, so the app computes the same occurrences as the server.
 *
 * Local times are wall-clock times in a time zone:
 * - a time that does not exist (spring forward gap) resolves to the first valid instant after it;
 * - an ambiguous time (fall back) resolves to its first occurrence.
 */
object Schedule {
    private val TIME = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")
    private val COMPACT_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun isValidTime(time: String): Boolean = TIME.matches(time)

    fun parseTime(time: String): LocalTime {
        val match = TIME.matchEntire(time) ?: throw IllegalArgumentException("Invalid time: $time")
        return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    /** The instant of a local wall-clock time (gap: first valid instant after; overlap: first). */
    fun resolveLocal(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Instant {
        val local = date.atTime(hour, minute)
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        if (offsets.isNotEmpty()) return offsets.minOf { local.toInstant(it) }
        // In a gap: the transition instant is the first moment whose wall-clock time is after it.
        return rules.getTransition(local).instant
    }

    /** Occurrence instants strictly after [after], in order (at most [count]). */
    fun upcomingOccurrences(
        time: String,
        repeatDays: Collection<Weekday>,
        zone: ZoneId,
        after: Instant,
        count: Int,
    ): List<Instant> {
        val t = parseTime(time)
        val start = after.atZone(zone).toLocalDate()
        val result = ArrayList<Instant>(count)
        var i = 0
        while (result.size < count && i < 7 * count + 2) {
            val date = start.plusDays(i.toLong())
            i++
            if (repeatDays.isNotEmpty() && Weekday.of(date) !in repeatDays) continue
            val at = resolveLocal(date, t.hour, t.minute, zone)
            if (at.isAfter(after)) result.add(at)
            if (repeatDays.isEmpty() && result.isNotEmpty()) break
        }
        return result
    }

    /**
     * Next time the alarm rings strictly after [after]. `skipNext` skips the first one (repeating
     * alarms only). Null when there is none.
     */
    fun nextOccurrence(
        time: String,
        repeatDays: Collection<Weekday>,
        skipNext: Boolean,
        zone: ZoneId,
        after: Instant,
    ): Instant? {
        val skip = skipNext && repeatDays.isNotEmpty()
        val list = upcomingOccurrences(time, repeatDays, zone, after, if (skip) 2 else 1)
        return if (skip) list.getOrNull(1) else list.getOrNull(0)
    }

    /**
     * Next ring for the phone's alarm engine. Instead of a `skip_next` flag (which only resets on
     * the server once the skipped occurrence passed) it takes the local date of the skipped
     * occurrence, so a phone that has not synced since does not skip a second time.
     */
    fun nextRing(
        time: String,
        repeatDays: Collection<Weekday>,
        skippedDate: LocalDate?,
        zone: ZoneId,
        after: Instant,
    ): Instant? {
        val list = upcomingOccurrences(time, repeatDays, zone, after, 2)
        val first = list.getOrNull(0) ?: return null
        if (repeatDays.isNotEmpty() && skippedDate != null && first.atZone(zone).toLocalDate() == skippedDate) {
            return list.getOrNull(1)
        }
        return first
    }

    /** Local date of the occurrence that `skip_next` skips, or null when nothing is skipped. */
    fun skippedDate(
        time: String,
        repeatDays: Collection<Weekday>,
        skipNext: Boolean,
        zone: ZoneId,
        now: Instant,
    ): LocalDate? {
        if (!skipNext || repeatDays.isEmpty() || !isValidTime(time)) return null
        val first = upcomingOccurrences(time, repeatDays, zone, now, 1).firstOrNull() ?: return null
        return first.atZone(zone).toLocalDate()
    }

    /** Occurrence id `<alarm_id>_<YYYYMMDDTHHMMSSZ>` (TR-API-04). */
    fun occurrenceId(alarmId: String, scheduledAt: Instant): String =
        "${alarmId}_${COMPACT_UTC.format(scheduledAt)}"

    /** Scheduled time encoded in an occurrence id, or null when it is not one. */
    fun occurrenceTime(occurrenceId: String): Instant? = runCatching {
        ZonedDateTime.parse(occurrenceId.substringAfterLast('_'), COMPACT_UTC).toInstant()
    }.getOrNull()

    /** Planned-run id `<occurrence_id>_<alarm_action_id>` (TR-SCH-06). */
    fun plannedRunId(occurrenceId: String, alarmActionId: String): String = "${occurrenceId}_$alarmActionId"

    /** Planned time of a before / at / after action; null for after_dismiss. */
    fun plannedRunAt(scheduledAt: Instant, timing: Timing, offsetMinutes: Int?): Instant? = when (timing) {
        Timing.BEFORE -> scheduledAt.minusSeconds((offsetMinutes ?: 0) * 60L)
        Timing.AT -> scheduledAt
        Timing.AFTER -> scheduledAt.plusSeconds((offsetMinutes ?: 0) * 60L)
        Timing.AFTER_DISMISS -> null
    }
}
