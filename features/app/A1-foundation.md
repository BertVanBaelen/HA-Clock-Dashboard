# App Phase A1: Foundation

**Goal:** An empty Android app builds, is signed in CI, and ships inside the app add-on image.

**Objects and components:** [Android app](../../analysis/components/android-app.md), [App add-on](../../analysis/components/app-addon.md)

| # | Feature | Requirements | Done when |
|---|---|---|---|
| A1.1 | Android project in `android/` (Kotlin, Compose, Room, WorkManager), application id decided | ATR-PLT-01..03 | Project builds locally and in CI |
| A1.2 | Network security config with cleartext disabled; backups disabled for app data | ATR-SYN-03, ASEC-02, 05 | Verified in manifest |
| A1.3 | CI: build, unit tests, lint on every PR touching `android/` | ATR-QLT-01 | Workflow green |
| A1.4 | Signing keystore as GitHub secret; keystore backup documented | ATR-REL-01, 02, ASEC-07 | Signed APK produced from a tag |
| A1.5 | Version scheme: `versionCode` increments, `versionName` matches the add-on version | ATR-REL-03 | Verified in release build |

**Exit criteria:** a tagged build produces a signed APK that installs on a phone and opens an empty screen.
