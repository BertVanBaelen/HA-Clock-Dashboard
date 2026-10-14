import { WEEKDAYS, type Timing, type Weekday } from '@alarm-actions/shared';
import { locale } from './theme';

/**
 * UI strings in the language of the HA user profile (TR-UI-05): Dutch or English, English for
 * every other language. Dates, times and weekday names use Intl with the same language.
 */
const en = {
  title: 'Alarms',
  planned: 'Planned by others',
  plannedHint: 'Actions from the alarms of other people in the house.',
  someone: 'Someone',
  afterDismiss: (alarm: string, delay: number) =>
    delay > 0
      ? `${delay} min after the ${alarm} alarm is dismissed`
      : `when the ${alarm} alarm is dismissed`,
  tabAlarms: 'Alarms',
  tabApp: 'Install app',
  newAlarm: 'New alarm',
  editAlarm: 'Edit alarm',
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
  deleteHint: 'The alarm and its actions are removed. This cannot be undone.',
  alarmCreated: (time: string) => `Alarm set for ${time}`,
  alarmSaved: 'Alarm saved',
  alarmDeleted: 'Alarm deleted',
  alarmOn: (time: string) => `Alarm ${time} on`,
  alarmOff: (time: string) => `Alarm ${time} off`,
  skipUndone: 'Next alarm will ring again',
  edit: 'Edit',
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
  label: 'Label',
  labelPlaceholder: 'Wake up',
  time: 'Time',
  repeat: 'Repeat',
  repeatHint: 'Select no days for a one-time alarm.',
  enabled: 'Enabled',
  actions: 'Actions',
  actionCount: (n: number) => (n === 1 ? '1 action' : `${n} actions`),
  noActions: 'No actions. Add one to run a script before, at or after the alarm.',
  addAction: 'Add action',
  noScripts: (label: string) =>
    `No scripts available. Give a script the label "${label}" in Home Assistant to use it here.`,
  loadingScripts: 'Loading scripts…',
  script: 'Script',
  pickScript: 'Choose a script…',
  timing: 'When',
  timings: {
    before: 'Before alarm',
    at: 'At alarm',
    after: 'After alarm',
    after_dismiss: 'After dismiss',
  } as Record<Timing, string>,
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
  invalidTime: 'Enter a valid time.',
  chooseScript: 'Choose a script.',
  minutesRange: 'Minutes must be between 1 and 720.',
  delayRange: 'Delay must be between 0 and 120 minutes.',
  duplicateAction: 'Same as another action.',
  networkError: 'The add-on cannot be reached. Check your connection.',
  requestFailed: (status: number) => `Request failed (${status}).`,
  genericError: 'Something went wrong.',
  appTitle: 'Android app',
  appIntro:
    'Install the companion app on your phone so alarms ring even without a network connection.',
  appUnavailable: 'The app is not available right now.',
  appVersion: (v: string) => `Available version: ${v}`,
  appStepDownload: 'Download the app on this phone.',
  appDownload: 'Download app',
  appChecksum: 'Checksum to verify the download',
  appStepInstall:
    'Open the downloaded file and install it. Android asks once to allow installing apps from your browser or the Home Assistant app.',
  appStepSetup: 'Open the app and set it up with this link, then log in with your Home Assistant account.',
  appOpenSetup: 'Set up the app',
  appManual: 'Or enter these addresses in the app yourself:',
  appHaUrl: 'Home Assistant',
  appApiUrl: 'Alarm API',
  appNotConfigured:
    'The app cannot be set up yet: an administrator has to set the external URL of Home Assistant (https) and "external_api_url" in the add-on configuration.',
  dismiss: 'Dismiss',
  dismissEarly: 'Dismiss early',
  dismissedToast: 'Alarm dismissed',
  dismissedAt: (when: string) => `Dismissed: ${when}`,
  awayBanner:
    'You are away: your phone is in another time zone, so the actions of your alarms are skipped. The alarms on your phone still ring.',
  phones: 'Your phones',
  phonesHint: 'Phones with the app. Removing one here does not log it out.',
  lastSync: (when: string) => `Last sync ${when}`,
  removePhone: 'Remove phone',
  awayBadge: (tz: string) => `Away (${tz})`,
  version: (v: string) => `Alarm Actions ${v}`,
};

export type Strings = typeof en;

const nl: Strings = {
  title: 'Wekkers',
  planned: 'Gepland door anderen',
  plannedHint: 'Acties van de wekkers van andere mensen in huis.',
  someone: 'Iemand',
  afterDismiss: (alarm: string, delay: number) =>
    delay > 0
      ? `${delay} min na het uitzetten van de wekker van ${alarm}`
      : `bij het uitzetten van de wekker van ${alarm}`,
  tabAlarms: 'Wekkers',
  tabApp: 'App installeren',
  newAlarm: 'Nieuwe wekker',
  editAlarm: 'Wekker bewerken',
  noAlarms: 'Nog geen wekkers. Maak er een aan om te beginnen.',
  loading: 'Laden…',
  retry: 'Opnieuw proberen',
  reload: 'Herladen',
  save: 'Opslaan',
  saving: 'Opslaan…',
  cancel: 'Annuleren',
  close: 'Sluiten',
  delete: 'Verwijderen',
  confirmDelete: 'Deze wekker verwijderen?',
  yesDelete: 'Ja, verwijderen',
  deleteHint: 'De wekker en zijn acties worden verwijderd. Dit kan niet ongedaan gemaakt worden.',
  alarmCreated: (time: string) => `Wekker gezet om ${time}`,
  alarmSaved: 'Wekker opgeslagen',
  alarmDeleted: 'Wekker verwijderd',
  alarmOn: (time: string) => `Wekker ${time} aan`,
  alarmOff: (time: string) => `Wekker ${time} uit`,
  skipUndone: 'Volgende wekker gaat weer af',
  edit: 'Bewerken',
  duplicate: 'Dupliceren',
  skipNext: 'Volgende overslaan',
  undoSkip: 'Overslaan ongedaan maken',
  skipped: 'Volgende wekker overgeslagen',
  once: 'Eenmalig',
  everyDay: 'Elke dag',
  weekdays: 'Weekdagen',
  weekend: 'Weekend',
  off: 'Uit',
  next: 'Volgende',
  label: 'Naam',
  labelPlaceholder: 'Opstaan',
  time: 'Tijd',
  repeat: 'Herhalen',
  repeatHint: 'Kies geen dagen voor een eenmalige wekker.',
  enabled: 'Aan',
  actions: 'Acties',
  actionCount: (n: number) => (n === 1 ? '1 actie' : `${n} acties`),
  noActions: 'Geen acties. Voeg er een toe om een script voor, bij of na de wekker te starten.',
  addAction: 'Actie toevoegen',
  noScripts: (label: string) =>
    `Geen scripts beschikbaar. Geef een script het label "${label}" in Home Assistant om het hier te gebruiken.`,
  loadingScripts: 'Scripts laden…',
  script: 'Script',
  pickScript: 'Kies een script…',
  timing: 'Wanneer',
  timings: {
    before: 'Voor de wekker',
    at: 'Bij de wekker',
    after: 'Na de wekker',
    after_dismiss: 'Na uitzetten',
  },
  minutes: 'Minuten',
  delay: 'Vertraging (minuten)',
  runNow: 'Nu uitvoeren',
  remove: 'Verwijderen',
  unavailable: 'Niet beschikbaar',
  unavailableHint:
    'Dit script bestaat niet meer of heeft het label niet meer. De actie wordt overgeslagen tot je ze aanpast of verwijdert.',
  actionLimit: (n: number) => `Maximaal ${n} acties per wekker.`,
  conflict: 'Deze wekker is ergens anders gewijzigd. Herlaad om de laatste versie te zien.',
  forbidden: 'Je mag deze wekker niet wijzigen.',
  notFound: 'Deze wekker bestaat niet meer.',
  haOffline:
    'Home Assistant is niet bereikbaar. Wekkers blijven bewaard en lopen zodra het terug is.',
  beforeMissed: (minutes: number, script: string) =>
    `"${script}" (${minutes} min vooraf) is voor de volgende wekker al voorbij en loopt deze keer niet.`,
  savedWithWarnings: 'Opgeslagen met waarschuwingen',
  runResult: (r: string) => `Resultaat: ${r}`,
  invalidTime: 'Geef een geldige tijd in.',
  chooseScript: 'Kies een script.',
  minutesRange: 'Minuten moeten tussen 1 en 720 liggen.',
  delayRange: 'Vertraging moet tussen 0 en 120 minuten liggen.',
  duplicateAction: 'Gelijk aan een andere actie.',
  networkError: 'De add-on is niet bereikbaar. Controleer je verbinding.',
  requestFailed: (status: number) => `Aanvraag mislukt (${status}).`,
  genericError: 'Er ging iets mis.',
  appTitle: 'Android-app',
  appIntro: 'Installeer de app op je telefoon zodat wekkers ook zonder netwerkverbinding afgaan.',
  appUnavailable: 'De app is nu niet beschikbaar.',
  appVersion: (v: string) => `Beschikbare versie: ${v}`,
  appStepDownload: 'Download de app op deze telefoon.',
  appDownload: 'App downloaden',
  appChecksum: 'Controlegetal om de download na te kijken',
  appStepInstall:
    'Open het gedownloade bestand en installeer het. Android vraagt één keer om apps installeren toe te staan vanuit je browser of de Home Assistant-app.',
  appStepSetup: 'Open de app en stel ze in met deze link, en log daarna in met je Home Assistant-account.',
  appOpenSetup: 'App instellen',
  appManual: 'Of geef deze adressen zelf in de app in:',
  appHaUrl: 'Home Assistant',
  appApiUrl: 'Wekker-API',
  appNotConfigured:
    'De app kan nog niet ingesteld worden: een beheerder moet de externe URL van Home Assistant (https) en "external_api_url" in de add-on-configuratie instellen.',
  dismiss: 'Uitzetten',
  dismissEarly: 'Vroeg uitzetten',
  dismissedToast: 'Wekker uitgezet',
  dismissedAt: (when: string) => `Uitgezet: ${when}`,
  awayBanner:
    'Je bent weg: je telefoon staat in een andere tijdzone, dus de acties van je wekkers worden overgeslagen. De wekkers op je telefoon gaan wel af.',
  phones: 'Je telefoons',
  phonesHint: 'Telefoons met de app. Hier verwijderen meldt de telefoon niet af.',
  lastSync: (when: string) => `Laatst gesynchroniseerd ${when}`,
  removePhone: 'Telefoon verwijderen',
  awayBadge: (tz: string) => `Weg (${tz})`,
  version: (v: string) => `Alarm Actions ${v}`,
};

const LANGUAGES: Record<string, Strings> = { en, nl };

/** Current strings; filled by setLanguage before the first render. */
export const t: Strings = { ...en };

/** Picks the strings for an HA language code such as "nl", "nl-BE" or "en-GB". */
export function setLanguage(language: string): void {
  Object.assign(t, LANGUAGES[language.toLowerCase().split('-')[0]!] ?? en);
}
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
  return new Intl.DateTimeFormat(locale(), {
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(2024, 0, 1, h, m)));
}

/** A past or future moment with its date, for example "14 Oct, 12:30". */
export function formatDateTime(iso: string, timeZone: string | null): string {
  const date = new Date(iso);
  try {
    return new Intl.DateTimeFormat(locale(), {
      day: 'numeric',
      month: 'short',
      hour: 'numeric',
      minute: '2-digit',
      ...(timeZone ? { timeZone } : {}),
    }).format(date);
  } catch {
    return date.toLocaleString();
  }
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
