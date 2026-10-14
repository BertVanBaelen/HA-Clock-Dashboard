package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.PlannedRing
import alarmclock.vanbaelen.dev.core.ReportType
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri

/**
 * Plays the alarm (AFR-RNG-01..04): alarm audio stream, looping, optional volume ramp,
 * vibration, full-screen notification with Snooze and Stop, and a 10 minute limit.
 * Uses only the ring data from the intent / [EngineStore], never the network or Room.
 */
class RingingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var current: PlannedRing? = null
    private val waiting = ArrayDeque<PlannedRing>()
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var volume = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = app.engine
        when (intent?.action) {
            ACTION_SNOOZE -> snooze()
            ACTION_DISMISS -> dismiss()
            else -> {
                val ring = AlarmEngine.ringFrom(intent) ?: engine.ringing.value
                goForeground(ring ?: current)
                when {
                    ring == null -> if (current == null) stopRinging()
                    current == null -> start(ring)
                    current?.alarmId == ring.alarmId -> Unit
                    waiting.none { it.alarmId == ring.alarmId } -> waiting.addLast(ring)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(ring: PlannedRing?) {
        val notification = notification(ring)
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_RINGING, notification, types)
        } catch (e: Exception) {
            Log.w(TAG, "System-exempted foreground service refused, using media playback", e)
            ServiceCompat.startForeground(this, Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
    }

    private fun start(ring: PlannedRing) {
        current = ring
        app.engine.setRinging(ring)
        Notifications.cancel(this, Notifications.snoozedId(ring.alarmId))
        acquireWakeLock()
        goForeground(ring)
        playSound(ring)
        vibrate()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_RINGING_MS)
        try {
            startActivity(RingingActivity.intent(this))
        } catch (e: Exception) {
            // Background activity starts are blocked on newer Android; the full-screen intent shows it.
            Log.d(TAG, "Full-screen intent will show the alarm")
        }
    }

    private val timeout = Runnable {
        current?.let { ReportQueue.enqueue(this, it, ReportType.TIMED_OUT) }
        next()
    }

    private fun snooze() {
        val ring = current ?: app.engine.ringing.value ?: return next()
        val minutes = ring.snoozeMinutes.coerceIn(1, 60)
        ReportQueue.enqueue(this, ring, ReportType.SNOOZED)
        val snoozed = app.engine.snooze(ring, minutes)
        Notifications.notify(
            this, Notifications.snoozedId(ring.alarmId),
            NotificationCompat.Builder(this, Notifications.CHANNEL_SNOOZE)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(getString(R.string.snoozed_title, ring.label.ifBlank { getString(R.string.alarm) }))
                .setContentText(getString(R.string.snoozed_until, timeText(snoozed.ringAt)))
                .setOngoing(true)
                .setContentIntent(Notifications.openAppIntent(this))
                .addAction(0, getString(R.string.stop), dismissSnoozedIntent(this, snoozed))
                .build(),
        )
        next()
    }

    private fun dismiss() {
        val ring = current ?: app.engine.ringing.value ?: return next()
        ReportQueue.enqueue(this, ring, ReportType.DISMISSED)
        app.engine.cancelSnooze(ring.alarmId)
        next()
    }

    /** Stops the current ring and starts the next waiting one, or stops the service. */
    private fun next() {
        stopSound()
        handler.removeCallbacks(timeout)
        current = null
        val now = System.currentTimeMillis()
        while (waiting.isNotEmpty()) {
            val ring = waiting.removeFirst()
            if (ring.ringAt + MAX_RINGING_MS > now) return start(ring)
            ReportQueue.enqueue(this, ring, ReportType.TIMED_OUT)
        }
        stopRinging()
    }

    private fun stopRinging() {
        stopSound()
        app.engine.setRinging(null)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmActions:ringing").apply {
            acquire(MAX_RINGING_MS + 60_000)
        }
    }

    private fun playSound(ring: PlannedRing) {
        stopSound()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = listOfNotNull(
            ring.soundUri?.toUri(),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        volume = if (ring.volumeRamp) RAMP_START else 1f
        for (uri in candidates) {
            val mp = tryPlayer(uri, attributes) ?: continue
            player = mp
            if (ring.volumeRamp) handler.postDelayed(ramp, RAMP_STEP_MS)
            return
        }
        // No playable sound (e.g. media not readable before the first unlock): beep on the alarm stream.
        Log.w(TAG, "No playable alarm sound, using a tone")
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        handler.post(beep)
    }

    private fun tryPlayer(uri: Uri, attributes: AudioAttributes): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play alarm sound", e)
            mp.release()
            null
        }
    }

    private val ramp = object : Runnable {
        override fun run() {
            val mp = player ?: return
            volume = (volume + (1f - RAMP_START) * RAMP_STEP_MS / RAMP_MS).coerceAtMost(1f)
            runCatching { mp.setVolume(volume, volume) }
            if (volume < 1f) handler.postDelayed(this, RAMP_STEP_MS)
        }
    }

    private val beep = object : Runnable {
        override fun run() {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
            handler.postDelayed(this, 2000)
        }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (v == null || !v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun stopSound() {
        handler.removeCallbacks(ramp)
        handler.removeCallbacks(beep)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        tone?.release()
        tone = null
        vibrator?.cancel()
        vibrator = null
    }

    override fun onDestroy() {
        stopSound()
        handler.removeCallbacksAndMessages(null)
        wakeLock?.takeIf { it.isHeld }?.release()
        if (current != null) app.engine.setRinging(null)
        super.onDestroy()
    }

    private fun notification(ring: PlannedRing?): Notification {
        val label = ring?.label?.ifBlank { null } ?: getString(R.string.alarm)
        val fullScreen = PendingIntent.getActivity(
            this, 0, RingingActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(label)
            .setContentText(ring?.time ?: "")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, getString(R.string.snooze), serviceIntent(this, ACTION_SNOOZE))
            .addAction(0, getString(R.string.stop), serviceIntent(this, ACTION_DISMISS))
            .build()
    }

    private fun timeText(epochMs: Long): String =
        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(epochMs))

    companion object {
        private const val TAG = "RingingService"
        const val ACTION_START = "alarmclock.vanbaelen.dev.RING_START"
        const val ACTION_SNOOZE = "alarmclock.vanbaelen.dev.RING_SNOOZE"
        const val ACTION_DISMISS = "alarmclock.vanbaelen.dev.RING_DISMISS"
        const val MAX_RINGING_MS = 10 * 60_000L
        private const val RAMP_START = 0.1f
        private const val RAMP_MS = 60_000L
        private const val RAMP_STEP_MS = 2_000L

        fun serviceIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(), Intent(context, RingingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Sends Snooze / Stop from the ringing screen. */
        fun send(context: Context, action: String) {
            context.startService(Intent(context, RingingService::class.java).setAction(action))
        }

        fun dismissSnoozedIntent(context: Context, ring: PlannedRing): PendingIntent = PendingIntent.getBroadcast(
            context, Notifications.snoozedId(ring.alarmId),
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DISMISS_SNOOZED)
                .setData("alarmactions-snooze:${ring.alarmId}".toUri())
                .putExtra(AlarmEngine.EXTRA_RING, AlarmEngine.ringExtra(ring)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
