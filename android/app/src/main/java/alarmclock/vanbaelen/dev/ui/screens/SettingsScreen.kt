package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.BuildConfig
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.Routes
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.AppVersion
import alarmclock.vanbaelen.dev.core.UpdateBlock
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.update.UpdateState
import alarmclock.vanbaelen.dev.ui.AppIcons
import alarmclock.vanbaelen.dev.ui.AppType
import alarmclock.vanbaelen.dev.ui.Format
import alarmclock.vanbaelen.dev.ui.MainViewModel
import alarmclock.vanbaelen.dev.ui.tokens
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * App settings (docs/design.md, Settings): category cards with a search in portrait,
 * a category list beside the selected category's page in landscape.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.app
    val t = tokens
    val focus = LocalFocusManager.current
    val account by vm.account.collectAsState()
    val settings by vm.settings.collectAsState()
    val syncState by vm.syncState.collectAsState()
    val updateState by vm.updateState.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf<String?>(null) }
    var updateCheck by remember { mutableStateOf<UpdateCheck?>(null) }
    var pickEntity by remember { mutableStateOf<EntitySlot?>(null) }
    // Saveable so the open category, highlighted row and query outlive leaving the screen and back.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var highlight by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val landscape = isLandscape()

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

    fun pickSound() = soundPicker.launch(
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.defaultSoundUri?.let(Uri::parse)),
    )

    fun checkUpdate() {
        // AFR-UPD-01: check now instead of waiting for the next sync; installs when there is one.
        updateCheck = UpdateCheck.Checking
        app.scope.launch {
            val info = runCatching { app.syncer.checkUpdate() }.getOrNull()
            val available = info?.takeIf { AppVersion.updateAvailable(it, BuildConfig.VERSION_CODE.toLong()) }
            updateCheck = when {
                info == null -> UpdateCheck.Failed
                available == null -> UpdateCheck.UpToDate
                else -> UpdateCheck.Available(available.version ?: "")
            }
            if (available != null) app.updater.install(available)
        }
    }

    // Everything below is resolved while composing; the rows only carry plain strings and callbacks.
    val on = stringResource(R.string.state_on)
    val off = stringResource(R.string.off)
    val notSet = stringResource(R.string.not_set)
    val sound = soundName(context, settings.defaultSoundUri) ?: stringResource(R.string.sound_default)
    val from = Format.time(context, settings.nightStart)
    val until = Format.time(context, settings.nightEnd)
    val stateText = updateStateText(updateState)
    val updateHint = listOfNotNull(
        updateCheckText(updateCheck),
        stateText.takeIf { updateState != UpdateState.Idle && updateState != UpdateState.NeedsPermission },
    ).joinToString("\n").ifEmpty { null }

    val categories = listOf(
        SettingsCategory(
            id = "alarms",
            name = stringResource(R.string.nav_alarms),
            icon = AppIcons.bell,
            summary = if (settings.volumeRamp) stringResource(R.string.settings_summary_ramp, sound) else sound,
            intro = stringResource(R.string.settings_intro_alarms),
            groups = listOf(
                SettingsGroup(
                    stringResource(R.string.sound),
                    listOf(
                        SettingItem.Value(stringResource(R.string.default_sound), sound) { pickSound() },
                        SettingItem.Switch(
                            stringResource(R.string.volume_ramp),
                            stringResource(R.string.volume_ramp_hint),
                            settings.volumeRamp,
                            if (settings.volumeRamp) on else off,
                        ) { v -> update { it.copy(volumeRamp = v) } },
                    ),
                ),
            ),
        ),
        SettingsCategory(
            id = "night",
            name = stringResource(R.string.night_display),
            icon = AppIcons.moon,
            summary = if (settings.nightAutoStart) stringResource(R.string.settings_summary_night_auto, from, until) else stringResource(R.string.settings_summary_night_manual),
            intro = stringResource(R.string.settings_intro_night),
            groups = listOf(
                SettingsGroup(
                    stringResource(R.string.settings_group_starting),
                    listOf(
                        SettingItem.Switch(
                            stringResource(R.string.night_auto),
                            stringResource(R.string.night_auto_hint),
                            settings.nightAutoStart,
                            if (settings.nightAutoStart) on else off,
                        ) { v -> app.prefs.updateSettings { it.copy(nightAutoStart = v) } },
                        SettingItem.Value(stringResource(R.string.night_from), from) { editTime = "start" },
                        SettingItem.Value(stringResource(R.string.night_until), until) { editTime = "end" },
                    ),
                ),
                SettingsGroup(
                    stringResource(R.string.display_screen),
                    listOf(
                        SettingItem.Action(stringResource(R.string.night_open)) { nav.navigate(Routes.NIGHT) },
                        SettingItem.Value(
                            stringResource(R.string.checklist_settings),
                            when {
                                settings.checklistEntity == null -> notSet
                                !settings.checklistShow -> stringResource(R.string.checklist_hidden)
                                else -> stringResource(R.string.checklist_on, from)
                            },
                        ) { nav.navigate(Routes.CHECKLIST) },
                    ),
                ),
                SettingsGroup(
                    stringResource(R.string.settings_group_widgets),
                    EntitySlot.entries.map { slot ->
                        SettingItem.Value(stringResource(slot.title), slot.read(settings).joinToString(", ").ifEmpty { notSet }) { pickEntity = slot }
                    },
                    note = stringResource(R.string.widgets_entities_intro),
                ),
            ),
        ),
        SettingsCategory(
            id = "home",
            name = stringResource(R.string.home_assistant),
            icon = AppIcons.home,
            summary = when {
                account.needsLogin -> stringResource(R.string.login_again)
                else -> account.userName ?: stringResource(R.string.settings_summary_not_connected)
            },
            intro = stringResource(R.string.settings_intro_ha),
            groups = listOf(
                SettingsGroup(
                    stringResource(R.string.settings_group_connection),
                    listOfNotNull(
                        SettingItem.Info(stringResource(R.string.user), account.userName ?: "—"),
                        SettingItem.Info(stringResource(R.string.ha_url), account.haUrl ?: "—"),
                        SettingItem.Info(stringResource(R.string.api_url), account.apiUrl ?: "—"),
                        syncState.timeZone?.let { SettingItem.Info(stringResource(R.string.server_time_zone), it) },
                        syncState.lastSyncAt?.let { SettingItem.Info(stringResource(R.string.last_sync), Format.ago(context, it)) },
                    ),
                ),
                SettingsGroup(
                    stringResource(R.string.account),
                    listOf(
                        SettingItem.Action(stringResource(R.string.login_again)) { nav.navigate(Routes.SETUP) },
                        SettingItem.Action(stringResource(R.string.logout), danger = true) { confirmLogout = true },
                    ),
                ),
            ),
        ),
        SettingsCategory(
            id = "about",
            name = stringResource(R.string.settings_about_help),
            icon = AppIcons.info,
            summary = stringResource(R.string.settings_summary_version, BuildConfig.VERSION_NAME),
            intro = stringResource(R.string.settings_intro_about),
            groups = listOf(
                SettingsGroup(
                    stringResource(R.string.reliability),
                    listOf(
                        SettingItem.Value(stringResource(R.string.health_check), null, stringResource(R.string.health_check_hint)) { nav.navigate(Routes.HEALTH) },
                    ),
                ),
                SettingsGroup(
                    stringResource(R.string.about),
                    listOfNotNull(
                        SettingItem.Info(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                        SettingItem.Action(stringResource(R.string.check_update), updateHint) { checkUpdate() },
                        // Opens "Install unknown apps" for this app; tap Check for updates again afterwards.
                        if (updateState == UpdateState.NeedsPermission) {
                            SettingItem.Action(stringResource(R.string.allow), stateText) {
                                vm.resetUpdate()
                                runCatching { context.startActivity(app.updater.unknownSourcesIntent()) }
                            }
                        } else {
                            null
                        },
                        SettingItem.Info(stringResource(R.string.client_id), app.prefs.clientId),
                    ),
                ),
            ),
        ),
    )
    val hits = search(categories, query)

    fun open(id: String, row: String? = null) {
        selected = id
        highlight = row
        query = ""
        focus.clearFocus()
    }

    BackHandler(enabled = query.isNotEmpty() || (!landscape && selected != null)) {
        if (query.isNotEmpty()) {
            query = ""
        } else {
            selected = null
            highlight = null
        }
    }

    if (landscape) {
        val current = categories.firstOrNull { it.id == selected } ?: categories.first()
        Row(Modifier.fillMaxSize().background(t.bg)) {
            LandscapeRail(Routes.SETTINGS, nav)
            Column(
                Modifier
                    .width(270.dp)
                    .fillMaxHeight()
                    .drawBehind { drawLine(t.line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.settings),
                    style = AppType.bodyStrong.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = t.ink,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp).semantics { heading() },
                )
                SearchField(query, { query = it }, Modifier.padding(bottom = 6.dp))
                if (query.isNotBlank()) {
                    SearchResults(hits) { open(it.category.id, it.item.label) }
                } else {
                    categories.forEach { c -> CategoryItem(c, c.id == current.id) { open(c.id) } }
                }
            }
            key(current.id) {
                Rise(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))) {
                    CategoryPage(current, highlight, showTitle = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
    } else {
        val current = categories.firstOrNull { it.id == selected }
        Column(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (current == null) {
                Text(
                    stringResource(R.string.settings),
                    style = AppType.screenTitle,
                    color = t.ink,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
                )
                SearchField(query, { query = it }, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (query.isNotBlank()) {
                        SearchResults(hits) { open(it.category.id, it.item.label) }
                    } else {
                        // An odd last card spans both columns.
                        categories.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { c -> CategoryCard(c, Modifier.weight(1f).fillMaxHeight()) { open(c.id) } }
                            }
                        }
                    }
                }
            } else {
                Breadcrumb(current.name) {
                    selected = null
                    highlight = null
                }
                key(current.id) {
                    Rise(Modifier.weight(1f).fillMaxWidth()) {
                        CategoryPage(current, highlight, showTitle = false, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    editTime?.let { which ->
        val currentTime = if (which == "start") settings.nightStart else settings.nightEnd
        TimeDialog(
            initial = runCatching { LocalTime.parse(currentTime) }.getOrDefault(LocalTime.of(22, 0)),
            onDismiss = { editTime = null },
            onPick = { time ->
                val value = "%02d:%02d".format(time.hour, time.minute)
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

private class SettingsCategory(val id: String, val name: String, val icon: ImageVector, val summary: String, val intro: String, val groups: List<SettingsGroup>)

private class SettingsGroup(val title: String, val rows: List<SettingItem>, val note: String? = null)

/** One row of a category page; [value] is what the row and the search show. */
private sealed class SettingItem(val label: String, val value: String?, val sub: String?) {
    class Switch(label: String, sub: String?, val checked: Boolean, state: String, val onChange: (Boolean) -> Unit) : SettingItem(label, state, sub)
    class Value(label: String, value: String?, sub: String? = null, val onClick: () -> Unit) : SettingItem(label, value, sub)
    class Action(label: String, sub: String? = null, val danger: Boolean = false, val onClick: () -> Unit) : SettingItem(label, null, sub)
    class Info(label: String, value: String) : SettingItem(label, value, null)
}

private class SettingHit(val category: SettingsCategory, val group: SettingsGroup, val item: SettingItem)

private fun search(categories: List<SettingsCategory>, query: String): List<SettingHit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return categories.flatMap { c ->
        c.groups.flatMap { g ->
            g.rows
                .filter { r -> listOfNotNull(r.label, r.value, c.name, g.title).joinToString(" ").lowercase().contains(q) }
                .map { SettingHit(c, g, it) }
        }
    }
}

/** Result of Settings → Check for updates; its text is resolved while composing (configuration-aware). */
private sealed interface UpdateCheck {
    data object Checking : UpdateCheck
    data object Failed : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Available(val version: String) : UpdateCheck
}

@Composable
private fun updateCheckText(check: UpdateCheck?): String? = when (check) {
    null -> null
    UpdateCheck.Checking -> stringResource(R.string.checking_update)
    UpdateCheck.Failed -> stringResource(R.string.update_check_failed)
    UpdateCheck.UpToDate -> stringResource(R.string.up_to_date)
    is UpdateCheck.Available -> stringResource(R.string.update_available_text, check.version)
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
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = tokens
    val label = stringResource(R.string.search)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(t.soft).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AppIcons.search, null, tint = t.muted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.settings_search_hint), style = AppType.body, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                textStyle = AppType.body.copy(color = t.ink),
                singleLine = true,
                cursorBrush = SolidColor(t.accent),
            )
        }
        if (query.isNotEmpty()) RoundIconButton(AppIcons.close, stringResource(R.string.settings_search_clear), { onQuery("") }, size = 18.dp)
    }
}

/** "N settings found" and the matching rows; a tap opens the row's category with the row highlighted. */
@Composable
private fun SearchResults(hits: List<SettingHit>, onOpen: (SettingHit) -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (hits.isEmpty()) stringResource(R.string.settings_nothing_found) else pluralStringResource(R.plurals.settings_found, hits.size, hits.size),
            style = AppType.secondary,
            color = t.muted,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
        hits.forEach { hit ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(shape)
                    .background(t.surface)
                    .border(1.dp, t.line, shape)
                    .clickable(role = Role.Button) { onOpen(hit) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(hit.item.label, style = AppType.body, color = t.ink)
                    Text("${hit.category.name} › ${hit.group.title}", style = AppType.small, color = t.muted)
                }
                hit.item.value?.let {
                    Text(it, style = AppType.label, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/** Portrait home: a card with an accent icon tile, the name and a summary of the current values. */
@Composable
private fun CategoryCard(category: SettingsCategory, modifier: Modifier, onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .heightIn(min = 124.dp)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(t.accentSoft), contentAlignment = Alignment.Center) {
            Icon(category.icon, null, tint = t.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(category.name, style = AppType.bodyStrong, color = t.ink)
            Text(category.summary, style = AppType.small, color = t.muted)
        }
    }
}

/** Landscape: one category in the list beside the page. */
@Composable
private fun CategoryItem(category: SettingsCategory, selected: Boolean, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.soft else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(category.icon, null, tint = if (selected) t.accent else t.muted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(category.name, style = AppType.label, fontWeight = FontWeight.SemiBold, color = t.ink)
            Text(category.summary, style = AppType.small, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Portrait: "‹ Settings / Night display". */
@Composable
private fun Breadcrumb(name: String, onBack: () -> Unit) {
    val t = tokens
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onBack, role = Role.Button).heightIn(min = 44.dp).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.back, null, tint = t.muted, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.settings), style = AppType.body, color = t.muted)
        }
        Text("/", style = AppType.body, color = t.muted)
        Text(name, style = AppType.bodyStrong, color = t.ink, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

/** A category's intro and groups, scrolling; shared by portrait (under the breadcrumb) and landscape ([showTitle]). */
@Composable
private fun CategoryPage(category: SettingsCategory, highlight: String?, showTitle: Boolean, modifier: Modifier = Modifier) {
    val t = tokens
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = if (showTitle) 16.dp else 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showTitle) {
                Text(category.name, style = AppType.bodyStrong.copy(fontSize = 20.sp, lineHeight = 26.sp), color = t.ink, modifier = Modifier.semantics { heading() })
            }
            Text(category.intro, style = AppType.label, color = t.muted)
        }
        category.groups.forEach { group ->
            Column {
                Text(
                    group.title.uppercase(),
                    style = AppType.small.copy(letterSpacing = 0.08.em),
                    fontWeight = FontWeight.SemiBold,
                    color = t.muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).semantics { heading() },
                )
                Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.line, shape)) {
                    group.rows.forEachIndexed { i, item -> ItemRow(item, highlighted = item.label == highlight, divider = i > 0) }
                }
                group.note?.let { Text(it, style = AppType.small, color = t.muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp)) }
            }
        }
    }
}

/** A switch, value (chevron, opens a picker), action or info row; at least 52 high. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(item: SettingItem, highlighted: Boolean, divider: Boolean) {
    val t = tokens
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        withFrameNanos { } // wait until the page is laid out
        bring.bringIntoView()
    }
    val click = when (item) {
        is SettingItem.Switch -> Modifier.clickable(role = Role.Switch) { item.onChange(!item.checked) }
        is SettingItem.Value -> Modifier.clickable(role = Role.Button, onClick = item.onClick)
        is SettingItem.Action -> Modifier.clickable(role = Role.Button, onClick = item.onClick)
        is SettingItem.Info -> Modifier
    }
    val vertical = if (item is SettingItem.Switch) 2.dp else 8.dp
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .background(if (highlighted) t.accentSoft else Color.Transparent)
            .drawBehind { if (divider) drawLine(t.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .then(click)
            .heightIn(min = 52.dp)
            .padding(start = 14.dp, end = if (item is SettingItem.Switch) 4.dp else 12.dp, top = vertical, bottom = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val color = when {
                item is SettingItem.Action && item.danger -> t.danger
                item is SettingItem.Action -> t.accent
                else -> t.ink
            }
            Text(item.label, style = AppType.body, color = color)
            item.sub?.let { Text(it, style = AppType.small, color = t.muted) }
        }
        when (item) {
            is SettingItem.Switch -> AppSwitch(item.checked, item.onChange, item.label)
            is SettingItem.Value -> {
                item.value?.let {
                    Text(it, style = AppType.label, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Icon(AppIcons.chevron, null, tint = t.muted, modifier = Modifier.size(16.dp))
            }
            is SettingItem.Info -> Text(
                item.value.orEmpty(),
                style = AppType.label,
                color = t.muted,
                textAlign = TextAlign.End,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            is SettingItem.Action -> Unit
        }
    }
}
