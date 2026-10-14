package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.MainActivity
import alarmclock.vanbaelen.dev.core.ApiJson
import alarmclock.vanbaelen.dev.core.FiredInfo
import alarmclock.vanbaelen.dev.core.PlannedRing
import alarmclock.vanbaelen.dev.core.RingPlanner
import alarmclock.vanbaelen.dev.core.RingSpec
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

/**
 * Schedules the next ring of every enabled alarm with AlarmManager.setAlarmClock (ATR-RNG-01).
 * Works only from [EngineStore] (never the network, ATR-RNG-05), so it also runs before the
 * first unlock after a reboot.
 */
class AlarmEngine(context: Context) {
    private val ctx = context.applicationContext
    val store = EngineStore(ctx)
    private val alarmManager = ctx.getSystemService(AlarmManager::class.java)
    private val _scheduled = MutableStateFlow(store.scheduled)
    private val _ringing = MutableStateFlow(store.ringing)

    /** Every scheduled ring (normal and snoozed), soonest first. */
    val scheduled: StateFlow<List<PlannedRing>> = _scheduled.asStateFlow()

    /** The ring that is sounding now, if any. */
    val ringing: StateFlow<PlannedRing?> = _ringing.asStateFlow()

    val haTimeZone: String? get() = store.haTimeZone

    /** New alarm data from the local database (after sync, edits or settings changes). */
    @Synchronized
    fun updateSpecs(specs: List<RingSpec>, haTimeZone: String?) {
        store.specs = specs
        store.haTimeZone = haTimeZone
        val ids = specs.map { it.alarmId }.toSet()
        store.fired = store.fired.filterKeys { it in ids }
        store.skips = store.skips.filterKeys { it in ids }
        reschedule()
    }

    /** Recomputes every ring; also used after reboot, time and time zone changes (ATR-RNG-04). */
    @Synchronized
    fun reschedule(now: Instant = Instant.now()) {
        val previous = store.scheduled
        val missed = RingPlanner.missed(previous.filter { !it.snooze }, now)
        if (missed.isNotEmpty()) {
            Log.i(TAG, "Missed ${missed.size} alarm(s) while the phone was off")
            store.missed = store.missed + missed
            // A missed one-time alarm must not ring a day later.
            val fired = store.fired.toMutableMap()
            missed.forEach { ring -> markFired(fired, ring) }
            store.fired = fired
        }
        val specs = store.specs
        val ids = specs.map { it.alarmId }.toSet()
        val plan = RingPlanner.plan(specs, store.fired, ZoneId.systemDefault(), now)
        val snoozes = previous.filter { it.snooze && it.alarmId in ids && it.ringAt > now.toEpochMilli() - 60_000 }
        previous.forEach(::cancel)
        val all = (plan + snoozes).sortedBy { it.ringAt }
        all.forEach(::set)
        store.scheduled = all
        _scheduled.value = all
    }

    /** Called when a ring fires: remember it so it does not ring twice, then plan the next one. */
    @Synchronized
    fun onRingFired(ring: PlannedRing) {
        store.scheduled = store.scheduled.filterNot { it.alarmId == ring.alarmId && it.snooze == ring.snooze }
        if (!ring.snooze) {
            val fired = store.fired.toMutableMap()
            markFired(fired, ring)
            store.fired = fired
        }
        reschedule()
    }

    private fun markFired(fired: MutableMap<String, FiredInfo>, ring: PlannedRing) {
        val spec = store.specs.firstOrNull { it.alarmId == ring.alarmId } ?: return
        fired[ring.alarmId] = FiredInfo(ring.ringAt, spec.revision, oneTime = spec.repeatDays.isEmpty())
    }

    /** Early dismiss from the alarm list (up to 60 min before): this occurrence will not ring. */
    @Synchronized
    fun dismissEarly(ring: PlannedRing) {
        val fired = store.fired.toMutableMap()
        markFired(fired, ring)
        store.fired = fired
        reschedule()
    }

    @Synchronized
    fun snooze(ring: PlannedRing, minutes: Int, now: Instant = Instant.now()): PlannedRing {
        val snoozed = ring.copy(ringAt = now.toEpochMilli() + minutes * 60_000L, snooze = true)
        cancelSnooze(ring.alarmId)
        set(snoozed)
        val all = (store.scheduled + snoozed).sortedBy { it.ringAt }
        store.scheduled = all
        _scheduled.value = all
        return snoozed
    }

    @Synchronized
    fun cancelSnooze(alarmId: String) {
        val (snoozes, rest) = store.scheduled.partition { it.snooze && it.alarmId == alarmId }
        snoozes.forEach(::cancel)
        store.scheduled = rest
        _scheduled.value = rest
    }

    fun setRinging(ring: PlannedRing?) {
        store.ringing = ring
        _ringing.value = ring
    }

    /** Missed rings detected before the database was available; cleared when taken. */
    @Synchronized
    fun takeMissed(): List<PlannedRing> = store.missed.also { if (it.isNotEmpty()) store.missed = emptyList() }

    @Synchronized
    fun takeLockedReports(): List<LockedReport> = store.lockedReports.also { if (it.isNotEmpty()) store.lockedReports = emptyList() }

    @Synchronized
    fun addLockedReport(report: LockedReport) {
        store.lockedReports = store.lockedReports + report
    }

    /** Logout: cancel every alarm and forget everything. */
    @Synchronized
    fun clearAll() {
        store.scheduled.forEach(::cancel)
        store.clear()
        _scheduled.value = emptyList()
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun set(ring: PlannedRing) {
        val operation = ringIntent(ring)
        try {
            if (canScheduleExact()) {
                val show = PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(ring.ringAt, show), operation)
            } else {
                // Without exact-alarm access the health check warns; this still rings, maybe late.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ring.ringAt, operation)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm not allowed", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ring.ringAt, operation)
        }
    }

    private fun cancel(ring: PlannedRing) {
        alarmManager.cancel(ringIntent(ring))
    }

    private fun ringIntent(ring: PlannedRing): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_RING)
            .setData("alarmactions-ring:${ring.alarmId}/${if (ring.snooze) "snooze" else "ring"}".toUri())
            .putExtra(EXTRA_RING, ApiJson.encodeToString(PlannedRing.serializer(), ring))
        return PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val TAG = "AlarmEngine"
        const val EXTRA_RING = "ring"

        fun ringFrom(intent: Intent?): PlannedRing? = intent?.getStringExtra(EXTRA_RING)?.let {
            runCatching { ApiJson.decodeFromString(PlannedRing.serializer(), it) }.getOrNull()
        }

        fun ringExtra(ring: PlannedRing): String = ApiJson.encodeToString(PlannedRing.serializer(), ring)
    }
}
