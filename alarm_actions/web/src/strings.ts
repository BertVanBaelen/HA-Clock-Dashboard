import { WEEKDAYS, type Timing, type Weekday } from '@alarm-actions/shared';
import { locale } from './theme';

/**
 * UI strings. English only for now; dates, times and weekday names follow the HA language through
 * Intl. Keeping strings here makes adding translations a data change.
 */
export const t = {
  title: 'Alarms',
  tabAlarms: 'Alarms',
  tabSettings: 'Settings',
  tabApp: 'Install app',
  newAlarm: 'New alarm',
  editAlarm: 'Edit alarm',
  viewAlarm: 'Alarm',
  noAlarms: 'No alarms yet. Create one to get started.',
  loading: 'Loading…',
  retry: 'Retry',
  reload: 'Reload',
  save: 'Save',
  saving: 'Saving…',
  cancel: 'Cancel',
  close: 'Close',
  delete: 'Delete',
  confirmDelete: 'Delete this alarm?',
  yesDelete: 'Yes, delete',
  edit: 'Edit',
  view: 'View',
  duplicate: 'Duplicate',
  skipNext: 'Skip next',
  undoSkip: 'Undo skip',
  skipped: 'Next alarm skipped',
  once: 'Once',
  everyDay: 'Every day',
  weekdays: 'Weekdays',
  weekend: 'Weekend',
  off: 'Off',
  next: 'Next',
  owner: 'Owner',
  readOnly: "This alarm belongs to another user. You can view it, but only the owner can change it.",
  label: 'Label',
  labelPlaceholder: 'Wake up',
  time: 'Time',
  repeat: 'Repeat',
  repeatHint: 'Select no days for a one-time alarm.',
  enabled: 'Enabled',
  snooze: 'Snooze (minutes)',
  snoozeHint: (d: number) => `Leave empty to use the default (${d} min).`,
  actions: 'Actions',
  noActions: 'No actions. Add one to run a script before, at or after the alarm.',
  addAction: 'Add action',
  noScripts: (label: string) =>
    `No scripts available. Give a script the label "${label}" in Home Assistant to use it here.`,
  script: 'Script',
  pickScript: 'Choose a script…',
  timing: 'When',
  minutes: 'Minutes',
  delay: 'Delay (minutes)',
  runNow: 'Run now',
  remove: 'Remove',
  unavailable: 'Unavailable',
  unavailableHint:
    'This script no longer exists or lost its label. The action is skipped until you fix or remove it.',
  actionLimit: (n: number) => `At most ${n} actions per alarm.`,
  conflict: 'This alarm was changed somewhere else. Reload it to see the latest version.',
  forbidden: 'You are not allowed to change this alarm.',
  notFound: 'This alarm no longer exists.',
  haOffline: 'Home Assistant is not reachable. Alarms are kept and run when it is back.',
  beforeMissed: (minutes: number, script: string) =>
    `"${script}" (${minutes} min before) is already past for the next alarm and will not run this time.`,
  savedWithWarnings: 'Saved with warnings',
  runResult: (r: string) => `Run result: ${r}`,
  settingsTitle: 'Settings',
  settingsAdminHint:
    'Change these values in Settings → Add-ons → Alarm Actions → Configuration.',
  settingsUserHint: 'Only an administrator can change these values.',
  appTitle: 'Android app',
  appIntro:
    'Install the companion app on your phone so alarms ring even without a network connection.',
  appUnavailable: 'The app is not available right now.',
  appVersion: (v: string) => `Available version: ${v}`,
  appComing: 'Download and setup instructions follow in a later version.',
  timeZone: 'Time zone',
  version: 'Version',
} as const;

export const TIMING_LABEL: Record<Timing, string> = {
  before: 'Before alarm',
  at: 'At alarm',
  after: 'After alarm',
  after_dismiss: 'After dismiss',
};

export const SETTING_LABEL: Record<string, string> = {
  snooze_minutes: 'Default snooze (minutes)',
  dismiss_timeout_minutes: 'Dismiss timeout (minutes)',
  grace_period_minutes: 'Grace period (minutes)',
  action_label: 'Script label',
  history_days: 'History days',
  history_max: 'History entries per alarm',
  max_script_starts_per_minute: 'Max script starts per minute',
  min_script_interval_seconds: 'Min seconds between runs of a script',
  log_level: 'Log level',
  external_api_url: 'External API URL',
};

// 2024-01-01 is a Monday; used to get localized weekday names.
const MONDAY = Date.UTC(2024, 0, 1, 12);

export function dayName(day: Weekday, style: 'short' | 'narrow' | 'long' = 'short'): string {
  const date = new Date(MONDAY + WEEKDAYS.indexOf(day) * 86_400_000);
  return new Intl.DateTimeFormat(locale(), { weekday: style, timeZone: 'UTC' }).format(date);
}

export function formatDays(days: readonly Weekday[]): string {
  if (days.length === 0) return t.once;
  if (days.length === 7) return t.everyDay;
  const key = days.join(',');
  if (key === 'mon,tue,wed,thu,fri') return t.weekdays;
  if (key === 'sat,sun') return t.weekend;
  return days.map((d) => dayName(d)).join(', ');
}

/** "HH:MM" in the user's clock style (12 or 24 h). */
export function formatTime(time: string): string {
  const [h, m] = time.split(':').map(Number) as [number, number];
  return new Intl.DateTimeFormat(locale(), { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }).format(
    new Date(Date.UTC(2024, 0, 1, h, m)),
  );
}

/** Next trigger in HA's time zone, for example "Tue 07:00". */
export function formatNext(iso: string, timeZone: string | null): string {
  const date = new Date(iso);
  const opts: Intl.DateTimeFormatOptions = {
    weekday: 'short',
    hour: 'numeric',
    minute: '2-digit',
    ...(timeZone ? { timeZone } : {}),
  };
  if (date.getTime() - Date.now() > 6 * 86_400_000) {
    opts.day = 'numeric';
    opts.month = 'short';
  }
  try {
    return new Intl.DateTimeFormat(locale(), opts).format(date);
  } catch {
    return date.toLocaleString();
  }
}
