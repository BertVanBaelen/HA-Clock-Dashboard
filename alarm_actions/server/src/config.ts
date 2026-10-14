import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import { DEFAULT_SETTINGS, type Settings } from '@alarm-actions/shared';

/** Supervisor ingress proxy address; the only accepted source on the ingress listener. */
export const INGRESS_PROXY_IP = '172.30.32.2';

export type LogLevel = 'trace' | 'debug' | 'info' | 'warn' | 'error' | 'fatal' | 'silent';

export interface AppConfig {
  dataDir: string;
  /** Ingress listener (`ingress_port` in config.yaml). */
  port: number;
  host: string;
  /** Source address allowed on the ingress listener. */
  trustedProxy: string;
  haWsUrl: string;
  /** Supervisor token; null when running outside Home Assistant (development). */
  supervisorToken: string | null;
  /** Built panel (web/dist); null serves the API only. */
  webDistDir: string | null;
  logLevel: LogLevel;
  settings: Settings;
}

const optionsSchema = z.object({
  snooze_minutes: z.number().int().min(1).max(30).default(DEFAULT_SETTINGS.snooze_minutes),
  dismiss_timeout_minutes: z
    .number()
    .int()
    .min(5)
    .max(240)
    .default(DEFAULT_SETTINGS.dismiss_timeout_minutes),
  grace_period_minutes: z
    .number()
    .int()
    .min(1)
    .max(60)
    .default(DEFAULT_SETTINGS.grace_period_minutes),
  history_days: z.number().int().min(1).max(365).default(DEFAULT_SETTINGS.history_days),
  history_max: z.number().int().min(10).max(2000).default(DEFAULT_SETTINGS.history_max),
  max_script_starts_per_minute: z
    .number()
    .int()
    .min(1)
    .max(200)
    .default(DEFAULT_SETTINGS.max_script_starts_per_minute),
  min_script_interval_seconds: z
    .number()
    .int()
    .min(0)
    .max(3600)
    .default(DEFAULT_SETTINGS.min_script_interval_seconds),
  log_level: z
    .enum(['trace', 'debug', 'info', 'warning', 'error', 'fatal'])
    .default(DEFAULT_SETTINGS.log_level),
  external_api_url: z
    .string()
    .trim()
    .nullable()
    .optional()
    .transform((v) => (v ? v : null)),
});

/** Reads the add-on options written by the Supervisor; unknown keys are ignored. */
export function readSettings(file: string): Settings {
  const raw = fs.existsSync(file) ? (JSON.parse(fs.readFileSync(file, 'utf8')) as unknown) : {};
  const parsed = optionsSchema.safeParse(raw ?? {});
  if (!parsed.success) {
    const issues = parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ');
    throw new Error(`Invalid add-on options: ${issues}`);
  }
  return { ...parsed.data, action_label: DEFAULT_SETTINGS.action_label };
}

/** Add-on log levels mapped to pino levels. */
export function toLogLevel(level: Settings['log_level']): LogLevel {
  return level === 'warning' ? 'warn' : level;
}

function defaultWebDistDir(): string | null {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const candidate = path.resolve(here, '../../web/dist');
  return fs.existsSync(path.join(candidate, 'index.html')) ? candidate : null;
}

/**
 * Configuration from the add-on options (`/data/options.json`) plus a few environment variables
 * that the Supervisor sets (`SUPERVISOR_TOKEN`) or that are used for local development.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const dataDir = path.resolve(env.DATA_DIR || '/data');
  fs.mkdirSync(dataDir, { recursive: true });
  const settings = readSettings(env.OPTIONS_FILE || path.join(dataDir, 'options.json'));
  return {
    dataDir,
    port: Number(env.PORT || 8099),
    host: env.HOST || '0.0.0.0',
    trustedProxy: env.TRUSTED_PROXY || INGRESS_PROXY_IP,
    haWsUrl: env.HA_WS_URL || 'ws://supervisor/core/websocket',
    supervisorToken: env.SUPERVISOR_TOKEN || null,
    webDistDir: env.WEB_DIST_DIR ? path.resolve(env.WEB_DIST_DIR) : defaultWebDistDir(),
    logLevel: (env.LOG_LEVEL as LogLevel | undefined) || toLogLevel(settings.log_level),
    settings,
  };
}
