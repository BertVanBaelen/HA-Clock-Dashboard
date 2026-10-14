# Security Requirements

Two rules drive everything here:

1. **Users can only touch their own alarms.** Nobody can see, edit, skip, dismiss or delete someone else's alarm.
2. **Nothing is open to the internet.** Every endpoint requires a Home Assistant login. This holds inside and outside the home, because the app must work from anywhere.

## How outside-the-home use works
- The app logs in through Home Assistant's own login and calls the same authenticated API as the panel. There is no extra port, webhook, cloud relay, or separate login.
- If HA is reachable on a public URL (needed for the app away from home), the only things publicly visible are HA's own login page and HA's static files. The integration adds **no** unauthenticated route.
- The panel's JavaScript bundle is served as a static file, which HA does not protect behind login. It therefore contains only UI code, never data or settings.
- All scheduling and execution happens inside HA. The integration makes no outbound calls and uses no cloud service.

## Requirements
| ID | Requirement |
|---|---|
| SEC-01 | Every HTTP route requires Home Assistant authentication. There are no public endpoints or webhooks. The single exception is the APK download: a link valid for about 60 seconds that only a logged-in user can create (SEC-09). State changes use POST/PUT/DELETE only |
| SEC-02 | The acting user always comes from the authenticated session, never from request content |
| SEC-03 | **Alarm access is owner-only.** Admins can view all alarms and history but not change other users' alarms (D19, analysis section 13). This covers reading, editing, deleting, skipping, dismissing, snoozing, running actions, and history. For other users an alarm behaves as "not found" (404); admin writes get 403. A central helper enforces this for every route, and the default is deny. If an owner is deleted or deactivated, their alarms stop running and stay disabled. Sensor and switch entities are opt-in, since entities are visible to all HA users |
| SEC-04 | Only scripts carrying the `alarm_action` label can be run, via `script.turn_on` and with no parameters from user input. The label is checked when saving and again when running |
| SEC-05 | Input is validated strictly (schemas, unknown fields rejected, length and size limits). Caps limit alarms and actions per user, and script starts are rate limited so a bug or abuse cannot hammer a device; starts over the limit are dropped and logged (D21) |
| SEC-06 | Dismiss, snooze and timed-out reports are accepted only from the alarm's owner, only for a real occurrence, within a plausible time window, and are idempotent. Late reports never start stale actions |
| SEC-07 | The panel and integration make no requests to third-party hosts and load no external scripts or fonts. User text is never rendered as HTML |
| SEC-08 | The panel bundle contains no data, secrets, or instance-specific information |
| SEC-09 | APK download: only through a short-lived signed link created by a logged-in user. The APK contains no secrets, tokens or server address. Release builds are signed in CI; the signing key is never committed |
| SEC-10 | The README gives guidance for internet use: HTTPS via a reverse proxy, MFA for all users, `ip_ban`, a dedicated non-admin user per person or phone, and revoking a lost phone's token in the HA profile |
| SEC-11 | Automated tests cover this: every route rejects anonymous requests, and every route is tested for owner, other user, and admin |

## Things to know (accepted)
- Anyone with access to alarms can cause **any** script labeled `alarm_action` to run. Only label scripts that are safe, and give heating scripts their own auto-off.
- Securing Home Assistant itself (users, MFA, proxy, updates) is outside this integration.
- The OAuth `client_id` page on GitHub Pages is public and static; it only declares the app's redirect URI and holds no data.
- A phone's reported time zone is personal data (it reveals travel); it is visible only to its owner and admins and is redacted in diagnostics.
- Android app security (token storage, HTTPS only, signing key) is in [app-requirements.md](app-requirements.md).
