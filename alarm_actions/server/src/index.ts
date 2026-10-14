import path from 'node:path';
import { loadConfig } from './config.js';
import { DB_FILE, openDatabase } from './db.js';
import { restoreFromSnapshot } from './snapshot.js';
import { createLogger } from './logger.js';
import { createServices, startServices, stopServices } from './services.js';
import { buildApp } from './app.js';
import { APP_VERSION } from './version.js';

async function main(): Promise<void> {
  const config = loadConfig();
  const log = createLogger(config.logLevel);
  if (restoreFromSnapshot(config.dataDir)) {
    log.warn('Database restored from the backup snapshot');
  }
  const db = openDatabase(path.join(config.dataDir, DB_FILE));
  const services = createServices(config, db, log);
  const app = await buildApp(services);

  let stopping = false;
  const shutdown = async (signal: string) => {
    if (stopping) return;
    stopping = true;
    log.info(`${signal} received, stopping`);
    try {
      await app.close();
      await stopServices(services);
    } finally {
      db.close();
      process.exit(0);
    }
  };
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));

  await app.listen({ port: config.port, host: config.host });
  startServices(services);
  log.info(`Alarm Actions ${APP_VERSION} listening on port ${config.port}`);
}

main().catch((err: unknown) => {
  console.error(err);
  process.exit(1);
});
