import { LIMITS, type Client, type ClientInput } from '@alarm-actions/shared';
import type { DB } from './db.js';

interface ClientRow {
  id: string;
  user_id: string;
  name: string;
  app_version: string;
  time_zone: string;
  last_sync_seq: number;
  last_seen_at: number;
  created_at: number;
}

/** Canonical IANA name, so aliases (`US/Pacific`, `Etc/UTC`) compare equal to their target. */
export function canonicalTimeZone(tz: string): string {
  try {
    return new Intl.DateTimeFormat('en-US', { timeZone: tz }).resolvedOptions().timeZone;
  } catch {
    return tz;
  }
}

export function sameTimeZone(a: string, b: string): boolean {
  return a === b || canonicalTimeZone(a) === canonicalTimeZone(b);
}

/**
 * Registered phones ([Client], FR-APP-04) and away detection (FR-EXE-12, TR-SCH-09): the owner is
 * away while the time zone of their most recently seen phone differs from the HA time zone.
 * A phone's time zone is personal data and is never logged.
 */
export class ClientStore {
  private readonly listeners = new Set<(userId: string) => void>();

  constructor(
    private readonly db: DB,
    private readonly now: () => number,
    private readonly haTimeZone: () => string | null,
  ) {}

  /** Called when a user's latest time zone may have changed (the scheduler re-checks away). */
  onChange(listener: (userId: string) => void): void {
    this.listeners.add(listener);
  }

  /**
   * Creates or updates the phone. Returns null when the id belongs to another user (answered
   * with 404, like any foreign object). At the per-user cap the longest unseen phone is removed,
   * so reinstalling the app never locks a user out.
   */
  upsert(userId: string, input: ClientInput): Client | null {
    const now = this.now();
    const result = this.db.transaction((): ClientRow | null => {
      const existing = this.row(input.id);
      if (existing && existing.user_id !== userId) return null;
      if (existing) {
        this.db
          .prepare(
            `UPDATE clients SET name = ?, app_version = ?, time_zone = ?, last_sync_seq = ?, last_seen_at = ?
             WHERE id = ?`,
          )
          .run(input.name, input.app_version, input.time_zone, input.last_sync_seq, now, input.id);
      } else {
        const ids = this.db
          .prepare('SELECT id FROM clients WHERE user_id = ? ORDER BY last_seen_at DESC, created_at DESC')
          .all(userId) as Array<{ id: string }>;
        for (const old of ids.slice(LIMITS.clientsPerUser - 1)) {
          this.db.prepare('DELETE FROM clients WHERE id = ?').run(old.id);
        }
        this.db
          .prepare(
            `INSERT INTO clients (id, user_id, name, app_version, time_zone, last_sync_seq, last_seen_at, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
          )
          .run(input.id, userId, input.name, input.app_version, input.time_zone, input.last_sync_seq, now, now);
      }
      return this.row(input.id);
    })();
    if (!result) return null;
    for (const listener of this.listeners) listener(userId);
    return this.toApi(result);
  }

  list(userId: string): Client[] {
    return (
      this.db
        .prepare('SELECT * FROM clients WHERE user_id = ? ORDER BY last_seen_at DESC, created_at DESC')
        .all(userId) as ClientRow[]
    ).map((row) => this.toApi(row));
  }

  /** Removes one of the user's phones (it does not revoke its login). False when not theirs. */
  remove(userId: string, id: string): boolean {
    const removed =
      this.db.prepare('DELETE FROM clients WHERE id = ? AND user_id = ?').run(id, userId).changes > 0;
    if (removed) for (const listener of this.listeners) listener(userId);
    return removed;
  }

  /** The owner is away (FR-EXE-12). Never away while the HA time zone is unknown. */
  isAway(userId: string): boolean {
    const haTz = this.haTimeZone();
    if (!haTz) return false;
    const latest = this.db
      .prepare(
        'SELECT time_zone FROM clients WHERE user_id = ? ORDER BY last_seen_at DESC, created_at DESC LIMIT 1',
      )
      .get(userId) as { time_zone: string } | undefined;
    return latest !== undefined && !sameTimeZone(latest.time_zone, haTz);
  }

  private row(id: string): ClientRow | null {
    return (this.db.prepare('SELECT * FROM clients WHERE id = ?').get(id) as ClientRow | undefined) ?? null;
  }

  private toApi(row: ClientRow): Client {
    const haTz = this.haTimeZone();
    return {
      id: row.id,
      name: row.name,
      app_version: row.app_version,
      time_zone: row.time_zone,
      last_sync_seq: row.last_sync_seq,
      last_seen_at: new Date(row.last_seen_at).toISOString(),
      away: haTz !== null && !sameTimeZone(row.time_zone, haTz),
    };
  }
}
