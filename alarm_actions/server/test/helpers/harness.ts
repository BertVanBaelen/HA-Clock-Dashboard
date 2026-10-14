import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import type { FastifyInstance, InjectOptions, LightMyRequestResponse } from 'fastify';
import { DEFAULT_SETTINGS, type Settings } from '@alarm-actions/shared';
import { buildApp } from '../../src/app.js';
import type { AppConfig } from '../../src/config.js';
import { DB_FILE, openDatabase } from '../../src/db.js';
import { createLogger } from '../../src/logger.js';
import {
  createServices,
  startServices,
  stopServices,
  type Services,
} from '../../src/services.js';
import { FakeHaCore } from './fakeHa.js';

export const INGRESS_IP = '172.30.32.2';

export async function waitFor(
  condition: () => boolean,
  timeoutMs = 5000,
  what = 'condition',
): Promise<void> {
  const start = Date.now();
  while (!condition()) {
    if (Date.now() - start > timeoutMs) throw new Error(`Timed out waiting for ${what}`);
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

export function tempDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'alarm-actions-'));
}

export function testConfig(dataDir: string, haWsUrl: string, settings: Partial<Settings> = {}) {
  const config: AppConfig = {
    dataDir,
    port: 0,
    host: '127.0.0.1',
    trustedProxy: INGRESS_IP,
    haWsUrl,
    haCoreWsUrl: haWsUrl,
    externalPort: 0,
    supervisorToken: 'test-supervisor-token',
    webDistDir: null,
    logLevel: 'silent',
    settings: { ...DEFAULT_SETTINGS, ...settings },
    appUrl: null,
  };
  return config;
}

export interface HarnessOptions {
  now?: () => number;
  settings?: Partial<Settings>;
  /** Connect to the fake Core and start background services (default true). */
  start?: boolean;
  dataDir?: string;
  fake?: FakeHaCore;
  /** Also build the external API listener with this base path (e.g. `/clock`). */
  externalBasePath?: string;
}

export interface Harness {
  fake: FakeHaCore;
  services: Services;
  app: FastifyInstance;
  /** The external API listener, when `externalBasePath` was given. */
  external: FastifyInstance | null;
  dataDir: string;
  /** Request as an ingress user (or without identity when `user` is null). */
  request(
    user: string | null,
    method: InjectOptions['method'],
    url: string,
    payload?: unknown,
    extra?: Partial<InjectOptions>,
  ): Promise<LightMyRequestResponse>;
  /** Request on the external listener with a bearer token (or none when `token` is null). */
  requestExternal(
    token: string | null,
    method: InjectOptions['method'],
    url: string,
    payload?: unknown,
    extra?: Partial<InjectOptions>,
  ): Promise<LightMyRequestResponse>;
  /** Stops app and services; keeps the fake Core and data dir unless `all` is set. */
  close(all?: boolean): Promise<void>;
}

export async function startHarness(options: HarnessOptions = {}): Promise<Harness> {
  const fake = options.fake ?? (await FakeHaCore.start());
  const dataDir = options.dataDir ?? tempDir();
  const config = testConfig(dataDir, fake.url, options.settings);
  const log = createLogger('silent');
  const db = openDatabase(path.join(dataDir, DB_FILE));
  const services = createServices(config, db, log, {
    now: options.now,
    haBackoffMs: { min: 20, max: 100 },
  });
  const app = await buildApp(services);
  await app.ready();
  const external =
    options.externalBasePath === undefined
      ? null
      : await buildApp(services, { mode: 'external', basePath: options.externalBasePath });
  await external?.ready();
  if (options.start !== false) {
    startServices(services);
    await waitFor(() => services.ha.readyCount > 0, 5000, 'HA connection');
  }

  return {
    fake,
    services,
    app,
    external,
    dataDir,
    request(user, method, url, payload, extra = {}) {
      return app.inject({
        method,
        url,
        remoteAddress: INGRESS_IP,
        headers: user ? { 'x-remote-user-id': user } : {},
        ...(payload === undefined ? {} : { payload: payload as object }),
        ...extra,
      });
    },
    requestExternal(token, method, url, payload, extra = {}) {
      if (!external) throw new Error('No external listener in this harness');
      const { headers, ...rest } = extra;
      return external.inject({
        method,
        url,
        remoteAddress: '172.30.33.5',
        headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
        ...(payload === undefined ? {} : { payload: payload as object }),
        ...rest,
      });
    },
    async close(all = false) {
      await app.close();
      await external?.close();
      await stopServices(services);
      db.close();
      if (all) {
        if (!options.fake) await fake.close();
        fs.rmSync(dataDir, { recursive: true, force: true });
      }
    },
  };
}
