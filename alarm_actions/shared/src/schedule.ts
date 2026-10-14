import type { AlarmAction, Weekday } from './types.js';
import { WEEKDAYS } from './types.js';

/**
 * Schedule calculation (TR-SCH-01, TR-API-04). Pure functions without Node APIs, so the server
 * and the panel compute exactly the same occurrences; the app uses the same test vectors
 * (docs/test-vectors/schedule.json).
 *
 * Local times are wall-clock times in an IANA time zone:
 * - a time that does not exist (spring forward gap) resolves to the first valid instant after it;
 * - an ambiguous time (fall back) resolves to its first occurrence.
 */

const MINUTE = 60_000;
const DAY = 86_400_000;

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let f = formatters.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    formatters.set(timeZone, f);
  }
  return f;
}

/** True when the runtime knows the IANA time zone. */
export function isValidTimeZone(timeZone: string): boolean {
  try {
    formatter(timeZone);
    return true;
  } catch {
    return false;
  }
}

export interface LocalDateTime {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

/** Wall-clock date and time of an instant in a time zone. */
export function toLocal(ms: number, timeZone: string): LocalDateTime {
  const parts: Record<string, number> = {};
  for (const p of formatter(timeZone).formatToParts(new Date(ms))) {
    if (p.type !== 'literal') parts[p.type] = Number(p.value);
  }
  return {
    year: parts.year!,
    month: parts.month!,
    day: parts.day!,
    hour: parts.hour! % 24,
    minute: parts.minute!,
    second: parts.second!,
  };
}

/** UTC offset in minutes of a time zone at an instant (Brussels in summer: 120). */
export function offsetMinutes(ms: number, timeZone: string): number {
  const whole = Math.floor(ms / 1000) * 1000;
  const l = toLocal(whole, timeZone);
  const asUtc = Date.UTC(l.year, l.month - 1, l.day, l.hour, l.minute, l.second);
  return Math.round((asUtc - whole) / MINUTE);
}

/** The instant of a local wall-clock time (see the module comment for gaps and overlaps). */
export function resolveLocal(
  date: { year: number; month: number; day: number },
  hour: number,
  minute: number,
  timeZone: string,
): number {
  const naive = Date.UTC(date.year, date.month - 1, date.day, hour, minute);
  const offsets = new Set(
    [naive - DAY, naive, naive + DAY].map((t) => offsetMinutes(t, timeZone)),
  );
  const matches: number[] = [];
  for (const offset of offsets) {
    const candidate = naive - offset * MINUTE;
    if (offsetMinutes(candidate, timeZone) === offset) matches.push(candidate);
  }
  if (matches.length > 0) return Math.min(...matches);

  // In a gap: the first minute whose wall-clock time is at or after the requested time.
  const sorted = [...offsets].sort((a, b) => a - b);
  let lo = naive - sorted[sorted.length - 1]! * MINUTE;
  let hi = naive - sorted[0]! * MINUTE;
  const wall = (t: number) => t + offsetMinutes(t, timeZone) * MINUTE;
  while (hi - lo > MINUTE) {
    const mid = lo + Math.floor((hi - lo) / MINUTE / 2) * MINUTE;
    if (wall(mid) >= naive) hi = mid;
    else lo = mid;
  }
  return wall(lo) >= naive ? lo : hi;
}

/** Weekday name of a calendar date. */
export function weekdayOf(date: { year: number; month: number; day: number }): Weekday {
  const index = new Date(Date.UTC(date.year, date.month - 1, date.day)).getUTCDay();
  return WEEKDAYS[(index + 6) % 7]!;
}

function addDays(date: { year: number; month: number; day: number }, days: number) {
  const d = new Date(Date.UTC(date.year, date.month - 1, date.day + days));
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

export interface ScheduleInput {
  time: string;
  repeat_days: readonly Weekday[];
  skip_next?: boolean;
}

/** Occurrence instants strictly after `afterMs`, in order (at most `count`). */
export function upcomingOccurrences(
  alarm: ScheduleInput,
  timeZone: string,
  afterMs: number,
  count: number,
): number[] {
  const [hour, minute] = alarm.time.split(':').map(Number) as [number, number];
  const start = toLocal(afterMs, timeZone);
  const result: number[] = [];
  for (let i = 0; result.length < count && i < 7 * count + 2; i++) {
    const date = addDays(start, i);
    if (alarm.repeat_days.length > 0 && !alarm.repeat_days.includes(weekdayOf(date))) continue;
    const at = resolveLocal(date, hour, minute, timeZone);
    if (at > afterMs) result.push(at);
    if (alarm.repeat_days.length === 0 && result.length > 0) break;
  }
  return result;
}

/**
 * Next time the alarm rings strictly after `afterMs`. `skip_next` skips the first one (repeating
 * alarms only). Returns null when there is none.
 */
export function nextOccurrence(
  alarm: ScheduleInput,
  timeZone: string,
  afterMs: number,
): number | null {
  const skip = alarm.skip_next === true && alarm.repeat_days.length > 0;
  const list = upcomingOccurrences(alarm, timeZone, afterMs, skip ? 2 : 1);
  return (skip ? list[1] : list[0]) ?? null;
}

function compactUtc(ms: number): string {
  return new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z').replace(/[-:]/g, '');
}

/** Occurrence id `<alarm_id>_<YYYYMMDDTHHMMSSZ>` (TR-API-04). */
export function occurrenceId(alarmId: string, scheduledAtMs: number): string {
  return `${alarmId}_${compactUtc(scheduledAtMs)}`;
}

/** Planned-run id `<occurrence_id>_<alarm_action_id>` (TR-SCH-06). */
export function plannedRunId(occurrenceIdValue: string, alarmActionId: string): string {
  return `${occurrenceIdValue}_${alarmActionId}`;
}

/** Planned time of a `before` / `at` / `after` action; null for `after_dismiss`. */
export function plannedRunAt(
  scheduledAtMs: number,
  action: Pick<AlarmAction, 'timing' | 'offset_minutes'>,
): number | null {
  switch (action.timing) {
    case 'before':
      return scheduledAtMs - (action.offset_minutes ?? 0) * MINUTE;
    case 'at':
      return scheduledAtMs;
    case 'after':
      return scheduledAtMs + (action.offset_minutes ?? 0) * MINUTE;
    default:
      return null;
  }
}
