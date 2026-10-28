package alarmclock.vanbaelen.dev.data

import alarmclock.vanbaelen.dev.App
import alarmclock.vanbaelen.dev.R
import alarmclock.vanbaelen.dev.alarm.Notifications
import alarmclock.vanbaelen.dev.alarm.SkipMark
import alarmclock.vanbaelen.dev.core.ActionsResponse
import alarmclock.vanbaelen.dev.core.Alarm
import alarmclock.vanbaelen.dev.core.AlarmInput
import alarmclock.vanbaelen.dev.core.AlarmSaveResponse
import alarmclock.vanbaelen.dev.core.AlarmWarning
import alarmclock.vanbaelen.dev.core.ApiJson
import alarmclock.vanbaelen.dev.core.Draft
import alarmclock.vanbaelen.dev.core.Drafts
import alarmclock.vanbaelen.dev.core.EditOp
import alarmclock.vanbaelen.dev.core.EditState
import alarmclock.vanbaelen.dev.core.EffectiveAlarm
import alarmclock.vanbaelen.dev.core.PendingEdit
import alarmclock.vanbaelen.dev.core.PendingEdits
import alarmclock.vanbaelen.dev.core.RingSpec
import alarmclock.vanbaelen.dev.core.RunResponse
import alarmclock.vanbaelen.dev.core.Schedule
import alarmclock.vanbaelen.dev.core.ServerSettings
import alarmclock.vanbaelen.dev.work.ReportWorker
import alarmclock.vanbaelen.dev.work.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** Why an API call failed, mapped to a message by the UI. */
enum class Problem { OFFLINE, LOGIN, HA_OFFLINE, CONFLICT, FORBIDDEN, NOT_FOUND, REJECTED, ERROR }

sealed interface SaveResult {
    data class Saved(val alarm: Alarm, val warnings: List<AlarmWarning>) : SaveResult
    data class Queued(val alarmId: String) : SaveResult
    data class Failed(val problem: Problem, val message: String? = null) : SaveResult
}

fun problemOf(e: Throwable): Problem = when (e) {
    is LoginRequiredException -> Problem.LOGIN
    is ApiException -> when (e.status) {
        409 -> Problem.CONFLICT
        403 -> Problem.FORBIDDEN
        404 -> Problem.NOT_FOUND
        400, 422 -> Problem.REJECTED
        503 -> Problem.HA_OFFLINE
        else -> Problem.ERROR
    }
    is IOException -> Problem.OFFLINE
    else -> Problem.ERROR
}

/** Failures after which an edit is queued and sent later instead of being shown as an error. */
private fun queueable(e: Throwable): Boolean = when (e) {
    is LoginRequiredException -> true
    is ApiException -> e.status == 503 || e.status == 429 || e.status >= 500
    is IOException -> true
    else -> false
}

fun PendingEditRow.toEdit() = PendingEdit(
    seq = seq,
    alarmId = alarmId,
    op = EditOp.valueOf(op),
    input = inputJson?.let { ApiJson.decodeFromString(AlarmInput.serializer(), it) },
    baseRevision = baseRevision,
    state = EditState.valueOf(state),
    message = message,
)

fun PendingEdit.toRow(serverJson: String? = null) = PendingEditRow(
    seq = seq,
    alarmId = alarmId,
    op = op.name,
    inputJson = input?.let { ApiJson.encodeToString(AlarmInput.serializer(), it) },
    baseRevision = baseRevision,
    state = state.name,
    message = message,
    serverJson = serverJson,
)

fun AlarmRow.toAlarm(): Alarm = ApiJson.decodeFromString(Alarm.serializer(), json)
fun Alarm.toRow() = AlarmRow(id, ApiJson.encodeToString(Alarm.serializer(), this), changeSeq)

/** Local copy of the user's alarms and the operations on it (online first, queued offline). */
class Repository(private val app: App) {
    private val dao get() = app.db.dao()
    private val editMutex = Mutex()

    val alarms: Flow<List<EffectiveAlarm>> = combine(app.db.dao().alarmsFlow(), app.db.dao().editsFlow()) { rows, edits ->
        PendingEdits.effective(rows.map { it.toAlarm() }, edits.map { it.toEdit() })
    }
    val syncState: Flow<SyncStateRow> = app.db.dao().syncStateFlow().map { it ?: SyncStateRow() }
    val edits: Flow<List<PendingEditRow>> = app.db.dao().editsFlow()
    val missed: Flow<List<MissedAlarmRow>> = app.db.dao().missedFlow()
    val sounds: Flow<Map<String, String?>> = app.db.dao().soundsFlow().map { rows -> rows.associate { it.alarmId to it.soundUri } }
    /** Alarms set not to vibrate on this phone. */
    val silentVibration: Flow<Set<String>> = app.db.dao().soundsFlow().map { rows -> rows.filter { !it.vibrate }.map { it.alarmId }.toSet() }
    val scripts: Flow<List<ScriptRow>> = app.db.dao().scriptsFlow()
    val pendingReports: Flow<Int> = app.db.dao().reportCountFlow()

    suspend fun effectiveAlarms(): List<EffectiveAlarm> =
        PendingEdits.effective(dao.alarms().map { it.toAlarm() }, dao.edits().map { it.toEdit() })

    /**
     * Hands the alarms to the alarm engine. Rings use the effective alarms (queued offline edits
     * included), the phone's own sound choice and the server's default snooze.
     */
    suspend fun rebuildEngine() {
        val state = dao.syncState() ?: SyncStateRow()
        val local = dao.sounds().associateBy { it.alarmId }
        val settings = app.prefs.settings.value
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val skips = app.engine.store.skips.toMutableMap()
        val specs = effectiveAlarms().map { effective ->
            val alarm = effective.alarm
            val skippedDate = if (alarm.skipNext && alarm.repeating && Schedule.isValidTime(alarm.time)) {
                skips[alarm.id]?.takeIf { it.revision == alarm.revision }?.date
                    ?: Schedule.skippedDate(alarm.time, alarm.days, true, zone, now)?.toString()?.also {
                        skips[alarm.id] = SkipMark(alarm.revision, it)
                    }
            } else {
                skips.remove(alarm.id)
                null
            }
            RingSpec(
                alarmId = alarm.id,
                revision = alarm.revision,
                label = alarm.label,
                time = alarm.time,
                repeatDays = alarm.days.map { it.api },
                enabled = alarm.enabled,
                skippedDate = skippedDate,
                snoozeMinutes = alarm.snoozeMinutes ?: state.defaultSnooze,
                soundUri = local[alarm.id]?.soundUri ?: settings.defaultSoundUri,
                vibrate = local[alarm.id]?.vibrate ?: true,
                volumeRamp = settings.volumeRamp,
                haTimeZone = state.timeZone,
                dismissedAt = alarm.currentOccurrence?.takeIf { it.status == "dismissed" }
                    ?.let { runCatching { java.time.OffsetDateTime.parse(it.scheduledAt).toInstant().toEpochMilli() }.getOrNull() ?: Schedule.occurrenceTime(it.id)?.toEpochMilli() },
            )
        }
        app.engine.store.skips = skips
        app.engine.updateSpecs(specs, state.timeZone)
    }

    /** Moves what the engine recorded while the phone was locked into Room (missed alarms, reports). */
    suspend fun importEngineState() {
        val missed = app.engine.takeMissed()
        if (missed.isNotEmpty()) {
            dao.insertMissed(missed.map { MissedAlarmRow(alarmId = it.alarmId, label = it.label, time = it.time, ringAt = it.ringAt) })
            Notifications.status(app, Notifications.ID_MISSED, app.getString(R.string.missed_title), app.getString(R.string.missed_text))
        }
        val reports = app.engine.takeLockedReports()
        reports.forEach {
            dao.insertReport(PendingReportRow(alarmId = it.alarmId, occurrenceId = it.occurrenceId, type = it.type, occurredAt = it.occurredAt, scheduledAt = it.scheduledAt))
        }
        if (reports.isNotEmpty()) ReportWorker.enqueue(app)
        if (app.prefs.account.value.loggedIn) rebuildEngine()
    }

    suspend fun clearMissed() = dao.clearMissed()

    suspend fun upsert(alarm: Alarm) {
        dao.upsertAlarms(listOf(alarm.toRow()))
    }

    private suspend fun queue(edit: PendingEdit) = editMutex.withLock {
        val current = dao.edits().map { it.toEdit() }
        val seq = (current.maxOfOrNull { it.seq } ?: 0) + 1
        val serverJson = dao.edits().associate { it.seq to it.serverJson }
        val merged = PendingEdits.coalesce(current, edit.copy(seq = seq))
        dao.replaceEdits(merged.map { it.toRow(serverJson[it.seq]) })
        SyncWorker.syncSoon(app)
    }

    /** Saves a draft: directly when online, queued when not (AFR-SYN-02). */
    suspend fun save(draft: Draft): SaveResult {
        val id = draft.id
        val result = when {
            id == null -> try {
                val res = ApiJson.decodeFromString(AlarmSaveResponse.serializer(), app.api.call("POST", "alarms", encode(Drafts.toInput(draft, null))))
                upsert(res.alarm)
                SaveResult.Saved(res.alarm, res.warnings)
            } catch (e: Exception) {
                if (!queueable(e)) return SaveResult.Failed(problemOf(e), (e as? ApiException)?.body?.message)
                val tempId = PendingEdits.newTempId()
                queue(PendingEdit(0, tempId, EditOp.CREATE, Drafts.toInput(draft, null)))
                SaveResult.Queued(tempId)
            }
            PendingEdits.isTempId(id) -> {
                queue(PendingEdit(0, id, EditOp.UPDATE, Drafts.toInput(draft, null)))
                SaveResult.Queued(id)
            }
            hasPendingEdit(id) -> {
                // Keep the order of queued edits for this alarm.
                queue(PendingEdit(0, id, EditOp.UPDATE, Drafts.toInput(draft), baseRevision = draft.revision))
                SaveResult.Queued(id)
            }
            else -> update(id, Drafts.toInput(draft))
        }
        rebuildEngine()
        return result
    }

    private suspend fun update(id: String, input: AlarmInput): SaveResult = try {
        val res = ApiJson.decodeFromString(AlarmSaveResponse.serializer(), app.api.call("PUT", "alarms/$id", encode(input)))
        upsert(res.alarm)
        SaveResult.Saved(res.alarm, res.warnings)
    } catch (e: Exception) {
        if (queueable(e)) {
            queue(PendingEdit(0, id, EditOp.UPDATE, input, baseRevision = input.revision))
            SaveResult.Queued(id)
        } else {
            SaveResult.Failed(problemOf(e), (e as? ApiException)?.body?.message)
        }
    }

    private suspend fun hasPendingEdit(id: String) = dao.edits().any { it.alarmId == id }

    suspend fun setEnabled(alarm: Alarm, enabled: Boolean): SaveResult {
        val input = Drafts.toggleInput(alarm, enabled)
        val result = if (PendingEdits.isTempId(alarm.id) || hasPendingEdit(alarm.id)) {
            queue(PendingEdit(0, alarm.id, EditOp.UPDATE, input.copy(revision = if (PendingEdits.isTempId(alarm.id)) null else alarm.revision), alarm.revision))
            SaveResult.Queued(alarm.id)
        } else {
            update(alarm.id, input)
        }
        rebuildEngine()
        return result
    }

    suspend fun delete(alarm: Alarm): SaveResult {
        val result = if (PendingEdits.isTempId(alarm.id) || hasPendingEdit(alarm.id)) {
            queue(PendingEdit(0, alarm.id, EditOp.DELETE, baseRevision = alarm.revision))
            SaveResult.Queued(alarm.id)
        } else {
            try {
                app.api.call("DELETE", "alarms/${alarm.id}")
                dao.deleteAlarms(listOf(alarm.id))
                SaveResult.Saved(alarm, emptyList())
            } catch (e: Exception) {
                when {
                    e is ApiException && e.status == 404 -> {
                        dao.deleteAlarms(listOf(alarm.id))
                        SaveResult.Saved(alarm, emptyList())
                    }
                    queueable(e) -> {
                        queue(PendingEdit(0, alarm.id, EditOp.DELETE, baseRevision = alarm.revision))
                        SaveResult.Queued(alarm.id)
                    }
                    else -> SaveResult.Failed(problemOf(e), (e as? ApiException)?.body?.message)
                }
            }
        }
        if (result !is SaveResult.Failed) dao.deleteSound(alarm.id)
        rebuildEngine()
        return result
    }

    /** Skip / undo skip of the next occurrence (repeating alarms only). */
    suspend fun setSkip(alarm: Alarm, skip: Boolean): SaveResult {
        val op = if (skip) EditOp.SKIP else EditOp.UNSKIP
        val result = if (hasPendingEdit(alarm.id)) {
            queue(PendingEdit(0, alarm.id, op))
            SaveResult.Queued(alarm.id)
        } else {
            try {
                val updated = ApiJson.decodeFromString(Alarm.serializer(), app.api.call("POST", "alarms/${alarm.id}/${if (skip) "skip" else "unskip"}"))
                upsert(updated)
                SaveResult.Saved(updated, emptyList())
            } catch (e: Exception) {
                if (queueable(e)) {
                    queue(PendingEdit(0, alarm.id, op))
                    SaveResult.Queued(alarm.id)
                } else {
                    SaveResult.Failed(problemOf(e), (e as? ApiException)?.body?.message)
                }
            }
        }
        rebuildEngine()
        return result
    }

    suspend fun runNow(alarmId: String, actionId: String): RunResponse =
        ApiJson.decodeFromString(RunResponse.serializer(), app.api.call("POST", "alarms/$alarmId/actions/$actionId/run"))

    /** Reloads one alarm from the server (after a 409); null when it no longer exists. */
    suspend fun reload(id: String): Alarm? = try {
        val alarm = ApiJson.decodeFromString(Alarm.serializer(), app.api.call("GET", "alarms/$id"))
        upsert(alarm)
        alarm
    } catch (e: ApiException) {
        if (e.status != 404) throw e
        dao.deleteAlarms(listOf(id))
        null
    } finally {
        rebuildEngine()
    }

    /** Refreshes the cached script list (GET /actions) and the action label; cached for offline use. */
    suspend fun refreshScripts(): Problem? = try {
        val list = ApiJson.decodeFromString(ActionsResponse.serializer(), app.api.call("GET", "actions")).actions
        dao.replaceScripts(list.mapIndexed { i, a -> ScriptRow(a.entityId, a.name.ifBlank { a.entityId }, a.iconPath, a.description, i) })
        runCatching { ApiJson.decodeFromString(ServerSettings.serializer(), app.api.call("GET", "settings")) }.getOrNull()?.let { s ->
            val state = dao.syncState() ?: SyncStateRow()
            dao.saveSyncState(state.copy(actionLabel = s.actionLabel, defaultSnooze = s.snoozeMinutes))
        }
        null
    } catch (e: Exception) {
        problemOf(e)
    }

    /** The phone-local sound and vibration of an alarm; the row is dropped when both are default. */
    suspend fun setLocal(alarmId: String, uri: String?, vibrate: Boolean) {
        if (uri == null && vibrate) dao.deleteSound(alarmId) else dao.saveSound(AlarmSoundRow(alarmId, uri, vibrate))
        rebuildEngine()
    }

    /** Sends queued edits in order; stops at the first network problem. True when the queue is done. */
    suspend fun flushEdits(): Boolean = editMutex.withLock {
        for (row in dao.edits()) {
            if (row.state != EditState.PENDING.name) continue
            val edit = row.toEdit()
            try {
                when (edit.op) {
                    EditOp.CREATE -> {
                        val res = ApiJson.decodeFromString(AlarmSaveResponse.serializer(), app.api.call("POST", "alarms", encode(edit.input!!.copy(revision = null))))
                        upsert(res.alarm)
                        dao.sounds().firstOrNull { it.alarmId == edit.alarmId }?.let {
                            dao.saveSound(it.copy(alarmId = res.alarm.id))
                            dao.deleteSound(edit.alarmId)
                        }
                    }
                    EditOp.UPDATE -> {
                        val input = edit.input!!.copy(revision = edit.baseRevision)
                        val res = ApiJson.decodeFromString(AlarmSaveResponse.serializer(), app.api.call("PUT", "alarms/${edit.alarmId}", encode(input)))
                        upsert(res.alarm)
                    }
                    EditOp.DELETE -> {
                        try {
                            app.api.call("DELETE", "alarms/${edit.alarmId}")
                        } catch (e: ApiException) {
                            if (e.status != 404) throw e
                        }
                        dao.deleteAlarms(listOf(edit.alarmId))
                    }
                    EditOp.SKIP, EditOp.UNSKIP -> {
                        try {
                            val path = "alarms/${edit.alarmId}/${if (edit.op == EditOp.SKIP) "skip" else "unskip"}"
                            upsert(ApiJson.decodeFromString(Alarm.serializer(), app.api.call("POST", path)))
                        } catch (e: ApiException) {
                            if (e.status != 404 && e.status != 422) throw e
                        }
                    }
                }
                dao.deleteEdit(row.seq)
            } catch (e: ApiException) {
                when {
                    e.status == 409 || (e.status == 404 && edit.op == EditOp.UPDATE) -> {
                        val server = runCatching { app.api.call("GET", "alarms/${edit.alarmId}") }.getOrNull()
                        dao.insertEdits(listOf(row.copy(state = EditState.CONFLICT.name, serverJson = server, message = null)))
                    }
                    queueable(e) -> return@withLock false
                    else -> dao.insertEdits(listOf(row.copy(state = EditState.REJECTED.name, message = e.body?.message ?: e.message)))
                }
            } catch (e: IOException) {
                return@withLock false
            }
        }
        true
    }

    suspend fun edit(seq: Long): PendingEditRow? = dao.edit(seq)

    /** Conflict: overwrite the server with the local version (new revision). */
    suspend fun keepMine(seq: Long) {
        editMutex.withLock {
            val row = dao.edit(seq) ?: return
            val server = row.serverJson?.let { ApiJson.decodeFromString(Alarm.serializer(), it) }
            val updated = when {
                row.op == EditOp.DELETE.name -> row.copy(state = EditState.PENDING.name, baseRevision = server?.revision)
                server != null -> row.copy(state = EditState.PENDING.name, baseRevision = server.revision, message = null)
                else -> {
                    // Deleted on the server: create it again as a new alarm.
                    val input = row.inputJson?.let { ApiJson.decodeFromString(AlarmInput.serializer(), it) }
                    val fresh = input?.copy(revision = null, actions = input.actions.map { it.copy(id = null) })
                    dao.deleteAlarms(listOf(row.alarmId))
                    row.copy(
                        alarmId = PendingEdits.newTempId(), op = EditOp.CREATE.name, state = EditState.PENDING.name,
                        inputJson = fresh?.let(::encode), baseRevision = null, message = null,
                    )
                }
            }
            dao.deleteEdit(seq)
            dao.insertEdits(listOf(updated))
        }
        rebuildEngine()
        SyncWorker.syncSoon(app)
    }

    /** Conflict: drop the local edit and use what the server has. */
    suspend fun useServer(seq: Long) {
        editMutex.withLock {
            val row = dao.edit(seq) ?: return
            dao.deleteEdit(seq)
            val server = row.serverJson?.let { ApiJson.decodeFromString(Alarm.serializer(), it) }
            if (server != null) upsert(server) else if (!PendingEdits.isTempId(row.alarmId) && row.op != EditOp.DELETE.name) {
                dao.deleteAlarms(listOf(row.alarmId))
            }
        }
        rebuildEngine()
    }

    private fun encode(input: AlarmInput) = ApiJson.encodeToString(AlarmInput.serializer(), input)
}
