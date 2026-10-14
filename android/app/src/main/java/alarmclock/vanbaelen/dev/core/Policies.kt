package alarmclock.vanbaelen.dev.core

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

enum class ReportType(val api: String) {
    DISMISSED("dismissed"),
    SNOOZED("snoozed"),
    TIMED_OUT("timed_out");

    companion object {
        fun fromApi(value: String): ReportType? = entries.firstOrNull { it.api == value }
    }
}

/** What to do with a queued report after a send attempt. */
enum class ReportOutcome { SENT, DROP, RETRY, LOGIN }

/** Dismiss / snooze / timed-out reports (AFR-SYN-03, AFR-RNG-08, SEC-06). */
object ReportPolicy {
    /** Away = the phone's time zone differs from HA's (same string comparison as the server). */
    fun isAway(haTimeZone: String?, phoneTimeZone: String): Boolean =
        haTimeZone != null && haTimeZone != phoneTimeZone

    /** Reports are only queued at home and for a known occurrence. */
    fun shouldQueue(away: Boolean, occurrenceId: String?): Boolean = !away && !occurrenceId.isNullOrBlank()

    /**
     * Same plausibility rule as the server: occurred_at no earlier than 60 min before the
     * scheduled time (snoozed / timed_out not before scheduled - 2 min) and not in the future.
     */
    fun plausible(type: ReportType, scheduledAt: Instant, occurredAt: Instant, now: Instant): Boolean {
        if (occurredAt.isAfter(now.plus(Duration.ofMinutes(2)))) return false
        val earliest = when (type) {
            ReportType.DISMISSED -> scheduledAt.minus(Duration.ofMinutes(60))
            else -> scheduledAt.minus(Duration.ofMinutes(2))
        }
        return !occurredAt.isBefore(earliest)
    }

    /** Classifies an HTTP status (null: network failure) for a report. */
    fun outcome(status: Int?): ReportOutcome = when {
        status == null -> ReportOutcome.RETRY
        status in 200..299 -> ReportOutcome.SENT
        status == 401 -> ReportOutcome.LOGIN
        status == 408 || status == 429 || status >= 500 -> ReportOutcome.RETRY
        // 400/404/422: the server will never accept it (unknown alarm, implausible, skipped).
        else -> ReportOutcome.DROP
    }
}

enum class Compatibility { OK, DASHBOARD_TOO_OLD, APP_TOO_OLD }

/** API version check against GET /info (AFR-SET-02). */
object ApiCompat {
    const val API_VERSION = 1

    fun check(info: ApiInfo): Compatibility = when {
        info.apiVersion < API_VERSION -> Compatibility.DASHBOARD_TOO_OLD
        info.minClientApiVersion > API_VERSION -> Compatibility.APP_TOO_OLD
        else -> Compatibility.OK
    }
}

object AppVersion {
    /** versionCode = major * 1_000_000 + minor * 1_000 + patch (ATR-REL-03, see app/build.gradle.kts). */
    fun versionCode(versionName: String): Int {
        val parts = versionName.substringBefore('-').split('.').map { it.toInt() }
        require(parts.size == 3 && parts.all { it >= 0 } && parts[1] < 1000 && parts[2] < 1000) { "Invalid version $versionName" }
        return parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
    }

    fun updateAvailable(info: AppInfo?, currentVersionCode: Long): Boolean =
        info != null && info.available && info.versionCode != null && !info.sha256.isNullOrBlank() &&
            info.versionCode > currentVersionCode
}

enum class UpdateBlock { RINGING, ALARM_SOON }

/** No update while an alarm rings or within 30 minutes before the next one (ATR-REL-04). */
object UpdatePolicy {
    val QUIET_BEFORE_ALARM: Duration = Duration.ofMinutes(30)

    fun check(now: Instant, ringing: Boolean, nextRing: Instant?): UpdateBlock? = when {
        ringing -> UpdateBlock.RINGING
        nextRing != null && !nextRing.isBefore(now) && Duration.between(now, nextRing) < QUIET_BEFORE_ALARM -> UpdateBlock.ALARM_SOON
        else -> null
    }

    fun checksumMatches(expectedHex: String?, actualHex: String): Boolean =
        !expectedHex.isNullOrBlank() && expectedHex.trim().equals(actualHex, ignoreCase = true)
}

/** Proactive token refresh when the access token expires within a minute (ATR-SYN-05). */
object TokenPolicy {
    val REFRESH_MARGIN: Duration = Duration.ofSeconds(60)

    fun needsRefresh(expiresAt: Instant, now: Instant): Boolean = !now.plus(REFRESH_MARGIN).isBefore(expiresAt)
}

enum class UrlError { EMPTY, NOT_HTTPS, INVALID }

data class SetupUrls(val haUrl: String, val apiUrl: String)

/** Setup deep link and URL validation (ATR-SYN-06). */
object SetupLinks {
    /** Normalised https URL without trailing slash, or the reason it is rejected. */
    fun validate(raw: String): Result<String> {
        val value = raw.trim()
        if (value.isEmpty()) return Result.failure(UrlException(UrlError.EMPTY))
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return Result.failure(UrlException(UrlError.INVALID))
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return Result.failure(UrlException(UrlError.NOT_HTTPS))
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            return Result.failure(UrlException(UrlError.INVALID))
        }
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val path = (uri.rawPath ?: "").trimEnd('/')
        return Result.success("https://${uri.host.lowercase()}$port$path")
    }

    /** Parses `alarmactions://setup?ha=<url>&api=<url>`; null when it is not a valid setup link. */
    fun parse(link: String): SetupUrls? {
        val uri = try {
            URI(link)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != "alarmactions" || uri.host != "setup") return null
        val params = (uri.rawQuery ?: return null).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
        val ha = params["ha"]?.let { validate(it).getOrNull() } ?: return null
        val api = params["api"]?.let { validate(it).getOrNull() } ?: return null
        return SetupUrls(ha, api)
    }

    fun host(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
}

class UrlException(val error: UrlError) : IllegalArgumentException(error.name)

/** Night display auto-start window (AFR-NGT-01); a window may wrap midnight. */
object NightWindow {
    fun contains(now: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
        start == end -> true
        start.isBefore(end) -> !now.isBefore(start) && now.isBefore(end)
        else -> !now.isBefore(start) || now.isBefore(end)
    }
}
