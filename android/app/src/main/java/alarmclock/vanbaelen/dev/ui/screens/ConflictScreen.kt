package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.Alarm
import alarmclock.vanbaelen.dev.core.AlarmInput
import alarmclock.vanbaelen.dev.core.ApiJson
import alarmclock.vanbaelen.dev.core.EditOp
import alarmclock.vanbaelen.dev.core.EditState
import alarmclock.vanbaelen.dev.core.Weekday
import alarmclock.vanbaelen.dev.data.PendingEditRow
import alarmclock.vanbaelen.dev.ui.Format
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A queued offline edit the server refused (409 conflict, deleted, or invalid) (AFR-SYN-02). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(seq: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val row by produceState<PendingEditRow?>(null, seq) { value = context.app.repository.edit(seq) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_title)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) } },
            )
        },
    ) { padding ->
        val edit = row
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (edit == null) {
                Text(stringResource(R.string.loading))
                return@Column
            }
            val server = edit.serverJson?.let { runCatching { ApiJson.decodeFromString(Alarm.serializer(), it) }.getOrNull() }
            val mine = edit.inputJson?.let { runCatching { ApiJson.decodeFromString(AlarmInput.serializer(), it) }.getOrNull() }
            val rejected = edit.state == EditState.REJECTED.name
            Text(
                when {
                    rejected -> stringResource(R.string.edit_rejected, edit.message ?: "")
                    server == null -> stringResource(R.string.conflict_deleted)
                    else -> stringResource(R.string.conflict_changed)
                },
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.conflict_mine), style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            edit.op == EditOp.DELETE.name -> stringResource(R.string.conflict_mine_delete)
                            mine != null -> describe(context, mine.time, mine.label, mine.repeatDays, mine.enabled, mine.actions.size)
                            else -> edit.op
                        },
                    )
                }
            }
            if (server != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.conflict_server), style = MaterialTheme.typography.titleSmall)
                        Text(describe(context, server.time, server.label, server.repeatDays, server.enabled, server.actions.size))
                    }
                }
            }
            if (!rejected) {
                Button(
                    onClick = { scope.launch { context.app.repository.keepMine(seq); onDone() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.keep_mine)) }
            }
            OutlinedButton(
                onClick = { scope.launch { context.app.repository.useServer(seq); onDone() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (rejected) R.string.discard_change else R.string.use_server)) }
        }
    }
}

private fun describe(context: Context, time: String, label: String, days: List<String>, enabled: Boolean, actions: Int): String =
    listOfNotNull(
        Format.time(context, time),
        label.ifBlank { null },
        Format.days(context, Weekday.parse(days)),
        if (enabled) null else context.getString(R.string.off),
        context.resources.getQuantityString(R.plurals.action_count, actions, actions),
    ).joinToString(" · ")
