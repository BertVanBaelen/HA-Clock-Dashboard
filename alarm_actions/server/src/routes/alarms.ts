import type { FastifyInstance, FastifyRequest } from 'fastify';
import {
  LIMITS,
  alarmCreateSchema,
  alarmUpdateSchema,
  emptyBodySchema,
  nextOccurrence,
  type Alarm,
  type AlarmActionInput,
  type AlarmAction,
  type AlarmSaveResponse,
} from '@alarm-actions/shared';
import { conflict, notFound, unavailable, unprocessable } from '../errors.js';
import type { HaUser } from '../ha/users.js';
import { authorizeAlarm, currentUser, visibleOwner } from '../security.js';
import type { Services } from '../services.js';
import { assignActionIds, type StoredAlarm } from '../store.js';
import { parseBody, UUID_PARAM } from './validation.js';

const iso = (ms: number) => new Date(ms).toISOString();

/** Next time the alarm rings, null when disabled or the HA time zone is not known yet. */
export function nextOccurrenceOf(svc: Services, alarm: StoredAlarm): number | null {
  const tz = svc.directory.timeZone;
  if (!alarm.enabled || !tz) return null;
  return nextOccurrence(alarm, tz, svc.now());
}

/** Stored alarm to API shape, as seen by `viewer`. */
export function toApiAlarm(svc: Services, alarm: StoredAlarm, viewer: HaUser): Alarm {
  const next = nextOccurrenceOf(svc, alarm);
  return {
    id: alarm.id,
    owner_user_id: alarm.owner_user_id,
    owner_name: svc.users.get(alarm.owner_user_id)?.name ?? null,
    label: alarm.label,
    time: alarm.time,
    repeat_days: alarm.repeat_days,
    enabled: alarm.enabled,
    skip_next: alarm.skip_next,
    snooze_minutes: alarm.snooze_minutes,
    actions: alarm.actions,
    revision: alarm.revision,
    change_seq: alarm.change_seq,
    created_at: iso(alarm.created_at),
    updated_at: iso(alarm.updated_at),
    next_occurrence: next === null ? null : iso(next),
    disabled_reason: alarm.disabled_reason,
    editable: alarm.owner_user_id === viewer.id,
  };
}

/** Hook for save warnings (phase 3 adds `before` runs that are already past). */
export type SaveWarningsHook = (alarm: StoredAlarm) => AlarmSaveResponse['warnings'];

/**
 * Checks the action caps (SEC-05) and the label gate for scripts that are new on this alarm:
 * a script that is already attached may stay even when it lost its label (shown as
 * unavailable, FR-ACT-07), but nothing new can be attached unless it is labeled.
 */
function checkActions(
  svc: Services,
  actions: AlarmActionInput[],
  existing: readonly AlarmAction[],
): void {
  if (actions.length > LIMITS.actionsPerAlarm) {
    throw unprocessable(
      'limit_reached',
      `An alarm can have at most ${LIMITS.actionsPerAlarm} actions.`,
    );
  }
  const total = svc.store.countActions() - existing.length + actions.length;
  if (actions.length > existing.length && total > LIMITS.actionsTotal) {
    throw unprocessable('limit_reached', `At most ${LIMITS.actionsTotal} actions in total.`);
  }
  const attached = new Set(existing.map((a) => a.script_entity_id));
  const added = actions.filter((a) => !attached.has(a.script_entity_id));
  if (added.length === 0) return;
  if (!svc.scripts.ready) {
    throw unavailable('The list of scripts is not loaded yet. Try again shortly.');
  }
  for (const action of added) {
    if (!svc.scripts.isAvailable(action.script_entity_id)) {
      throw unprocessable(
        'script_unavailable',
        `${action.script_entity_id} is not a script with the ${svc.config.settings.action_label} label.`,
        { script_entity_id: action.script_entity_id },
      );
    }
  }
}

export function alarmRoutes(app: FastifyInstance, saveWarnings: SaveWarningsHook): void {
  const svc = app.svc;

  const load = (id: string) => (UUID_PARAM.test(id) ? svc.store.get(id.toLowerCase()) : null);

  const saved = (alarm: StoredAlarm, user: HaUser): AlarmSaveResponse => ({
    alarm: toApiAlarm(svc, alarm, user),
    warnings: saveWarnings(alarm),
  });

  app.get('/alarms', async (req) => {
    const user = currentUser(req);
    return { alarms: svc.store.list(visibleOwner(user)).map((a) => toApiAlarm(svc, a, user)) };
  });

  app.post('/alarms', async (req, reply) => {
    const user = currentUser(req);
    const input = parseBody(alarmCreateSchema, req.body);
    if (svc.store.countByOwner(user.id) >= LIMITS.alarmsPerUser) {
      throw unprocessable('limit_reached', `You can have at most ${LIMITS.alarmsPerUser} alarms.`);
    }
    checkActions(svc, input.actions, []);
    const alarm = svc.store.create(user.id, input);
    return reply.code(201).send(saved(alarm, user));
  });

  app.get<{ Params: { id: string } }>('/alarms/:id', async (req) => {
    const user = currentUser(req);
    const alarm = authorizeAlarm(user, load(req.params.id), 'read');
    return toApiAlarm(svc, alarm, user);
  });

  app.put<{ Params: { id: string } }>('/alarms/:id', async (req) => {
    const user = currentUser(req);
    const current = authorizeAlarm(user, load(req.params.id), 'write');
    const input = parseBody(alarmUpdateSchema, req.body);
    if (input.revision !== current.revision) throw conflict(current.revision);
    checkActions(svc, input.actions, current.actions);
    const result = svc.store.update(
      current.id,
      {
        label: input.label,
        time: input.time,
        repeat_days: input.repeat_days,
        enabled: input.enabled,
        snooze_minutes: input.snooze_minutes,
        actions: assignActionIds(input.actions, current.actions),
        // Skipping only exists for repeating alarms.
        skip_next: input.repeat_days.length > 0 ? current.skip_next : false,
        // Enabling again clears why the system disabled it.
        disabled_reason: input.enabled ? null : current.disabled_reason,
      },
      input.revision,
    );
    if (!result) throw notFound('Alarm');
    if ('conflict' in result) throw conflict(result.conflict);
    return saved(result, user);
  });

  app.delete<{ Params: { id: string } }>('/alarms/:id', async (req, reply) => {
    const user = currentUser(req);
    const alarm = authorizeAlarm(user, load(req.params.id), 'write');
    svc.store.delete(alarm.id);
    return reply.code(204).send();
  });

  const setSkip = async (req: FastifyRequest<{ Params: { id: string } }>, skip: boolean) => {
    const user = currentUser(req);
    const alarm = authorizeAlarm(user, load(req.params.id), 'write');
    parseBody(emptyBodySchema, req.body);
    if (alarm.repeat_days.length === 0) {
      throw unprocessable('not_repeating', 'Only repeating alarms can be skipped.');
    }
    const result =
      alarm.skip_next === skip ? alarm : svc.store.update(alarm.id, { skip_next: skip });
    if (!result || 'conflict' in result) throw notFound('Alarm');
    return toApiAlarm(svc, result, user);
  };

  app.post<{ Params: { id: string } }>('/alarms/:id/skip', (req) => setSkip(req, true));
  app.post<{ Params: { id: string } }>('/alarms/:id/unskip', (req) => setSkip(req, false));
}
