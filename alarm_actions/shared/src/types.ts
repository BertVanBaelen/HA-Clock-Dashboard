/** Global settings (add-on options, read-only through the API). */
export interface Settings {
  snooze_minutes: number;
  dismiss_timeout_minutes: number;
  grace_period_minutes: number;
  /** Label that marks scripts usable as actions (fixed for now; configurable is backlog). */
  action_label: string;
  history_days: number;
  history_max: number;
  max_script_starts_per_minute: number;
  min_script_interval_seconds: number;
  log_level: 'trace' | 'debug' | 'info' | 'warning' | 'error' | 'fatal';
  external_api_url: string | null;
}

export const DEFAULT_SETTINGS: Settings = {
  snooze_minutes: 9,
  dismiss_timeout_minutes: 60,
  grace_period_minutes: 5,
  action_label: 'alarm_action',
  history_days: 30,
  history_max: 200,
  max_script_starts_per_minute: 20,
  min_script_interval_seconds: 30,
  log_level: 'info',
  external_api_url: null,
};

/** Error body of every non-2xx API response. */
export interface ApiErrorBody {
  error: string;
  message: string;
  [extra: string]: unknown;
}

/** Weekdays are exchanged as names, never numbers (TR-API-08). */
export const WEEKDAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
export type Weekday = (typeof WEEKDAYS)[number];

export const TIMINGS = ['before', 'at', 'after', 'after_dismiss'] as const;
export type Timing = (typeof TIMINGS)[number];

/** An action attached to an alarm ([AlarmAction]). */
export interface AlarmAction {
  id: string;
  script_entity_id: string;
  timing: Timing;
  /** Required for `before` / `after`, null otherwise. */
  offset_minutes: number | null;
  /** Only for `after_dismiss` (default 0), null otherwise. */
  delay_minutes: number | null;
  enabled: boolean;
}

export type DisabledReason = 'owner_inactive' | 'fired' | null;

/** An alarm as returned by the API. */
export interface Alarm {
  id: string;
  owner_user_id: string;
  /** Display name of the owner (from HA users), null when unknown. */
  owner_name: string | null;
  label: string;
  time: string;
  repeat_days: Weekday[];
  enabled: boolean;
  skip_next: boolean;
  snooze_minutes: number | null;
  actions: AlarmAction[];
  revision: number;
  change_seq: number;
  created_at: string;
  updated_at: string;
  /** Next time the alarm rings (UTC ISO), null when disabled. Derived. */
  next_occurrence: string | null;
  /** Why the system disabled the alarm, when it did. */
  disabled_reason: DisabledReason;
  /** False for an admin looking at another user's alarm. */
  editable: boolean;
}

/** Response of a create/update: the alarm plus warnings (for example `before` runs missed). */
export interface AlarmSaveResponse {
  alarm: Alarm;
  warnings: AlarmWarning[];
}

export interface AlarmWarning {
  code: 'before_missed';
  alarm_action_id: string;
  message: string;
}

/** A script that can be used as an action ([ActionDefinition]). */
export interface ActionDefinition {
  entity_id: string;
  name: string;
  icon: string | null;
  description: string | null;
}

export interface ActionsResponse {
  actions: ActionDefinition[];
}

export interface Info {
  name: string;
  version: string;
  api_version: number;
  min_client_api_version: number;
  /** HA time zone, null until Core was reached. */
  time_zone: string | null;
  ha_connected: boolean;
  app_available: boolean;
}

export interface Me {
  id: string;
  name: string;
  is_admin: boolean;
  /** Phase 6 (away detection); always false until then. */
  away: boolean;
}

export const EXECUTION_RESULTS = [
  'running',
  'success',
  'failed',
  'skipped',
  'missed',
  'unavailable',
  'expired',
] as const;
export type ExecutionResult = (typeof EXECUTION_RESULTS)[number];

export interface RunNowResponse {
  result: ExecutionResult;
  reason: string | null;
}

/** Caps (SEC-05). */
export const LIMITS = {
  alarmsPerUser: 50,
  actionsPerAlarm: 20,
  actionsTotal: 1000,
  labelLength: 100,
} as const;
