package alarmclock.vanbaelen.dev.core

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Limits shared with the server (alarm_actions/shared LIMITS). */
object Limits {
    const val LABEL_LENGTH = 100
    const val ACTIONS_PER_ALARM = 20
    const val OFFSET_MIN = 1
    const val OFFSET_MAX = 720
    const val DELAY_MIN = 0
    const val DELAY_MAX = 120
    const val SNOOZE_MIN = 1
    const val SNOOZE_MAX = 30
}

/** Editable copy of an action; [key] is a client-only key, [id] is null for new ones. */
data class DraftAction(
    val key: String = newKey(),
    val id: String? = null,
    val scriptEntityId: String,
    val timing: Timing,
    val offsetMinutes: Int?,
    val delayMinutes: Int?,
    val enabled: Boolean = true,
)

/** Editable copy of an alarm, mirroring alarm_actions/web/src/draft.ts. */
data class Draft(
    val id: String? = null,
    val revision: Int? = null,
    val skipNext: Boolean = false,
    val label: String = "",
    val time: String = "07:00",
    val repeatDays: List<Weekday> = emptyList(),
    val enabled: Boolean = true,
    /** Null means "use the default". */
    val snoozeMinutes: Int? = null,
    val actions: List<DraftAction> = emptyList(),
)

enum class DraftError { INVALID_TIME, CHOOSE_SCRIPT, MINUTES_RANGE, DELAY_RANGE, DUPLICATE_ACTION, SNOOZE_RANGE, LABEL_LENGTH, ACTION_LIMIT }

private fun newKey(): String = UUID.randomUUID().toString()

object Drafts {
    /** Key used in validation results for errors that belong to the alarm itself. */
    const val ALARM_KEY = ""

    fun empty(): Draft = Draft()

    fun fromAlarm(alarm: Alarm): Draft = Draft(
        id = alarm.id,
        revision = alarm.revision,
        skipNext = alarm.skipNext,
        label = alarm.label,
        time = alarm.time,
        repeatDays = alarm.days,
        enabled = alarm.enabled,
        snoozeMinutes = alarm.snoozeMinutes,
        actions = alarm.actions.map {
            DraftAction(
                id = it.id,
                scriptEntityId = it.scriptEntityId,
                timing = Timing.fromApi(it.timing),
                offsetMinutes = it.offsetMinutes,
                delayMinutes = it.delayMinutes,
                enabled = it.enabled,
            )
        },
    )

    /** Opening an alarm to edit turns it on, so saving activates it (same as the panel). */
    fun edit(alarm: Alarm): Draft = fromAlarm(alarm).copy(enabled = true)

    /** A new, unsaved copy: no ids, so the server creates new ones. */
    fun copy(draft: Draft): Draft = draft.copy(
        id = null,
        revision = null,
        skipNext = false,
        enabled = true,
        actions = draft.actions.map { it.copy(id = null, key = newKey()) },
    )

    fun newAction(script: String): DraftAction =
        DraftAction(scriptEntityId = script, timing = Timing.BEFORE, offsetMinutes = 10, delayMinutes = null)

    /** Changing the timing resets the fields that do not apply to it. */
    fun withTiming(action: DraftAction, timing: Timing): DraftAction = action.copy(
        timing = timing,
        offsetMinutes = if (timing.needsOffset) action.offsetMinutes ?: 10 else null,
        delayMinutes = if (timing == Timing.AFTER_DISMISS) action.delayMinutes ?: 0 else null,
    )

    fun toggleDay(draft: Draft, day: Weekday): Draft {
        val days = if (day in draft.repeatDays) draft.repeatDays - day else draft.repeatDays + day
        return draft.copy(repeatDays = Weekday.entries.filter { it in days })
    }

    /** Request body for POST (create) or PUT (update, with revision). */
    fun toInput(draft: Draft, revision: Int? = draft.revision): AlarmInput = AlarmInput(
        label = draft.label.trim(),
        time = draft.time,
        repeatDays = draft.repeatDays.map { it.api },
        enabled = draft.enabled,
        snoozeMinutes = draft.snoozeMinutes,
        actions = draft.actions.map {
            AlarmActionInput(
                id = it.id,
                scriptEntityId = it.scriptEntityId,
                timing = it.timing.api,
                offsetMinutes = it.offsetMinutes,
                delayMinutes = it.delayMinutes,
                enabled = it.enabled,
            )
        },
        revision = revision,
    )

    /** Body to toggle an alarm from the list: the full alarm with only `enabled` changed. */
    fun toggleInput(alarm: Alarm, enabled: Boolean): AlarmInput = toInput(fromAlarm(alarm).copy(enabled = enabled))

    /** Problems that block saving, keyed by action key ([ALARM_KEY] for the alarm itself). */
    fun validate(draft: Draft): Map<String, DraftError> {
        val errors = LinkedHashMap<String, DraftError>()
        if (!Schedule.isValidTime(draft.time)) errors[ALARM_KEY] = DraftError.INVALID_TIME
        else if (draft.label.trim().length > Limits.LABEL_LENGTH) errors[ALARM_KEY] = DraftError.LABEL_LENGTH
        else if (draft.snoozeMinutes != null && draft.snoozeMinutes !in Limits.SNOOZE_MIN..Limits.SNOOZE_MAX) {
            errors[ALARM_KEY] = DraftError.SNOOZE_RANGE
        } else if (draft.actions.size > Limits.ACTIONS_PER_ALARM) errors[ALARM_KEY] = DraftError.ACTION_LIMIT
        val seen = HashSet<String>()
        for (a in draft.actions) {
            if (a.scriptEntityId.isEmpty()) {
                errors[a.key] = DraftError.CHOOSE_SCRIPT
                continue
            }
            val offset = a.offsetMinutes
            if ((a.timing.needsOffset && offset == null) || (offset != null && offset !in Limits.OFFSET_MIN..Limits.OFFSET_MAX)) {
                errors[a.key] = DraftError.MINUTES_RANGE
                continue
            }
            val delay = a.delayMinutes
            if (delay != null && delay !in Limits.DELAY_MIN..Limits.DELAY_MAX) {
                errors[a.key] = DraftError.DELAY_RANGE
                continue
            }
            val sig = listOf(a.scriptEntityId, a.timing.api, offset, delay).joinToString("|")
            if (!seen.add(sig)) errors[a.key] = DraftError.DUPLICATE_ACTION
        }
        return errors
    }

    /**
     * Enabled `before` actions whose run time for the next occurrence is already past (AFR-ALM-03,
     * FR-EXE-09). The server decides at save time; this is the early warning in the editor.
     */
    fun missedBeforeActions(draft: Draft, timeZone: String?, now: Instant): List<DraftAction> {
        if (timeZone == null || !draft.enabled || !Schedule.isValidTime(draft.time)) return emptyList()
        val next = try {
            Schedule.nextOccurrence(draft.time, draft.repeatDays, draft.skipNext, ZoneId.of(timeZone), now)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return draft.actions.filter { a ->
            if (!a.enabled || a.timing != Timing.BEFORE || a.offsetMinutes == null || a.offsetMinutes == 0) return@filter false
            val at = Schedule.plannedRunAt(next, a.timing, a.offsetMinutes)
            at != null && !at.isAfter(now)
        }
    }

    /** A local alarm built from a draft, used for offline-created and offline-edited alarms. */
    fun applyTo(base: Alarm?, id: String, input: AlarmInput): Alarm = Alarm(
        id = id,
        ownerUserId = base?.ownerUserId ?: "",
        ownerName = base?.ownerName,
        label = input.label,
        time = input.time,
        repeatDays = input.repeatDays,
        enabled = input.enabled,
        skipNext = if (input.repeatDays.isEmpty()) false else base?.skipNext ?: false,
        snoozeMinutes = input.snoozeMinutes,
        actions = input.actions.map {
            AlarmAction(
                id = it.id ?: "",
                scriptEntityId = it.scriptEntityId,
                timing = it.timing,
                offsetMinutes = it.offsetMinutes,
                delayMinutes = it.delayMinutes,
                enabled = it.enabled,
            )
        },
        revision = base?.revision ?: 0,
        changeSeq = base?.changeSeq ?: 0,
        createdAt = base?.createdAt,
        updatedAt = base?.updatedAt,
    )
}
