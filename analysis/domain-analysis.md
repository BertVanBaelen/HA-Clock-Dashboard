# Functional Domain Analysis

Overview of the domain objects and components of the Alarm Actions add-ons. Each object and component has its own file with its attributes, rules, decisions and the requirements and features that build it. Feature files link to these files.

## 1. Overview

```
User 1───* Alarm 1───* AlarmAction *───1 ActionDefinition (HA script)
              │              │
              │              └──* PlannedRun ──1 ExecutionRecord
              │
              └──* Occurrence 1───* OccurrenceEvent (snoozed / dismissed)

Settings (global)        Client (app instance) *───1 User
```

### Domain objects
| Object | Summary | Persisted | Owner of lifecycle |
|---|---|---|---|
| [Alarm](objects/alarm.md) | A user's wake-up time with its actions | Yes | User |
| [AlarmAction](objects/alarm-action.md) | A labeled script linked to an alarm with a timing | Yes (embedded in Alarm) | User |
| [ActionDefinition](objects/action-definition.md) | An HA script with the action label | No (read from HA) | Home Assistant |
| [Occurrence](objects/occurrence.md) | One concrete instance of an alarm | Partly (active and recent) | Scheduler |
| [OccurrenceEvent](objects/occurrence-event.md) | Snoozed, dismissed or timed-out report | Yes (short-lived) | Client / User |
| [PlannedRun](objects/planned-run.md) | An action bound to an occurrence with a run time | No (derived) | Scheduler |
| [ExecutionRecord](objects/execution-record.md) | History of what was run | Yes (capped history) | Executor |
| [Settings](objects/settings.md) | Global add-on options | Yes (add-on options) | Admin |
| [Client](objects/client.md) | An installed Android app; sync and away detection | Yes (minimal) | App |

### Components
| Component | Summary |
|---|---|
| [Dashboard add-on](components/dashboard-addon.md) | Sidebar panel, storage, scheduler, executor and API |
| [App add-on](components/app-addon.md) | Optional; holds the APK and enables the "Install app" tab |
| [Android app](components/android-app.md) | Optional; rings alarms, reports dismiss/snooze, night display, in-app updater |
## 2. Cross-cutting business rules
1. **Phone independence:** every timed action is executed by HA. The phone only reports events.
2. **Label gate:** a script can only be run if it carries the action label at execution time.
3. **Time zone:** all server schedule calculations use the HA time zone; storage and API use UTC for instants and local `HH:MM` for alarm times. The phone rings at the local wall-clock `time` in its own time zone (rule 12).
4. **DST:** a time that does not exist (spring forward) fires at the next valid moment; an ambiguous time (fall back) fires at its first occurrence. Server and app follow the same published test vectors.
5. **Ownership:** users only modify their own alarms; admins may view all, but not change them.
6. **Concurrency:** updates carry a `revision`; a mismatch returns 409 and the client must refetch.
7. **Own alarms only:** users can only see and change their own alarms; others get "not found".
8. **Login everywhere:** every request requires a Home Assistant login: the panel through HA ingress, the app with its HA access token on the optional external API. Exposing the API to the internet for the app adds no route that works without login.
9. **Script gate:** the add-on only starts labeled scripts, with no parameters from users.
10. **Safety:** exactly-once execution, rate limiting and the missed-run policy prevent repeated or stale device actions.
11. **Dashboard without app:** the dashboard add-on works fully on its own; the Android app is optional and is offered only when the second add-on ("Alarm Actions App") is installed.
12. **Away ([Client](objects/client.md#away)):** when the owner's most recently seen phone reports a time zone different from HA's, the owner is treated as not at home. The phone rings at its local wall-clock time; HA runs **no** actions for that owner's occurrences and records them as `skipped` ("user away"). The panel shows the away state. Normal behaviour resumes as soon as the phone reports the HA time zone again. Known limit: a phone that has not synced since travelling is not detected.

Detailed security requirements: [../requirements/security-requirements.md](../requirements/security-requirements.md).

## 3. Example alarm (JSON)
```json
{
  "id": "6f0c1a52-4c5e-4a56-9d4b-2b1d6d6a9b10",
  "owner_user_id": "a1b2c3",
  "label": "Work",
  "time": "06:45",
  "repeat_days": ["mon", "tue", "wed", "thu", "fri"],
  "enabled": true,
  "skip_next": false,
  "snooze_minutes": null,
  "revision": 7,
  "change_seq": 1342,
  "next_occurrence": "2026-10-15T04:45:00Z",
  "actions": [
    { "id": "0b5e9c1e-7d2a-4f3b-9a51-3c2f7e8d1a01", "script_entity_id": "script.heat_bathroom", "timing": "before", "offset_minutes": 10, "enabled": true },
    { "id": "5a7c2d90-1e4b-4c8f-8f0a-9d6b3e2c4f02", "script_entity_id": "script.heat_car", "timing": "after_dismiss", "delay_minutes": 20, "enabled": true }
  ]
}
```

## 4. Access control

Who may do what. "Other user" is any HA user who is not the owner. The "Admin" column is an admin acting on **another user's** alarm; on their own alarms admins are owners.

| Operation | Owner | Other user | Admin | Not logged in |
|---|---|---|---|---|
| List alarms | Own only | Own only (never sees the owner's) | All | Rejected (401) |
| Read one alarm | Yes | Not found (404) | Yes | Rejected |
| Create alarm | Yes (becomes owner) | n/a | Own only (owner = self) | Rejected |
| Edit or delete alarm | Yes | Not found (404) | Forbidden (403) | Rejected |
| Skip, unskip | Yes | Not found (404) | Forbidden (403) | Rejected |
| Report dismissed, snoozed or timed out | Yes | Not found (404) | Forbidden (403) | Rejected |
| Run action now | Yes | Not found (404) | Forbidden (403) | Rejected |
| View history | Own alarms | Own alarms | All | Rejected |
| List available actions (labeled scripts) | Yes | Yes | Yes | Rejected |
| Sync own alarms (`/sync`) | Own only | Own only | Own only | Rejected |
| Register an app instance (incl. time zone) | Yes (own) | n/a | Yes (own) | Rejected |
| Download the APK (when the app add-on runs) | Any logged-in user | Any logged-in user | Yes | Rejected |
| Read settings | Yes | Yes | Yes | Rejected |
| Change settings (add-on configuration page) | No | No | Yes | Rejected |

**Rules**
- The owner is fixed when the alarm is created and cannot be changed or supplied by the client; it always comes from the HA user that ingress identifies or that HA confirms for the app's token.
- "Not found" instead of "forbidden" for other people's alarms, so ids cannot be probed. Admins get 403 on writes because they can already see the alarm.
- Admins have **view-only** access to other users' alarms. To stop another user's alarms, an admin deactivates that HA user (alarms are then disabled, see [Alarm](objects/alarm.md)).
- Alarms of a deleted or deactivated user are disabled, and stay disabled after reactivation.
- One central check is used by every route; the default is deny.

## 5. Internet exposure

The app must work outside the home. Home Assistant is reachable on a public URL for the login (e.g. `home.vanbaelen.dev`), and the dashboard add-on can offer its API on its own public URL (e.g. `api.vanbaelen.dev/clock`), both through a Cloudflare tunnel or another HTTPS reverse proxy. Neither adds anything usable without a Home Assistant login.

| Part | Reachable without login? | Notes |
|---|---|---|
| Dashboard panel | **No**, only through HA ingress, which requires HA login | |
| Dashboard API through ingress | **No** | Used by the panel |
| Dashboard external API (optional) | **No**, every request needs a valid HA access token, checked with HA | Off until `external_api_url` is set; API routes only; used by the app. Reached by the tunnel over the internal Supervisor network, no host port by default |
| App add-on (APK) | Not reachable from outside | Internal Supervisor network only; the APK is downloaded through the dashboard API |
| Scheduler, executor | Not reachable | Runs inside the add-on container |
| Webhooks | **None** | |
| Cloud services, outbound calls | **None** | Nothing leaves HA |

**How the app gets access from outside:** it logs in with the user's HA account (OAuth on the HA URL), receives an access token, and calls the external API URL with that token; the add-on asks HA who the token belongs to and applies the same access checks as for the panel (see TR-APP-06, TR-API-11..13 and [Android app](components/android-app.md)).

Example with a Cloudflare tunnel (cloudflared add-on on the same HA host):

| Public hostname | Routed to |
|---|---|
| `home.vanbaelen.dev` | `http://homeassistant:8123` (HA login, token refresh, panel) |
| `api.vanbaelen.dev`, path `/clock` | `http://<dashboard add-on hostname>:8100` (external API) |

The add-on option is then `external_api_url: https://api.vanbaelen.dev/clock`. HA's OAuth flow requires the app's `client_id` to be a URL whose page declares the app's redirect URI; this is a static page on the project's GitHub Pages and contains no data. A lost phone's token can be revoked in the HA user profile. Use a non-admin user for each phone.

