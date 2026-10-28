import {
  nextOccurrence,
  plannedRunAt,
  type Alarm,
  type AlarmAction,
  type Timing,
  type Weekday,
} from '@alarm-actions/shared';
import { t } from './strings';

/** Editable copy of an action; `key` is a client-only React key, `id` is absent for new ones. */
export interface DraftAction {
  key: string;
  id?: string;
  script_entity_id: string;
  timing: Timing;
  offset_minutes: number | null;
  delay_minutes: number | null;
  enabled: boolean;
}

export interface Draft {
  id?: string;
  revision?: number;
  skip_next: boolean;
  label: string;
  time: string;
  repeat_days: Weekday[];
  enabled: boolean;
  /** Empty means "use the default". */
  snooze_minutes: number | null;
  actions: DraftAction[];
}

let counter = 0;
export const newKey = () => `k${++counter}`;

/** True when saving `a` would send the same alarm as `b` (used to show Save only for changes). */
export function sameDraft(a: Draft, b: Draft): boolean {
  return JSON.stringify(draftBody(a)) === JSON.stringify(draftBody(b));
}

export function emptyDraft(): Draft {
  return {
    skip_next: false,
    label: '',
    time: '07:00',
    repeat_days: [],
    enabled: true,
    snooze_minutes: null,
    actions: [],
  };
}

function toDraftAction(a: AlarmAction): DraftAction {
  return {
    key: newKey(),
    id: a.id,
    script_entity_id: a.script_entity_id,
    timing: a.timing,
    offset_minutes: a.offset_minutes,
    delay_minutes: a.delay_minutes,
    enabled: a.enabled,
  };
}

export function draftFromAlarm(alarm: Alarm): Draft {
  return {
    id: alarm.id,
    revision: alarm.revision,
    skip_next: alarm.skip_next,
    label: alarm.label,
    time: alarm.time,
    repeat_days: [...alarm.repeat_days],
    enabled: alarm.enabled,
    snooze_minutes: alarm.snooze_minutes,
    actions: alarm.actions.map((a) => toDraftAction(a)),
  };
}

/** Opening an alarm to edit turns it on, so saving activates it. */
export function editDraft(alarm: Alarm): Draft {
  return { ...draftFromAlarm(alarm), enabled: true };
}

/** A new, unsaved copy (FR-ALM-08): no ids, so the server creates new ones. */
export function duplicateDraft(alarm: Alarm): Draft {
  return copyDraft(draftFromAlarm(alarm));
}

export function copyDraft(draft: Draft): Draft {
  return {
    ...draft,
    id: undefined,
    revision: undefined,
    skip_next: false,
    enabled: true,
    actions: draft.actions.map((a) => ({ ...a, id: undefined, key: newKey() })),
  };
}

export function newAction(script: string): DraftAction {
  return {
    key: newKey(),
    script_entity_id: script,
    timing: 'before',
    offset_minutes: 10,
    delay_minutes: null,
    enabled: true,
  };
}

/** Changing the timing resets the fields that do not apply to it. */
export function withTiming(action: DraftAction, timing: Timing): DraftAction {
  const needsOffset = timing === 'before' || timing === 'after';
  return {
    ...action,
    timing,
    offset_minutes: needsOffset ? (action.offset_minutes ?? 10) : null,
    delay_minutes: timing === 'after_dismiss' ? (action.delay_minutes ?? 0) : null,
  };
}

/** Request body for POST (create) or PUT (update, with revision). */
export function draftBody(draft: Draft): Record<string, unknown> {
  return {
    label: draft.label.trim(),
    time: draft.time,
    repeat_days: draft.repeat_days,
    enabled: draft.enabled,
    snooze_minutes: draft.snooze_minutes,
    actions: draft.actions.map((a) => ({
      ...(a.id ? { id: a.id } : {}),
      script_entity_id: a.script_entity_id,
      timing: a.timing,
      offset_minutes: a.offset_minutes,
      delay_minutes: a.delay_minutes,
      enabled: a.enabled,
    })),
    ...(draft.revision !== undefined ? { revision: draft.revision } : {}),
  };
}

/** Body to toggle an alarm from the list: the full alarm with only `enabled` changed. */
export function toggleBody(alarm: Alarm, enabled: boolean): Record<string, unknown> {
  return draftBody({ ...draftFromAlarm(alarm), enabled });
}

/** Problems that block saving, keyed by action key ('' for the alarm itself). */
export function validateDraft(draft: Draft): Map<string, string> {
  const errors = new Map<string, string>();
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(draft.time)) errors.set('', t.invalidTime);
  const seen = new Set<string>();
  for (const a of draft.actions) {
    if (!a.script_entity_id) {
      errors.set(a.key, t.chooseScript);
      continue;
    }
    if (a.offset_minutes !== null && !(a.offset_minutes >= 1 && a.offset_minutes <= 720)) {
      errors.set(a.key, t.minutesRange);
      continue;
    }
    if (a.delay_minutes !== null && !(a.delay_minutes >= 0 && a.delay_minutes <= 120)) {
      errors.set(a.key, t.delayRange);
      continue;
    }
    const sig = [a.script_entity_id, a.timing, a.offset_minutes, a.delay_minutes].join('|');
    if (seen.has(sig)) errors.set(a.key, t.duplicateAction);
    seen.add(sig);
  }
  return errors;
}

/**
 * Enabled `before` actions whose run time for the next occurrence is already past (FR-UI-09,
 * FR-EXE-09). The server decides at save time; this is the early warning in the editor.
 */
export function missedBeforeActions(
  draft: Draft,
  timeZone: string | null,
  now: number,
): DraftAction[] {
  if (!timeZone || !draft.enabled || !/^\d\d:\d\d$/.test(draft.time)) return [];
  let next: number | null;
  try {
    next = nextOccurrence(draft, timeZone, now);
  } catch {
    return [];
  }
  if (next === null) return [];
  return draft.actions.filter((a) => {
    if (!a.enabled || a.timing !== 'before' || !a.offset_minutes) return false;
    const at = plannedRunAt(next, a);
    return at !== null && at <= now;
  });
}
