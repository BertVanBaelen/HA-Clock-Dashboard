# Security Requirements

Two rules drive everything here:

1. **Users can only touch their own alarms.** Nobody can see, edit, skip, dismiss or delete someone else's alarm.
2. **Nothing is open to the internet.** Every endpoint requires a Home Assistant login. This holds inside and outside the home, because the app must work from anywhere.

## How outside-the-home use works
- The app logs in through Home Assistant's own login and reaches the add-on API through HA **ingress**, exactly like the panel. There is no extra port, webhook, cloud relay, or separate login.
- If HA is reachable on a public URL (needed for the app away from home), the only things publicly visible are HA's own login page and HA's static files. The add-ons expose **no** host port and add **no** unauthenticated route; the panel bundle itself is also only served through ingress, behind login.
- All scheduling and execution happens inside HA (the add-on talks to HA Core over the internal Supervisor network). The add-ons make no outbound calls and use no cloud service.

## Requirements
| ID | Requirement |
|---|---|
| SEC-01 | Every request reaches the add-on through HA ingress, which requires a Home Assistant login. There are no public endpoints, webhooks or exposed ports. State changes use POST/PUT/DELETE only |
| SEC-02 | The acting user always comes from the `X-Remote-User-Id` header set by HA ingress, never from request content. The header is trusted only on requests from the Supervisor ingress proxy (`172.30.32.2`); other requests get 401 (D29) |
| SEC-03 | **Alarm access is owner-only.** Admins can view all alarms and history but not change other users' alarms (D19, analysis section 13). This covers reading, editing, deleting, skipping, dismissing, snoozing, running actions, and history. For other users an alarm behaves as "not found" (404); admin writes get 403. A central helper enforces this for every route, and the default is deny. If an owner is deleted or deactivated, their alarms stop running and stay disabled. No HA entities are created, so alarm times are not exposed to other users (D31) |
| SEC-04 | Only scripts carrying the `alarm_action` label can be run, via `script.turn_on` and with no parameters from user input. The label is checked when saving and again when running |
| SEC-05 | Input is validated strictly (schemas, unknown fields rejected, length and size limits). Caps limit alarms and actions per user, and script starts are rate limited so a bug or abuse cannot hammer a device; starts over the limit are dropped and logged (D21) |
| SEC-06 | Dismiss, snooze and timed-out reports are accepted only from the alarm's owner, only for a real occurrence, within a plausible time window, and are idempotent. Late reports never start stale actions |
| SEC-07 | The panel and add-ons make no requests to third-party hosts and load no external scripts or fonts. User text is never rendered as HTML |
| SEC-08 | The panel bundle contains no data, secrets, or instance-specific information |
| SEC-09 | APK download only through the dashboard's ingress API (login required); the app add-on serves the APK on the internal Supervisor network only. The APK contains no secrets, tokens or server address. Release builds are signed in CI; the signing key is never committed |
| SEC-10 | The README gives guidance for internet use: HTTPS via a reverse proxy, MFA for all users, `ip_ban`, a dedicated non-admin user per person or phone, and revoking a lost phone's token in the HA profile |
| SEC-11 | Automated tests cover this: every route rejects requests without ingress identity or from a non-ingress source, and every route is tested for owner, other user, and admin |
| SEC-12 | Both add-ons use least privilege: no `ports`, no `host_network`, not `privileged`, no extra Supervisor API roles unless a feature needs them, default AppArmor. The `SUPERVISOR_TOKEN` is never logged or returned by the API |

## Things to know (accepted)
- Anyone with access to alarms can cause **any** script labeled `alarm_action` to run. Only label scripts that are safe, and give heating scripts their own auto-off.
- Securing Home Assistant itself (users, MFA, proxy, updates) is outside this project.
- The dashboard add-on holds an admin-level `SUPERVISOR_TOKEN` towards HA Core (needed to list users and start scripts). The add-on code is the trust boundary; users can only reach it through the checked API.
- The OAuth `client_id` page on GitHub Pages is public and static; it only declares the app's redirect URI and holds no data.
- A phone's reported time zone is personal data (it reveals travel); it is visible only to its owner and admins and is redacted in the debug output and logs.
- Android app security (token storage, HTTPS only, signing key) is in [app-requirements.md](app-requirements.md).
