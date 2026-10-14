@file:OptIn(ExperimentalSerializationApi::class)

package alarmclock.vanbaelen.dev.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** JSON as used by the API: snake_case names, unknown response fields ignored. */
val ApiJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
data class AlarmAction(
    val id: String,
    val scriptEntityId: String,
    val timing: String,
    val offsetMinutes: Int? = null,
    val delayMinutes: Int? = null,
    val enabled: Boolean = true,
)

@Serializable
data class CurrentOccurrence(val id: String, val scheduledAt: String, val status: String)

@Serializable
data class Alarm(
    val id: String,
    val ownerUserId: String = "",
    val ownerName: String? = null,
    val label: String = "",
    val time: String,
    val repeatDays: List<String> = emptyList(),
    val enabled: Boolean = true,
    val skipNext: Boolean = false,
    val snoozeMinutes: Int? = null,
    val actions: List<AlarmAction> = emptyList(),
    val revision: Int = 0,
    val changeSeq: Long = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val nextOccurrence: String? = null,
    val disabledReason: String? = null,
    val currentOccurrence: CurrentOccurrence? = null,
) {
    val days: List<Weekday> get() = Weekday.parse(repeatDays)
    val repeating: Boolean get() = days.isNotEmpty()
}

@Serializable
data class AlarmWarning(val code: String, val alarmActionId: String? = null, val message: String = "")

@Serializable
data class AlarmSaveResponse(val alarm: Alarm, val warnings: List<AlarmWarning> = emptyList())

@Serializable
data class AlarmsResponse(val alarms: List<Alarm> = emptyList())

@Serializable
data class AlarmActionInput(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val id: String? = null,
    val scriptEntityId: String,
    val timing: String,
    val offsetMinutes: Int?,
    val delayMinutes: Int?,
    val enabled: Boolean,
)

/** Body of POST /alarms (revision null) and PUT /alarms/{id} (with revision). */
@Serializable
data class AlarmInput(
    val label: String,
    val time: String,
    val repeatDays: List<String>,
    val enabled: Boolean,
    val snoozeMinutes: Int?,
    val actions: List<AlarmActionInput>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val revision: Int? = null,
)

@Serializable
data class SyncResponse(
    val changeSeq: Long,
    val full: Boolean,
    val alarms: List<Alarm> = emptyList(),
    val deleted: List<String> = emptyList(),
    val timeZone: String? = null,
    val snoozeMinutes: Int = 9,
    val dismissTimeoutMinutes: Int = 60,
    val away: Boolean = false,
)

@Serializable
data class ClientRegistration(
    val id: String,
    val name: String,
    val appVersion: String,
    val timeZone: String,
    val lastSyncSeq: Long,
)

@Serializable
data class ClientInfo(
    val id: String,
    val name: String = "",
    val appVersion: String = "",
    val timeZone: String = "",
    val lastSyncSeq: Long = 0,
    val lastSeenAt: String? = null,
    val away: Boolean = false,
)

@Serializable
data class ApiInfo(
    val name: String = "",
    val version: String = "",
    val apiVersion: Int = 0,
    val minClientApiVersion: Int = 0,
    val timeZone: String? = null,
    val haConnected: Boolean = false,
    val appAvailable: Boolean = false,
)

@Serializable
data class Me(val id: String, val name: String = "", val away: Boolean = false)

@Serializable
data class ActionDefinition(
    val entityId: String,
    val name: String = "",
    val icon: String? = null,
    val iconPath: String? = null,
    val description: String? = null,
)

@Serializable
data class ActionsResponse(val actions: List<ActionDefinition> = emptyList())

@Serializable
data class ServerSettings(val snoozeMinutes: Int = 9, val actionLabel: String = "")

@Serializable
data class ReportBody(val occurredAt: String)

@Serializable
data class ReportResponse(
    val occurrenceId: String = "",
    val type: String = "",
    val status: String = "",
    val duplicate: Boolean = false,
)

@Serializable
data class AppInfo(
    val available: Boolean = false,
    val version: String? = null,
    val versionCode: Long? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val haUrl: String? = null,
    val apiUrl: String? = null,
)

@Serializable
data class RunResponse(val result: String = "", val reason: String? = null)

@Serializable
data class ApiErrorBody(val error: String = "", val message: String = "", val currentRevision: Int? = null)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Long = 1800,
    val tokenType: String = "Bearer",
)
