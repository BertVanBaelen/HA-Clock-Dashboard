# Android App Requirements

The Android app lives in `android/` in this repository and is distributed as an APK bundled in the HACS release (decisions D15, D17). The server side (sync, download) is specified in the functional and technical requirements. This document covers the app itself.

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
| AFR-RNG-04 | The user can snooze (default 9 min, from the server settings, adjustable per alarm) or stop the alarm | M |
| AFR-RNG-05 | The user picks the alarm sound; volume ramps up gently (optional) | S |
| AFR-RNG-06 | Alarms are rescheduled after reboot, app update, time change and time zone change | M |
| AFR-RNG-07 | If the phone was off at alarm time, the missed alarm is shown on next start | S |
| AFR-SYN-01 | The app syncs on open and periodically in the background, plus a manual "sync now" | M |
| AFR-SYN-02 | Edits made offline are queued and sent when the connection returns; conflicts are shown to the user | S |
| AFR-SYN-03 | Dismiss and snooze reports are queued when offline and sent later | M |
| AFR-SYN-04 | The app shows the sync state ("last synced", "offline") so the user knows alarms are current | M |
| AFR-SET-01 | A first-run guide requests the needed permissions: exact alarms, full-screen intent, notifications, battery optimisation exemption | M |
| AFR-SET-02 | The app shows a clear message when the dashboard is too old or too new for the app (API version check) | M |
| AFR-SET-03 | The app checks for a newer bundled APK and offers to open the download | S |
| AFR-SET-04 | A health check screen tells the user if something would stop the alarm from ringing (permission missing, battery restriction, DND channel blocked) | S |

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
| ATR-RNG-06 | A guard against stuck ringing: the alarm stops after a maximum duration (default 10 min) and is reported as not dismissed |
| ATR-SYN-01 | The app computes occurrence ids exactly as the server does (test vectors in `docs/`) so it can report events for occurrences offline |
| ATR-SYN-02 | Sync uses `GET /sync?since=`; writes use optimistic `revision`; reports use the dismiss/snooze endpoints |
| ATR-SYN-03 | Network calls only to the user's HA URL over **HTTPS**; cleartext traffic is disabled in the network security config |
| ATR-REL-01 | CI builds a release APK on tags, signs it with a keystore from GitHub secrets, and hands it to the integration release pipeline (TR-APP-02) |
| ATR-REL-02 | The keystore is backed up outside GitHub; losing it means users must uninstall to update |
| ATR-REL-03 | `versionCode` increases on every release; `versionName` matches the integration version |
| ATR-QLT-01 | Unit tests for the alarm schedule calculation (DST, weekdays, skip) and sync merge logic; instrumented tests for the ringing flow where feasible |
| ATR-QLT-02 | A manual device test list covers several Android versions and manufacturers known for aggressive battery management |

## 3. Security requirements
| ID | Requirement |
|---|---|
| ASEC-01 | Login through HA's OAuth flow; tokens stored in the Android Keystore-backed encrypted storage, never in plain preferences or logs |
| ASEC-02 | The app only talks to the configured HA URL over HTTPS; certificate validation is not disabled |
| ASEC-03 | The APK contains no secrets, tokens or server address |
| ASEC-04 | The app uses a non-admin HA user; it is documented that admin accounts must not be used on phones |
| ASEC-05 | Local alarm data is excluded from cloud backups, or backups are disabled for the app |
| ASEC-06 | Tokens are deleted on logout; a revoked token ends sync but alarms already stored keep ringing until the user logs out |
| ASEC-07 | The signing keystore is a CI secret and is never committed |

## 4. Out of scope (v1)
iOS app, Play Store release, creating new scripts from the app, Wear OS, widgets, sleep tracking, push-based instant sync, notifications for failed actions.

## 5. Open questions
1. Minimum Android version (29 is a suggestion).
2. Gentle volume ramp and sound picker in v1, or later?
3. Should a revoked token also stop alarms from ringing? (Proposed: no, a wake-up alarm should never silently fail.)
