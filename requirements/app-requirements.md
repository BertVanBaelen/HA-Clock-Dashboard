# Android App Requirements

The Android app lives in `android/` in this repository. Its APK is shipped inside the second add-on, "Alarm Actions App" (`alarm_actions_app`); when that add-on runs, the dashboard shows an "Install app" tab with the download ([Dashboard add-on](../analysis/components/dashboard-addon.md), [App add-on](../analysis/components/app-addon.md)). The server side (sync, download) is specified in the functional and technical requirements. This document covers the app itself.

Priority: **M** must (v1), **S** should, **C** could.

## 1. Functional requirements
| ID | Requirement | Prio |
|---|---|---|
| AFR-LOG-01 | The user enters the HA URL and logs in with their Home Assistant account (standard HA login flow); the password is never handled by the app | M |
| AFR-LOG-02 | The user can log out, which deletes the local data and tokens | M |
| AFR-ALM-01 | The app lists, creates, edits, deletes, enables/disables alarms and skips the next occurrence, with the same fields as the panel | M |
| AFR-ALM-02 | Per alarm, actions (labeled scripts) with timing before / at / after / after dismissal are chosen from the list the server provides | M |
| AFR-ALM-03 | The app shows a warning when a *before* action will be skipped (same rule as the panel) | M |
| AFR-RNG-01 | The alarm rings at the right time with **no network connection**, from a local copy of the alarms | M |
| AFR-RNG-02 | The alarm shows a **full-screen** screen over the lock screen and turns the screen on | M |
| AFR-RNG-03 | The alarm **rings through Do Not Disturb** | M |
| AFR-RNG-04 | The user can snooze (the alarm's own snooze duration, else the server default of 9 min) or stop the alarm | M |
| AFR-RNG-05 | The user picks the alarm sound (stored on the phone only, [Alarm](../analysis/objects/alarm.md)); volume ramps up gently (optional) | S |
| AFR-RNG-06 | Alarms are rescheduled after reboot, app update, time change and time zone change | M |
| AFR-RNG-07 | If the phone was off at alarm time, the missed alarm is shown on next start | S |
| AFR-RNG-08 | When the phone's time zone differs from HA's, alarms ring at the local wall-clock time and the app shows "away: Home Assistant actions paused". No dismiss/snooze reports are sent while away ([Client](../analysis/objects/client.md)) | M |
| AFR-SYN-01 | The app syncs on open, on time zone change, and periodically in the background (WorkManager, about every 15 min), plus a manual "sync now". On every sync it registers itself and reports its time zone (`POST /clients`) | M |
| AFR-SYN-02 | Edits made offline are queued and sent when the connection returns; conflicts are shown to the user | S |
| AFR-SYN-03 | Dismiss, snooze and timed-out reports are queued when offline and sent later | M |
| AFR-SYN-04 | The app shows the sync state ("last synced", "offline") so the user knows alarms are current | M |
| AFR-SET-01 | A first-run guide requests the needed permissions: exact alarms, full-screen intent, notifications, battery optimisation exemption | M |
| AFR-SET-02 | The app shows a clear message when the dashboard is too old or too new for the app (API version check) | M |
| AFR-SET-03 | **In-app update ([Android app](../analysis/components/android-app.md)):** the app checks `GET /app/info` on every sync and, when a newer APK is in the app add-on, shows "Update available". One tap downloads and installs it over the existing app; alarms, login and settings are kept, no uninstall needed | M |
| AFR-SET-04 | A health check screen tells the user if something would stop the alarm from ringing (permission missing, battery restriction, DND channel blocked) | S |
| AFR-SET-05 | The install guide explains allowing "Install unknown apps": once for the browser (first install) and once for the app itself (in-app updates) | M |
| AFR-NGT-01 | **Night display ([Android app](../analysis/components/android-app.md)):** a bedside clock mode with a large dimmed clock and the next alarm, keeping the screen on. Started manually or, optionally, automatically while charging during a set time window. A ringing alarm takes over the screen | S |

## 2. Technical requirements
| ID | Requirement |
|---|---|
| ATR-PLT-01 | Kotlin, native Android. Minimum and target SDK defined in the project (suggested min SDK 29) and checked in CI |
| ATR-PLT-02 | Application id `com.example.alarmactions` as placeholder; final id chosen before first release (cannot change later) |
| ATR-PLT-03 | UI with Jetpack Compose; local storage with Room; background sync with WorkManager |
| ATR-RNG-01 | Alarms use `AlarmManager.setAlarmClock()` so they fire exactly and show in the system alarm indicator |
| ATR-RNG-02 | A foreground service plays the sound on the **alarm audio stream** (`USAGE_ALARM`), which passes through Do Not Disturb by default |
| ATR-RNG-03 | A full-screen intent starts the ringing activity over the lock screen. Permission handling covers Android 14+ restrictions on full-screen intents |
| ATR-RNG-04 | A boot receiver reschedules all alarms; the app also reschedules on `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED` and package replacement |
| ATR-RNG-05 | Ringing never depends on the network: the engine reads only from Room |
| ATR-RNG-06 | A guard against stuck ringing: the alarm stops after a maximum duration (default 10 min) and is reported as `timed_out` |
| ATR-RNG-07 | Exact alarms use the `USE_EXACT_ALARM` permission (allowed for alarm-clock apps; falls back to `SCHEDULE_EXACT_ALARM` handling). Full-screen intent permission on Android 14+ is verified at runtime, since grant rules differ for sideloaded apps |
| ATR-SYN-01 | The app computes occurrence ids and alarm times exactly as the server does, verified against the published test vectors in `docs/` (ids, DST, weekdays, skip), so it can report events offline. While away it schedules in its own time zone ([Client](../analysis/objects/client.md)) |
| ATR-SYN-02 | Sync uses `GET /sync?since=<change_seq>`; writes use optimistic `revision`; reports use the dismiss/snooze/timed-out endpoints |
| ATR-SYN-05 | **Ingress access (TR-APP-06):** after OAuth login the app opens the HA websocket, sends `supervisor/api` `GET /addons/<slug>/info` for the `ingress_entry` and `POST /ingress/session` for a session token, then calls `<ha_url>/api/hassio_ingress/<entry>/api/v1/...` with the `ingress_session` cookie. It renews the session with `/ingress/validate_session` and creates a new one on 401. Whether a **non-admin** user may make these calls is verified first (spike A2.0); if not, the design must be revisited before A2 continues |
| ATR-SYN-06 | The dashboard add-on slug is deterministic for this repository (`<hash>_alarm_actions`) and built into the app as default; the user can override it by scanning the QR code or typing the slug shown on the "Install app" tab (TR-APP-09) |
| ATR-SYN-03 | Network calls only to the user's HA URL over **HTTPS**; cleartext traffic is disabled in the network security config |
| ATR-SYN-04 | HA OAuth requires the `client_id` to be a URL whose page declares the redirect URI. The project hosts a static page on GitHub Pages (e.g. `https://bertvanbaelen.github.io/HA-Clock-Dashboard/`) with `<link rel="redirect_uri" href="alarmactions://auth-callback">`; the app uses that `client_id` and handles the custom-scheme callback |
| ATR-REL-01 | CI builds a release APK on tags, signs it with a keystore from GitHub secrets, and copies it into the app add-on image (TR-APP-02) |
| ATR-REL-02 | The keystore is backed up outside GitHub; losing it means users must uninstall to update |
| ATR-REL-03 | `versionCode` increases on every release; `versionName` matches the add-on version |
| ATR-REL-04 | In-app updater: downloads `GET /app/apk` through the ingress session, checks the SHA-256 from `GET /app/info`, and installs with a `PackageInstaller` session (`REQUEST_INSTALL_PACKAGES`). Android itself rejects an APK with a different signing key or a lower `versionCode`. On Android 12+ the session requests `USER_ACTION_NOT_REQUIRED`; when Android still asks, the user confirms with one tap. No update starts while an alarm rings or within 30 minutes before the next alarm; after the update, alarms are rescheduled (ATR-RNG-04) |
| ATR-NGT-01 | Night display: a Compose screen with `FLAG_KEEP_SCREEN_ON`, low window brightness and a dark theme, reading only from Room (works offline). The clock shifts position slightly every few minutes to avoid burn-in. Auto-start uses the charging state and the user's time window |
| ATR-QLT-01 | Unit tests for the alarm schedule calculation (DST, weekdays, skip) and sync merge logic; instrumented tests for the ringing flow where feasible |
| ATR-QLT-02 | A manual device test list covers several Android versions and manufacturers known for aggressive battery management |

## 3. Security requirements
| ID | Requirement |
|---|---|
| ASEC-01 | Login through HA's OAuth flow; tokens stored in the Android Keystore-backed encrypted storage, never in plain preferences or logs |
| ASEC-02 | The app only talks to the configured HA URL over HTTPS; certificate validation is not disabled |
| ASEC-03 | The APK contains no secrets, tokens or server address |
| ASEC-04 | The app uses a non-admin HA user; it is documented that admin accounts must not be used on phones. An admin who wants the app uses a separate non-admin account, which then owns their phone alarms |
| ASEC-05 | Local alarm data is excluded from cloud backups, or backups are disabled for the app |
| ASEC-06 | Tokens are deleted on logout; a revoked token ends sync but alarms already stored keep ringing until the user logs out |
| ASEC-07 | The signing keystore is a CI secret and is never committed |
| ASEC-08 | Updates are only downloaded from the user's own HA (through ingress, HTTPS) and installed only when the checksum matches; the Android signature check prevents installing an APK from another signer |

## 4. Out of scope (v1)
iOS app, Play Store release, creating new scripts from the app, Wear OS, widgets, sleep tracking, push-based instant sync, notifications for failed actions.

## 5. Open questions
1. Minimum Android version (29 is a suggestion).
2. Gentle volume ramp and sound picker in v1, or later?
3. Should a revoked token also stop alarms from ringing? (Proposed: no, a wake-up alarm should never silently fail.)
4. Can a non-admin HA user open an ingress session for the add-on from the app (ATR-SYN-05)? To be answered by spike A2.0.
