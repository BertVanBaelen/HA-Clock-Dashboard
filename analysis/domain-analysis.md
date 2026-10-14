# Functional Domain Analysis

This document describes every domain object of the integration: purpose, attributes, rules, lifecycle, and relations. It is the basis for the data model and the API contract.

## 1. Overview

```
User 1───* Alarm 1───* AlarmAction *───1 ActionDefinition (HA script)
              │              │
              │              └──* PlannedRun ──1 ExecutionRecord
              │
              └──* Occurrence 1───* OccurrenceEvent (snoozed / dismissed)

Settings (global)        Client (app instance) *───1 User
```

| Object | Persisted | Owner of lifecycle |
|---|---|---|
| Alarm | Yes | User |
| AlarmAction | Yes (embedded in Alarm) | User |
| ActionDefinition | No (read from HA) | Home Assistant |
| Occurrence | Partly (state of current/recent) | Scheduler |
| OccurrenceEvent | Yes (short-lived) | Client / User |
| PlannedRun | No (derived) | Scheduler |
| ExecutionRecord | Yes (capped history) | Executor |
| Settings | Yes | Admin |
| Client (app instance) | Yes (optional, minimal) | App |

---

## 2. Alarm

**Purpose:** A user-defined wake-up moment with the actions linked to it.

| Attribute | Type | Req | Notes |
|---|---|---|---|
| `id` | UUID | Yes | Server-generated, immutable |
| `owner_user_id` | string | Yes | HA user id, immutable |
| `label` | string (<=100) | No | Display name |
| `time` | `HH:MM` local | Yes | In the HA time zone |
| `repeat_days` | set of 0-6 (Mon-Sun) | No | Empty = one-time alarm |
| `enabled` | bool | Yes | Default true |
| `skip_next` | bool | Yes | Skips only the next occurrence; resets after it passes |
| `actions` | list of AlarmAction | Yes | 0-20 |
| `revision` | int | Yes | Incremented on each change; used for optimistic locking and sync |
| `created_at`, `updated_at` | datetime UTC | Yes | |
| `next_occurrence` | datetime UTC | Derived | Not stored; null if disabled or none |

**Rules**
- A one-time alarm (no `repeat_days`) refers to the next time the clock reaches `time`. After it fires it sets `enabled = false`.
- `skip_next` applies to the first upcoming occurrence only. Once that occurrence's time passes, the flag resets to false.
- Changing `time`, `repeat_days`, `enabled`, or `actions` triggers rescheduling and bumps `revision`.
- Deleting an alarm cancels all its pending planned runs. History entries are kept until they expire.
- A user may have at most 50 alarms and an alarm at most 20 actions (integration-wide cap: 1000 actions).
- **Ownership:** only the owner and admins can see or change an alarm. For anyone else it behaves as non-existent (404).
- If the owner is deleted or deactivated, the alarm is disabled and its runs are recorded as `skipped` ("owner inactive").
- Alarm times and labels are personal data (they reveal sleeping patterns).

**States**
```
Disabled ⇄ Enabled(waiting) → Enabled(skipping next) → Enabled(waiting)
Enabled(one-time) → fired → Disabled
```

---

## 3. AlarmAction

**Purpose:** Links one ActionDefinition to one Alarm with a timing. Belongs to exactly one alarm.

| Attribute | Type | Req | Notes |
|---|---|---|---|
| `id` | UUID | Yes | Unique within alarm; part of the planned-run id |
| `script_entity_id` | string | Yes | e.g. `script.heat_bathroom` |
| `timing` | enum | Yes | `before`, `at`, `after`, `after_dismiss` |
| `offset_minutes` | int 1-720 | Cond. | Required for `before` and `after`; must be absent for others |
| `delay_minutes` | int 0-120 | No | Only for `after_dismiss`; default 0 |
| `enabled` | bool | Yes | Default true |

**Timing semantics**
| Timing | Planned run time | Dependency |
|---|---|---|
| `before` | occurrence − offset | none |
| `at` | occurrence | none |
| `after` | occurrence + offset | none (runs even if the user is still asleep) |
| `after_dismiss` | dismissal time + delay | needs a `dismissed` event before the timeout |

**Rules**
- The script must exist and carry the `alarm_action` label at save time and at execution time. Otherwise status is `unavailable`.
- The same script may appear several times with different timings.
- Duplicate (script, timing, offset) combinations within one alarm are rejected.
- `before` runs whose planned time is already past when the alarm is saved or edited are not executed for that occurrence. They are recorded as `missed` and the UI warns before saving (decision D3).
- Execution passes **no parameters** to the script; the action is only a reference.

---

## 4. ActionDefinition

**Purpose:** An action the user is allowed to choose. It is a Home Assistant script with the label `alarm_action`. Not owned or stored by this integration.

| Attribute | Source | Notes |
|---|---|---|
| `entity_id` | script entity | Unique key |
| `name` | script friendly name | |
| `icon` | script icon | Optional |
| `description` | script description | Optional |
| `available` | derived | False if the script disappeared or lost its label |

**Rules**
- The list is read live from the HA registries on every request.
- Adding a new script with the label makes it appear immediately; no restart or update.
- **Trust:** labeling a script is an admin decision that makes it runnable by every user with alarm access. Only safe, idempotent scripts should be labeled; heating scripts should include their own auto-off.

---

## 5. Occurrence

**Purpose:** One concrete instance of an alarm on a specific date and time. The unit to which skip, snooze, dismiss, and planned runs attach.

| Attribute | Type | Notes |
|---|---|---|
| `id` | string | `<alarm_id>:<ISO UTC datetime>`, deterministic |
| `alarm_id` | UUID | |
| `scheduled_at` | datetime UTC | |
| `status` | enum | `pending`, `skipped`, `fired`, `snoozed`, `dismissed`, `expired` |

**State machine**
```
pending ──skip──────────────► skipped
pending ──time reached──────► fired
fired ──snoozed event──────► snoozed ──dismissed event──► dismissed
fired ──dismissed event─────► dismissed
fired/snoozed ──timeout─────► expired
```

**Rules**
- Only the next occurrence per alarm exists as `pending`. Future occurrences are computed, not stored.
- Occurrences in a final state (`skipped`, `dismissed`, `expired`) are kept only for history.
- The deterministic id allows an offline app to report events for an occurrence the server has not yet heard of, as long as the alarm and time are consistent with the server's computation.

---

## 6. OccurrenceEvent

**Purpose:** A report from a client or user that something happened to an occurrence.

| Attribute | Type | Notes |
|---|---|---|
| `occurrence_id` | string | |
| `type` | enum | `snoozed`, `dismissed` |
| `occurred_at` | datetime UTC | Client-reported time (used for `after_dismiss` delay base) |
| `received_at` | datetime UTC | Server time |
| `source` | enum | `app`, `panel`, `api` |
| `user_id` | string | Reporter |

**Rules**
- Idempotent: the first `dismissed` per occurrence wins; repeats are ignored with a success response.
- A `dismissed` event whose `occurred_at` is older than the dismissal timeout is accepted for the record, but does not trigger `after_dismiss` actions.
- `snoozed` has no effect on scheduled actions (FR-EXE-07); it is recorded and fired on the event bus.
- **Authorisation:** only the alarm owner or an admin can report events.
- **Plausibility:** `occurred_at` more than 2 minutes in the future, or more than 60 minutes before the scheduled time, is rejected. The occurrence must belong to an existing alarm and a time the scheduler could have produced.
- Event bus payloads contain ids and timestamps only.

---

## 7. PlannedRun (derived)

**Purpose:** An AlarmAction bound to an Occurrence with a concrete execution time. Computed by the scheduler, never edited by users.

| Attribute | Notes |
|---|---|
| `id` | `<occurrence_id>:<alarm_action_id>` |
| `run_at` | UTC |
| `status` | `waiting`, `running`, `done` |

**Rules**
- Exactly-once: an id that already has an ExecutionRecord is never run again.
- Rebuilt after every change and on startup. `after_dismiss` runs are created only on the dismiss event.

---

## 8. ExecutionRecord

**Purpose:** History and audit of what the system did.

| Attribute | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `planned_run_id` | string | Unique; used for exactly-once |
| `alarm_id`, `alarm_action_id` | UUID | |
| `script_entity_id` | string | Snapshot at execution |
| `planned_at` | datetime UTC | |
| `executed_at` | datetime UTC | Null if not executed |
| `result` | enum | `success`, `failed`, `skipped`, `missed`, `unavailable`, `expired` |
| `reason` | string | e.g. "occurrence skipped", "grace period exceeded", error message |

**Rules**
- `success` means the service call was accepted by HA, not that the script finished.
- Kept for 30 days or 200 entries, whichever is smaller.
- Visible per alarm to its owner; admins see all.

**Result meanings**
| Result | Meaning |
|---|---|
| `success` | Script started |
| `failed` | Service call raised an error |
| `skipped` | Occurrence skipped or action disabled |
| `missed` | Planned time passed beyond grace period (e.g. HA was down) |
| `unavailable` | Script deleted or label removed |
| `expired` | `after_dismiss` timeout reached with no dismissal |

---

## 9. Settings

| Attribute | Default | Notes |
|---|---|---|
| `snooze_minutes` | 9 | Default for clients; the app may override locally |
| `dismiss_timeout_minutes` | 60 | After this, `after_dismiss` actions expire |
| `grace_period_minutes` | 5 | Restart recovery window |
| `action_label` | `alarm_action` | Label used to find scripts |
| `history_days` / `history_max` | 30 / 200 | |
| `max_script_starts_per_minute` | 20 | Integration-wide rate limit |
| `min_script_interval_seconds` | 30 | Duplicate-start suppression per script |

**Rules:** Readable by every user, writable by admins only. Changing values reschedules everything.

---

## 10. Client (app instance)

**Purpose:** Optional record of an installed Android app, for sync bookkeeping and for showing connected devices.

| Attribute | Type | Notes |
|---|---|---|
| `id` | UUID | Generated by the app |
| `user_id` | string | HA user, from the session |
| `name` | string | e.g. device name |
| `app_version` | string | |
| `last_sync_revision` | int | Last `revision` the client confirmed |
| `last_seen_at` | datetime UTC | |

**Rules**
- Not required for the API to work; the app functions without registering.
- A user only sees their own clients (admins all). Removing a client does not revoke its login; tokens are revoked in the HA user profile.
- The app keeps its own **local copy** of the user's alarms and sync state (see [app requirements](../requirements/app-requirements.md)). The server is the source of truth for actions and scheduling; the phone is the source of truth for ringing.

**Sync rules**
- `GET /sync?since=N` returns alarms with `revision > N` plus ids deleted since then.
- Conflicts: a write carrying an old `revision` gets 409; the app refetches and shows the user the current version.
- If the phone was offline, dismiss/snooze reports are queued and sent later (accepted for the record; see section 6 for the late-report rule).

## 11. Cross-cutting business rules
1. **Phone independence:** every timed action is executed by HA. The phone only reports events.
2. **Label gate:** a script can only be run if it carries the action label at execution time.
3. **Time zone:** all schedule calculations use the HA time zone; storage and API use UTC for instants and local `HH:MM` for alarm times.
4. **DST:** a time that does not exist (spring forward) fires at the next valid moment; an ambiguous time (fall back) fires at its first occurrence.
5. **Ownership:** users only modify their own alarms; admins may view and modify all.
6. **Concurrency:** updates carry a `revision`; a mismatch returns 409 and the client must refetch.
7. **Own alarms only:** users can only see and change their own alarms; others get "not found".
8. **Login everywhere:** every endpoint requires a Home Assistant login, so exposing HA to the internet for the app adds no open logic.
9. **Script gate:** the integration only starts labeled scripts, with no parameters from users.
10. **Safety:** exactly-once execution, rate limiting and the missed-run policy prevent repeated or stale device actions.
11. **Dashboard without app:** the dashboard works fully without the Android app; the app is an optional extra downloaded from the same integration.

Detailed security requirements: [../requirements/security-requirements.md](../requirements/security-requirements.md).

## 12. Example alarm (JSON)
```json
{
  "id": "6f0c1a52-4c5e-4a56-9d4b-2b1d6d6a9b10",
  "owner_user_id": "a1b2c3",
  "label": "Work",
  "time": "06:45",
  "repeat_days": [0, 1, 2, 3, 4],
  "enabled": true,
  "skip_next": false,
  "revision": 7,
  "next_occurrence": "2026-10-15T04:45:00Z",
  "actions": [
    { "id": "e1", "script_entity_id": "script.heat_bathroom", "timing": "before", "offset_minutes": 10, "enabled": true },
    { "id": "e2", "script_entity_id": "script.heat_car", "timing": "after_dismiss", "delay_minutes": 20, "enabled": true }
  ]
}
```

## 13. Access control

Who may do what. "Other user" is any HA user who is not the owner.

| Operation | Owner | Other user | Admin | Not logged in |
|---|---|---|---|---|
| List alarms | Own only | Own only (never sees the owner's) | All | Rejected (401) |
| Read one alarm | Yes | Not found (404) | Yes | Rejected |
| Create alarm | Yes (becomes owner) | n/a | Yes (owner = self) | Rejected |
| Edit or delete alarm | Yes | Not found (404) | Yes (see open point) | Rejected |
| Skip, unskip | Yes | Not found (404) | Yes | Rejected |
| Report dismissed or snoozed | Yes | Not found (404) | Yes | Rejected |
| Run own action now | Yes | Not found (404) | Yes | Rejected |
| View history | Own alarms | Own alarms | All | Rejected |
| List available actions (labeled scripts) | Yes | Yes | Yes | Rejected |
| Sync own alarms (`/sync`) | Own only | Own only | Own only | Rejected |
| Register an app instance | Yes (own) | n/a | Yes (own) | Rejected |
| Request APK download link | Any logged-in user | Any logged-in user | Yes | Rejected |
| Read settings | Yes | Yes | Yes | Rejected |
| Change settings | No | No | Yes | Rejected |

**Rules**
- The owner is fixed when the alarm is created and cannot be changed or supplied by the client; it always comes from the logged-in session.
- "Not found" instead of "forbidden" for other people's alarms, so ids cannot be probed.
- Alarms of a deleted or deactivated user are disabled.
- One central check is used by every route; the default is deny.

**Open point:** should admins be able to *edit* other users' alarms, or only view and delete them? Currently: full access.

## 14. Internet exposure

The app must work outside the home, so Home Assistant itself is reachable on a public URL. The integration must not add anything open to the internet.

| Part | Reachable without login? | Notes |
|---|---|---|
| API endpoints (`/api/alarm_actions/...`) | **No**, HA login required | Same login as everything in HA; used by panel and app |
| Scheduler, executor | Not reachable | Runs inside HA only |
| Webhooks, public URLs | **None** | |
| APK download link | Only via a ~60 second signed link that a logged-in user requests | The APK holds no secrets or server address |
| Cloud services, outbound calls | **None** | Nothing leaves HA |
| Panel JavaScript bundle | Yes, as a static file | HA does not protect static files; the bundle contains only UI code, no data |

**How the app gets access from outside:** it logs in with the user's HA account (OAuth through HA), receives a token, and calls the same API. A lost phone's token can be revoked in the HA user profile. Use a non-admin user for each phone.

