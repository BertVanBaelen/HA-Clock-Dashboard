import type { FastifyBaseLogger } from 'fastify';

/** Where Core's own websocket API is, for validating app tokens (TR-API-12). */
export interface CoreTarget {
  url: string;
  /** Core serves its own certificate, which is not issued for the internal `homeassistant` name. */
  tlsInsecure: boolean;
}

const FALLBACK: CoreTarget = { url: 'ws://homeassistant:8123/api/websocket', tlsInsecure: false };
const TIMEOUT_MS = 5000;

export interface CoreTargetOptions {
  /** Fixed URL (`HA_CORE_WS_URL`, development and tests); skips the Supervisor lookup. */
  fixedUrl: string | null;
  supervisorToken: string | null;
  supervisorUrl?: string;
  log: FastifyBaseLogger;
}

/**
 * Core's port and SSL setting come from the Supervisor (`GET /core/info`), so a Home Assistant on
 * another port than 8123 or with `ssl_certificate` set still works. The answer is kept until a
 * connection fails, then looked up again.
 */
export class CoreTargetResolver {
  private cached: CoreTarget | null = null;
  private pending: Promise<CoreTarget> | null = null;

  constructor(private readonly opts: CoreTargetOptions) {}

  get(): Promise<CoreTarget> {
    if (this.opts.fixedUrl) return Promise.resolve({ url: this.opts.fixedUrl, tlsInsecure: false });
    if (this.cached) return Promise.resolve(this.cached);
    this.pending ??= this.lookup().finally(() => (this.pending = null));
    return this.pending;
  }

  /** Forget the looked-up target, for example after Core refused the connection. */
  invalidate(): void {
    this.cached = null;
  }

  private async lookup(): Promise<CoreTarget> {
    if (!this.opts.supervisorToken) return FALLBACK;
    try {
      const res = await fetch(new URL('/core/info', this.opts.supervisorUrl ?? 'http://supervisor'), {
        headers: { authorization: `Bearer ${this.opts.supervisorToken}` },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (!res.ok) throw new Error(`Supervisor answered ${res.status}`);
      const body = (await res.json()) as { data?: { port?: unknown; ssl?: unknown } };
      const port = body.data?.port;
      if (typeof port !== 'number' || !Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error('Supervisor gave no Core port');
      }
      const ssl = body.data?.ssl === true;
      const target = {
        url: `${ssl ? 'wss' : 'ws'}://homeassistant:${port}/api/websocket`,
        tlsInsecure: ssl,
      };
      this.opts.log.info({ url: target.url }, 'Home Assistant Core for token checks');
      this.cached = target;
      return target;
    } catch (err) {
      this.opts.log.warn(
        { err: (err as Error).message },
        'Could not get the Core port from the Supervisor; trying port 8123',
      );
      return FALLBACK;
    }
  }
}
