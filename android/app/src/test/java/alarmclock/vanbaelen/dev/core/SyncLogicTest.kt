package alarmclock.vanbaelen.dev.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    private fun alarm(id: String, seq: Long, revision: Int = 1, time: String = "07:00", days: List<String> = listOf("mon")) =
        Alarm(id = id, time = time, repeatDays = days, changeSeq = seq, revision = revision)

    private fun input(time: String, revision: Int? = null) =
        AlarmInput(label = "x", time = time, repeatDays = listOf("mon"), enabled = true, snoozeMinutes = null, actions = emptyList(), revision = revision)

    @Test
    fun fullSyncReplacesEverything() {
        val response = SyncResponse(changeSeq = 10, full = true, alarms = listOf(alarm("a", 5), alarm("c", 9)))
        val plan = SyncMerge.plan(mapOf("a" to 7L, "b" to 3L), response)
        assertEquals(listOf("a", "c"), plan.upserts.map { it.id })
        assertEquals(setOf("b"), plan.deletes)
        assertEquals(10, plan.changeSeq)
    }

    @Test
    fun incrementalAppliesChangesAndTombstones() {
        val response = SyncResponse(changeSeq = 12, full = false, alarms = listOf(alarm("a", 11)), deleted = listOf("b", "zz"))
        val plan = SyncMerge.plan(mapOf("a" to 7L, "b" to 3L, "c" to 4L), response)
        assertEquals(listOf("a"), plan.upserts.map { it.id })
        assertEquals(setOf("b", "zz"), plan.deletes)
    }

    @Test
    fun incrementalKeepsNewerLocalCopy() {
        val response = SyncResponse(changeSeq = 12, full = false, alarms = listOf(alarm("a", 8)))
        val plan = SyncMerge.plan(mapOf("a" to 11L), response)
        assertTrue(plan.upserts.isEmpty())
    }

    @Test
    fun pendingEditsOverlayServerCopy() {
        val server = listOf(alarm("a", 1, time = "06:00"), alarm("b", 2, time = "08:00"))
        val queue = listOf(
            PendingEdit(1, "a", EditOp.UPDATE, input("06:30", 1), baseRevision = 1),
            PendingEdit(2, "b", EditOp.DELETE, baseRevision = 1),
            PendingEdit(3, "local-1", EditOp.CREATE, input("09:00")),
        )
        val effective = PendingEdits.effective(server, queue)
        assertEquals(listOf("a" to "06:30", "local-1" to "09:00"), effective.map { it.alarm.id to it.alarm.time })
        assertTrue(effective.all { it.pending })
    }

    @Test
    fun syncDoesNotOverwritePendingEdit() {
        // Server copy changes by sync; the queued local edit still wins in the effective view.
        val queue = listOf(PendingEdit(1, "a", EditOp.UPDATE, input("06:30", 1), baseRevision = 1))
        val afterSync = listOf(alarm("a", 5, revision = 2, time = "05:00"))
        assertEquals("06:30", PendingEdits.effective(afterSync, queue).single().alarm.time)
        // ...and it is detected as a conflict before it is sent.
        assertEquals(1, SyncMerge.conflicts(queue, afterSync.associateBy { it.id }).size)
        assertTrue(SyncMerge.conflicts(queue, mapOf("a" to alarm("a", 5, revision = 1))).isEmpty())
        // A deleted alarm with a pending update is a conflict as well.
        assertEquals(1, SyncMerge.conflicts(queue, emptyMap()).size)
    }

    @Test
    fun coalesceMergesEditsOfOneAlarm() {
        var queue = PendingEdits.coalesce(emptyList(), PendingEdit(1, "local-1", EditOp.CREATE, input("07:00")))
        queue = PendingEdits.coalesce(queue, PendingEdit(2, "local-1", EditOp.UPDATE, input("07:30", 0), 0))
        assertEquals(1, queue.size)
        assertEquals(EditOp.CREATE, queue[0].op)
        assertEquals("07:30", queue[0].input!!.time)
        assertNull(queue[0].input!!.revision)
        queue = PendingEdits.coalesce(queue, PendingEdit(3, "local-1", EditOp.DELETE))
        assertTrue(queue.isEmpty())

        queue = PendingEdits.coalesce(emptyList(), PendingEdit(1, "a", EditOp.UPDATE, input("07:00", 4), 4))
        queue = PendingEdits.coalesce(queue, PendingEdit(2, "a", EditOp.UPDATE, input("07:10", 4), 4))
        assertEquals(1, queue.size)
        assertEquals("07:10", queue.single().input!!.time)
        queue = PendingEdits.coalesce(queue, PendingEdit(3, "a", EditOp.DELETE, baseRevision = 4))
        assertEquals(listOf(EditOp.DELETE), queue.map { it.op })
        assertEquals(4, queue.single().baseRevision)
    }

    @Test
    fun skipAndUnskipCancel() {
        var queue = PendingEdits.coalesce(emptyList(), PendingEdit(1, "a", EditOp.SKIP))
        assertEquals(1, queue.size)
        val skipped = PendingEdits.effective(listOf(alarm("a", 1)), queue).single()
        assertTrue(skipped.alarm.skipNext)
        queue = PendingEdits.coalesce(queue, PendingEdit(2, "a", EditOp.UNSKIP))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun conflictedEditStaysVisible() {
        val queue = listOf(PendingEdit(1, "a", EditOp.DELETE, baseRevision = 1, state = EditState.CONFLICT))
        val effective = PendingEdits.effective(listOf(alarm("a", 1)), queue).single()
        assertTrue(effective.conflict)
        assertFalse(PendingEdits.isTempId("a"))
        assertTrue(PendingEdits.isTempId(PendingEdits.newTempId()))
    }
}
