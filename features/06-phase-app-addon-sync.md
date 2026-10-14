# Phase 6: App Add-on and Sync API

**Goal:** The second add-on `alarm_actions_app` delivers the APK; when it runs, the dashboard offers the download, and the Android app can reach the dashboard API from anywhere through the optional external API (e.g. `https://api.vanbaelen.dev/clock` behind a Cloudflare tunnel) and sync.

**Objects and components:** [Client](../analysis/objects/client.md), [Occurrence](../analysis/objects/occurrence.md), [OccurrenceEvent](../analysis/objects/occurrence-event.md), [Dashboard add-on](../analysis/components/dashboard-addon.md), [App add-on](../analysis/components/app-addon.md), [Android app](../analysis/components/android-app.md)

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 6.1 | `GET /sync?since=<change_seq>` with tombstones for deleted alarms, own alarms only | FR-APP-03, TR-APP-07, SEC-03, [Client](../analysis/objects/client.md) | App can fetch only changes, including system changes (skip reset, auto-disable) |
| 6.2 | Client registration with time zone (`POST /clients`, `GET /clients`) | FR-APP-04, analysis section 10 | `last_seen` and time zone stored |
| 6.2a | Away detection: owner's latest client time zone ≠ HA time zone → runs skipped ("user away"); panel shows phones, last sync and away banner | FR-EXE-12, FR-UI-10, TR-SCH-09, [Client](../analysis/objects/client.md) | Away test: no scripts run, records say "user away"; back home resumes |
| 6.3 | App add-on skeleton `alarm_actions_app/`: `config.yaml` (no ports, no ingress), Dockerfile, small server with `/info` and `/apk` | TR-APP-01, SEC-12, [App add-on](../analysis/components/app-addon.md) | Add-on linter passes; add-on starts and serves `/info` internally |
| 6.4 | `app_proxy.py`: detect the app add-on on its internal hostname; `GET /app/info` and `GET /app/apk` proxied through the dashboard's ingress API | FR-APP-01, 05, TR-APP-03, 04, SEC-09 | Download works when logged in; "unavailable" when the app add-on is stopped or not installed |
| 6.5 | "Install app" tab in the panel: shown only when the app add-on runs; download button, install steps, setup QR code with HA URL and API URL, or a hint to set `external_api_url` | FR-UI-07, FR-APP-02, 07, TR-UI-06, TR-APP-09, TR-API-14 | Tab visibility verified with and without the app add-on |
| 6.6 | External API listener: `external_api_url` option, port 8100 (`ports: {8100/tcp: null}`), routes under the URL's path only, API only (no panel, no `/debug`), `no-store`, no CORS, body limit | FR-SET-05, TR-API-11, 13, TR-PLT-07, 09, SEC-12, 13 | Off by default; with the option set, `/clock/api/v1/info` answers and every other path is 404 |
| 6.6a | `token_auth.py`: bearer token checked with HA (`auth/current_user`), active-user check, 60 s hashed cache, rate-limited validations, tokens never logged | TR-API-09, 12, 13, SEC-02, 11, 13 | Tests: valid, invalid, expired, revoked and inactive-user tokens; forged `X-Remote-User-*` headers ignored; access matrix green on both listeners |
| 6.6b | Cloudflare tunnel guide in `DOCS.md`: public hostnames for HA and the API, route to the add-on's internal hostname and port 8100, no Cloudflare Access or a bypass rule, no caching, Bot Fight Mode check | SEC-10, FR-APP-07 | Followed on a real install: `https://api.vanbaelen.dev/clock/api/v1/info` with a non-admin token works from mobile data |
| 6.7 | API version policy and `min_client_api_version` in `GET /info` | FR-APP-06, TR-API-01, 06, TR-APP-08 | Documented and returned |
| 6.8 | `docs/openapi.yaml` includes app endpoints, validated in CI | TR-API-05 | Contract matches implementation |
| 6.9 | Occurrence id and schedule test vectors (DST, weekdays, skip) published for the app | TR-API-04, ATR-SYN-01 | In `docs/test-vectors/`, used by server and app tests |
| 6.10 | Late dismissal handling for offline phones | FR-EXE-13, analysis section 6, SEC-06 | Accepted for the record; stale `after_dismiss` runs recorded `missed` |
| 6.11 | Release pipeline: build and sign APK, copy it into the app add-on image, push both images to GHCR, publish checksum, then bump versions | TR-APP-02, TR-PLT-10, TR-QLT-07 | Add-on Store installs a release of the app add-on that contains the APK |
| 6.12 | OAuth `client_id` page on GitHub Pages declaring the app's redirect URI | ATR-SYN-04 | HA login from the app accepts the redirect |

**Exit criteria:** with both add-ons installed from the Add-on Store, a logged-in non-admin user can download the APK from the panel, and a test client built only from the OpenAPI file can call the external API through a Cloudflare tunnel with a non-admin user's HA token, sync alarms, edit them, and report a dismissal.