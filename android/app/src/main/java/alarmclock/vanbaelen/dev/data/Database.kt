package alarmclock.vanbaelen.dev.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Server copy of one alarm (the API JSON, so new server fields survive a round trip). */
@Entity(tableName = "alarms")
data class AlarmRow(@PrimaryKey val id: String, val json: String, val changeSeq: Long)

/** Single row with the sync cursor and what the last sync learned. */
@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey val id: Int = 0,
    val changeSeq: Long = 0,
    val lastSyncAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val timeZone: String? = null,
    val defaultSnooze: Int = 9,
    val dismissTimeout: Int = 60,
    val serverAway: Boolean = false,
    /** Problem of the last attempt: offline, login, dashboard_old, app_old, ha_offline, client_limit, error. */
    val problem: String? = null,
    val problemDetail: String? = null,
    val appInfoJson: String? = null,
    val actionLabel: String? = null,
)

@Entity(tableName = "pending_reports")
data class PendingReportRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: String,
    val occurrenceId: String,
    val type: String,
    val occurredAt: Long,
    val scheduledAt: Long,
    val attempts: Int = 0,
)

@Entity(tableName = "pending_edits")
data class PendingEditRow(
    @PrimaryKey val seq: Long,
    val alarmId: String,
    val op: String,
    val inputJson: String?,
    val baseRevision: Int?,
    val state: String,
    val message: String?,
    /** Server version at the moment of the conflict (null when deleted on the server). */
    val serverJson: String? = null,
)

/** Alarm sound and vibration chosen on this phone; never sent to the server. */
@Entity(tableName = "alarm_sounds")
data class AlarmSoundRow(
    @PrimaryKey val alarmId: String,
    val soundUri: String?,
    @ColumnInfo(defaultValue = "1") val vibrate: Boolean = true,
)

@Entity(tableName = "missed_alarms")
data class MissedAlarmRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: String,
    val label: String,
    val time: String,
    val ringAt: Long,
)

/** Cached GET /actions list, so the editor works offline. */
@Entity(tableName = "scripts")
data class ScriptRow(
    @PrimaryKey val entityId: String,
    val name: String,
    val iconPath: String?,
    val description: String?,
    val position: Int,
)

@Dao
interface AppDao {
    @Query("SELECT * FROM alarms")
    fun alarmsFlow(): Flow<List<AlarmRow>>

    @Query("SELECT * FROM alarms")
    suspend fun alarms(): List<AlarmRow>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun alarm(id: String): AlarmRow?

    @Upsert
    suspend fun upsertAlarms(rows: List<AlarmRow>)

    @Query("DELETE FROM alarms WHERE id IN (:ids)")
    suspend fun deleteAlarms(ids: Collection<String>)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun syncStateFlow(): Flow<SyncStateRow?>

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun syncState(): SyncStateRow?

    @Upsert
    suspend fun saveSyncState(row: SyncStateRow)

    @Insert
    suspend fun insertReport(row: PendingReportRow)

    @Query("SELECT * FROM pending_reports ORDER BY id")
    suspend fun reports(): List<PendingReportRow>

    @Query("SELECT COUNT(*) FROM pending_reports")
    fun reportCountFlow(): Flow<Int>

    @Query("DELETE FROM pending_reports WHERE id = :id")
    suspend fun deleteReport(id: Long)

    @Query("UPDATE pending_reports SET attempts = attempts + 1 WHERE id = :id")
    suspend fun reportAttempted(id: Long)

    @Query("SELECT * FROM pending_edits ORDER BY seq")
    fun editsFlow(): Flow<List<PendingEditRow>>

    @Query("SELECT * FROM pending_edits ORDER BY seq")
    suspend fun edits(): List<PendingEditRow>

    @Query("SELECT * FROM pending_edits WHERE seq = :seq")
    suspend fun edit(seq: Long): PendingEditRow?

    @Query("DELETE FROM pending_edits")
    suspend fun deleteAllEdits()

    @Query("DELETE FROM pending_edits WHERE seq = :seq")
    suspend fun deleteEdit(seq: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdits(rows: List<PendingEditRow>)

    @Transaction
    suspend fun replaceEdits(rows: List<PendingEditRow>) {
        deleteAllEdits()
        insertEdits(rows)
    }

    @Query("SELECT * FROM alarm_sounds")
    fun soundsFlow(): Flow<List<AlarmSoundRow>>

    @Query("SELECT * FROM alarm_sounds")
    suspend fun sounds(): List<AlarmSoundRow>

    @Upsert
    suspend fun saveSound(row: AlarmSoundRow)

    @Query("DELETE FROM alarm_sounds WHERE alarmId = :alarmId")
    suspend fun deleteSound(alarmId: String)

    @Query("SELECT * FROM missed_alarms ORDER BY ringAt")
    fun missedFlow(): Flow<List<MissedAlarmRow>>

    @Insert
    suspend fun insertMissed(rows: List<MissedAlarmRow>)

    @Query("DELETE FROM missed_alarms")
    suspend fun clearMissed()

    @Query("SELECT * FROM scripts ORDER BY position")
    fun scriptsFlow(): Flow<List<ScriptRow>>

    @Query("DELETE FROM scripts")
    suspend fun clearScripts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScripts(rows: List<ScriptRow>)

    @Transaction
    suspend fun replaceScripts(rows: List<ScriptRow>) {
        clearScripts()
        insertScripts(rows)
    }
}

@Database(
    entities = [
        AlarmRow::class, SyncStateRow::class, PendingReportRow::class, PendingEditRow::class,
        AlarmSoundRow::class, MissedAlarmRow::class, ScriptRow::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** 1 → 2: per-alarm vibration (on by default). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarm_sounds ADD COLUMN vibrate INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "alarm_actions.db").addMigrations(MIGRATION_1_2).build()
    }
}
