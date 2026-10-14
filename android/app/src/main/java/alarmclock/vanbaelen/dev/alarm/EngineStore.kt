package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.core.ApiJson
import alarmclock.vanbaelen.dev.core.FiredInfo
import alarmclock.vanbaelen.dev.core.PlannedRing
import alarmclock.vanbaelen.dev.core.RingSpec
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** A report made while the phone was still locked after a reboot (Room is not available then). */
@Serializable
data class LockedReport(val alarmId: String, val occurrenceId: String, val type: String, val occurredAt: Long, val scheduledAt: Long)

@Serializable
data class SkipMark(val revision: Int, val date: String)

/**
 * Alarm engine state in device-protected storage, so alarms are rescheduled and ring after a
 * reboot before the first unlock. Holds only what ringing needs (times, labels, sounds).
 */
class EngineStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("alarm_engine", Context.MODE_PRIVATE)

    private val specList = ListSerializer(RingSpec.serializer())
    private val ringList = ListSerializer(PlannedRing.serializer())
    private val firedMap = MapSerializer(String.serializer(), FiredInfo.serializer())
    private val skipMap = MapSerializer(String.serializer(), SkipMark.serializer())
    private val reportList = ListSerializer(LockedReport.serializer())

    private fun <T> read(key: String, serializer: kotlinx.serialization.KSerializer<T>, default: T): T =
        prefs.getString(key, null)?.let { runCatching { ApiJson.decodeFromString(serializer, it) }.getOrNull() } ?: default

    private fun <T> write(key: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        prefs.edit().putString(key, ApiJson.encodeToString(serializer, value)).commit()
    }

    var specs: List<RingSpec>
        get() = read("specs", specList, emptyList())
        set(value) = write("specs", specList, value)

    var scheduled: List<PlannedRing>
        get() = read("scheduled", ringList, emptyList())
        set(value) = write("scheduled", ringList, value)

    var fired: Map<String, FiredInfo>
        get() = read("fired", firedMap, emptyMap())
        set(value) = write("fired", firedMap, value)

    var skips: Map<String, SkipMark>
        get() = read("skips", skipMap, emptyMap())
        set(value) = write("skips", skipMap, value)

    var missed: List<PlannedRing>
        get() = read("missed", ringList, emptyList())
        set(value) = write("missed", ringList, value)

    var lockedReports: List<LockedReport>
        get() = read("locked_reports", reportList, emptyList())
        set(value) = write("locked_reports", reportList, value)

    var haTimeZone: String?
        get() = prefs.getString("ha_time_zone", null)
        set(value) {
            prefs.edit().putString("ha_time_zone", value).commit()
        }

    /** Ring currently sounding, so a restarted service or the UI can pick it up. */
    var ringing: PlannedRing?
        get() = prefs.getString("ringing", null)?.let { runCatching { ApiJson.decodeFromString(PlannedRing.serializer(), it) }.getOrNull() }
        set(value) {
            prefs.edit().putString("ringing", value?.let { ApiJson.encodeToString(PlannedRing.serializer(), it) }).commit()
        }

    fun clear() {
        prefs.edit().clear().commit()
    }
}
