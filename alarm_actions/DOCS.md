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

- **New alarm**: set the time, an optional label and the days. Select no days for a one-time
  alarm. Snooze is set in the Android app; otherwise the default from the add-on options applies.
- **Editing** an alarm switches it on: saving activates it, unless you turn it off in the editor.
- **Actions**: pick a script, choose *before*, *at* or *after* the alarm and the number of minutes.
  Each action can be switched off on its own; ▶ runs a saved action right away to test it.
- If a *before* action is already past for the next alarm (for example 20 minutes before an alarm
  in 5 minutes), the editor warns you; it runs from the following alarm on.
- Actions whose script was deleted or lost its label are marked **Unavailable** and are skipped.
- **Skip next** (⏭) skips only the next ring of a repeating alarm; tap again to undo.
- If an alarm was changed elsewhere (another phone or tab), saving asks you to reload it first.
- The panel follows your Home Assistant theme (light/dark) and works in the Companion App.

## Users and permissions

- Every Home Assistant user sees only their own alarms.
- Below your alarms, **Planned by others** lists the upcoming actions of other users' alarms: who, which script and when. Other users' alarms themselves cannot be opened or changed, also not by administrators.
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
