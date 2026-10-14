# Alarm Actions App

This add-on holds the **Android app** for [Alarm Actions](../alarm_actions/DOCS.md). It has no
panel, no options and no open ports: the Alarm Actions add-on fetches the app from it over the
internal add-on network and offers it to logged-in users.

## Installation

1. Install and start **Alarm Actions** first.
2. Install and start **Alarm Actions App** from the same repository.
3. Open **Alarms** in the sidebar on your Android phone: the **Install app** tab appears. Follow
   the steps there. The full setup (external address, Cloudflare tunnel) is described in the
   Alarm Actions documentation under *Android app*.

The tab is only shown on Android phones, and only while this add-on runs.

## Updates

The app version always equals this add-on's version. After updating the add-on, the app finds
the new version on its next check and offers to install it; you can also download it again from
the **Install app** tab.

## Verifying the download

The **Install app** tab shows the SHA-256 checksum of the APK. The same checksum is listed in
the GitHub release of this version.

## Notes

- Android asks once to allow installing apps from the browser (or the Home Assistant app) you
  downloaded with. If the download does not start in the Home Assistant app, open the panel in
  your phone's browser.
- The app contains no secrets and no server address; you set it up with your own Home Assistant
  account.
