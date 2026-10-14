package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.PlannedRing
import alarmclock.vanbaelen.dev.core.ReportPolicy
import alarmclock.vanbaelen.dev.core.ReportType
import alarmclock.vanbaelen.dev.core.Schedule
import alarmclock.vanbaelen.dev.data.PendingReportRow
import alarmclock.vanbaelen.dev.work.ReportWorker
import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Records a dismiss / snooze / timeout for the server (AFR-SYN-03). Nothing is recorded while
 * away (AFR-RNG-08) or for a ring without a server occurrence. Works before the first unlock too.
 */
object ReportQueue {
    private const val TAG = "ReportQueue"

    fun enqueue(context: Context, ring: PlannedRing, type: ReportType, occurredAt: Instant = Instant.now()) {
        val app = context.app
        val occurrenceId = ring.occurrenceId
        val away = ReportPolicy.isAway(app.engine.haTimeZone, ZoneId.systemDefault().id)
        if (!ReportPolicy.shouldQueue(away, occurrenceId) || occurrenceId == null) return
        val scheduledAt = Schedule.occurrenceTime(occurrenceId) ?: Instant.ofEpochMilli(ring.ringAt)
        if (!ReportPolicy.plausible(type, scheduledAt, occurredAt, Instant.now())) {
            Log.i(TAG, "Not reporting ${type.api}: implausible time")
            return
        }
        val report = LockedReport(ring.alarmId, occurrenceId, type.api, occurredAt.toEpochMilli(), scheduledAt.toEpochMilli())
        if (!app.isUserUnlocked()) {
            app.engine.addLockedReport(report)
            return
        }
        app.scope.launch {
            app.db.dao().insertReport(
                PendingReportRow(alarmId = report.alarmId, occurrenceId = report.occurrenceId, type = report.type, occurredAt = report.occurredAt, scheduledAt = report.scheduledAt),
            )
            ReportWorker.enqueue(app)
        }
    }
}
