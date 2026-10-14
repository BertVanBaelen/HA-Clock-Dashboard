# Changelog

## 0.1.0

- First release: add-on skeleton, connection to Home Assistant Core, database with hot backup.
- Alarm API under `/api/v1` (owner-only, also for admins; `GET /planned` shows other users' upcoming actions), scripts with the `alarm_action` label as actions, contract in `docs/openapi.yaml`.
- Scheduler: before/at/after actions start their scripts at the planned time, exactly once, also across restarts (grace period) and Core outages; skip next, one-time alarms, rate limits, execution history and "run now".
- "Alarms" panel: alarm list with on/off, skip next and duplicate; editor for time, label, days and actions (script picker with icons, timing, per-action on/off, run now); warnings for unavailable scripts and missed *before* actions; reload prompt on conflicts; "Planned by others" list with the time of other users' upcoming actions; follows the HA theme; Dutch and English text from the HA user language; mobile layout; scripts load in the background so the list opens fast; saving an edited alarm activates it.
