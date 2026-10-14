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

## Users and permissions

- Every Home Assistant user sees only their own alarms.
- Administrators also see all alarms of other users, read-only.
- The add-on runs scripts with Home Assistant's own permissions, so only label scripts that are
  safe for every user to trigger.

## Options

| Option | Default | Meaning |
|---|---|---|
| `snooze_minutes` | 9 | Default snooze length for new alarms. |
| `dismiss_timeout_minutes` | 60 | After this time an alarm without a dismiss counts as dismissed. |
| `grace_period_minutes` | 5 | How late a script may still start, for example after a restart. Later runs are recorded as missed. |
| `history_days` | 30 | How long run history is kept. |
| `history_max` | 200 | Maximum number of history entries per user. |
| `max_script_starts_per_minute` | 20 | Safety limit for all script starts together. |
| `min_script_interval_seconds` | 30 | Minimum time between two starts of the same script. |
| `log_level` | info | Add-on log detail. |
| `external_api_url` | (empty) | Address of the external API for the Android app (later release). |

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
