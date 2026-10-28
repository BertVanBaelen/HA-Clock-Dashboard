package alarmclock.vanbaelen.dev.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the alarm engine needs to ring one alarm. Kept in device-protected storage, so
 * alarms are rescheduled and ring after a reboot before the phone is unlocked.
 */
@Serializable
data class RingSpec(
    val alarmId: String,
    val revision: Int,
    val label: String,
    val time: String,
    val repeatDays: List<String>,
    val enabled: Boolean,
    /** Local date (ISO) of the occurrence skipped by `skip_next`. */
    val skippedDate: String? = null,
    /** Effective snooze minutes (alarm's own or the server default). */
    val snoozeMinutes: Int,
    val soundUri: String? = null,
    val volumeRamp: Boolean = false,
    /** Vibrate while ringing (set on this phone). */
    val vibrate: Boolean = true,
    /** HA time zone; null when unknown (then the phone's zone is used). */
    val haTimeZone: String? = null,
    /** Scheduled time (epoch ms) of an occurrence the user already dismissed early elsewhere. */
    val dismissedAt: Long? = null,
)

/** Per alarm: when it last rang (and the revision then), so it does not ring twice. */
@Serializable
data class FiredInfo(val lastRungAt: Long, val revision: Int, val oneTime: Boolean)

/** One scheduled ring. [occurrenceId] is null while away (no reports then). */
@Serializable
data class PlannedRing(
    val alarmId: String,
    val ringAt: Long,
    val occurrenceId: String?,
    val label: String,
    val time: String,
    val snoozeMinutes: Int,
    val soundUri: String? = null,
    val volumeRamp: Boolean = false,
    val snooze: Boolean = false,
    val vibrate: Boolean = true,
)

object RingPlanner {
    /**
     * Next ring of every enabled alarm, at its wall-clock time in the phone's time zone
     * (AFR-RNG-08). At home this equals the HA occurrence, whose id is used for reports.
     */
    fun plan(specs: List<RingSpec>, fired: Map<String, FiredInfo>, phoneZone: ZoneId, now: Instant): List<PlannedRing> =
        specs.mapNotNull { spec -> next(spec, fired[spec.alarmId], phoneZone, now) }.sortedBy { it.ringAt }

    fun next(spec: RingSpec, fired: FiredInfo?, phoneZone: ZoneId, now: Instant): PlannedRing? {
        if (!spec.enabled || !Schedule.isValidTime(spec.time)) return null
        val days = Weekday.parse(spec.repeatDays)
        // A one-time alarm rings once per revision; the server disables it after it fired.
        if (days.isEmpty() && fired != null && fired.oneTime && fired.revision == spec.revision) return null
        var after = now
        if (fired != null) {
            val last = Instant.ofEpochMilli(fired.lastRungAt)
            if (last.isAfter(after)) after = last
        }
        val skipped = spec.skippedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        var ringAt = Schedule.nextRing(spec.time, days, skipped, phoneZone, after) ?: return null
        val away = ReportPolicy.isAway(spec.haTimeZone, phoneZone.id)
        if (spec.dismissedAt != null && isDismissed(spec, ringAt, phoneZone, away)) {
            // Already dismissed early (panel or another phone): ring the occurrence after it.
            if (days.isEmpty()) return null
            ringAt = Schedule.nextRing(spec.time, days, skipped, phoneZone, ringAt) ?: return null
        }
        return PlannedRing(
            alarmId = spec.alarmId,
            ringAt = ringAt.toEpochMilli(),
            occurrenceId = if (away) null else Schedule.occurrenceId(spec.alarmId, ringAt),
            label = spec.label,
            time = spec.time,
            snoozeMinutes = spec.snoozeMinutes,
            soundUri = spec.soundUri,
            volumeRamp = spec.volumeRamp,
            vibrate = spec.vibrate,
        )
    }

    /** At home the ring is the HA occurrence itself; away, the same local date counts. */
    private fun isDismissed(spec: RingSpec, ringAt: Instant, phoneZone: ZoneId, away: Boolean): Boolean {
        val dismissed = Instant.ofEpochMilli(spec.dismissedAt ?: return false)
        if (dismissed == ringAt) return true
        if (!away) return false
        val haZone = runCatching { ZoneId.of(spec.haTimeZone) }.getOrNull() ?: return false
        return dismissed.atZone(haZone).toLocalDate() == ringAt.atZone(phoneZone).toLocalDate()
    }

    /** Scheduled rings that passed without ringing (phone off), for the missed-alarm message. */
    fun missed(scheduled: List<PlannedRing>, now: Instant, toleranceMs: Long = 60_000): List<PlannedRing> =
        scheduled.filter { it.ringAt + toleranceMs < now.toEpochMilli() }
}
