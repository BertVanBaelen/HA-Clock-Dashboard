import { Readable } from 'node:stream';
import { afterEach, describe, expect, it } from 'vitest';
import {
  occurrenceId,
  type Alarm,
  type AlarmSaveResponse,
  type Client,
  type ClientsResponse,
  type Me,
  type OccurrenceEventResponse,
  type Settings,
  type SyncResponse,
} from '@alarm-actions/shared';
import { BUS_EVENTS, REASON } from '../src/scheduler.js';
import { VALIDATIONS_PER_IP_PER_MINUTE } from '../src/tokenAuth.js';
import { FakeHaCore } from './helpers/fakeHa.js';
import { startHarness, tempDir, waitFor, type Harness } from './helpers/harness.js';

const MINUTE = 60_000;
const DAY = 86_400_000;
/** 2026-10-14 12:16 in Brussels (CEST = UTC+2). */
const NOW = Date.parse('2026-10-14T10:16:00Z');
/** 12:30 Brussels on the same day. */
const T1230 = Date.parse('2026-10-14T10:30:00Z');

const heat = 'script.heat_bathroom';
const coffee = 'script.coffee';
const PHONE_A = '0b6f1b4e-6a0e-4d43-9c1a-6f7f6c1e0a01';
const PHONE_B = '0b6f1b4e-6a0e-4d43-9c1a-6f7f6c1e0a02';
const TOKEN_A = 'token-of-alice';
const BASE = '/clock';

let harness: Harness | null = null;
let fake: FakeHaCore | null = null;

afterEach(async () => {
  await harness?.close(true);
  await fake?.close();
  harness = null;
  fake = null;
});

interface Setup {
  h: Harness;
  c: { t: number };
}

async function setup(settings: Partial<Settings> = {}, start = NOW): Promise<Setup> {
  const c = { t: start };
  fake = await FakeHaCore.start();
  fake.userTokens.set(TOKEN_A, 'user-a');
  harness = await startHarness({
    fake,
    dataDir: tempDir(),
    now: () => c.t,
    settings,
    externalBasePath: BASE,
  });
  const h = harness;
  await waitFor(
    () => h.services.scripts.ready && h.services.users.loaded && h.services.directory.timeZone !== null,
    5000,
    'HA data',
  );
  await h.services.scheduler.tick();
  return { h, c };
}

async function at(s: Setup, ms: number): Promise<void> {
  s.c.t = ms;
  await s.h.services.scheduler.tick();
}

async function create(h: Harness, body: Record<string, unknown> = {}, user = 'user-a') {
  const res = await h.request(user, 'POST', '/api/v1/alarms', {
    label: 'Test',
    time: '12:30',
    repeat_days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'],
    ...body,
  });
  expect(res.statusCode, res.body).toBe(201);
  return (res.json() as AlarmSaveResponse).alarm;
}

const started = (h: Harness) => h.fake.calls.map((c) => (c.target as { entity_id: string }).entity_id);

function records(h: Harness, alarm: Alarm) {
  return h.services.history
    .forAlarm(alarm.id, 100)
    .map((r) => ({ action: r.alarm_action_id, result: r.result, reason: r.reason, planned_at: r.planned_at }))
    .sort((a, b) => a.planned_at - b.planned_at);
}

const getAlarm = async (h: Harness, id: string) =>
  (await h.request('user-a', 'GET', `/api/v1/alarms/${id}`)).json() as Alarm;

function report(
  h: Harness,
  alarm: Alarm,
  scheduledAt: number,
  type: 'dismissed' | 'snoozed' | 'timed_out',
  occurredAt?: number,
  user = 'user-a',
) {
  return h.request(
    user,
    'POST',
    `/api/v1/alarms/${alarm.id}/occurrences/${occurrenceId(alarm.id, scheduledAt)}/${type}`,
    occurredAt === undefined ? undefined : { occurred_at: new Date(occurredAt).toISOString() },
  );
}

function client(id = PHONE_A, time_zone = 'Europe/Brussels', last_sync_seq = 0) {
  return { id, name: 'Pixel', app_version: '0.1.2', time_zone, last_sync_seq };
}

const sync = async (h: Harness, since?: number) => {
  const res = await h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/sync${since === undefined ? '' : `?since=${since}`}`);
  expect(res.statusCode, res.body).toBe(200);
  return res.json() as SyncResponse;
};

describe('sync (FR-APP-03, TR-APP-07)', () => {
  it('returns everything first, then only changes and deletions', async () => {
    const s = await setup();
    const a = await create(s.h, { label: 'A' });
    const b = await create(s.h, { label: 'B' });
    await create(s.h, { label: 'Other' }, 'user-b');

    const first = await sync(s.h);
    expect(first).toMatchObject({
      full: true,
      deleted: [],
      time_zone: 'Europe/Brussels',
      snooze_minutes: 9,
      dismiss_timeout_minutes: 60,
      away: false,
    });
    expect(first.alarms.map((x) => x.label).sort()).toEqual(['A', 'B']);

    const unchanged = await sync(s.h, first.change_seq);
    expect(unchanged).toMatchObject({ full: false, alarms: [], deleted: [], change_seq: first.change_seq });

    expect((await s.h.request('user-a', 'POST', `/api/v1/alarms/${a.id}/skip`)).statusCode).toBe(200);
    expect((await s.h.request('user-a', 'DELETE', `/api/v1/alarms/${b.id}`)).statusCode).toBe(204);
    const next = await sync(s.h, first.change_seq);
    expect(next.full).toBe(false);
    expect(next.alarms.map((x) => [x.id, x.skip_next])).toEqual([[a.id, true]]);
    expect(next.deleted).toEqual([b.id]);
    expect(next.change_seq).toBeGreaterThan(first.change_seq);
  });

  it('answers a full sync for an unknown cursor or one below the tombstone floor', async () => {
    const s = await setup();
    const a = await create(s.h);
    const b = await create(s.h);
    const cursor = (await sync(s.h)).change_seq;
    expect((await sync(s.h, cursor + 100)).full).toBe(true);

    expect((await s.h.request('user-a', 'DELETE', `/api/v1/alarms/${b.id}`)).statusCode).toBe(204);
    s.c.t += 40 * DAY;
    s.h.services.store.pruneTombstones(30 * DAY);
    const res = await sync(s.h, cursor);
    expect(res.full).toBe(true);
    expect(res.alarms.map((x) => x.id)).toEqual([a.id]);
    expect(res.deleted).toEqual([]);
  });

  it('rejects a malformed cursor', async () => {
    const s = await setup();
    const res = await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/sync?since=abc`);
    expect(res.statusCode).toBe(400);
  });
});

describe('clients and away (FR-APP-04, FR-EXE-12)', () => {
  it('registers, lists and removes phones; foreign ids are 404', async () => {
    const s = await setup();
    const res = await s.h.requestExternal(TOKEN_A, 'POST', `${BASE}/api/v1/clients`, client(PHONE_A.toUpperCase()));
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json()).toMatchObject({ id: PHONE_A, away: false, last_seen_at: new Date(NOW).toISOString() });

    const foreign = await s.h.request('user-b', 'POST', '/api/v1/clients', client(PHONE_A));
    expect(foreign.statusCode).toBe(404);
    expect((await s.h.request('user-b', 'DELETE', `/api/v1/clients/${PHONE_A}`)).statusCode).toBe(404);

    const list = (await s.h.request('user-a', 'GET', '/api/v1/clients')).json() as ClientsResponse;
    expect(list.clients.map((c) => c.id)).toEqual([PHONE_A]);
    expect((await s.h.request('user-a', 'DELETE', `/api/v1/clients/${PHONE_A}`)).statusCode).toBe(204);
    expect(((await s.h.request('user-a', 'GET', '/api/v1/clients')).json() as ClientsResponse).clients).toEqual([]);
  });

  it('rejects unknown time zones and extra fields', async () => {
    const s = await setup();
    for (const body of [client(PHONE_A, 'Mars/Olympus'), { ...client(), extra: 1 }, { ...client(), id: 'nope' }]) {
      expect((await s.h.request('user-a', 'POST', '/api/v1/clients', body)).statusCode).toBe(400);
    }
  });

  it('removes the longest unseen phone at the cap instead of refusing', async () => {
    const s = await setup();
    const ids = Array.from({ length: 11 }, (_, i) => `0b6f1b4e-6a0e-4d43-9c1a-${String(i).padStart(12, '0')}`);
    for (const id of ids) {
      s.c.t += 1000;
      expect((await s.h.request('user-a', 'POST', '/api/v1/clients', client(id))).statusCode).toBe(200);
    }
    const list = ((await s.h.request('user-a', 'GET', '/api/v1/clients')).json() as ClientsResponse).clients;
    expect(list).toHaveLength(10);
    expect(list.map((c: Client) => c.id)).not.toContain(ids[0]);
  });

  it('is away while the latest phone is in another time zone and skips runs then', async () => {
    const s = await setup();
    const alarm = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    await s.h.request('user-a', 'POST', '/api/v1/clients', client(PHONE_A, 'Europe/Brussels'));
    s.c.t += 1000;
    await s.h.request('user-a', 'POST', '/api/v1/clients', client(PHONE_B, 'America/New_York'));
    expect(((await s.h.request('user-a', 'GET', '/api/v1/me')).json() as Me).away).toBe(true);
    expect(((await s.h.request('user-b', 'GET', '/api/v1/me')).json() as Me).away).toBe(false);
    const list = ((await s.h.request('user-a', 'GET', '/api/v1/clients')).json() as ClientsResponse);
    expect(list.away).toBe(true);
    expect(list.clients.map((c) => [c.id, c.away])).toEqual([
      [PHONE_B, true],
      [PHONE_A, false],
    ]);

    await at(s, T1230);
    expect(started(s.h)).toEqual([]);
    expect(records(s.h, alarm)).toEqual([expect.objectContaining({ result: 'skipped', reason: REASON.away })]);

    // Back home: an alias of the HA zone counts as the same zone.
    s.c.t = T1230 + MINUTE;
    await s.h.request('user-a', 'POST', '/api/v1/clients', client(PHONE_A, 'Europe/Brussels'));
    expect(((await s.h.request('user-a', 'GET', '/api/v1/me')).json() as Me).away).toBe(false);
    await at(s, T1230 + DAY);
    expect(started(s.h)).toEqual([coffee]);
  });
});

describe('dismiss flow (FR-EVT-01..03, FR-EXE-11, TR-SCH-07/08)', () => {
  const actions = [
    { script_entity_id: heat, timing: 'before', offset_minutes: 5 },
    { script_entity_id: coffee, timing: 'at' },
    { script_entity_id: heat, timing: 'after', offset_minutes: 5 },
    { script_entity_id: coffee, timing: 'after_dismiss', delay_minutes: 5 },
  ];

  it('dismissing while it rings runs after_dismiss actions after their delay and fires a bus event', async () => {
    const s = await setup();
    const alarm = await create(s.h, { actions });
    await at(s, T1230);
    expect(started(s.h)).toEqual([heat, coffee]);
    expect((await getAlarm(s.h, alarm.id)).current_occurrence).toEqual({
      id: occurrenceId(alarm.id, T1230),
      scheduled_at: new Date(T1230).toISOString(),
      status: 'fired',
    });

    s.c.t = T1230 + 3 * MINUTE;
    const res = await report(s.h, alarm, T1230, 'dismissed', T1230 + 2 * MINUTE);
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json()).toEqual({
      occurrence_id: occurrenceId(alarm.id, T1230),
      type: 'dismissed',
      status: 'dismissed',
      duplicate: false,
    } satisfies OccurrenceEventResponse);
    await waitFor(() => s.h.fake.firedEvents.length === 1, 2000, 'bus event');
    expect(s.h.fake.firedEvents[0]).toEqual({
      event_type: BUS_EVENTS.dismissed,
      event_data: {
        alarm_id: alarm.id,
        occurrence_id: occurrenceId(alarm.id, T1230),
        scheduled_at: new Date(T1230).toISOString(),
        occurred_at: new Date(T1230 + 2 * MINUTE).toISOString(),
      },
    });

    // The same report again (retry) changes nothing.
    const again = await report(s.h, alarm, T1230, 'dismissed', T1230 + 2 * MINUTE);
    expect(again.json()).toMatchObject({ status: 'dismissed', duplicate: true });

    await at(s, T1230 + 5 * MINUTE); // after (heat)
    await at(s, T1230 + 7 * MINUTE - 1000);
    expect(started(s.h)).toEqual([heat, coffee, heat]);
    await at(s, T1230 + 7 * MINUTE); // dismissed at +2 plus 5 min
    expect(started(s.h)).toEqual([heat, coffee, heat, coffee]);
    await at(s, T1230 + 8 * MINUTE);
    expect(s.h.services.scheduler.occurrence(occurrenceId(alarm.id, T1230))).toMatchObject({
      status: 'dismissed',
      runs_done: 1,
    });
    expect(s.h.fake.firedEvents).toHaveLength(1);
  });

  it('dismissing early skips before/at/after, runs after_dismiss and moves the next ring', async () => {
    const s = await setup();
    const alarm = await create(s.h, { actions });
    const occ = occurrenceId(alarm.id, T1230);
    expect((await getAlarm(s.h, alarm.id)).current_occurrence).toEqual({
      id: occ,
      scheduled_at: new Date(T1230).toISOString(),
      status: 'pending',
    });

    const res = await s.h.requestExternal(
      TOKEN_A,
      'POST',
      `${BASE}/api/v1/alarms/${alarm.id}/occurrences/${occ}/dismissed`,
    );
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json()).toMatchObject({ status: 'dismissed', duplicate: false });

    const after = await getAlarm(s.h, alarm.id);
    expect(after.current_occurrence).toMatchObject({ id: occ, status: 'dismissed' });
    expect(after.next_occurrence).toBe(new Date(T1230 + DAY).toISOString());
    expect(after.change_seq).toBeGreaterThan(alarm.change_seq);
    expect(records(s.h, alarm).filter((r) => r.reason === REASON.dismissedEarly)).toHaveLength(3);

    await at(s, NOW + 5 * MINUTE);
    expect(started(s.h)).toEqual([coffee]);
    await at(s, T1230 + 10 * MINUTE);
    expect(started(s.h)).toEqual([coffee]);
    expect(s.h.services.scheduler.occurrence(occ)).toMatchObject({ status: 'dismissed', runs_done: 1 });
    // The next day rings normally.
    await at(s, T1230 + DAY);
    expect(started(s.h)).toEqual([coffee, heat, coffee]);
  });

  it('disables a one-time alarm that is dismissed early', async () => {
    const s = await setup();
    const alarm = await create(s.h, { repeat_days: [], actions: [{ script_entity_id: coffee, timing: 'at' }] });
    expect((await report(s.h, alarm, T1230, 'dismissed')).statusCode).toBe(200);
    expect(await getAlarm(s.h, alarm.id)).toMatchObject({ enabled: false, disabled_reason: 'fired' });
    await at(s, T1230);
    expect(started(s.h)).toEqual([]);
  });

  it('snoozes, then dismisses', async () => {
    const s = await setup();
    const alarm = await create(s.h, { actions });
    await at(s, T1230);
    s.c.t = T1230 + MINUTE;
    expect((await report(s.h, alarm, T1230, 'snoozed')).json()).toMatchObject({ status: 'snoozed' });
    expect((await getAlarm(s.h, alarm.id)).current_occurrence).toMatchObject({ status: 'snoozed' });
    s.c.t = T1230 + 11 * MINUTE;
    expect((await report(s.h, alarm, T1230, 'snoozed')).json()).toMatchObject({ duplicate: false });
    expect((await report(s.h, alarm, T1230, 'dismissed')).json()).toMatchObject({ status: 'dismissed' });
    await waitFor(() => s.h.fake.firedEvents.length === 3, 2000, 'bus events');
    expect(s.h.fake.firedEvents.map((e) => e.event_type)).toEqual([
      BUS_EVENTS.snoozed,
      BUS_EVENTS.snoozed,
      BUS_EVENTS.dismissed,
    ]);
    // Snoozing before the alarm rang is implausible.
    const early = await create(s.h, { time: '13:30' });
    const res = await report(s.h, early, T1230 + 60 * MINUTE, 'snoozed');
    expect(res.statusCode).toBe(422);
    expect(res.json()).toMatchObject({ error: 'implausible_time' });
  });

  it('records after_dismiss actions as expired when nobody dismisses in time', async () => {
    const s = await setup();
    const alarm = await create(s.h, { actions });
    await at(s, T1230);
    await at(s, T1230 + 5 * MINUTE);
    await at(s, T1230 + 60 * MINUTE);
    await at(s, T1230 + 61 * MINUTE);
    expect(s.h.services.scheduler.occurrence(occurrenceId(alarm.id, T1230))).toMatchObject({
      status: 'expired',
      runs_done: 1,
    });
    expect(records(s.h, alarm).find((r) => r.action === alarm.actions[3]!.id)).toMatchObject({
      result: 'expired',
      reason: REASON.expired,
    });
    expect((await getAlarm(s.h, alarm.id)).current_occurrence).toBeNull();
    // A late report does not revive it.
    expect((await report(s.h, alarm, T1230, 'dismissed')).json()).toMatchObject({ status: 'expired' });
    expect(started(s.h)).toEqual([heat, coffee, heat]);
  });

  it('treats timed_out and a dismissal after the timeout as expired', async () => {
    const s = await setup();
    const alarm = await create(s.h, { actions });
    await at(s, T1230);
    s.c.t = T1230 + 30 * MINUTE;
    expect((await report(s.h, alarm, T1230, 'timed_out')).json()).toMatchObject({ status: 'expired' });
    await waitFor(() => s.h.services.history.forAlarm(alarm.id, 100).some((r) => r.result === 'expired'));
    expect(s.h.fake.firedEvents).toEqual([]);

    const late = await create(s.h, { time: '12:30', actions });
    s.c.t = T1230 + 61 * MINUTE; // the tick did not run since the alarm fired
    expect((await report(s.h, late, T1230, 'dismissed', T1230 + 61 * MINUTE)).json()).toMatchObject({
      status: 'expired',
    });
  });

  it('accepts a report for an occurrence the server never tracked; a stale one ends up missed', async () => {
    const s = await setup();
    const alarm = await create(s.h, { time: '11:50', actions });
    // 11:50 today is past (it is 12:16): the server never fired it.
    const past = T1230 - 40 * MINUTE;
    const res = await report(s.h, alarm, past, 'dismissed', past + MINUTE);
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json()).toMatchObject({ status: 'dismissed' });
    await at(s, NOW + MINUTE);
    // Dismissed at 11:51 + 5 min is long past the grace period.
    expect(started(s.h)).toEqual([]);
    expect(records(s.h, alarm)).toEqual([
      expect.objectContaining({ action: alarm.actions[3]!.id, result: 'missed', reason: REASON.grace }),
    ]);
  });

  it('rejects skipped, unknown, implausible and foreign occurrences', async () => {
    const s = await setup();
    const alarm = await create(s.h, { actions });
    const code = async (res: Awaited<ReturnType<typeof report>>) => [res.statusCode, res.json().error];

    expect(await code(await report(s.h, alarm, T1230 + MINUTE, 'dismissed'))).toEqual([422, 'invalid_occurrence']);
    const bogus = await s.h.request('user-a', 'POST', `/api/v1/alarms/${alarm.id}/occurrences/nonsense/dismissed`);
    expect([bogus.statusCode, bogus.json().error]).toEqual([422, 'invalid_occurrence']);
    expect(await code(await report(s.h, alarm, T1230, 'dismissed', NOW + 10 * MINUTE))).toEqual([422, 'implausible_time']);
    expect(await code(await report(s.h, alarm, T1230 + DAY, 'dismissed'))).toEqual([422, 'implausible_time']);
    expect((await report(s.h, alarm, T1230, 'dismissed', undefined, 'user-b')).statusCode).toBe(404);
    const badBody = await s.h.request(
      'user-a',
      'POST',
      `/api/v1/alarms/${alarm.id}/occurrences/${occurrenceId(alarm.id, T1230)}/dismissed`,
      { occurred_at: 'yesterday' },
    );
    expect(badBody.statusCode).toBe(400);

    expect((await s.h.request('user-a', 'POST', `/api/v1/alarms/${alarm.id}/skip`)).statusCode).toBe(200);
    expect((await getAlarm(s.h, alarm.id)).current_occurrence).toBeNull();
    expect(await code(await report(s.h, alarm, T1230, 'dismissed'))).toEqual([422, 'occurrence_skipped']);
    expect(s.h.fake.firedEvents).toEqual([]);
  });
});

describe('external listener (TR-API-12, SEC-02, SEC-13)', () => {
  it('needs a valid token, caches the check and sets no-store', async () => {
    const s = await setup();
    const none = await s.h.requestExternal(null, 'GET', `${BASE}/api/v1/me`);
    expect(none.statusCode).toBe(401);
    expect(none.headers['www-authenticate']).toBe('Bearer');
    expect((await s.h.requestExternal('wrong', 'GET', `${BASE}/api/v1/me`)).statusCode).toBe(401);

    const me = await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/me`, undefined, {
      headers: { 'x-remote-user-id': 'admin' },
    });
    expect(me.statusCode).toBe(200);
    expect(me.json()).toMatchObject({ id: 'user-a', name: 'Alice' });
    expect(me.headers['cache-control']).toBe('no-store');
    const logins = s.h.fake.userLogins;
    await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/me`);
    expect(s.h.fake.userLogins).toBe(logins);
    s.c.t += 61_000;
    await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/me`);
    expect(s.h.fake.userLogins).toBe(logins + 1);
  });

  it('ignores ingress headers, serves nothing outside the base path and no panel', async () => {
    const s = await setup();
    const forged = await s.h.requestExternal(null, 'GET', `${BASE}/api/v1/me`, undefined, {
      headers: { 'x-remote-user-id': 'user-a' },
    });
    expect(forged.statusCode).toBe(401);
    expect((await s.h.requestExternal(TOKEN_A, 'GET', '/api/v1/me')).statusCode).toBe(404);
    expect((await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/`)).statusCode).toBe(404);
    expect((await s.h.requestExternal(TOKEN_A, 'GET', '/')).statusCode).toBe(404);
  });

  it('refuses tokens of unknown or inactive users', async () => {
    const s = await setup();
    s.h.fake.userTokens.set('ghost-token', 'ghost');
    expect((await s.h.requestExternal('ghost-token', 'GET', `${BASE}/api/v1/me`)).statusCode).toBe(401);
  });

  it('rate limits token checks per address', async () => {
    const s = await setup();
    for (let i = 0; i < VALIDATIONS_PER_IP_PER_MINUTE; i++) {
      const res = await s.h.requestExternal(`bad-${i}`, 'GET', `${BASE}/api/v1/me`, undefined, {
        headers: { 'cf-connecting-ip': '198.51.100.7' },
      });
      expect(res.statusCode).toBe(401);
    }
    const limited = await s.h.requestExternal('bad-x', 'GET', `${BASE}/api/v1/me`, undefined, {
      headers: { 'cf-connecting-ip': '198.51.100.7' },
    });
    expect(limited.statusCode).toBe(429);
    // Another address and cached tokens still get through.
    const other = await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/me`, undefined, {
      headers: { 'cf-connecting-ip': '198.51.100.8' },
    });
    expect(other.statusCode).toBe(200);
  });

  it('answers 503 when Core cannot check the token', async () => {
    const s = await setup();
    s.h.fake.accepting = false;
    const res = await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/me`);
    expect(res.statusCode).toBe(503);
    s.h.fake.accepting = true;
    expect((await s.h.requestExternal(TOKEN_A, 'GET', `${BASE}/api/v1/me`)).statusCode).toBe(200);
  });
});

describe('app download (FR-APP-05, SEC-09)', () => {
  it('streams the APK from the app add-on to logged-in users', async () => {
    const s = await setup({ external_api_url: 'https://clock.example.com/clock' });
    s.h.services.appProbe.apk = async () => ({
      body: Readable.from([Buffer.from('PK-apk-bytes')]),
      size: 12,
      version: '1.2.3',
    });
    expect((await s.h.request(null, 'GET', '/api/v1/app/apk')).statusCode).toBe(401);
    const res = await s.h.request('user-a', 'GET', '/api/v1/app/apk');
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toBe('application/vnd.android.package-archive');
    expect(res.headers['content-disposition']).toBe('attachment; filename="alarm-actions-1.2.3.apk"');
    expect(res.headers['x-content-type-options']).toBe('nosniff');
    expect(res.body).toBe('PK-apk-bytes');

    const info = (await s.h.request('user-a', 'GET', '/api/v1/app/info')).json();
    expect(info).toMatchObject({ ha_url: 'https://home.example.com', api_url: 'https://clock.example.com/clock' });
  });

  it('answers 503 without the app add-on', async () => {
    const s = await setup();
    expect((await s.h.request('user-a', 'GET', '/api/v1/app/apk')).statusCode).toBe(503);
  });
});
