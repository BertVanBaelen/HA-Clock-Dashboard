# Phase 1: Foundation

**Goal:** The repository installs through HACS and loads in Home Assistant without errors.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 1.1 | Repository skeleton: `custom_components/alarm_actions`, `hacs.json`, `manifest.json`, `const.py` | TR-PLT-01, 02 | Folder structure matches technical requirements |
| 1.2 | Config flow (single instance, no options yet) | TR-PLT-01 | Integration can be added in the UI |
| 1.3 | CI: hassfest, HACS validation, ruff, pytest | TR-PLT-05, TR-QLT-03, 04 | All workflows green on main |
| 1.4 | Release workflow and first tag `v0.1.0` | TR-PLT-02 | HACS shows the version |
| 1.5 | Translations scaffold (English) | FR-UI-08 | `translations/en.json` present |
| 1.6 | Test harness with `pytest-homeassistant-custom-component` | TR-QLT-01 | One passing setup test |
| 1.7 | MIT `LICENSE` and issue templates | D8 | Files present |
| 1.8 | `manifest.json` with all keys HACS requires, `brand/icon.png`, `hacs.json` | TR-PLT-07, 08 | HACS validation action passes |

**Exit criteria:** Add repo as a custom HACS repository, install, restart, add integration, no errors in the log.
