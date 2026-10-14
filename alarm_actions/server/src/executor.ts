import type { FastifyBaseLogger } from 'fastify';
import type { ExecutionResult, Settings } from '@alarm-actions/shared';
import { HaCommandError, NotConnectedError, type HaClient } from './ha/client.js';
import type { ScriptRegistry } from './ha/scripts.js';
import type { UserDirectory } from './ha/users.js';
import type { HistoryStore, RunSubject } from './history.js';

export interface RunOutcome {
  result: ExecutionResult;
  reason: string | null;
}

/** Not run yet because Core (or its data) is not available; the caller retries. */
export const DEFERRED = 'deferred' as const;

/**
 * Starts scripts (TR-EXE-01..06). Every run, scheduled or "run now", goes through the same
 * checks: owner active, script still labeled, rate limit and duplicate suppression, then a
 * write-ahead `running` record and `script.turn_on` without any data (SEC-04).
 */
export class Executor {
  constructor(
    private readonly ha: HaClient,
    private readonly users: UserDirectory,
    private readonly scripts: ScriptRegistry,
    private readonly history: HistoryStore,
    private readonly settings: Settings,
    private readonly log: FastifyBaseLogger,
  ) {}

  async run(subject: RunSubject): Promise<RunOutcome | typeof DEFERRED> {
    if (!this.ha.connected || !this.users.loaded || !this.scripts.ready) return DEFERRED;

    const owner = this.users.get(subject.owner_user_id);
    if (!owner?.isActive) return this.outcome(subject, 'skipped', 'owner inactive');
    if (!this.scripts.isAvailable(subject.script_entity_id)) {
      return this.outcome(subject, 'unavailable', 'script deleted or label removed');
    }

    const started = this.history.beginStart(subject, {
      maxStartsPerMinute: this.settings.max_script_starts_per_minute,
      minIntervalMs: this.settings.min_script_interval_seconds * 1000,
    });
    if ('existing' in started) {
      return { result: started.existing.result, reason: started.existing.reason };
    }
    if ('refused' in started) {
      this.log.warn(
        { script: subject.script_entity_id, run: subject.planned_run_id },
        `Script start dropped: ${started.refused}`,
      );
      return { result: 'skipped', reason: started.refused };
    }

    try {
      await this.ha.sendCommand({
        type: 'call_service',
        domain: 'script',
        service: 'turn_on',
        target: { entity_id: subject.script_entity_id },
      });
      this.history.finish(subject.planned_run_id, 'success', null);
      this.log.info(
        { script: subject.script_entity_id, run: subject.planned_run_id },
        'Script started',
      );
      return { result: 'success', reason: null };
    } catch (err) {
      if (err instanceof NotConnectedError) {
        this.history.abandon(subject.planned_run_id);
        return DEFERRED;
      }
      // A lost connection after sending may or may not have started the script; never retry.
      const reason =
        err instanceof HaCommandError ? err.message : (err as Error).message || 'unknown error';
      this.history.finish(subject.planned_run_id, 'failed', reason);
      this.log.error(
        { script: subject.script_entity_id, run: subject.planned_run_id, reason },
        'Script start failed',
      );
      return { result: 'failed', reason };
    }
  }

  private outcome(subject: RunSubject, result: ExecutionResult, reason: string): RunOutcome {
    const record = this.history.record(subject, result, reason);
    this.log.info({ script: subject.script_entity_id, run: subject.planned_run_id }, `Run ${result}: ${reason}`);
    return { result: record.result, reason: record.reason };
  }
}
