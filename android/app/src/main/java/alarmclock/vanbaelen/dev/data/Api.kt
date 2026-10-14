package alarmclock.vanbaelen.dev.data

import alarmclock.vanbaelen.dev.core.ApiErrorBody
import alarmclock.vanbaelen.dev.core.ApiJson
import alarmclock.vanbaelen.dev.core.SetupLinks
import alarmclock.vanbaelen.dev.core.TokenPolicy
import alarmclock.vanbaelen.dev.core.TokenResponse
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Refreshing the token failed (revoked or expired refresh token): the user must log in again. */
class LoginRequiredException : IOException("Login required")

/** Non-2xx API answer; [body] is the parsed `{ error, message }` when present. */
class ApiException(val status: Int, val body: ApiErrorBody?) : IOException("HTTP $status ${body?.error ?: ""}".trim()) {
    val code: String get() = body?.error ?: ""
}

/**
 * Only https and only the configured HA and API hosts (ATR-SYN-03, ASEC-02). As a network
 * interceptor it also covers redirects, so a token never reaches another host.
 */
class HostGuard(private val prefs: AppPrefs) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val account = prefs.account.value
        val allowed = listOfNotNull(account.haUrl, account.apiUrl).mapNotNull { SetupLinks.host(it) }.toSet()
        if (!url.isHttps || url.host.lowercase() !in allowed) throw IOException("Blocked request to a host that is not configured")
        return chain.proceed(chain.request())
    }
}

fun createHttpClient(prefs: AppPrefs): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .addNetworkInterceptor(HostGuard(prefs))
    .build()

/** HA OAuth (IndieAuth style) login and token handling (AFR-LOG-01, ATR-SYN-04/05, ASEC-01). */
class AuthManager(private val http: OkHttpClient, private val prefs: AppPrefs, private val secure: SecureStore) {
    private val mutex = Mutex()

    fun authorizeUri(haUrl: String): Uri {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val state = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.oauthState = state
        return Uri.parse("$haUrl/auth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .build()
    }

    /** Handles `alarmactions://auth-callback?code=..&state=..`. */
    suspend fun completeLogin(callback: Uri) {
        val expected = prefs.oauthState
        val state = callback.getQueryParameter("state")
        if (expected == null || state != expected) throw IOException("state")
        prefs.oauthState = null
        callback.getQueryParameter("error")?.let { throw IOException(it) }
        val code = callback.getQueryParameter("code") ?: throw IOException("code")
        val tokens = tokenRequest(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .build(),
        ) ?: throw IOException("token")
        mutex.withLock { save(tokens) }
        prefs.updateAccount { it.copy(loggedIn = true, needsLogin = false) }
    }

    /** A valid access token, refreshed when it expires within a minute or after a 401. */
    suspend fun accessToken(rejected: String? = null): String = mutex.withLock {
        val current = secure.get(ACCESS)
        val expiresAt = secure.get(EXPIRES)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        if (current != null && current != rejected && expiresAt != null && !TokenPolicy.needsRefresh(expiresAt, Instant.now())) {
            return@withLock current
        }
        val refresh = secure.get(REFRESH)
        if (refresh == null) {
            markNeedsLogin()
            throw LoginRequiredException()
        }
        val tokens = tokenRequest(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", CLIENT_ID)
                .build(),
        )
        if (tokens == null) {
            markNeedsLogin()
            throw LoginRequiredException()
        }
        save(tokens)
        tokens.accessToken
    }

    fun markNeedsLogin() {
        if (prefs.account.value.loggedIn) prefs.updateAccount { it.copy(needsLogin = true) }
    }

    /** Best-effort token revocation on logout (ASEC-06). */
    suspend fun revoke() {
        val refresh = secure.get(REFRESH) ?: return
        val ha = prefs.account.value.haUrl ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(
                    Request.Builder().url("$ha/auth/revoke").post(FormBody.Builder().add("token", refresh).build()).build(),
                ).execute().close()
            }
        }
    }

    fun clear() = secure.clear()

    /** POST <ha>/auth/token; null when HA rejects the grant (4xx), IOException on network trouble. */
    private suspend fun tokenRequest(form: FormBody): TokenResponse? = withContext(Dispatchers.IO) {
        val ha = prefs.account.value.haUrl ?: throw IOException("No HA URL")
        val request = Request.Builder().url("$ha/auth/token").header("Accept", "application/json").post(form).build()
        http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> ApiJson.decodeFromString(TokenResponse.serializer(), response.body.string())
                response.code in 400..499 -> null
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    private fun save(tokens: TokenResponse) {
        secure.put(ACCESS, tokens.accessToken)
        tokens.refreshToken?.let { secure.put(REFRESH, it) }
        secure.put(EXPIRES, (System.currentTimeMillis() + tokens.expiresIn * 1000).toString())
    }

    companion object {
        const val CLIENT_ID = "https://bertvanbaelen.github.io/HA-Clock-Dashboard/"
        const val REDIRECT_URI = "alarmactions://auth-callback"
        private const val ACCESS = "access_token"
        private const val REFRESH = "refresh_token"
        private const val EXPIRES = "expires_at"
    }
}

/** Calls `<api_url>/api/v1/...` with the HA access token (ATR-SYN-05). */
class ApiClient(private val http: OkHttpClient, private val auth: AuthManager, private val prefs: AppPrefs) {
    private val json = "application/json".toMediaType()

    private fun url(path: String): String {
        val api = prefs.account.value.apiUrl ?: throw IOException("No API URL")
        return "$api/api/v1/$path"
    }

    /** Response text of a successful call; [body] null sends no body and no Content-Type. */
    suspend fun call(method: String, path: String, body: String? = null): String =
        withAuth { token -> execute(method, path, body, token) }.second

    suspend fun status(method: String, path: String, body: String? = null): Pair<Int, String> =
        withAuth(throwOnError = false) { token -> execute(method, path, body, token) }

    private suspend fun withAuth(throwOnError: Boolean = true, block: suspend (String) -> Pair<Int, String>): Pair<Int, String> {
        var token = auth.accessToken()
        var result = block(token)
        if (result.first == 401) {
            token = auth.accessToken(rejected = token)
            result = block(token)
            if (result.first == 401) {
                auth.markNeedsLogin()
                throw LoginRequiredException()
            }
        }
        if (throwOnError && result.first !in 200..299) {
            val error = runCatching { ApiJson.decodeFromString(ApiErrorBody.serializer(), result.second) }.getOrNull()
            throw ApiException(result.first, error)
        }
        return result
    }

    private suspend fun execute(method: String, path: String, body: String?, token: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val requestBody = when {
                body != null -> body.toRequestBody(json)
                method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            val request = Request.Builder()
                .url(url(path))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .method(method, requestBody)
                .build()
            http.newCall(request).execute().use { it.code to it.body.string() }
        }

    /** Streams a binary download (GET /app/apk) to [consumer]. */
    suspend fun <T> download(path: String, consumer: (InputStream, Long) -> T): T {
        suspend fun attempt(token: String): Pair<Int, T?> = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(path)).header("Authorization", "Bearer $token").get().build()
            http.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build()
                .newCall(request).execute().use { response ->
                    if (!response.isSuccessful) response.code to null
                    else response.code to consumer(response.body.byteStream(), response.body.contentLength())
                }
        }
        var token = auth.accessToken()
        var result = attempt(token)
        if (result.first == 401) {
            token = auth.accessToken(rejected = token)
            result = attempt(token)
        }
        return result.second ?: throw ApiException(result.first, null)
    }
}
