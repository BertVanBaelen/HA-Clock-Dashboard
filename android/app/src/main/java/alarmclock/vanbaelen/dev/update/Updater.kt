package alarmclock.vanbaelen.dev.update

import alarmclock.vanbaelen.dev.App
import alarmclock.vanbaelen.dev.BuildConfig
import alarmclock.vanbaelen.dev.core.ApiJson
import alarmclock.vanbaelen.dev.core.AppInfo
import alarmclock.vanbaelen.dev.core.AppVersion
import alarmclock.vanbaelen.dev.core.UpdateBlock
import alarmclock.vanbaelen.dev.core.UpdatePolicy
import alarmclock.vanbaelen.dev.data.SyncStateRow
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val progress: Float?) : UpdateState
    data object Installing : UpdateState
    data class Blocked(val reason: UpdateBlock) : UpdateState
    data object NeedsPermission : UpdateState
    data object ChecksumMismatch : UpdateState
    data class Failed(val message: String?) : UpdateState
}

/**
 * In-app update (AFR-UPD-01, ATR-REL-04): download GET /app/apk, verify the SHA-256 from
 * GET /app/info and install with PackageInstaller. Never while ringing or shortly before an alarm.
 */
class Updater(private val app: App) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun availableInfo(row: SyncStateRow): AppInfo? = row.appInfoJson
        ?.let { runCatching { ApiJson.decodeFromString(AppInfo.serializer(), it) }.getOrNull() }
        ?.takeIf { AppVersion.updateAvailable(it, BuildConfig.VERSION_CODE.toLong()) }

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())

    suspend fun install(info: AppInfo) {
        if (_state.value is UpdateState.Downloading || _state.value == UpdateState.Installing) return
        val nextRing = app.engine.scheduled.value.minOfOrNull { it.ringAt }?.let(Instant::ofEpochMilli)
        UpdatePolicy.check(Instant.now(), app.engine.ringing.value != null, nextRing)?.let {
            _state.value = UpdateState.Blocked(it)
            return
        }
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        _state.value = UpdateState.Downloading(null)
        val file = File(app.cacheDir, "update.apk")
        try {
            val sha = app.api.download("app/apk") { input, length ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        total += n
                        if (length > 0) _state.value = UpdateState.Downloading(total.toFloat() / length)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!UpdatePolicy.checksumMatches(info.sha256, sha)) {
                file.delete()
                _state.value = UpdateState.ChecksumMismatch
                return
            }
            _state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { commit(file) }
        } catch (e: Exception) {
            Log.w(TAG, "Update failed: ${e.javaClass.simpleName}")
            file.delete()
            _state.value = UpdateState.Failed(e.message)
        }
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(app, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
        file.delete()
    }

    fun onResult(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> UpdateState.Idle
            else -> UpdateState.Failed(message)
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    companion object {
        private const val TAG = "Updater"
    }
}

/** PackageInstaller result; asks the user to confirm when the system requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.let {
                try {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w("InstallResult", "Cannot show the install confirmation", e)
                }
            }
        }
        (context.applicationContext as App).updater.onResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        const val ACTION = "alarmclock.vanbaelen.dev.INSTALL_RESULT"
    }
}
