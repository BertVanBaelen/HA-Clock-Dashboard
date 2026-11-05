# Manual device test list (ATR-QLT-02)

Run this list before each release on at least:

- one **Pixel / stock Android 14+** phone (or emulator)
- one **Android 10–12** phone
- one phone from a manufacturer with aggressive battery management: **Samsung**, **Xiaomi** or
  **OnePlus/Oppo**

Record for each run: device, Android version, app version, pass/fail and notes.

## 1. Install and setup
- [ ] Fresh install of a signed release APK. The launcher icon shows an alarm clock.
- [ ] Panel → Connect phone → scan the QR with the camera app. The app opens with both URLs filled in.
- [ ] Manual entry: an `http://` address is rejected with "must start with https://".
- [ ] Log in through the browser tab. You return to the app, and the guide appears.
- [ ] Guide: every item can be fixed with its button, and the status turns green after returning.
- [ ] Alarms created in the panel appear after the first sync.

## 2. Ringing (screen off, phone locked)
- [ ] An alarm 2 minutes ahead rings at the exact minute and shows full screen over the lock screen.
- [ ] Sound plays on the alarm volume and gets gradually louder (volume ramp on). The phone vibrates.
- [ ] **Snooze**: the alarm stops and rings again after the snooze minutes. The notification shows
  "Snoozed until …" with a Stop button. The panel shows *snoozed*.
- [ ] **Stop**: the alarm stops. The panel shows *dismissed*, and after-dismiss actions run in HA.
- [ ] Not touched for 10 minutes: the alarm stops by itself and the panel shows *timed out*.
- [ ] Alarm with a chosen sound plays that sound. The default sound is used otherwise.
- [ ] Two alarms at the same minute: both ring one after the other. Neither is lost.
- [ ] Do Not Disturb on (alarms allowed): the alarm still rings.
- [ ] Airplane mode: the alarm rings, and the report is sent after reconnecting.

## 3. Reliability
- [ ] Reboot the phone and **do not unlock** it. The alarm still rings (direct boot).
- [ ] Phone off at alarm time, then switched on afterwards: a "Missed alarm" notification and banner appear.
- [ ] Change the time manually: the next alarm is rescheduled correctly.
- [ ] Leave the phone idle overnight (Doze): the morning alarm rings on time.
- [ ] Manufacturer phone: the app is swiped away from recents and the screen stays off for hours; the alarm still rings.
- [ ] Force a sync: the "Last synced …" time updates.

## 4. Editing and sync
- [ ] Create, edit and delete an alarm in the app. The panel shows the change within seconds.
- [ ] Panel edits appear in the app on resume or after "Sync now".
- [ ] Skip next / Undo skip on a repeating alarm, checked in both directions.
- [ ] Editor validation matches the panel:
  - a script is required
  - minutes must be 1–720
  - delay must be 0–120
  - duplicate actions are rejected
  - snooze must be within range
- [ ] An action "before" whose time has already passed shows the "already past" warning.
- [ ] Alarm detail (docs/design.md): opening an alarm with actions morphs its mini timeline into the rail;
  add an action (pick, drag, ▲▼, Place here), move one to "After dismiss", remove one, then Save.
  Back with unsaved changes asks "Discard changes?".
- [ ] Alarm detail tabs: Timeline | Wake-up. Name and Repeat, Sound, Vibrate, Sunrise and Snooze are all edited on Wake-up and kept (or dropped) with Save / Discard. Switching to Wake-up while placing an action drops the unplaced action.
- [ ] Settings: four category cards with their current values; search ("sound", "checklist") finds a row and opens its category with the row highlighted. Every row still works (sound, volume ramp, night times, widget entities, log in again / out, health check, check for updates). Back closes the search, then the category.
- [ ] Landscape (phone on its side, not charging in the night window): Alarms shows the rail, the alarm list and the detail. Add an alarm with +, tap the time for the time page (drums, repeat, name, Done), add / move / remove an action on the horizontal timeline, switch to Wake-up, Save. Switching alarms with unsaved changes asks "Discard changes?". Settings in landscape: categories on the left, rows on the right. Rotating back and forth keeps the open alarm.
- [ ] Offline edit: save in airplane mode. The alarm shows "Not synced yet" and is sent after reconnecting.
- [ ] Conflict:
  - Edit offline on the phone and edit the same alarm in the panel, then reconnect.
  - "Change not saved" appears. Check both "Keep mine" and "Use dashboard version".
- [ ] Early dismiss: dismiss an upcoming alarm in the panel. The phone does not ring that occurrence.
- [ ] "Dismiss" in the app for an alarm within 60 minutes. It does not ring.

## 5. Away / time zones
- [ ] Set the phone to another time zone:
  - the "Away" banner appears
  - the alarm rings at its local wall-clock time
  - no report reaches HA
- [ ] Back in the HA time zone: the banner disappears and reports work again.

## 6. Login and account
- [ ] Revoke the refresh token in the HA profile:
  - "Log in again" appears as a banner and a notification
  - alarms keep ringing
- [ ] Log out:
  - alarms are removed and no longer ring
  - the phone disappears from the panel's phone list

## 7. Update
- [ ] Install a newer version in the add-on. The "Update available" banner appears after sync.
- [ ] Update within 30 minutes before an alarm: it is refused with a message.
- [ ] Update otherwise:
  - the download progresses
  - the install happens without a prompt (Android 12+) or after one confirmation
  - alarms are still scheduled after the update
- [ ] A corrupted APK (checksum mismatch) is refused.

## 8. Night display
- [ ] Opens black, dimmed and in landscape, without status and navigation bars. The screen stays on and the clock moves slightly every few minutes.
- [ ] The next alarm shows with "in 7 h 12 min"; with actions, the line with dots and the first action / alarm / last action times.
- [ ] Tap or swipe up: the clock shrinks to the top left and the widget cards appear; tap, swipe down or Hide goes back. Back leaves the night display.
- [ ] The brightness pill steps Normal → Dim → Superdim → Normal and is remembered next time. Superdim: the resting clock is thin and deep red, without the action line and hint, and the screen is at its lowest; the widgets open at normal brightness and hide by themselves after 30 s untouched.
- [ ] Edit widgets → Display: Clock size S / M / L / XL changes the preview and the resting clock; the preview follows Dim and Superdim. Done goes back to Edit widgets. Settings from before (Dimmer on) come back as Dim.
- [ ] Screen Off until tapped: only the bell and the alarm time stay lit at the bottom right. A tap shows the clock for 5 s with a bar running out, then it fades; a tap while it shows (or a swipe up) opens the widgets.
- [ ] Do not disturb: the first tap opens the system access screen; after allowing it, the pill turns Do Not Disturb on and off (also visible in quick settings). An alarm still rings while it is on.
- [ ] Edit widgets: switching widgets off and on changes the cards; Start by itself switches the setting.
- [ ] Settings → Night display: choose a weather entity, up to six calendars, a night lamp, a thermostat, a bedroom temperature and up to three status entities. The cards show their data; First up shows the earliest event of all chosen calendars.
- [ ] Bedroom widget (first): sliding the Night lamp bar up and down dims the light in HA in 5 % steps with a tick per step (a switch goes off / on); 0 turns it off. Sliding Heating sets the thermostat target; "Heating · now" shows the room. A sideways swipe that starts on a bar scrolls to the next widget and does not change the light or the heating.
- [ ] Settings → Daily checklist: the HA to-do lists show with their open count; choosing one shows its items. Tapping an item picks an automation or script ("Runs …"); Clear removes it. Edit in Home Assistant opens the list in HA.
- [ ] Checklist on the night display: inside the night window with open items the night display opens on the checklist. Ticking an item ticks it in HA (with Tick off in HA too) and runs its automation; with that switch off, HA stays as it is. All ticked: "All done. Sleep well."; Dim the screen shows the resting clock and the checklist stays away that night; the Checklist pill with the widgets brings it back. With Start fresh every day, the list is unticked the first time it is read after 04:00.
- [ ] All lights off turns off every light in HA; tapping again within a few seconds (tap to undo) turns them back on.
- [ ] Without entities, the HA cards say where to choose them. With HA unreachable (Wi-Fi off), they keep their values or say HA is not reachable, and the clock and alarm still work.
- [ ] Skip tomorrow in the Next alarm card: the alarm dims, the alarm line says "Skipped · next alarm …", and the list shows the skip. Switching it off undoes it.
- [ ] Auto-start: with Start by itself on, inside the time window and the app open, charge the phone and stand it on its side. Leaving the night display while it stays on its side does not start it again.
- [ ] A ringing alarm takes over the night display. After Stop, the night display is closed; after snooze, it stays.

## 8a. Sunrise and wake-up screen
- [ ] Alarm detail → Wake-up tab: the Sunrise screen switch (on = 20 min) and Starts offer 10 / 20 / 30 min; Save keeps it; the list shows " · Sunrise 20 min". Update the app over an older install: existing alarms keep their sunrise, brightest point 100 %.
- [ ] Brightest point 40 %: with the night display open the sunrise stops at a soft amber and never gets bright; the wake-up screen starts at that same level. At 100 % it goes all the way to white as before.
- [ ] An alarm with a 10 min sunrise and the night display open: from 10 min before, the screen slowly turns from black via orange to warm white and gets brighter. At the alarm time the wake-up screen opens bright.
- [ ] The same alarm without the night display: the wake-up screen brightens over about 30 seconds.
- [ ] An alarm without a sunrise: the wake-up screen is black with amber text, at normal brightness.
- [ ] Swipe up (or tap the hint) snoozes: the screen dims, "Snoozing until …", swipe up again adds one more snooze. Stop there dismisses the alarm.
- [ ] Stop: the good-morning screen with the date and stop time, today's weather by the hour (when a weather entity is set) and the after-dismiss actions; Done closes it, and it closes by itself after 10 minutes.
- [ ] After a reboot, before the first unlock: the alarm rings with its sunrise; the good-morning screen shows without weather.

## 9. Language and appearance
- [ ] Phone language Dutch: all texts are in Dutch. Phone language English: all texts are in English.
- [ ] Dark mode and light mode are both readable.
