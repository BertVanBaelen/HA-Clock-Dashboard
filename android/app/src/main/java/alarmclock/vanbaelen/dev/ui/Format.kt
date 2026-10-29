package alarmclock.vanbaelen.dev.ui

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.ActionSummary
import alarmclock.vanbaelen.dev.core.DraftError
import alarmclock.vanbaelen.dev.core.HaState
import alarmclock.vanbaelen.dev.core.Limits
import alarmclock.vanbaelen.dev.core.Timing
import alarmclock.vanbaelen.dev.core.UrlError
import alarmclock.vanbaelen.dev.core.Weekday
import alarmclock.vanbaelen.dev.data.Problem
import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Formatting that follows the panel (strings.ts): days summary, times, next ring. */
object Format {
    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    fun time(context: Context, hhmm: String): String {
        val t = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: return hhmm
        return time(context, t)
    }

    fun time(context: Context, t: LocalTime): String =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale(context)).format(t)

    fun dayName(context: Context, day: Weekday, style: TextStyle = TextStyle.SHORT): String =
        day.dayOfWeek.getDisplayName(style, locale(context))

    fun days(context: Context, days: List<Weekday>): String = when {
        days.isEmpty() -> context.getString(R.string.once)
        days.size == 7 -> context.getString(R.string.every_day)
        days.map { it.dayOfWeek.value }.toSet() == (1..5).toSet() -> context.getString(R.string.weekdays)
        days.map { it.dayOfWeek.value }.toSet() == setOf(6, 7) -> context.getString(R.string.weekend)
        else -> days.joinToString(", ") { dayName(context, it) }
    }

    /** Next ring, for example "Tue 07:00" (with the date when more than 6 days away). */
    fun next(context: Context, epochMs: Long): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        val far = epochMs - System.currentTimeMillis() > 6 * 86_400_000L
        val day = DateTimeFormatter.ofPattern(if (far) "EEE d MMM" else "EEE", locale(context)).format(at)
        return "$day ${time(context, at.toLocalTime())}"
    }

    /** Time left until [epochMs], rounded up to the minute: "7 h 12 min", "2 d 3 h". */
    fun until(context: Context, epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = ((epochMs - now + 59_999L) / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60
        return when {
            hours >= 24 -> context.getString(R.string.dur_d_h, (hours / 24).toInt(), (hours % 24).toInt())
            hours > 0 -> context.getString(R.string.dur_h_min, hours.toInt(), (minutes % 60).toInt())
            else -> context.getString(R.string.dur_min, minutes.toInt())
        }
    }

    /** The alarm time [hhmm] shifted by [offset] minutes, for timeline labels. */
    fun shifted(context: Context, hhmm: String, offset: Int): String {
        val t = runCatching { LocalTime.parse(hhmm) }.getOrNull() ?: return hhmm
        return time(context, t.plusMinutes(offset.toLong()))
    }

    /** "2 before · 1 with · 1 after · 1 after dismiss", or "Simple alarm". */
    fun summary(context: Context, s: ActionSummary): String {
        if (s.isEmpty) return context.getString(R.string.simple_alarm)
        return listOfNotNull(
            s.before.takeIf { it > 0 }?.let { context.getString(R.string.summary_before, it) },
            s.with.takeIf { it > 0 }?.let { context.getString(R.string.summary_with, it) },
            s.after.takeIf { it > 0 }?.let { context.getString(R.string.summary_after, it) },
            s.afterDismiss.takeIf { it > 0 }?.let { context.getString(R.string.summary_after_dismiss, it) },
        ).joinToString(" · ")
    }

    /** Relative label of an action card: "−30 min", "with alarm", "+5 min", "right away". */
    fun relative(context: Context, signedOffset: Int?, delay: Int?): String = when {
        signedOffset == null -> if ((delay ?: 0) == 0) context.getString(R.string.rel_right_away) else context.getString(R.string.rel_after, delay)
        signedOffset < 0 -> context.getString(R.string.rel_before, -signedOffset)
        signedOffset == 0 -> context.getString(R.string.rel_at)
        else -> context.getString(R.string.rel_after, signedOffset)
    }

    fun ago(context: Context, epochMs: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), 60_000L).toString()

    fun timing(context: Context, timing: Timing): String = context.getString(
        when (timing) {
            Timing.BEFORE -> R.string.timing_before
            Timing.AT -> R.string.timing_at
            Timing.AFTER -> R.string.timing_after
            Timing.AFTER_DISMISS -> R.string.timing_after_dismiss
        },
    )

    fun draftError(context: Context, error: DraftError): String = when (error) {
        DraftError.INVALID_TIME -> context.getString(R.string.invalid_time)
        DraftError.CHOOSE_SCRIPT -> context.getString(R.string.choose_script)
        DraftError.MINUTES_RANGE -> context.getString(R.string.minutes_range)
        DraftError.DELAY_RANGE -> context.getString(R.string.delay_range)
        DraftError.DUPLICATE_ACTION -> context.getString(R.string.duplicate_action)
        DraftError.SNOOZE_RANGE -> context.getString(R.string.snooze_range, Limits.SNOOZE_MIN, Limits.SNOOZE_MAX)
        DraftError.LABEL_LENGTH -> context.getString(R.string.label_length, Limits.LABEL_LENGTH)
        DraftError.ACTION_LIMIT -> context.getString(R.string.action_limit, Limits.ACTIONS_PER_ALARM)
    }

    fun problem(context: Context, problem: Problem, message: String? = null): String = when (problem) {
        Problem.OFFLINE -> context.getString(R.string.network_error)
        Problem.LOGIN -> context.getString(R.string.login_again_text)
        Problem.HA_OFFLINE -> context.getString(R.string.ha_offline)
        Problem.CONFLICT -> context.getString(R.string.conflict)
        Problem.FORBIDDEN -> context.getString(R.string.forbidden)
        Problem.NOT_FOUND -> context.getString(R.string.not_found)
        Problem.REJECTED -> message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.generic_error)
        Problem.ERROR -> context.getString(R.string.generic_error)
    }

    /** The night display's clock: like [time], but without AM/PM so it fits the big type. */
    fun clock(context: Context, t: LocalTime): String =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", locale(context)).format(t)

    /** Time of [epochMs] in the phone's zone. */
    fun time(context: Context, epochMs: Long): String =
        time(context, Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime())

    /** A ring within a day as its time, a later one with the day ("Sat 08:15"). */
    fun ring(context: Context, epochMs: Long, now: Long = System.currentTimeMillis()): String =
        if (epochMs - now < 86_400_000L) time(context, epochMs) else next(context, epochMs)

    /** "Today", "Tomorrow" or the weekday. */
    fun dayTitle(context: Context, date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> context.getString(R.string.today)
        today.plusDays(1) -> context.getString(R.string.tomorrow)
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale(context)).replaceFirstChar { it.titlecase(locale(context)) }
    }

    /** "Thursday 29 October". */
    fun longDate(context: Context, date: LocalDate): String =
        DateTimeFormatter.ofPattern("EEEE d MMMM", locale(context)).format(date).replaceFirstChar { it.titlecase(locale(context)) }

    fun greeting(context: Context, hour: Int): String = context.getString(
        when (hour) {
            in 4..11 -> R.string.good_morning
            in 12..17 -> R.string.good_afternoon
            else -> R.string.good_evening
        },
    )

    /** A temperature rounded to whole degrees: "12°". */
    fun degrees(value: Double?): String = value?.let { "${Math.round(it)}°" } ?: "–"

    /** A room temperature with one decimal: "19.5°". */
    fun degreesFine(context: Context, value: Double): String = String.format(locale(context), "%.1f°", value)

    /** Home Assistant weather condition (`partlycloudy`, `rainy`, …) in words. */
    fun condition(context: Context, condition: String?): String = context.getString(
        when (condition) {
            "clear-night" -> R.string.weather_clear_night
            "cloudy" -> R.string.weather_cloudy
            "fog" -> R.string.weather_fog
            "hail" -> R.string.weather_hail
            "lightning", "lightning-rainy" -> R.string.weather_lightning
            "partlycloudy" -> R.string.weather_partlycloudy
            "pouring" -> R.string.weather_pouring
            "rainy" -> R.string.weather_rainy
            "snowy" -> R.string.weather_snowy
            "snowy-rainy" -> R.string.weather_snowy_rainy
            "sunny" -> R.string.weather_sunny
            "windy", "windy-variant" -> R.string.weather_windy
            else -> R.string.weather_unknown
        },
    )

    /** An entity's state for the home widget: "Off", "Locked", "Heating · 18°", "21.5 °C". */
    fun entityState(context: Context, s: HaState): String {
        if (!s.available) return context.getString(R.string.state_unavailable)
        val onOff = { context.getString(if (s.state == "on") R.string.state_on else R.string.state_off) }
        return when (s.domain) {
            "light", "switch", "fan", "input_boolean" -> onOff()
            "lock" -> when (s.state) {
                "locked" -> context.getString(R.string.state_locked)
                "unlocked" -> context.getString(R.string.state_unlocked)
                "open" -> context.getString(R.string.state_open)
                else -> s.state
            }
            "cover" -> when (s.state) {
                "open", "opening" -> context.getString(R.string.state_open)
                "closed", "closing" -> context.getString(R.string.state_closed)
                else -> s.state
            }
            "binary_sensor" -> if (s.text("device_class") in setOf("door", "window", "opening", "garage_door")) {
                context.getString(if (s.state == "on") R.string.state_open else R.string.state_closed)
            } else {
                onOff()
            }
            "climate" -> {
                val mode = when (s.state) {
                    "off" -> return context.getString(R.string.state_off)
                    "heat" -> context.getString(R.string.state_heating)
                    "cool" -> context.getString(R.string.state_cooling)
                    else -> context.getString(R.string.state_auto)
                }
                s.number("temperature")?.let { "$mode · ${degrees(it)}" } ?: mode
            }
            "sensor" -> s.state + (s.text("unit_of_measurement")?.let { " $it" } ?: "")
            else -> s.state
        }
    }

    fun urlError(context: Context, error: UrlError): String = context.getString(
        when (error) {
            UrlError.EMPTY -> R.string.url_empty
            UrlError.NOT_HTTPS -> R.string.url_not_https
            UrlError.INVALID -> R.string.url_invalid
        },
    )
}
