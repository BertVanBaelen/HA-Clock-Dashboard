import type { FastifyInstance } from 'fastify';
import type { ActionsResponse, AppInfo, Info, Me, Settings } from '@alarm-actions/shared';
import { unavailable } from '../errors.js';
import { currentUser, installIdentity } from '../security.js';
import { API_VERSION, APP_VERSION, MIN_CLIENT_API_VERSION } from '../version.js';
import { alarmRoutes } from './alarms.js';

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
    app_available: (await svc.appProbe.info()).available,
  }));

  // The "Install app" tab (TR-UI-06); the APK download follows in phase 6.
  app.get('/app/info', async (): Promise<AppInfo> => svc.appProbe.info());

  app.get('/me', async (req): Promise<Me> => {
    const user = currentUser(req);
    return { id: user.id, name: user.name, is_admin: user.isAdmin, away: false };
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
