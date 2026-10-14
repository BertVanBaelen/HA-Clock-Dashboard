# Technical Requirements

## 1. Platform
| ID | Requirement |
|---|---|
| TR-PLT-01 | Custom integration for Home Assistant Core, domain `alarm_actions` (decision D7). **One integration per repository**, because HACS manages only one (decision D15) |
| TR-PLT-02 | Distributed through HACS as a custom repository, with GitHub Releases and semantic version tags |
| TR-PLT-03 | Minimum supported HA version **2026.3** (needed for local brand images, D27), defined in `hacs.json` (`homeassistant` key) and tested against current stable and previous release |
| TR-PLT-04 | Python, fully async, no blocking I/O in the event loop |
| TR-PLT-05 | Must pass `hassfest` and HACS validation GitHub Actions |
| TR-PLT-06 | No external runtime dependencies beyond HA core where possible |
| TR-PLT-07 | `manifest.json` defines at least `domain`, `name`, `version`, `documentation`, `issue_tracker`, `codeowners` (HACS requirement) |
| TR-PLT-08 | Brand images (`icon.png`, optionally `logo.png` and dark/`@2x` variants) are shipped in `custom_components/alarm_actions/brand/`, which HA 2026.3+ picks up automatically (D27). They are not submitted to the `home-assistant/brands` repository |

## 2. Repository structure
```
custom_components/alarm_actions/
  manifest.json
  __init__.py          setup, panel + API registration
  const.py
  config_flow.py       single-instance setup, options flow
  models.py            dataclasses for domain objects
  storage.py           persistence (HA Store)
  scheduler.py         next-occurrence calc + timers
  executor.py          runs scripts, records results
  security.py          owner/admin checks, validation helpers
  ratelimit.py         script-start and API rate limiting
  api.py               HTTP views
  sync.py              sync and client registration for the app
  app_download.py      app info and signed APK download link
  app/                 alarm_actions.apk, added by release CI (not in git)
  sensor.py, switch.py opt-in entities
  diagnostics.py       redacted diagnostics
  services.yaml
  translations/en.json
  brand/icon.png       brand images (D27)
  frontend/            Lit + TypeScript source, built bundle
hacs.json              zip_release + filename (see section 12)
LICENSE                MIT
android/               Kotlin app source (see app-requirements.md)
docs/openapi.yaml
tests/
.github/
  workflows/           hassfest, hacs, lint, tests, release
```

## 3. Persistence
| ID | Requirement |
|---|---|
| TR-DAT-01 | Alarms and settings are stored with `homeassistant.helpers.storage.Store`, in `.storage/alarm_actions` |
| TR-DAT-02 | Stored data has a `version`; a migration function handles schema upgrades |
| TR-DAT-03 | Execution history is capped per user (default last 200 entries, 30 days) and stored separately from alarms |
| TR-DAT-04 | Writes are debounced (`async_delay_save`) except for dismiss events and write-ahead execution records (TR-SCH-06), which are saved immediately |
| TR-DAT-05 | IDs are UUIDv4 strings, generated server-side. Action ids are preserved when a `PUT` sends an existing id (D26) |
| TR-DAT-06 | The store keeps one monotonic `change_seq`. Every alarm change (user or system) and every deletion takes the next value; deletions are kept as tombstones for at least 30 days (D25) |

## 4. Scheduling
| ID | Requirement |
|---|---|
| TR-SCH-01 | Next occurrence is computed in the HA configured time zone using `zoneinfo` and handles DST changes (nonexistent and ambiguous local times) |
| TR-SCH-02 | For every enabled alarm, the scheduler computes the next *occurrence* and, for each action, a *planned run* (occurrence time + offset). Planned runs are tracked per occurrence, because an alarm can have several active occurrences at once (an earlier one still ringing or with `after` runs pending) |
| TR-SCH-03 | Timers use `async_track_point_in_utc_time`; a single timer for the earliest planned run, rescheduled after each |
| TR-SCH-04 | Scheduler recalculates on: alarm create/update/delete, skip, settings change, HA start, time zone change, away-state change |
| TR-SCH-05 | On start, planned runs within the grace period are executed; older are recorded as `missed` |
| TR-SCH-06 | Planned runs have a deterministic id `<occurrence_id>_<alarm_action_id>` to guarantee exactly-once execution. The execution record is written as `running` and saved immediately **before** the script call; a record still `running` at startup becomes `failed` ("interrupted") and is not retried |
| TR-SCH-07 | "After dismissal" runs are created on the dismiss event, not pre-scheduled; a timeout timer (or a `timed_out` event) expires them. A computed run time already past by more than the grace period is recorded as `missed` |
| TR-SCH-08 | Early dismissal (before the occurrence fires) cancels its `before`/`at`/`after` runs not yet started (D22) |
| TR-SCH-09 | When the owner is away (latest client `time_zone` ≠ HA time zone, D20), the executor records the owner's runs as `skipped` ("user away") instead of running them |

## 5. Execution
| ID | Requirement |
|---|---|
| TR-EXE-01 | Actions are executed with `hass.services.async_call("script", "turn_on", {"entity_id": ...}, blocking=False)` |
| TR-EXE-02 | Before each execution, verify the script entity exists and still has the configured label; otherwise record `unavailable` |
| TR-EXE-03 | Failures never stop other actions; each is isolated with try/except and logged |
| TR-EXE-04 | Label lookup uses the HA label registry and entity registry |
| TR-EXE-05 | The executor passes no variables or data to scripts and enforces the rate limit and duplicate suppression of SEC-05. Starts over the limit are dropped and recorded as `skipped` (D21). "Run now" goes through the same executor and limits |
| TR-EXE-06 | Before executing, the alarm owner is verified to exist and be active (SEC-03) |

## 6. API
All endpoints under `/api/alarm_actions/`, implemented as `HomeAssistantView` with `requires_auth = True`. JSON only. Standard HTTP status codes; errors as `{ "error": "code", "message": "..." }`. Authorisation rules: see [security-requirements.md](security-requirements.md).

| Method | Path | Description |
|---|---|---|
| GET | `/info` | Integration version, `api_version`, `min_client_api_version`, whether an app is offered |
| GET | `/alarms` | List alarms visible to the user (includes `revision` and `next_occurrence`) |
| POST | `/alarms` | Create alarm |
| GET | `/alarms/{id}` | Get alarm |
| PUT | `/alarms/{id}` | Replace alarm (requires matching `revision`) |
| DELETE | `/alarms/{id}` | Delete alarm |
| POST | `/alarms/{id}/skip` | Skip next occurrence |
| POST | `/alarms/{id}/unskip` | Undo skip |
| POST | `/alarms/{id}/actions/{action_id}/run` | Run one of the user's own attached actions now |
| GET | `/actions` | List available scripts with the `alarm_action` label |
| POST | `/alarms/{id}/occurrences/{occurrence_id}/dismissed` | Report dismissal |
| POST | `/alarms/{id}/occurrences/{occurrence_id}/snoozed` | Report snooze |
| POST | `/alarms/{id}/occurrences/{occurrence_id}/timed_out` | Report that ringing stopped without dismissal |
| GET | `/history?alarm_id=&limit=` | Execution history (own alarms; admins all) |
| GET / PUT | `/settings` | Read / update settings (admin for PUT) |

App-related endpoints (same integration):

| Method | Path | Description |
|---|---|---|
| GET | `/sync?since=<change_seq>` | Alarms changed since a `change_seq`, deleted ids, current `change_seq`, default snooze |
| POST | `/clients` | Register or update an app instance, including its current time zone (called on every sync) |
| GET | `/clients` | The user's own phones with last sync time and away state (admins: all) |
| GET | `/app/info` | Bundled APK version, checksum, availability |
| POST | `/app/download-link` | Create a short-lived signed link to the APK for the logged-in user |

| ID | Requirement |
|---|---|
| TR-API-01 | The API is versioned by the path and by an integer `api_version` in `GET /info`. Additive changes keep the number; breaking changes bump it and are listed in the changelog |
| TR-API-02 | Writes use optimistic concurrency via `revision` in the request body; a mismatch returns `409` |
| TR-API-03 | Input validated with `voluptuous` schemas; unknown fields rejected (SEC-05) |
| TR-API-04 | An `occurrence_id` is deterministic: `<alarm_id>_<YYYYMMDDTHHMMSSZ>` (scheduled UTC time, D26), so the app can compute it offline. Test vectors for ids **and** schedule calculation (DST, weekdays, skip) are published in `docs/` |
| TR-API-05 | `docs/openapi.yaml` is the contract for this repository, validated in CI |
| TR-API-06 | `GET /info` returns `min_client_api_version` so a client such as the app can detect an incompatible dashboard and show a clear message |
| TR-API-07 | Foreign or unknown objects return `404`; admin-only endpoints and admin writes to other users' alarms return `403` (SEC-03, D19) |
| TR-API-08 | Weekdays are exchanged as `mon`..`sun`, never as numbers (D26) |

## 7. Frontend
| ID | Requirement |
|---|---|
| TR-UI-01 | Registered via `panel_custom` with `sidebar_title`, icon `mdi:alarm`, `require_admin: false` |
| TR-UI-02 | Implemented in **Lit + TypeScript**, bundled with **Vite**, using HA CSS variables for theming (decision D4) |
| TR-UI-03 | The built bundle is produced by CI and attached to the release (and included in the HACS zip), so users need no build tooling |
| TR-UI-04 | Static files are registered through `hass.http.async_register_static_paths`. These are served without authentication, so the bundle must hold no sensitive data (SEC-08) |
| TR-UI-05 | The panel talks to the API via `hass.callApi`, inheriting the user's session |
| TR-UI-06 | The panel shows an "Install app" tab when `GET /app/info` reports an available APK |
| TR-UI-07 | No `innerHTML`/`unsafeHTML` with user data, no third-party requests (SEC-07, 02) |

## 8. Security
See [security-requirements.md](security-requirements.md) (IDs `SEC-*`). Implementation entry points: `security.py` (owner check and validation helpers) and `ratelimit.py`.

## 9. Events and entities
| ID | Requirement |
|---|---|
| TR-EVT-01 | Fire `alarm_actions_alarm_dismissed`, `alarm_actions_alarm_snoozed`, and `alarm_actions_action_executed` on the HA event bus, with ids, timestamps and (for executions) the `result` only (SEC-06), so users can build e.g. "notify on failure" automations |
| TR-ENT-01 | A `sensor` with device class `timestamp` for the next alarm, created through the config entry with `entity_registry_enabled_default = False` (SEC-03) |
| TR-ENT-02 | Optional per-alarm `switch` entities, also disabled by default |

## 10. Quality
| ID | Requirement |
|---|---|
| TR-QLT-01 | Unit tests with `pytest` and `pytest-homeassistant-custom-component` |
| TR-QLT-02 | The scheduler is tested for DST transitions, midnight rollover, restart recovery, and skip behavior |
| TR-QLT-03 | Linting with `ruff`; type hints with `mypy` where practical |
| TR-QLT-04 | CI runs hassfest, HACS validation, lint, and tests on each PR |
| TR-QLT-05 | Changelog maintained per release |
| TR-QLT-06 | Authorisation and route-authentication tests (SEC-11) run in CI |
| TR-QLT-07 | The release workflow fails if `manifest.json` `version` does not match the git tag |

## 11. Observability
| ID | Requirement |
|---|---|
| TR-OBS-01 | Standard HA logger `logging.getLogger(__name__)`; debug logs for scheduling decisions |
| TR-OBS-02 | A diagnostics platform exposes redacted alarm and scheduler state |

## 12. App distribution
| ID | Requirement |
|---|---|
| TR-APP-01 | `hacs.json` sets `"zip_release": true` and `"filename": "alarm_actions.zip"`. The release zip holds the contents of `custom_components/alarm_actions/` including `app/alarm_actions.apk` |
| TR-APP-02 | Release CI builds the Android app, signs it with a keystore stored as a GitHub secret, copies the APK into the zip, and publishes a SHA-256 checksum. The APK is never committed to git |
| TR-APP-03 | If installed from the branch instead of a release, no APK is present and the Install tab stays hidden |
| TR-APP-04 | An integration option "Offer Android app" (default on) controls whether the tab and endpoints are active |
| TR-APP-05 | `POST /app/download-link` requires login and returns a link signed with HA's `async_sign_path`, valid for about 60 seconds. The APK itself contains no secrets or server address |
| TR-APP-06 | The download view serves the file with `Content-Type: application/vnd.android.package-archive` and a fixed filename including the version |
| TR-APP-07 | `GET /sync` returns alarms with `change_seq > since`, plus ids deleted since then (tombstones kept for at least 30 days), and the current `change_seq`. A client whose `since` is older than the tombstone window gets a full resync |
| TR-APP-08 | The app and integration share the contract in `docs/openapi.yaml`; the app checks `min_client_api_version` from `GET /info` on every sync |
| TR-APP-09 | The APK is kept small (release build with R8 shrinking); it is stored in the HA config folder and therefore included in every HA backup |

## Decisions on earlier open questions
Frontend Lit + TypeScript + Vite (D4), single config entry (D5), polling sync (D6), one repository with one integration (D15), sync cursor `change_seq` (D25), contract formats (D26), local brand images (D27). See [../analysis/decisions.md](../analysis/decisions.md).

