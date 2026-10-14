package alarmclock.vanbaelen.dev.alarm

import alarmclock.vanbaelen.dev.MainActivity
import alarmclock.vanbaelen.dev.R
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_SNOOZE = "snooze"
    const val CHANNEL_STATUS = "status"
    const val ID_RINGING = 1
    const val ID_SNOOZED_BASE = 1000
    const val ID_MISSED = 2
    const val ID_LOGIN = 3
    const val ID_UPDATE = 4

    fun snoozedId(alarmId: String): Int = ID_SNOOZED_BASE + Math.floorMod(alarmId.hashCode(), 1000)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alarm_description)
            // The service plays the sound on the alarm stream and vibrates itself.
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val snooze = NotificationChannel(CHANNEL_SNOOZE, context.getString(R.string.channel_snooze), NotificationManager.IMPORTANCE_LOW)
        val status = NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannels(listOf(alarm, snooze, status))
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    fun notify(context: Context, id: Int, notification: Notification) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun status(context: Context, id: Int, title: String, text: String) {
        notify(
            context, id,
            NotificationCompat.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }
}
