# PlannedRun (derived)

**Purpose:** An [AlarmAction](alarm-action.md) bound to an [Occurrence](occurrence.md) with a concrete execution time. Computed by the scheduler, never edited by users.

## Attributes
| Attribute | Notes |
|---|---|
| `id` | `<occurrence_id>_<alarm_action_id>` |
| `run_at` | UTC |
| `status` | `waiting`, `running`, `done` |

## Rules
- **Exactly-once:** an id that already has an [ExecutionRecord](execution-record.md) is never run again.
- **Write-ahead:** the ExecutionRecord is committed with result `running` *before* the script is called, then updated with the outcome. A crash between call and update never causes a second run; a record still `running` after restart becomes `failed` ("interrupted").
- Rebuilt after every change, on add-on start, on Core reconnect and on time zone or away changes. `after_dismiss` runs are created only on the dismiss event.
- On add-on start, runs within the grace period are executed; older ones are recorded as `missed`. If HA Core is unreachable when a run is due, it is retried until the grace period ends.
- Before running: the owner must be active, the owner must not be away, the script must still carry the label, and the rate limit and duplicate suppression ([Settings](settings.md)) must allow it. Otherwise the run is recorded as `skipped` or `unavailable`.
- Started with `script.turn_on` over the HA websocket, without waiting for the script to finish. Failures are isolated per run.
- "Run now" uses the same executor and limits.

## Decisions
| Decision | Why |
|---|---|
| Planned-run id `<occurrence_id>_<action_id>` with a write-ahead record | Guarantees exactly-once across restarts and crashes |
| Script starts over the rate limit are **dropped** (recorded `skipped`, "rate limited"), not queued | Protects devices; late starts are rarely useful |
| Every timed action is executed by HA, never by the phone | Actions must run even if no phone is online |

## Requirements and features
- Requirements: FR-EXE-01, 05, 08, 10; TR-SCH-02..06, 09, TR-EXE-01, 03, 05, 06, TR-HA-05; SEC-04, 05
- [Phase 3](../../features/03-phase-scheduling-execution.md): 3.1, 3.2, 3.3, 3.6, 3.8, 3.10, 3.11, 3.12, 3.16, 3.17