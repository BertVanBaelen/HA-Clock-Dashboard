package alarmclock.vanbaelen.dev.data

import alarmclock.vanbaelen.dev.core.ClockSize
import alarmclock.vanbaelen.dev.core.HaJson
import alarmclock.vanbaelen.dev.core.NightBrightness
import alarmclock.vanbaelen.dev.core.NightWidget
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    /** Night display: widgets shown on tap, the brightness, and the Home Assistant entities they read. */
    val nightWidgets: Set<NightWidget> = NightWidget.DEFAULT,
    val nightBrightness: NightBrightness = NightBrightness.NORMAL,
    val clockSize: ClockSize = ClockSize.XL,
    /** Only a small alarm sign stays lit; a tap shows the clock for a few seconds. */
    val screenOffUntilTap: Boolean = false,
    val weatherEntity: String? = null,
    val calendarEntities: List<String> = emptyList(),
    val roomTemperatureEntity: String? = null,
    val heatingEntity: String? = null,
    val nightLightEntity: String? = null,
    val homeEntities: List<String> = emptyList(),
    /** The bedtime checklist: a to-do list, and per item uid the automation or script that ticking it runs. */
    val checklistEntity: String? = null,
    val checklistActions: Map<String, String> = emptyMap(),
    val checklistShow: Boolean = true,
    val checklistReset: Boolean = true,
    val checklistSync: Boolean = true,
) {
    /** The Bedroom widget has at least one entity to show. */
    val hasBedroom: Boolean
        get() = nightLightEntity != null || heatingEntity != null || roomTemperatureEntity != null || homeEntities.isNotEmpty()
}

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
        nightWidgets = prefs.getString("night_widgets", null)?.let { NightWidget.parse(list(it)) } ?: NightWidget.DEFAULT,
        // Before the three levels there was only the Dimmer.
        nightBrightness = NightBrightness.parse(prefs.getString("night_brightness", null))
            ?: if (prefs.getBoolean("night_dimmer", false)) NightBrightness.DIM else NightBrightness.NORMAL,
        clockSize = ClockSize.parse(prefs.getString("night_clock_size", null)) ?: ClockSize.XL,
        screenOffUntilTap = prefs.getBoolean("night_screen_off", false),
        weatherEntity = prefs.getString("night_weather", null),
        calendarEntities = list(prefs.getString("night_calendar", null)),
        roomTemperatureEntity = prefs.getString("night_room_temperature", null),
        heatingEntity = prefs.getString("night_heating", null),
        nightLightEntity = prefs.getString("night_light", null),
        homeEntities = list(prefs.getString("night_home", null)),
        checklistEntity = prefs.getString("checklist_entity", null),
        checklistActions = prefs.getString("checklist_actions", null)
            ?.let { runCatching { HaJson.decodeFromString(ACTIONS, it) }.getOrNull() }
            .orEmpty(),
        checklistShow = prefs.getBoolean("checklist_show", true),
        checklistReset = prefs.getBoolean("checklist_reset", true),
        checklistSync = prefs.getBoolean("checklist_sync", true),
    )

    /** When the checklist was last unticked (or first read); null before that. */
    var checklistResetAt: Long?
        get() = prefs.getLong("checklist_reset_at", 0L).takeIf { it > 0 }
        set(value) {
            prefs.edit().putLong("checklist_reset_at", value ?: 0L).commit()
        }

    /** Items ticked on the night display while ticks stay on this phone (uids). */
    var checklistTicks: Set<String>
        get() = prefs.getStringSet("checklist_ticks", null).orEmpty()
        set(value) {
            prefs.edit().putStringSet("checklist_ticks", value).commit()
        }

    private fun list(value: String?): List<String> = value?.split(',')?.filter { it.isNotBlank() }.orEmpty()

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
            .putString("night_widgets", s.nightWidgets.joinToString(",") { it.id })
            .remove("night_dimmer")
            .putString("night_brightness", s.nightBrightness.id)
            .putString("night_clock_size", s.clockSize.id)
            .putBoolean("night_screen_off", s.screenOffUntilTap)
            .putString("night_weather", s.weatherEntity)
            .putString("night_calendar", s.calendarEntities.joinToString(","))
            .putString("night_room_temperature", s.roomTemperatureEntity)
            .putString("night_heating", s.heatingEntity)
            .putString("night_light", s.nightLightEntity)
            .putString("night_home", s.homeEntities.joinToString(","))
            .putString("checklist_entity", s.checklistEntity)
            .putString("checklist_actions", HaJson.encodeToString(ACTIONS, s.checklistActions))
            .putBoolean("checklist_show", s.checklistShow)
            .putBoolean("checklist_reset", s.checklistReset)
            .putBoolean("checklist_sync", s.checklistSync)
            .commit()
        _settings.value = s
    }

    /** Logout: forget the account but keep the client id and the URLs for an easy new login. */
    fun clearAccount() {
        updateAccount { Account(haUrl = it.haUrl, apiUrl = it.apiUrl) }
        oauthState = null
    }

    private companion object {
        val ACTIONS = MapSerializer(String.serializer(), String.serializer())
    }
}
