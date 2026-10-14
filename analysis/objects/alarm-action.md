# AlarmAction

**Purpose:** Links one [ActionDefinition](action-definition.md) (a labeled HA script) to one [Alarm](alarm.md) with a timing. Belongs to exactly one alarm.

## Attributes
| Attribute | Type | Req | Notes |
|---|---|---|---|
| `id` | UUID | Yes | Server-generated when the action is added; **stable across edits** (a `PUT` that sends an existing id keeps it). Part of the [planned-run](planned-run.md) id |
| `script_entity_id` | string | Yes | e.g. `script.heat_bathroom` |
| `timing` | enum | Yes | `before`, `at`, `after`, `after_dismiss` |
| `offset_minutes` | int 1-720 | Cond. | Required for `before` and `after`; must be absent for others |
| `delay_minutes` | int 0-120 | No | Only for `after_dismiss`; default 0 |
| `enabled` | bool | Yes | Default true |

## Timing
| Timing | Planned run time | Dependency |
|---|---|---|
| `before` | occurrence − offset | none |
| `at` | occurrence | none |
| `after` | occurrence + offset | none (runs even if the user is still asleep) |
| `after_dismiss` | dismissal time + delay | needs a `dismissed` [event](occurrence-event.md) before the timeout |

`before`, `at` and `after` runs that have not started are cancelled when the occurrence is dismissed early. All runs are skipped while the owner is away ([Client](client.md)).

## Rules
- The script must exist and carry the action label at save time and at execution time. Otherwise status is `unavailable`.
- The same script may appear several times with different timings. Duplicate (script, timing, offset, delay) combinations within one alarm are rejected.
- `before` runs whose planned time is already past when the alarm is saved or edited are not executed for that occurrence. They are recorded as `missed` and the UI warns before saving.
- Execution passes **no parameters** to the script; the action is only a reference.
- Disabled actions are not planned and produce no history record.

## Decisions
| Decision | Why |
|---|---|
| A *before* action whose time is already past when saving is **skipped, logged as `missed`, and the UI warns** | Running it immediately would surprise users |
| Action ids stay stable across edits | The id is part of the planned-run id; a new id would break exactly-once |
| Actions are HA **scripts**, not automations | Scripts can be started on demand without triggers; users build them with the normal HA editor |

## Requirements and features
- Requirements: FR-ACT-02..06, FR-EXE-09, FR-UI-02, 03, 09; TR-DAT-05; AFR-ALM-02, 03
- [Phase 2](../../features/02-phase-data-model-api.md): 2.3
- [Phase 3](../../features/03-phase-scheduling-execution.md): 3.14
- [Phase 4](../../features/04-phase-dashboard-panel.md): 4.4, 4.5, 4.12
- [App A4](../../features/app/A4-editor-reporting.md): A4.2, A4.4