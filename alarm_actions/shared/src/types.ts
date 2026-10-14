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
