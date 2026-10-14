# Feature Roadmap

Scope: this repository (`HA-Clock-Dashboard`) is a Home Assistant **add-on repository** with two tracks that ship together in one release: the **dashboard add-on** `alarm_actions` (phases 1-7) and the **Android app** (A1-A5, in `android/`), which is delivered through the second add-on `alarm_actions_app`. Requirement IDs refer to [`../requirements`](../requirements). Each phase lists the [domain objects and components](../analysis/domain-analysis.md) it builds; those files hold the rules and decisions.

## Dashboard track (`alarm_actions/`)
| Phase | Name | Outcome | Status |
|---|---|---|---|
| 1 | Foundation | Add-on repository installs from the Add-on Store, connects to HA Core, CI green | ◐ code written; needs CI run and HA OS test |
| 2 | Data model and API | Alarms managed through an ingress API that is owner-only | ◐ code and tests written; needs ingress test on HA OS |
| 3 | Scheduling and execution | Actions run at the right times in HA | ☐ |
| 4 | Dashboard panel | Ingress panel "Alarms" with full UI for alarms and actions | ☐ |
| 5 | Dismiss and history | After-dismissal flow, history view, events | ☐ |
| 6 | App add-on and sync API | App add-on with APK, Install tab, external API for the app (e.g. through a Cloudflare tunnel), sync endpoint | ☐ |
| 7 | Hardening and release | Final access check, end-to-end test on HA OS with the app, v1.0.0 | ☐ |

Status: ☐ open, ◐ code written but exit criteria not yet verified, ☑ done.

## Android app track (`android/`)
| Phase | Name | Outcome | Status |
|---|---|---|---|
| A1 | Foundation | Empty app builds and is signed in CI | ☐ |
| A2 | Login and sync | External API spike, log in, alarms stored locally | ☐ |
| A3 | Alarm engine | Full-screen alarm through Do Not Disturb, offline | ☐ |
| A4 | Editor and reporting | Same editing as the panel, dismiss/snooze reports | ☐ |
| A5 | Hardening and release | Real-life trial, update check | ☐ |

## Dependencies
```
Dashboard:  1 → 2 → 3 → 5 → 7
                 ↘ 4 ↗
                  2 → 6 → 7
App:        A1 → A3 ──────→ A4 → A5 → 7
            A1 → A2.0 spike (needs dashboard 1) → A2 (needs dashboard 2 and 6) → A4
```
A3 (the alarm engine) has no server dependency and is the riskiest part on the phone, so start it right after A1. The A2.0 spike (does a non-admin user's HA token reach the add-on's external API through the Cloudflare tunnel?) checks the remote-access path; run it as soon as phase 1 delivers an installable add-on. The app and dashboard share one API contract (`docs/openapi.yaml`) and one release version, so a given release always contains matching versions.

## Definition of done (every feature)
- Requirement IDs covered and listed in the PR
- Unit tests written; CI passing (including the add-on linter)
- Documentation updated (README, `DOCS.md` or docs)
- Any new route takes the user from ingress or the validated HA token and respects owner-only access; the access matrix test is extended for both listeners
- Changes to the API update `docs/openapi.yaml`