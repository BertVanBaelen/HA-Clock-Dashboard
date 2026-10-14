import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { mdiRadiator, mdiScriptText } from '@mdi/js';
import type { Alarm, AlarmSaveResponse, PlannedAction } from '@alarm-actions/shared';
import { AppProbe } from '../src/appProbe.js';
import { mdiPath } from '../src/ha/scripts.js';
import { createLogger } from '../src/logger.js';
import { API_REQUESTS_PER_MINUTE } from '../src/security.js';
import { FakeHaCore } from './helpers/fakeHa.js';
import { startHarness, waitFor, type Harness } from './helpers/harness.js';

let harness: Harness | null = null;

afterEach(async () => {
  await harness?.close(true);
  harness = null;
});

/** 2026-10-14 12:16 in Brussels (a Wednesday). */
const NOW = Date.parse('2026-10-14T10:16:00Z');

function clock(start = NOW) {
  let t = start;
  return {
    now: () => t,
    advance: (ms: number) => {
      t += ms;
    },
  };
}

const workAlarm = {
  label: 'Work',
  time: '06:45',
  repeat_days: ['mon', 'tue', 'wed', 'thu', 'fri'],
  actions: [
    { script_entity_id: 'script.heat_bathroom', timing: 'before', offset_minutes: 10 },
    { script_entity_id: 'script.coffee', timing: 'after_dismiss', delay_minutes: 20 },
  ],
};

async function create(h: Harness, user: string, body: unknown = workAlarm): Promise<Alarm> {
  const res = await h.request(user, 'POST', '/api/v1/alarms', body);
  expect(res.statusCode, res.body).toBe(201);
  return (res.json() as AlarmSaveResponse).alarm;
}

function updateBody(alarm: Alarm, changes: Record<string, unknown> = {}) {
  return {
    label: alarm.label,
    time: alarm.time,
    repeat_days: alarm.repeat_days,
    enabled: alarm.enabled,
    snooze_minutes: alarm.snooze_minutes,
    actions: alarm.actions,
    revision: alarm.revision,
    ...changes,
  };
}

describe('alarm CRUD', () => {
  it('creates, reads, updates and deletes an alarm with actions', async () => {
    harness = await startHarness({ now: () => NOW });
    const h = harness;
    const alarm = await create(h, 'user-a');
    expect(alarm).toMatchObject({
      owner_user_id: 'user-a',
      owner_name: 'Alice',
      label: 'Work',
      repeat_days: ['mon', 'tue', 'wed', 'thu', 'fri'],
      enabled: true,
      skip_next: false,
      revision: 1,
      change_seq: 1,
      disabled_reason: null,
      next_occurrence: '2026-10-15T04:45:00.000Z',
    });
    expect(alarm.actions).toHaveLength(2);
    expect(alarm.actions[1]).toMatchObject({ timing: 'after_dismiss', delay_minutes: 20 });

    const read = await h.request('user-a', 'GET', `/api/v1/alarms/${alarm.id}`);
    expect(read.json()).toEqual(alarm);

    // Edit: keep the first action (with its id), drop the second, add a new one.
    const kept = alarm.actions[0]!;
    const res = await h.request(
      'user-a',
      'PUT',
      `/api/v1/alarms/${alarm.id}`,
      updateBody(alarm, {
        time: '07:00',
        actions: [
          { ...kept, offset_minutes: 15 },
          { script_entity_id: 'script.coffee', timing: 'at' },
        ],
      }),
    );
    expect(res.statusCode, res.body).toBe(200);
    const updated = (res.json() as AlarmSaveResponse).alarm;
    expect(updated.revision).toBe(2);
    expect(updated.change_seq).toBe(2);
    expect(updated.actions[0]).toMatchObject({ id: kept.id, offset_minutes: 15 });
    expect(updated.actions[1]!.id).not.toBe(alarm.actions[1]!.id);
    expect(updated.next_occurrence).toBe('2026-10-15T05:00:00.000Z');

    const del = await h.request('user-a', 'DELETE', `/api/v1/alarms/${alarm.id}`);
    expect(del.statusCode).toBe(204);
    expect((await h.request('user-a', 'GET', `/api/v1/alarms/${alarm.id}`)).statusCode).toBe(404);
    expect(h.services.store.tombstones('user-a')).toEqual([{ alarm_id: alarm.id, change_seq: 3 }]);
    expect(h.services.store.changeSeq).toBe(3);
  });

  it('rejects a stale revision with 409', async () => {
    harness = await startHarness({ now: () => NOW });
    const alarm = await create(harness, 'user-a');
    const first = await harness.request(
      'user-a',
      'PUT',
      `/api/v1/alarms/${alarm.id}`,
      updateBody(alarm, { label: 'One' }),
    );
    expect(first.statusCode).toBe(200);
    const second = await harness.request(
      'user-a',
      'PUT',
      `/api/v1/alarms/${alarm.id}`,
      updateBody(alarm, { label: 'Two' }),
    );
    expect(second.statusCode).toBe(409);
    expect(second.json()).toMatchObject({ error: 'conflict', current_revision: 2 });
  });

  it('replaces action ids that do not belong to the alarm', async () => {
    harness = await startHarness({ now: () => NOW });
    const a = await create(harness, 'user-a');
    const b = await create(harness, 'user-a');
    const res = await harness.request(
      'user-a',
      'PUT',
      `/api/v1/alarms/${b.id}`,
      updateBody(b, { actions: [a.actions[0]] }),
    );
    expect(res.statusCode).toBe(200);
    expect((res.json() as AlarmSaveResponse).alarm.actions[0]!.id).not.toBe(a.actions[0]!.id);
  });

  it('skips and unskips repeating alarms only', async () => {
    harness = await startHarness({ now: () => NOW });
    const h = harness;
    const alarm = await create(h, 'user-a');
    const skip = await h.request('user-a', 'POST', `/api/v1/alarms/${alarm.id}/skip`);
    expect(skip.statusCode).toBe(200);
    expect(skip.json()).toMatchObject({
      skip_next: true,
      next_occurrence: '2026-10-16T04:45:00.000Z',
    });
    const unskip = await h.request('user-a', 'POST', `/api/v1/alarms/${alarm.id}/unskip`, {});
    expect(unskip.json()).toMatchObject({ skip_next: false });

    const once = await create(h, 'user-a', { time: '23:30' });
    expect(once.next_occurrence).toBe('2026-10-14T21:30:00.000Z');
    const res = await h.request('user-a', 'POST', `/api/v1/alarms/${once.id}/skip`);
    expect(res.statusCode).toBe(422);
    expect(res.json()).toMatchObject({ error: 'not_repeating' });
  });

  it('clears skip_next when an alarm becomes one-time', async () => {
    harness = await startHarness({ now: () => NOW });
    const alarm = await create(harness, 'user-a');
    const skipped = (
      await harness.request('user-a', 'POST', `/api/v1/alarms/${alarm.id}/skip`)
    ).json() as Alarm;
    const res = await harness.request(
      'user-a',
      'PUT',
      `/api/v1/alarms/${alarm.id}`,
      updateBody(skipped, { repeat_days: [] }),
    );
    expect((res.json() as AlarmSaveResponse).alarm.skip_next).toBe(false);
  });

  it('has no next occurrence when disabled', async () => {
    harness = await startHarness({ now: () => NOW });
    const alarm = await create(harness, 'user-a', { ...workAlarm, enabled: false });
    expect(alarm.next_occurrence).toBeNull();
  });

  it('keeps alarms across a restart', async () => {
    const fake = await FakeHaCore.start();
    harness = await startHarness({ fake, now: () => NOW });
    const alarm = await create(harness, 'user-a');
    const dataDir = harness.dataDir;
    await harness.close();
    harness = await startHarness({ fake, dataDir, now: () => NOW });
    const res = await harness.request('user-a', 'GET', `/api/v1/alarms/${alarm.id}`);
    expect(res.json()).toEqual(alarm);
    await harness.close(true);
    harness = null;
    await fake.close();
  });
});

describe('access control (analysis §4)', () => {
  type Actor = 'user-a' | 'user-b' | 'admin';
  const actors: Actor[] = ['user-a', 'user-b', 'admin'];

  /** Expected status per operation for the owner (user-a), another user and an admin (no extra rights). */
  const matrix: Array<{
    name: string;
    method: 'GET' | 'PUT' | 'DELETE' | 'POST';
    path: (id: string, alarm: Alarm) => string;
    body?: (alarm: Alarm) => unknown;
    expected: Record<Actor, number>;
  }> = [
    {
      name: 'read one',
      method: 'GET',
      path: (id) => `/api/v1/alarms/${id}`,
      expected: { 'user-a': 200, 'user-b': 404, admin: 404 },
    },
    {
      name: 'edit',
      method: 'PUT',
      path: (id) => `/api/v1/alarms/${id}`,
      body: (alarm) => updateBody(alarm, { label: 'Changed' }),
      expected: { 'user-a': 200, 'user-b': 404, admin: 404 },
    },
    {
      name: 'skip',
      method: 'POST',
      path: (id) => `/api/v1/alarms/${id}/skip`,
      expected: { 'user-a': 200, 'user-b': 404, admin: 404 },
    },
    {
      name: 'unskip',
      method: 'POST',
      path: (id) => `/api/v1/alarms/${id}/unskip`,
      expected: { 'user-a': 200, 'user-b': 404, admin: 404 },
    },
    {
      name: 'run now',
      method: 'POST',
      path: (id, alarm) => `/api/v1/alarms/${id}/actions/${alarm.actions[0]!.id}/run`,
      expected: { 'user-a': 200, 'user-b': 404, admin: 404 },
    },
    {
      name: 'delete',
      method: 'DELETE',
      path: (id) => `/api/v1/alarms/${id}`,
      expected: { 'user-a': 204, 'user-b': 404, admin: 404 },
    },
  ];

  for (const op of matrix) {
    for (const actor of actors) {
      it(`${op.name} as ${actor} → ${op.expected[actor]}`, async () => {
        harness = await startHarness({ now: () => NOW });
        const alarm = await create(harness, 'user-a');
        const res = await harness.request(
          actor,
          op.method,
          op.path(alarm.id, alarm),
          op.body ? op.body(alarm) : undefined,
        );
        expect(res.statusCode, res.body).toBe(op.expected[actor]);
        // A refused write changes nothing.
        if (res.statusCode >= 400) {
          const after = harness.services.store.get(alarm.id);
          expect(after?.revision).toBe(1);
        }
      });
    }
  }

  it('lists own alarms only, also for admins', async () => {
    harness = await startHarness({ now: () => NOW });
    const a = await create(harness, 'user-a');
    const admins = await create(harness, 'admin');
    const list = async (user: string) =>
      ((await harness!.request(user, 'GET', '/api/v1/alarms')).json() as { alarms: Alarm[] })
        .alarms;
    expect((await list('user-a')).map((x) => x.id)).toEqual([a.id]);
    expect(await list('user-b')).toEqual([]);
    expect((await list('admin')).map((x) => x.id)).toEqual([admins.id]);
  });

  it("shows everyone the upcoming actions of other users' alarms, sorted by time", async () => {
    harness = await startHarness({ now: () => NOW });
    await create(harness, 'user-a');
    await create(harness, 'user-a', {
      time: '13:00',
      actions: [
        { script_entity_id: 'script.coffee', timing: 'at' },
        { script_entity_id: 'script.heat_bathroom', timing: 'before', offset_minutes: 60 },
        { script_entity_id: 'script.coffee', timing: 'after', offset_minutes: 5, enabled: false },
      ],
    });
    await create(harness, 'user-a', { ...workAlarm, time: '05:00', enabled: false });
    await create(harness, 'user-b', {
      time: '08:00',
      actions: [{ script_entity_id: 'script.coffee', timing: 'at' }],
    });
    const planned = async (user: string) =>
      (
        (await harness!.request(user, 'GET', '/api/v1/planned')).json() as {
          planned: PlannedAction[];
        }
      ).planned;

    expect(await planned('user-b')).toEqual([
      // 13:00 today: the "before" action at 12:00 is already past (now is 12:16).
      {
        owner_name: 'Alice',
        script_entity_id: 'script.coffee',
        script_name: 'Coffee',
        icon_path: mdiScriptText,
        timing: 'at',
        run_at: '2026-10-14T11:00:00.000Z',
        alarm_at: '2026-10-14T11:00:00.000Z',
        delay_minutes: null,
      },
      {
        owner_name: 'Alice',
        script_entity_id: 'script.heat_bathroom',
        script_name: 'Heat bathroom',
        icon_path: mdiRadiator,
        timing: 'before',
        run_at: '2026-10-15T04:35:00.000Z',
        alarm_at: '2026-10-15T04:45:00.000Z',
        delay_minutes: null,
      },
      expect.objectContaining({
        script_entity_id: 'script.coffee',
        timing: 'after_dismiss',
        run_at: null,
        alarm_at: '2026-10-15T04:45:00.000Z',
        delay_minutes: 20,
      }),
    ]);
    // Own alarms are not in the list.
    expect((await planned('user-a')).map((p) => p.owner_name)).toEqual(['Bob']);
  });

  it('makes the creator the owner, even for admins', async () => {
    harness = await startHarness({ now: () => NOW });
    expect((await create(harness, 'admin')).owner_user_id).toBe('admin');
    const res = await harness.request('admin', 'POST', '/api/v1/alarms', {
      ...workAlarm,
      owner_user_id: 'user-a',
    });
    expect(res.statusCode).toBe(400);
  });

  it('answers 404 for malformed and unknown ids', async () => {
    harness = await startHarness({ now: () => NOW });
    for (const id of ['nope', '00000000-0000-4000-8000-000000000000']) {
      expect((await harness.request('user-a', 'GET', `/api/v1/alarms/${id}`)).statusCode).toBe(404);
    }
  });

  it.each(['/api/v1/actions', '/api/v1/settings', '/api/v1/info', '/api/v1/me', '/api/v1/planned'])(
    '%s is readable by every user',
    async (url) => {
      harness = await startHarness({ now: () => NOW });
      for (const actor of actors) {
        expect((await harness.request(actor, 'GET', url)).statusCode).toBe(200);
      }
    },
  );
});

describe('identity (SEC-01, SEC-02, SEC-11)', () => {
  const sample = '6f0c1a52-4c5e-4a56-9d4b-2b1d6d6a9b10';
  const concrete = (url: string) => url.replace(/:[a-z_]+/g, sample);

  it('rejects every route without ingress identity or from another source', async () => {
    harness = await startHarness({ now: () => NOW });
    const routes = harness.app.routeList;
    expect(routes.filter((r) => r.url.startsWith('/api/v1')).length).toBeGreaterThanOrEqual(11);
    for (const route of routes) {
      const url = concrete(route.url);
      const method = route.method as 'GET';
      const anonymous = await harness.request(null, method, url);
      expect(anonymous.statusCode, `${route.method} ${url}`).toBe(401);
      const foreign = await harness.app.inject({
        method,
        url,
        remoteAddress: '172.30.33.7',
        headers: { 'x-remote-user-id': 'user-a' },
      });
      expect(foreign.statusCode, `${route.method} ${url} from another source`).toBe(401);
    }
  });

  it('rejects unknown and inactive users on every API route', async () => {
    const fake = await FakeHaCore.start();
    fake.state.users.push({
      id: 'user-c',
      name: 'Carol',
      is_active: false,
      group_ids: ['system-users'],
    });
    harness = await startHarness({ fake, now: () => NOW });
    for (const route of harness.app.routeList.filter((r) => r.url.startsWith('/api/v1'))) {
      for (const user of ['ghost', 'user-c']) {
        const res = await harness.request(user, route.method as 'GET', concrete(route.url));
        expect(res.statusCode, `${route.method} ${route.url} as ${user}`).toBe(401);
      }
    }
    await harness.close(true);
    harness = null;
    await fake.close();
  });

  it('picks up a new HA user after a refresh', async () => {
    const c = clock();
    harness = await startHarness({ now: c.now });
    harness.fake.state.users.push({ id: 'user-d', name: 'Dave', is_active: true, group_ids: [] });
    c.advance(11_000);
    const res = await harness.request('user-d', 'GET', '/api/v1/me');
    expect(res.json()).toEqual({ id: 'user-d', name: 'Dave', away: false });
  });

  it('answers 503 while Home Assistant users were never loaded', async () => {
    harness = await startHarness({ start: false, now: () => NOW });
    const res = await harness.request('user-a', 'GET', '/api/v1/me');
    expect(res.statusCode).toBe(503);
  });

  it('returns the user name', async () => {
    harness = await startHarness({ now: () => NOW });
    expect((await harness.request('user-a', 'GET', '/api/v1/me')).json()).toMatchObject({
      name: 'Alice',
    });
  });

  it('rate limits per user', async () => {
    const c = clock();
    harness = await startHarness({ now: c.now });
    for (let i = 0; i < API_REQUESTS_PER_MINUTE; i++) {
      expect((await harness.request('user-a', 'GET', '/api/v1/me')).statusCode).toBe(200);
    }
    const limited = await harness.request('user-a', 'GET', '/api/v1/me');
    expect(limited.statusCode).toBe(429);
    expect(limited.headers['retry-after']).toBe('60');
    expect((await harness.request('user-b', 'GET', '/api/v1/me')).statusCode).toBe(200);
    c.advance(60_000);
    expect((await harness.request('user-a', 'GET', '/api/v1/me')).statusCode).toBe(200);
  });
});

describe('input validation (SEC-05)', () => {
  it.each([
    ['unknown field', { ...workAlarm, color: 'red' }],
    ['bad time', { ...workAlarm, time: '25:00' }],
    ['numeric weekday', { ...workAlarm, repeat_days: [1] }],
    ['label too long', { ...workAlarm, label: 'x'.repeat(101) }],
    [
      'missing offset',
      { time: '06:00', actions: [{ script_entity_id: 'script.coffee', timing: 'before' }] },
    ],
    ['not a script', { time: '06:00', actions: [{ script_entity_id: 'light.x', timing: 'at' }] }],
    ['array body', [workAlarm]],
  ])('rejects %s with 400', async (_name, body) => {
    harness = await startHarness({ now: () => NOW });
    const res = await harness.request('user-a', 'POST', '/api/v1/alarms', body);
    expect(res.statusCode).toBe(400);
    expect(res.json()).toMatchObject({ error: 'invalid_input' });
    expect(typeof res.json().message).toBe('string');
  });

  it('rejects invalid JSON and oversized bodies', async () => {
    harness = await startHarness({ now: () => NOW });
    const broken = await harness.request('user-a', 'POST', '/api/v1/alarms', '{"time":', {
      headers: { 'x-remote-user-id': 'user-a', 'content-type': 'application/json' },
    });
    expect(broken.statusCode).toBe(400);
    const huge = await harness.request('user-a', 'POST', '/api/v1/alarms', {
      time: '06:00',
      label: 'x'.repeat(70 * 1024),
    });
    expect(huge.statusCode).toBe(413);
    expect(huge.json()).toMatchObject({ error: 'payload_too_large' });
  });

  it('rejects a body on skip', async () => {
    harness = await startHarness({ now: () => NOW });
    const alarm = await create(harness, 'user-a');
    const res = await harness.request('user-a', 'POST', `/api/v1/alarms/${alarm.id}/skip`, {
      skip: true,
    });
    expect(res.statusCode).toBe(400);
  });

  it('requires a revision on update', async () => {
    harness = await startHarness({ now: () => NOW });
    const alarm = await create(harness, 'user-a');
    const body: Record<string, unknown> = updateBody(alarm);
    delete body.revision;
    const res = await harness.request('user-a', 'PUT', `/api/v1/alarms/${alarm.id}`, body);
    expect(res.statusCode).toBe(400);
  });
});

describe('caps (SEC-05)', () => {
  it('allows at most 50 alarms per user', async () => {
    harness = await startHarness({ now: () => NOW });
    for (let i = 0; i < 50; i++) await create(harness, 'user-a', { time: '06:00' });
    const res = await harness.request('user-a', 'POST', '/api/v1/alarms', { time: '06:00' });
    expect(res.statusCode).toBe(422);
    expect(res.json()).toMatchObject({ error: 'limit_reached' });
    await create(harness, 'user-b', { time: '06:00' });
  });

  it('allows at most 20 actions per alarm', async () => {
    harness = await startHarness({ now: () => NOW });
    const actions = Array.from({ length: 21 }, (_, i) => ({
      script_entity_id: 'script.coffee',
      timing: 'before',
      offset_minutes: i + 1,
    }));
    const res = await harness.request('user-a', 'POST', '/api/v1/alarms', {
      time: '06:00',
      actions,
    });
    expect(res.statusCode).toBe(422);
    await create(harness, 'user-a', { time: '06:00', actions: actions.slice(0, 20) });
  });
});

describe('actions (labeled scripts)', () => {
  it('lists labeled scripts only', async () => {
    harness = await startHarness({ now: () => NOW });
    const res = await harness.request('user-a', 'GET', '/api/v1/actions');
    expect(res.json()).toEqual({
      actions: [
        {
          entity_id: 'script.coffee',
          name: 'Coffee',
          icon: null,
          icon_path: mdiScriptText,
          description: null,
        },
        {
          entity_id: 'script.heat_bathroom',
          name: 'Heat bathroom',
          icon: 'mdi:radiator',
          icon_path: mdiRadiator,
          description: null,
        },
      ],
    });
  });

  it('follows label changes without a restart', async () => {
    harness = await startHarness({ now: () => NOW });
    const { fake, services } = harness;
    fake.state.entities.find((e) => e.entity_id === 'script.unlabeled')!.labels = ['alarm_action'];
    fake.state.entities.find((e) => e.entity_id === 'script.coffee')!.labels = [];
    fake.fireEvent('entity_registry_updated', { action: 'update' });
    await waitFor(() => services.scripts.isAvailable('script.unlabeled'), 2000, 'label added');
    expect(services.scripts.isAvailable('script.coffee')).toBe(false);
  });

  it('matches the label by name and ignores disabled scripts', async () => {
    const fake = await FakeHaCore.start();
    fake.state.labels = [{ label_id: 'wake_up_123', name: 'Alarm_Action' }];
    fake.state.entities = [
      { entity_id: 'script.coffee', labels: ['wake_up_123'] },
      { entity_id: 'script.heat_bathroom', labels: ['wake_up_123'], disabled_by: 'user' },
      { entity_id: 'light.kitchen', labels: ['wake_up_123'] },
    ];
    harness = await startHarness({ fake, now: () => NOW });
    expect(harness.services.scripts.list().map((s) => s.entity_id)).toEqual(['script.coffee']);
    await harness.close(true);
    harness = null;
    await fake.close();
  });

  it('refuses to attach a script without the label, but keeps one that lost it', async () => {
    harness = await startHarness({ now: () => NOW });
    const h = harness;
    const res = await h.request('user-a', 'POST', '/api/v1/alarms', {
      time: '06:00',
      actions: [{ script_entity_id: 'script.unlabeled', timing: 'at' }],
    });
    expect(res.statusCode).toBe(422);
    expect(res.json()).toMatchObject({
      error: 'script_unavailable',
      script_entity_id: 'script.unlabeled',
    });

    const alarm = await create(h, 'user-a');
    h.fake.state.entities.find((e) => e.entity_id === 'script.coffee')!.labels = [];
    h.fake.fireEvent('entity_registry_updated', {});
    await waitFor(() => !h.services.scripts.isAvailable('script.coffee'), 2000, 'label removed');
    const edit = await h.request(
      'user-a',
      'PUT',
      `/api/v1/alarms/${alarm.id}`,
      updateBody(alarm, { label: 'Still here' }),
    );
    expect(edit.statusCode, edit.body).toBe(200);
  });
});

describe('info and settings', () => {
  it('returns the API versions and the HA time zone', async () => {
    harness = await startHarness({ now: () => NOW });
    expect((await harness.request('user-a', 'GET', '/api/v1/info')).json()).toMatchObject({
      name: 'Alarm Actions',
      api_version: 1,
      min_client_api_version: 1,
      time_zone: 'Europe/Brussels',
      ha_connected: true,
      app_available: false,
    });
  });

  it('returns the add-on options', async () => {
    harness = await startHarness({ now: () => NOW, settings: { snooze_minutes: 7 } });
    expect((await harness.request('user-a', 'GET', '/api/v1/settings')).json()).toMatchObject({
      snooze_minutes: 7,
      action_label: 'alarm_action',
    });
  });
});

describe('script icons (FR-UI-03)', () => {
  it('resolves mdi icons to SVG paths', () => {
    expect(mdiPath('mdi:radiator')).toBe(mdiRadiator);
    expect(mdiPath('hass:radiator')).toBe(mdiRadiator);
    expect(mdiPath(null)).toBe(mdiScriptText);
    expect(mdiPath('mdi:does-not-exist')).toBeNull();
    expect(mdiPath('custom:thing')).toBeNull();
  });
});

describe('app add-on probe (TR-APP-03)', () => {
  it('reports the app add-on as available while it answers, cached for 30 s', async () => {
    let hits = 0;
    let up = true;
    const server = http.createServer((req, res) => {
      hits++;
      if (!up || req.url !== '/info') {
        res.statusCode = 503;
        res.end();
        return;
      }
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ version: '1.2.0' }));
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
      let t = NOW;
      const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
      const probe = new AppProbe(url, createLogger('silent'), () => t);
      expect(await probe.info()).toEqual({ available: true, version: '1.2.0' });
      up = false;
      expect(await probe.info()).toEqual({ available: true, version: '1.2.0' });
      expect(hits).toBe(1);
      t += 31_000;
      expect(await probe.info()).toEqual({ available: false, version: null });
    } finally {
      server.close();
    }
  });

  it('peek answers from the cache at once and refreshes in the background', async () => {
    const server = http.createServer((_req, res) => {
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ version: '1.2.0' }));
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
      const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
      const probe = new AppProbe(url, createLogger('silent'));
      expect(probe.peek()).toBe(false);
      await waitFor(() => probe.peek());
    } finally {
      server.close();
    }
  });

  it('reports unavailable without a URL or when nothing listens', async () => {
    expect(await new AppProbe(null, createLogger('silent')).info()).toEqual({
      available: false,
      version: null,
    });
    const probe = new AppProbe('http://127.0.0.1:1', createLogger('silent'));
    expect(await probe.info()).toEqual({ available: false, version: null });
  });

  it('serves /app/info and info.app_available to logged-in users only', async () => {
    harness = await startHarness({ now: () => NOW });
    harness.services.appProbe.info = async () => ({ available: true, version: '1.0.0' });
    harness.services.appProbe.peek = () => true;
    const res = await harness.request('user-a', 'GET', '/api/v1/app/info');
    expect(res.json()).toEqual({ available: true, version: '1.0.0' });
    const info = await harness.request('user-a', 'GET', '/api/v1/info');
    expect(info.json()).toMatchObject({ app_available: true });
    expect((await harness.request(null, 'GET', '/api/v1/app/info')).statusCode).toBe(401);
  });
});
