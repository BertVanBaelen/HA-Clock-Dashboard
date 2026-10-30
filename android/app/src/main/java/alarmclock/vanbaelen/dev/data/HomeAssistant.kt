package alarmclock.vanbaelen.dev.data

import alarmclock.vanbaelen.dev.core.CalendarEvent
import alarmclock.vanbaelen.dev.core.Forecast
import alarmclock.vanbaelen.dev.core.HaJson
import alarmclock.vanbaelen.dev.core.HaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/**
 * Home Assistant's own REST API at the HA URL, with the user's login, for the night display
 * widgets (weather, calendar, home). The add-on API is not involved, so this works wherever the
 * HA URL is reachable; [HostGuard] keeps the token on the configured hosts.
 */
class HomeAssistant(private val http: OkHttpClient, private val auth: AuthManager, private val prefs: AppPrefs) {
    private val json = "application/json".toMediaType()

    private fun api(path: String): HttpUrl.Builder {
        val ha = prefs.account.value.haUrl ?: throw IOException("No Home Assistant URL")
        return ha.toHttpUrl().newBuilder().addPathSegments("api/$path")
    }

    suspend fun states(): List<HaState> =
        HaJson.decodeFromString(ListSerializer(HaState.serializer()), send("GET", api("states").build()))

    /** One entity; null when it does not exist (any more). */
    suspend fun state(entityId: String): HaState? = try {
        HaJson.decodeFromString(HaState.serializer(), send("GET", api("states").addPathSegment(entityId).build()))
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    /** `weather.get_forecasts` of one entity; [type] is `daily` or `hourly`. */
    suspend fun forecast(entityId: String, type: String): List<Forecast> {
        val url = api("services/weather/get_forecasts").addQueryParameter("return_response", null).build()
        val body = buildJsonObject {
            put("entity_id", entityId)
            put("type", type)
        }
        val root = HaJson.parseToJsonElement(send("POST", url, body.toString())) as? JsonObject ?: return emptyList()
        val response = (root["service_response"] as? JsonObject)?.get(entityId) as? JsonObject ?: return emptyList()
        val list = response["forecast"] ?: return emptyList()
        return HaJson.decodeFromJsonElement(ListSerializer(Forecast.serializer()), list)
    }

    /** Events of a calendar entity between [start] and [end]. */
    suspend fun events(entityId: String, start: Instant, end: Instant): List<CalendarEvent> {
        val url = api("calendars").addPathSegment(entityId)
            .addQueryParameter("start", start.toString())
            .addQueryParameter("end", end.toString())
            .build()
        return HaJson.decodeFromString(ListSerializer(CalendarEvent.serializer()), send("GET", url))
    }

    /** Calls `<domain>.<service>` for one entity, e.g. `light.turn_on`, with service [data] such as `brightness_pct`. */
    suspend fun call(domain: String, service: String, entityId: String, data: Map<String, Number> = emptyMap()) =
        call(domain, service, listOf(entityId), data)

    /** Calls `<domain>.<service>` for several entities at once. */
    suspend fun call(domain: String, service: String, entityIds: List<String>, data: Map<String, Number> = emptyMap()) {
        val body = buildJsonObject {
            put("entity_id", JsonArray(entityIds.map(::JsonPrimitive)))
            data.forEach { (key, value) -> put(key, value) }
        }
        send("POST", api("services").addPathSegment(domain).addPathSegment(service).build(), body.toString())
    }

    private suspend fun send(method: String, url: HttpUrl, body: String? = null): String {
        var token = auth.accessToken()
        var result = execute(method, url, body, token)
        if (result.first == 401) {
            token = auth.accessToken(rejected = token)
            result = execute(method, url, body, token)
            if (result.first == 401) throw LoginRequiredException()
        }
        if (result.first !in 200..299) throw ApiException(result.first, null)
        return result.second
    }

    private suspend fun execute(method: String, url: HttpUrl, body: String?, token: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .method(method, body?.toRequestBody(json))
                .build()
            http.newCall(request).execute().use { it.code to it.body.string() }
        }
}
