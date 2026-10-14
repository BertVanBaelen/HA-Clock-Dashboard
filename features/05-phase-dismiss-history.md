# Phase 5: Dismiss Flow, History, and Events

**Goal:** "After dismissal" actions work, and the system is observable.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 5.1 | Occurrence state tracking | analysis §5 | States follow the state machine |
| 5.2 | Dismissed / snoozed / timed-out endpoints (idempotent) | FR-EVT-01, 02 | Duplicates ignored |
| 5.3 | `after_dismiss` runs with delay | FR-EXE-02, TR-SCH-07 | Runs after the dismissal plus delay |
| 5.4 | Dismissal timeout, `timed_out` report and `expired` result | FR-EXE-03 | Run expires and is logged |
| 5.4a | Early dismissal acts as skip for `before`/`at`/`after` | FR-EXE-11, TR-SCH-08, D22 | Early dismiss cancels pending runs and starts `after_dismiss` |
| 5.5 | Snooze logging (no schedule shift) | FR-EXE-07 | Recorded and on the event bus |
| 5.6 | Event bus events via websocket `fire_event` | FR-EVT-04, TR-EVT-01 | Events visible in Developer Tools |
| 5.7 | Manual dismiss from panel | FR-EVT-03 | Button in UI |
| 5.8 | History endpoint and panel view | FR-UI-04, FR-EXE-06 | Results per alarm shown |
| 5.9 | Logging with `log_level` option and admin-only `GET /debug` (redacted) | TR-OBS-01, 02 | Debug output contains no tokens or time zones of other users |
| 5.10 | Dismiss/snooze only by the alarm owner, for a real occurrence, within a plausible time window | SEC-06, SEC-03 | Foreign and implausible reports rejected |
| 5.11 | Minimal event bus payloads (ids and timestamps) | SEC-06 | Verified |
| 5.12 | History only for own alarms (admins: all, read-only) | SEC-03 | Matrix test covers history |

There are no HA entities (D31).

**Exit criteria:** Reporting a dismissal via the API starts the "after dismissal" scripts; ignoring it results in an `expired` record.