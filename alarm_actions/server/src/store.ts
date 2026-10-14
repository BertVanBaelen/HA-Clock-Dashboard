import crypto from 'node:crypto';
import type {
  AlarmAction,
  AlarmActionInput,
  AlarmCreateInput,
  DisabledReason,
  Weekday,
} from '@alarm-actions/shared';
import { getMeta, setMeta, type DB } from './db.js';

/** An alarm as stored (instants in ms). The API shape is built in `routes/alarms.ts`. */
export interface StoredAlarm {
  id: string;
  owner_user_id: string;
  label: string;
  time: string;
  repeat_days: Weekday[];
  enabled: boolean;
  skip_next: boolean;
  snooze_minutes: number | null;
  actions: AlarmAction[];
  disabled_reason: DisabledReason;
  revision: number;
  change_seq: number;
  created_at: number;
  updated_at: number;
}

/** Fields a change may set; everything else is maintained by the store. */
export type AlarmChanges = Partial<
  Pick<
    StoredAlarm,
    | 'label'
    | 'time'
    | 'repeat_days'
    | 'enabled'
    | 'skip_next'
    | 'snooze_minutes'
    | 'actions'
    | 'disabled_reason'
  >
>;

interface AlarmRow {
  id: string;
  owner_user_id: string;
  label: string;
  time: string;
  repeat_days: string;
  enabled: number;
  skip_next: number;
  snooze_minutes: number | null;
  actions_json: string;
  disabled_reason: string | null;
  revision: number;
  change_seq: number;
  created_at: number;
  updated_at: number;
}

function fromRow(row: AlarmRow): StoredAlarm {
  return {
    id: row.id,
    owner_user_id: row.owner_user_id,
    label: row.label,
    time: row.time,
    repeat_days: JSON.parse(row.repeat_days) as Weekday[],
    enabled: row.enabled === 1,
    skip_next: row.skip_next === 1,
    snooze_minutes: row.snooze_minutes,
    actions: JSON.parse(row.actions_json) as AlarmAction[],
    disabled_reason: (row.disabled_reason ?? null) as DisabledReason,
    revision: row.revision,
    change_seq: row.change_seq,
    created_at: row.created_at,
    updated_at: row.updated_at,
  };
}

/**
 * Keeps the ids of actions the client sent back and gives new actions a fresh UUID, so execution
 * records and occurrences keep pointing at the same action after an edit (TR-DAT-05). Ids that
 * do not belong to this alarm are replaced as well.
 */
export function assignActionIds(
  input: AlarmActionInput[],
  existing: readonly AlarmAction[] = [],
): AlarmAction[] {
  const known = new Set(existing.map((a) => a.id));
  return input.map((a) => ({
    id: a.id && known.has(a.id) ? a.id : crypto.randomUUID(),
    script_entity_id: a.script_entity_id,
    timing: a.timing,
    offset_minutes: a.offset_minutes,
    delay_minutes: a.delay_minutes,
    enabled: a.enabled,
  }));
}

export type ChangeListener = (alarmId: string) => void;

/**
 * Alarm storage (TR-DAT-01..06). Every change, by a user or by the system, runs in a
 * transaction, bumps the alarm's `revision` and takes the next store-wide `change_seq`; deletions
 * leave a tombstone with their own `change_seq`.
 */
export class AlarmStore {
  private readonly listeners = new Set<ChangeListener>();

  constructor(
    private readonly db: DB,
    private readonly now: () => number,
  ) {}

  /** Called after every committed change (scheduler, phase 3). */
  onChange(listener: ChangeListener): void {
    this.listeners.add(listener);
  }

  private notify(id: string): void {
    for (const listener of this.listeners) listener(id);
  }

  private nextSeq(): number {
    const next = Number(getMeta(this.db, 'change_seq') ?? '0') + 1;
    setMeta(this.db, 'change_seq', String(next));
    return next;
  }

  get changeSeq(): number {
    return Number(getMeta(this.db, 'change_seq') ?? '0');
  }

  get(id: string): StoredAlarm | null {
    const row = this.db.prepare('SELECT * FROM alarms WHERE id = ?').get(id) as
      | AlarmRow
      | undefined;
    return row ? fromRow(row) : null;
  }

  /** Alarms of one owner, or all alarms when `owner` is omitted; ordered by time. */
  list(owner?: string): StoredAlarm[] {
    const rows = (
      owner === undefined
        ? this.db.prepare('SELECT * FROM alarms ORDER BY time, created_at').all()
        : this.db
            .prepare('SELECT * FROM alarms WHERE owner_user_id = ? ORDER BY time, created_at')
            .all(owner)
    ) as AlarmRow[];
    return rows.map(fromRow);
  }

  countByOwner(owner: string): number {
    const row = this.db
      .prepare('SELECT COUNT(*) AS n FROM alarms WHERE owner_user_id = ?')
      .get(owner) as { n: number };
    return row.n;
  }

  /** Total number of actions over all alarms (global cap). */
  countActions(): number {
    const row = this.db
      .prepare('SELECT COALESCE(SUM(json_array_length(actions_json)), 0) AS n FROM alarms')
      .get() as { n: number };
    return row.n;
  }

  create(owner: string, input: AlarmCreateInput): StoredAlarm {
    const id = crypto.randomUUID();
    const now = this.now();
    this.db.transaction(() => {
      this.db
        .prepare(
          `INSERT INTO alarms (id, owner_user_id, label, time, repeat_days, enabled, skip_next,
             snooze_minutes, actions_json, disabled_reason, revision, change_seq, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, 1, ?, ?, ?)`,
        )
        .run(
          id,
          owner,
          input.label,
          input.time,
          JSON.stringify(input.repeat_days),
          input.enabled ? 1 : 0,
          input.snooze_minutes,
          JSON.stringify(assignActionIds(input.actions)),
          this.nextSeq(),
          now,
          now,
        );
    })();
    this.notify(id);
    return this.get(id)!;
  }

  /**
   * Applies changes. With `expectedRevision` the change is only applied when the stored revision
   * still matches; otherwise `{ conflict: currentRevision }` is returned (TR-API-02).
   */
  update(
    id: string,
    changes: AlarmChanges,
    expectedRevision?: number,
  ): StoredAlarm | { conflict: number } | null {
    const result = this.db.transaction((): StoredAlarm | { conflict: number } | null => {
      const current = this.get(id);
      if (!current) return null;
      if (expectedRevision !== undefined && current.revision !== expectedRevision) {
        return { conflict: current.revision };
      }
      const next: StoredAlarm = { ...current, ...changes };
      this.db
        .prepare(
          `UPDATE alarms SET label = ?, time = ?, repeat_days = ?, enabled = ?, skip_next = ?,
             snooze_minutes = ?, actions_json = ?, disabled_reason = ?, revision = revision + 1,
             change_seq = ?, updated_at = ?
           WHERE id = ?`,
        )
        .run(
          next.label,
          next.time,
          JSON.stringify(next.repeat_days),
          next.enabled ? 1 : 0,
          next.skip_next ? 1 : 0,
          next.snooze_minutes,
          JSON.stringify(next.actions),
          next.disabled_reason,
          this.nextSeq(),
          this.now(),
          id,
        );
      return this.get(id);
    })();
    if (result && !('conflict' in result)) this.notify(id);
    return result;
  }

  /** Deletes an alarm and leaves a tombstone. Returns false when it did not exist. */
  delete(id: string): boolean {
    const deleted = this.db.transaction((): boolean => {
      const current = this.get(id);
      if (!current) return false;
      this.db.prepare('DELETE FROM alarms WHERE id = ?').run(id);
      this.db
        .prepare(
          `INSERT INTO tombstones (alarm_id, owner_user_id, change_seq, deleted_at) VALUES (?, ?, ?, ?)
           ON CONFLICT(alarm_id) DO UPDATE SET change_seq = excluded.change_seq, deleted_at = excluded.deleted_at`,
        )
        .run(id, current.owner_user_id, this.nextSeq(), this.now());
      return true;
    })();
    if (deleted) this.notify(id);
    return deleted;
  }

  /** Tombstones of one owner newer than `afterSeq` (sync, phase 6). */
  tombstones(owner: string, afterSeq = 0): Array<{ alarm_id: string; change_seq: number }> {
    return this.db
      .prepare(
        'SELECT alarm_id, change_seq FROM tombstones WHERE owner_user_id = ? AND change_seq > ? ORDER BY change_seq',
      )
      .all(owner, afterSeq) as Array<{ alarm_id: string; change_seq: number }>;
  }

  /** Removes tombstones older than `maxAgeMs` (kept at least 30 days, TR-DAT-06). */
  pruneTombstones(maxAgeMs: number): number {
    return this.db.transaction(() => {
      const cutoff = this.now() - maxAgeMs;
      const row = this.db
        .prepare('SELECT MAX(change_seq) AS seq FROM tombstones WHERE deleted_at < ?')
        .get(cutoff) as { seq: number | null };
      if (row.seq !== null && row.seq > this.tombstoneFloor) {
        setMeta(this.db, 'tombstone_floor', String(row.seq));
      }
      return this.db.prepare('DELETE FROM tombstones WHERE deleted_at < ?').run(cutoff).changes;
    })();
  }

  /**
   * Highest `change_seq` of a pruned tombstone. A client whose cursor is below it may have missed
   * a deletion and gets a full resync.
   */
  get tombstoneFloor(): number {
    return Number(getMeta(this.db, 'tombstone_floor') ?? '0');
  }
}
