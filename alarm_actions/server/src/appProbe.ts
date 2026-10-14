import type { FastifyBaseLogger } from 'fastify';
import type { AppInfo } from '@alarm-actions/shared';

const TIMEOUT_MS = 2000;
const CACHE_MS = 30_000;

const UNAVAILABLE: AppInfo = { available: false, version: null };

/**
 * Detects the app add-on (TR-APP-03) by asking it for `/info` on the internal network. The answer
 * is cached so the panel's polling does not reach the app add-on on every request.
 */
export class AppProbe {
  private cached: { at: number; info: AppInfo } | null = null;
  private pending: Promise<AppInfo> | null = null;

  constructor(
    private readonly baseUrl: string | null,
    private readonly log: FastifyBaseLogger,
    private readonly now: () => number = Date.now,
  ) {}

  info(): Promise<AppInfo> {
    if (!this.baseUrl) return Promise.resolve(UNAVAILABLE);
    if (this.cached && this.now() - this.cached.at < CACHE_MS) return Promise.resolve(this.cached.info);
    this.pending ??= this.fetchInfo(this.baseUrl).then((info) => {
      this.cached = { at: this.now(), info };
      this.pending = null;
      return info;
    });
    return this.pending;
  }

  private async fetchInfo(baseUrl: string): Promise<AppInfo> {
    try {
      const res = await fetch(new URL('/info', baseUrl), { signal: AbortSignal.timeout(TIMEOUT_MS) });
      if (!res.ok) return UNAVAILABLE;
      const body = (await res.json()) as { version?: unknown };
      return { available: true, version: typeof body.version === 'string' ? body.version : null };
    } catch (err) {
      this.log.debug({ err: (err as Error).message }, 'App add-on not reachable');
      return UNAVAILABLE;
    }
  }
}
