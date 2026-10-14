import { z } from 'zod';
import { LIMITS, TIMINGS, WEEKDAYS } from './types.js';

/** Strict request schemas (SEC-05, TR-API-03): unknown fields are rejected. */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;
export const SCRIPT_PATTERN = /^script\.[a-z0-9_]+$/;

export const alarmActionInputSchema = z
  .object({
    id: z.string().regex(UUID, 'must be a UUID').optional(),
    script_entity_id: z.string().max(255).regex(SCRIPT_PATTERN, 'must be a script entity id'),
    timing: z.enum(TIMINGS),
    offset_minutes: z.number().int().min(1).max(720).nullable().optional(),
    delay_minutes: z.number().int().min(0).max(120).nullable().optional(),
    enabled: z.boolean().default(true),
  })
  .strict()
  .superRefine((action, ctx) => {
    const needsOffset = action.timing === 'before' || action.timing === 'after';
    if (needsOffset && action.offset_minutes == null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['offset_minutes'],
        message: `is required for timing '${action.timing}'`,
      });
    }
    if (!needsOffset && action.offset_minutes != null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['offset_minutes'],
        message: `is not allowed for timing '${action.timing}'`,
      });
    }
    if (action.timing !== 'after_dismiss' && action.delay_minutes != null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['delay_minutes'],
        message: 'is only allowed for timing after_dismiss',
      });
    }
  })
  .transform((action) => ({
    id: action.id?.toLowerCase(),
    script_entity_id: action.script_entity_id,
    timing: action.timing,
    offset_minutes: action.offset_minutes ?? null,
    delay_minutes: action.timing === 'after_dismiss' ? (action.delay_minutes ?? 0) : null,
    enabled: action.enabled,
  }));

export type AlarmActionInput = z.output<typeof alarmActionInputSchema>;

const alarmFields = {
  label: z.string().trim().max(LIMITS.labelLength).default(''),
  time: z.string().regex(TIME_PATTERN, 'must be HH:MM (24 h)'),
  repeat_days: z.array(z.enum(WEEKDAYS)).max(7).default([]),
  enabled: z.boolean().default(true),
  snooze_minutes: z.number().int().min(1).max(30).nullable().default(null),
  // Hard upper bound against huge bodies; the real cap (LIMITS.actionsPerAlarm) answers 422.
  actions: z.array(alarmActionInputSchema).max(100).default([]),
};

function checkAlarm(
  alarm: { repeat_days: string[]; actions: AlarmActionInput[] },
  ctx: z.RefinementCtx,
): void {
  if (new Set(alarm.repeat_days).size !== alarm.repeat_days.length) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['repeat_days'], message: 'has duplicates' });
  }
  const keys = new Set<string>();
  const ids = new Set<string>();
  alarm.actions.forEach((a, i) => {
    const key = [a.script_entity_id, a.timing, a.offset_minutes, a.delay_minutes].join('|');
    if (keys.has(key)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['actions', i],
        message: 'duplicates another action (same script, timing, offset and delay)',
      });
    }
    keys.add(key);
    if (a.id) {
      if (ids.has(a.id)) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['actions', i, 'id'], message: 'is used twice' });
      }
      ids.add(a.id);
    }
  });
}

/** Days in calendar order, so stored values are canonical. */
function sortDays<T extends { repeat_days: (typeof WEEKDAYS)[number][] }>(alarm: T): T {
  return { ...alarm, repeat_days: WEEKDAYS.filter((d) => alarm.repeat_days.includes(d)) };
}

export const alarmCreateSchema = z.object(alarmFields).strict().superRefine(checkAlarm).transform(sortDays);
export type AlarmCreateInput = z.output<typeof alarmCreateSchema>;

export const alarmUpdateSchema = z
  .object({ ...alarmFields, revision: z.number().int().min(1) })
  .strict()
  .superRefine(checkAlarm)
  .transform(sortDays);
export type AlarmUpdateInput = z.output<typeof alarmUpdateSchema>;

/** Body of skip / unskip / run: nothing, or an empty object. */
export const emptyBodySchema = z.object({}).strict().optional();

/** Human readable summary of a zod error, used in 400 responses. */
export function describeZodError(error: z.ZodError): string {
  return error.issues
    .slice(0, 5)
    .map((issue) => (issue.path.length ? `${issue.path.join('.')}: ${issue.message}` : issue.message))
    .join('; ');
}
