package alarmclock.vanbaelen.dev

import alarmclock.vanbaelen.dev.alarm.AlarmEngine
import alarmclock.vanbaelen.dev.alarm.Notifications
import alarmclock.vanbaelen.dev.data.ApiClient
import alarmclock.vanbaelen.dev.data.AppDatabase
import alarmclock.vanbaelen.dev.data.AppPrefs
import alarmclock.vanbaelen.dev.data.AuthManager
import alarmclock.vanbaelen.dev.data.HomeAssistant
import alarmclock.vanbaelen.dev.data.NightData
import alarmclock.vanbaelen.dev.data.Repository
import alarmclock.vanbaelen.dev.data.SecureStore
import alarmclock.vanbaelen.dev.data.Syncer
import alarmclock.vanbaelen.dev.data.createHttpClient
import alarmclock.vanbaelen.dev.update.Updater
import alarmclock.vanbaelen.dev.work.SyncWorker
import android.app.Application
import android.content.Context
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application with a small service locator. Everything that touches credential-protected
 * storage (preferences, tokens, Room) is created lazily, because after a reboot the process can
 * start before the first unlock to ring an alarm (only [engine] works then).
 */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine by lazy { AlarmEngine(this) }
    val prefs by lazy { AppPrefs(this) }
    private val secure by lazy { SecureStore(this) }
    private val http by lazy { createHttpClient(prefs) }
    val auth by lazy { AuthManager(http, prefs, secure) }
    val api by lazy { ApiClient(http, auth, prefs) }
    val ha by lazy { HomeAssistant(http, auth, prefs) }
    val night by lazy { NightData(this) }
    val db by lazy { AppDatabase.create(this) }
    val repository by lazy { Repository(this) }
    val syncer by lazy { Syncer(this) }
    val updater by lazy { Updater(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        if (isUserUnlocked()) {
            scope.launch {
                repository.importEngineState()
                if (prefs.account.value.loggedIn) SyncWorker.schedulePeriodic(this@App)
            }
        }
    }

    fun isUserUnlocked(): Boolean = getSystemService(UserManager::class.java).isUserUnlocked
}

val Context.app: App get() = applicationContext as App
