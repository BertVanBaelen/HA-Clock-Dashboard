import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { StoredAlarm } from './store.js';
import type { HaUser } from './ha/users.js';
import { notFound, tooManyRequests, unauthorized, unavailable } from './errors.js';

declare module 'fastify' {
  interface FastifyRequest {
    /** The HA user of an API request; set by `installIdentity` (SEC-02). */
    haUser: HaUser | null;
  }
}

/** Per-user API rate limit (SEC-05). */
export const API_REQUESTS_PER_MINUTE = 120;

/** Strips the IPv4-mapped IPv6 prefix (`::ffff:172.30.32.2`). */
export function clientAddress(ip: string): string {
  return ip.startsWith('::ffff:') ? ip.slice(7) : ip;
}

/**
 * Ingress identity (SEC-01, SEC-02, TR-API-09): the request must come from the Supervisor
 * ingress proxy and carry `X-Remote-User-Id`. Returns the user id or throws 401.
 */
export function ingressUserId(req: FastifyRequest, trustedProxy: string): string {
  const userId = req.headers['x-remote-user-id'];
  if (clientAddress(req.ip) !== trustedProxy || typeof userId !== 'string' || !userId) {
    throw unauthorized();
  }
  return userId;
}

/**
 * Resolves the HA user of every request in this (API) scope and applies the per-user rate limit.
 * Unknown or inactive users get 401; when the user list was never loaded (Core not reached yet)
 * the answer is 503 so the client retries instead of logging out.
 */
export function installIdentity(app: FastifyInstance): void {
  const svc = app.svc;
  const windows = new Map<string, { start: number; count: number }>();

  app.decorateRequest('haUser', null);
  app.addHook('onRequest', async (req) => {
    const userId = ingressUserId(req, svc.config.trustedProxy);
    const user = await svc.users.resolve(userId);
    if (user === null) throw unavailable('Home Assistant is not reachable yet. Try again shortly.');
    if (!user || !user.isActive) throw unauthorized();

    const now = svc.now();
    let window = windows.get(user.id);
    if (!window || now - window.start >= 60_000) {
      window = { start: now, count: 0 };
      windows.set(user.id, window);
    }
    window.count++;
    if (window.count > API_REQUESTS_PER_MINUTE) {
      throw tooManyRequests(Math.max(1, Math.ceil((window.start + 60_000 - now) / 1000)));
    }
    req.haUser = user;
  });
}

/** The user of an API request (always set inside the API scope). */
export function currentUser(req: FastifyRequest): HaUser {
  if (!req.haUser) throw unauthorized();
  return req.haUser;
}

/**
 * The one access check for alarms (SEC-03, TR-API-07, analysis §4), deny by default:
 * only the owner may read or change an alarm. For anyone else, or a missing alarm, it is 404,
 * so ids cannot be probed. Other users only see planned actions (`GET /planned`).
 */
export function authorizeAlarm(user: HaUser, alarm: StoredAlarm | null): StoredAlarm {
  if (!alarm || alarm.owner_user_id !== user.id) throw notFound('Alarm');
  return alarm;
}
