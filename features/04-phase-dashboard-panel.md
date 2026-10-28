# Phase 4: Dashboard Panel

**Goal:** A complete, mobile-friendly UI in the HA sidebar.

**Objects and components:** [Alarm](../analysis/objects/alarm.md), [AlarmAction](../analysis/objects/alarm-action.md), [ActionDefinition](../analysis/objects/action-definition.md), [Settings](../analysis/objects/settings.md), [Dashboard add-on](../analysis/components/dashboard-addon.md), [App add-on](../analysis/components/app-addon.md)

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 4.1 | Ingress panel, frontend build stage in the Dockerfile, static files served by the add-on with relative URLs | TR-UI-01, 03, 04, TR-API-10 | "Alarms" appears in the sidebar for admins and non-admins |
| 4.2 | Alarm list with toggle, time, days, next trigger | FR-UI-01, FR-ALM-07 | List renders live data |
| 4.3 | Alarm detail with timeline (time, label, days, one-time, actions; [design](../docs/design.md)); opening an alarm to edit switches it on, so saving activates it | FR-UI-02, FR-ALM-01, 02 | Alarm saved via API; an edited disabled alarm is saved enabled |
| 4.4 | Action editor: picker, timing, offset | FR-UI-02, 03, FR-ACT-02..04 | Actions saved with correct timing |
| 4.5 | Per-action enable/disable and duplicates | FR-ACT-05, 06 | Works in UI |
| 4.6 | Skip next / undo skip | FR-ALM-05 | Button with visual state |
| 4.7 | Unavailable-script warning | FR-ACT-07 | Warning shown for broken actions |
| 4.8 | Conflict handling (409) | TR-API-02 | User is prompted to refresh |
| 4.9 | Theming (design tokens; HA dark mode and language read from the parent frame, `prefers-color-scheme` fallback) and responsive layout | FR-UI-05, 06, TR-UI-05 | Verified in light/dark, desktop and Companion App |
| 4.10 | ~~Settings view~~ dropped: settings are only shown on the add-on Configuration page; the panel shows its version in the footer | FR-SET-01, 02, [Settings](../analysis/objects/settings.md) | Values on the Configuration tab |
| 4.11 | Alarm duplication | FR-ALM-08 | *Could have* |
| 4.12 | Warning in the editor when a *before* action will be skipped | FR-UI-09, FR-EXE-09 | Warning shown before save |
| 4.13 | User text rendered safely (no unsafe HTML) | SEC-07 | Reviewed |
| 4.14 | No external requests; panel bundle contains no data, only UI code | SEC-07, SEC-08 | Verified in network tab and bundle |
| 4.15 | "Install app" tab placeholder wired to `GET /app/info` (full feature in phase 6) | FR-UI-07, TR-UI-06 | Tab hidden when the app add-on is not running |
| 4.16 | ~~Per-alarm snooze duration field~~ moved to the Android app editor ([A4](app/A4-editor-reporting.md)): the panel never rings. The panel keeps an existing value when saving | FR-ALM-09 | Snooze unchanged after a panel edit |
| 4.17 | "Planned by others" list for every user: owner name, script and time of other users' upcoming actions (`GET /planned`); no admin access to other users' alarms | FR-ACC-02, [Alarm](../analysis/objects/alarm.md) | Another user's next actions appear with their time; their alarms cannot be opened |
| 4.18 | Panel text in the HA user's language (Dutch or English, English fallback) | FR-UI-08, TR-UI-05 | Dutch profile shows Dutch text |
| 4.19 | Fast first view: the list waits only for info, user and alarms; scripts load in the background and when an alarm is opened | FR-UI-01 | List shows without waiting for scripts or the app add-on |

**Exit criteria:** A non-technical household member can create an alarm with actions on a phone, without touching YAML.
