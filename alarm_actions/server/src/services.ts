import type { FastifyBaseLogger } from 'fastify';
import type { AppConfig } from './config.js';
import type { DB } from './db.js';
import { HaClient } from './ha/client.js';
import { HaDirectory } from './ha/directory.js';

/** Everything the routes and background jobs share. Tests build it with fakes. */
export interface Services {
  config: AppConfig;
  db: DB;
  log: FastifyBaseLogger;
  ha: HaClient;
  directory: HaDirectory;
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
  const ha = new HaClient({
    url: config.haWsUrl,
    token: config.supervisorToken ?? '',
    log,
    minBackoffMs: options.haBackoffMs?.min,
    maxBackoffMs: options.haBackoffMs?.max,
  });
  const directory = new HaDirectory(ha, log);
  return { config, db, log, ha, directory, now: options.now ?? Date.now };
}

/** Starts the background parts (HA connection). */
export function startServices(services: Services): void {
  if (!services.config.supervisorToken) {
    services.log.warn('SUPERVISOR_TOKEN is not set; not connecting to Home Assistant');
    return;
  }
  services.ha.start();
}

export async function stopServices(services: Services): Promise<void> {
  await services.ha.stop();
}
