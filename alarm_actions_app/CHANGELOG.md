# Changelog

## Unreleased

- App: new design (`docs/design.md`): warm light and dark colors, new icon, alarm timeline with drag-to-place actions, and the list-to-detail animation.
- App: alarms can be set not to vibrate (Vibrate chip or row in the alarm, "No vibration" in the list). This is kept on the phone, like the sound.
- App: fixed the **Update**, **Allow**, **Log in again** and **Review** buttons in the banners, which did nothing. Phones with 0.2.1 or older must install this update once by hand, from the panel's **Install app** tab. After that, updates install from the app again.
- App: **Settings → Check for updates** checks right away and installs a new version. Once the app has updated itself, later updates no longer ask for confirmation.
- App: a ringing alarm now opens the full-screen alarm screen (also on the lock screen) instead of
  only showing a notification, and no longer asks to unlock the phone first.

## 0.1.2

- First version: serves the signed Android app (APK) to the Alarm Actions add-on on the internal
  network. The Alarms panel offers the download in the **Install app** tab on Android phones.
