package alarmclock.vanbaelen.dev.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Runs the shared schedule vectors (docs/test-vectors/schedule.json, ATR-SYN-01). */
class ScheduleVectorsTest {
    private val root: JsonObject by lazy {
        val path = System.getProperty("scheduleVectors")
        val file = path?.let(::File)?.takeIf { it.exists() } ?: File("../../docs/test-vectors/schedule.json")
        assertTrue("schedule vectors not found at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun scheduleVectors() {
        val vectors = root["schedule"]!!.jsonArray
        assertTrue(vectors.isNotEmpty())
        for (element in vectors) {
            val v = element.jsonObject
            val name = v["name"]!!.jsonPrimitive.content
            val next = Schedule.nextOccurrence(
                time = v["time"]!!.jsonPrimitive.content,
                repeatDays = Weekday.parse(v["repeat_days"]!!.jsonArray.map { it.jsonPrimitive.content }),
                skipNext = v["skip_next"]!!.jsonPrimitive.boolean,
                zone = ZoneId.of(v["time_zone"]!!.jsonPrimitive.content),
                after = Instant.parse(v["reference"]!!.jsonPrimitive.content),
            )
            assertEquals(name, Instant.parse(v["expected"]!!.jsonPrimitive.content), next)
        }
    }

    @Test
    fun scheduleVectorsThroughEngine() {
        // The ring engine (skipped date instead of skip_next) must give the same result at home.
        for (element in root["schedule"]!!.jsonArray) {
            val v = element.jsonObject
            val zone = ZoneId.of(v["time_zone"]!!.jsonPrimitive.content)
            val time = v["time"]!!.jsonPrimitive.content
            val days = Weekday.parse(v["repeat_days"]!!.jsonArray.map { it.jsonPrimitive.content })
            val reference = Instant.parse(v["reference"]!!.jsonPrimitive.content)
            val skipped = Schedule.skippedDate(time, days, v["skip_next"]!!.jsonPrimitive.boolean, zone, reference)
            val next = Schedule.nextRing(time, days, skipped, zone, reference)
            assertEquals(v["name"]!!.jsonPrimitive.content, Instant.parse(v["expected"]!!.jsonPrimitive.content), next)
        }
    }

    @Test
    fun occurrenceIdVectors() {
        for (element in root["occurrence_ids"]!!.jsonArray) {
            val v = element.jsonObject
            val at = Instant.parse(v["scheduled_at"]!!.jsonPrimitive.content)
            val id = Schedule.occurrenceId(v["alarm_id"]!!.jsonPrimitive.content, at)
            assertEquals(v["expected"]!!.jsonPrimitive.content, id)
            assertEquals(at, Schedule.occurrenceTime(id))
        }
    }

    @Test
    fun plannedRunIdVectors() {
        for (element in root["planned_run_ids"]!!.jsonArray) {
            val v = element.jsonObject
            assertEquals(
                v["expected"]!!.jsonPrimitive.content,
                Schedule.plannedRunId(v["occurrence_id"]!!.jsonPrimitive.content, v["alarm_action_id"]!!.jsonPrimitive.content),
            )
        }
    }
}
