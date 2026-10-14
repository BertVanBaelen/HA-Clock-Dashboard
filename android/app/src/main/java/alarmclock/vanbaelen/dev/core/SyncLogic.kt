package alarmclock.vanbaelen.dev.core

/** Queued offline change to an alarm (AFR-SYN-02). */
enum class EditOp { CREATE, UPDATE, DELETE, SKIP, UNSKIP }

enum class EditState { PENDING, CONFLICT, REJECTED }

data class PendingEdit(
    val seq: Long,
    /** Server id, or a temporary `local-` id for alarms created offline. */
    val alarmId: String,
    val op: EditOp,
    val input: AlarmInput? = null,
    /** Revision the edit was based on (UPDATE / DELETE). */
    val baseRevision: Int? = null,
    val state: EditState = EditState.PENDING,
    val message: String? = null,
)

/** An alarm as shown and rung by the app: the server copy with queued local edits applied. */
data class EffectiveAlarm(val alarm: Alarm, val pending: Boolean, val conflict: Boolean)

/** Result of applying a `GET /sync` response to the local copy. */
data class MergePlan(val upserts: List<Alarm>, val deletes: Set<String>, val changeSeq: Long)

object SyncMerge {
    /**
     * Full: the response is the complete list (local alarms not in it are dropped).
     * Incremental: upsert changed alarms and drop tombstones. An alarm whose local copy is newer
     * (written from a direct API response while the sync was running) is kept.
     */
    fun plan(localChangeSeq: Map<String, Long>, response: SyncResponse): MergePlan {
        val upserts = response.alarms.filter { alarm ->
            val local = localChangeSeq[alarm.id]
            response.full || local == null || alarm.changeSeq >= local
        }
        val deletes = if (response.full) {
            localChangeSeq.keys - response.alarms.map { it.id }.toSet()
        } else {
            response.deleted.toSet() - response.alarms.map { it.id }.toSet()
        }
        return MergePlan(upserts, deletes, response.changeSeq)
    }

    /**
     * Queued updates/deletes that will be rejected: the alarm was changed (other revision) or
     * deleted on the server since the edit was made.
     */
    fun conflicts(edits: List<PendingEdit>, server: Map<String, Alarm>): List<PendingEdit> = edits.filter { edit ->
        if (edit.state != EditState.PENDING || PendingEdits.isTempId(edit.alarmId)) return@filter false
        if (edit.op != EditOp.UPDATE && edit.op != EditOp.DELETE) return@filter false
        val current = server[edit.alarmId] ?: return@filter edit.op == EditOp.UPDATE
        edit.baseRevision != null && current.revision != edit.baseRevision
    }
}

object PendingEdits {
    private const val TEMP_PREFIX = "local-"

    fun isTempId(id: String): Boolean = id.startsWith(TEMP_PREFIX)
    fun newTempId(): String = TEMP_PREFIX + java.util.UUID.randomUUID().toString()

    /**
     * Adds [edit] to the queue, merging it with earlier edits of the same alarm so the queue holds
     * at most what the server needs to reach the local state.
     */
    fun coalesce(queue: List<PendingEdit>, edit: PendingEdit): List<PendingEdit> {
        val same = queue.filter { it.alarmId == edit.alarmId }
        val others = queue.filter { it.alarmId != edit.alarmId }
        val create = same.firstOrNull { it.op == EditOp.CREATE }
        val update = same.firstOrNull { it.op == EditOp.UPDATE }
        return when (edit.op) {
            EditOp.CREATE -> others + edit
            EditOp.UPDATE -> when {
                create != null -> others + create.copy(input = edit.input?.copy(revision = null))
                update != null -> others + same.filter { it.op != EditOp.UPDATE } +
                    update.copy(input = edit.input, seq = edit.seq)
                else -> queue + edit
            }
            EditOp.DELETE -> when {
                create != null -> others
                else -> others + edit.copy(baseRevision = update?.baseRevision ?: edit.baseRevision)
            }
            EditOp.SKIP, EditOp.UNSKIP -> {
                val opposite = if (edit.op == EditOp.SKIP) EditOp.UNSKIP else EditOp.SKIP
                val pendingOpposite = same.firstOrNull { it.op == opposite && it.state == EditState.PENDING }
                if (pendingOpposite != null) {
                    queue - pendingOpposite
                } else if (same.any { it.op == edit.op }) {
                    queue
                } else {
                    queue + edit
                }
            }
        }.sortedBy { it.seq }
    }

    /** Server alarms with the queued edits applied, in the order shown in the list. */
    fun effective(server: List<Alarm>, queue: List<PendingEdit>): List<EffectiveAlarm> {
        val result = LinkedHashMap<String, EffectiveAlarm>()
        server.forEach { result[it.id] = EffectiveAlarm(it, pending = false, conflict = false) }
        for (edit in queue.sortedBy { it.seq }) {
            val conflict = edit.state != EditState.PENDING
            val current = result[edit.alarmId]
            when (edit.op) {
                EditOp.CREATE -> edit.input?.let {
                    result[edit.alarmId] = EffectiveAlarm(Drafts.applyTo(null, edit.alarmId, it), true, conflict)
                }
                EditOp.UPDATE -> edit.input?.let {
                    result[edit.alarmId] = EffectiveAlarm(Drafts.applyTo(current?.alarm, edit.alarmId, it), true, conflict)
                }
                EditOp.DELETE -> if (conflict && current != null) {
                    result[edit.alarmId] = current.copy(pending = true, conflict = true)
                } else {
                    result.remove(edit.alarmId)
                }
                EditOp.SKIP, EditOp.UNSKIP -> if (current != null) {
                    val skip = edit.op == EditOp.SKIP && current.alarm.repeating
                    result[edit.alarmId] = current.copy(alarm = current.alarm.copy(skipNext = skip), pending = true)
                }
            }
        }
        return result.values.sortedWith(compareBy({ it.alarm.time }, { it.alarm.label }))
    }
}
