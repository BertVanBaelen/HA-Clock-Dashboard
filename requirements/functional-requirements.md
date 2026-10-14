# Functional Requirements

Priority: **M** = must (v1), **S** = should (v1 if time permits), **C** = could (later).
IDs are stable; reference them in issues and PRs.

## 1. Alarm management
| ID | Requirement | Prio |
|---|---|---|
| FR-ALM-01 | A user can create an alarm with a time (HH:MM) and optional label | M |
| FR-ALM-02 | An alarm can repeat on selected weekdays, or be one-time | M |
| FR-ALM-03 | An alarm can be enabled/disabled without deleting it | M |
| FR-ALM-04 | A user can edit and delete alarms | M |
| FR-ALM-05 | A user can skip only the next occurrence of a repeating alarm (not available for one-time alarms) | M |
| FR-ALM-06 | A one-time alarm disables itself after it has fired; actions of the occurrence that fired still complete | M |
| FR-ALM-07 | The panel shows the next trigger time of each alarm | M |
| FR-ALM-08 | Alarms can be duplicated | C |
| FR-ALM-09 | An alarm can have its own snooze duration; otherwise the default from settings applies ([Alarm](../analysis/objects/alarm.md)) | S |

## 2. Actions
| ID | Requirement | Prio |
|---|---|---|
| FR-ACT-01 | The system lists all HA scripts labeled `alarm_action` as available actions | M |
| FR-ACT-02 | A user can attach zero or more actions to an alarm | M |
| FR-ACT-03 | Each attached action has a timing: **before**, **at**, **after**, or **after dismissal** | M |
| FR-ACT-04 | For *before* / *after*, the user sets an offset in minutes (1-720) | M |
| FR-ACT-05 | The same script can be attached more than once with different timings | S |
| FR-ACT-06 | An individual attached action can be enabled/disabled | S |
| FR-ACT-07 | If an attached script is deleted or loses its label, the action is shown as "unavailable" and is not executed | M |
| FR-ACT-08 | Actions can be restricted to certain weekdays independent of the alarm | C |
| FR-ACT-09 | Conditions such as "only if home" | C |

## 3. Execution
| ID | Requirement | Prio |
|---|---|---|
| FR-EXE-01 | Actions run at the correct time without any phone or app being involved | M |
| FR-EXE-02 | *After dismissal* actions run only when a dismissal is reported (plus configurable delay, default 0) | M |
| FR-EXE-03 | If no dismissal arrives within a timeout (default 60 min after alarm time), or the phone reports that ringing timed out, *after dismissal* actions are skipped and logged as expired | M |
| FR-EXE-04 | Skipping an occurrence skips all its actions (before, at, after, after dismissal) | M |
| FR-EXE-05 | After a HA restart, actions still due are executed if within a grace period (default 5 min); older ones are marked missed | M |
| FR-EXE-06 | Every execution result (success, failed, skipped, missed) is recorded in a history | M |
| FR-EXE-07 | Snoozing does not shift *before* / *at* / *after* actions; it is only logged | M |
| FR-EXE-08 | A user can trigger one of their own attached actions manually from the panel ("run now") for testing | S |
| FR-EXE-09 | If saving or editing an alarm leaves a *before* action in the past for the next occurrence, that action is skipped for that occurrence and recorded as `missed` ([AlarmAction](../analysis/objects/alarm-action.md)) | M |
| FR-EXE-10 | Execution is rate limited and duplicate starts are suppressed to protect devices. Starts beyond the limit are dropped and logged as skipped ([Settings](../analysis/objects/settings.md)) | M |
| FR-EXE-11 | Dismissing an alarm up to 60 min before it rings cancels its *before* / *at* / *after* actions not yet run; *after dismissal* actions run from the dismissal time ([OccurrenceEvent](../analysis/objects/occurrence-event.md)) | M |
| FR-EXE-12 | When the owner's phone is in a different time zone than HA, no actions run for that owner's alarms; they are logged as skipped ("user away") and the panel shows the away state ([Client](../analysis/objects/client.md)) | M |
| FR-EXE-13 | A dismissal reported late by an offline phone does not start an *after dismissal* action whose time is already past by more than the grace period; it is logged as missed | M |
| FR-EXE-14 | Editing an alarm while it is ringing or snoozed does not cancel the running occurrence's remaining actions, except actions that were removed or disabled | M |

## 4. Dismiss and snooze events
| ID | Requirement | Prio |
|---|---|---|
| FR-EVT-01 | A client (app) can report *snoozed*, *dismissed* and *timed out* (stopped ringing without dismissal) for a specific occurrence | M |
| FR-EVT-02 | Duplicate reports for the same occurrence are ignored (idempotent) | M |
| FR-EVT-03 | The panel allows manually marking an alarm as dismissed | S |
| FR-EVT-04 | Dismiss/snooze events are fired on the HA event bus for use in other automations | S |

## 5. Dashboard panel
| ID | Requirement | Prio |
|---|---|---|
| FR-UI-01 | Installing the dashboard add-on adds a sidebar panel "Alarms", visible to all users (also non-admins), listing the user's alarms with toggle, time, days, next trigger | M |
| FR-UI-02 | Create/edit dialog for alarm fields and action list | M |
| FR-UI-03 | Action picker shows script name and icon | M |
| FR-UI-04 | History view shows recent executions per alarm | S |
| FR-UI-05 | Works on desktop and mobile (HA Companion App on iOS and Android) | M |
| FR-UI-06 | Follows the HA theme (light/dark) | S |
| FR-UI-07 | An "Install app" tab lets the user download the Android app. It appears only when the app add-on is installed and running (section 8) | S |
| FR-UI-08 | Translations: English at minimum | M |
| FR-UI-09 | When an edit causes a *before* action to be skipped for the next occurrence, the UI shows a warning before saving (FR-EXE-09) | M |
| FR-UI-10 | The panel shows the user's connected phones with their last sync time, and an "away, actions paused" banner when FR-EXE-12 applies | S |

## 6. Settings
Settings are options of the dashboard add-on (Settings → Add-ons → Alarm Actions → Configuration), so only admins can change them ([Settings](../analysis/objects/settings.md)). The panel shows them read-only.

| ID | Requirement | Prio |
|---|---|---|
| FR-SET-01 | Configurable default snooze duration (default 9 min) | S |
| FR-SET-02 | Configurable dismissal timeout and restart grace period | S |
| FR-SET-03 | Configurable label name for action scripts (default `alarm_action`) | C |
| FR-SET-04 | Configurable rate limit and log level | C |
| FR-SET-05 | Optional external API address (`external_api_url`, e.g. `https://api.vanbaelen.dev/clock`). Empty (default) = the API is only reachable through ingress and the app cannot be used | M |

There are no Home Assistant entities ([Dashboard add-on](../analysis/components/dashboard-addon.md)). The phone's next alarm appears in the HA Companion App's own *Next alarm* sensor.

## 7. Access and ownership
| ID | Requirement | Prio |
|---|---|---|
| FR-ACC-01 | Every alarm has an owner (the HA user who created it) | M |
| FR-ACC-02 | Users see and edit only their own alarms and history; admins can **view** all alarms and history but not change other users' alarms ([Alarm](../analysis/objects/alarm.md)) | M |
| FR-ACC-03 | Shared household alarms visible to all users | C |
| FR-ACC-04 | Alarms of a deleted or deactivated HA user stop running and are disabled; they stay disabled if the user is reactivated ([Alarm](../analysis/objects/alarm.md)) | M |
| FR-ACC-05 | Requests for alarms the user may not access behave as "not found" | M |

## 8. App distribution and sync (server side)
The Android app is offered by a second, optional add-on in the same repository, "Alarm Actions App" ([App add-on](../analysis/components/app-addon.md)). The app itself is specified in [app-requirements.md](app-requirements.md).

| ID | Requirement | Prio |
|---|---|---|
| FR-APP-01 | A logged-in user can download the Android APK from the panel's "Install app" tab | S |
| FR-APP-02 | The tab and download are only available while the app add-on is installed and running; without it the dashboard works fully | S |
| FR-APP-03 | The app can fetch alarms changed since its last sync, including deletions | M |
| FR-APP-04 | The app registers itself and reports its time zone on every sync, so the dashboard can show connected devices, last sync time and away state | M |
| FR-APP-05 | The app can ask which app version is bundled, to offer updates | S |
| FR-APP-06 | The dashboard tells the app when it is too old or too new for the API, so the app shows a clear message | M |
| FR-APP-07 | When the external API is set, the app can manage alarms from outside the home (e.g. through a Cloudflare tunnel) using the user's HA login. The Install app tab shows a setup QR code with the HA URL and the API URL | M |

## 9. Security
See [security-requirements.md](security-requirements.md) (IDs `SEC-*`). Visible behaviour: users only see and change their own alarms (FR-ACC), and every endpoint, including the external API for the app, requires a Home Assistant login.

## 10. Non-goals (v1)
Creating HA automations, HA entities for alarms, HA Container or Core installations (no Supervisor, so no add-ons), iOS app, conditions on actions, push-based instant sync, multiple time zones per alarm, Play Store release.

## Decisions
Decisions are recorded in the file of the object or component they concern: alarms are per user ([Alarm](../analysis/objects/alarm.md)), dismissal timeout 60 minutes ([Occurrence](../analysis/objects/occurrence.md)), late *before* actions are skipped with a warning ([AlarmAction](../analysis/objects/alarm-action.md)), admins view-only ([Alarm](../analysis/objects/alarm.md)), away by time zone ([Client](../analysis/objects/client.md)), rate limit drops ([Settings](../analysis/objects/settings.md)), early dismissal ([OccurrenceEvent](../analysis/objects/occurrence-event.md)), two add-ons ([Dashboard add-on](../analysis/components/dashboard-addon.md)), settings in add-on configuration ([Settings](../analysis/objects/settings.md)), no entities ([Dashboard add-on](../analysis/components/dashboard-addon.md)), app add-on ([App add-on](../analysis/components/app-addon.md)).
