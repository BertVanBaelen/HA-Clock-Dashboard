package alarmclock.vanbaelen.dev.ui

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.core.DraftError
import alarmclock.vanbaelen.dev.core.Limits
import alarmclock.vanbaelen.dev.core.Timing
import alarmclock.vanbaelen.dev.core.UrlError
import alarmclock.vanbaelen.dev.core.Weekday
import alarmclock.vanbaelen.dev.data.Problem
import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
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

    fun urlError(context: Context, error: UrlError): String = context.getString(
        when (error) {
            UrlError.EMPTY -> R.string.url_empty
            UrlError.NOT_HTTPS -> R.string.url_not_https
            UrlError.INVALID -> R.string.url_invalid
        },
    )
}
