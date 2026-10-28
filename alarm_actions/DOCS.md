# Alarm Actions

Plan your alarms in Home Assistant and let them start scripts before, at or after the alarm time.
For example: heat the bathroom 20 minutes before you wake up, or turn on the coffee machine
5 minutes after you dismiss the alarm.

## Installation

1. In Home Assistant open **Settings → Add-ons → Add-on Store**.
2. Open the menu (⋮) → **Repositories** and add
   `https://github.com/BertVanBaelen/HA-Clock-Dashboard`.
3. Install **Alarm Actions** and start it.
4. **Alarms** appears in the sidebar for every user.

## Making scripts available

Only scripts with the label `alarm_action` can be used as actions. In Home Assistant open
**Settings → Automations & scenes → Scripts**, open a script, choose **Settings (⚙) → Labels**
and add the label `alarm_action` (create it the first time). Scripts are started with
`script.turn_on` and receive no data, so they must work without variables.

## Using the panel

- **New alarm** (＋): choose *Simple alarm* or *With actions*. Tap the big time to change it, and
  the name or the **Repeat** and **Snooze** chips to change those. Select no days for a one-time
  alarm. Without a snooze of its own, the default from the add-on options applies.
- Tap an alarm to open it. Changes are kept until you tap **Save**; leaving with unsaved changes
  asks first. Saving an alarm switches it on. **⋯** duplicates or deletes it.
- **Actions** sit on a timeline around the alarm. **Add action**, pick a script, then drag it on
  the timeline (5-minute steps) or use ▲ ▼ (1 minute), and tap **Place here**. Tap an action to
  move it, switch it off, remove it or run it right away (**Run now**, for saved actions).
  *After dismiss* actions run when the alarm is dismissed (on the phone or in the panel), plus an
  optional delay. They are listed below the timeline. When nobody dismisses the alarm within
  `dismiss_timeout_minutes`, they do not run.
- If a *before* action is already past for the next alarm (for example 20 minutes before an alarm
  in 5 minutes), it is marked on the timeline. It runs from the following alarm on.
- Actions whose script was deleted or lost its label are marked **Unavailable** and are skipped.
- **Skip next** (⏭) skips only the next ring of a repeating alarm; tap again to undo.
- **Dismiss** (🔕) appears from one hour before an alarm until it is dismissed or times out. Before
  the alarm time it is an *early dismissal*: the *before*, *at* and *after* actions of that ring are
  skipped, the phone does not ring, and the *after dismiss* actions start right away (plus delay).
- If an alarm was changed elsewhere (another phone or tab), saving asks you to reload it first.
- The panel follows the light/dark mode of Home Assistant and works in the Companion App.

## Users and permissions

- Every Home Assistant user sees only their own alarms.
- Below your alarms, **Planned by others** lists the upcoming actions of other users' alarms: who, which script and when. Other users' alarms themselves cannot be opened or changed, also not by administrators.
- The add-on runs scripts with Home Assistant's own permissions, so only label scripts that are
  safe for every user to trigger.

## Options

| Option | Default | Meaning |
|---|---|---|
| `snooze_minutes` | 9 | Default snooze length for new alarms. |
| `dismiss_timeout_minutes` | 60 | After this time a ringing alarm that nobody dismissed expires; its *after dismiss* actions do not run. |
| `grace_period_minutes` | 5 | How late a script may still start, for example after a restart. Later runs are recorded as missed. |
| `history_days` | 30 | How long run history is kept. |
| `history_max` | 200 | Maximum number of history entries per user. |
| `max_script_starts_per_minute` | 20 | Safety limit for all script starts together. |
| `min_script_interval_seconds` | 30 | Minimum time between two starts of the same script. |
| `log_level` | info | Add-on log detail. |
| `external_api_url` | (empty) | Public address of the API for the Android app, for example `https://api.example.com/clock`. See [Android app](#android-app). |

## Android app

The optional **Alarm Actions App** add-on (same repository) holds the Android app. The app keeps a
copy of your alarms and rings them itself, also without network, and reports dismissals back.

### Setting it up

1. Install and start **Alarm Actions App**. On an Android phone the Alarms panel now shows an
   **Install app** tab.
2. Make Home Assistant reachable over **https** from outside (Settings → System → Network →
   *Home Assistant URL*, Internet). The app logs in there with your Home Assistant account.
3. Give the API its own public address and set it as `external_api_url` on this add-on's
   **Configuration** tab, then restart the add-on. The address may have a path, for example
   `https://api.example.com/clock`; the API then answers below `/clock/api/v1`.
4. Route that address to this add-on, port **8100**. With the **Cloudflared** add-on, add to its
   `additional_hosts`:

   ```yaml
   - hostname: api.example.com
     service: http://<hostname>:8100
   ```

   `<hostname>` is shown on this add-on's **Info** tab (for example `a1b2c3d4-alarm-actions`).
   Do **not** put Cloudflare Access (a login page) in front of this hostname, and do not cache it.
5. On the phone open the **Install app** tab: download the app, install it, tap
   **Set up the app** and log in.

The port is not published on the host; only the tunnel (on the internal add-on network) reaches
it. Every request must carry a valid Home Assistant access token, which the add-on checks with
Home Assistant (the result is cached for a minute). Failed checks are rate limited per address.

### Notes

- Home Assistant's own `ip_ban` counts failed logins per source address. Token checks come from
  this add-on's address, so many made-up tokens could ban it; the add-on's rate limit keeps that
  unlikely. If it happens, remove the add-on's address from `ip_bans.yaml`.
- Token checks connect to Home Assistant directly on the internal network. The add-on asks the
  Supervisor for Home Assistant's port and whether it uses its own certificate (`ssl_certificate`),
  so a port other than 8123 and SSL both work.
- **Your phones** in the panel lists the phones with the app and when they last synced. Removing a
  phone there does not log it out; revoke its token in your Home Assistant profile
  (*Security → Refresh tokens*) for that.
- **Away**: while your most recently used phone is in another time zone than Home Assistant, your
  actions are skipped (recorded as *user away*); the phone still rings at local time. The panel
  shows a banner while you are away.
- Dismissing and snoozing fire the Home Assistant events `alarm_actions_alarm_dismissed` and
  `alarm_actions_alarm_snoozed` (data: `alarm_id`, `occurrence_id`, `scheduled_at`,
  `occurred_at`), which you can use in automations.
- The panel offers a setup link instead of a QR code: open the tab on the phone itself.

## Times and time zones

Alarm times are wall-clock times in the Home Assistant time zone
(**Settings → System → General**). On a daylight saving change an alarm in the skipped hour rings
at the first valid minute after it; an alarm in the repeated hour rings once, in the first one.

## Backups

The add-on supports hot backups. Before each backup a consistent snapshot of the database is
written; restoring the backup restores all alarms and history.

## Support

Report problems at
[GitHub issues](https://github.com/BertVanBaelen/HA-Clock-Dashboard/issues). Add the add-on log
(**Settings → Add-ons → Alarm Actions → Log**).
