package alarmclock.vanbaelen.dev.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Sunrise of an alarm, set per alarm on this phone (docs/design.md, Night display). With the night
 * display open, the screen brightens from black to warm white over the chosen minutes before the
 * ring; without it, a short sunrise plays on the ringing screen.
 */
object Sunrise {
    /** Choices in the alarm detail: minutes before the alarm, 0 = off. */
    val CHOICES = listOf(0, 10, 20, 30)
    /** The sunrise on the ringing screen when the night display did not show one. */
    const val SHORT_MS = 30_000L
    /** Window brightness of the night display, where every sunrise starts. */
    const val NIGHT_BRIGHTNESS = 0.02f

    // Keyframes of the mockup's wake-up screen (ARGB): background and text colour.
    private val BACKGROUND = listOf(0f to 0xFF000000L, 0.35f to 0xFF2E1607L, 0.55f to 0xFFC77632L, 0.78f to 0xFFFFDDB5L, 1f to 0xFFFFFDF8L)
    private val INK = listOf(0f to 0xFFE6B183L, 0.35f to 0xFFF2C79CL, 0.55f to 0xFFFFF3E6L, 0.62f to 0xFF2A1A0EL, 1f to 0xFF2A1A0EL)

    /** Progress 0..1 of the sunrise before [ring] at [now]; null outside it or without a sunrise. */
    fun progress(ring: PlannedRing, now: Long): Float? {
        if (ring.snooze || ring.sunriseMinutes <= 0) return null
        val start = ring.ringAt - ring.sunriseMinutes * 60_000L
        if (now < start || now >= ring.ringAt) return null
        return (now - start).toFloat() / (ring.ringAt - start)
    }

    /** The sunrise running at [now] (the furthest along when several overlap) and its progress. */
    fun running(rings: List<PlannedRing>, now: Long): Pair<PlannedRing, Float>? =
        rings.mapNotNull { r -> progress(r, now)?.let { r to it } }.maxByOrNull { it.second }

    fun background(p: Float): Long = interpolate(BACKGROUND, p)

    fun ink(p: Float): Long = interpolate(INK, p)

    /** Window brightness: from the night level up to full, slowly at first like daylight. */
    fun brightness(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return NIGHT_BRIGHTNESS + (1f - NIGHT_BRIGHTNESS) * x * x
    }

    /** Colour at [p] between the keyframes [stops], per ARGB channel. */
    internal fun interpolate(stops: List<Pair<Float, Long>>, p: Float): Long {
        val x = p.coerceIn(0f, 1f)
        val i = stops.indexOfLast { it.first <= x }.coerceIn(0, stops.size - 2)
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        val f = if (p1 > p0) ((x - p0) / (p1 - p0)).coerceIn(0f, 1f) else 1f
        var out = 0L
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (c0 shr shift) and 0xFF
            val b = (c1 shr shift) and 0xFF
            out = out or (Math.round(a + (b - a) * f).toLong() shl shift)
        }
        return out
    }
}

/** The alarm the night display is about; [skipped] when it is skipped with "Skip next". */
data class NightAlarm(val alarmId: String, val ringAt: Long, val skipped: Boolean)

/** Widgets of the night display, in the order they appear (docs/design.md, Night display). */
enum class NightWidget(val id: String) {
    WEATHER("weather"),
    PLAN("plan"),
    HOME("home"),
    CALENDAR("calendar"),
    BATTERY("battery");

    companion object {
        val DEFAULT: Set<NightWidget> = setOf(WEATHER, PLAN, HOME, CALENDAR)

        fun parse(ids: Collection<String>): Set<NightWidget> = entries.filter { it.id in ids }.toSet()
    }
}

object NightPlan {
    /** How many entities the home widget lists under the night light. */
    const val HOME_ROWS = 3

    /**
     * The soonest of the normal rings and the occurrences skipped with "Skip next" ([skipped]:
     * alarm id to skipped time). A skipped one stays in view, dimmed, so the skip can be undone.
     */
    fun upcoming(rings: List<PlannedRing>, skipped: Map<String, Long>, now: Long): NightAlarm? {
        val normal = rings.filter { !it.snooze && it.ringAt > now }.map { NightAlarm(it.alarmId, it.ringAt, false) }
        val skips = skipped.filter { it.value > now }.map { NightAlarm(it.key, it.value, true) }
        return (normal + skips).minByOrNull { it.ringAt }
    }

    /** Time of the occurrence on [skippedDate] that `skip_next` skips, in [zone]. */
    fun skippedAt(time: String, skippedDate: LocalDate, zone: ZoneId): Instant? {
        if (!Schedule.isValidTime(time)) return null
        val t = Schedule.parseTime(time)
        return Schedule.resolveLocal(skippedDate, t.hour, t.minute, zone)
    }

    /**
     * The day the widgets look ahead to: the next alarm's day when it rings within a day, else
     * tomorrow in the afternoon and evening, today after midnight.
     */
    fun targetDate(nextRingAt: Long?, now: ZonedDateTime): LocalDate {
        val ms = now.toInstant().toEpochMilli()
        if (nextRingAt != null && nextRingAt - ms in 0..86_400_000L) {
            return Instant.ofEpochMilli(nextRingAt).atZone(now.zone).toLocalDate()
        }
        return if (now.hour >= 12) now.toLocalDate().plusDays(1) else now.toLocalDate()
    }

    /** Edge labels of the mini timeline that do not crowd the alarm label (fractions 0..1). */
    fun edgeLabels(offsets: List<Int>, range: Int, minGap: Float = 0.15f): List<Int> {
        val first = offsets.minOrNull()
        val last = offsets.maxOrNull()
        return listOfNotNull(first?.takeIf { it < 0 }, last?.takeIf { it > 0 })
            .filter { kotlin.math.abs(Timeline.miniFraction(it, range) - 0.5f) >= minGap }
    }
}

/** JSON of Home Assistant's own REST API (plain names, unlike the add-on API). */
val HaJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** One entity from `GET /api/states`. */
@Serializable
data class HaState(
    @SerialName("entity_id") val entityId: String,
    val state: String = "",
    val attributes: JsonObject = JsonObject(emptyMap()),
) {
    val domain: String get() = entityId.substringBefore('.')
    val name: String get() = text("friendly_name")?.takeIf { it.isNotBlank() } ?: entityId
    val available: Boolean get() = state != "unavailable" && state != "unknown"

    fun text(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull

    fun number(key: String): Double? = (attributes[key] as? JsonPrimitive)?.doubleOrNull
}

/** One entry of `weather.get_forecasts` (daily or hourly). */
@Serializable
data class Forecast(
    val datetime: String = "",
    val condition: String? = null,
    val temperature: Double? = null,
    val templow: Double? = null,
    @SerialName("precipitation_probability") val precipitationProbability: Double? = null,
    val precipitation: Double? = null,
    @SerialName("wind_speed") val windSpeed: Double? = null,
) {
    val at: Instant? get() = runCatching { OffsetDateTime.parse(datetime).toInstant() }.getOrNull()
}

/** One event of `GET /api/calendars/{entity_id}`. */
@Serializable
data class CalendarEvent(
    val summary: String = "",
    val start: EventTime = EventTime(),
    val end: EventTime = EventTime(),
    val location: String? = null,
) {
    /** Start of a timed event; null for an all-day event. */
    val startsAt: Instant? get() = start.dateTime?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
}

@Serializable
data class EventTime(val dateTime: String? = null, val date: String? = null)

/** Reading the forecast for the weather widget and the good-morning screen. */
object WeatherPlan {
    /** Rain is likely from this precipitation probability (%). */
    const val RAIN_LIKELY = 50.0
    /** Dry again below this probability (%). */
    const val DRY = 30.0
    private val RAIN_CONDITIONS = setOf("rainy", "pouring", "lightning-rainy", "snowy-rainy", "hail")

    /** The daily forecast of [date] in [zone]. */
    fun day(daily: List<Forecast>, date: LocalDate, zone: ZoneId): Forecast? =
        daily.firstOrNull { it.at?.atZone(zone)?.toLocalDate() == date }

    /** Hourly forecasts after [from], at most [count]. */
    fun hoursAfter(hourly: List<Forecast>, from: Instant, count: Int): List<Forecast> =
        hourly.filter { it.at?.isAfter(from) == true }.take(count)

    fun rainy(f: Forecast): Boolean {
        val p = f.precipitationProbability
        return if (p != null) p >= RAIN_LIKELY else f.condition in RAIN_CONDITIONS || (f.precipitation ?: 0.0) >= 0.2
    }

    /** First hour of [date] between 06:00 and 22:00 when rain is likely. */
    fun rainFrom(hourly: List<Forecast>, date: LocalDate, zone: ZoneId): Instant? = hourly.firstNotNullOfOrNull { f ->
        val at = f.at ?: return@firstNotNullOfOrNull null
        val local = at.atZone(zone)
        at.takeIf { local.toLocalDate() == date && local.hour in 6..21 && rainy(f) }
    }

    /** First hour after [from] when it is dry again. */
    fun dryAgain(hourly: List<Forecast>, from: Instant): Instant? = hourly.firstNotNullOfOrNull { f ->
        val at = f.at ?: return@firstNotNullOfOrNull null
        at.takeIf { it.isAfter(from) && !rainy(f) && (f.precipitationProbability ?: 0.0) < DRY }
    }

    /** Coat weather: a low of 8 °C (46 °F) or less. */
    fun cold(low: Double?, unit: String?): Boolean {
        if (low == null) return false
        return if (unit?.contains('F') == true) low <= 46.0 else low <= 8.0
    }
}
