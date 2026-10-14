# Android app

**Purpose:** Optional native Android app that rings the alarms of its user and reports dismiss/snooze to the [dashboard add-on](dashboard-addon.md). It has the same alarm features as the panel, plus ringing and a night display. Distributed by the [app add-on](app-addon.md).

**Uses:** [Alarm](../objects/alarm.md), [AlarmAction](../objects/alarm-action.md), [Occurrence](../objects/occurrence.md), [OccurrenceEvent](../objects/occurrence-event.md), [Client](../objects/client.md), [Settings](../objects/settings.md) (default snooze).

## Features
- Login with the HA account (OAuth through HA), then an ingress session to the dashboard API.
- Local copy of the user's alarms, synced with `GET /sync`; works offline.
- Exact alarms, full-screen ringing over the lock screen, ringing through Do Not Disturb; snooze and dismiss.
- Reports dismiss/snooze/timed-out to HA (queued when offline).
- Alarm editor with the same actions as the panel.
- **Night display** (bedside clock mode) from the local copy.
- **In-app updater** that installs new versions from the app add-on.

## Rules
- The phone is the source of truth for ringing; HA for actions and scheduling.
- The APK contains no secrets or server address; the user enters the HA URL and logs in. Use a non-admin HA user per phone.
- Updates install over the existing app (same signing key, higher `versionCode`), keeping data. The checksum is verified; no update within 30 minutes before an alarm.
- While the owner is away (other time zone, see [Client](../objects/client.md)), the phone rings at local time and sends no reports.

## Decisions
| Decision | Why |
|---|---|
| **Fully native** app with an **in-app updater**; a PWA was rejected | A PWA cannot ring at an exact time while closed, show a full-screen alarm over the lock screen, or ring through Do Not Disturb. Sideloaded updates never need an uninstall as long as the signing key is kept |
| The APK contains no secrets or server address | The same APK is safe to publish anywhere |
| **Night display** (bedside clock mode), priority Should | Wanted by the user; works offline from the local copy |
| Sound stays local; snooze length comes from the server | Sounds are device files; snooze is part of the alarm |

## Requirements and features
- Requirements: all of [app requirements](../../requirements/app-requirements.md); TR-APP-06, 08
- [App phases](../../features/app/): [A1](../../features/app/A1-foundation.md), [A2](../../features/app/A2-login-sync.md), [A3](../../features/app/A3-alarm-engine.md), [A4](../../features/app/A4-editor-reporting.md), [A5](../../features/app/A5-hardening-release.md)
- [Phase 6](../../features/06-phase-app-addon-sync.md): 6.6, 6.12
- [Phase 7](../../features/07-phase-hardening-release.md): 7.10, 7.10a