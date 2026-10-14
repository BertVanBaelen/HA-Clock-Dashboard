# Phase 6: App Distribution and Sync API (server side)

**Goal:** The integration can sync with the Android app and offer the APK for download, all inside the same integration and release.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 6.1 | `GET /sync?since=` with tombstones for deleted alarms, own alarms only | FR-APP-03, TR-APP-07, SEC-03 | App can fetch only changes |
| 6.2 | Client registration endpoint (optional, minimal) | FR-APP-04, analysis section 10 | `last_seen` visible |
| 6.3 | `GET /app/info`: bundled APK version, checksum, availability | FR-APP-05, TR-APP-05 | Returns "unavailable" when no APK |
| 6.4 | `app_download.py`: `POST /app/download-link` returns a ~60 s signed link; APK served with correct type and filename | FR-APP-01, TR-APP-05, 06, SEC-09 | Link works once logged in, expires, cannot be created anonymously |
| 6.5 | "Install app" tab in the panel, hidden when no APK or option off | FR-UI-07, FR-APP-02, TR-UI-06 | Tab visibility verified in both cases |
| 6.6 | Integration option "Offer Android app" (default on) | TR-APP-04, D16 | Toggle hides tab and endpoints |
| 6.7 | API version policy and `min_client_api_version` in `GET /info` | FR-APP-06, TR-API-01, 06, TR-APP-08 | Documented and returned |
| 6.8 | `docs/openapi.yaml` includes app endpoints, validated in CI | TR-API-05 | Contract matches implementation |
| 6.9 | Occurrence id test vectors published for the app | TR-API-04, ATR-SYN-01 | In `docs/`, used by server and app tests |
| 6.10 | Late dismissal handling for offline phones | analysis section 6, SEC-06 | Accepted for the record, no stale actions |
| 6.11 | Release pipeline: build and sign APK, put it in `app/`, build `alarm_actions.zip`, publish checksum | TR-APP-01, 02, D17 | HACS installs a release that includes the APK |
| 6.12 | `hacs.json` with `zip_release`, `brand/icon.png`, `manifest.json` keys required by HACS | TR-PLT-07, 08, TR-APP-01 | HACS validation action passes |

**Exit criteria:** after installing a release through HACS, a logged-in user can download the APK from the panel, and a test client built only from the OpenAPI file can sync alarms, edit them, and report a dismissal.
