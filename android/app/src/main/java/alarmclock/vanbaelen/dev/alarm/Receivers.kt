package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.ReportType
import alarmclock.vanbaelen.dev.work.SyncWorker
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** Fired by AlarmManager: start ringing (foreground service) and plan the next ring. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = context.app.engine
        when (intent.action) {
            ACTION_RING -> {
                val ring = AlarmEngine.ringFrom(intent) ?: return
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, RingingService::class.java).setAction(RingingService.ACTION_START)
                            .putExtra(AlarmEngine.EXTRA_RING, AlarmEngine.ringExtra(ring)),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start the ringing service", e)
                }
                engine.onRingFired(ring)
            }
            ACTION_DISMISS_SNOOZED -> {
                val ring = AlarmEngine.ringFrom(intent) ?: return
                engine.cancelSnooze(ring.alarmId)
                Notifications.cancel(context, Notifications.snoozedId(ring.alarmId))
                ReportQueue.enqueue(context, ring, ReportType.DISMISSED)
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_RING = "alarmclock.vanbaelen.dev.RING"
        const val ACTION_DISMISS_SNOOZED = "alarmclock.vanbaelen.dev.DISMISS_SNOOZED"
    }
}

/** Reboot, clock and time zone changes and app updates: reschedule every alarm (ATR-RNG-04). */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED -> app.engine.reschedule()
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED -> {
                app.engine.reschedule()
                if (!app.isUserUnlocked()) return
                val pending = goAsync()
                app.scope.launch {
                    try {
                        app.repository.importEngineState()
                        if (app.prefs.account.value.loggedIn) {
                            SyncWorker.schedulePeriodic(app)
                            // A new time zone may mean away / back home: tell the server (AFR-RNG-08).
                            if (intent.action != Intent.ACTION_BOOT_COMPLETED) SyncWorker.syncSoon(app)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
