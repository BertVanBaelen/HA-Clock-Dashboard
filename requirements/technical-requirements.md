# Technical Requirements

Architecture ([Dashboard add-on](../analysis/components/dashboard-addon.md)): this repository is a **Home Assistant add-on repository** with two add-ons. The user adds one repository URL in the Add-on Store and installs the add-ons they want.

| Add-on | Slug | Purpose |
|---|---|---|
| Alarm Actions (dashboard) | `alarm_actions` | Sidebar panel, alarm storage, scheduler, executor, API for panel and app |
| Alarm Actions App | `alarm_actions_app` | Serves the Android APK; when installed, the dashboard shows the "Install app" tab |

```
  HA frontend (sidebar "Alarms", ingress iframe)            Android app
               │                                    │ login: HA OAuth on the HA URL (e.g. home.vanbaelen.dev)
               ▼                                    │ API:   Bearer <HA access token> on the external API URL
  HA Core / Supervisor ingress proxy                ▼        (e.g. https://api.vanbaelen.dev/clock)
  (login required, X-Remote-User-* headers)   Cloudflare tunnel / reverse proxy (HTTPS)
               │                                    │
               ▼ ingress listener                   ▼ external API listener (optional, port 8100)
   ┌──────── alarm_actions add-on (container) ────────┐     ┌── alarm_actions_app add-on ──┐
   │ Fastify API + panel, scheduler, executor, SQLite │────►│ /info, /apk (internal only)  │
   └──────────────────────┬───────────────────────────┘     └──────────────────────────────┘
                          │ ws://supervisor/core/websocket (SUPERVISOR_TOKEN)
                          │ ws://homeassistant:8123/api/websocket (user token check only)
                          ▼
                HA Core: labels, entity registry, users, script.turn_on, event bus
```

## 1. Platform
| ID | Requirement |
|---|---|
| TR-PLT-01 | The repository is a HA add-on repository (`repository.yaml` at the root) containing two add-ons: `alarm_actions` and `alarm_actions_app` ([Dashboard add-on](../analysis/components/dashboard-addon.md)) |
| TR-PLT-02 | Both add-ons share one semantic version, set in each `config.yaml`, released together with a GitHub Release and a `CHANGELOG.md` per add-on |
| TR-PLT-03 | Requires a Supervisor installation (**HA OS or Supervised**). The `homeassistant` key in `config.yaml` sets the minimum Core version (at least 2024.4, for labels). Tested against the current stable and previous Core release |
| TR-PLT-04 | Service code in **Node.js 22 + TypeScript** (Fastify, `better-sqlite3`, `ws`), on the official `node:22-bookworm-slim` image; the Node process is the container entry point (no s6) ([Dashboard add-on](../analysis/components/dashboard-addon.md)) |
| TR-PLT-05 | Must pass the add-on linter (`frenck/action-addon-linter`); images are built with `docker/build-push-action` per architecture on native runners |
| TR-PLT-06 | Architectures `aarch64` and `amd64`. Images are prebuilt by CI, pushed to GHCR and referenced with `image:` in `config.yaml`, so users never build locally |
| TR-PLT-07 | Dashboard `config.yaml` sets at least: `ingress: true`, `ingress_port`, `panel_icon: mdi:alarm`, `panel_title: Alarms`, **`panel_admin: false`** (so non-admin users see the panel), `homeassistant_api: true`, `init: false`, `options`/`schema` (including the optional `external_api_url`, TR-API-11), `ports: {8100/tcp: null}` (disabled by default, TR-API-11), backup settings (TR-DAT-07). Options that equal the Supervisor default (`boot: auto`, `startup: application`, `ingress_port: 8099`, `backup: hot`) are left out because the add-on linter rejects them; health is checked by a Docker `HEALTHCHECK` instead of the obsolete `watchdog` |
| TR-PLT-08 | Each add-on folder contains `icon.png`, `logo.png`, `DOCS.md`, `CHANGELOG.md` and `translations/en.yaml` (option labels) |
| TR-PLT-09 | Least privilege: no host port mapped by default (the optional external API port is `null` until the admin maps it), no `host_network`, not `privileged`, no `hassio_api` unless a feature needs it, default AppArmor profile (SEC-12) |
| TR-PLT-10 | When the Supervisor upgrades the image (the version in `config.yaml` on the default branch), the image for that version must already exist on GHCR. CI publishes images before the version bump is merged |

## 2. Repository structure
```
repository.yaml                 add-on repository metadata (name, url, maintainer)
alarm_actions/                  dashboard add-on (npm workspaces)
  config.yaml  Dockerfile  DOCS.md  CHANGELOG.md  icon.png  logo.png
  translations/en.yaml
  docker/alarm-actions-backup   backup_pre command (writes the database snapshot)
  shared/src/                   types, zod schemas, schedule calculation (used by server and web)
  server/src/
    index.ts                    startup, signal handling
    app.ts                      Fastify app, ingress guard, static panel
    config.ts                   environment and add-on options
    ha/client.ts                websocket connection to HA Core (TR-HA)
    ha/directory.ts             time zone, users and alarm_action scripts from Core
    db.ts  snapshot.ts          SQLite persistence, migrations, backup snapshot
    store/                      alarms, occurrences and execution records
    scheduler.ts                next-occurrence calc + timers
    executor.ts                 runs scripts, records results
    security.ts                 identity (ingress or bearer token), owner/admin checks
    ratelimit.ts                script-start and API rate limiting
    routes/                     HTTP routes
    appProxy.ts                 detects the app add-on, proxies APK info/download
  server/test/                  vitest tests with a fake HA websocket server
  web/                          React + TypeScript panel, built with Vite in the image build
alarm_actions_app/              Android app add-on
  config.yaml  Dockerfile  DOCS.md  CHANGELOG.md  icon.png  logo.png
  server                        /info and /apk on the internal network only
  (alarm_actions.apk is copied in by release CI, never committed)
android/                        Kotlin app source (see app-requirements.md)
docs/openapi.yaml               API contract; docs/test-vectors/ for ids and schedules
scripts/                        check-versions.sh, set-version.sh
LICENSE                         MIT
.github/workflows/              ci, release, tag check (APK build later)
```

## 3. Persistence
| ID | Requirement |
|---|---|
| TR-DAT-01 | Alarms, occurrences, events, clients and history are stored in SQLite at `/data/alarm_actions.db` (WAL mode). `/data` survives add-on updates and is included in HA backups |
| TR-DAT-02 | The database has a schema version; migrations run on start |
| TR-DAT-03 | Execution history is capped per user (default last 200 entries, 30 days) |
| TR-DAT-04 | Every change is a transaction. Write-ahead execution records (TR-SCH-06) are committed before the script call |
| TR-DAT-05 | IDs are UUIDv4 strings, generated server-side. Action ids are preserved when a `PUT` sends an existing id ([Occurrence](../analysis/objects/occurrence.md)) |
| TR-DAT-06 | One monotonic `change_seq`. Every alarm change (user or system) and every deletion takes the next value; deletions are kept as tombstones for at least 30 days ([Client](../analysis/objects/client.md)) |
| TR-DAT-07 | `backup: hot` with a `backup_pre` step that writes a consistent SQLite snapshot. Cold backups are not used, because they stop the scheduler and HA's nightly backup runs in the early morning, when alarms are due |

## 4. Connection to Home Assistant Core
| ID | Requirement |
|---|---|
| TR-HA-01 | The add-on connects to `ws://supervisor/core/websocket` with `SUPERVISOR_TOKEN` (`homeassistant_api: true`). It reconnects with backoff; Core restarts are normal and must not lose state |
| TR-HA-02 | The HA time zone is read with `get_config` and refreshed on `core_config_updated` |
| TR-HA-03 | Labeled scripts are found with `config/label_registry/list` and `config/entity_registry/list`, with name and icon from the state. The result is cached and refreshed on `label_registry_updated` and `entity_registry_updated` |
| TR-HA-04 | Users (`is_active`, `is_admin`, name) come from `config/auth/list`; refreshed periodically and checked before each execution (owner check) |
| TR-HA-05 | If Core is unreachable when a run is due, the run is retried until the grace period ends, then recorded as `missed` |

## 5. Scheduling
| ID | Requirement |
|---|---|
| TR-SCH-01 | Next occurrence is computed in the HA time zone using the IANA time zone data of `Intl` (Node.js with full ICU) and handles DST changes (nonexistent and ambiguous local times) |
| TR-SCH-02 | For every enabled alarm, the scheduler computes the next *occurrence* and, for each action, a *planned run* (occurrence time + offset). Planned runs are tracked per occurrence, because an alarm can have several active occurrences at once |
| TR-SCH-03 | One asyncio timer for the earliest planned run, rescheduled after each run. The timer is re-checked at least every 60 s against wall-clock UTC, so clock corrections cannot delay runs |
| TR-SCH-04 | Scheduler recalculates on: alarm create/update/delete, skip, add-on start (also after an options change, which restarts the add-on), Core reconnect, time zone change, away-state change |
| TR-SCH-05 | On add-on start, planned runs within the grace period are executed; older are recorded as `missed` |
| TR-SCH-06 | Planned runs have a deterministic id `<occurrence_id>_<alarm_action_id>` to guarantee exactly-once execution. The execution record is committed as `running` **before** the script call; a record still `running` at startup becomes `failed` ("interrupted") and is not retried |
| TR-SCH-07 | "After dismissal" runs are created on the dismiss event, not pre-scheduled; a timeout timer (or a `timed_out` event) expires them. A computed run time already past by more than the grace period is recorded as `missed` |
| TR-SCH-08 | Early dismissal (before the occurrence fires) cancels its `before`/`at`/`after` runs not yet started ([OccurrenceEvent](../analysis/objects/occurrence-event.md)) |
| TR-SCH-09 | When the owner is away (latest client `time_zone` ≠ HA time zone, [Client](../analysis/objects/client.md)), the executor records the owner's runs as `skipped` ("user away") instead of running them |

## 6. Execution
| ID | Requirement |
|---|---|
| TR-EXE-01 | Actions are executed with the websocket command `call_service` (`script.turn_on`, `target.entity_id`), without waiting for the script to finish |
| TR-EXE-02 | Before each execution, verify the script entity exists and still has the configured label; otherwise record `unavailable` |
| TR-EXE-03 | Failures never stop other actions; each is isolated and logged |
| TR-EXE-04 | Label lookup uses the cached registries of TR-HA-03 |
| TR-EXE-05 | The executor passes no variables or data to scripts and enforces the rate limit and duplicate suppression of SEC-05. Starts over the limit are dropped and recorded as `skipped` ([Settings](../analysis/objects/settings.md)). "Run now" goes through the same executor and limits |
| TR-EXE-06 | Before executing, the alarm owner is verified to exist and be active (TR-HA-04, SEC-03) |

## 7. API
Served by the dashboard add-on on two listeners with the same routes and the same access checks: the **ingress listener** (`ingress_port`, used by the panel through `/api/hassio_ingress/<token>/api/v1/...`) and, only when `external_api_url` is set, the **external API listener** (port 8100, used by the Android app through e.g. a Cloudflare tunnel at `https://api.vanbaelen.dev/clock/api/v1/...`). JSON only. Standard HTTP status codes; errors as `{ "error": "code", "message": "..." }`. Authorisation rules: see [security-requirements.md](security-requirements.md).

| Method | Path (under `api/v1`) | Description |
|---|---|---|
| GET | `/info` | Add-on version, `api_version`, `min_client_api_version`, whether the app add-on is available |
| GET | `/me` | Current user id, name, admin flag, away state |
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
| GET | `/settings` | Read settings (they are changed in the add-on configuration, [Settings](../analysis/objects/settings.md)) |
| GET | `/sync?since=<change_seq>` | Alarms changed since a `change_seq`, deleted ids, current `change_seq`, default snooze |
| POST | `/clients` | Register or update an app instance, including its current time zone (called on every sync) |
| GET | `/clients` | The user's own phones with last sync time and away state (admins: all) |
| GET | `/app/info` | APK version, checksum and availability (from the app add-on) |
| GET | `/app/apk` | APK download, proxied from the app add-on |
| GET | `/debug` | Admin only: redacted scheduler and storage state |

| ID | Requirement |
|---|---|
| TR-API-01 | The API is versioned by the path (`/api/v1`) and by an integer `api_version` in `GET /info`. Additive changes keep the number; breaking changes bump it and are listed in the changelog |
| TR-API-02 | Writes use optimistic concurrency via `revision` in the request body; a mismatch returns `409` |
| TR-API-03 | Input validated with strict schemas (`zod`, shared between server and panel); unknown fields rejected (SEC-05) |
| TR-API-04 | An `occurrence_id` is deterministic: `<alarm_id>_<YYYYMMDDTHHMMSSZ>` (scheduled UTC time, [Occurrence](../analysis/objects/occurrence.md)), so the app can compute it offline. Test vectors for ids **and** schedule calculation (DST, weekdays, skip) are published in `docs/test-vectors/` |
| TR-API-05 | `docs/openapi.yaml` is the contract for this repository, validated in CI |
| TR-API-06 | `GET /info` returns `min_client_api_version` so a client such as the app can detect an incompatible dashboard and show a clear message |
| TR-API-07 | Foreign or unknown objects return `404`; admin-only endpoints and admin writes to other users' alarms return `403` (SEC-03, [Alarm](../analysis/objects/alarm.md)) |
| TR-API-08 | Weekdays are exchanged as `mon`..`sun`, never as numbers ([Occurrence](../analysis/objects/occurrence.md)) |
| TR-API-09 | **Identity ([Dashboard add-on](../analysis/components/dashboard-addon.md)):** on the ingress listener the user is taken from the `X-Remote-User-Id` header that HA ingress adds; requests are accepted only from the Supervisor ingress proxy address (`172.30.32.2`); any other source, or a request without the header, gets `401`. On the external API listener the user comes from the bearer token (TR-API-12); `X-Remote-User-*` headers are ignored there. Both paths yield the same user object for the one central access check. Admin status and active state come from TR-HA-04 |
| TR-API-10 | All routes use relative URLs, because the ingress path prefix differs per installation |
| TR-API-11 | **External API listener:** off by default. When the add-on option `external_api_url` is set (an `https://` URL, e.g. `https://api.vanbaelen.dev/clock`), the add-on also listens on container port 8100. Routes are served under the path of that URL (`/clock/api/v1/...`); everything else gets 404. It serves only the API: no panel files and no `/debug`. It is reached either by a tunnel or proxy add-on on the internal Supervisor network (`http://<addon-hostname>:8100`, no host port), or, for a proxy on another machine, by mapping the port in the add-on's Network settings |
| TR-API-12 | **Token check:** external requests carry `Authorization: Bearer <HA access token>` (the token the app got from HA OAuth). The add-on validates it by authenticating a websocket to `ws://homeassistant:8123/api/websocket` with that token (directly to Core, because the Supervisor proxy only accepts the add-on's own token; `wss://` if Core has its own certificate) and calling `auth/current_user` (id, name, admin flag); then it checks that the user is active (TR-HA-04). Results are cached by SHA-256 of the token for at most 60 s (failures also 60 s). Missing, invalid or expired tokens get `401` with `WWW-Authenticate: Bearer`, and the app refreshes its token at HA |
| TR-API-13 | **External hardening:** every external response has `Cache-Control: no-store` (so Cloudflare or another proxy never caches personal data); no CORS headers (the API is for the native app, browsers cannot read it cross-origin); request body limit 64 KB; token validations are rate limited (default 30 per minute per client IP, taken from `CF-Connecting-IP`/`X-Forwarded-For` only for rate limiting and logs, and 120 per minute in total); the `Authorization` header and tokens are never logged |
| TR-API-14 | The add-on reads HA's `external_url` from `get_config` and, with `external_api_url`, shows both on the Install app tab as text and as a QR code (`alarmactions://setup?ha=<url>&api=<url>`), so the app is set up by scanning |

## 8. Frontend
| ID | Requirement |
|---|---|
| TR-UI-01 | The panel is the dashboard add-on's ingress panel (sidebar "Alarms", `mdi:alarm`, visible to non-admins via `panel_admin: false`) |
| TR-UI-02 | Implemented in **React 19 + TypeScript**, bundled with **Vite** ([Dashboard add-on](../analysis/components/dashboard-addon.md)) |
| TR-UI-03 | The bundle is built in a Node stage of the multi-stage `Dockerfile`, so users need no build tooling |
| TR-UI-04 | The panel calls the add-on API with relative URLs; the ingress session cookie provides the login |
| TR-UI-05 | Theme and language follow HA: the panel reads HA's theme CSS variables and language from the parent frame (same origin), with `prefers-color-scheme` as fallback |
| TR-UI-06 | The panel shows an "Install app" tab when `GET /app/info` reports the app add-on is installed and running |
| TR-UI-07 | No `innerHTML`/`unsafeHTML` with user data, no third-party requests (SEC-07) |

## 9. Security
See [security-requirements.md](security-requirements.md) (IDs `SEC-*`). Implementation entry points: `server/src/security.ts` (identity, owner check), the zod schemas in `shared/src/schemas.ts` (validation) and `server/src/ratelimit.ts`; the external API and token check follow in phase 6.

## 10. Events
| ID | Requirement |
|---|---|
| TR-EVT-01 | Fire `alarm_actions_alarm_dismissed`, `alarm_actions_alarm_snoozed`, and `alarm_actions_action_executed` on the HA event bus (websocket `fire_event`), with ids, timestamps and (for executions) the `result` only (SEC-06), so users can build e.g. "notify on failure" automations |

There are no HA entities ([Dashboard add-on](../analysis/components/dashboard-addon.md)). The phone's next alarm is visible in HA through the Companion App's own *Next alarm* sensor, because the Android app uses `setAlarmClock`.

## 11. Quality
| ID | Requirement |
|---|---|
| TR-QLT-01 | Unit tests with `vitest` and Fastify `inject`, against a fake HA websocket server |
| TR-QLT-02 | The scheduler is tested for DST transitions, midnight rollover, restart recovery, Core disconnects, and skip behavior |
| TR-QLT-03 | Linting with `eslint` (typescript-eslint) and strict `tsc` type checks for server, shared code and panel |
| TR-QLT-04 | CI runs the add-on linter, lint, tests and a test image build on each PR |
| TR-QLT-05 | Changelog maintained per release and per add-on |
| TR-QLT-06 | Authorisation and identity tests (SEC-11) run in CI on both listeners, including requests from a non-ingress source address, forged `X-Remote-User-*` headers on the external listener, and missing, invalid, expired and inactive-user tokens |
| TR-QLT-07 | The release workflow fails if the two `config.yaml` versions differ from each other or from the git tag |

## 12. Observability
| ID | Requirement |
|---|---|
| TR-OBS-01 | Logs to stdout (visible in the add-on Log tab), with a `log_level` option; debug logs for scheduling decisions |
| TR-OBS-02 | Admin-only `GET /debug` returns redacted alarm and scheduler state |

## 13. App add-on and app access
| ID | Requirement |
|---|---|
| TR-APP-01 | The `alarm_actions_app` image contains the signed APK and a small HTTP server that serves `/info` (version, SHA-256) and `/apk` on the internal Supervisor network only: no ports, no ingress panel |
| TR-APP-02 | Release CI builds the Android app, signs it with a keystore stored as a GitHub secret, copies the APK into the app add-on image, and publishes a SHA-256 checksum. The APK is never committed to git |
| TR-APP-03 | The dashboard detects the app add-on by calling it on its internal hostname (derived from its own hostname, `<repo-hash>-alarm-actions` → `<repo-hash>-alarm-actions-app`). Not reachable → `GET /app/info` reports unavailable and the tab is hidden |
| TR-APP-04 | The APK is downloaded through the dashboard API (`GET /app/apk`: the panel through ingress, the in-app updater through the external API), so it always requires HA login. It is served with `Content-Type: application/vnd.android.package-archive` and a fixed filename including the version. No signed links are needed |
| TR-APP-05 | The APK contains no secrets or server address |
| TR-APP-06 | **App access path:** the app logs in with HA OAuth on the HA URL (e.g. `https://home.vanbaelen.dev`) and gets an access and refresh token. It calls the external API URL (e.g. `https://api.vanbaelen.dev/clock/api/v1/...`) with `Authorization: Bearer <access token>` (TR-API-12). On `401` it refreshes the token at HA once and retries. The app needs `external_api_url` to be set; without it, the Install app tab says so |
| TR-APP-07 | `GET /sync` returns alarms with `change_seq > since`, plus ids deleted since then (tombstones kept for at least 30 days), and the current `change_seq`. A client whose `since` is older than the tombstone window gets a full resync |
| TR-APP-08 | The app and the dashboard add-on share the contract in `docs/openapi.yaml`; the app checks `min_client_api_version` from `GET /info` on every sync |
| TR-APP-09 | The Install app tab shows the setup QR code of TR-API-14 |

## Decisions
Decisions are recorded in the file of the object or component they concern: frontend React + TypeScript + Vite, backend Node.js + TypeScript ([Dashboard add-on](../analysis/components/dashboard-addon.md)), polling sync ([Client](../analysis/objects/client.md)), sync cursor `change_seq` ([Client](../analysis/objects/client.md)), contract formats ([Occurrence](../analysis/objects/occurrence.md)), add-on repository with two add-ons ([Dashboard add-on](../analysis/components/dashboard-addon.md)), ingress identity for the panel and HA-token identity for the external API ([Dashboard add-on](../analysis/components/dashboard-addon.md)), settings in add-on configuration ([Settings](../analysis/objects/settings.md)), no HA entities ([Dashboard add-on](../analysis/components/dashboard-addon.md)), app add-on ([App add-on](../analysis/components/app-addon.md)).
