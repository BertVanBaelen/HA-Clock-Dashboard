import { afterEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_SETTINGS,
  occurrenceId,
  plannedRunId,
  type Alarm,
  type AlarmSaveResponse,
  type RunNowResponse,
  type Settings,
} from '@alarm-actions/shared';
import { REASON } from '../src/scheduler.js';
import { FakeHaCore } from './helpers/fakeHa.js';
import { startHarness, tempDir, waitFor, type Harness } from './helpers/harness.js';

const MINUTE = 60_000;
const DAY = 86_400_000;

/** 2026-10-14 12:16 in Brussels (a Wednesday, CEST = UTC+2). */
const NOW = Date.parse('2026-10-14T10:16:00Z');
/** 12:30 Brussels on the same day. */
const T1230 = Date.parse('2026-10-14T10:30:00Z');

let harness: Harness | null = null;
let fake: FakeHaCore | null = null;

afterEach(async () => {
  await harness?.close(true);
  await fake?.close();
  harness = null;
  fake = null;
});

function clock(start = NOW) {
  const c = {
    t: start,
    now: () => c.t,
    set: (ms: number) => {
      c.t = ms;
    },
  };
  return c;
}

type Clock = ReturnType<typeof clock>;

interface Setup {
  h: Harness;
  c: Clock;
  dataDir: string;
}

async function ready(h: Harness): Promise<void> {
  await waitFor(
    () => h.services.scripts.ready && h.services.users.loaded && h.services.directory.timeZone !== null,
    5000,
    'HA data',
  );
  await h.services.scheduler.tick();
}

async function setup(settings: Partial<Settings> = {}, c = clock()): Promise<Setup> {
  fake = await FakeHaCore.start();
  const dataDir = tempDir();
  harness = await startHarness({ fake, dataDir, now: c.now, settings });
  await ready(harness);
  return { h: harness, c, dataDir };
}

/** Restarts the add-on on the same data and fake Core. */
async function restart(s: Setup, settings: Partial<Settings> = {}): Promise<Harness> {
  await s.h.close();
  harness = await startHarness({ fake: fake!, dataDir: s.dataDir, now: s.c.now, settings });
  s.h = harness;
  await ready(harness);
  return harness;
}

async function create(h: Harness, body: Record<string, unknown>, user = 'user-a') {
  const res = await h.request(user, 'POST', '/api/v1/alarms', {
    label: 'Test',
    time: '12:30',
    repeat_days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'],
    ...body,
  });
  expect(res.statusCode, res.body).toBe(201);
  return res.json() as AlarmSaveResponse;
}

async function at(s: Setup, ms: number): Promise<void> {
  s.c.set(ms);
  await s.h.services.scheduler.tick();
}

const started = (h: Harness) =>
  h.fake.calls.map((c) => {
    expect(c).toMatchObject({ domain: 'script', service: 'turn_on' });
    return (c.target as { entity_id: string }).entity_id;
  });

function records(h: Harness, alarm: Alarm) {
  return h.services.history
    .forAlarm(alarm.id, 100)
    .map((r) => ({ action: r.alarm_action_id, result: r.result, reason: r.reason, planned_at: r.planned_at }))
    .sort((a, b) => a.planned_at - b.planned_at);
}

const heat = 'script.heat_bathroom';
const coffee = 'script.coffee';

describe('planned runs', () => {
  it('starts before, at and after actions at the right times', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, {
      actions: [
        { script_entity_id: heat, timing: 'before', offset_minutes: 10 },
        { script_entity_id: coffee, timing: 'at' },
        { script_entity_id: heat, timing: 'after', offset_minutes: 5 },
      ],
    });
    expect(s.h.services.scheduler.pendingOccurrence(alarm.id)?.scheduled_at).toBe(T1230);

    await at(s, T1230 - 10 * MINUTE - 1000);
    expect(started(s.h)).toEqual([]);
    await at(s, T1230 - 10 * MINUTE);
    expect(started(s.h)).toEqual([heat]);
    await at(s, T1230 - 1000);
    expect(started(s.h)).toEqual([heat]);
    await at(s, T1230);
    expect(started(s.h)).toEqual([heat, coffee]);
    await at(s, T1230 + 5 * MINUTE);
    expect(started(s.h)).toEqual([heat, coffee, heat]);

    expect(records(s.h, alarm).map((r) => r.result)).toEqual(['success', 'success', 'success']);
    // Exactly once: more ticks start nothing; the fired occurrence is then done.
    await at(s, T1230 + 6 * MINUTE);
    expect(started(s.h)).toHaveLength(3);
    const occurrences = s.h.services.scheduler.occurrences(alarm.id);
    expect(occurrences.map((o) => [o.scheduled_at, o.status, o.runs_done])).toEqual([
      [T1230, 'fired', 1],
      [T1230 + DAY, 'pending', 0],
    ]);
  });

  it('runs planned runs of two occurrences that overlap', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, {
      time: '12:20',
      actions: [
        { script_entity_id: coffee, timing: 'before', offset_minutes: 720 },
        { script_entity_id: heat, timing: 'after', offset_minutes: 720 },
      ],
    });
    await at(s, NOW + 4 * MINUTE); // day 1 fires at 12:20
    // 00:20: day 1's after run and day 2's before run.
    await at(s, NOW + 4 * MINUTE + 12 * 60 * MINUTE);
    expect(started(s.h).sort()).toEqual([coffee, heat]);
    expect(s.h.services.scheduler.occurrences(alarm.id).map((o) => o.status)).toEqual([
      'fired',
      'pending',
    ]);
  });

  it('skips the next occurrence and resets the flag (FR-ALM-05, 06)', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, {
      actions: [
        { script_entity_id: heat, timing: 'before', offset_minutes: 10 },
        { script_entity_id: coffee, timing: 'at' },
      ],
    });
    expect((await s.h.request('user-a', 'POST', `/api/v1/alarms/${alarm.id}/skip`)).statusCode).toBe(200);
    await at(s, T1230 - 10 * MINUTE);
    await at(s, T1230);
    expect(started(s.h)).toEqual([]);
    expect(records(s.h, alarm).map((r) => [r.result, r.reason])).toEqual([
      ['skipped', REASON.skipped],
      ['skipped', REASON.skipped],
    ]);
    const after = s.h.services.store.get(alarm.id)!;
    expect(after.skip_next).toBe(false);
    expect(after.revision).toBe(alarm.revision + 2);

    await at(s, T1230 + DAY - 10 * MINUTE);
    await at(s, T1230 + DAY);
    expect(started(s.h)).toEqual([heat, coffee]);
  });

  it('disables a one-time alarm when it fires but still runs its after actions', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, {
      repeat_days: [],
      actions: [
        { script_entity_id: coffee, timing: 'at' },
        { script_entity_id: heat, timing: 'after', offset_minutes: 5 },
      ],
    });
    await at(s, T1230);
    const stored = s.h.services.store.get(alarm.id)!;
    expect(stored.enabled).toBe(false);
    expect(stored.disabled_reason).toBe('fired');
    expect(s.h.services.scheduler.pendingOccurrence(alarm.id)).toBeNull();
    await at(s, T1230 + 5 * MINUTE);
    expect(started(s.h)).toEqual([coffee, heat]);
  });

  it('records a before action as missed when it is already past on save (FR-EXE-09)', async () => {
    const s = await setup();
    const res = await create(s.h, {
      time: '12:20',
      actions: [
        { script_entity_id: heat, timing: 'before', offset_minutes: 10 },
        { script_entity_id: coffee, timing: 'at' },
      ],
    });
    expect(res.warnings).toEqual([
      expect.objectContaining({ code: 'before_missed', alarm_action_id: res.alarm.actions[0]!.id }),
    ]);
    expect(records(s.h, res.alarm)).toEqual([
      expect.objectContaining({ result: 'missed', reason: REASON.beforePast }),
    ]);
    await at(s, NOW + 4 * MINUTE);
    expect(started(s.h)).toEqual([coffee]);
  });

  it('follows DST changes (Europe/Brussels, 25 Oct 2026)', async () => {
    const s = await setup({}, clock(Date.parse('2026-10-24T12:00:00Z')));
    const { alarm } = await create(s.h, {
      time: '07:00',
      repeat_days: ['sat', 'sun'],
      actions: [{ script_entity_id: heat, timing: 'before', offset_minutes: 10 }],
    });
    // 07:00 CET = 06:00Z after the switch back.
    const sunday = Date.parse('2026-10-25T06:00:00Z');
    expect(s.h.services.scheduler.pendingOccurrence(alarm.id)?.scheduled_at).toBe(sunday);
    await at(s, sunday - 10 * MINUTE - 1000);
    expect(started(s.h)).toEqual([]);
    await at(s, sunday - 10 * MINUTE);
    expect(started(s.h)).toEqual([heat]);
  });
});

describe('edits and deletes', () => {
  it('cancels removed actions of a fired occurrence and does not add new ones (FR-EXE-14)', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, {
      actions: [
        { script_entity_id: coffee, timing: 'at' },
        { script_entity_id: heat, timing: 'after', offset_minutes: 10 },
      ],
    });
    await at(s, T1230);
    const res = await s.h.request('user-a', 'PUT', `/api/v1/alarms/${alarm.id}`, {
      label: alarm.label,
      time: alarm.time,
      repeat_days: alarm.repeat_days,
      enabled: true,
      snooze_minutes: alarm.snooze_minutes,
      revision: s.h.services.store.get(alarm.id)!.revision,
      actions: [
        { ...alarm.actions[0] },
        { script_entity_id: heat, timing: 'after', offset_minutes: 5 },
      ],
    });
    expect(res.statusCode, res.body).toBe(200);
    await at(s, T1230 + 15 * MINUTE);
    expect(started(s.h)).toEqual([coffee]);
    const fired = s.h.services.scheduler.occurrences(alarm.id)[0]!;
    expect(fired).toMatchObject({ status: 'fired', runs_done: 1 });
  });

  it('cancels all runs of a deleted alarm', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, {
      actions: [
        { script_entity_id: coffee, timing: 'at' },
        { script_entity_id: heat, timing: 'after', offset_minutes: 10 },
      ],
    });
    await at(s, T1230);
    expect((await s.h.request('user-a', 'DELETE', `/api/v1/alarms/${alarm.id}`)).statusCode).toBe(204);
    await at(s, T1230 + 15 * MINUTE);
    expect(started(s.h)).toEqual([coffee]);
    expect(s.h.services.scheduler.occurrences(alarm.id)).toEqual([]);
  });

  it('removes the pending occurrence of a disabled alarm', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    const res = await s.h.request('user-a', 'PUT', `/api/v1/alarms/${alarm.id}`, {
      label: alarm.label,
      time: alarm.time,
      repeat_days: alarm.repeat_days,
      enabled: false,
      snooze_minutes: alarm.snooze_minutes,
      revision: alarm.revision,
      actions: alarm.actions,
    });
    expect(res.statusCode, res.body).toBe(200);
    expect(s.h.services.scheduler.pendingOccurrence(alarm.id)).toBeNull();
    await at(s, T1230);
    expect(started(s.h)).toEqual([]);
  });
});

describe('restarts and outages (TR-SCH-05, TR-HA-05)', () => {
  it('runs a missed run after a restart within the grace period', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    s.c.set(T1230 + 3 * MINUTE);
    await restart(s);
    expect(started(s.h)).toEqual([coffee]);
    expect(records(s.h, alarm)[0]).toMatchObject({ result: 'success' });
  });

  it('records a run as missed after a restart beyond the grace period', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    s.c.set(T1230 + 6 * MINUTE);
    await restart(s);
    expect(started(s.h)).toEqual([]);
    expect(records(s.h, alarm)[0]).toMatchObject({ result: 'missed', reason: REASON.grace });
  });

  it('does not start again a script whose start was interrupted by a restart', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    const occ = occurrenceId(alarm.id, T1230);
    s.h.services.history.beginStart(
      {
        planned_run_id: plannedRunId(occ, alarm.actions[0]!.id),
        owner_user_id: 'user-a',
        alarm_id: alarm.id,
        alarm_action_id: alarm.actions[0]!.id,
        occurrence_id: occ,
        script_entity_id: coffee,
        planned_at: T1230,
      },
      { maxStartsPerMinute: 100, minIntervalMs: 0 },
    );
    s.c.set(T1230 + MINUTE);
    await restart(s);
    expect(started(s.h)).toEqual([]);
    expect(records(s.h, alarm)[0]).toMatchObject({ result: 'failed', reason: 'interrupted' });
  });

  it('retries while Core is down and runs once it is back within the grace period', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    s.h.fake.accepting = false;
    s.h.fake.dropConnections();
    await waitFor(() => !s.h.services.ha.connected, 5000, 'disconnect');
    await at(s, T1230);
    expect(records(s.h, alarm)).toEqual([]);
    s.c.set(T1230 + 2 * MINUTE);
    const readyCount = s.h.services.ha.readyCount;
    s.h.fake.accepting = true;
    await waitFor(() => s.h.services.ha.readyCount > readyCount, 5000, 'reconnect');
    await waitFor(() => s.h.fake.calls.length > 0, 5000, 'script start');
    expect(started(s.h)).toEqual([coffee]);
    expect(records(s.h, alarm)[0]).toMatchObject({ result: 'success' });
  });

  it('records a run as missed when Core stays down for the whole grace period', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    s.h.fake.accepting = false;
    s.h.fake.dropConnections();
    await waitFor(() => !s.h.services.ha.connected, 5000, 'disconnect');
    await at(s, T1230);
    await at(s, T1230 + 5 * MINUTE + 1000);
    expect(records(s.h, alarm)[0]).toMatchObject({ result: 'missed', reason: REASON.grace });
    s.h.fake.accepting = true;
    await waitFor(() => s.h.services.ha.connected, 5000, 'reconnect');
    await s.h.services.scheduler.tick();
    expect(started(s.h)).toEqual([]);
  });
});

describe('executor safety (TR-EXE, SEC)', () => {
  it('records unavailable when the script lost its label', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    s.h.fake.state.entities.find((e) => e.entity_id === coffee)!.labels = [];
    await s.h.services.scripts.refresh();
    await at(s, T1230);
    expect(started(s.h)).toEqual([]);
    expect(records(s.h, alarm)[0]).toMatchObject({ result: 'unavailable' });
  });

  it('isolates a failing script from the other runs', async () => {
    const s = await setup();
    s.h.fake.failCall = (call) =>
      (call.target as { entity_id: string }).entity_id === heat ? 'Script error' : null;
    const { alarm } = await create(s.h, {
      actions: [
        { script_entity_id: heat, timing: 'at' },
        { script_entity_id: coffee, timing: 'at' },
      ],
    });
    await at(s, T1230);
    const byAction = new Map(records(s.h, alarm).map((r) => [r.action, r]));
    expect(byAction.get(alarm.actions[0]!.id)).toMatchObject({ result: 'failed' });
    expect(byAction.get(alarm.actions[0]!.id)!.reason).toContain('Script error');
    expect(byAction.get(alarm.actions[1]!.id)).toMatchObject({ result: 'success' });
  });

  it('refuses starts above the rate limit', async () => {
    const s = await setup({ max_script_starts_per_minute: 2, min_script_interval_seconds: 0 });
    const a = await create(s.h, {
      actions: [
        { script_entity_id: heat, timing: 'at' },
        { script_entity_id: coffee, timing: 'at' },
      ],
    });
    const b = await create(s.h, { actions: [{ script_entity_id: heat, timing: 'at' }] });
    await at(s, T1230);
    expect(started(s.h)).toHaveLength(2);
    const all = [...records(s.h, a.alarm), ...records(s.h, b.alarm)];
    expect(all.map((r) => [r.result, r.reason]).sort()).toEqual([
      ['skipped', 'rate limited'],
      ['success', null],
      ['success', null],
    ]);
  });

  it('refuses a second start of the same script within the minimum interval', async () => {
    const s = await setup();
    const a = await create(s.h, { actions: [{ script_entity_id: heat, timing: 'at' }] });
    const b = await create(s.h, { actions: [{ script_entity_id: heat, timing: 'at' }] });
    await at(s, T1230);
    expect(started(s.h)).toEqual([heat]);
    const all = [...records(s.h, a.alarm), ...records(s.h, b.alarm)];
    expect(all.map((r) => [r.result, r.reason]).sort()).toEqual([
      ['skipped', 'duplicate start'],
      ['success', null],
    ]);
  });

  it('disables alarms of a deactivated owner and keeps them disabled (FR-ACC-04)', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    const user = s.h.fake.state.users.find((u) => u.id === 'user-a')!;
    user.is_active = false;
    await s.h.services.users.refresh();
    expect(s.h.services.store.get(alarm.id)).toMatchObject({
      enabled: false,
      disabled_reason: 'owner_inactive',
    });
    user.is_active = true;
    await s.h.services.users.refresh();
    expect(s.h.services.store.get(alarm.id)!.enabled).toBe(false);
    await at(s, T1230);
    expect(started(s.h)).toEqual([]);
  });
});

describe('run now (FR-EXE-08)', () => {
  it('starts the script and records it without an occurrence', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    const url = `/api/v1/alarms/${alarm.id}/actions/${alarm.actions[0]!.id}/run`;
    const res = await s.h.request('user-a', 'POST', url);
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json() as RunNowResponse).toEqual({ result: 'success', reason: null });
    expect(started(s.h)).toEqual([coffee]);
    const [record] = s.h.services.history.forAlarm(alarm.id, 10);
    expect(record).toMatchObject({ occurrence_id: null, result: 'success' });
    expect(record!.planned_run_id).toMatch(/^manual_/);

    // The same limits apply: a second start right away is a duplicate.
    const again = await s.h.request('user-a', 'POST', url);
    expect(again.json()).toEqual({ result: 'skipped', reason: 'duplicate start' });
  });

  it('answers 404 for an unknown action and 503 while Core is down', async () => {
    const s = await setup();
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    const unknown = await s.h.request(
      'user-a',
      'POST',
      `/api/v1/alarms/${alarm.id}/actions/6f0c1a52-4c5e-4a56-9d4b-2b1d6d6a9b10/run`,
    );
    expect(unknown.statusCode).toBe(404);
    s.h.fake.accepting = false;
    s.h.fake.dropConnections();
    await waitFor(() => !s.h.services.ha.connected, 5000, 'disconnect');
    const down = await s.h.request(
      'user-a',
      'POST',
      `/api/v1/alarms/${alarm.id}/actions/${alarm.actions[0]!.id}/run`,
    );
    expect(down.statusCode).toBe(503);
    expect(s.h.services.history.forAlarm(alarm.id, 10)).toEqual([]);
  });
});

describe('retention', () => {
  it('prunes old records and finished occurrences', async () => {
    const s = await setup({ history_days: 1 });
    const { alarm } = await create(s.h, { actions: [{ script_entity_id: coffee, timing: 'at' }] });
    await at(s, T1230);
    expect(records(s.h, alarm)).toHaveLength(1);
    s.c.set(T1230 + 2 * DAY);
    s.h.services.scheduler.prune();
    expect(records(s.h, alarm)).toEqual([]);
    expect(s.h.services.scheduler.occurrences(alarm.id).map((o) => o.status)).toEqual(['pending']);
    expect(DEFAULT_SETTINGS.history_days).toBe(30);
  });
});
