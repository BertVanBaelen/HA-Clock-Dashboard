# Changelog

## 0.1.0

- First release: add-on skeleton, connection to Home Assistant Core, database with hot backup.
- Alarm API under `/api/v1` (owner-only, admins read-only), scripts with the `alarm_action` label as actions, contract in `docs/openapi.yaml`.
- Scheduler: before/at/after actions start their scripts at the planned time, exactly once, also across restarts (grace period) and Core outages; skip next, one-time alarms, rate limits, execution history and "run now".
