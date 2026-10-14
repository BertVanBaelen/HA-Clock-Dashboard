package alarmclock.vanbaelen.dev.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Connection data: the two URLs, the client id and the logged-in user (no secrets). */
data class Account(
    val haUrl: String? = null,
    val apiUrl: String? = null,
    val userName: String? = null,
    val loggedIn: Boolean = false,
    /** Refreshing the token failed: alarms keep ringing, but the user must log in again. */
    val needsLogin: Boolean = false,
) {
    val configured: Boolean get() = haUrl != null && apiUrl != null
}

/** Local settings that never leave the phone. */
data class LocalSettings(
    val defaultSoundUri: String? = null,
    val volumeRamp: Boolean = true,
    val nightAutoStart: Boolean = false,
    val nightStart: String = "22:00",
    val nightEnd: String = "07:00",
    val guideDone: Boolean = false,
)

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(readAccount())
    private val _settings = MutableStateFlow(readSettings())
    val account: StateFlow<Account> = _account.asStateFlow()
    val settings: StateFlow<LocalSettings> = _settings.asStateFlow()

    /** Generated once and kept, also across logins (POST /clients id). */
    val clientId: String
        @Synchronized get() = prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("client_id", it).commit()
        }

    /** The server answered 404 for our client id (taken by another user): start over with a new one. */
    @Synchronized
    fun resetClientId() {
        prefs.edit().remove("client_id").commit()
    }

    var oauthState: String?
        get() = prefs.getString("oauth_state", null)
        set(value) {
            prefs.edit().putString("oauth_state", value).commit()
        }

    private fun readAccount() = Account(
        haUrl = prefs.getString("ha_url", null),
        apiUrl = prefs.getString("api_url", null),
        userName = prefs.getString("user_name", null),
        loggedIn = prefs.getBoolean("logged_in", false),
        needsLogin = prefs.getBoolean("needs_login", false),
    )

    private fun readSettings() = LocalSettings(
        defaultSoundUri = prefs.getString("default_sound", null),
        volumeRamp = prefs.getBoolean("volume_ramp", true),
        nightAutoStart = prefs.getBoolean("night_auto", false),
        nightStart = prefs.getString("night_start", null) ?: "22:00",
        nightEnd = prefs.getString("night_end", null) ?: "07:00",
        guideDone = prefs.getBoolean("guide_done", false),
    )

    @Synchronized
    fun updateAccount(transform: (Account) -> Account) {
        val a = transform(_account.value)
        prefs.edit()
            .putString("ha_url", a.haUrl)
            .putString("api_url", a.apiUrl)
            .putString("user_name", a.userName)
            .putBoolean("logged_in", a.loggedIn)
            .putBoolean("needs_login", a.needsLogin)
            .commit()
        _account.value = a
    }

    @Synchronized
    fun updateSettings(transform: (LocalSettings) -> LocalSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("default_sound", s.defaultSoundUri)
            .putBoolean("volume_ramp", s.volumeRamp)
            .putBoolean("night_auto", s.nightAutoStart)
            .putString("night_start", s.nightStart)
            .putString("night_end", s.nightEnd)
            .putBoolean("guide_done", s.guideDone)
            .commit()
        _settings.value = s
    }

    /** Logout: forget the account but keep the client id and the URLs for an easy new login. */
    fun clearAccount() {
        updateAccount { Account(haUrl = it.haUrl, apiUrl = it.apiUrl) }
        oauthState = null
    }
}
