package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.alarm.Notifications
import alarmclock.vanbaelen.dev.app
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect

enum class CheckStatus { OK, PROBLEM, INFO }

data class HealthItem(val title: Int, val text: Int, val status: CheckStatus, val fix: (() -> Unit)?)

/**
 * First-run permission guide and health check (AFR-SET-04): everything an alarm needs to ring
 * reliably, with a button to fix each problem. Re-checked whenever the screen is resumed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(guide: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val items = remember(tick) { healthItems(context) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (guide) R.string.guide_title else R.string.health_check)) },
                navigationIcon = {
                    if (!guide) IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (guide) Text(stringResource(R.string.guide_intro), Modifier.padding(16.dp))
            items.forEach { HealthCard(it) }
            if (guide) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.continue_button)) }
            }
        }
    }
}

@Composable
private fun HealthCard(item: HealthItem) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            val (icon, tint) = when (item.status) {
                CheckStatus.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                CheckStatus.PROBLEM -> Icons.Default.Warning to MaterialTheme.colorScheme.error
                CheckStatus.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, null, tint = tint, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(item.text))
                if (item.fix != null && item.status != CheckStatus.OK) {
                    TextButton(onClick = item.fix) { Text(stringResource(R.string.fix)) }
                }
            }
        }
    }
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        context.startActivity(appSettings(context))
    }
}

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

@SuppressLint("BatteryLife")
private fun healthItems(context: Context, requestNotifications: () -> Unit): List<HealthItem> {
    val app = context.app
    val nm = context.getSystemService(NotificationManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val items = mutableListOf<HealthItem>()
    fun status(ok: Boolean) = if (ok) CheckStatus.OK else CheckStatus.PROBLEM

    items += HealthItem(R.string.check_exact, R.string.check_exact_text, status(app.engine.canScheduleExact())) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
        }
    }
    val canPost = Notifications.canPost(context)
    items += HealthItem(R.string.check_notifications, R.string.check_notifications_text, status(canPost)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications()
        } else {
            open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    val channel = nm.getNotificationChannel(Notifications.CHANNEL_ALARM)
    val channelOk = channel != null && channel.importance >= NotificationManager.IMPORTANCE_HIGH
    items += HealthItem(R.string.check_channel, R.string.check_channel_text, status(channelOk)) {
        open(
            context,
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_ALARM),
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        items += HealthItem(R.string.check_full_screen, R.string.check_full_screen_text, status(nm.canUseFullScreenIntent())) {
            open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()))
        }
    }
    items += HealthItem(R.string.check_battery, R.string.check_battery_text, status(pm.isIgnoringBatteryOptimizations(context.packageName))) {
        open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
    }
    val dndBlocksAll = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    items += HealthItem(R.string.check_dnd, R.string.check_dnd_text, if (dndBlocksAll) CheckStatus.PROBLEM else CheckStatus.INFO) {
        open(context, Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
    }
    items += HealthItem(
        R.string.check_install, R.string.check_install_text,
        if (context.packageManager.canRequestPackageInstalls()) CheckStatus.OK else CheckStatus.INFO,
    ) { open(context, app.updater.unknownSourcesIntent()) }
    return items
}
