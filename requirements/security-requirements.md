# Security Requirements

Two rules drive everything here:

1. **Users can only touch their own alarms.** Nobody can see, edit, skip, dismiss or delete someone else's alarm.
2. **Nothing is open without a Home Assistant login.** Every endpoint requires a valid HA login, inside and outside the home, because the app must work from anywhere.

## How outside-the-home use works
- The panel is only served through HA **ingress**, behind HA login.
- The app logs in with Home Assistant's own login (OAuth on the HA URL, e.g. `home.vanbaelen.dev`) and calls the **external API** of the dashboard add-on (e.g. `api.vanbaelen.dev/clock`, through a Cloudflare tunnel) with the HA access token it received. The add-on checks that token with HA on every request (cached up to 60 s). There is no separate login, webhook or cloud relay.
- The external API is **off** until an admin sets `external_api_url`. It serves only the API (no panel, no debug), and every route requires a valid HA token. No host port is mapped by default; a tunnel add-on reaches the add-on over the internal Supervisor network.
- All scheduling and execution happens inside HA (the add-on talks to HA Core over the internal Supervisor network). The add-ons make no outbound calls and use no cloud service.

## Requirements
| ID | Requirement |
|---|---|
| SEC-01 | Every request requires a Home Assistant login: through HA ingress (panel), or with a valid HA access token on the optional external API (app). There are no unauthenticated endpoints and no webhooks; no host port is mapped by default. State changes use POST/PUT/DELETE only |
| SEC-02 | The acting user always comes from HA, never from request content: on the ingress listener from the `X-Remote-User-Id` header, trusted only on requests from the Supervisor ingress proxy (`172.30.32.2`); on the external API listener only from the bearer token validated with HA (`auth/current_user`), where `X-Remote-User-*` headers are ignored. Anything else gets 401 ([Dashboard add-on](../analysis/components/dashboard-addon.md)) |
| SEC-03 | **Alarm access is owner-only, also for admins** ([Alarm](../analysis/objects/alarm.md), [access control](../analysis/domain-analysis.md#4-access-control)). This covers reading, editing, deleting, skipping, dismissing, snoozing, running actions, and history. For other users an alarm behaves as "not found" (404). A central helper enforces this for every route, and the default is deny. If an owner is deleted or deactivated, their alarms stop running and stay disabled. By design, `GET /planned` shows every user the upcoming actions of other users (owner name, script, time), so household members know what will run; alarm ids, labels and settings are not exposed. No HA entities are created ([Dashboard add-on](../analysis/components/dashboard-addon.md)) |
| SEC-04 | Only scripts carrying the `alarm_action` label can be run, via `script.turn_on` and with no parameters from user input. The label is checked when saving and again when running |
| SEC-05 | Input is validated strictly (schemas, unknown fields rejected, length and size limits). Caps limit alarms and actions per user, and script starts are rate limited so a bug or abuse cannot hammer a device; starts over the limit are dropped and logged ([Settings](../analysis/objects/settings.md)) |
| SEC-06 | Dismiss, snooze and timed-out reports are accepted only from the alarm's owner, only for a real occurrence, within a plausible time window, and are idempotent. Late reports never start stale actions |
| SEC-07 | The panel and add-ons make no requests to third-party hosts and load no external scripts or fonts. User text is never rendered as HTML |
| SEC-08 | The panel bundle contains no data, secrets, or instance-specific information |
| SEC-09 | APK download only through the dashboard API (ingress or external API, login required in both); the app add-on serves the APK on the internal Supervisor network only. The APK contains no secrets, tokens or server address. Release builds are signed in CI; the signing key is never committed |
| SEC-10 | The README gives guidance for internet use: HTTPS via a reverse proxy or Cloudflare tunnel, MFA for all users, `ip_ban`, a dedicated non-admin user per person or phone, and revoking a lost phone's token in the HA profile. For a **Cloudflare tunnel** it explains: route the API hostname to the add-on's internal address and port 8100; do not put Cloudflare Access (Zero Trust login) in front of the API or HA hostname used by the app, or add a bypass rule, because the native app cannot pass that login; make sure Bot Fight Mode or WAF challenges do not block the app; never enable caching for the API hostname |
| SEC-11 | Automated tests cover this on both listeners: every route rejects requests without ingress identity or from a non-ingress source (ingress) and without a valid token, with forged identity headers, or for an inactive user (external API); every route is tested for owner, other user, and an admin who is not the owner |
| SEC-12 | Both add-ons use least privilege: no mapped host port by default (only the optional external API port, `null` by default), no `host_network`, not `privileged`, no extra Supervisor API roles unless a feature needs them, default AppArmor. The `SUPERVISOR_TOKEN` is never logged or returned by the API |
| SEC-13 | The external API is hardened for the internet: off by default, API routes only under the configured path, Cache-Control: no-store on every response, no CORS, small request bodies, rate-limited token checks, and tokens never logged or stored (only a SHA-256 hash in the 60-second cache). TLS ends at Cloudflare or the proxy; the hop to the add-on stays inside the HA host |

## Things to know (accepted)
- Anyone with access to alarms can cause **any** script labeled `alarm_action` to run. Only label scripts that are safe, and give heating scripts their own auto-off.
- Securing Home Assistant itself (users, MFA, proxy, updates) is outside this project.
- The dashboard add-on holds an admin-level `SUPERVISOR_TOKEN` towards HA Core (needed to list users and start scripts). The add-on code is the trust boundary; users can only reach it through the checked API.
- An HA access token is valid for the whole HA API, not only for this add-on. The app sends it only to the configured HA URL and external API URL, both under the user's own control, over HTTPS. A stolen token gives the same access as the phone's HA user, which is why a non-admin user per phone is required.
- The OAuth `client_id` page on GitHub Pages is public and static; it only declares the app's redirect URI and holds no data.
- A phone's reported time zone is personal data (it reveals travel); it is visible only to its owner and is redacted in the debug output and logs.
- Android app security (token storage, HTTPS only, signing key) is in [app-requirements.md](app-requirements.md).
