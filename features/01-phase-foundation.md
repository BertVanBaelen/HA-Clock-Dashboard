# Phase 1: Foundation

**Goal:** The add-on repository can be added in the Add-on Store, the dashboard add-on installs, starts and connects to HA Core without errors.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 1.1 | Repository skeleton: `repository.yaml`, `alarm_actions/` with `config.yaml`, `build.yaml`, `Dockerfile`, `DOCS.md`, `CHANGELOG.md`, `icon.png`, `logo.png`, `translations/en.yaml`, `service/main.py` | TR-PLT-01, 07, 08 | Folder structure matches technical requirements |
| 1.2 | `config.yaml`: ingress, `panel_admin: false`, `homeassistant_api: true`, options/schema, minimum Core version, least-privilege settings | TR-PLT-03, 07, 09, SEC-12 | Add-on linter passes; panel visible to a non-admin user |
| 1.3 | CI: add-on linter, ruff, mypy, pytest, test image build for aarch64 and amd64 | TR-PLT-05, 06, TR-QLT-03, 04 | All workflows green on main |
| 1.4 | Release workflow: build and push images to GHCR, then bump `config.yaml` versions; first release `0.1.0` | TR-PLT-02, 06, 10 | Add-on Store shows and installs the version without a local build |
| 1.5 | Translations scaffold for add-on options (English) | FR-UI-08, TR-PLT-08 | `translations/en.yaml` present |
| 1.6 | Test harness: pytest with the aiohttp test client and a fake HA websocket server | TR-QLT-01 | One passing startup test |
| 1.7 | MIT `LICENSE` and issue templates | D8 | Files present |
| 1.8 | `ha_client.py`: websocket to Core with `SUPERVISOR_TOKEN`, reconnect with backoff, `get_config` time zone | TR-HA-01, 02 | Connects on HA OS test instance; survives a Core restart |
| 1.9 | Release check: both `config.yaml` versions equal the tag | TR-QLT-07 | Mismatched version fails the workflow |
| 1.10 | SQLite storage file in `/data`, hot backup with `backup_pre` snapshot | TR-DAT-01, 07 | HA backup contains a consistent database |

**Exit criteria:** on a HA OS (or Supervised) test instance: add the repository URL in the Add-on Store, install the dashboard add-on, start it, "Alarms" appears in the sidebar for a non-admin user, no errors in the add-on log.