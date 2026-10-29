package alarmclock.vanbaelen.dev.ui

import alarmclock.vanbaelen.dev.App
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.alarm.ReportQueue
import alarmclock.vanbaelen.dev.core.Alarm
import alarmclock.vanbaelen.dev.core.AppInfo
import alarmclock.vanbaelen.dev.core.EditState
import alarmclock.vanbaelen.dev.core.EffectiveAlarm
import alarmclock.vanbaelen.dev.core.PlannedRing
import alarmclock.vanbaelen.dev.core.ReportType
import alarmclock.vanbaelen.dev.core.SetupLinks
import alarmclock.vanbaelen.dev.core.SetupUrls
import alarmclock.vanbaelen.dev.data.MissedAlarmRow
import alarmclock.vanbaelen.dev.data.PendingEditRow
import alarmclock.vanbaelen.dev.data.SaveResult
import alarmclock.vanbaelen.dev.data.SyncStateRow
import alarmclock.vanbaelen.dev.work.SyncWorker
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the alarm list: the alarm, its next ring on this phone, a running snooze and the phone's own choices. */
data class AlarmItem(
    val effective: EffectiveAlarm,
    val next: PlannedRing?,
    val snoozed: PlannedRing?,
    val vibrate: Boolean = true,
    val sunrise: Int = 0,
) {
    val alarm: Alarm get() = effective.alarm
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val account = app.prefs.account
    val settings = app.prefs.settings
    val ringing = app.engine.ringing
    /** Every scheduled ring (normal and snoozed), soonest first. */
    val scheduled = app.engine.scheduled
    val updateState = app.updater.state

    val items: StateFlow<List<AlarmItem>> = combine(app.repository.alarms, app.engine.scheduled, app.repository.local) { alarms, scheduled, local ->
        alarms.map { e ->
            AlarmItem(
                e,
                scheduled.firstOrNull { it.alarmId == e.alarm.id && !it.snooze },
                scheduled.firstOrNull { it.alarmId == e.alarm.id && it.snooze },
                local[e.alarm.id]?.vibrate ?: true,
                local[e.alarm.id]?.sunrise ?: 0,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncState: StateFlow<SyncStateRow> = app.repository.syncState.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStateRow())
    val missed: StateFlow<List<MissedAlarmRow>> = app.repository.missed.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val problems: StateFlow<List<PendingEditRow>> = app.repository.edits
        .map { list -> list.filter { it.state != EditState.PENDING.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingEdits: StateFlow<Int> = app.repository.edits.map { list -> list.count { it.state == EditState.PENDING.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val nextRing: StateFlow<PlannedRing?> = app.engine.scheduled.map { it.minByOrNull { r -> r.ringAt } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncing = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Setup values from a deep link / QR code, shown on the setup screen. */
    val pendingSetup = MutableStateFlow<SetupUrls?>(null)

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun sync() {
        if (!account.value.loggedIn || syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            try {
                app.syncer.sync()
            } finally {
                syncing.value = false
            }
        }
    }

    private fun report(result: SaveResult, ok: String) {
        message(
            when (result) {
                is SaveResult.Saved -> ok
                is SaveResult.Queued -> app.getString(R.string.saved_offline)
                is SaveResult.Failed -> Format.problem(app, result.problem, result.message)
            },
        )
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) = viewModelScope.launch {
        val time = Format.time(app, alarm.time)
        report(app.repository.setEnabled(alarm, enabled), app.getString(if (enabled) R.string.alarm_on else R.string.alarm_off, time))
    }

    fun setSkip(alarm: Alarm, skip: Boolean) = viewModelScope.launch {
        report(app.repository.setSkip(alarm, skip), app.getString(if (skip) R.string.skipped else R.string.skip_undone))
    }

    /** Dismiss an alarm that rings within the hour, from the list (AFR-RNG-06). */
    fun dismissEarly(ring: PlannedRing) {
        ReportQueue.enqueue(app, ring, ReportType.DISMISSED)
        app.engine.dismissEarly(ring)
        message(app.getString(R.string.dismissed_early))
    }

    fun clearMissed() = viewModelScope.launch { app.repository.clearMissed() }

    fun updateInfo(state: SyncStateRow): AppInfo? = app.updater.availableInfo(state)

    fun installUpdate(info: AppInfo) = viewModelScope.launch { app.updater.install(info) }

    fun resetUpdate() = app.updater.reset()

    fun logout(done: () -> Unit) = viewModelScope.launch {
        app.syncer.logout()
        done()
    }

    fun handleSetupLink(uri: Uri): Boolean {
        val urls = SetupLinks.parse(uri.toString()) ?: return false
        pendingSetup.value = urls
        return true
    }

    /** Saves the URLs and returns the HA login page to open in a Custom Tab. */
    suspend fun startLogin(urls: SetupUrls): Uri {
        val current = account.value
        if (current.loggedIn && (current.haUrl != urls.haUrl || current.apiUrl != urls.apiUrl)) {
            // Another dashboard: the local data belongs to the old one.
            app.syncer.logout()
        }
        app.prefs.updateAccount { it.copy(haUrl = urls.haUrl, apiUrl = urls.apiUrl) }
        return app.auth.authorizeUri(urls.haUrl)
    }

    val loggingIn = MutableStateFlow(false)

    fun completeLogin(uri: Uri, done: (Boolean) -> Unit) = viewModelScope.launch {
        loggingIn.value = true
        val ok = try {
            app.auth.completeLogin(uri)
            SyncWorker.schedulePeriodic(app)
            app.syncer.sync()
            true
        } catch (e: Exception) {
            message(app.getString(R.string.login_failed))
            false
        } finally {
            loggingIn.value = false
        }
        done(ok)
    }

    fun completeGuide() = app.prefs.updateSettings { it.copy(guideDone = true) }
}
