# Decision Log

| # | Decision | Reason | Date |
|---|---|---|---|
| D1 | Alarms are **per user**; visible to the owner and admins | Alarms are personal, scripts are shared. Household alarms are backlog | 2026-10-14 |
| D2 | Dismissal timeout **60 minutes from alarm time**, configurable | Covers long snooze chains; heating after that is pointless | 2026-10-14 |
| D3 | A "before" action whose time is already past when saving is **skipped, logged as missed, and the UI warns** | Running it immediately would surprise users | 2026-10-14 |
| D4 | Frontend: **Lit + TypeScript + Vite**; bundle built by CI, no build tools for users | Matches HA's own frontend | 2026-10-14 |
| D5 | **Single config entry** | One alarm store per HA install | 2026-10-14 |
| D6 | Sync is **polling** in v1 (app syncs periodically via `GET /sync`); websocket later | Simplicity; contract unaffected | 2026-10-14 |
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
| D19 | Admins have **view-only** access to other users' alarms (no edit, delete, skip, dismiss or run); writes return 403 | Alarms are personal; an admin can still deactivate a user to stop their alarms | 2026-10-14 |
| D20 | **Away by time zone:** when the owner's phone reports a time zone different from HA's, the phone rings at its local wall-clock time and HA runs no actions for that owner (recorded as `skipped`, "user away") | A user in another time zone is not at home, so heating etc. is pointless | 2026-10-14 |
| D21 | Script starts beyond the integration-wide rate limit are **dropped** and recorded as `skipped` ("rate limited"), not queued | Protects devices; late starts are rarely useful | 2026-10-14 |
| D22 | A dismissal **before** the alarm fires (up to 60 min early) acts as a skip for `before`/`at`/`after` runs not yet started; `after_dismiss` runs from the dismissal time + delay | Waking up early should start the "after I get up" actions, not the wake-up ones | 2026-10-14 |
| D23 | Per-alarm `snooze_minutes` is stored on the server and synced; the alarm sound stays local on the phone | Snooze is part of the alarm; sounds are device files | 2026-10-14 |
| D24 | Alarms disabled because the owner was deactivated **stay disabled** after reactivation | No surprise alarms; the owner re-enables them | 2026-10-14 |
| D25 | Sync uses a store-wide `change_seq`; per-alarm `revision` is only for optimistic locking | Per-alarm counters cannot be compared across alarms | 2026-10-14 |
| D26 | Contract formats: occurrence id `<alarm_id>_<YYYYMMDDTHHMMSSZ>`, planned-run id `<occurrence_id>_<action_id>`, weekdays as `mon`..`sun`, action ids stable across edits | URL-safe, unambiguous between Python, TypeScript and Kotlin; keeps exactly-once intact after edits | 2026-10-14 |
| D27 | Brand images live in `custom_components/alarm_actions/brand/`; minimum HA version **2026.3** | Since HA 2026.3 custom integrations ship their own brand images; the brands repo no longer accepts custom integrations | 2026-10-14 |
