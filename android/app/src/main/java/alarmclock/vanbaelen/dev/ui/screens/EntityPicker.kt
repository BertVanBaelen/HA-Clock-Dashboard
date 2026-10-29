package alarmclock.vanbaelen.dev.ui.screens

import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.app
import alarmclock.vanbaelen.dev.core.HaState
import alarmclock.vanbaelen.dev.core.NightPlan
import alarmclock.vanbaelen.dev.data.LocalSettings
import alarmclock.vanbaelen.dev.data.Problem
import alarmclock.vanbaelen.dev.data.problemOf
import alarmclock.vanbaelen.dev.ui.Format
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The Home Assistant entities the night display widgets read, chosen in Settings. */
enum class EntitySlot(@StringRes val title: Int, val max: Int) {
    WEATHER(R.string.entity_weather, 1),
    CALENDAR(R.string.entity_calendar, 1),
    ROOM_TEMPERATURE(R.string.entity_room_temperature, 1),
    NIGHT_LIGHT(R.string.entity_night_light, 1),
    HOME(R.string.entity_home, NightPlan.HOME_ROWS);

    fun read(s: LocalSettings): List<String> = when (this) {
        WEATHER -> listOfNotNull(s.weatherEntity)
        CALENDAR -> listOfNotNull(s.calendarEntity)
        ROOM_TEMPERATURE -> listOfNotNull(s.roomTemperatureEntity)
        NIGHT_LIGHT -> listOfNotNull(s.nightLightEntity)
        HOME -> s.homeEntities
    }

    fun write(s: LocalSettings, ids: List<String>): LocalSettings = when (this) {
        WEATHER -> s.copy(weatherEntity = ids.firstOrNull())
        CALENDAR -> s.copy(calendarEntity = ids.firstOrNull())
        ROOM_TEMPERATURE -> s.copy(roomTemperatureEntity = ids.firstOrNull())
        NIGHT_LIGHT -> s.copy(nightLightEntity = ids.firstOrNull())
        HOME -> s.copy(homeEntities = ids.take(max))
    }

    fun accepts(e: HaState): Boolean = when (this) {
        WEATHER -> e.domain == "weather"
        CALENDAR -> e.domain == "calendar"
        ROOM_TEMPERATURE -> e.domain == "climate" || (e.domain == "sensor" && e.text("device_class") == "temperature")
        NIGHT_LIGHT -> e.domain in setOf("light", "switch", "input_boolean")
        HOME -> e.domain in setOf("light", "switch", "lock", "climate", "cover", "binary_sensor", "fan", "input_boolean", "sensor")
    }
}

/**
 * Picks [max] entities from Home Assistant (read with the user's login); one is picked with a
 * tap, more with check boxes and Done. Clear removes the choice.
 */
@Composable
fun EntityPickerDialog(
    title: String,
    accepts: (HaState) -> Boolean,
    selected: List<String>,
    max: Int,
    onDismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    var entities by remember { mutableStateOf<List<HaState>?>(null) }
    var problem by remember { mutableStateOf<Problem?>(null) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) {
        try {
            entities = context.app.night.entities().filter(accepts)
        } catch (e: Exception) {
            problem = problemOf(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (max > 1) Hint(stringResource(R.string.choose_up_to, max))
                val list = entities
                val failed = problem
                when {
                    failed != null -> Text(Format.problem(context, failed))
                    list == null -> Text(stringResource(R.string.loading))
                    else -> {
                        val q = query.trim().lowercase()
                        val shown = list.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.entityId.contains(q) }
                        if (shown.isEmpty()) Text(stringResource(R.string.no_entities))
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            shown.forEach { e ->
                                val on = e.entityId in chosen
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            when {
                                                max == 1 -> onDone(listOf(e.entityId))
                                                on -> chosen = chosen - e.entityId
                                                chosen.size < max -> chosen = chosen + e.entityId
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (max == 1) RadioButton(selected = on, onClick = null) else Checkbox(checked = on, onCheckedChange = null)
                                    Column(Modifier.padding(start = 10.dp)) {
                                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(e.entityId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (max > 1) TextButton(onClick = { onDone(chosen) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            Row {
                if (selected.isNotEmpty()) TextButton(onClick = { onDone(emptyList()) }) { Text(stringResource(R.string.clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
