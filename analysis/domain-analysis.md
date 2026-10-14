# Functional Domain Analysis

This document describes every domain object of the Alarm Actions add-ons: purpose, attributes, rules, lifecycle, and relations. It is the basis for the data model and the API contract.

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
| Occurrence | Partly (active and recent) | Scheduler |
| OccurrenceEvent | Yes (short-lived) | Client / User |
| PlannedRun | No (derived) | Scheduler |
| ExecutionRecord | Yes (capped history) | Executor |
| Settings | Yes | Admin |
| Client (app instance) | Yes (minimal; needed for away detection) | App |

---

## 2. Alarm

**Purpose:** A user-defined wake-up moment with the actions linked to it.

| Attribute | Type | Req | Notes |
|---|---|---|---|
| `id` | UUID | Yes | Server-generated, immutable |
| `owner_user_id` | string | Yes | HA user id, immutable |
| `label` | string (<=100) | No | Display name |
| `time` | `HH:MM` local | Yes | In the HA time zone |
| `repeat_days` | set of `mon`..`sun` | No | Empty = one-time alarm. Day names avoid numbering differences between Python, JS and Kotlin |
| `enabled` | bool | Yes | Default true |
| `skip_next` | bool | Yes | Repeating alarms only. Skips only the next occurrence; resets after it passes |
| `snooze_minutes` | int 1-30 | No | Per-alarm snooze; null = `Settings.snooze_minutes`. Synced to the app (D23) |
| `actions` | list of AlarmAction | Yes | 0-20 |
| `revision` | int | Yes | Per-alarm counter, incremented on each change; used for optimistic locking only |
| `change_seq` | int | Yes | Store-wide sequence number at the last change; used as the sync cursor (D25) |
| `created_at`, `updated_at` | datetime UTC | Yes | |
| `next_occurrence` | datetime UTC | Derived | Not stored; null if disabled or none |

**Rules**
- A one-time alarm (no `repeat_days`) refers to the next time the clock reaches `time`. After it fires it sets `enabled = false`. The occurrence that already fired is **not** cancelled: its pending `after` and `after_dismiss` runs still happen.
- `skip_next` applies to the first upcoming occurrence only. Once that occurrence's time passes, the flag resets to false. On a one-time alarm, skip is rejected (422); disable it instead.
- Changing `time`, `repeat_days`, `enabled`, or `actions` triggers rescheduling and bumps `revision`.
- Changes made by the system (skip reset, one-time auto-disable, owner-inactive disable) also bump `revision` and `change_seq`, so clients learn about them through sync.
- **Edits during an active occurrence** (fired or snoozed): changes to `time` and `repeat_days` apply from the next occurrence. The active occurrence keeps its pending runs, except runs of actions that were removed or disabled, which are cancelled.
- Deleting an alarm cancels all its pending planned runs, including those of active occurrences. History entries are kept until they expire.
- A user may have at most 50 alarms and an alarm at most 20 actions (cap across all users: 1000 actions).
- **Ownership:** only the owner can change an alarm; admins can view it (D19). For anyone else it behaves as non-existent (404).
- If the owner is deleted or deactivated, the alarm is disabled and its runs are recorded as `skipped` ("owner inactive"). If the owner is reactivated, the alarms stay disabled until the owner turns them on again (D24).
- Alarm times and labels are personal data (they reveal sleeping patterns).

**States**
```
Disabled ⇄ Enabled(waiting) → Enabled(skipping next) → Enabled(waiting)
Enabled(one-time) → fired → Disabled   (fired occurrence completes its runs)
```

---

## 3. AlarmAction

**Purpose:** Links one ActionDefinition to one Alarm with a timing. Belongs to exactly one alarm.

| Attribute | Type | Req | Notes |
|---|---|---|---|
| `id` | UUID | Yes | Server-generated when the action is added; **stable across edits** (a `PUT` that sends an existing id keeps it). Part of the planned-run id, so a new id would break exactly-once |
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

`before`, `at` and `after` runs that have not started yet are cancelled when the occurrence is dismissed early (before it fires, D22). All runs are skipped while the owner is away (section 11, rule 12).

**Rules**
- The script must exist and carry the `alarm_action` label at save time and at execution time. Otherwise status is `unavailable`.
- The same script may appear several times with different timings.
- Duplicate (script, timing, offset, delay) combinations within one alarm are rejected.
- `before` runs whose planned time is already past when the alarm is saved or edited are not executed for that occurrence. They are recorded as `missed` and the UI warns before saving (decision D3).
- Execution passes **no parameters** to the script; the action is only a reference.

---

## 4. ActionDefinition

**Purpose:** An action the user is allowed to choose. It is a Home Assistant script with the label `alarm_action`. Not owned or stored by the add-on.

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
| `id` | string | `<alarm_id>_<YYYYMMDDTHHMMSSZ>` (scheduled time in UTC), deterministic and URL-safe. Example: `6f0c1a52-…-2b1d6d6a9b10_20261015T044500Z` |
| `alarm_id` | UUID | |
| `scheduled_at` | datetime UTC | |
| `status` | enum | `pending`, `skipped`, `fired`, `snoozed`, `dismissed`, `expired` |

**State machine**
```
pending ──skip──────────────────► skipped
pending ──dismissed event (early)► dismissed   (before/at/after runs cancelled, D22)
pending ──time reached──────────► fired
fired ──snoozed event──────────► snoozed ──dismissed event──► dismissed
fired ──dismissed event─────────► dismissed
fired/snoozed ──timeout or timed_out event──► expired
```

**Rules**
- At most one occurrence per alarm is `pending` (the next one). Future occurrences beyond that are computed, not stored.
- An alarm can have **several active occurrences at once**: an earlier occurrence can still be `fired`/`snoozed` (dismiss timeout up to 60 min) or have `after` runs pending (offset up to 720 min) while the next occurrence's `before` runs are already due. The scheduler tracks planned runs per occurrence, not per alarm.
- Occurrences in a final state (`skipped`, `dismissed`, `expired`) are kept only for history.
- An early dismissal (before `scheduled_at`) is allowed up to 60 minutes before the scheduled time; earlier than that, the user should use skip.
- The deterministic id allows an offline app to report events for an occurrence the server has not yet heard of, as long as the alarm and time are consistent with the server's computation.

---

## 6. OccurrenceEvent

**Purpose:** A report from a client or user that something happened to an occurrence.

| Attribute | Type | Notes |
|---|---|---|
| `occurrence_id` | string | |
| `type` | enum | `snoozed`, `dismissed`, `timed_out` (the phone stopped ringing after its maximum duration without a dismissal) |
| `occurred_at` | datetime UTC | Client-reported time (used for `after_dismiss` delay base); clamped to `received_at` if later |
| `received_at` | datetime UTC | Server time |
| `source` | enum | `app`, `panel`, `api` |
| `user_id` | string | Reporter |

**Rules**
- Idempotent: the first `dismissed` per occurrence wins; repeats are ignored with a success response.
- A `dismissed` event whose `occurred_at` is later than `scheduled_at + dismiss_timeout` is accepted for the record, but does not trigger `after_dismiss` actions.
- **Stale late reports:** the `after_dismiss` run time is `occurred_at + delay`. If that time is already more than the grace period in the past when the report arrives (phone was offline), the run is not executed and is recorded as `missed`.
- A `dismissed` event before `scheduled_at` is an early dismissal (D22): pending `before`/`at`/`after` runs are cancelled (recorded as `skipped`, "dismissed early") and `after_dismiss` runs are planned from `occurred_at`.
- `timed_out` expires the occurrence immediately: `after_dismiss` runs are recorded as `expired` without waiting for the dismiss timeout.
- `snoozed` has no effect on scheduled actions (FR-EXE-07); it is recorded and fired on the event bus.
- **Authorisation:** only the alarm owner can report events (admins have view-only access to other users' alarms, D19).
- **Plausibility:** `occurred_at` more than 2 minutes in the future, or more than 60 minutes before the scheduled time, is rejected. The occurrence must belong to an existing alarm and a time the scheduler could have produced.
- Event bus payloads contain ids and timestamps only.

---

## 7. PlannedRun (derived)

**Purpose:** An AlarmAction bound to an Occurrence with a concrete execution time. Computed by the scheduler, never edited by users.

| Attribute | Notes |
|---|---|
| `id` | `<occurrence_id>_<alarm_action_id>` |
| `run_at` | UTC |
| `status` | `waiting`, `running`, `done` |

**Rules**
- Exactly-once: an id that already has an ExecutionRecord is never run again.
- **Write-ahead:** the ExecutionRecord is written with result `running` and saved immediately (not debounced) *before* the script is called, then updated with the outcome. A crash between the call and the save therefore never causes a second run; a record still `running` after restart becomes `failed` ("interrupted").
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
| `result` | enum | `running` (transient), `success`, `failed`, `skipped`, `missed`, `unavailable`, `expired` |
| `reason` | string | e.g. "occurrence skipped", "dismissed early", "user away", "rate limited", "duplicate start", "owner inactive", "grace period exceeded", error message |

**Rules**
- `success` means the service call was accepted by HA, not that the script finished.
- Kept for 30 days or 200 entries **per user**, whichever is smaller, so one busy user cannot push out another user's history.
- Disabled actions produce no record (avoids a daily `skipped` entry per disabled action).
- Visible per alarm to its owner; admins see all.

**Result meanings**
| Result | Meaning |
|---|---|
| `running` | Written just before the script call (write-ahead); replaced by the outcome |
| `success` | Script started |
| `failed` | Service call raised an error, or the run was interrupted by a crash |
| `skipped` | Not run on purpose: occurrence skipped, dismissed early, user away, rate limited, duplicate start, owner inactive |
| `missed` | Planned time passed beyond grace period (e.g. HA was down) |
| `unavailable` | Script deleted or label removed |
| `expired` | `after_dismiss` timeout reached with no dismissal |

---

## 9. Settings

| Attribute | Default | Notes |
|---|---|---|
| `snooze_minutes` | 9 | Default when an alarm has no own `snooze_minutes` |
| `dismiss_timeout_minutes` | 60 | After this, `after_dismiss` actions expire |
| `grace_period_minutes` | 5 | Restart recovery window, and limit for stale late reports |
| `action_label` | `alarm_action` | Label used to find scripts |
| `history_days` / `history_max` | 30 / 200 | `history_max` is per user |
| `max_script_starts_per_minute` | 20 | Rate limit across all users. Starts beyond it are **dropped** and recorded as `skipped` ("rate limited") (D21) |
| `min_script_interval_seconds` | 30 | Duplicate-start suppression per script; a suppressed start is recorded as `skipped` ("duplicate start") |

**Rules:** Settings are options of the dashboard add-on and can only be changed in its configuration page, so only admins can change them (D30). Every user can read them through the API. Saving options restarts the add-on; the scheduler then recalculates everything, and the grace period covers runs due during the restart.

---

## 10. Client (app instance)

**Purpose:** Record of an installed Android app, for sync bookkeeping, showing connected devices, and detecting that the owner is away (different time zone).

| Attribute | Type | Notes |
|---|---|---|
| `id` | UUID | Generated by the app |
| `user_id` | string | HA user, from the session |
| `name` | string | e.g. device name |
| `app_version` | string | |
| `time_zone` | string (IANA) | The phone's current time zone, reported on every sync |
| `last_sync_seq` | int | Last `change_seq` the client confirmed |
| `last_seen_at` | datetime UTC | |

**Rules**
- The API works without a registered client, but the Android app always registers and updates its record on every sync (needed for away detection).
- **Away:** the owner is *away* when the `time_zone` of their most recently seen client differs from the HA time zone. See section 11, rule 12.
- A user only sees their own clients (admins all). Removing a client does not revoke its login; tokens are revoked in the HA user profile.
- The app keeps its own **local copy** of the user's alarms and sync state (see [app requirements](../requirements/app-requirements.md)). The server is the source of truth for actions and scheduling; the phone is the source of truth for ringing.

**Sync rules**
- Every change to any alarm (including system changes and deletions) takes the next value of one store-wide `change_seq`. Per-alarm `revision` values cannot be compared across alarms, so they are not used as a sync cursor.
- `GET /sync?since=N` returns alarms with `change_seq > N`, ids deleted since then (tombstones), the current `change_seq`, and the settings the app needs (default snooze).
- Conflicts: a write carrying an old `revision` gets 409; the app refetches and shows the user the current version.
- If the phone was offline, dismiss/snooze reports are queued and sent later (accepted for the record; see section 6 for the late-report rule). While away, the phone sends no dismiss/snooze reports.
- Background sync on Android runs at most about every 15 minutes, so a change made in the panel can take that long to reach the phone. The panel shows each phone's last sync time.

## 11. Cross-cutting business rules
1. **Phone independence:** every timed action is executed by HA. The phone only reports events.
2. **Label gate:** a script can only be run if it carries the action label at execution time.
3. **Time zone:** all server schedule calculations use the HA time zone; storage and API use UTC for instants and local `HH:MM` for alarm times. The phone rings at the local wall-clock `time` in its own time zone (rule 12).
4. **DST:** a time that does not exist (spring forward) fires at the next valid moment; an ambiguous time (fall back) fires at its first occurrence. Server and app follow the same published test vectors.
5. **Ownership:** users only modify their own alarms; admins may view all, but not change them (D19).
6. **Concurrency:** updates carry a `revision`; a mismatch returns 409 and the client must refetch.
7. **Own alarms only:** users can only see and change their own alarms; others get "not found".
8. **Login everywhere:** every request reaches the add-on through HA ingress and therefore requires a Home Assistant login, so exposing HA to the internet for the app adds no open logic.
9. **Script gate:** the add-on only starts labeled scripts, with no parameters from users.
10. **Safety:** exactly-once execution, rate limiting and the missed-run policy prevent repeated or stale device actions.
11. **Dashboard without app:** the dashboard add-on works fully on its own; the Android app is optional and is offered only when the second add-on ("Alarm Actions App") is installed (D32).
12. **Away (D20):** when the owner's most recently seen phone reports a time zone different from HA's, the owner is treated as not at home. The phone rings at its local wall-clock time; HA runs **no** actions for that owner's occurrences and records them as `skipped` ("user away"). The panel shows the away state. Normal behaviour resumes as soon as the phone reports the HA time zone again. Known limit: a phone that has not synced since travelling is not detected.

Detailed security requirements: [../requirements/security-requirements.md](../requirements/security-requirements.md).

## 12. Example alarm (JSON)
```json
{
  "id": "6f0c1a52-4c5e-4a56-9d4b-2b1d6d6a9b10",
  "owner_user_id": "a1b2c3",
  "label": "Work",
  "time": "06:45",
  "repeat_days": ["mon", "tue", "wed", "thu", "fri"],
  "enabled": true,
  "skip_next": false,
  "snooze_minutes": null,
  "revision": 7,
  "change_seq": 1342,
  "next_occurrence": "2026-10-15T04:45:00Z",
  "actions": [
    { "id": "0b5e9c1e-7d2a-4f3b-9a51-3c2f7e8d1a01", "script_entity_id": "script.heat_bathroom", "timing": "before", "offset_minutes": 10, "enabled": true },
    { "id": "5a7c2d90-1e4b-4c8f-8f0a-9d6b3e2c4f02", "script_entity_id": "script.heat_car", "timing": "after_dismiss", "delay_minutes": 20, "enabled": true }
  ]
}
```

## 13. Access control

Who may do what. "Other user" is any HA user who is not the owner. The "Admin" column is an admin acting on **another user's** alarm; on their own alarms admins are owners.

| Operation | Owner | Other user | Admin | Not logged in |
|---|---|---|---|---|
| List alarms | Own only | Own only (never sees the owner's) | All | Rejected (401) |
| Read one alarm | Yes | Not found (404) | Yes | Rejected |
| Create alarm | Yes (becomes owner) | n/a | Own only (owner = self) | Rejected |
| Edit or delete alarm | Yes | Not found (404) | Forbidden (403) | Rejected |
| Skip, unskip | Yes | Not found (404) | Forbidden (403) | Rejected |
| Report dismissed, snoozed or timed out | Yes | Not found (404) | Forbidden (403) | Rejected |
| Run action now | Yes | Not found (404) | Forbidden (403) | Rejected |
| View history | Own alarms | Own alarms | All | Rejected |
| List available actions (labeled scripts) | Yes | Yes | Yes | Rejected |
| Sync own alarms (`/sync`) | Own only | Own only | Own only | Rejected |
| Register an app instance (incl. time zone) | Yes (own) | n/a | Yes (own) | Rejected |
| Download the APK (when the app add-on runs) | Any logged-in user | Any logged-in user | Yes | Rejected |
| Read settings | Yes | Yes | Yes | Rejected |
| Change settings (add-on configuration page) | No | No | Yes | Rejected |

**Rules**
- The owner is fixed when the alarm is created and cannot be changed or supplied by the client; it always comes from the HA user that ingress identifies.
- "Not found" instead of "forbidden" for other people's alarms, so ids cannot be probed. Admins get 403 on writes because they can already see the alarm.
- Admins have **view-only** access to other users' alarms (D19). To stop another user's alarms, an admin deactivates that HA user (alarms are then disabled, section 2).
- Alarms of a deleted or deactivated user are disabled, and stay disabled after reactivation.
- One central check is used by every route; the default is deny.

## 14. Internet exposure

The app must work outside the home, so Home Assistant itself is reachable on a public URL. The add-ons must not add anything open to the internet.

| Part | Reachable without login? | Notes |
|---|---|---|
| Dashboard add-on API and panel | **No**, only through HA ingress, which requires HA login | No host port is exposed; used by panel and app |
| App add-on (APK) | Not reachable from outside | Internal Supervisor network only; the APK is downloaded through the dashboard API |
| Scheduler, executor | Not reachable | Runs inside the add-on container |
| Webhooks, public URLs | **None** | |
| Cloud services, outbound calls | **None** | Nothing leaves HA |

**How the app gets access from outside:** it logs in with the user's HA account (OAuth through HA), receives a token, opens an ingress session for the dashboard add-on and calls the same API as the panel (see TR-APP-06). HA's OAuth flow requires the app's `client_id` to be a URL whose page declares the app's redirect URI; this is a static page on the project's GitHub Pages and contains no data. A lost phone's token can be revoked in the HA user profile. Use a non-admin user for each phone.

