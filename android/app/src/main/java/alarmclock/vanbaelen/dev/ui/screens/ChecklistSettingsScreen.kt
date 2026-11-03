package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.Checklist
import alarmclock.vanbaelen.dev.core.HaState
import alarmclock.vanbaelen.dev.core.TodoItem
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.data.Problem
import alarmclock.vanbaelen.dev.data.problemOf
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.AppType
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import alarmclock.vanbaelen.dev.ui.tokens
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.time.LocalTime

/**
 * Settings → Daily checklist (docs/design.md, Night display): the Home Assistant to-do list the
 * night display shows, per item what ticking it runs, and when the list shows and starts fresh.
 */
@Composable
fun ChecklistSettingsScreen(vm: MainViewModel, onBack: () -> Unit, onLogin: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val t = tokens
    val account by vm.account.collectAsState()
    val settings by vm.settings.collectAsState()
    var entities by remember { mutableStateOf<List<HaState>?>(null) }
    var items by remember { mutableStateOf<List<TodoItem>?>(null) }
    var problem by remember { mutableStateOf<Problem?>(null) }
    var itemsProblem by remember { mutableStateOf<Problem?>(null) }
    var pickFor by remember { mutableStateOf<TodoItem?>(null) }
    LaunchedEffect(Unit) {
        try {
            entities = app.night.entities()
        } catch (e: Exception) {
            problem = problemOf(e)
        }
    }
    LaunchedEffect(settings.checklistEntity) {
        items = null
        itemsProblem = null
        val entity = settings.checklistEntity ?: return@LaunchedEffect
        try {
            items = app.night.todoItems(entity)
        } catch (e: Exception) {
            itemsProblem = problemOf(e)
        }
    }
    fun update(transform: (LocalSettings) -> LocalSettings) = app.prefs.updateSettings(transform)
    val names = remember(entities) { entities.orEmpty().associate { it.entityId to it.name } }

    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onBack, role = Role.Button).heightIn(min = 44.dp).padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.back, null, tint = t.muted, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.settings), style = AppType.body, color = t.muted)
            }
            Text("/", style = AppType.body, color = t.muted)
            Text(stringResource(R.string.checklist_settings), style = AppType.bodyStrong, color = t.ink, modifier = Modifier.padding(horizontal = 10.dp))
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Where the lists come from.
            Card {
                Icon(AppIcons.home, null, tint = t.accent, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_assistant), style = AppType.body, fontWeight = FontWeight.Medium, color = t.ink)
                    val host = account.haUrl?.let { Uri.parse(it).host } ?: "—"
                    Text(
                        if (account.needsLogin) stringResource(R.string.checklist_ha_login, host) else stringResource(R.string.checklist_ha_connected, host),
                        style = AppType.small,
                        color = if (account.needsLogin) t.warn else t.muted,
                    )
                }
                Text(
                    stringResource(R.string.change),
                    style = AppType.label,
                    color = t.accent,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onLogin, role = Role.Button).heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }

            Column {
                Heading(stringResource(R.string.checklist_list))
                Text(stringResource(R.string.checklist_list_hint), style = AppType.secondary, color = t.muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                val lists = entities?.filter { it.domain == "todo" }
                when {
                    problem != null && lists == null -> Text(Format.problem(context, problem!!), style = AppType.secondary, color = t.danger)
                    lists == null -> Text(stringResource(R.string.loading), style = AppType.secondary, color = t.muted)
                    lists.isEmpty() -> Text(stringResource(R.string.checklist_no_lists), style = AppType.secondary, color = t.muted)
                    else -> Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        lists.forEach { list ->
                            ListChoice(list, selected = list.entityId == settings.checklistEntity) {
                                // Another list has other items: what they ran no longer applies.
                                if (list.entityId != settings.checklistEntity) update { it.copy(checklistEntity = list.entityId, checklistActions = emptyMap()) }
                            }
                        }
                    }
                }
            }

            settings.checklistEntity?.let { entity ->
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Heading(stringResource(R.string.checklist_items), Modifier.weight(1f))
                        Text(
                            stringResource(R.string.checklist_edit_in_ha),
                            style = AppType.secondary,
                            color = t.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) {
                                    val url = account.haUrl?.trimEnd('/')?.let { "$it/todo?entity_id=$entity" } ?: return@clickable
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                                .heightIn(min = 44.dp)
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                        )
                    }
                    Text(stringResource(R.string.checklist_items_hint), style = AppType.secondary, color = t.muted, modifier = Modifier.padding(bottom = 6.dp))
                    val list = items
                    when {
                        itemsProblem != null -> Text(Format.problem(context, itemsProblem!!), style = AppType.secondary, color = t.danger)
                        list == null -> Text(stringResource(R.string.loading), style = AppType.secondary, color = t.muted)
                        list.isEmpty() -> Text(stringResource(R.string.checklist_empty), style = AppType.secondary, color = t.muted)
                        else -> Column(Modifier.drawBehind { drawLine(t.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }) {
                            list.forEach { item ->
                                val runs = settings.checklistActions[item.uid]
                                ItemRow(item.summary, runs?.let { names[it] ?: it }) { pickFor = item }
                            }
                        }
                    }
                }
            }

            ToggleCard(
                stringResource(R.string.checklist_show),
                stringResource(R.string.checklist_show_hint, Format.time(context, settings.nightStart)),
                settings.checklistShow,
            ) { on -> update { it.copy(checklistShow = on) } }
            ToggleCard(
                stringResource(R.string.checklist_reset),
                stringResource(R.string.checklist_reset_hint, Format.time(context, LocalTime.of(Checklist.RESET_HOUR, 0))),
                settings.checklistReset,
            ) { on -> update { it.copy(checklistReset = on) } }
            ToggleCard(stringResource(R.string.checklist_sync), stringResource(R.string.checklist_sync_hint), settings.checklistSync) { on ->
                update { it.copy(checklistSync = on) }
            }
        }
    }

    pickFor?.let { item ->
        EntityPickerDialog(
            title = stringResource(R.string.checklist_pick_action, item.summary),
            accepts = { it.domain == "automation" || it.domain == "script" },
            selected = listOfNotNull(settings.checklistActions[item.uid]),
            max = 1,
            onDismiss = { pickFor = null },
            onDone = { ids ->
                update { s ->
                    val id = ids.firstOrNull()
                    s.copy(checklistActions = if (id == null) s.checklistActions - item.uid else s.checklistActions + (item.uid to id))
                }
                pickFor = null
            },
        )
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = AppType.small.copy(letterSpacing = 0.08.em), fontWeight = FontWeight.SemiBold, color = tokens.muted, modifier = modifier)
}

/** A `surface` card with a 1px `line` border, radius 16, its content in a row; [onClick] makes it a switch. */
@Composable
private fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Switch, onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** One of the to-do lists, a radio choice: its name and how many items are open. */
@Composable
private fun ListChoice(list: HaState, selected: Boolean, onPick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(14.dp)
    val border by animateColorAsState(if (selected) t.accent else t.line, tween(200), label = "listBorder")
    val open = list.state.toIntOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(if (selected) t.surface else Color.Transparent)
            .border(1.dp, border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).border(2.dp, if (selected) t.accent else t.muted, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(t.accent))
        }
        Column(Modifier.weight(1f)) {
            Text(list.name, style = AppType.body, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = if (open != null) pluralStringResource(R.plurals.checklist_open, open, open) + " · " + list.entityId else list.entityId
            Text(meta, style = AppType.small, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An item of the chosen list; tap to choose the automation or script that ticking it runs. */
@Composable
private fun ItemRow(name: String, runs: String?, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .drawBehind { drawLine(t.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .clickable(onClick = onClick, role = Role.Button),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(16.dp).border(1.5.dp, t.muted, RoundedCornerShape(5.dp)))
        Text(name, style = AppType.label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            runs?.let { stringResource(R.string.checklist_runs, it) } ?: stringResource(R.string.checklist_add_action),
            style = AppType.small,
            color = if (runs != null) t.muted else t.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A card with a title, a line of explanation and a switch; the whole card toggles. */
@Composable
private fun ToggleCard(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.heightIn(min = 60.dp), onClick = { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.body, fontWeight = FontWeight.Medium, color = tokens.ink)
            Text(text, style = AppType.small, color = tokens.muted)
        }
        AppSwitch(checked, onChange, title)
    }
}
