import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import Database from 'better-sqlite3';
import { DB_FILE, SCHEMA_VERSION, SNAPSHOT_FILE, getMeta, openDatabase } from '../src/db.js';
import { readSettings } from '../src/config.js';
import { restoreFromSnapshot, writeSnapshot } from '../src/snapshot.js';
import { APP_VERSION } from '../src/version.js';
import { FakeHaCore } from './helpers/fakeHa.js';
import { startHarness, tempDir, waitFor, type Harness } from './helpers/harness.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const addonDir = path.resolve(here, '../..');

let harness: Harness | null = null;
afterEach(async () => {
  await harness?.close(true);
  harness = null;
});

describe('startup', () => {
  it('connects to Core and reads the time zone', async () => {
    harness = await startHarness();
    expect(harness.services.ha.connected).toBe(true);
    expect(harness.services.directory.timeZone).toBe('Europe/Brussels');
  });

  it('follows time zone changes', async () => {
    harness = await startHarness();
    harness.fake.state.config.time_zone = 'America/New_York';
    harness.fake.fireEvent('core_config_updated');
    await waitFor(() => harness!.services.directory.timeZone === 'America/New_York');
  });

  it('migrates a new database to the current schema', () => {
    const dir = tempDir();
    const db = openDatabase(path.join(dir, DB_FILE));
    expect(db.pragma('user_version', { simple: true })).toBe(SCHEMA_VERSION);
    expect(getMeta(db, 'change_seq')).toBe('0');
    db.close();
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('refuses a database from a newer version', () => {
    const dir = tempDir();
    const file = path.join(dir, DB_FILE);
    const raw = new Database(file);
    raw.pragma(`user_version = ${SCHEMA_VERSION + 1}`);
    raw.close();
    expect(() => openDatabase(file)).toThrow(/newer/);
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('reads add-on options with defaults', () => {
    const dir = tempDir();
    const file = path.join(dir, 'options.json');
    fs.writeFileSync(file, JSON.stringify({ snooze_minutes: 5, external_api_url: '' }));
    const settings = readSettings(file);
    expect(settings.snooze_minutes).toBe(5);
    expect(settings.external_api_url).toBeNull();
    expect(settings.action_label).toBe('alarm_action');
    fs.writeFileSync(file, JSON.stringify({ snooze_minutes: 99 }));
    expect(() => readSettings(file)).toThrow(/snooze_minutes/);
    fs.rmSync(dir, { recursive: true, force: true });
  });
});

describe('ingress guard', () => {
  it('rejects requests without an ingress identity or from another source', async () => {
    harness = await startHarness({ start: false });
    expect((await harness.request(null, 'GET', '/')).statusCode).toBe(401);
    const direct = await harness.app.inject({
      method: 'GET',
      url: '/',
      remoteAddress: '172.30.33.5',
      headers: { 'x-remote-user-id': 'user-a' },
    });
    expect(direct.statusCode).toBe(401);
    expect(direct.json()).toMatchObject({ error: 'unauthorized' });
  });

  it('accepts the ingress proxy as an IPv4-mapped address', async () => {
    harness = await startHarness({ start: false });
    const res = await harness.app.inject({
      method: 'GET',
      url: '/does-not-exist',
      remoteAddress: '::ffff:172.30.32.2',
      headers: { 'x-remote-user-id': 'user-a' },
    });
    expect(res.statusCode).toBe(404);
  });
});

describe('Home Assistant connection', () => {
  it('reconnects with backoff after Core restarts and resubscribes', async () => {
    harness = await startHarness();
    const { fake, services } = harness;
    fake.accepting = false;
    fake.dropConnections();
    await waitFor(() => !services.ha.connected, 2000, 'disconnect');
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(services.ha.connected).toBe(false);
    fake.accepting = true;
    await waitFor(() => services.ha.readyCount >= 2, 5000, 'reconnect');
    fake.state.config.time_zone = 'Asia/Tokyo';
    fake.fireEvent('core_config_updated');
    await waitFor(() => services.directory.timeZone === 'Asia/Tokyo');
  });

  it('keeps serving the panel while Core is down', async () => {
    const fake = await FakeHaCore.start();
    fake.accepting = false;
    harness = await startHarness({ fake, start: false });
    harness.services.ha.start();
    await new Promise((resolve) => setTimeout(resolve, 100));
    const res = await harness.request('user-a', 'GET', '/nothing-here');
    expect(res.statusCode).toBe(404);
    await harness.close(true);
    harness = null;
    await fake.close();
  });
});

describe('backup snapshot', () => {
  it('writes a snapshot and restores it when the live database is missing', async () => {
    const dir = tempDir();
    const db = openDatabase(path.join(dir, DB_FILE));
    db.prepare("INSERT INTO meta (key, value) VALUES ('marker', 'kept')").run();
    expect(await writeSnapshot(dir)).toBe(path.join(dir, SNAPSHOT_FILE));
    db.close();

    // Simulate a restore: only the snapshot came back.
    for (const suffix of ['', '-wal', '-shm']) fs.rmSync(path.join(dir, DB_FILE + suffix), { force: true });
    expect(restoreFromSnapshot(dir)).toBe(true);
    const restored = openDatabase(path.join(dir, DB_FILE));
    expect(getMeta(restored, 'marker')).toBe('kept');
    restored.close();
    expect(restoreFromSnapshot(dir)).toBe(false);
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('does nothing without a database', async () => {
    const dir = tempDir();
    expect(await writeSnapshot(dir)).toBeNull();
    fs.rmSync(dir, { recursive: true, force: true });
  });
});

describe('versions', () => {
  it('match between config.yaml, package.json files and the server', () => {
    const configYaml = fs.readFileSync(path.join(addonDir, 'config.yaml'), 'utf8');
    const match = /^version:\s*"?([^"\s]+)"?/m.exec(configYaml);
    expect(match?.[1]).toBe(APP_VERSION);
    for (const pkg of ['package.json', 'server/package.json', 'shared/package.json', 'web/package.json']) {
      const json = JSON.parse(fs.readFileSync(path.join(addonDir, pkg), 'utf8')) as { version: string };
      expect(json.version, pkg).toBe(APP_VERSION);
    }
  });
});
