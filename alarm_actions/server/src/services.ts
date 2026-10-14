import type { FastifyBaseLogger } from 'fastify';
import type { AppConfig } from './config.js';
import type { DB } from './db.js';
import { HaClient } from './ha/client.js';
import { HaDirectory } from './ha/directory.js';
import { ScriptRegistry } from './ha/scripts.js';
import { UserDirectory } from './ha/users.js';
import { Executor } from './executor.js';
import { HistoryStore } from './history.js';
import { Scheduler } from './scheduler.js';
import { AlarmStore } from './store.js';
import { AppProbe } from './appProbe.js';
import { ClientStore } from './clients.js';
import { TokenValidator } from './tokenAuth.js';

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
  history: HistoryStore;
  executor: Executor;
  scheduler: Scheduler;
  appProbe: AppProbe;
  clients: ClientStore;
  tokens: TokenValidator;
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
  const history = new HistoryStore(db, now);
  const settings = config.settings;
  const clients = new ClientStore(db, now, () => directory.timeZone);
  const executor = new Executor(ha, users, scripts, history, settings, log);
  const scheduler = new Scheduler({
    db,
    store,
    history,
    executor,
    ha,
    directory,
    users,
    scripts,
    clients,
    settings,
    log,
    now,
  });
  const appProbe = new AppProbe(config.appUrl, log, now);
  const tokens = new TokenValidator({ url: config.haCoreWsUrl, log, now });
  return {
    config,
    db,
    log,
    ha,
    directory,
    users,
    scripts,
    store,
    history,
    executor,
    scheduler,
    appProbe,
    clients,
    tokens,
    now,
  };
}

/** Starts the background parts (HA connection, periodic refreshes). */
export function startServices(services: Services): void {
  if (!services.config.supervisorToken) {
    services.log.warn('SUPERVISOR_TOKEN is not set; not connecting to Home Assistant');
    return;
  }
  services.ha.start();
  services.users.startPeriodic();
  services.scheduler.start();
}

export async function stopServices(services: Services): Promise<void> {
  await services.scheduler.stop();
  services.users.stopPeriodic();
  services.scripts.stop();
  await services.ha.stop();
}
