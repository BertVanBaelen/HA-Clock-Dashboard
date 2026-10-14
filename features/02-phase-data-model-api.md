# Phase 2: Data Model and API

**Goal:** Everything the panel and app need can be done via an API that requires login and is owner-only.

**Objects and components:** [Alarm](../analysis/objects/alarm.md), [AlarmAction](../analysis/objects/alarm-action.md), [ActionDefinition](../analysis/objects/action-definition.md), [Occurrence](../analysis/objects/occurrence.md), [Settings](../analysis/objects/settings.md), [Dashboard add-on](../analysis/components/dashboard-addon.md)

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 2.1 | Domain models: Alarm (incl. `snooze_minutes`), AlarmAction, Settings; weekdays as `mon`..`sun` | TR-DAT-05, TR-API-08, analysis §2-3, 9 | Dataclasses and validation schemas exist |
| 2.2 | Storage layer with versioned schema, migrations, store-wide `change_seq` and tombstones | TR-DAT-01, 02, 04, 06 | Data survives restart; every change bumps `change_seq` |
| 2.3 | Alarm CRUD endpoints; action ids preserved on `PUT` | FR-ALM-01..04, TR-API-02, 03, [Occurrence](../analysis/objects/occurrence.md) | Create, read, update, delete work with revision check; action ids stable after edit |
| 2.4 | Action listing from labeled scripts (label and entity registries via websocket, cached, refreshed on registry events) | FR-ACT-01, 07, TR-EXE-04, TR-HA-03 | `GET /actions` returns labeled scripts only; new label shows up without restart |
| 2.5 | Owner-only access: a user can only see and change their own alarms; admins view-only | FR-ACC-01, 02, 05, SEC-03, [Alarm](../analysis/objects/alarm.md) | User B cannot read or edit user A's alarm (404); admin can read it but writes get 403 |
| 2.6 | `GET /settings` reads the add-on options (no write endpoint) | FR-SET-01, 02, [Settings](../analysis/objects/settings.md) | Options changed in the add-on configuration are returned after restart |
| 2.7 | Skip / unskip endpoints (state only); rejected for one-time alarms | FR-ALM-05 | `skip_next` toggles; one-time alarm returns 422 |
| 2.8 | Derived `next_occurrence` calculation | FR-ALM-07, TR-SCH-01 | Correct incl. DST and weekdays |
| 2.9 | Input limits and error format | SEC-05 | 400/403/404/409 responses match spec |
| 2.10 | `security.py`: one central owner-or-admin check used by every route, deny by default | SEC-03, TR-API-07 | No route has its own ad-hoc check |
| 2.11 | `security.py`: identity from `X-Remote-User-Id`, accepted only from the Supervisor ingress proxy; admin flag from `config/auth/list`. Route-enumeration test fails on a route without the identity check | SEC-01, 02, SEC-11, TR-API-09, TR-HA-04 | Requests without ingress identity or from another source get 401 on every route |
| 2.12 | Access matrix tests (owner, other user, admin, no identity) for every route, following analysis section 13 | SEC-11 | All cells of the matrix covered |
| 2.13 | Strict input validation and size limits | SEC-05 | Negative tests pass |
| 2.14 | Per-user caps (50 alarms, 20 actions) and API rate limit | SEC-05 | `422` / `429` returned |
| 2.15 | `GET /info` with `api_version` and `min_client_api_version` | TR-API-01, 06 | Endpoint documented |

**Exit criteria:** A full alarm with actions can be created, edited, and deleted through the ingress API as a non-admin user (from the panel origin or a test client with an ingress session), and a second user cannot see or change it.
