import crypto from 'node:crypto';
import WebSocket from 'ws';
import type { FastifyBaseLogger } from 'fastify';
import { HttpError, tooManyRequests, unavailable } from './errors.js';
import type { CoreTarget } from './coreTarget.js';

const CACHE_MS = 60_000;
const CACHE_MAX = 1000;
const TIMEOUT_MS = 5000;
/** Validations (cache misses) per client address and in total, per minute (SEC-13). */
export const VALIDATIONS_PER_IP_PER_MINUTE = 30;
export const VALIDATIONS_PER_MINUTE = 120;

export const bearerRequired = () =>
  new HttpError(
    401,
    'unauthorized',
    'A valid Home Assistant access token is required.',
    {},
    { 'www-authenticate': 'Bearer' },
  );

export interface TokenValidatorOptions {
  /** Core's websocket API; looked up per validation so a changed port is picked up. */
  target: () => Promise<CoreTarget>;
  /** Called when Core could not be reached, so the target is looked up again next time. */
  onUnreachable?: () => void;
  log: FastifyBaseLogger;
  now: () => number;
  timeoutMs?: number;
}

class Window {
  start = 0;
  count = 0;
}

/**
 * Bearer tokens of the external API (TR-API-12, SEC-02, SEC-13). A token is checked by logging in
 * to Core's websocket API with it and asking `auth/current_user`; the answer (also a rejection) is
 * cached for 60 s under the token's SHA-256, never the token itself. Tokens are never logged.
 * Validations are rate limited per client address and in total, so a flood of made-up tokens
 * cannot hammer Core (or trip HA's own login ban for the add-on's address).
 */
export class TokenValidator {
  private readonly cache = new Map<string, { at: number; userId: string | null }>();
  private readonly inflight = new Map<string, Promise<string | null>>();
  private readonly perIp = new Map<string, Window>();
  private readonly total = new Window();

  constructor(private readonly opts: TokenValidatorOptions) {}

  /** HA user id of the token, or null when Core rejects it. Throws 429 or 503. */
  async validate(token: string, address: string): Promise<string | null> {
    const key = crypto.createHash('sha256').update(token).digest('hex');
    const now = this.opts.now();
    const cached = this.cache.get(key);
    if (cached && now - cached.at < CACHE_MS) return cached.userId;

    const running = this.inflight.get(key);
    if (running) return running;

    this.limit(address, now);
    const promise = this.ask(token)
      .then((userId) => {
        this.cache.delete(key);
        this.cache.set(key, { at: this.opts.now(), userId });
        if (this.cache.size > CACHE_MAX) this.cache.delete(this.cache.keys().next().value!);
        return userId;
      })
      .finally(() => this.inflight.delete(key));
    this.inflight.set(key, promise);
    return promise;
  }

  private limit(address: string, now: number): void {
    if (this.perIp.size > 10_000) this.perIp.clear();
    let ip = this.perIp.get(address);
    if (!ip) {
      ip = new Window();
      this.perIp.set(address, ip);
    }
    for (const [window, max] of [
      [ip, VALIDATIONS_PER_IP_PER_MINUTE],
      [this.total, VALIDATIONS_PER_MINUTE],
    ] as const) {
      if (now - window.start >= 60_000) {
        window.start = now;
        window.count = 0;
      }
      if (window.count >= max) {
        throw tooManyRequests(Math.max(1, Math.ceil((window.start + 60_000 - now) / 1000)));
      }
    }
    ip.count++;
    this.total.count++;
  }

  /** One short websocket session: auth, `auth/current_user`, close. */
  private async ask(token: string): Promise<string | null> {
    const target = await this.opts.target();
    try {
      return await this.session(token, target);
    } catch (err) {
      this.opts.onUnreachable?.();
      throw err;
    }
  }

  private session(token: string, target: CoreTarget): Promise<string | null> {
    return new Promise((resolve, reject) => {
      let settled = false;
      const ws = new WebSocket(target.url, {
        handshakeTimeout: this.opts.timeoutMs ?? TIMEOUT_MS,
        rejectUnauthorized: !target.tlsInsecure,
      });
      const finish = (err: Error | null, userId: string | null = null) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        ws.removeAllListeners('message');
        ws.on('error', () => {});
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.terminate();
        if (err) reject(err);
        else resolve(userId);
      };
      const down = () =>
        unavailable('Home Assistant is not reachable to check the login. Try again shortly.');
      const timer = setTimeout(() => finish(down()), this.opts.timeoutMs ?? TIMEOUT_MS);
      ws.on('error', (err) => {
        this.opts.log.warn({ err: err.message }, 'Token check: Home Assistant not reachable');
        finish(down());
      });
      ws.on('close', () => finish(down()));
      ws.on('message', (raw) => {
        let msg: { type?: string; id?: number; success?: boolean; result?: { id?: unknown } };
        try {
          msg = JSON.parse(raw.toString()) as typeof msg;
        } catch {
          return finish(down());
        }
        if (msg.type === 'auth_required') {
          ws.send(JSON.stringify({ type: 'auth', access_token: token }));
        } else if (msg.type === 'auth_invalid') {
          finish(null, null);
        } else if (msg.type === 'auth_ok') {
          ws.send(JSON.stringify({ id: 1, type: 'auth/current_user' }));
        } else if (msg.type === 'result' && msg.id === 1) {
          const id = msg.success ? msg.result?.id : undefined;
          finish(null, typeof id === 'string' && id ? id : null);
        }
      });
    });
  }
}
