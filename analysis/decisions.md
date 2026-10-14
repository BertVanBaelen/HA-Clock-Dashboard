# Decision Log

| # | Decision | Reason | Date |
|---|---|---|---|
| D1 | Alarms are **per user**; visible to the owner and admins | Alarms are personal, scripts are shared. Household alarms are backlog | 2026-10-14 |
| D2 | Dismissal timeout **60 minutes from alarm time**, configurable | Covers long snooze chains; heating after that is pointless | 2026-10-14 |
| D3 | A "before" action whose time is already past when saving is **skipped, logged as missed, and the UI warns** | Running it immediately would surprise users | 2026-10-14 |
| D4 | Frontend: **Lit + TypeScript + Vite**; bundle built by CI, no build tools for users | Matches HA's own frontend | 2026-10-14 |
| D5 | **Single config entry** | One alarm store per HA install | 2026-10-14 |
| D6 | Sync is **polling** in v1 (provided by the app repo); websocket later | Simplicity; contract unaffected | 2026-10-14 |
| D7 | Domain name **`alarm_actions`** | Describes the function; avoid later data migration | 2026-10-14 |
| D8 | License **MIT** | Common in the HA ecosystem | 2026-10-14 |
| D9 | ~~Two repositories~~ **Superseded by D15** | HACS manages only one integration per repository, and one link should install everything | 2026-10-14 |
| D10 | Dismiss/snooze events are part of the core API | The panel needs manual dismiss; "after dismissal" is a core feature | 2026-10-14 |
| D11 | ~~Extension point for other integrations~~ **Dropped** | No separate app package any more, so not needed | 2026-10-14 |
| D12 | Sensor and switch entities are **opt-in** | Avoid exposing other users' alarm times to all HA users | 2026-10-14 |
| D13 | Unknown or foreign objects return **404** | Prevents id enumeration | 2026-10-14 |
| D14 | Security scope kept lean: owner-only alarms, and nothing open without HA login (also for outside-home app use) | Matches what is needed for a home project; no heavy compliance work | 2026-10-14 |
| D15 | **One repository, one integration (`alarm_actions`)** that contains the dashboard, the sync API and the Android app download. The Android app source lives in `android/` in the same repo. One HACS link installs everything | HACS only manages one integration per repo | 2026-10-14 |
| D16 | The "Install app" tab shows only if an APK is bundled in the installed release **and** the option "Offer Android app" is on (default on) | Keeps the dashboard usable without the app; users who do not want it can switch it off | 2026-10-14 |
| D17 | The APK is delivered inside the HACS release zip (`zip_release`), built and signed by CI; it is never committed to git | Binary stays out of the repo; one install step | 2026-10-14 |
| D18 | The APK contains no secrets or server address; the user enters the HA URL and logs in | The same APK can be downloaded from a repo anywhere and is safe to publish | 2026-10-14 |
