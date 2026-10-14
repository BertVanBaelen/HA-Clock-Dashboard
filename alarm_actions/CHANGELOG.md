# Changelog

## Unreleased

- Fix app login on Home Assistant installs that do not use port 8123 (or use their own certificate): the add-on now asks the Supervisor for Home Assistant's port and SSL setting instead of assuming `ws://homeassistant:8123`.

## 0.2.0

- Android app support (phase 6): the external API for the app (own address through a Cloudflare tunnel, `external_api_url`, port 8100) with Home Assistant token login; sync endpoint with deletions; phone registration with away detection (actions are skipped while your phone is in another time zone).
- Dismiss flow: dismiss and snooze reports from the app and a **Dismiss** button in the panel (also early, up to an hour before); *after dismiss* actions can be chosen again and run after the dismissal plus their delay; an alarm nobody dismisses expires after `dismiss_timeout_minutes`. Dismissing and snoozing fire `alarm_actions_alarm_dismissed` / `alarm_actions_alarm_snoozed` events.
- Panel: **Install app** tab on Android phones (download, install steps, setup link), **Your phones** list and an away banner.

## 0.1.2

- Livelier panel: the editor opens as an animated popup (slides up on phones), deleting asks in a confirmation popup, short notifications confirm saving, deleting, switching on/off and skipping, the list animates in, and a loading placeholder replaces the "Loading…" text. Animations are turned off when the system asks for reduced motion.

## 0.1.0

- First release: add-on skeleton, connection to Home Assistant Core, database with hot backup.
- Alarm API under `/api/v1` (owner-only, also for admins; `GET /planned` shows other users' upcoming actions), scripts with the `alarm_action` label as actions, contract in `docs/openapi.yaml`.
- Scheduler: before/at/after actions start their scripts at the planned time, exactly once, also across restarts (grace period) and Core outages; skip next, one-time alarms, rate limits, execution history and "run now".
- "Alarms" panel: alarm list with on/off, skip next and duplicate; editor for time, label, days and actions (script picker with icons, timing, per-action on/off, run now); warnings for unavailable scripts and missed *before* actions; reload prompt on conflicts; "Planned by others" list with the time of other users' upcoming actions; follows the HA theme; Dutch and English text from the HA user language; mobile layout; scripts load in the background so the list opens fast; saving an edited alarm activates it.
