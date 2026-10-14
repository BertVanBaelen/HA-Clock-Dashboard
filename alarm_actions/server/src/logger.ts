import { pino } from 'pino';
import type { FastifyBaseLogger } from 'fastify';
import type { LogLevel } from './config.js';

/** One logger for the HTTP server and the background services (scheduler, HA client). */
export function createLogger(level: LogLevel): FastifyBaseLogger {
  return pino({
    level,
    // The add-on log is read in the HA UI; keep it compact and never log request headers.
    redact: ['req.headers', 'headers.authorization', 'token', 'access_token'],
  }) as unknown as FastifyBaseLogger;
}
