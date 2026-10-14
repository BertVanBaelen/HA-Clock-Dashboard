# Phase 6: App Distribution and Sync API (server side)

**Goal:** The integration can sync with the Android app and offer the APK for download, all inside the same integration and release.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 6.1 | `GET /sync?since=<change_seq>` with tombstones for deleted alarms, own alarms only | FR-APP-03, TR-APP-07, SEC-03, D25 | App can fetch only changes, including system changes (skip reset, auto-disable) |
| 6.2 | Client registration with time zone (`POST /clients`, `GET /clients`) | FR-APP-04, analysis section 10 | `last_seen` and time zone stored |
| 6.2a | Away detection: owner's latest client time zone ≠ HA time zone → runs skipped ("user away"); panel shows phones, last sync and away banner | FR-EXE-12, FR-UI-10, TR-SCH-09, D20 | Away test: no scripts run, records say "user away"; back home resumes |
| 6.3 | `GET /app/info`: bundled APK version, checksum, availability | FR-APP-05, TR-APP-01, 03 | Returns "unavailable" when no APK |
| 6.4 | `app_download.py`: `POST /app/download-link` returns a ~60 s signed link; APK served with correct type and filename | FR-APP-01, TR-APP-05, 06, SEC-09 | Link works once logged in, expires, cannot be created anonymously |
| 6.5 | "Install app" tab in the panel, hidden when no APK or option off | FR-UI-07, FR-APP-02, TR-UI-06 | Tab visibility verified in both cases |
| 6.6 | Integration option "Offer Android app" (default on) | TR-APP-04, D16 | Toggle hides tab and endpoints |
| 6.7 | API version policy and `min_client_api_version` in `GET /info` | FR-APP-06, TR-API-01, 06, TR-APP-08 | Documented and returned |
| 6.8 | `docs/openapi.yaml` includes app endpoints, validated in CI | TR-API-05 | Contract matches implementation |
| 6.9 | Occurrence id and schedule test vectors (DST, weekdays, skip) published for the app | TR-API-04, ATR-SYN-01 | In `docs/`, used by server and app tests |
| 6.10 | Late dismissal handling for offline phones | FR-EXE-13, analysis section 6, SEC-06 | Accepted for the record; stale `after_dismiss` runs recorded `missed` |
| 6.11 | Release pipeline: build and sign APK, put it in `app/`, build `alarm_actions.zip`, publish checksum | TR-APP-01, 02, D17 | HACS installs a release that includes the APK |
| 6.12 | `hacs.json` with `zip_release`, `custom_components/alarm_actions/brand/icon.png`, `manifest.json` keys required by HACS | TR-PLT-07, 08, TR-APP-01 | HACS validation action passes |
| 6.13 | OAuth `client_id` page on GitHub Pages declaring the app's redirect URI | ATR-SYN-04 | HA login from the app accepts the redirect |

**Exit criteria:** after installing a release through HACS, a logged-in user can download the APK from the panel, and a test client built only from the OpenAPI file can sync alarms, edit them, and report a dismissal.
