# HA Clock Dashboard

A Home Assistant (HACS) custom integration that adds an **alarm planner** to your dashboard. Define alarms and choose which Home Assistant scripts run **before, at, or after** each alarm, for example *heat the bathroom 10 minutes before* or *heat the car 20 minutes after I get up*.

> Status: **planning / pre-alpha**. See [`features/`](features/00-roadmap.md) for the roadmap.

## What it does
- Sidebar panel to create and manage alarms (time, label, days, on/off, skip next)
- Per alarm, attach **actions** (HA scripts labeled `alarm_action`) with a timing: X minutes before, at alarm time, X minutes after, or after dismissal
- Scheduling runs **inside Home Assistant**, so actions fire even if no phone is online
- API for dismiss/snooze reports and for the Android app
- An **Install app** tab to download the Android app, which adds a full-screen alarm that rings through Do Not Disturb
- Works without the app: the dashboard is fully usable on its own
- **Away detection:** when your phone is in another time zone, it rings at local time and Home Assistant pauses your actions

## What it does not do
- Ring a phone from the dashboard (ringing is the Android app's job)
- Create or edit Home Assistant automations
- Anything iOS-specific. iPhone users can use the panel in the HA Companion App plus the native Clock app

## One repository, everything inside
HACS can only manage one integration per repository, so this repo contains **one integration** (`alarm_actions`) with the dashboard, the sync API and the Android app download. The Android app source is in `android/`. Add one link in HACS and you get both.

| Part | Where |
|---|---|
| Integration, scheduler, panel, API | `custom_components/alarm_actions/` |
| Android app source (Kotlin) | `android/` |
| APK served to users | Built by CI and bundled into the release zip |

## Installation (HACS custom repository)
Requires Home Assistant **2026.3** or newer.

1. HACS → ⋮ → **Custom repositories**
2. Add `https://github.com/BertVanBaelen/HA-Clock-Dashboard`, category **Integration**
3. Install, then restart Home Assistant
4. Settings → Devices & services → **Add integration** → *Alarm Actions*
5. Optional, Android: open the **Install app** tab in the Alarms panel and download the APK (the tab can be switched off in the integration options)

## Quick start
1. Create the scripts you want to use (e.g. `script.heat_bathroom`)
2. Give each script the label `alarm_action`
3. Open **Alarms** in the sidebar, create an alarm, add actions and timings
4. For a ringing alarm on Android: install the app from the **Install app** tab and log in with your HA account

## Security
- Users can only see and change **their own alarms**; admins can view all alarms but not change other users' alarms
- **Every endpoint requires a Home Assistant login.** Nothing is open to the internet, so making HA reachable for the app outside your home adds no open logic. The app logs in with your HA account
- A **non-admin** user is enough; create one per person or phone
- Only scripts labeled `alarm_action` can be run, and without parameters from users
- The APK contains no secrets or server address, and is downloaded through a short-lived link that only a logged-in user can create

**Note:** anyone with access to alarms can cause *any* script with that label to run. Only label scripts that are safe, and give heating scripts their own auto-off.

If HA is reachable from the internet: use HTTPS through a reverse proxy, enable MFA, enable `ip_ban`, and revoke a lost phone's token in the HA user profile. Details: [requirements/security-requirements.md](requirements/security-requirements.md).

## Documentation
- [Functional requirements](requirements/functional-requirements.md)
- [Technical requirements](requirements/technical-requirements.md)
- [Android app requirements](requirements/app-requirements.md)
- [Security requirements](requirements/security-requirements.md)
- [Domain analysis](analysis/domain-analysis.md)
- [Decision log](analysis/decisions.md)
- [Feature roadmap](features/00-roadmap.md)

## Repository layout
```
custom_components/alarm_actions/   integration code (to be created)
  app/                             APK placed here by release CI (not in git)
  brand/                           integration icon and logo
android/                           Android app source (Kotlin)
requirements/                      functional, technical, app, security requirements
analysis/                          domain analysis and decision log
features/                          phased feature plan (dashboard + app tracks)
docs/openapi.yaml                  API contract (to be created)
LICENSE  hacs.json
```

## License
MIT
