# Android app

**Purpose:** Optional native Android app that rings the alarms of its user and reports dismiss/snooze to the [dashboard add-on](dashboard-addon.md). It has the same alarm features as the panel, plus ringing and a night display. Distributed by the [app add-on](app-addon.md).

**Uses:** [Alarm](../objects/alarm.md), [AlarmAction](../objects/alarm-action.md), [Occurrence](../objects/occurrence.md), [OccurrenceEvent](../objects/occurrence-event.md), [Client](../objects/client.md), [Settings](../objects/settings.md) (default snooze).

## Features
- Setup by scanning the QR code on the Install app tab (HA URL and API URL).
- Login with the HA account (OAuth on the HA URL); API calls go to the external API URL with the HA access token, so the app works away from home (e.g. through a Cloudflare tunnel).
- Local copy of the user's alarms, synced with `GET /sync`; works offline.
- Exact alarms, full-screen ringing over the lock screen, ringing through Do Not Disturb; snooze and dismiss.
- Reports dismiss/snooze/timed-out to HA (queued when offline).
- Alarm editor with the same actions as the panel.
- **Night display** (bedside clock mode) from the local copy, with widgets (weather, morning plan, bedroom, calendar, battery) and a per-alarm **sunrise** that brightens the screen before the alarm. The HA widgets read Home Assistant's own REST API at the HA URL with the user's login; ringing never depends on them.
- **In-app updater** that installs new versions from the app add-on.

## Rules
- The phone is the source of truth for ringing; HA for actions and scheduling.
- The APK contains no secrets or server address; the user scans the setup QR code or enters the HA URL and API URL, and logs in. Use a non-admin HA user per phone.
- The app needs the dashboard's external API (`external_api_url`); without it, the Install app tab explains how to enable it.
- Updates install over the existing app (same signing key, higher `versionCode`), keeping data. The checksum is verified; no update within 30 minutes before an alarm.
- While the owner is away (other time zone, see [Client](../objects/client.md)), the phone rings at local time and sends no reports.

## Decisions
| Decision | Why |
|---|---|
| **Fully native** app with an **in-app updater**; a PWA was rejected | A PWA cannot ring at an exact time while closed, show a full-screen alarm over the lock screen, or ring through Do Not Disturb. Sideloaded updates never need an uninstall as long as the signing key is kept |
| The APK contains no secrets or server address | The same APK is safe to publish anywhere |
| **Night display** (bedside clock mode), priority Should | Wanted by the user; works offline from the local copy |
| Sound, vibration and sunrise stay local; snooze length comes from the server | Sounds are device files, and vibrating and the screen's sunrise are phone settings; snooze is part of the alarm |
| Night display widgets call HA directly, not through the add-on | No add-on change is needed and the user's own HA permissions apply; the night display is used at home, where the HA URL is reachable |
| API calls with the HA access token to the external API URL, not through ingress | Works through the user's Cloudflare tunnel with an own hostname, and avoids Supervisor ingress calls a non-admin user may not be allowed to make |

## Requirements and features
- Requirements: all of [app requirements](../../requirements/app-requirements.md); FR-APP-07, TR-APP-06, 08, TR-API-12, 14
- [App phases](../../features/app/): [A1](../../features/app/A1-foundation.md), [A2](../../features/app/A2-login-sync.md), [A3](../../features/app/A3-alarm-engine.md), [A4](../../features/app/A4-editor-reporting.md), [A5](../../features/app/A5-hardening-release.md)
- [Phase 6](../../features/06-phase-app-addon-sync.md): 6.6, 6.12
- [Phase 7](../../features/07-phase-hardening-release.md): 7.10, 7.10a