# App add-on (`alarm_actions_app`)

**Purpose:** Optional second add-on that holds the signed [Android app](android-app.md) APK. Installing it is the opt-in for the app: while it runs, the [dashboard add-on](dashboard-addon.md) shows the "Install app" tab.

## Features
- Serves the APK, its version and checksum on the internal Supervisor network only.
- The dashboard checks whether the app add-on runs and shows the "Install app" tab with a download button and a setup QR code (HA URL and API URL).
- The download is proxied through the dashboard API (ingress for the panel, external API for the in-app updater), so it always requires an HA login.
- Provides version info for the in-app updater.

## Rules
- The APK is copied into the add-on image by CI and signed there; it is never committed to git.
- Not reachable from outside HA; no host ports, no ingress panel of its own.
- Updating the app add-on (new version in the Add-on Store) makes the new APK available; phones update through the in-app updater.

## Decisions
| Decision | Why |
|---|---|
| The **app add-on** holds the APK; when it runs, the dashboard shows the Install tab and proxies the download (login required) | Installing the app add-on is the opt-in; no signed links needed |
| The APK contains no secrets or server address | The same APK is safe to publish anywhere |

## Requirements and features
- Requirements: FR-APP-01, 02, 05, FR-UI-07; TR-APP-01..04, 09; SEC-09
- [Phase 4](../../features/04-phase-dashboard-panel.md): 4.15
- [Phase 6](../../features/06-phase-app-addon-sync.md): 6.3, 6.4, 6.5, 6.11
- [Phase 7](../../features/07-phase-hardening-release.md): 7.11