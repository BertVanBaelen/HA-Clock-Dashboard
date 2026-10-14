import type { FastifyBaseLogger } from 'fastify';
import type { AppConfig } from './config.js';
import type { DB } from './db.js';
import { HaClient } from './ha/client.js';
import { HaDirectory } from './ha/directory.js';
import { ScriptRegistry } from './ha/scripts.js';
import { UserDirectory } from './ha/users.js';
import { AlarmStore } from './store.js';

/** Everything the routes and background jobs share. Tests build it with fakes. */
export interface Services {
  config: AppConfig;
  db: DB;
  log: FastifyBaseLogger;
  ha: HaClient;
  directory: HaDirectory;
  users: UserDirectory;
  scripts: ScriptRegistry;
  store: AlarmStore;
  /** Wall clock in ms; injectable for tests. */
  now: () => number;
}

export interface ServiceOptions {
  now?: () => number;
  haBackoffMs?: { min: number; max: number };
}

export function createServices(
  config: AppConfig,
  db: DB,
  log: FastifyBaseLogger,
  options: ServiceOptions = {},
): Services {
  const now = options.now ?? Date.now;
  const ha = new HaClient({
    url: config.haWsUrl,
    token: config.supervisorToken ?? '',
    log,
    minBackoffMs: options.haBackoffMs?.min,
    maxBackoffMs: options.haBackoffMs?.max,
  });
  const directory = new HaDirectory(ha, log);
  const users = new UserDirectory(ha, log, now);
  const scripts = new ScriptRegistry(ha, log, config.settings.action_label);
  const store = new AlarmStore(db, now);
  return { config, db, log, ha, directory, users, scripts, store, now };
}

/** Starts the background parts (HA connection, periodic refreshes). */
export function startServices(services: Services): void {
  if (!services.config.supervisorToken) {
    services.log.warn('SUPERVISOR_TOKEN is not set; not connecting to Home Assistant');
    return;
  }
  services.ha.start();
  services.users.startPeriodic();
}

export async function stopServices(services: Services): Promise<void> {
  services.users.stopPeriodic();
  services.scripts.stop();
  await services.ha.stop();
}
