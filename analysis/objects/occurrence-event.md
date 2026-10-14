# OccurrenceEvent

**Purpose:** A report from the app, the panel or the API that something happened to an [Occurrence](occurrence.md): snoozed, dismissed or timed out.

## Attributes
| Attribute | Type | Notes |
|---|---|---|
| `occurrence_id` | string | |
| `type` | enum | `snoozed`, `dismissed`, `timed_out` (the phone stopped ringing after its maximum duration without a dismissal) |
| `occurred_at` | datetime UTC | Client-reported time (base for the `after_dismiss` delay); clamped to `received_at` if later |
| `received_at` | datetime UTC | Server time |
| `source` | enum | `app`, `panel`, `api` |
| `user_id` | string | Reporter |

## Rules
- Idempotent: the first `dismissed` per occurrence wins; repeats are ignored with a success response.
- A `dismissed` event later than `scheduled_at + dismiss_timeout` is accepted for the record but does not trigger `after_dismiss` actions.
- **Stale late reports:** the `after_dismiss` run time is `occurred_at + delay`. If that is already more than the grace period in the past when the report arrives (phone was offline), the run is not executed and is recorded as `missed`.
- **Early dismissal:** a `dismissed` event before `scheduled_at` cancels pending `before`/`at`/`after` runs (recorded as `skipped`, "dismissed early"); `after_dismiss` runs are planned from `occurred_at`.
- `timed_out` expires the occurrence immediately: `after_dismiss` runs are recorded as `expired` without waiting for the timeout.
- `snoozed` does not shift scheduled actions; it is recorded and fired on the event bus.
- **Authorisation:** only the alarm owner can report events.
- **Plausibility:** `occurred_at` more than 2 minutes in the future, or more than 60 minutes before the scheduled time, is rejected. The occurrence must belong to an existing alarm and a time the scheduler could have produced.
- Each event is fired on the HA event bus (`alarm_actions_alarm_dismissed`, `alarm_actions_alarm_snoozed`) with ids and timestamps only.
- While the owner is away ([Client](client.md)), the phone sends no reports.

## Decisions
| Decision | Why |
|---|---|
| Dismiss/snooze events are part of the core API, not only of the app | The panel needs manual dismiss; "after dismissal" is a core feature |
| A dismissal **before** the alarm fires (up to 60 min early) acts as a skip for `before`/`at`/`after`; `after_dismiss` runs from the dismissal time | Waking up early should start the "after I get up" actions, not the wake-up ones |
| Late reports never start stale actions | A phone that was offline for hours must not suddenly heat the car |

## Requirements and features
- Requirements: FR-EVT-01..04, FR-EXE-02, 03, 07, 11, 13; TR-SCH-07, 08, TR-EVT-01; SEC-06; AFR-SYN-03
- [Phase 5](../../features/05-phase-dismiss-history.md): 5.2, 5.3, 5.4, 5.4a, 5.5, 5.6, 5.7, 5.10, 5.11
- [Phase 6](../../features/06-phase-app-addon-sync.md): 6.10
- [App A3](../../features/app/A3-alarm-engine.md): A3.4, A3.7
- [App A4](../../features/app/A4-editor-reporting.md): A4.5