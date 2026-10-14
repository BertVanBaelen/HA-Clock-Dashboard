# Phase 5: History and Logging

**Goal:** The system is observable: every run is visible in the panel, in the add-on log and on the event bus.

The dismiss flow (dismiss/snooze reports, `after_dismiss` runs, expiry) is planned in [phase 6](06-phase-app-addon-sync.md). The phone app is the only real source of these reports, so the flow is built together with the external API the app uses.

**Objects and components:** [ExecutionRecord](../analysis/objects/execution-record.md), [Dashboard add-on](../analysis/components/dashboard-addon.md)

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 5.1 | History endpoint and panel view | FR-UI-04, FR-EXE-06 | Results per alarm shown |
| 5.2 | History only for own alarms (also for admins) | SEC-03 | Matrix test covers history |
| 5.3 | Logging with `log_level` option and admin-only `GET /debug` (redacted) | TR-OBS-01, 02 | Debug output contains no tokens or time zones of other users |
| 5.4 | `alarm_actions_action_executed` event on the event bus via websocket `fire_event` | FR-EVT-04, TR-EVT-01 | Event visible in Developer Tools |
| 5.5 | Minimal event bus payloads (ids and timestamps) | SEC-06 | Verified |

There are no HA entities ([Dashboard add-on](../analysis/components/dashboard-addon.md)).

**Exit criteria:** After an alarm with a `before` and an `at` action has fired, both results show in the panel's history view, an `alarm_actions_action_executed` event appeared for each, and `GET /debug` shows the runs without secrets.
