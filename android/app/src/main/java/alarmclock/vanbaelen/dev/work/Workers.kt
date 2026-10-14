package alarmclock.vanbaelen.dev.work

import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.data.SyncProblem
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Periodic sync every 15 minutes and one-time "sync soon" / retry after reconnect (AFR-SYN-01). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.isUserUnlocked() || !app.prefs.account.value.loggedIn) return Result.success()
        return when (app.syncer.sync()) {
            SyncProblem.OFFLINE, SyncProblem.HA_OFFLINE -> if (runAttemptCount < 5) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "sync-periodic"
        private const val ONCE = "sync-once"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Sync once a network is available; [delayed] waits a little (retry after a failure). */
        fun syncSoon(context: Context, delayed: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply { if (delayed) setInitialDelay(1, TimeUnit.MINUTES) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, if (delayed) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(ONCE)
        }
    }
}

/** Sends queued reports with exponential backoff (AFR-SYN-03). */
class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.isUserUnlocked()) return Result.retry()
        return if (app.syncer.flushReports()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "reports"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
