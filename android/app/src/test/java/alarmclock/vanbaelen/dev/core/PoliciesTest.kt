package alarmclock.vanbaelen.dev.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalTime

class PoliciesTest {
    private val scheduled = Instant.parse("2026-10-15T04:45:00Z")

    @Test
    fun awayIsTimeZoneMismatch() {
        assertFalse(ReportPolicy.isAway("Europe/Brussels", "Europe/Brussels"))
        assertTrue(ReportPolicy.isAway("Europe/Brussels", "America/New_York"))
        assertFalse(ReportPolicy.isAway(null, "America/New_York"))
    }

    @Test
    fun noReportsWhileAway() {
        assertFalse(ReportPolicy.shouldQueue(away = true, occurrenceId = "a_20261015T044500Z"))
        assertFalse(ReportPolicy.shouldQueue(away = false, occurrenceId = null))
        assertTrue(ReportPolicy.shouldQueue(away = false, occurrenceId = "a_20261015T044500Z"))
    }

    @Test
    fun plausibility() {
        val now = scheduled.plusSeconds(600)
        assertTrue(ReportPolicy.plausible(ReportType.DISMISSED, scheduled, scheduled.plusSeconds(30), now))
        assertTrue(ReportPolicy.plausible(ReportType.DISMISSED, scheduled, scheduled.minusSeconds(59 * 60), now))
        assertFalse(ReportPolicy.plausible(ReportType.DISMISSED, scheduled, scheduled.minusSeconds(61 * 60), now))
        assertFalse(ReportPolicy.plausible(ReportType.SNOOZED, scheduled, scheduled.minusSeconds(180), now))
        assertTrue(ReportPolicy.plausible(ReportType.TIMED_OUT, scheduled, scheduled.minusSeconds(60), now))
        assertFalse(ReportPolicy.plausible(ReportType.DISMISSED, scheduled, now.plusSeconds(180), now))
    }

    @Test
    fun reportOutcomes() {
        assertEquals(ReportOutcome.RETRY, ReportPolicy.outcome(null))
        assertEquals(ReportOutcome.SENT, ReportPolicy.outcome(200))
        assertEquals(ReportOutcome.LOGIN, ReportPolicy.outcome(401))
        assertEquals(ReportOutcome.DROP, ReportPolicy.outcome(422))
        assertEquals(ReportOutcome.DROP, ReportPolicy.outcome(404))
        assertEquals(ReportOutcome.RETRY, ReportPolicy.outcome(503))
        assertEquals(ReportOutcome.RETRY, ReportPolicy.outcome(429))
    }

    @Test
    fun apiVersionCheck() {
        assertEquals(Compatibility.OK, ApiCompat.check(ApiInfo(apiVersion = 1, minClientApiVersion = 1)))
        assertEquals(Compatibility.OK, ApiCompat.check(ApiInfo(apiVersion = 2, minClientApiVersion = 1)))
        assertEquals(Compatibility.DASHBOARD_TOO_OLD, ApiCompat.check(ApiInfo(apiVersion = 0, minClientApiVersion = 0)))
        assertEquals(Compatibility.APP_TOO_OLD, ApiCompat.check(ApiInfo(apiVersion = 2, minClientApiVersion = 2)))
    }

    @Test
    fun versionCode() {
        assertEquals(1_002_003, AppVersion.versionCode("1.2.3"))
        assertEquals(1_002, AppVersion.versionCode("0.1.2"))
        assertTrue(AppVersion.versionCode("0.2.0") > AppVersion.versionCode("0.1.999"))
        assertTrue(AppVersion.updateAvailable(AppInfo(true, "0.1.3", 1003, "ab"), 1002))
        assertFalse(AppVersion.updateAvailable(AppInfo(true, "0.1.2", 1002, "ab"), 1002))
        assertFalse(AppVersion.updateAvailable(AppInfo(true, "0.1.3", 1003, null), 1002))
        assertFalse(AppVersion.updateAvailable(AppInfo(false), 1002))
    }

    @Test
    fun versionNameComesFromAddonConfig() {
        val file = System.getProperty("addonConfig")?.let(::File)?.takeIf { it.exists() } ?: return
        val version = file.readLines().firstNotNullOfOrNull { Regex("""^version:\s*"?(\d+\.\d+\.\d+)"?""").find(it)?.groupValues?.get(1) }
        assertTrue("version line in ${file.name}", version != null)
        assertTrue(AppVersion.versionCode(version!!) > 0)
    }

    @Test
    fun updatePolicy() {
        val now = Instant.parse("2026-10-14T20:00:00Z")
        assertEquals(UpdateBlock.RINGING, UpdatePolicy.check(now, true, null))
        assertEquals(UpdateBlock.ALARM_SOON, UpdatePolicy.check(now, false, now.plusSeconds(29 * 60)))
        assertNull(UpdatePolicy.check(now, false, now.plusSeconds(31 * 60)))
        assertNull(UpdatePolicy.check(now, false, null))
        assertTrue(UpdatePolicy.checksumMatches("ABCdef", "abcdef"))
        assertFalse(UpdatePolicy.checksumMatches(null, "abcdef"))
        assertFalse(UpdatePolicy.checksumMatches("abc", "abd"))
    }

    @Test
    fun tokenRefreshMargin() {
        val now = Instant.parse("2026-10-14T20:00:00Z")
        assertTrue(TokenPolicy.needsRefresh(now.plusSeconds(59), now))
        assertTrue(TokenPolicy.needsRefresh(now.minusSeconds(1), now))
        assertFalse(TokenPolicy.needsRefresh(now.plusSeconds(120), now))
    }

    @Test
    fun setupLinkAndUrls() {
        val urls = SetupLinks.parse("alarmactions://setup?ha=https%3A%2F%2Fha.example.com%2F&api=https%3A%2F%2Fapi.example.com%2Fclock")
        assertEquals(SetupUrls("https://ha.example.com", "https://api.example.com/clock"), urls)
        assertNull(SetupLinks.parse("alarmactions://setup?ha=http%3A%2F%2Fha.local&api=https%3A%2F%2Fa.b"))
        assertNull(SetupLinks.parse("https://setup?ha=x"))
        assertEquals(UrlError.NOT_HTTPS, (SetupLinks.validate("http://x.y").exceptionOrNull() as UrlException).error)
        assertEquals(UrlError.EMPTY, (SetupLinks.validate(" ").exceptionOrNull() as UrlException).error)
        assertEquals(UrlError.INVALID, (SetupLinks.validate("https://").exceptionOrNull() as UrlException).error)
        assertEquals("https://ha.example.com:8443", SetupLinks.validate("https://HA.example.com:8443/").getOrNull())
    }

    @Test
    fun nightWindow() {
        val start = LocalTime.of(22, 0)
        val end = LocalTime.of(7, 0)
        assertTrue(NightWindow.contains(LocalTime.of(23, 0), start, end))
        assertTrue(NightWindow.contains(LocalTime.of(3, 0), start, end))
        assertFalse(NightWindow.contains(LocalTime.of(12, 0), start, end))
        assertTrue(NightWindow.contains(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)))
    }
}
