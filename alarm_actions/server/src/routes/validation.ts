import type { z } from 'zod';
import { describeZodError } from '@alarm-actions/shared';
import { badRequest } from '../errors.js';

/** Parses a request body with a strict schema; failures are 400 `invalid_input` (SEC-05). */
export function parseBody<S extends z.ZodTypeAny>(schema: S, body: unknown): z.output<S> {
  const parsed = schema.safeParse(body);
  if (!parsed.success) throw badRequest(describeZodError(parsed.error));
  return parsed.data as z.output<S>;
}

/** Path parameter ids are UUIDs; anything else cannot exist. */
export const UUID_PARAM = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
