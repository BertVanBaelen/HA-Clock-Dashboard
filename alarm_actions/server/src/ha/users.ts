import type { FastifyBaseLogger } from 'fastify';
import type { HaClient } from './client.js';

interface AuthListUser {
  id: string;
  name?: string | null;
  username?: string | null;
  is_active?: boolean;
  is_owner?: boolean;
  system_generated?: boolean;
  group_ids?: string[];
}

export interface HaUser {
  id: string;
  name: string;
  isAdmin: boolean;
  isActive: boolean;
}

/** Minimum time between refreshes caused by an unknown user id. */
const UNKNOWN_REFRESH_MS = 10_000;

/**
 * HA users from `config/auth/list` (TR-HA-04): name, admin flag (`system-admin` group or owner)
 * and active state. Loaded after every connect, refreshed periodically and when an unknown user
 * shows up (a user created after the last refresh).
 */
export class UserDirectory {
  private users: Map<string, HaUser> | null = null;
  private lastRefresh = 0;
  private inflight: Promise<void> | null = null;
  private timer: NodeJS.Timeout | null = null;
  private readonly listeners = new Set<() => void>();

  constructor(
    private readonly ha: HaClient,
    private readonly log: FastifyBaseLogger,
    private readonly now: () => number,
  ) {
    ha.onConnect(() => this.refresh());
  }

  /** True once the user list was read at least once. */
  get loaded(): boolean {
    return this.users !== null;
  }

  get(id: string): HaUser | undefined {
    return this.users?.get(id);
  }

  /** Called after every successful refresh (owner check, phase 3). */
  onRefresh(listener: () => void): void {
    this.listeners.add(listener);
  }

  refresh(): Promise<void> {
    this.inflight ??= this.load().finally(() => {
      this.inflight = null;
    });
    return this.inflight;
  }

  private async load(): Promise<void> {
    this.lastRefresh = this.now();
    const list = await this.ha.sendCommand<AuthListUser[]>({ type: 'config/auth/list' });
    const users = new Map<string, HaUser>();
    for (const u of list) {
      users.set(u.id, {
        id: u.id,
        name: u.name || u.username || u.id,
        isAdmin: u.is_owner === true || (u.group_ids ?? []).includes('system-admin'),
        isActive: u.is_active !== false,
      });
    }
    this.users = users;
    for (const listener of this.listeners) {
      try {
        listener();
      } catch (err) {
        this.log.error({ err }, 'User refresh listener failed');
      }
    }
  }

  /**
   * Looks up a user, refreshing the list (at most every 10 s) when the id is unknown.
   * Returns `undefined` for an unknown user, `null` when users were never loaded.
   */
  async resolve(id: string): Promise<HaUser | undefined | null> {
    const known = this.get(id);
    if (known) return known;
    if (this.ha.connected && this.now() - this.lastRefresh >= UNKNOWN_REFRESH_MS) {
      try {
        await this.refresh();
      } catch (err) {
        this.log.warn({ err: (err as Error).message }, 'Could not refresh Home Assistant users');
      }
    }
    if (!this.loaded) return null;
    return this.get(id);
  }

  startPeriodic(intervalMs = 5 * 60_000): void {
    this.stopPeriodic();
    this.timer = setInterval(() => {
      if (!this.ha.connected) return;
      this.refresh().catch((err: Error) =>
        this.log.warn({ err: err.message }, 'Could not refresh Home Assistant users'),
      );
    }, intervalMs);
    this.timer.unref();
  }

  stopPeriodic(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
}
