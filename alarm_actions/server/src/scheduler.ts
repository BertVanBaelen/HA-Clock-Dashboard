import crypto from 'node:crypto';
import type { FastifyBaseLogger } from 'fastify';
import {
  occurrenceId,
  plannedRunAt,
  plannedRunId,
  upcomingOccurrences,
  type AlarmAction,
  type AlarmWarning,
  type CurrentOccurrence,
  type OccurrenceEventResponse,
  type OccurrenceEventType,
  type OccurrenceStatus,
  type Settings,
} from '@alarm-actions/shared';
import type { ClientStore } from './clients.js';
import type { DB } from './db.js';
import { unavailable, unprocessable } from './errors.js';
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
/** Early dismissal window; earlier than this, use skip (analysis §5). */
export const EARLY_DISMISS_MS = 60 * MINUTE;
/** Allowed clock difference for reported times (SEC-06). */
const CLOCK_SKEW_MS = 2 * MINUTE;

export const REASON = {
  skipped: 'occurrence skipped',
  beforePast: 'before time already passed when the alarm was saved',
  grace: 'grace period exceeded',
  dismissedEarly: 'dismissed early',
  away: 'user away',
  expired: 'alarm not dismissed in time',
} as const;

export type EventSource = 'panel' | 'app' | 'api';

/** Bus events (FR-EVT-04, TR-EVT-01): ids and timestamps only (SEC-06). */
export const BUS_EVENTS = {
  dismissed: 'alarm_actions_alarm_dismissed',
  snoozed: 'alarm_actions_alarm_snoozed',
} as const;

export interface OccurrenceRow {
  id: string;
  alarm_id: string;
  scheduled_at: number;
  status: OccurrenceStatus;
  /** Action ids when the occurrence fired; later added actions do not run for it (FR-EXE-14). */
  action_ids: string | null;
  runs_done: number;
  updated_at: number;
  /** Dismissal time (client-reported, clamped); base of the `after_dismiss` delays. */
  dismissed_at: number | null;
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
  clients: ClientStore;
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
    // A phone's time zone report can start or end "away" (TR-SCH-09).
    d.clients.onChange(() => this.wake());
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

  occurrence(id: string): OccurrenceRow | null {
    return (
      (this.d.db.prepare('SELECT * FROM occurrences WHERE id = ?').get(id) as OccurrenceRow | undefined) ??
      null
    );
  }

  /**
   * Next time the alarm rings, skipping occurrences that were already dismissed early and, for
   * repeating alarms with `skip_next`, the skipped one. Null when disabled or the HA time zone is
   * not known yet.
   */
  nextRing(alarm: StoredAlarm): number | null {
    const tz = this.d.directory.timeZone;
    if (!alarm.enabled || !tz) return null;
    const handled = this.d.db.prepare(
      "SELECT 1 FROM occurrences WHERE id = ? AND status NOT IN ('pending', 'skipped')",
    );
    const list = upcomingOccurrences(alarm, tz, this.d.now(), 4).filter(
      (t) => handled.get(occurrenceId(alarm.id, t)) === undefined,
    );
    const skip = alarm.skip_next && alarm.repeat_days.length > 0;
    return (skip ? list[1] : list[0]) ?? null;
  }

  /**
   * The occurrence the owner can dismiss now: a ringing one (fired/snoozed, until the dismissal
   * timeout), else an early-dismissed one that is still to come, else the pending one within the
   * early-dismissal window (not when it will be skipped).
   */
  currentOccurrence(alarm: StoredAlarm): CurrentOccurrence | null {
    const now = this.d.now();
    const timeout = this.d.settings.dismiss_timeout_minutes * MINUTE;
    const rows = this.d.db
      .prepare(
        `SELECT * FROM occurrences WHERE alarm_id = ? AND (
           (status IN ('fired', 'snoozed') AND scheduled_at > ?) OR
           (status = 'dismissed' AND scheduled_at > ?) OR status = 'pending')
         ORDER BY scheduled_at DESC`,
      )
      .all(alarm.id, now - timeout, now) as OccurrenceRow[];
    const pick =
      rows.find((r) => r.status === 'fired' || r.status === 'snoozed') ??
      rows.find((r) => r.status === 'dismissed') ??
      rows.find(
        (r) =>
          r.status === 'pending' &&
          alarm.enabled &&
          r.scheduled_at - EARLY_DISMISS_MS <= now &&
          !(alarm.skip_next && alarm.repeat_days.length > 0),
      );
    if (!pick) return null;
    return {
      id: pick.id,
      scheduled_at: new Date(pick.scheduled_at).toISOString(),
      status: pick.status === 'pending' && pick.scheduled_at <= now ? 'fired' : (pick.status as CurrentOccurrence['status']),
    };
  }

  /**
   * A dismissed / snoozed / timed_out report (FR-EVT-01..03, SEC-06), for an alarm the caller
   * owns. Checks that the occurrence is one the alarm produces and that the reported time is
   * plausible, records the event (idempotent) and moves the occurrence along its state machine.
   * The tick then starts or records the `after_dismiss` runs.
   */
  report(
    alarm: StoredAlarm,
    occId: string,
    type: OccurrenceEventType,
    input: { occurredAt?: number; source: EventSource; userId: string },
  ): OccurrenceEventResponse {
    const scheduledAt = parseOccurrenceTime(alarm.id, occId);
    if (scheduledAt === null) throw invalidOccurrence();
    const now = this.d.now();
    let at = input.occurredAt ?? now;
    if (at > now + CLOCK_SKEW_MS) throw implausible('lies in the future');
    at = Math.min(at, now);
    if (at < scheduledAt - EARLY_DISMISS_MS) throw implausible('is too long before the alarm');
    if (type !== 'dismissed' && at < scheduledAt - CLOCK_SKEW_MS) {
      throw implausible('is before the alarm rang');
    }

    let row = this.occurrence(occId);
    if (row && row.alarm_id !== alarm.id) throw invalidOccurrence();
    if (row?.status === 'pending' && row.scheduled_at <= now) {
      // Its time has come but the tick did not get to it yet.
      this.fire(row);
      row = this.occurrence(occId);
    }
    if (!row) {
      const tz = this.d.directory.timeZone;
      if (!tz) throw unavailable('Home Assistant is not reachable yet. Try again shortly.');
      const produced = upcomingOccurrences(alarm, tz, scheduledAt - 1, 1)[0] === scheduledAt;
      if (!produced || scheduledAt > now) throw invalidOccurrence();
    }
    if (row?.status === 'skipped') throw occurrenceSkipped();
    if (
      row?.status === 'pending' &&
      type === 'dismissed' &&
      alarm.skip_next &&
      alarm.repeat_days.length > 0
    ) {
      throw occurrenceSkipped();
    }

    const timeout = this.d.settings.dismiss_timeout_minutes * MINUTE;
    let early = false;
    const outcome = this.d.db.transaction((): { status: OccurrenceStatus; duplicate: boolean } => {
      const inserted =
        this.d.db
          .prepare(
            `INSERT INTO occurrence_events (id, occurrence_id, alarm_id, owner_user_id, type, occurred_at,
               received_at, source, user_id)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING`,
          )
          .run(crypto.randomUUID(), occId, alarm.id, alarm.owner_user_id, type, at, now, input.source, input.userId)
          .changes > 0;
      if (!inserted) return { status: row?.status ?? 'expired', duplicate: true };

      const set = (status: OccurrenceStatus, dismissedAt: number | null = null) => {
        this.d.db
          .prepare('UPDATE occurrences SET status = ?, dismissed_at = ?, updated_at = ? WHERE id = ?')
          .run(status, dismissedAt, now, occId);
        return status;
      };

      if (!row) {
        // A past occurrence the server never tracked (it was down or the alarm was changed):
        // only its after_dismiss runs follow, and stale ones are recorded missed by the tick.
        let status: OccurrenceStatus = 'expired';
        if (type === 'dismissed' && at <= scheduledAt + timeout) status = 'dismissed';
        if (type === 'snoozed' && now < scheduledAt + timeout) status = 'snoozed';
        const ids = alarm.actions.filter((a) => a.timing === 'after_dismiss').map((a) => a.id);
        this.d.db
          .prepare(
            `INSERT INTO occurrences (id, alarm_id, scheduled_at, status, action_ids, runs_done, updated_at, dismissed_at)
             VALUES (?, ?, ?, ?, ?, 0, ?, ?)`,
          )
          .run(occId, alarm.id, scheduledAt, status, JSON.stringify(ids), now, status === 'dismissed' ? at : null);
        return { status, duplicate: false };
      }

      switch (row.status) {
        case 'pending':
          if (type !== 'dismissed') return { status: row.status, duplicate: false };
          // Early dismissal (FR-EXE-11, TR-SCH-08): acts as a skip of before/at/after.
          for (const action of this.runnableActions(alarm, row)) {
            const runAt = plannedRunAt(row.scheduled_at, action)!;
            this.d.history.record(this.subject(alarm, row, action, runAt), 'skipped', REASON.dismissedEarly);
          }
          this.d.db
            .prepare('UPDATE occurrences SET action_ids = ? WHERE id = ?')
            .run(JSON.stringify(alarm.actions.map((a) => a.id)), occId);
          early = true;
          return { status: set('dismissed', at), duplicate: false };
        case 'fired':
        case 'snoozed':
          if (type === 'snoozed') return { status: set('snoozed'), duplicate: false };
          if (type === 'timed_out' || at > scheduledAt + timeout) {
            return { status: set('expired'), duplicate: false };
          }
          return { status: set('dismissed', at), duplicate: false };
        default:
          return { status: row.status, duplicate: false };
      }
    })();

    if (!outcome.duplicate) {
      this.d.log.info({ alarm: alarm.id, occurrence: occId, type, status: outcome.status }, 'Occurrence report');
      if (type !== 'timed_out') this.fireBusEvent(type, alarm.id, occId, scheduledAt, at);
    }
    if (early) {
      // System change: the phones learn about the dismissal on their next sync (and a one-time
      // alarm is done, like when it fires).
      this.d.store.update(
        alarm.id,
        alarm.repeat_days.length === 0 ? { enabled: false, disabled_reason: 'fired' } : {},
      );
    }
    this.wake();
    return { occurrence_id: occId, type, status: outcome.status, duplicate: outcome.duplicate };
  }

  private fireBusEvent(
    type: 'dismissed' | 'snoozed',
    alarmId: string,
    occId: string,
    scheduledAt: number,
    occurredAt: number,
  ): void {
    this.d.ha
      .sendCommand({
        type: 'fire_event',
        event_type: BUS_EVENTS[type],
        event_data: {
          alarm_id: alarmId,
          occurrence_id: occId,
          scheduled_at: new Date(scheduledAt).toISOString(),
          occurred_at: new Date(occurredAt).toISOString(),
        },
      })
      .catch((err: Error) => this.d.log.warn({ err: err.message }, 'Could not fire the event on the HA bus'));
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
      this.d.db
        .prepare('DELETE FROM occurrence_events WHERE received_at < ?')
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

    const desired = alarm.enabled ? this.nextUnhandled(alarm, tz, now) : null;
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

  /** The first coming occurrence that was not dismissed early (the next pending one). */
  private nextUnhandled(alarm: StoredAlarm, tz: string, now: number): number | null {
    const handled = this.d.db.prepare("SELECT 1 FROM occurrences WHERE id = ? AND status != 'pending'");
    for (const t of upcomingOccurrences(alarm, tz, now, 3)) {
      if (handled.get(occurrenceId(alarm.id, t)) === undefined) return t;
    }
    return null;
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

  /** Enabled `after_dismiss` actions that belong to the occurrence (FR-EXE-02). */
  private dismissActions(alarm: StoredAlarm, occurrence: OccurrenceRow): AlarmAction[] {
    let actions = alarm.actions;
    if (occurrence.action_ids !== null) {
      const ids = new Set(JSON.parse(occurrence.action_ids) as string[]);
      actions = actions.filter((a) => ids.has(a.id));
    }
    return actions.filter((a) => a.enabled && a.timing === 'after_dismiss');
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
    // Dismissal timeout (FR-EXE-03): a ringing occurrence nobody dismissed expires.
    const timeout = settings.dismiss_timeout_minutes * MINUTE;
    db.prepare(
      "UPDATE occurrences SET status = 'expired', updated_at = ? WHERE status IN ('fired', 'snoozed') AND scheduled_at + ? <= ?",
    ).run(now, timeout, now);
    this.reconcileAll();

    let nextAt = Infinity;
    const runs: DueRun[] = [];
    const open = db
      .prepare("SELECT * FROM occurrences WHERE runs_done = 0 AND status != 'skipped'")
      .all() as OccurrenceRow[];
    for (const occurrence of open) {
      const alarm = this.d.store.get(occurrence.alarm_id);
      if (!alarm) continue;
      if (occurrence.status === 'pending') nextAt = Math.min(nextAt, occurrence.scheduled_at);
      if (occurrence.status === 'fired' || occurrence.status === 'snoozed') {
        nextAt = Math.min(nextAt, occurrence.scheduled_at + timeout);
      }
      const planned = this.runnableActions(alarm, occurrence).map((action) => {
        const runAt = plannedRunAt(occurrence.scheduled_at, action)!;
        return { occurrence, alarm, runAt, subject: this.subject(alarm, occurrence, action, runAt) };
      });
      for (const action of this.dismissActions(alarm, occurrence)) {
        if (occurrence.status === 'dismissed' && occurrence.dismissed_at !== null) {
          // TR-SCH-07: dismissal time plus delay; a stale late report ends up missed (grace).
          const runAt = occurrence.dismissed_at + (action.delay_minutes ?? 0) * MINUTE;
          planned.push({ occurrence, alarm, runAt, subject: this.subject(alarm, occurrence, action, runAt) });
        } else if (occurrence.status === 'expired') {
          history.record(this.subject(alarm, occurrence, action, occurrence.scheduled_at + timeout), 'expired', REASON.expired);
        }
      }
      const done = history.existing(planned.map((p) => p.subject.planned_run_id));
      const remaining = planned.filter((p) => !done.has(p.subject.planned_run_id));
      if (remaining.length === 0 && (occurrence.status === 'dismissed' || occurrence.status === 'expired')) {
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
        } else if (this.d.clients.isAway(run.alarm.owner_user_id)) {
          // FR-EXE-12: the owner is in another time zone, so not at home.
          history.record(run.subject, 'skipped', REASON.away);
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

/** Scheduled time of an occurrence id `<alarm_id>_<YYYYMMDDTHHMMSSZ>`, null when malformed. */
export function parseOccurrenceTime(alarmId: string, id: string): number | null {
  const prefix = `${alarmId}_`;
  if (!id.startsWith(prefix)) return null;
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(id.slice(prefix.length));
  if (!m) return null;
  const [, y, mo, d, h, mi, s] = m.map(Number) as [number, number, number, number, number, number, number];
  const ms = Date.UTC(y, mo - 1, d, h, mi, s);
  return occurrenceId(alarmId, ms) === id ? ms : null;
}

const invalidOccurrence = () =>
  unprocessable('invalid_occurrence', 'This alarm has no occurrence at that time.');
const implausible = (why: string) =>
  unprocessable('implausible_time', `The reported time ${why}.`);
const occurrenceSkipped = () =>
  unprocessable('occurrence_skipped', 'This occurrence is skipped; it cannot be dismissed.');
