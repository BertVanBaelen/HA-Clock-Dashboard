import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { StoredAlarm } from './store.js';
import type { HaUser } from './ha/users.js';
import { forbidden, notFound, tooManyRequests, unauthorized, unavailable } from './errors.js';

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

export type Access = 'read' | 'write';

/**
 * The one access check for alarms (SEC-03, TR-API-07, analysis §4), deny by default:
 * - owner: read and write;
 * - admin on another user's alarm: read only, writes get 403;
 * - anyone else, or a missing alarm: 404, so ids cannot be probed.
 */
export function authorizeAlarm(
  user: HaUser,
  alarm: StoredAlarm | null,
  access: Access,
): StoredAlarm {
  if (!alarm) throw notFound('Alarm');
  if (alarm.owner_user_id === user.id) return alarm;
  if (user.isAdmin) {
    if (access === 'read') return alarm;
    throw forbidden("Admins can view other users' alarms but not change them.");
  }
  throw notFound('Alarm');
}

/** Alarms a user may list: their own, or everyone's for an admin. */
export function visibleOwner(user: HaUser): string | undefined {
  return user.isAdmin ? undefined : user.id;
}
