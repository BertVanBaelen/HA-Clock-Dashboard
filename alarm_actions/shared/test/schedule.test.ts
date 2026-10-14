import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  nextOccurrence,
  occurrenceId,
  offsetMinutes,
  plannedRunAt,
  plannedRunId,
  resolveLocal,
  upcomingOccurrences,
  weekdayOf,
} from '../src/schedule.js';
import type { Weekday } from '../src/types.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(
  fs.readFileSync(path.resolve(here, '../../../docs/test-vectors/schedule.json'), 'utf8'),
) as {
  schedule: Array<{
    name: string;
    time_zone: string;
    time: string;
    repeat_days: Weekday[];
    skip_next: boolean;
    reference: string;
    expected: string;
  }>;
  occurrence_ids: Array<{ alarm_id: string; scheduled_at: string; expected: string }>;
  planned_run_ids: Array<{ occurrence_id: string; alarm_action_id: string; expected: string }>;
};

const iso = (ms: number | null) =>
  ms === null ? null : new Date(ms).toISOString().replace('.000Z', 'Z');

describe('schedule test vectors', () => {
  for (const v of vectors.schedule) {
    it(v.name, () => {
      const next = nextOccurrence(v, v.time_zone, Date.parse(v.reference));
      expect(iso(next)).toBe(v.expected);
    });
  }

  for (const v of vectors.occurrence_ids) {
    it(`occurrence id ${v.expected}`, () => {
      expect(occurrenceId(v.alarm_id, Date.parse(v.scheduled_at))).toBe(v.expected);
    });
  }

  for (const v of vectors.planned_run_ids) {
    it(`planned run id ${v.expected}`, () => {
      expect(plannedRunId(v.occurrence_id, v.alarm_action_id)).toBe(v.expected);
    });
  }
});

describe('time zone helpers', () => {
  it('computes offsets', () => {
    expect(offsetMinutes(Date.parse('2026-07-01T12:00:00Z'), 'Europe/Brussels')).toBe(120);
    expect(offsetMinutes(Date.parse('2026-01-01T12:00:00Z'), 'Europe/Brussels')).toBe(60);
    expect(offsetMinutes(Date.parse('2026-01-01T12:00:00Z'), 'America/New_York')).toBe(-300);
  });

  it('resolves gap and overlap', () => {
    const gap = resolveLocal({ year: 2026, month: 3, day: 29 }, 2, 0, 'Europe/Brussels');
    expect(iso(gap)).toBe('2026-03-29T01:00:00Z');
    const overlap = resolveLocal({ year: 2026, month: 10, day: 25 }, 2, 0, 'Europe/Brussels');
    expect(iso(overlap)).toBe('2026-10-25T00:00:00Z');
  });

  it('names weekdays', () => {
    expect(weekdayOf({ year: 2026, month: 10, day: 14 })).toBe('wed');
    expect(weekdayOf({ year: 2026, month: 10, day: 18 })).toBe('sun');
  });

  it('lists several upcoming occurrences', () => {
    const list = upcomingOccurrences(
      { time: '06:45', repeat_days: ['mon', 'fri'] },
      'Europe/Brussels',
      Date.parse('2026-10-14T10:16:00Z'),
      3,
    );
    expect(list.map(iso)).toEqual([
      '2026-10-16T04:45:00Z',
      '2026-10-19T04:45:00Z',
      '2026-10-23T04:45:00Z',
    ]);
  });

  it('computes planned run times', () => {
    const at = Date.parse('2026-10-15T04:45:00Z');
    expect(iso(plannedRunAt(at, { timing: 'before', offset_minutes: 20 }))).toBe(
      '2026-10-15T04:25:00Z',
    );
    expect(iso(plannedRunAt(at, { timing: 'at', offset_minutes: null }))).toBe(
      '2026-10-15T04:45:00Z',
    );
    expect(iso(plannedRunAt(at, { timing: 'after', offset_minutes: 5 }))).toBe(
      '2026-10-15T04:50:00Z',
    );
    expect(plannedRunAt(at, { timing: 'after_dismiss', offset_minutes: null })).toBeNull();
  });
});
