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
  /**
   * The occurrence that can be dismissed right now (pending within 60 min, or ringing until the
   * dismissal timeout), or an early-dismissed occurrence that has not come yet (`dismissed`: the
   * phone must not ring for it). Null otherwise. Derived.
   */
  current_occurrence: CurrentOccurrence | null;
}

export const OCCURRENCE_STATUSES = [
  'pending',
  'skipped',
  'fired',
  'snoozed',
  'dismissed',
  'expired',
] as const;
export type OccurrenceStatus = (typeof OCCURRENCE_STATUSES)[number];

export interface CurrentOccurrence {
  id: string;
  scheduled_at: string;
  status: 'pending' | 'fired' | 'snoozed' | 'dismissed';
}

export const OCCURRENCE_EVENT_TYPES = ['dismissed', 'snoozed', 'timed_out'] as const;
export type OccurrenceEventType = (typeof OCCURRENCE_EVENT_TYPES)[number];

/** Answer to a dismissed / snoozed / timed_out report (FR-EVT-01, 02). */
export interface OccurrenceEventResponse {
  occurrence_id: string;
  type: OccurrenceEventType;
  /** Occurrence status after the report. */
  status: OccurrenceStatus;
  /** True when this report was already received; nothing changed. */
  duplicate: boolean;
}

/** `GET /sync?since=N` (FR-APP-03, TR-APP-07). */
export interface SyncResponse {
  /** Store-wide cursor; send it as `since` next time. */
  change_seq: number;
  /** True: `alarms` is the complete list and replaces the local copy. */
  full: boolean;
  alarms: Alarm[];
  /** Ids of own alarms deleted since `since` (empty when `full`). */
  deleted: string[];
  time_zone: string | null;
  snooze_minutes: number;
  dismiss_timeout_minutes: number;
  away: boolean;
}

/** A registered phone ([Client]). */
export interface Client {
  id: string;
  name: string;
  app_version: string;
  time_zone: string;
  last_sync_seq: number;
  last_seen_at: string;
  /** This phone's time zone differs from the HA time zone. */
  away: boolean;
}

export interface ClientsResponse {
  clients: Client[];
  /** The owner is away: their most recently seen phone is in another time zone (FR-EXE-12). */
  away: boolean;
}

/**
 * An upcoming action of another user's alarm (`GET /planned`): only who, what and when, so
 * everyone knows what will happen in the house. Other users' alarms themselves stay private.
 */
export interface PlannedAction {
  owner_name: string | null;
  script_entity_id: string;
  /** Script name, null when the script is gone or lost its label. */
  script_name: string | null;
  icon_path: string | null;
  timing: Timing;
  /** When the action runs (UTC ISO); null for `after_dismiss`, which follows the dismissal. */
  run_at: string | null;
  /** When the alarm rings (UTC ISO). */
  alarm_at: string;
  delay_minutes: number | null;
}

export interface PlannedResponse {
  planned: PlannedAction[];
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
  /** SVG path (24×24) of the icon, resolved by the server; null when unknown. */
  icon_path: string | null;
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

/** The APK offered by the app add-on (TR-APP-03). */
export interface AppPackage {
  available: boolean;
  version: string | null;
  /** Android versionCode, for update checks in the app (A5). */
  version_code: number | null;
  /** SHA-256 of the APK, lowercase hex. */
  sha256: string | null;
  size: number | null;
}

/** `GET /app/info`: the APK plus the URLs the app is set up with (TR-API-14). */
export interface AppInfo extends AppPackage {
  /** HA external URL (login), null when not set in HA. */
  ha_url: string | null;
  /** The `external_api_url` option, null when the external API is off. */
  api_url: string | null;
}

export interface Me {
  id: string;
  name: string;
  /** The user's most recently seen phone is in another time zone (FR-EXE-12). */
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
  /** Phones per user; registering another replaces the longest unseen one. */
  clientsPerUser: 10,
} as const;
