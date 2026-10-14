# ActionDefinition

**Purpose:** An action a user may choose: a Home Assistant script with the label `alarm_action` (configurable in [Settings](settings.md)). Not owned or stored by the add-on.

## Attributes
| Attribute | Source | Notes |
|---|---|---|
| `entity_id` | script entity | Unique key |
| `name` | script friendly name | |
| `icon` | script icon | Optional |
| `description` | script description | Optional |
| `available` | derived | False if the script disappeared or lost its label |

## Rules
- Read from the HA label and entity registries over the websocket, cached, and refreshed on registry-updated events, so a newly labeled script appears without a restart.
- Checked again right before every execution (label gate).
- **Trust:** labeling a script is an admin decision that makes it runnable by every user with alarm access. Only safe, idempotent scripts should be labeled; heating scripts should include their own auto-off.

## Decisions
| Decision | Why |
|---|---|
| Only scripts with the label can be run, via `script.turn_on`, without parameters | Users cannot start arbitrary services or inject data |

## Requirements and features
- Requirements: FR-ACT-01, 07; TR-HA-03, TR-EXE-02, 04; SEC-04
- [Phase 2](../../features/02-phase-data-model-api.md): 2.4
- [Phase 3](../../features/03-phase-scheduling-execution.md): 3.3, 3.11
- [Phase 4](../../features/04-phase-dashboard-panel.md): 4.7