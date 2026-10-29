package alarmclock.vanbaelen.dev.core

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NightTest {
    private val zone = ZoneId.of("Europe/Brussels")
    private val ringAt = Instant.parse("2026-10-30T05:30:00Z").toEpochMilli()

    private fun ring(sunrise: Int = 20, snooze: Boolean = false, alarmId: String = "a", at: Long = ringAt) =
        PlannedRing(alarmId, at, null, "", "06:30", 9, snooze = snooze, sunriseMinutes = sunrise)

    @Test
    fun ringPlanCarriesTheSunrise() {
        val now = Instant.parse("2026-10-14T10:16:00Z")
        val spec = RingSpec("a", 1, "Wake", "06:45", listOf("thu"), true, null, 9, sunriseMinutes = 20)
        assertEquals(20, RingPlanner.next(spec, null, zone, now)!!.sunriseMinutes)
        assertEquals(0, RingPlanner.next(spec.copy(sunriseMinutes = 0), null, zone, now)!!.sunriseMinutes)
    }

    @Test
    fun sunriseRunsOnlyBeforeTheRing() {
        val r = ring(sunrise = 20)
        assertNull(Sunrise.progress(r, ringAt - 21 * 60_000L))
        assertEquals(0f, Sunrise.progress(r, ringAt - 20 * 60_000L)!!, 0.0001f)
        assertEquals(0.5f, Sunrise.progress(r, ringAt - 10 * 60_000L)!!, 0.0001f)
        assertNull(Sunrise.progress(r, ringAt))
        assertNull(Sunrise.progress(ring(sunrise = 0), ringAt - 60_000L))
        assertNull(Sunrise.progress(ring(snooze = true), ringAt - 60_000L))
    }

    @Test
    fun runningSunrisePicksTheFurthestAlong() {
        val early = ring(alarmId = "early", sunrise = 30)
        val late = ring(alarmId = "late", sunrise = 10, at = ringAt + 5 * 60_000L)
        val now = ringAt - 2 * 60_000L
        assertEquals("early", Sunrise.running(listOf(late, early), now)!!.first.alarmId)
        assertNull(Sunrise.running(listOf(ring(sunrise = 0)), now))
    }

    @Test
    fun sunriseColoursFollowTheKeyframes() {
        assertEquals(0xFF000000L, Sunrise.background(0f))
        assertEquals(0xFFFFFDF8L, Sunrise.background(1f))
        assertEquals(0xFF2E1607L, Sunrise.background(0.35f))
        assertEquals(0xFFE6B183L, Sunrise.ink(0f))
        assertEquals(0xFF2A1A0EL, Sunrise.ink(0.8f))
        // Half way between black and #2E1607.
        assertEquals(0xFF170B04L, Sunrise.background(0.175f))
        assertEquals(Sunrise.NIGHT_BRIGHTNESS, Sunrise.brightness(0f), 0.0001f)
        assertEquals(1f, Sunrise.brightness(1f), 0.0001f)
        assertTrue(Sunrise.brightness(0.5f) < 0.5f)
    }

    @Test
    fun upcomingIncludesASkippedOccurrence() {
        val normal = ring(alarmId = "b", at = ringAt + 86_400_000L)
        val now = ringAt - 3_600_000L
        assertEquals(NightAlarm("b", normal.ringAt, false), NightPlan.upcoming(listOf(normal), emptyMap(), now))
        assertEquals(NightAlarm("a", ringAt, true), NightPlan.upcoming(listOf(normal), mapOf("a" to ringAt), now))
        // Snoozes and passed skips do not count.
        assertNull(NightPlan.upcoming(listOf(ring(snooze = true)), mapOf("a" to now - 1), now))
    }

    @Test
    fun skippedOccurrenceIsAtTheAlarmTime() {
        assertEquals(Instant.parse("2026-10-30T05:30:00Z"), NightPlan.skippedAt("06:30", LocalDate.parse("2026-10-30"), zone))
        assertNull(NightPlan.skippedAt("6:30", LocalDate.parse("2026-10-30"), zone))
    }

    @Test
    fun targetDateLooksAheadToTheNextAlarm() {
        val evening = ZonedDateTime.parse("2026-10-29T23:00:00+01:00[Europe/Brussels]")
        assertEquals(LocalDate.parse("2026-10-30"), NightPlan.targetDate(ringAt, evening))
        assertEquals(LocalDate.parse("2026-10-30"), NightPlan.targetDate(null, evening))
        val night = ZonedDateTime.parse("2026-10-30T01:00:00+01:00[Europe/Brussels]")
        assertEquals(LocalDate.parse("2026-10-30"), NightPlan.targetDate(null, night))
        // An alarm more than a day away does not decide the day.
        assertEquals(LocalDate.parse("2026-10-30"), NightPlan.targetDate(ringAt + 3 * 86_400_000L, evening))
    }

    @Test
    fun edgeLabelsDoNotCrowdTheAlarm() {
        assertEquals(listOf(-30, 55), NightPlan.edgeLabels(listOf(-30, -20, -5, 5, 55), 60))
        assertEquals(emptyList<Int>(), NightPlan.edgeLabels(listOf(-5, 5), 60))
        assertEquals(listOf(-45), NightPlan.edgeLabels(listOf(-45, -10), 60))
    }

    @Test
    fun widgetsParseInTheirOwnOrder() {
        assertEquals(setOf(NightWidget.BATTERY, NightWidget.PLAN), NightWidget.parse(listOf("battery", "plan", "sky")))
        assertTrue(NightWidget.parse(emptyList()).isEmpty())
    }

    @Test
    fun forecastFromHomeAssistant() {
        val json = """
            [{"datetime":"2026-10-30T07:00:00+00:00","condition":"rainy","temperature":7.2,"precipitation_probability":70},
             {"datetime":"2026-10-30T08:00:00+00:00","condition":"rainy","temperature":8,"precipitation_probability":60},
             {"datetime":"2026-10-30T09:00:00+00:00","condition":"cloudy","temperature":10,"precipitation_probability":20,"unknown":1}]
        """.trimIndent()
        val hourly = HaJson.decodeFromString(ListSerializer(Forecast.serializer()), json)
        assertEquals(3, hourly.size)
        val date = LocalDate.parse("2026-10-30")
        assertEquals(Instant.parse("2026-10-30T07:00:00Z"), WeatherPlan.rainFrom(hourly, date, zone))
        assertEquals(Instant.parse("2026-10-30T09:00:00Z"), WeatherPlan.dryAgain(hourly, Instant.parse("2026-10-30T07:00:00Z")))
        assertEquals(2, WeatherPlan.hoursAfter(hourly, Instant.parse("2026-10-30T07:30:00Z"), 6).size)
        assertTrue(WeatherPlan.rainy(Forecast(condition = "pouring")))
        assertFalse(WeatherPlan.rainy(Forecast(condition = "rainy", precipitationProbability = 10.0)))
    }

    @Test
    fun coldDependsOnTheUnit() {
        assertTrue(WeatherPlan.cold(5.0, "°C"))
        assertFalse(WeatherPlan.cold(12.0, "°C"))
        assertFalse(WeatherPlan.cold(50.0, "°F"))
        assertTrue(WeatherPlan.cold(40.0, "°F"))
        assertFalse(WeatherPlan.cold(null, "°C"))
    }

    @Test
    fun calendarAndStatesFromHomeAssistant() {
        val events = HaJson.decodeFromString(
            ListSerializer(CalendarEvent.serializer()),
            """[{"summary":"Stand-up","start":{"dateTime":"2026-10-30T09:30:00+01:00"},"end":{"dateTime":"2026-10-30T09:45:00+01:00"}},
                {"summary":"Holiday","start":{"date":"2026-10-30"},"end":{"date":"2026-10-31"}}]""",
        )
        assertEquals(Instant.parse("2026-10-30T08:30:00Z"), events[0].startsAt)
        assertNull(events[1].startsAt)
        val state = HaJson.decodeFromString(
            HaState.serializer(),
            """{"entity_id":"sensor.bedroom","state":"19.5","attributes":{"friendly_name":"Bedroom","unit_of_measurement":"°C","list":[1]}}""",
        )
        assertEquals("Bedroom", state.name)
        assertEquals("sensor", state.domain)
        assertEquals("°C", state.text("unit_of_measurement"))
        assertNull(state.text("list"))
        assertNull(state.number("missing"))
    }
}
