# Feature Roadmap

Scope: this repository (`HA-Clock-Dashboard`) has two tracks that ship together in one release: the **dashboard integration** (phases 1-7) and the **Android app** (A1-A5, in `android/`). Requirement IDs refer to [`../requirements`](../requirements).

## Dashboard track (`custom_components/alarm_actions`)
| Phase | Name | Outcome | Status |
|---|---|---|---|
| 1 | Foundation | Installable via HACS, CI green | ☐ |
| 2 | Data model and API | Alarms managed through an API that requires login and is owner-only | ☐ |
| 3 | Scheduling and execution | Actions run at the right times in HA | ☐ |
| 4 | Dashboard panel | Full UI for alarms and actions | ☐ |
| 5 | Dismiss, history, entities | After-dismissal flow, history view, HA entities | ☐ |
| 6 | App distribution and sync API | Install tab, signed APK download, sync endpoint, release with APK | ☐ |
| 7 | Hardening and release | Final access check, end-to-end test with the app, v1.0.0 | ☐ |

## Android app track (`android/`)
| Phase | Name | Outcome | Status |
|---|---|---|---|
| A1 | Foundation | Empty app builds and is signed in CI | ☐ |
| A2 | Login and sync | Log in, alarms stored locally | ☐ |
| A3 | Alarm engine | Full-screen alarm through Do Not Disturb, offline | ☐ |
| A4 | Editor and reporting | Same editing as the panel, dismiss/snooze reports | ☐ |
| A5 | Hardening and release | Real-life trial, update check | ☐ |

## Dependencies
```
Dashboard:  1 → 2 → 3 → 5 → 7
                 ↘ 4 ↗
                  2 → 6 → 7
App:        A1 → A3 ──────→ A4 → A5 → 7
            A1 → A2 (needs dashboard 2 and 6) → A4
```
A3 (the alarm engine) has no server dependency and is the riskiest part, so start it right after A1. The app and dashboard share one API contract (`docs/openapi.yaml`) and one release, so a given release always contains matching versions.

## Definition of done (every feature)
- Requirement IDs covered and listed in the PR
- Unit tests written; CI passing
- Documentation updated (README or docs)
- Any new route requires login and respects owner-only access; the access matrix test is extended
- Changes to the API update `docs/openapi.yaml`
