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

/** The Bedroom widget: night lamp, heating (thermostat), room temperature and a few status rows. */
data class HomeInfo(val lamp: HaState?, val heating: HaState?, val temperature: HaState?, val rows: List<HaState>)

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
        if (NightWidget.CALENDAR in s.nightWidgets) attempt { _agenda.value = s.calendarEntities.takeIf { it.isNotEmpty() }?.let { readAgenda(it, date, zone) } }
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

    /** Sets the night lamp to [percent] (0 = off; a switch goes on above 0); the widget shows the new state right away. */
    suspend fun setLamp(entityId: String, percent: Int): Problem? = try {
        val domain = entityId.substringBefore('.')
        when {
            percent <= 0 -> app.ha.call(domain, "turn_off", entityId)
            domain == "light" -> app.ha.call(domain, "turn_on", entityId, mapOf("brightness_pct" to percent))
            else -> app.ha.call(domain, "turn_on", entityId)
        }
        reread(entityId)
        null
    } catch (e: Exception) {
        problemOf(e)
    }

    /** Sets the thermostat's target temperature. */
    suspend fun setHeating(entityId: String, target: Double): Problem? = try {
        app.ha.call("climate", "set_temperature", entityId, mapOf("temperature" to target))
        reread(entityId)
        null
    } catch (e: Exception) {
        problemOf(e)
    }

    /**
     * "All lights off": every light that is on, and the night lamp when it is a switch. Returns
     * what was on, for the undo.
     */
    suspend fun allLightsOff(lamp: String?): Result<List<String>> = runCatching {
        val on = app.ha.states().filter { it.state == "on" && (it.domain == "light" || it.entityId == lamp) }.map { it.entityId }
        switch(on, false)
        lamp?.let { reread(it) }
        on
    }

    /** Turns [entityIds] back on (the undo of [allLightsOff]); lights come back at their last brightness. */
    suspend fun turnOn(entityIds: List<String>, lamp: String?): Problem? = try {
        switch(entityIds, true)
        lamp?.let { reread(it) }
        null
    } catch (e: Exception) {
        problemOf(e)
    }

    private suspend fun switch(entityIds: List<String>, on: Boolean) {
        entityIds.groupBy { it.substringBefore('.') }.forEach { (domain, ids) ->
            app.ha.call(domain, if (on) "turn_on" else "turn_off", ids)
        }
    }

    /** Reads one of the Bedroom widget's entities again after changing it. */
    private suspend fun reread(entityId: String) {
        val updated = app.ha.state(entityId) ?: return
        _home.value = _home.value?.let { h ->
            h.copy(
                lamp = if (h.lamp?.entityId == entityId) updated else h.lamp,
                heating = if (h.heating?.entityId == entityId) updated else h.heating,
            )
        }
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
        if (!s.hasBedroom) return null
        return HomeInfo(
            lamp = s.nightLightEntity?.let { app.ha.state(it) },
            heating = s.heatingEntity?.let { app.ha.state(it) },
            temperature = s.roomTemperatureEntity?.let { app.ha.state(it) },
            rows = s.homeEntities.mapNotNull { app.ha.state(it) },
        )
    }

    /** Events of every chosen calendar together; a calendar that fails is left out unless they all fail. */
    private suspend fun readAgenda(entityIds: List<String>, date: LocalDate, zone: ZoneId): AgendaInfo {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val results = entityIds.map { id -> runCatching { app.ha.events(id, start, end) } }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val events = results.flatMap { it.getOrDefault(emptyList()) }
            .filter { e -> e.startsAt?.let { !it.isBefore(start) && it.isBefore(end) } == true }
            .sortedBy { it.startsAt }
        return AgendaInfo(date, events)
    }

    private fun instant(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}
