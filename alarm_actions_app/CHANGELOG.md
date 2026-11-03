# Changelog

## Unreleased

- App: new design (`docs/design.md`): warm light and dark colors, new icon, alarm timeline with drag-to-place actions, and the list-to-detail animation.
- App: alarms can be set not to vibrate (Vibrate chip or row in the alarm, "No vibration" in the list). This is kept on the phone, like the sound.
- App: new night display (landscape bedside clock) with the next alarm and its actions, a Dimmer, and widgets on tap: tomorrow's weather, the morning plan with Skip tomorrow, bedroom (temperature, night light, status), the first calendar event and battery. Choose the Home Assistant entities in **Settings → Night display**; the widgets read them directly from Home Assistant with your login. **Start by itself** now starts when the phone charges on its side inside the time window.
- App: night display Bedroom widget, now first and twice as wide: slide the night lamp (5 % steps) and the heating target, and **All lights off** with a few seconds to undo. Choose the thermostat in **Settings → Night display → Heating**. The calendar widget reads up to six calendars. A **Do not disturb** pill on the night display turns the phone's Do Not Disturb on and off (alarms still ring).
- App: night display **bedtime checklist**: pick a Home Assistant to-do list in **Settings → Night display → Daily checklist**. From the night start time the night display opens on it until everything is ticked. Ticking an item can run an automation or script (chosen per item), ticks it in Home Assistant too (optional), and the list can start fresh every day at 04:00.
- App: night display **Display** settings (Edit widgets → Display) with a live preview: four clock sizes, **Off until tapped** (only a tiny alarm sign stays lit; a tap shows the clock for 5 seconds) and **Normal / Dim / Superdim** (deep red, thinner digits and the lowest screen brightness). The eye pill steps through the three; a Dimmer that was on becomes Dim.
- App: the Bedroom widget's night lamp and heating are now vertical bars; swiping sideways scrolls to the next widget without changing a light.
- App: alarms can have a **sunrise** of 10, 20 or 30 minutes (kept on the phone). With the night display open the screen brightens like a sunrise before the alarm; otherwise a short sunrise plays when it rings.
- App: new wake-up screen: swipe up to snooze (it stays dimmed with "Snoozing until"), and after Stop a good-morning screen with today's weather and the actions that run after the alarm.
- App: fixed the **Update**, **Allow**, **Log in again** and **Review** buttons in the banners, which did nothing. Phones with 0.2.1 or older must install this update once by hand, from the panel's **Install app** tab. After that, updates install from the app again.
- App: **Settings → Check for updates** checks right away and installs a new version. Once the app has updated itself, later updates no longer ask for confirmation.
- App: a ringing alarm now opens the full-screen alarm screen (also on the lock screen) instead of
  only showing a notification, and no longer asks to unlock the phone first.

## 0.1.2

- First version: serves the signed Android app (APK) to the Alarm Actions add-on on the internal
  network. The Alarms panel offers the download in the **Install app** tab on Android phones.
