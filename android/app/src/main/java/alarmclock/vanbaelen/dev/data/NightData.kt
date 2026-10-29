package alarmclock.vanbaelen.dev.data

import alarmclock.vanbaelen.dev.App
import alarmclock.vanbaelen.dev.core.CalendarEvent
import alarmclock.vanbaelen.dev.core.Forecast
import alarmclock.vanbaelen.dev.core.HaState
import alarmclock.vanbaelen.dev.core.NightWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** The weather entity now, its forecasts and the sun times (sun.sun), for the weather widget and the good-morning screen. */
data class WeatherInfo(
    val name: String,
    val condition: String,
    val temperature: Double?,
    val apparent: Double?,
    val unit: String?,
    val windSpeed: Double?,
    val windUnit: String?,
    val daily: List<Forecast>,
    val hourly: List<Forecast>,
    val sunrise: Instant?,
    val sunset: Instant?,
)

/** The home widget: room temperature, night light and a few status rows. */
data class HomeInfo(val temperature: HaState?, val nightLight: HaState?, val rows: List<HaState>)

/** Timed events of [date], soonest first. */
data class AgendaInfo(val date: LocalDate, val events: List<CalendarEvent>)

/**
 * Widget data of the night display, read from Home Assistant (never needed for ringing). Kept in
 * memory, refreshed while the night display or the good-morning screen is shown.
 */
class NightData(private val app: App) {
    private val mutex = Mutex()
    private val _weather = MutableStateFlow<WeatherInfo?>(null)
    private val _home = MutableStateFlow<HomeInfo?>(null)
    private val _agenda = MutableStateFlow<AgendaInfo?>(null)
    private val _problem = MutableStateFlow<Problem?>(null)
    val weather: StateFlow<WeatherInfo?> = _weather.asStateFlow()
    val home: StateFlow<HomeInfo?> = _home.asStateFlow()
    val agenda: StateFlow<AgendaInfo?> = _agenda.asStateFlow()
    /** Problem of the last refresh; the widgets keep their last values meanwhile. */
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    /** Reads what the enabled widgets show; [date] is the day they look ahead to. */
    suspend fun refresh(date: LocalDate, zone: ZoneId) = mutex.withLock {
        val s = app.prefs.settings.value
        if (!app.prefs.account.value.loggedIn) return@withLock
        var problem: Problem? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (problem == null) problem = problemOf(e)
            }
        }
        if (NightWidget.WEATHER in s.nightWidgets) attempt { _weather.value = s.weatherEntity?.let { readWeather(it) } }
        if (NightWidget.HOME in s.nightWidgets) attempt { _home.value = readHome(s) }
        if (NightWidget.CALENDAR in s.nightWidgets) attempt { _agenda.value = s.calendarEntity?.let { readAgenda(it, date, zone) } }
        _problem.value = problem
    }

    /** Only the weather, for the good-morning screen. */
    suspend fun refreshWeather(): Problem? = mutex.withLock {
        val entity = app.prefs.settings.value.weatherEntity ?: return@withLock null
        try {
            _weather.value = readWeather(entity)
            null
        } catch (e: Exception) {
            problemOf(e)
        }
    }

    /** Switches the night light; the widget shows the new state right away. */
    suspend fun setSwitch(entityId: String, on: Boolean): Problem? = try {
        app.ha.call(entityId.substringBefore('.'), if (on) "turn_on" else "turn_off", entityId)
        val updated = app.ha.state(entityId)
        _home.value = _home.value?.let { h -> if (updated != null && h.nightLight?.entityId == entityId) h.copy(nightLight = updated) else h }
        null
    } catch (e: Exception) {
        problemOf(e)
    }

    /** Every entity, for choosing the widgets' entities in Settings. */
    suspend fun entities(): List<HaState> = app.ha.states().sortedBy { it.name.lowercase() }

    private suspend fun readWeather(entityId: String): WeatherInfo? {
        val state = app.ha.state(entityId) ?: return null
        val sun = runCatching { app.ha.state("sun.sun") }.getOrNull()
        return WeatherInfo(
            name = state.name,
            condition = state.state,
            temperature = state.number("temperature"),
            apparent = state.number("apparent_temperature"),
            unit = state.text("temperature_unit"),
            windSpeed = state.number("wind_speed"),
            windUnit = state.text("wind_speed_unit"),
            daily = runCatching { app.ha.forecast(entityId, "daily") }.getOrDefault(emptyList()),
            hourly = runCatching { app.ha.forecast(entityId, "hourly") }.getOrDefault(emptyList()),
            sunrise = sun?.text("next_rising")?.let { instant(it) },
            sunset = sun?.text("next_setting")?.let { instant(it) },
        )
    }

    private suspend fun readHome(s: LocalSettings): HomeInfo? {
        if (s.roomTemperatureEntity == null && s.nightLightEntity == null && s.homeEntities.isEmpty()) return null
        return HomeInfo(
            temperature = s.roomTemperatureEntity?.let { app.ha.state(it) },
            nightLight = s.nightLightEntity?.let { app.ha.state(it) },
            rows = s.homeEntities.mapNotNull { app.ha.state(it) },
        )
    }

    private suspend fun readAgenda(entityId: String, date: LocalDate, zone: ZoneId): AgendaInfo {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val events = app.ha.events(entityId, start, end)
            .filter { e -> e.startsAt?.let { !it.isBefore(start) && it.isBefore(end) } == true }
            .sortedBy { it.startsAt }
        return AgendaInfo(date, events)
    }

    private fun instant(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}
