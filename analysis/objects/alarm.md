# Alarm

**Purpose:** A user-defined wake-up moment with the actions linked to it.
Overview of all objects: [domain analysis](../domain-analysis.md).

## Attributes
| Attribute | Type | Req | Notes |
|---|---|---|---|
| `id` | UUID | Yes | Server-generated, immutable |
| `owner_user_id` | string | Yes | HA user id (from ingress or the validated HA token), immutable |
| `label` | string (<=100) | No | Display name |
| `time` | `HH:MM` local | Yes | In the HA time zone |
| `repeat_days` | set of `mon`..`sun` | No | Empty = one-time alarm |
| `enabled` | bool | Yes | Default true |
| `skip_next` | bool | Yes | Repeating alarms only. Skips only the next occurrence; resets after it passes |
| `snooze_minutes` | int 1-30 | No | Per-alarm snooze; null = [Settings](settings.md) `snooze_minutes`. Synced to the app |
| `actions` | list of [AlarmAction](alarm-action.md) | Yes | 0-20 |
| `revision` | int | Yes | Per-alarm counter, incremented on each change; used for optimistic locking only |
| `change_seq` | int | Yes | Store-wide sequence number at the last change; the sync cursor (see [Client](client.md)) |
| `created_at`, `updated_at` | datetime UTC | Yes | |
| `next_occurrence` | datetime UTC | Derived | Not stored; null if disabled or none |

## Rules
- A one-time alarm (no `repeat_days`) refers to the next time the clock reaches `time`. After it fires it sets `enabled = false`. The [occurrence](occurrence.md) that already fired is **not** cancelled: its pending `after` and `after_dismiss` runs still happen.
- `skip_next` applies to the first upcoming occurrence only. Once that occurrence's time passes, the flag resets to false. On a one-time alarm, skip is rejected (422); disable it instead.
- Changing `time`, `repeat_days`, `enabled`, `snooze_minutes` or `actions` triggers rescheduling and bumps `revision` and `change_seq`.
- Changes made by the system (skip reset, one-time auto-disable, owner-inactive disable) also bump `revision` and `change_seq`, so clients learn about them through sync.
- **Edits during an active occurrence** (fired or snoozed): changes to `time` and `repeat_days` apply from the next occurrence. The active occurrence keeps its pending runs, except runs of actions that were removed or disabled, which are cancelled.
- Deleting an alarm cancels all its pending planned runs, including those of active occurrences, and leaves a tombstone for sync. History entries are kept until they expire.
- Caps: at most 50 alarms per user, 20 actions per alarm, 1000 actions in total.
- **Ownership:** only the owner can change an alarm. Admins can view it, but writes return 403. For any other user it behaves as non-existent (404).
- If the owner is deleted or deactivated, the alarm is disabled and its runs are recorded as `skipped` ("owner inactive"). If the owner is reactivated, the alarms **stay disabled** until the owner turns them on again.
- Alarm times and labels are personal data (they reveal sleeping patterns).

## States
```
Disabled ⇄ Enabled(waiting) → Enabled(skipping next) → Enabled(waiting)
Enabled(one-time) → fired → Disabled   (fired occurrence completes its runs)
```

## Decisions
| Decision | Why |
|---|---|
| Alarms are **per user**, visible to the owner (and read-only to admins). Shared household alarms are backlog | Alarms are personal; scripts are shared |
| Admins have **view-only** access to other users' alarms (no edit, delete, skip, dismiss or run); writes return 403 | Alarms are personal; an admin can still deactivate a user to stop their alarms |
| Foreign or unknown alarms return **404** | Prevents id enumeration |
| `snooze_minutes` is stored on the alarm and synced; the alarm **sound** stays local on the phone | Snooze is part of the alarm; sounds are device files |
| Alarms disabled because the owner was deactivated **stay disabled** after reactivation | No surprise alarms; the owner re-enables them |
| Weekdays are `mon`..`sun`, never numbers | Python, JavaScript and Kotlin number weekdays differently |
| `revision` is only for optimistic locking; sync uses the store-wide `change_seq` | Per-alarm counters cannot be compared across alarms |

## Requirements and features
- Requirements: FR-ALM-01..09, FR-EXE-14, FR-ACC-01..05, FR-UI-01, 02; TR-DAT-05, 06, TR-API-02, 07, 08; SEC-03, 05; AFR-ALM-01
- [Phase 2](../../features/02-phase-data-model-api.md): 2.1, 2.2, 2.3, 2.5, 2.7, 2.8, 2.10, 2.14
- [Phase 3](../../features/03-phase-scheduling-execution.md): 3.2, 3.4, 3.5, 3.13, 3.15
- [Phase 4](../../features/04-phase-dashboard-panel.md): 4.2, 4.3, 4.6, 4.8, 4.11, 4.16, 4.17
- [App A4](../../features/app/A4-editor-reporting.md): A4.1, A4.3, A4.6