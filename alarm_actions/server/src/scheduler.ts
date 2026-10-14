import type { FastifyBaseLogger } from 'fastify';
import {
  occurrenceId,
  plannedRunAt,
  plannedRunId,
  upcomingOccurrences,
  type AlarmAction,
  type AlarmWarning,
  type Settings,
} from '@alarm-actions/shared';
import type { DB } from './db.js';
import { DEFERRED, type Executor } from './executor.js';
import type { HaClient } from './ha/client.js';
import type { HaDirectory } from './ha/directory.js';
import type { ScriptRegistry } from './ha/scripts.js';
import type { UserDirectory } from './ha/users.js';
import type { HistoryStore, RunSubject } from './history.js';
import type { AlarmStore, StoredAlarm } from './store.js';

const MINUTE = 60_000;
const DAY = 86_400_000;
/** The timer is re-checked at least this often against the wall clock (TR-SCH-03). */
const MAX_SLEEP_MS = 60_000;
const PRUNE_INTERVAL_MS = 60 * MINUTE;
const TOMBSTONE_DAYS = 30;

export const REASON = {
  skipped: 'occurrence skipped',
  beforePast: 'before time already passed when the alarm was saved',
  grace: 'grace period exceeded',
} as const;

export type OccurrenceStatus = 'pending' | 'skipped' | 'fired';

export interface OccurrenceRow {
  id: string;
  alarm_id: string;
  scheduled_at: number;
  status: OccurrenceStatus;
  /** Action ids when the occurrence fired; later added actions do not run for it (FR-EXE-14). */
  action_ids: string | null;
  runs_done: number;
  updated_at: number;
}

export interface SchedulerDeps {
  db: DB;
  store: AlarmStore;
  history: HistoryStore;
  executor: Executor;
  ha: HaClient;
  directory: HaDirectory;
  users: UserDirectory;
  scripts: ScriptRegistry;
  settings: Settings;
  log: FastifyBaseLogger;
  now: () => number;
}

interface DueRun {
  occurrence: OccurrenceRow;
  alarm: StoredAlarm;
  runAt: number;
  subject: RunSubject;
}

/**
 * The scheduler (TR-SCH-02..06). Per alarm at most one occurrence is `pending` (the next one);
 * fired occurrences stay open until all their `before`/`at`/`after` runs have a record, so an
 * alarm can have several active occurrences. Planned runs are derived from the occurrences and
 * the alarm's actions; their records give exactly-once execution.
 *
 * Ticks are serialized. A tick fires due occurrences, brings the pending occurrences in line with
 * the alarms, then runs (or records) every due planned run.
 */
export class Scheduler {
  private stopped = true;
  private timer: NodeJS.Timeout | null = null;
  private pruneTimer: NodeJS.Timeout | null = null;
  private chain: Promise<void> = Promise.resolve();
  private queued: Promise<void> | null = null;

  constructor(private readonly d: SchedulerDeps) {
    d.store.onChange((id) => this.onAlarmChanged(id));
    d.directory.onTimeZoneChange(() => {
      this.reconcileAll();
      this.wake();
    });
    d.ha.onConnect(() => this.wake());
    d.users.onRefresh(() => this.checkOwners());
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    const interrupted = this.d.history.failInterrupted();
    if (interrupted > 0) this.d.log.warn(`${interrupted} script start(s) were interrupted by a restart`);
    this.prune();
    this.pruneTimer = setInterval(() => this.prune(), PRUNE_INTERVAL_MS);
    this.pruneTimer.unref();
    this.wake();
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    if (this.pruneTimer) clearInterval(this.pruneTimer);
    this.timer = null;
    this.pruneTimer = null;
    await this.chain;
  }

  /** Runs a tick after the current one; concurrent calls share one queued tick. */
  tick(): Promise<void> {
    if (this.queued) return this.queued;
    const next = this.chain.then(async () => {
      this.queued = null;
      try {
        await this.runTick();
      } catch (err) {
        this.d.log.error({ err }, 'Scheduler tick failed');
        this.arm(this.d.now() + MAX_SLEEP_MS);
      }
    });
    this.queued = next;
    this.chain = next;
    return next;
  }

  /** Ticks as soon as possible. */
  wake(): void {
    if (this.stopped) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.tick(), 0);
  }

  pendingOccurrence(alarmId: string): OccurrenceRow | null {
    return (
      (this.d.db
        .prepare("SELECT * FROM occurrences WHERE alarm_id = ? AND status = 'pending'")
        .get(alarmId) as OccurrenceRow | undefined) ?? null
    );
  }

  occurrences(alarmId: string): OccurrenceRow[] {
    return this.d.db
      .prepare('SELECT * FROM occurrences WHERE alarm_id = ? ORDER BY scheduled_at')
      .all(alarmId) as OccurrenceRow[];
  }

  /**
   * Save warnings (FR-EXE-09): `before` actions of the next occurrence that were already past
   * when the alarm was saved, and are therefore recorded as missed.
   */
  warningsFor(alarm: StoredAlarm): AlarmWarning[] {
    const occurrence = this.pendingOccurrence(alarm.id);
    if (!occurrence) return [];
    const warnings: AlarmWarning[] = [];
    for (const action of this.runnableActions(alarm, occurrence)) {
      if (action.timing !== 'before') continue;
      const record = this.d.history.get(plannedRunId(occurrence.id, action.id));
      if (record?.result !== 'missed') continue;
      const name = this.d.scripts.get(action.script_entity_id)?.name ?? action.script_entity_id;
      warnings.push({
        code: 'before_missed',
        alarm_action_id: action.id,
        message: `"${name}" (${action.offset_minutes} min before) is already past for the next alarm and will not run this time.`,
      });
    }
    return warnings;
  }

  /** A "run now" subject (FR-EXE-08); it goes through the same executor and limits. */
  manualSubject(alarm: StoredAlarm, action: AlarmAction, id: string): RunSubject {
    return {
      planned_run_id: `manual_${id}`,
      owner_user_id: alarm.owner_user_id,
      alarm_id: alarm.id,
      alarm_action_id: action.id,
      occurrence_id: null,
      script_entity_id: action.script_entity_id,
      planned_at: this.d.now(),
    };
  }

  /** Retention of history, finished occurrences and tombstones. */
  prune(): void {
    try {
      const { history_days, history_max } = this.d.settings;
      this.d.history.prune(history_days, history_max);
      this.d.db
        .prepare(
          "DELETE FROM occurrences WHERE runs_done = 1 AND status != 'pending' AND scheduled_at < ?",
        )
        .run(this.d.now() - history_days * DAY);
      this.d.store.pruneTombstones(TOMBSTONE_DAYS * DAY);
    } catch (err) {
      this.d.log.error({ err }, 'Pruning failed');
    }
  }

  /**
   * Owner check (FR-ACC-04): alarms of a deleted or deactivated user are disabled. They are not
   * enabled again when the user comes back.
   */
  checkOwners(): void {
    if (!this.d.users.loaded) return;
    for (const alarm of this.d.store.list()) {
      if (!alarm.enabled) continue;
      if (this.d.users.get(alarm.owner_user_id)?.isActive) continue;
      this.d.log.warn({ alarm: alarm.id }, 'Alarm disabled: its owner is deleted or inactive');
      this.d.store.update(alarm.id, { enabled: false, disabled_reason: 'owner_inactive' });
    }
  }

  private onAlarmChanged(id: string): void {
    const alarm = this.d.store.get(id);
    // A deleted alarm's occurrences went with it (cascade); nothing of it runs any more.
    if (alarm) this.reconcileAlarm(alarm, true);
    this.wake();
  }

  private reconcileAll(): void {
    for (const alarm of this.d.store.list()) this.reconcileAlarm(alarm, false);
  }

  /**
   * Keeps the pending occurrence equal to the alarm's next occurrence. A pending occurrence
   * whose time has come is left alone; the tick fires it. After an edit (or for a new pending
   * occurrence), `before` runs that are already past are recorded as missed (FR-EXE-09).
   */
  private reconcileAlarm(alarm: StoredAlarm, edited: boolean): void {
    const tz = this.d.directory.timeZone;
    if (!tz) return;
    const now = this.d.now();
    let pending = this.pendingOccurrence(alarm.id);
    if (pending && pending.scheduled_at <= now) return;

    const desired = alarm.enabled ? (upcomingOccurrences(alarm, tz, now, 1)[0] ?? null) : null;
    let created = false;
    if (pending && pending.scheduled_at !== desired) {
      this.d.db.prepare('DELETE FROM occurrences WHERE id = ?').run(pending.id);
      pending = null;
    }
    if (!pending && desired !== null) {
      this.d.db
        .prepare(
          `INSERT INTO occurrences (id, alarm_id, scheduled_at, status, action_ids, runs_done, updated_at)
           VALUES (?, ?, ?, 'pending', NULL, 0, ?) ON CONFLICT(id) DO NOTHING`,
        )
        .run(occurrenceId(alarm.id, desired), alarm.id, desired, now);
      pending = this.pendingOccurrence(alarm.id);
      created = true;
    }
    if (pending && (created || edited)) {
      for (const action of this.runnableActions(alarm, pending)) {
        if (action.timing !== 'before') continue;
        const runAt = plannedRunAt(pending.scheduled_at, action)!;
        if (runAt <= now) {
          this.d.history.record(this.subject(alarm, pending, action, runAt), 'missed', REASON.beforePast);
        }
      }
    }
  }

  /** The pending occurrence's time has come: it fires, or is skipped (FR-ALM-05, 06). */
  private fire(occurrence: OccurrenceRow): void {
    const alarm = this.d.store.get(occurrence.alarm_id);
    if (!alarm) return;
    const now = this.d.now();
    if (!alarm.enabled) {
      this.d.db.prepare('DELETE FROM occurrences WHERE id = ?').run(occurrence.id);
      return;
    }
    if (alarm.skip_next && alarm.repeat_days.length > 0) {
      this.d.db.transaction(() => {
        for (const action of this.runnableActions(alarm, occurrence)) {
          const runAt = plannedRunAt(occurrence.scheduled_at, action)!;
          this.d.history.record(this.subject(alarm, occurrence, action, runAt), 'skipped', REASON.skipped);
        }
        this.d.db
          .prepare("UPDATE occurrences SET status = 'skipped', runs_done = 1, updated_at = ? WHERE id = ?")
          .run(now, occurrence.id);
      })();
      this.d.log.info({ alarm: alarm.id, occurrence: occurrence.id }, 'Occurrence skipped');
      // System change: bumps revision and change_seq so clients see the flag reset.
      this.d.store.update(alarm.id, { skip_next: false });
      return;
    }
    this.d.db
      .prepare("UPDATE occurrences SET status = 'fired', action_ids = ?, updated_at = ? WHERE id = ?")
      .run(JSON.stringify(alarm.actions.map((a) => a.id)), now, occurrence.id);
    this.d.log.info({ alarm: alarm.id, occurrence: occurrence.id }, 'Alarm fired');
    if (alarm.repeat_days.length === 0) {
      // One-time alarm: disabled, but the fired occurrence still completes its runs.
      this.d.store.update(alarm.id, { enabled: false, disabled_reason: 'fired' });
    }
  }

  /** Enabled `before`/`at`/`after` actions that belong to the occurrence. */
  private runnableActions(alarm: StoredAlarm, occurrence: OccurrenceRow): AlarmAction[] {
    let actions = alarm.actions;
    if (occurrence.action_ids !== null) {
      const ids = new Set(JSON.parse(occurrence.action_ids) as string[]);
      actions = actions.filter((a) => ids.has(a.id));
    }
    return actions.filter((a) => a.enabled && a.timing !== 'after_dismiss');
  }

  private subject(
    alarm: StoredAlarm,
    occurrence: OccurrenceRow,
    action: AlarmAction,
    runAt: number,
  ): RunSubject {
    return {
      planned_run_id: plannedRunId(occurrence.id, action.id),
      owner_user_id: alarm.owner_user_id,
      alarm_id: alarm.id,
      alarm_action_id: action.id,
      occurrence_id: occurrence.id,
      script_entity_id: action.script_entity_id,
      planned_at: runAt,
    };
  }

  private async runTick(): Promise<void> {
    if (this.stopped) return;
    const { db, history, settings } = this.d;
    const now = this.d.now();

    const due = db
      .prepare("SELECT * FROM occurrences WHERE status = 'pending' AND scheduled_at <= ? ORDER BY scheduled_at")
      .all(now) as OccurrenceRow[];
    for (const occurrence of due) this.fire(occurrence);
    this.reconcileAll();

    let nextAt = Infinity;
    const runs: DueRun[] = [];
    const open = db
      .prepare("SELECT * FROM occurrences WHERE runs_done = 0 AND status IN ('pending', 'fired')")
      .all() as OccurrenceRow[];
    for (const occurrence of open) {
      const alarm = this.d.store.get(occurrence.alarm_id);
      if (!alarm) continue;
      if (occurrence.status === 'pending') nextAt = Math.min(nextAt, occurrence.scheduled_at);
      const planned = this.runnableActions(alarm, occurrence).map((action) => {
        const runAt = plannedRunAt(occurrence.scheduled_at, action)!;
        return { occurrence, alarm, runAt, subject: this.subject(alarm, occurrence, action, runAt) };
      });
      const done = history.existing(planned.map((p) => p.subject.planned_run_id));
      const remaining = planned.filter((p) => !done.has(p.subject.planned_run_id));
      if (remaining.length === 0 && occurrence.status !== 'pending') {
        db.prepare('UPDATE occurrences SET runs_done = 1, updated_at = ? WHERE id = ?').run(now, occurrence.id);
      }
      for (const run of remaining) {
        if (run.runAt <= now) runs.push(run);
        else nextAt = Math.min(nextAt, run.runAt);
      }
    }

    const grace = settings.grace_period_minutes * MINUTE;
    runs.sort((a, b) => a.runAt - b.runAt);
    for (const run of runs) {
      if (this.stopped) return;
      try {
        if (
          run.occurrence.status === 'pending' &&
          run.alarm.skip_next &&
          run.alarm.repeat_days.length > 0
        ) {
          history.record(run.subject, 'skipped', REASON.skipped);
        } else if (this.d.now() - run.runAt > grace) {
          // Also covers Core being unreachable for the whole grace period (TR-HA-05).
          history.record(run.subject, 'missed', REASON.grace);
          this.d.log.warn({ run: run.subject.planned_run_id }, 'Planned run missed');
        } else {
          const outcome = await this.d.executor.run(run.subject);
          if (outcome === DEFERRED) nextAt = Math.min(nextAt, run.runAt + grace + 1);
        }
      } catch (err) {
        // TR-EXE-03: one failing run never blocks the others.
        this.d.log.error({ err, run: run.subject.planned_run_id }, 'Planned run failed');
      }
    }
    this.arm(nextAt);
  }

  private arm(at: number): void {
    if (this.stopped) return;
    if (this.timer) clearTimeout(this.timer);
    const delay = Math.max(0, Math.min(at - this.d.now(), MAX_SLEEP_MS));
    this.timer = setTimeout(() => void this.tick(), delay);
    this.timer.unref();
  }
}
