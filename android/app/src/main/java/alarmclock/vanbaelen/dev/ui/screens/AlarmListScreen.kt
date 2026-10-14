package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.Routes
import alarmclock.vanbaelen.dev.core.ReportPolicy
import alarmclock.vanbaelen.dev.data.SyncProblem
import alarmclock.vanbaelen.dev.update.UpdateState
import alarmclock.vanbaelen.dev.core.UpdateBlock
import alarmclock.vanbaelen.dev.ui.AlarmItem
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.time.ZoneId

/** Alarm list with sync / update / problem banners (AFR-ALM-01, AFR-SYN-04). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val state by vm.syncState.collectAsState()
    val account by vm.account.collectAsState()
    val missed by vm.missed.collectAsState()
    val problems by vm.problems.collectAsState()
    val pendingEdits by vm.pendingEdits.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val update by vm.updateState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title)) },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::sync) { Icon(Icons.Default.Refresh, stringResource(R.string.sync_now)) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.night_display)) }, onClick = { menu = false; nav.navigate(Routes.NIGHT) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.health_check)) }, onClick = { menu = false; nav.navigate(Routes.HEALTH) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { menu = false; nav.navigate(Routes.SETTINGS) })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.editor()) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_alarm)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val away = ReportPolicy.isAway(state.timeZone, ZoneId.systemDefault().id) || state.serverAway
                if (account.needsLogin || state.problem == SyncProblem.LOGIN) {
                    Banner(stringResource(R.string.login_again_text), BannerKind.ERROR, stringResource(R.string.login_again)) { nav.navigate(Routes.SETUP) }
                }
                when (state.problem) {
                    SyncProblem.DASHBOARD_OLD -> Banner(stringResource(R.string.dashboard_too_old), BannerKind.ERROR)
                    SyncProblem.APP_OLD -> Banner(stringResource(R.string.app_too_old), BannerKind.ERROR)
                }
                vm.updateInfo(state)?.let { info ->
                    val text = when (val u = update) {
                        is UpdateState.Downloading -> stringResource(R.string.update_downloading, ((u.progress ?: 0f) * 100).toInt())
                        UpdateState.Installing -> stringResource(R.string.update_installing)
                        is UpdateState.Blocked -> stringResource(if (u.reason == UpdateBlock.RINGING) R.string.update_blocked_ringing else R.string.update_blocked_soon)
                        UpdateState.NeedsPermission -> stringResource(R.string.update_needs_permission)
                        UpdateState.ChecksumMismatch -> stringResource(R.string.update_checksum)
                        is UpdateState.Failed -> stringResource(R.string.update_failed)
                        UpdateState.Idle -> stringResource(R.string.update_available_text, info.version ?: "")
                    }
                    val action = when (update) {
                        UpdateState.NeedsPermission -> stringResource(R.string.allow)
                        is UpdateState.Downloading, UpdateState.Installing -> null
                        else -> stringResource(R.string.update)
                    }
                    Banner(text, BannerKind.INFO, action) {
                        if (update == UpdateState.NeedsPermission) {
                            vm.resetUpdate()
                            runCatching { context.startActivity((context.applicationContext as alarmclock.vanbaelen.dev.App).updater.unknownSourcesIntent()) }
                        } else {
                            vm.installUpdate(info)
                        }
                    }
                }
                if (away) Banner(stringResource(R.string.away_banner), BannerKind.WARNING)
                missed.lastOrNull()?.let { m ->
                    val text = if (missed.size == 1) {
                        stringResource(R.string.missed_one, m.label.ifBlank { stringResource(R.string.alarm) }, Format.next(context, m.ringAt))
                    } else {
                        pluralStringResource(R.plurals.missed_many, missed.size, missed.size)
                    }
                    Banner(text, BannerKind.WARNING, stringResource(R.string.close)) { vm.clearMissed() }
                }
                problems.firstOrNull()?.let { p ->
                    Banner(pluralStringResource(R.plurals.edits_problem, problems.size, problems.size), BannerKind.ERROR, stringResource(R.string.review)) {
                        nav.navigate(Routes.conflict(p.seq))
                    }
                }
                SyncStatus(state.lastSyncAt, state.problem, pendingEdits)
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_alarms),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            items(items, key = { it.alarm.id }) { item ->
                AlarmCard(item, vm, onClick = { nav.navigate(Routes.editor(item.alarm.id)) })
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun SyncStatus(lastSyncAt: Long?, problem: String?, pendingEdits: Int) {
    val context = LocalContext.current
    val last = lastSyncAt?.let { stringResource(R.string.last_synced, Format.ago(context, it)) } ?: stringResource(R.string.never_synced)
    val text = when (problem) {
        SyncProblem.OFFLINE -> stringResource(R.string.offline) + " · " + last
        SyncProblem.HA_OFFLINE -> stringResource(R.string.ha_offline)
        SyncProblem.ERROR -> stringResource(R.string.sync_failed) + " · " + last
        else -> last
    } + if (pendingEdits > 0) " · " + pluralStringResource(R.plurals.pending_edits, pendingEdits, pendingEdits) else ""
    Hint(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun AlarmCard(item: AlarmItem, vm: MainViewModel, onClick: () -> Unit) {
    val context = LocalContext.current
    val alarm = item.alarm
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Format.time(context, alarm.time),
                        fontSize = 40.sp,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val title = listOf(alarm.label, Format.days(context, alarm.days)).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = alarm.enabled, onCheckedChange = { vm.setEnabled(alarm, it) })
            }
            val next = item.snoozed ?: item.next
            val status = when {
                item.snoozed != null -> stringResource(R.string.snoozed_until, Format.next(context, item.snoozed.ringAt))
                !alarm.enabled -> stringResource(R.string.off)
                next != null -> stringResource(R.string.next_ring, Format.next(context, next.ringAt))
                else -> null
            }
            val flags = listOfNotNull(
                status,
                if (alarm.skipNext && alarm.repeating) stringResource(R.string.skipped) else null,
                if (alarm.actions.isNotEmpty()) context.resources.getQuantityString(R.plurals.action_count, alarm.actions.size, alarm.actions.size) else null,
                if (item.effective.conflict) stringResource(R.string.not_saved) else if (item.effective.pending) stringResource(R.string.not_synced) else null,
            )
            Hint(flags.joinToString(" · "), modifier = Modifier.padding(top = 4.dp))
            val soon = item.next?.takeIf { it.occurrenceId != null && it.ringAt - System.currentTimeMillis() in 0..3_600_000L }
            if ((alarm.enabled && alarm.repeating) || soon != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (soon != null) TextButton(onClick = { vm.dismissEarly(soon) }) { Text(stringResource(R.string.dismiss_now)) }
                    if (alarm.enabled && alarm.repeating) {
                        TextButton(onClick = { vm.setSkip(alarm, !alarm.skipNext) }) {
                            Text(stringResource(if (alarm.skipNext) R.string.undo_skip else R.string.skip_next))
                        }
                    }
                }
            }
        }
    }
}
