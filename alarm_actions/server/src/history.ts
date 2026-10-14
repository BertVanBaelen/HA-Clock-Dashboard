import crypto from 'node:crypto';
import type { ExecutionResult } from '@alarm-actions/shared';
import type { DB } from './db.js';

const DAY = 86_400_000;

/** Results that mean a script start was attempted (counted by the rate limit). */
const STARTED: ExecutionResult[] = ['running', 'success', 'failed'];
const STARTED_SQL = STARTED.map((r) => `'${r}'`).join(', ');

export interface ExecutionRecord {
  id: string;
  planned_run_id: string;
  owner_user_id: string;
  alarm_id: string;
  alarm_action_id: string;
  occurrence_id: string | null;
  script_entity_id: string;
  planned_at: number;
  executed_at: number | null;
  result: ExecutionResult;
  reason: string | null;
  created_at: number;
}

/** What a planned run (or a "run now") is about; the record is written for it. */
export interface RunSubject {
  planned_run_id: string;
  owner_user_id: string;
  alarm_id: string;
  alarm_action_id: string;
  occurrence_id: string | null;
  script_entity_id: string;
  planned_at: number;
}

export interface StartLimits {
  maxStartsPerMinute: number;
  minIntervalMs: number;
}

/** Why a write-ahead start was refused. */
export type StartRefusal = { refused: 'rate limited' | 'duplicate start' } | { existing: ExecutionRecord };

/**
 * Execution records (FR-EXE-06, TR-SCH-06, TR-DAT-03). The unique `planned_run_id` makes every
 * planned run produce at most one record, which is the exactly-once guarantee.
 */
export class HistoryStore {
  constructor(
    private readonly db: DB,
    private readonly now: () => number,
  ) {}

  get(plannedRunId: string): ExecutionRecord | null {
    return (
      (this.db
        .prepare('SELECT * FROM execution_records WHERE planned_run_id = ?')
        .get(plannedRunId) as ExecutionRecord | undefined) ?? null
    );
  }

  /** Planned-run ids that already have a record, out of `ids`. */
  existing(ids: string[]): Set<string> {
    const found = new Set<string>();
    for (let i = 0; i < ids.length; i += 500) {
      const chunk = ids.slice(i, i + 500);
      const rows = this.db
        .prepare(
          `SELECT planned_run_id FROM execution_records WHERE planned_run_id IN (${chunk.map(() => '?').join(',')})`,
        )
        .all(...chunk) as Array<{ planned_run_id: string }>;
      for (const row of rows) found.add(row.planned_run_id);
    }
    return found;
  }

  /** Records of one alarm, newest first. */
  forAlarm(alarmId: string, limit = 50): ExecutionRecord[] {
    return this.db
      .prepare(
        'SELECT * FROM execution_records WHERE alarm_id = ? ORDER BY created_at DESC, rowid DESC LIMIT ?',
      )
      .all(alarmId, limit) as ExecutionRecord[];
  }

  /**
   * Writes an outcome that is not a script start (skipped, missed, unavailable). Does nothing
   * when the planned run already has a record; returns the stored record.
   */
  record(subject: RunSubject, result: ExecutionResult, reason: string | null): ExecutionRecord {
    this.insert(subject, result, reason, null);
    return this.get(subject.planned_run_id)!;
  }

  /**
   * Write-ahead (TR-SCH-06): checks the rate limit and duplicate suppression and commits a
   * `running` record in one transaction, before the script is called. A start over a limit is
   * recorded as `skipped`.
   */
  beginStart(subject: RunSubject, limits: StartLimits): ExecutionRecord | StartRefusal {
    return this.db.transaction((): ExecutionRecord | StartRefusal => {
      const existing = this.get(subject.planned_run_id);
      if (existing) return { existing };
      const now = this.now();
      const starts = this.db
        .prepare(
          `SELECT COUNT(*) AS n FROM execution_records WHERE executed_at > ? AND result IN (${STARTED_SQL})`,
        )
        .get(now - 60_000) as { n: number };
      if (starts.n >= limits.maxStartsPerMinute) {
        this.insert(subject, 'skipped', 'rate limited', null);
        return { refused: 'rate limited' };
      }
      const recent = this.db
        .prepare(
          `SELECT 1 FROM execution_records
           WHERE script_entity_id = ? AND executed_at > ? AND result IN (${STARTED_SQL}) LIMIT 1`,
        )
        .get(subject.script_entity_id, now - limits.minIntervalMs);
      if (recent) {
        this.insert(subject, 'skipped', 'duplicate start', null);
        return { refused: 'duplicate start' };
      }
      this.insert(subject, 'running', null, now);
      return this.get(subject.planned_run_id)!;
    })();
  }

  /** Replaces the `running` record with the outcome of the script call. */
  finish(plannedRunId: string, result: ExecutionResult, reason: string | null): void {
    this.db
      .prepare(
        "UPDATE execution_records SET result = ?, reason = ? WHERE planned_run_id = ? AND result = 'running'",
      )
      .run(result, reason, plannedRunId);
  }

  /** The call was never sent (Core not connected): forget the write-ahead record so it can retry. */
  abandon(plannedRunId: string): void {
    this.db
      .prepare("DELETE FROM execution_records WHERE planned_run_id = ? AND result = 'running'")
      .run(plannedRunId);
  }

  /** At startup: a record still `running` was interrupted by a crash and is not retried. */
  failInterrupted(): number {
    return this.db
      .prepare("UPDATE execution_records SET result = 'failed', reason = 'interrupted' WHERE result = 'running'")
      .run().changes;
  }

  /** Retention (TR-DAT-03): older than `days`, or beyond the newest `maxPerUser` of each user. */
  prune(days: number, maxPerUser: number): number {
    const tooOld = this.db
      .prepare("DELETE FROM execution_records WHERE created_at < ? AND result != 'running'")
      .run(this.now() - days * DAY).changes;
    const overCap = this.db
      .prepare(
        `DELETE FROM execution_records WHERE id IN (
           SELECT id FROM (
             SELECT id, ROW_NUMBER() OVER (
               PARTITION BY owner_user_id ORDER BY created_at DESC, rowid DESC) AS n
             FROM execution_records WHERE result != 'running')
           WHERE n > ?)`,
      )
      .run(maxPerUser).changes;
    return tooOld + overCap;
  }

  private insert(
    subject: RunSubject,
    result: ExecutionResult,
    reason: string | null,
    executedAt: number | null,
  ): void {
    this.db
      .prepare(
        `INSERT INTO execution_records (id, planned_run_id, owner_user_id, alarm_id, alarm_action_id,
           occurrence_id, script_entity_id, planned_at, executed_at, result, reason, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(planned_run_id) DO NOTHING`,
      )
      .run(
        crypto.randomUUID(),
        subject.planned_run_id,
        subject.owner_user_id,
        subject.alarm_id,
        subject.alarm_action_id,
        subject.occurrence_id,
        subject.script_entity_id,
        subject.planned_at,
        executedAt,
        result,
        reason,
        this.now(),
      );
  }
}
