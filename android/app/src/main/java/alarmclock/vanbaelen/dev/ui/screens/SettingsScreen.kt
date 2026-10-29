package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.BuildConfig
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.Routes
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.AppVersion
import alarmclock.vanbaelen.dev.core.UpdateBlock
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.update.UpdateState
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Account, night display, sound, health check, about (A5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.app
    val account by vm.account.collectAsState()
    val settings by vm.settings.collectAsState()
    val syncState by vm.syncState.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf<String?>(null) }
    var updateCheck by remember { mutableStateOf<String?>(null) }
    var pickEntity by remember { mutableStateOf<EntitySlot?>(null) }
    val update by vm.updateState.collectAsState()

    fun update(transform: (LocalSettings) -> LocalSettings) {
        app.prefs.updateSettings(transform)
        app.scope.launch { app.repository.rebuildEngine() }
    }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            update { it.copy(defaultSoundUri = uri?.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.account))
            Info(stringResource(R.string.user), account.userName ?: "—")
            Info(stringResource(R.string.ha_url), account.haUrl ?: "—")
            Info(stringResource(R.string.api_url), account.apiUrl ?: "—")
            syncState.timeZone?.let { Info(stringResource(R.string.server_time_zone), it) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.SETUP) }) { Text(stringResource(R.string.login_again)) }
                TextButton(onClick = { confirmLogout = true }) { Text(stringResource(R.string.logout)) }
            }
            HorizontalDivider()

            SectionTitle(stringResource(R.string.sound))
            Setting(
                stringResource(R.string.default_sound),
                soundName(context, settings.defaultSoundUri) ?: stringResource(R.string.sound_default),
            ) {
                soundPicker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.defaultSoundUri?.let(Uri::parse)),
                )
            }
            Toggle(stringResource(R.string.volume_ramp), stringResource(R.string.volume_ramp_hint), settings.volumeRamp) { on ->
                update { it.copy(volumeRamp = on) }
            }
            HorizontalDivider()

            SectionTitle(stringResource(R.string.night_display))
            Toggle(stringResource(R.string.night_auto), stringResource(R.string.night_auto_hint), settings.nightAutoStart) { on ->
                app.prefs.updateSettings { it.copy(nightAutoStart = on) }
            }
            Setting(stringResource(R.string.night_from), Format.time(context, settings.nightStart)) { editTime = "start" }
            Setting(stringResource(R.string.night_until), Format.time(context, settings.nightEnd)) { editTime = "end" }
            Setting(stringResource(R.string.night_open), null) { nav.navigate(Routes.NIGHT) }
            Hint(stringResource(R.string.widgets_entities_intro), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val notSet = stringResource(R.string.not_set)
            EntitySlot.entries.forEach { slot ->
                Setting(stringResource(slot.title), slot.read(settings).joinToString(", ").ifEmpty { notSet }) { pickEntity = slot }
            }
            HorizontalDivider()

            SectionTitle(stringResource(R.string.reliability))
            Setting(stringResource(R.string.health_check), stringResource(R.string.health_check_hint)) { nav.navigate(Routes.HEALTH) }
            HorizontalDivider()

            SectionTitle(stringResource(R.string.about))
            Info(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            // AFR-UPD-01: check now instead of waiting for the next sync; installs when there is one.
            Setting(stringResource(R.string.check_update), updateCheck) {
                updateCheck = context.getString(R.string.checking_update)
                app.scope.launch {
                    val info = runCatching { app.syncer.checkUpdate() }.getOrNull()
                    val available = info?.takeIf { AppVersion.updateAvailable(it, BuildConfig.VERSION_CODE.toLong()) }
                    updateCheck = when {
                        info == null -> context.getString(R.string.update_check_failed)
                        available == null -> context.getString(R.string.up_to_date)
                        else -> context.getString(R.string.update_available_text, available.version ?: "")
                    }
                    if (available != null) app.updater.install(available)
                }
            }
            when (update) {
                UpdateState.Idle -> Unit
                // Opens "Install unknown apps" for this app; tap Check for updates again afterwards.
                UpdateState.NeedsPermission -> Setting(stringResource(R.string.allow), updateStateText(update)) {
                    vm.resetUpdate()
                    runCatching { context.startActivity(app.updater.unknownSourcesIntent()) }
                }
                else -> Hint(updateStateText(update), modifier = Modifier.padding(horizontal = 16.dp))
            }
            syncState.lastSyncAt?.let { Info(stringResource(R.string.last_sync), Format.ago(context, it)) }
            Info(stringResource(R.string.client_id), app.prefs.clientId)
        }
    }

    editTime?.let { which ->
        val current = if (which == "start") settings.nightStart else settings.nightEnd
        TimeDialog(
            initial = runCatching { LocalTime.parse(current) }.getOrDefault(LocalTime.of(22, 0)),
            onDismiss = { editTime = null },
            onPick = { t ->
                val value = "%02d:%02d".format(t.hour, t.minute)
                app.prefs.updateSettings { if (which == "start") it.copy(nightStart = value) else it.copy(nightEnd = value) }
                editTime = null
            },
        )
    }

    pickEntity?.let { slot ->
        EntityPickerDialog(
            title = stringResource(slot.title),
            accepts = slot::accepts,
            selected = slot.read(settings),
            max = slot.max,
            onDismiss = { pickEntity = null },
            onDone = { ids ->
                app.prefs.updateSettings { slot.write(it, ids) }
                pickEntity = null
            },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.logout { nav.navigate(Routes.SETUP) { popUpTo(0) } }
                }) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun updateStateText(state: UpdateState): String = when (state) {
    is UpdateState.Downloading -> stringResource(R.string.update_downloading, ((state.progress ?: 0f) * 100).toInt())
    UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Blocked -> stringResource(if (state.reason == UpdateBlock.RINGING) R.string.update_blocked_ringing else R.string.update_blocked_soon)
    UpdateState.NeedsPermission -> stringResource(R.string.update_needs_permission)
    UpdateState.ChecksumMismatch -> stringResource(R.string.update_checksum)
    is UpdateState.Failed -> stringResource(R.string.update_failed)
    UpdateState.Idle -> ""
}

@Composable
private fun Info(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Setting(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Hint(it) }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
