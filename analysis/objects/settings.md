# Settings

**Purpose:** Global settings of the [dashboard add-on](../components/dashboard-addon.md).

## Attributes
| Attribute | Default | Notes |
|---|---|---|
| `snooze_minutes` | 9 | Default when an [alarm](alarm.md) has no own `snooze_minutes` |
| `dismiss_timeout_minutes` | 60 | After this, `after_dismiss` actions expire |
| `grace_period_minutes` | 5 | Restart recovery window, and limit for stale late reports |
| `action_label` | `alarm_action` | Label used to find scripts |
| `history_days` / `history_max` | 30 / 200 | `history_max` is per user |
| `max_script_starts_per_minute` | 20 | Rate limit across all users. Starts beyond it are **dropped** and recorded as `skipped` ("rate limited") |
| `min_script_interval_seconds` | 30 | Duplicate-start suppression per script; a suppressed start is recorded as `skipped` ("duplicate start") |
| `log_level` | `info` | Add-on log verbosity |

## Rules
- Settings are **options of the dashboard add-on**, changed on its Configuration tab, so only admins can change them. Every user can read them through `GET /settings`; there is no write endpoint.
- Saving options restarts the add-on; the scheduler then recalculates everything, and the grace period covers runs due during the restart.
- The default snooze is sent to the app with every sync.

## Decisions
| Decision | Why |
|---|---|
| Settings live in the add-on configuration (admin only); the API only reads them | Standard add-on pattern; only admins can configure add-ons; no own settings UI or permission code |
| Rate-limited starts are dropped, not queued | Protects devices; late starts are rarely useful |

## Requirements and features
- Requirements: FR-SET-01..04, FR-ALM-09; TR-PLT-07
- [Phase 2](../../features/02-phase-data-model-api.md): 2.6
- [Phase 4](../../features/04-phase-dashboard-panel.md): 4.10
- Backlog: custom action label name (FR-SET-03), see [phase 7](../../features/07-phase-hardening-release.md)