# Phase 6: Extension Point and API Compatibility

**Goal:** The separate `alarm-actions-app` repository can plug in without any change to the core, and the core never depends on it.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 6.1 | `extensions.py` with `async_register_tab` and unregister callback | TR-EXT-01, 05, FR-EXT-01, 02 | Another integration can add and remove a tab |
| 6.2 | Same-origin path validation for extension modules | TR-EXT-02, SEC-09 | Absolute URLs rejected |
| 6.3 | Extensions only registrable in-process, listed in `GET /info` | TR-EXT-03, 04 | No API write path exists |
| 6.4 | Core works without any extension | FR-EXT-03, TR-EXT-06 | Test without the app package |
| 6.5 | OpenAPI contract `docs/openapi.yaml` validated in CI | TR-API-05 | Contract matches implementation |
| 6.6 | Occurrence id test vectors published for offline clients | TR-API-04 | Documented in `docs/` |
| 6.7 | API version and compatibility policy (breaking change rules, changelog) | TR-API-01, 06 | Documented |
| 6.8 | Late dismissal handling for offline phones | analysis section 6 | Accepted for the record, no stale actions |

**Exit criteria:** a small test integration registers a tab and a test client built only from the OpenAPI file can list alarms, edit them, and report a dismissal.

> Moved to the `alarm-actions-app` repository: sync endpoint, client registration, APK serving, Install app tab, the Android app.
