import { Readable } from 'node:stream';
import type { ReadableStream } from 'node:stream/web';
import type { FastifyBaseLogger } from 'fastify';
import type { AppPackage } from '@alarm-actions/shared';

const TIMEOUT_MS = 2000;
/** Time to get the APK response headers; the body itself may take longer. */
const APK_TIMEOUT_MS = 10_000;
const CACHE_MS = 30_000;

const UNAVAILABLE: AppPackage = {
  available: false,
  version: null,
  version_code: null,
  sha256: null,
  size: null,
};

export interface ApkStream {
  body: Readable;
  size: number | null;
  version: string | null;
}

const str = (v: unknown) => (typeof v === 'string' && v.length <= 100 ? v : null);
const int = (v: unknown) => (typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : null);

/**
 * The app add-on on the internal network (TR-APP-03, 04): detected by asking it for `/info`; its
 * APK is streamed through the dashboard's own (authenticated) API, so the app add-on needs no
 * ports or ingress (SEC-09, SEC-12). The info is cached so polling does not reach it every time.
 */
export class AppProbe {
  private cached: { at: number; info: AppPackage } | null = null;
  private pending: Promise<AppPackage> | null = null;

  constructor(
    private readonly baseUrl: string | null,
    private readonly log: FastifyBaseLogger,
    private readonly now: () => number = Date.now,
  ) {}

  info(): Promise<AppPackage> {
    if (!this.baseUrl) return Promise.resolve(UNAVAILABLE);
    if (this.fresh()) return Promise.resolve(this.cached!.info);
    this.pending ??= this.fetchInfo(this.baseUrl).then((info) => {
      this.cached = { at: this.now(), info };
      this.pending = null;
      return info;
    });
    return this.pending;
  }

  /**
   * Last known availability without waiting; starts a refresh when the cache is stale. Used by
   * `/info` so opening the panel never waits for the app add-on (or its DNS timeout).
   */
  peek(): boolean {
    if (this.baseUrl && !this.fresh()) void this.info();
    return this.cached?.info.available ?? false;
  }

  /** The APK from the app add-on, or null when it is not available. */
  async apk(): Promise<ApkStream | null> {
    if (!this.baseUrl) return null;
    try {
      const res = await fetch(new URL('/apk', this.baseUrl), {
        signal: AbortSignal.timeout(APK_TIMEOUT_MS),
      });
      if (!res.ok || !res.body) return null;
      const length = Number(res.headers.get('content-length'));
      const info = await this.info();
      return {
        body: Readable.fromWeb(res.body as ReadableStream<Uint8Array>),
        size: Number.isSafeInteger(length) && length > 0 ? length : null,
        version: info.version,
      };
    } catch (err) {
      this.log.warn({ err: (err as Error).message }, 'Could not get the APK from the app add-on');
      return null;
    }
  }

  private fresh(): boolean {
    return this.cached !== null && this.now() - this.cached.at < CACHE_MS;
  }

  private async fetchInfo(baseUrl: string): Promise<AppPackage> {
    try {
      const res = await fetch(new URL('/info', baseUrl), {
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (!res.ok) return UNAVAILABLE;
      const body = (await res.json()) as Record<string, unknown>;
      const sha = str(body.sha256);
      return {
        available: true,
        version: str(body.version),
        version_code: int(body.version_code),
        sha256: sha && /^[0-9a-f]{64}$/.test(sha) ? sha : null,
        size: int(body.size),
      };
    } catch (err) {
      this.log.debug({ err: (err as Error).message }, 'App add-on not reachable');
      return UNAVAILABLE;
    }
  }
}
