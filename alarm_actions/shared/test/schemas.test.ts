import { describe, expect, it } from 'vitest';
import { alarmCreateSchema, alarmUpdateSchema } from '../src/schemas.js';

const base = { time: '06:45' };

describe('alarm schemas', () => {
  it('fills defaults and sorts days', () => {
    const parsed = alarmCreateSchema.parse({ ...base, repeat_days: ['fri', 'mon'] });
    expect(parsed).toEqual({
      label: '',
      time: '06:45',
      repeat_days: ['mon', 'fri'],
      enabled: true,
      snooze_minutes: null,
      actions: [],
    });
  });

  it.each([
    { time: '24:00' },
    { time: '6:45' },
    { ...base, unknown: 1 },
    { ...base, label: 'x'.repeat(101) },
    { ...base, repeat_days: ['mon', 'mon'] },
    { ...base, repeat_days: [1] },
    { ...base, snooze_minutes: 0 },
    { ...base, snooze_minutes: 31 },
    { ...base, owner_user_id: 'someone' },
    { ...base, actions: [{ script_entity_id: 'light.kitchen', timing: 'at' }] },
    { ...base, actions: [{ script_entity_id: 'script.a', timing: 'before' }] },
    { ...base, actions: [{ script_entity_id: 'script.a', timing: 'at', offset_minutes: 5 }] },
    { ...base, actions: [{ script_entity_id: 'script.a', timing: 'after', offset_minutes: 721 }] },
    { ...base, actions: [{ script_entity_id: 'script.a', timing: 'at', delay_minutes: 1 }] },
    {
      ...base,
      actions: [{ script_entity_id: 'script.a', timing: 'after_dismiss', delay_minutes: 121 }],
    },
    { ...base, actions: [{ script_entity_id: 'script.a', timing: 'at', extra: true }] },
    { ...base, actions: [{ script_entity_id: 'script.a', timing: 'at', id: 'nope' }] },
    {
      ...base,
      actions: [
        { script_entity_id: 'script.a', timing: 'before', offset_minutes: 10 },
        { script_entity_id: 'script.a', timing: 'before', offset_minutes: 10 },
      ],
    },
  ])('rejects %j', (input) => {
    expect(alarmCreateSchema.safeParse(input).success).toBe(false);
  });

  it('accepts the same script with different timings', () => {
    const parsed = alarmCreateSchema.parse({
      ...base,
      actions: [
        { script_entity_id: 'script.a', timing: 'before', offset_minutes: 10 },
        { script_entity_id: 'script.a', timing: 'before', offset_minutes: 20 },
        { script_entity_id: 'script.a', timing: 'after_dismiss' },
      ],
    });
    expect(parsed.actions[2]).toMatchObject({
      delay_minutes: 0,
      offset_minutes: null,
      enabled: true,
    });
  });

  it('requires a revision on update', () => {
    expect(alarmUpdateSchema.safeParse(base).success).toBe(false);
    expect(alarmUpdateSchema.safeParse({ ...base, revision: 3 }).success).toBe(true);
  });
});
