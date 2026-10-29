# Alarm Actions – Android app

Native Android alarm clock for the HA-Clock-Dashboard *Alarm Actions* add-on. Alarms are edited
here or in the Home Assistant panel. The phone rings them itself with `AlarmManager.setAlarmClock`,
even without network. Home Assistant runs the scripts before, at and after the alarm.

- Package / application id: `alarmclock.vanbaelen.dev`
- Android 10 (API 29) or newer. Target and compile SDK 37.
- Kotlin, Jetpack Compose (Material 3), Room, WorkManager, OkHttp, kotlinx-serialization

## Install on a phone

1. In Home Assistant, open the **Alarm Actions** panel and choose **Connect phone**.
2. Download the APK from the panel (or from a GitHub release) and open it on the phone.
   Android asks to allow installing apps from that source (browser or file manager) the first time.
3. Open the app. You can connect in two ways:
   - Scan the QR code in the panel with the phone's **camera app** and open the `alarmactions://setup` link.
   - Type the Home Assistant address and the API address shown in the panel. Both must be `https://`.
4. Tap **Log in**. Your own Home Assistant login page opens in a browser tab. The app never sees your
   password. It stores only the tokens, encrypted with an Android Keystore key.
5. Work through the **Make alarms reliable** guide. You can open it again later via
   Settings → Health check.

Later versions install from inside the app: tap the **Update available** banner. The update is
refused while an alarm rings or within 30 minutes before the next alarm.

## Permissions

| Permission | Why |
|---|---|
| Alarms & reminders (`USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` on Android 12) | Ring at the exact minute |
| Notifications (`POST_NOTIFICATIONS`) | Ringing alarm, snooze status, missed alarms, "log in again" |
| Full-screen intent (`USE_FULL_SCREEN_INTENT`) | Show the alarm over the lock screen. On Android 14+ check it in the Health check. |
| Foreground service (system-exempted / media playback) | Keep playing the alarm sound until you stop it |
| Ignore battery optimisation | Avoid delayed alarms and sync on aggressive phones |
| Install unknown apps (`REQUEST_INSTALL_PACKAGES`) | Optional: in-app updates from your own dashboard |
| Boot completed, wake lock, vibrate, internet, network state | Reschedule after a reboot, ring reliably, sync |

The app sends tokens only to the configured Home Assistant and API hosts. It uses HTTPS only and
trusts only the system certificate authorities. Backups are disabled, so tokens never leave the phone.

## Troubleshooting

- **Alarm did not ring**
  - Open Settings → Health check and fix every item marked with a warning.
  - Check that the "Ringing alarms" notification channel is on with sound.
  - Check Do Not Disturb: alarms must be allowed.
- **Alarm rang without the full-screen screen**
  - On Android 14+, allow "Full screen notifications" for the app (Health check → Full-screen alarm).
- **Battery optimisation per manufacturer.** Also see <https://dontkillmyapp.com>.
  - *Samsung*: Settings → Battery → Background usage limits. Remove the app from "Sleeping apps" and
    "Deep sleeping apps". Add it to "Never sleeping apps".
  - *Xiaomi / Redmi / POCO (MIUI, HyperOS)*: App info → Autostart **on**, and Battery saver →
    **No restrictions**. Also lock the app in the recent-apps screen.
  - *Huawei / Honor*: Settings → Battery → App launch. Set the app to *Manage manually* and enable all three toggles.
  - *OnePlus / Oppo / Realme (ColorOS)*: Battery → App battery management → **Allow background activity**.
    Also turn off "Optimise battery use" and allow auto-launch.
  - *Google Pixel / stock Android*: the Health check exemption is enough.
- **"Log in again"**: the Home Assistant refresh token was revoked or has expired.
  - Alarms keep ringing. Tap **Log in again** to resume syncing.
- **"Away: Home Assistant actions paused"**: the phone is in a different time zone from Home Assistant.
  - Alarms ring at their wall-clock time in the local zone.
  - No reports are sent, so Home Assistant actions do not run.
- **Missed alarm**: the phone was off at alarm time. The app shows it on the next start.
- **Dashboard too old / app too old**: update the add-on or the app. The API versions must overlap.

## Build

Requirements: JDK 21 or newer (Android Studio's JBR works) and the Android SDK with platform 37 and
build-tools 36.

```powershell
# Windows
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd android
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

```bash
# Linux / macOS
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r <apk>`.
- Unit tests run the shared schedule vectors from `docs/test-vectors/schedule.json`. They are the
  same vectors the server tests use.
- `versionName` is read from `alarm_actions_app/config.yaml` (`version: "x.y.z"`).
  `versionCode = major*1_000_000 + minor*1_000 + patch`.
- To override the version: `-PappVersion=1.2.3 -PappVersionCode=1002003`.

### Release build and signing

The release build is minified with R8. It is signed only when the signing variables are set.
Otherwise the APK is unsigned (`app-release-unsigned.apk`).

| Variable (env or `-P` Gradle property) | Meaning |
|---|---|
| `ANDROID_KEYSTORE_FILE` | Path to the `.jks` keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password (defaults to the keystore password) |

```bash
cd android
ANDROID_KEYSTORE_FILE=$PWD/release.jks ANDROID_KEYSTORE_PASSWORD=*** \
ANDROID_KEY_ALIAS=alarmactions ANDROID_KEY_PASSWORD=*** \
./gradlew --no-daemon assembleRelease
# -> android/app/build/outputs/apk/release/app-release.apk
```

### Generating the keystore (once) and backing it up

```bash
keytool -genkeypair -v -keystore release.jks -storetype PKCS12 \
  -alias alarmactions -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Alarm Actions, O=vanbaelen.dev"
base64 -w0 release.jks > release.jks.base64   # value for the GitHub secret
```

- **Never commit the keystore.** `*.jks` and `*.keystore` are git-ignored.
- Store it in GitHub secrets, for example `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. The release workflow decodes it to a file and
  sets `ANDROID_KEYSTORE_FILE`.
- **Back up** the keystore and passwords outside GitHub, for example in a password manager plus an
  offline copy (ATR-REL-02). If the key is lost, users must uninstall the app (and lose its local
  settings) before they can install an update signed with a new key.

## Project layout

```
app/src/main/java/alarmclock/vanbaelen/dev/
  core/     plain Kotlin, unit-tested: schedule port, models, editor validation, timeline geometry, sync merge,
            ring planning, report/update/version policies
  data/     Room database, encrypted token store, OAuth, API client, repository, sync engine,
            Home Assistant REST client and data for the night display widgets
  alarm/    AlarmManager engine (device-protected storage, works before first unlock),
            ringing service, wake-up screen (sunrise, snooze, good morning), receivers, report queue, notifications
  work/     WorkManager: periodic sync, report retry
  update/   in-app updater (download, SHA-256, PackageInstaller)
  ui/       Compose screens (docs/design.md): setup, alarm list + detail timeline, conflicts, settings,
            health check, night display
```

See [TESTING.md](TESTING.md) for the manual device test list.
