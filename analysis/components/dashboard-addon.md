# Dashboard add-on (`alarm_actions`)

**Purpose:** The core of the product. One Home Assistant add-on that shows the alarm panel in the HA sidebar (ingress) and contains storage, scheduler, executor and the API used by the panel and the Android app. Works fully without the [app add-on](app-addon.md).

**Owns:** [Alarm](../objects/alarm.md), [AlarmAction](../objects/alarm-action.md), [Occurrence](../objects/occurrence.md), [OccurrenceEvent](../objects/occurrence-event.md), [PlannedRun](../objects/planned-run.md), [ExecutionRecord](../objects/execution-record.md), [Settings](../objects/settings.md), [Client](../objects/client.md). Reads [ActionDefinition](../objects/action-definition.md) from HA.

## Features
- Sidebar panel (ingress) with tabs for alarms, history and, when the app add-on runs, "Install app".
- Alarm editor with labeled scripts, timings, skip, enable/disable and "run now" (snooze is set in the Android app).
- Scheduler and executor that start HA scripts exactly once, also across restarts.
- API under `api/v1`, reachable through ingress (panel) and, optionally, on an external URL for the app (e.g. `https://api.vanbaelen.dev/clock` through a Cloudflare tunnel).
- HA event bus events `alarm_actions_alarm_dismissed`, `alarm_actions_alarm_snoozed`, `alarm_actions_action_executed`.
- Storage in SQLite under `/data`, included in HA backups.

## Rules
- Requires **HA OS or Supervised** (add-ons need the Supervisor). Container and Core installs are not supported.
- **Identity:** on ingress, the user comes from the ingress headers (`X-Remote-User-*`), accepted only from the Supervisor ingress proxy. On the external API, the user comes from the HA access token, which the add-on checks with HA (`auth/current_user`, cached up to 60 s). Both feed the same access check.
- **External API:** off until the admin sets `external_api_url`. Listens on container port 8100, serves only API routes under the URL's path (e.g. `/clock`), never the panel or `/debug`, with `Cache-Control: no-store` and no CORS. A tunnel add-on reaches it over the internal network; the host port stays unmapped unless the proxy runs on another machine.
- Talks to HA Core over the websocket API with the Supervisor token; only the permissions it needs are requested.
- Settings are add-on options ([Settings](../objects/settings.md)).
- No HA entities are created. Events stay.

## Decisions
| Decision | Why |
|---|---|
| **One add-on repository with two add-ons**: `alarm_actions` (dashboard) and `alarm_actions_app` (APK). One repository URL in the Add-on Store. HA OS/Supervised only | Matches how the user wants to install: the dashboard appears in the menu; the app is separate and optional |
| **Identity from HA ingress** for the panel | Every access requires an HA login; no own auth code for the panel |
| **Optional external API with HA-token check** for the app (own hostname such as `api.vanbaelen.dev/clock`), instead of the app opening ingress sessions | The user exposes services through a Cloudflare tunnel with one hostname per service. Ingress sessions from the app depend on Supervisor calls that a non-admin user may not be allowed to make; a bearer token is the normal way for a native app. The cost is a small, well-tested token check in the add-on |
| Frontend **React 19 + TypeScript + Vite**; the bundle is built when the add-on image is built, no build tools for users | Same stack as the owner's other add-on (TravelSteps); the panel runs in an iframe, so it does not need HA's own Lit components |
| Backend **Node.js 22 + TypeScript** (Fastify, better-sqlite3, ws, zod) on the official Node image, images prebuilt per architecture | One language for server, panel and the shared schedule calculation; same proven image and release pattern as TravelSteps |
| Names: slugs `alarm_actions` / `alarm_actions_app`, events `alarm_actions_*` | Describes the function; avoids a later data migration |
| **No HA entities**; the phone's next alarm shows in the Companion App's *Next alarm* sensor | The user does not want alarm entities; avoids exposing alarm times to all HA users |
| Security scope kept lean: owner-only alarms, and nothing open without HA login (also for use outside the home) | A home project; no heavy compliance work |
| License **MIT** | Common in the HA ecosystem |

## Requirements and features
- Requirements: TR-PLT-01..10, TR-DAT-01, 02, 07, TR-HA-01, 02, 04, TR-API-01, 03, 05, 06, 09..14, TR-UI-01..07, TR-OBS-01, 02, TR-QLT-01..07; FR-UI-05..08; FR-SET-05; SEC-01, 02, 07, 08, 10, 11, 12, 13
- [Phase 1](../../features/01-phase-foundation.md): all items
- [Phase 2](../../features/02-phase-data-model-api.md): 2.9, 2.11, 2.12, 2.13, 2.15
- [Phase 4](../../features/04-phase-dashboard-panel.md): 4.1, 4.9, 4.13, 4.14
- [Phase 5](../../features/05-phase-history-logging.md): 5.3
- [Phase 6](../../features/06-phase-app-addon-sync.md): 6.7, 6.8
- [Phase 7](../../features/07-phase-hardening-release.md): all items except the app ones