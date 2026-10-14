# HA Clock Dashboard

A Home Assistant **add-on repository** that adds an **alarm planner** to your sidebar. Define alarms and choose which Home Assistant scripts run **before, at, or after** each alarm, for example *heat the bathroom 10 minutes before* or *heat the car 20 minutes after I get up*.

> Status: **planning / pre-alpha**. See [`features/`](features/00-roadmap.md) for the roadmap.

## What it does
- Sidebar panel "Alarms" to create and manage alarms (time, label, days, on/off, skip next), for every HA user
- Per alarm, attach **actions** (HA scripts labeled `alarm_action`) with a timing: X minutes before, at alarm time, X minutes after, or after dismissal
- Scheduling runs **inside Home Assistant** (in the add-on), so actions fire even if no phone is online
- Optional second add-on that offers the **Android app**: an extra **Install app** tab appears in the panel. The app has the same alarm management plus a full-screen alarm that rings through Do Not Disturb and a night display (bedside clock). The app updates itself from the add-on; no reinstall needed
- Works without the app: the dashboard is fully usable on its own
- **Away detection:** when your phone is in another time zone, it rings at local time and Home Assistant pauses your actions

## What it does not do
- Ring a phone from the dashboard (ringing is the Android app's job)
- Create or edit Home Assistant automations
- Create HA entities for alarms (the phone's next alarm shows in the Companion App's own *Next alarm* sensor)
- Anything iOS-specific. iPhone users can use the panel in the HA Companion App plus the native Clock app

## Two add-ons, one repository URL
| Add-on | Folder | What it gives you |
|---|---|---|
| **Alarm Actions** | `alarm_actions/` | The "Alarms" panel, scheduler, actions, history and the API |
| **Alarm Actions App** (optional) | `alarm_actions_app/` | The Android APK; the panel shows an **Install app** tab while this add-on runs |

The Android app source is in `android/`; release CI builds and signs the APK and puts it in the app add-on image.

## Installation
Requires **Home Assistant OS or Supervised** (add-ons need the Supervisor; Container and Core installs are not supported).

1. Settings → Add-ons → **Add-on Store** → ⋮ → **Repositories**
2. Add `https://github.com/BertVanBaelen/HA-Clock-Dashboard`
3. Install **Alarm Actions**, start it. "Alarms" appears in the sidebar for all users
4. Optional, Android: install and start **Alarm Actions App**, then open the **Install app** tab in the Alarms panel and download the APK
5. Optional: change settings (snooze, timeouts, label, rate limit) on the add-on's **Configuration** tab (admins only)

## Quick start
1. Create the scripts you want to use (e.g. `script.heat_bathroom`)
2. Give each script the label `alarm_action`
3. Open **Alarms** in the sidebar, create an alarm, add actions and timings
4. For a ringing alarm on Android: install the app from the **Install app** tab and log in with your HA account

## Security
- Users can only see and change **their own alarms**; admins can view all alarms but not change other users' alarms
- **Everything goes through Home Assistant ingress, so every request requires a Home Assistant login.** The add-ons expose no ports, so making HA reachable for the app outside your home adds no open logic. The app logs in with your HA account
- A **non-admin** user is enough; create one per person or phone
- Only scripts labeled `alarm_action` can be run, and without parameters from users
- The APK contains no secrets or server address, and can only be downloaded by a logged-in user

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
repository.yaml                    add-on repository metadata (to be created)
alarm_actions/                     dashboard add-on: config.yaml, Dockerfile, service/, frontend/
alarm_actions_app/                 app add-on: config.yaml, Dockerfile, APK server (APK added by CI, not in git)
android/                           Android app source (Kotlin)
requirements/                      functional, technical, app, security requirements
analysis/                          domain analysis and decision log
features/                          phased feature plan (dashboard + app tracks)
docs/openapi.yaml                  API contract (to be created)
LICENSE
```

## License
MIT