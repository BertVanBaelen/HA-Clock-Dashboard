import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import { DEFAULT_SETTINGS, type Settings } from '@alarm-actions/shared';

/** Supervisor ingress proxy address; the only accepted source on the ingress listener. */
export const INGRESS_PROXY_IP = '172.30.32.2';
export const APP_ADDON_PORT = 8000;
/** External API listener (TR-API-11), only started when `external_api_url` is set. */
export const EXTERNAL_PORT = 8100;

export type LogLevel = 'trace' | 'debug' | 'info' | 'warn' | 'error' | 'fatal' | 'silent';

export interface AppConfig {
  dataDir: string;
  /** Ingress listener (`ingress_port` in config.yaml). */
  port: number;
  host: string;
  /** Source address allowed on the ingress listener. */
  trustedProxy: string;
  haWsUrl: string;
  /**
   * Core websocket reached directly (not through the Supervisor proxy, which only accepts the
   * Supervisor token); used to validate the app's bearer tokens (TR-API-12).
   */
  haCoreWsUrl: string;
  externalPort: number;
  /** Supervisor token; null when running outside Home Assistant (development). */
  supervisorToken: string | null;
  /** Built panel (web/dist); null serves the API only. */
  webDistDir: string | null;
  logLevel: LogLevel;
  settings: Settings;
  /** Base URL of the app add-on on the internal network (TR-APP-03); null disables the probe. */
  appUrl: string | null;
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

/**
 * Path prefix of the external API from `external_api_url` (TR-API-13): `https://x/clock` →
 * `/clock`, `https://x/` → ``. The API is served under `<prefix>/api/v1`. Throws for a URL that is
 * not http(s) or carries a query or fragment.
 */
export function externalBasePath(url: string): string {
  const parsed = new URL(url);
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Error('external_api_url must be an https URL');
  }
  if (parsed.search || parsed.hash) throw new Error('external_api_url must not have a query or fragment');
  return parsed.pathname.replace(/\/+$/, '');
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
    haCoreWsUrl: env.HA_CORE_WS_URL || 'ws://homeassistant:8123/api/websocket',
    externalPort: Number(env.EXTERNAL_PORT || EXTERNAL_PORT),
    supervisorToken: env.SUPERVISOR_TOKEN || null,
    webDistDir: env.WEB_DIST_DIR ? path.resolve(env.WEB_DIST_DIR) : defaultWebDistDir(),
    logLevel: (env.LOG_LEVEL as LogLevel | undefined) || toLogLevel(settings.log_level),
    settings,
    appUrl: env.APP_ADDON_URL || (env.SUPERVISOR_TOKEN ? defaultAppUrl() : null),
  };
}

/**
 * The app add-on's hostname follows from ours: `<repo-hash>-alarm-actions` →
 * `<repo-hash>-alarm-actions-app`, port 8000 (`alarm_actions_app/server.mjs`).
 */
function defaultAppUrl(): string {
  return `http://${os.hostname()}-app:${APP_ADDON_PORT}`;
}
