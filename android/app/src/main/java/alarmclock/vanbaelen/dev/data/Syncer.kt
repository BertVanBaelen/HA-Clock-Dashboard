package alarmclock.vanbaelen.dev.data

import alarmclock.vanbaelen.dev.App
import alarmclock.vanbaelen.dev.BuildConfig
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.alarm.Notifications
import alarmclock.vanbaelen.dev.core.ApiCompat
import alarmclock.vanbaelen.dev.core.ApiInfo
import alarmclock.vanbaelen.dev.core.ApiJson
import alarmclock.vanbaelen.dev.core.AppInfo
import alarmclock.vanbaelen.dev.core.AppVersion
import alarmclock.vanbaelen.dev.core.ClientRegistration
import alarmclock.vanbaelen.dev.core.Compatibility
import alarmclock.vanbaelen.dev.core.Me
import alarmclock.vanbaelen.dev.core.ReportBody
import alarmclock.vanbaelen.dev.core.ReportOutcome
import alarmclock.vanbaelen.dev.core.ReportPolicy
import alarmclock.vanbaelen.dev.core.SyncMerge
import alarmclock.vanbaelen.dev.core.SyncResponse
import alarmclock.vanbaelen.dev.work.ReportWorker
import alarmclock.vanbaelen.dev.work.SyncWorker
import android.os.Build
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** Problem codes stored in [SyncStateRow.problem]. */
object SyncProblem {
    const val OFFLINE = "offline"
    const val LOGIN = "login"
    const val DASHBOARD_OLD = "dashboard_old"
    const val APP_OLD = "app_old"
    const val HA_OFFLINE = "ha_offline"
    const val ERROR = "error"
}

/**
 * Sync with the dashboard (AFR-SYN-01): register the phone, fetch changes since the last cursor,
 * merge, send queued edits and reports, reschedule alarms and check for an app update.
 */
class Syncer(private val app: App) {
    private val mutex = Mutex()
    private val dao get() = app.db.dao()

    suspend fun sync(): String? = mutex.withLock {
        val account = app.prefs.account.value
        if (!account.loggedIn) return@withLock SyncProblem.LOGIN
        val start = dao.syncState() ?: SyncStateRow()
        dao.saveSyncState(start.copy(lastAttemptAt = System.currentTimeMillis()))
        val problem = try {
            runSync(start)
        } catch (e: LoginRequiredException) {
            SyncProblem.LOGIN
        } catch (e: ApiException) {
            Log.w(TAG, "Sync failed: HTTP ${e.status}")
            if (e.status == 503) SyncProblem.HA_OFFLINE else SyncProblem.ERROR
        } catch (e: IOException) {
            Log.w(TAG, "Sync failed: ${e.javaClass.simpleName}")
            SyncProblem.OFFLINE
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            SyncProblem.ERROR
        }
        val now = dao.syncState() ?: SyncStateRow()
        dao.saveSyncState(now.copy(problem = problem))
        when (problem) {
            SyncProblem.LOGIN -> Notifications.status(app, Notifications.ID_LOGIN, app.getString(R.string.login_again_title), app.getString(R.string.login_again_text))
            // Retried by WorkManager as soon as the network is back.
            SyncProblem.OFFLINE, SyncProblem.HA_OFFLINE -> SyncWorker.syncSoon(app, delayed = true)
        }
        // Rings always follow the local data, also when the sync failed.
        app.repository.rebuildEngine()
        problem
    }

    private suspend fun runSync(start: SyncStateRow): String? {
        val info = ApiJson.decodeFromString(ApiInfo.serializer(), app.api.call("GET", "info"))
        when (ApiCompat.check(info)) {
            Compatibility.DASHBOARD_TOO_OLD -> return SyncProblem.DASHBOARD_OLD
            Compatibility.APP_TOO_OLD -> {
                checkUpdate()
                return SyncProblem.APP_OLD
            }
            Compatibility.OK -> Unit
        }

        // 404: the id belongs to another HA user; start over with a new id (retried once).
        if (register(start.changeSeq) == 404) {
            app.prefs.resetClientId()
            register(start.changeSeq)
        }

        val response = ApiJson.decodeFromString(SyncResponse.serializer(), app.api.call("GET", "sync?since=${start.changeSeq}"))
        applySync(response)

        runCatching { ApiJson.decodeFromString(Me.serializer(), app.api.call("GET", "me")) }.getOrNull()?.let { me ->
            app.prefs.updateAccount { it.copy(userName = me.name.ifBlank { it.userName }) }
        }

        val editsDone = app.repository.flushEdits()
        val reportsDone = flushReports()
        if (!editsDone || !reportsDone) SyncWorker.syncSoon(app, delayed = true)
        // Keeps the script list for the editor fresh, so it also works offline.
        app.repository.refreshScripts()
        runCatching { checkUpdate() }
        Notifications.cancel(app, Notifications.ID_LOGIN)
        return null
    }

    /** POST /clients; returns the HTTP status (other errors are thrown). */
    private suspend fun register(changeSeq: Long): Int {
        val body = ClientRegistration(
            id = app.prefs.clientId,
            name = (Build.MODEL ?: "Android").take(100).ifBlank { "Android" },
            appVersion = BuildConfig.VERSION_NAME.take(40),
            timeZone = ZoneId.systemDefault().id,
            lastSyncSeq = changeSeq,
        )
        val (status, _) = app.api.status("POST", "clients", ApiJson.encodeToString(ClientRegistration.serializer(), body))
        if (status == 503 || status >= 500) throw ApiException(status, null)
        return status
    }

    private suspend fun applySync(response: SyncResponse) {
        val local = dao.alarms().associate { it.id to it.changeSeq }
        val plan = SyncMerge.plan(local, response)
        if (plan.deletes.isNotEmpty()) dao.deleteAlarms(plan.deletes)
        if (plan.upserts.isNotEmpty()) dao.upsertAlarms(plan.upserts.map { it.toRow() })
        val state = dao.syncState() ?: SyncStateRow()
        dao.saveSyncState(
            state.copy(
                changeSeq = plan.changeSeq,
                lastSyncAt = System.currentTimeMillis(),
                timeZone = response.timeZone,
                defaultSnooze = response.snoozeMinutes,
                dismissTimeout = response.dismissTimeoutMinutes,
                serverAway = response.away,
            ),
        )
    }

    /** GET /app/info; notifies once per new version (AFR-UPD-01). */
    suspend fun checkUpdate(): AppInfo? {
        val info = ApiJson.decodeFromString(AppInfo.serializer(), app.api.call("GET", "app/info"))
        val state = dao.syncState() ?: SyncStateRow()
        val previous = state.appInfoJson?.let { runCatching { ApiJson.decodeFromString(AppInfo.serializer(), it) }.getOrNull() }
        dao.saveSyncState(state.copy(appInfoJson = ApiJson.encodeToString(AppInfo.serializer(), info)))
        if (AppVersion.updateAvailable(info, BuildConfig.VERSION_CODE.toLong()) && previous?.versionCode != info.versionCode) {
            Notifications.status(app, Notifications.ID_UPDATE, app.getString(R.string.update_available), app.getString(R.string.update_available_text, info.version ?: ""))
        }
        return info
    }

    /**
     * Sends queued dismiss / snooze / timeout reports (AFR-SYN-03). Nothing is sent while away.
     * True when the queue is empty afterwards (or nothing more can be done now).
     */
    suspend fun flushReports(): Boolean {
        val state = dao.syncState() ?: SyncStateRow()
        if (ReportPolicy.isAway(state.timeZone, ZoneId.systemDefault().id)) return true
        if (!app.prefs.account.value.loggedIn) return true
        for (report in dao.reports()) {
            val body = ApiJson.encodeToString(ReportBody.serializer(), ReportBody(Instant.ofEpochMilli(report.occurredAt).toString()))
            val status = try {
                app.api.status("POST", "alarms/${report.alarmId}/occurrences/${report.occurrenceId}/${report.type}", body).first
            } catch (e: LoginRequiredException) {
                401
            } catch (e: IOException) {
                null
            }
            when (ReportPolicy.outcome(status)) {
                ReportOutcome.SENT, ReportOutcome.DROP -> dao.deleteReport(report.id)
                ReportOutcome.LOGIN -> return false
                ReportOutcome.RETRY -> {
                    dao.reportAttempted(report.id)
                    return false
                }
            }
        }
        return true
    }

    /** Log out (AFR-SET-03): revoke, cancel alarms, wipe local data. URLs are kept for a new login. */
    suspend fun logout() = mutex.withLock {
        runCatching { app.api.status("DELETE", "clients/${app.prefs.clientId}") }
        app.auth.revoke()
        SyncWorker.cancelAll(app)
        ReportWorker.cancel(app)
        app.engine.clearAll()
        app.db.clearAllTables()
        app.auth.clear()
        app.prefs.clearAccount()
        Notifications.cancel(app, Notifications.ID_LOGIN)
        Notifications.cancel(app, Notifications.ID_UPDATE)
    }

    companion object {
        private const val TAG = "Syncer"
    }
}
