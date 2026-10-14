import type { FastifyInstance } from 'fastify';
import {
  clientInputSchema,
  syncQuerySchema,
  type ActionsResponse,
  type AppInfo,
  type Client,
  type ClientsResponse,
  type Info,
  type Me,
  type Settings,
  type SyncResponse,
} from '@alarm-actions/shared';
import { notFound, unavailable } from '../errors.js';
import { currentUser, installIdentity } from '../security.js';
import { API_VERSION, APP_VERSION, MIN_CLIENT_API_VERSION } from '../version.js';
import { alarmRoutes, toApiAlarm } from './alarms.js';
import { parseBody, UUID_PARAM } from './validation.js';

/**
 * `/api/v1` (TR-API-01). Every route in this scope gets the identity hook; there is no route
 * without login (SEC-01).
 */
export async function apiRoutes(app: FastifyInstance): Promise<void> {
  const svc = app.svc;
  installIdentity(app);

  app.get('/info', async (): Promise<Info> => ({
    name: 'Alarm Actions',
    version: APP_VERSION,
    api_version: API_VERSION,
    min_client_api_version: MIN_CLIENT_API_VERSION,
    time_zone: svc.directory.timeZone,
    ha_connected: svc.ha.connected,
    app_available: svc.appProbe.peek(),
  }));

  // The "Install app" tab (TR-UI-06) and the app's update check (A5): the APK plus the URLs the
  // app is set up with (TR-API-14).
  app.get('/app/info', async (): Promise<AppInfo> => ({
    ...(await svc.appProbe.info()),
    ha_url: svc.directory.externalUrl,
    api_url: svc.config.settings.external_api_url,
  }));

  // FR-APP-05, SEC-09: the APK only for logged-in users, streamed from the app add-on.
  app.get('/app/apk', async (_req, reply) => {
    const apk = await svc.appProbe.apk();
    if (!apk) throw unavailable('The app is not available. Is the Alarm Actions App add-on running?');
    const version = (apk.version ?? 'latest').replace(/[^0-9A-Za-z.+-]/g, '');
    reply
      .header('Content-Type', 'application/vnd.android.package-archive')
      .header('Content-Disposition', `attachment; filename="alarm-actions-${version}.apk"`)
      .header('X-Content-Type-Options', 'nosniff');
    if (apk.size !== null) reply.header('Content-Length', String(apk.size));
    return reply.send(apk.body);
  });

  app.get('/me', async (req): Promise<Me> => {
    const user = currentUser(req);
    return { id: user.id, name: user.name, away: svc.clients.isAway(user.id) };
  });

  // FR-APP-03, TR-APP-07: own alarms changed since the cursor, plus deletions (tombstones).
  app.get('/sync', async (req): Promise<SyncResponse> => {
    const user = currentUser(req);
    const { since } = parseBody(syncQuerySchema, req.query);
    const changeSeq = svc.store.changeSeq;
    // A cursor older than the tombstone window (or from another database) gets everything.
    const full = since === 0 || since < svc.store.tombstoneFloor || since > changeSeq;
    const alarms = svc.store
      .list(user.id)
      .filter((a) => full || a.change_seq > since)
      .map((a) => toApiAlarm(svc, a));
    return {
      change_seq: changeSeq,
      full,
      alarms,
      deleted: full ? [] : svc.store.tombstones(user.id, since).map((t) => t.alarm_id),
      time_zone: svc.directory.timeZone,
      snooze_minutes: svc.config.settings.snooze_minutes,
      dismiss_timeout_minutes: svc.config.settings.dismiss_timeout_minutes,
      away: svc.clients.isAway(user.id),
    };
  });

  // FR-APP-04: the app registers on every sync; its time zone drives away detection.
  app.post('/clients', async (req): Promise<Client> => {
    const user = currentUser(req);
    const input = parseBody(clientInputSchema, req.body);
    const client = svc.clients.upsert(user.id, input);
    if (!client) throw notFound('Client');
    return client;
  });

  app.get('/clients', async (req): Promise<ClientsResponse> => {
    const user = currentUser(req);
    return { clients: svc.clients.list(user.id), away: svc.clients.isAway(user.id) };
  });

  app.delete<{ Params: { id: string } }>('/clients/:id', async (req, reply) => {
    const user = currentUser(req);
    const id = req.params.id.toLowerCase();
    if (!UUID_PARAM.test(id) || !svc.clients.remove(user.id, id)) throw notFound('Client');
    return reply.code(204).send();
  });

  app.get('/actions', async (): Promise<ActionsResponse> => {
    if (!svc.scripts.ready) {
      throw unavailable('The list of scripts is not loaded yet. Try again shortly.');
    }
    return { actions: svc.scripts.list() };
  });

  // Read-only: settings are changed on the add-on configuration page (FR-SET-01).
  app.get('/settings', async (): Promise<Settings> => svc.config.settings);

  alarmRoutes(app);
}
