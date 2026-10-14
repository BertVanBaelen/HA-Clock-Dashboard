# ExecutionRecord

**Purpose:** History and audit of what the system did for each [PlannedRun](planned-run.md).

## Attributes
| Attribute | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `planned_run_id` | string | Unique; used for exactly-once |
| `alarm_id`, `alarm_action_id` | UUID | |
| `script_entity_id` | string | Snapshot at execution |
| `planned_at` | datetime UTC | |
| `executed_at` | datetime UTC | Null if not executed |
| `result` | enum | `running` (transient), `success`, `failed`, `skipped`, `missed`, `unavailable`, `expired` |
| `reason` | string | e.g. "occurrence skipped", "dismissed early", "user away", "rate limited", "duplicate start", "owner inactive", "grace period exceeded", error message |

## Result meanings
| Result | Meaning |
|---|---|
| `running` | Written just before the script call (write-ahead); replaced by the outcome |
| `success` | Script started (the service call was accepted, not that the script finished) |
| `failed` | Service call raised an error, or the run was interrupted by a crash |
| `skipped` | Not run on purpose: occurrence skipped, dismissed early, user away, rate limited, duplicate start, owner inactive |
| `missed` | Planned time passed beyond the grace period (e.g. the add-on or HA was down) |
| `unavailable` | Script deleted or label removed |
| `expired` | `after_dismiss` timeout reached with no dismissal |

## Rules
- Kept for 30 days or 200 entries **per user**, whichever is smaller, so one busy user cannot push out another user's history.
- Disabled actions produce no record.
- Visible per alarm to its owner only.
- Each record is fired on the HA event bus as `alarm_actions_action_executed` with ids, timestamps and `result` only, so users can build e.g. "notify on failure" automations.

## Decisions
| Decision | Why |
|---|---|
| History cap is per user | One user's busy alarms must not erase another user's history |
| No record for disabled actions | Avoids a daily `skipped` entry per disabled action |

## Requirements and features
- Requirements: FR-EXE-06, FR-UI-04; TR-DAT-03, 04, TR-EVT-01
- [Phase 3](../../features/03-phase-scheduling-execution.md): 3.7, 3.8
- [Phase 5](../../features/05-phase-history-logging.md): 5.1, 5.2, 5.4