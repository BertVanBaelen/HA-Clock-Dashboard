# Design

One look for the Android app and the dashboard panel, from the "Alarm Clock App Mockups" (final, dark and light). Both follow this file. Where the mockup and the data model differ, the data model wins and this file says how the design adapts.

## Tokens

Light and dark follow the system (app) or HA's dark-mode setting (panel). There is no theme toggle.

| Token | Dark | Light | Use |
|---|---|---|---|
| `bg` | `#0D0D0F` | `#F5F5F2` | Screen background |
| `surface` | `#17171A` | `#FFFFFF` | Alarm cards, action cards, tiles |
| `ink` | `#F1F0EC` | `#151515` | Text, rail dots, primary button background |
| `muted` | `#A09FA6` | `#5C5C58` | Secondary text, idle icons |
| `line` | `#2A2A2F` | `#E2E1DB` | Borders, dividers, rail, ticks |
| `soft` | `#1F1F23` | `#EAE9E4` | Add button, create choices, pressed chips |
| `accent` | `#FFAD5C` | `#B4520A` | Alarm dot and line, switch track on, Save, focus ring |
| `on-accent` | `#1C1005` | `#FFFFFF` | Text and knob on accent |
| `accent-soft` | `#3A2A1B` | `#F6E3D2` | Accent tints (selected day, banners) |
| `off` | `#38383E` | `#D3D2CC` | Switch track off |
| `knob` | `#9A99A0` | `#FFFFFF` | Switch knob off |
| `danger` | `#FF8A7A` | `#B3261E` | Errors, Delete |
| `warn` | `#E8B04A` | `#8A5A00` | "Unavailable", "before already past" |

**Type:** the panel uses Inter (bundled, latin and latin-ext, OFL). The app uses the system sans-serif until Inter is bundled as a font resource. All numbers use tabular figures (`tnum`).

| Role | Size / weight |
|---|---|
| Screen title "Alarms" | 32 / 600, letter-spacing -0.02em |
| Alarm time in the list | 44 / 300, -0.035em, line-height 1 |
| Alarm time in the detail | 56 / 300, -0.045em |
| Body, rows, buttons | 15 / 400-600 |
| Secondary | 13-14 / 400, `muted` |
| Nav labels, timeline ticks | 12 |

**Shapes:** alarm card radius 22, 1px `line` border, `surface` fill. Chips are 40 high, radius 20, with a 1px border. Tiles and bottom-sheet buttons use radius 16. The primary button is 54 high, radius 18, `ink` fill with `bg` text. The switch has a 46×28 track and a 22 knob that moves 18. Touch targets are at least 44.

## Motion

| Name | Spec |
|---|---|
| `ease-soft` | cubic-bezier(.22,.8,.24,1) |
| `ease-morph` | cubic-bezier(.65,0,.35,1) |
| Rise | New content: opacity 0→1, translateY 14→0, 340 ms, ease-soft |
| Flash | A saved or moved action card: 2px accent ring, held to 45%, fades out over 1.8 s |
| Add button | The plus rotates 45° while the "New alarm" card is open, 300 ms ease-soft |
| Switch | Knob translate 300 ms ease-soft; track color 250 ms |
| Screen fade | List and detail cross-fade over 300 ms when no morph runs |
| **Morph** | Opening an alarm that has actions animates its mini timeline from the list into the detail rail; see below |
| Place | Guide line, knob, time label and ghost card follow the offset with a 120 ms `top` transition; other cards dim to 35% |
| Reduced motion | No morph, no rise; only fades |

**Morph (750 ms, ease-morph).** The mini timeline under the alarm card (a horizontal line with action dots and an accent dot at the alarm time) lifts off. It moves from its centre in the list to the alarm dot on the detail rail, and rotates 0°→90°, so before-actions end up on top. Its scale goes from the list scale to the detail scale (minutes × px-per-minute). The action dots grow from 6 to 9 and turn from `muted` to `ink`; the alarm dot grows from 10 to 13. The overlay is clipped at the top of the timeline viewport, so it never covers the header. Timing:
- The list fades out over 250 ms; the detail fades in over 350 ms after a 200 ms delay.
- The action cards fade in after 650 ms (300 ms); the real rail dots appear at 720 ms.
- Closing runs the same morph in reverse. The detail fades out over 200 ms, and the list fades back in after 500 ms (300 ms).
- No morph for alarms without around-the-alarm actions, or when closing from pick or place mode.

## Screens

### Alarm list
- **Header:** "Alarms", and below it "Next: 06:30 · in 7 h 12 min" (the soonest enabled ring, or "No alarms on"). On the right is the add button: a 44 circle with `soft` fill and a plus. The app also shows a sync button (muted, a spinner while syncing).
- **New alarm card** (rise, toggled by the add button): "New alarm" with Cancel, and two choices.
  - "Simple alarm": "Just a time and a sound. Add actions later if you like." Opens a new alarm in the detail.
  - "With actions": "Lights, coffee, music and more around the alarm." Opens a new alarm in pick mode.
- **Alarm card:** a 44/300 time, the label in `muted`, then "days · summary". The summary is "2 before · 1 with · 1 after · 1 after dismiss", or "Simple alarm" when there are no actions. In the app, " · No vibration" follows when the alarm does not vibrate. Below it is a status line: next ring, snoozed until, off, skipped, or not synced/not saved. Next comes the mini timeline (180 wide, only when there are around-the-alarm actions), then the switch on the right. An off alarm shows its content at 50% opacity. Small pill buttons under the content hold "Skip next"/"Undo skip" (repeating and on) and "Dismiss now" (within the hour before the ring, or while ringing).
- **Below the list (panel):** "Planned by others" and "Your phones", as quiet sections with the same card style.
- **Banners** (login, update, away, missed, problems, errors) are full-width cards with a 3px left stripe (`accent`, `warn` or `danger`) on `surface`.
- **Bottom nav:** the selected item has an 18×2 accent bar above its label; the others are `muted`. App: Alarms · Night · Settings. Panel: Alarms · Install app, only when the install tab is available.

### Alarm detail
This replaces the old editor dialog/screen. Changes are kept in a draft until **Save**.
- **Top row:** a breadcrumb, "‹ Alarms / 06:30". On the right is **Save** and a "⋯" menu with Duplicate and Delete (existing alarms only).
  - Save is an accent pill. It shows for a new alarm or when there are changes, except in pick and place mode, and reads "Saving…" while saving. After saving, the detail stays open.
  - Back with unsaved changes asks "Discard changes?" (Discard / Keep editing).
  - Back in pick or place mode leaves that mode first.
- **Header:** the 56/300 time (tap: time picker). On the right are the label and the days. Below it two tabs, **Timeline** and **Wake-up** (a segmented control; switching drops an unplaced action).
- **Wake-up tab** (app): grouped settings in `surface` cards (radius 16, 1px `line` border), each group with an uppercase 12/600 `muted` title and rows at least 52 high. A row shows its label, the current value and a chevron, or a switch. Tapping a row opens its editor right below it (rise); only one is open at a time. Sound, vibration, sunrise and brightest point are stored on the phone only.
  - **Alarm:** Name (a text field) and Repeat (7 day toggles, "Select no days for a one-time alarm").
  - **Sound:** Sound (opens the system ringtone picker) and a Vibrate switch.
  - **Sunrise:** a Sunrise screen switch (on = 20 min before). Then Starts (10 / 20 / 30 min before) and Brightest point: a 10–100 % slider in 5 % steps with a strip of the colours it reaches and a hint ("Goes all the way to white" from 90 %, "Stays soft and warm, never bright" up to 30 %). The sunrise on the night display and the wake-up screen stops at that point (100 % = all the way to white).
  - **Snooze:** Snooze for: − value + and "Use default (N min)".
- Banners for conflict (Reload), saved with warnings, and errors sit under the header.
- **Timeline tab, simple alarm** (no actions, view mode): a line "A simple alarm: just a time and a sound…" and a dashed card: "+ Add actions". "Turn on lights, start coffee or play the news before, with or after this alarm. It stays a simple alarm until you do."
- **Timeline** (when there are actions, or in pick or place mode): a vertical rail at x=60.
  - Range: `R = max(60, farthest before/after offset rounded up to 15)` minutes on each side, at 6 px/min up to R=120, then `720/R` px/min.
  - Ticks: major ticks with clock-time labels every 15 min (R≤60), 30 (R≤180) or 60. Minor ticks every 5, 15 or 30 min.
  - The alarm line is a 2px accent line across at offset 0. Its time label sits on the left in bold accent, with a 13 accent dot on the rail.
  - Action positions: `before` = −offset, `at` = 0, `after` = +offset. Each action has a 9 `ink` dot on the rail.
  - Action cards are 32 high, radius 10, to the right of the rail. Each shows the script icon, the script name (600), the description (`muted`, ellipsis) and the relative time on the right: "−30 min", "with alarm", "+5 min".
  - Cards that would overlap are pushed down (gap ≥ 2); the dots stay true.
  - A disabled action shows at 50% with "Off". An unavailable script shows its name in `warn` with a warning icon.
  - Tap a card: place mode for that action.
- **After you dismiss:** a section below the timeline, in the same scroll, for `after_dismiss` actions. It has the same cards, with the relative label "right away" or "+N min". Tap: place mode (after-dismiss variant).
- **Bottom area** (Timeline tab only; separated by a `line` border):
  - View mode: a primary "＋ Add action" button and the hint "Tap an action to move or change it". While scripts load, or when none exist, show the loading text or the no-scripts text instead.
  - Pick mode: "What should happen?" with Cancel, and a horizontal row of script tiles (92 wide, ≥84 high, icon + name). Picking a script places it at −15 (before 15).
  - Place mode:
    - A segmented control: "Around the alarm" | "After dismiss".
    - Around the alarm: ▲ earlier (−1 min), a readout ("15 min before the alarm", "Together with the alarm", "5 min after the alarm"), and ▼ later (+1 min). The subline reads "<Script> runs at 06:15 · drag it on the timeline". Dragging on the timeline snaps to 5 min. The range is ±720.
    - After dismiss: ▲▼ change the delay (0-120). The readout is "Right when you dismiss" or "10 min after you dismiss".
    - A row with the Enabled switch, plus "Run now" for a saved action of a saved alarm. A "before already past" warning shows when it applies.
    - Buttons: Remove (existing action), Cancel, and an accent **Place here** (new) or **Done** (existing). These change the draft; Save stores it.
    - After Place here or Done, the card flashes.

### Alarms in landscape (app)
From the "Landscape app" mockups (split variant). Shown when the window is wider than high; same draft, tabs, modes and Save rules as the portrait detail, without the morph.
- **Layout:** the rail, a 220 wide list, and the open alarm filling the rest. The first alarm opens by itself; one opened in portrait stays open. Picking another alarm or + with unsaved changes asks "Discard changes?". Back closes the time page or leaves pick/place mode, nothing else.
- **List:** "Alarms" (20 / 600), sync and a 44 `soft` + (a new simple alarm; actions come from the Timeline tab). Banners and the sync line above compact cards (radius 14, 1px `line`; the open one `accent` border on `surface`): the 24 / 300 time with the switch, "label · days", skipped / not synced flags, the mini timeline and the Skip next / Dismiss now pills. Off alarms at 50 %.
- **Detail header:** the 48 / 300 time and "label · days · tap the time to change it" (tap: time page); on the right the Timeline | Wake-up control, Save and ⋯. Wake-up is two columns (Sound and Snooze | Sunrise); name and repeat are on the time page.
- **Timeline:** a horizontal axis over the same range as portrait, ticks every major/minor step, clock labels under it where they fit, and a 2px accent alarm line with its time (bold, accent). Actions are 40 round icons (1.5px `line`, `surface`; selected `accent` on `accent-soft`) on stems: two lanes above the axis and one below the labels, a mark taking the first lane with 44 free to its left. After-dismiss actions are chips in an "After you dismiss" row under it. A simple alarm reads "Simple alarm. Add an action to build a timeline around it."
- **Bottom bar** (min 64, `line` top border): view: Add action and the hint, or the selected action (icon, "name · 06:15", "description · −15 min") with Remove, Move and ×. Pick: script chips and Cancel. Place: a row with Around the alarm | After dismiss, Run now and On; then ‹ › (1 min), "name · 06:15, −15 min" with "Drag along the line, or tap a spot", Cancel and Place here / Done. Tap or drag on the axis snaps to 5 min; a dashed accent guide, a 44 accent icon and the clock label follow (120 ms), other marks dim to 35 %.
- **Time page** (whole screen, rail hidden): "‹ Alarms / Workday / Time"; hour and minute drums (132 × 176, `surface`, radius 20; previous / current 84 / 200 / next) that step by dragging 28 or with the arrows, wrapping, minutes by 1; the new value slides in 34 over 260 ms. On the right Repeat (day toggles), Name, "Your 6 actions move with the alarm" with the first and last time, and Cancel / "Done · 06:30". Changes reach the draft only on Done.

### Night display (app)
From the "Sunrise Alarm — Night Display" mockups. A landscape layout on an 844 × 390 frame, scaled to the screen and centred on black; sizes in this section are frame units and ignore the font scale. The screen stays on, the system bars are hidden and the window brightness is 2 %.

| Token | Value | Use |
|---|---|---|
| `glow` | `#E08C45` | The clock |
| `ink` | `#E6B183` | Text |
| `muted` | `#AD8059` | Secondary text, action dots |
| `line` | `#3A2816` | Borders, the timeline |
| `soft` | `#120C06` | Dimmer on, battery track |
| `accent` | `#F2994A` | Alarm dot and time, switches on, Done, Stop |
| `on-accent` | `#1A0E04` | Text on accent |
| `off` / `knob` | `#3A2A1C` / `#8C6B4E` | Switch off |

Brightness (remembered) is **Normal** (content at 90 %), **Dim** (45 %) or **Superdim**. Superdim applies to the resting clock only: the window goes to the lowest brightness, content is at 75 %, the clock is `#7A1E12` at weight 100 and the alarm line `#5A170E`, and the action line and hint are left out. Widgets, the checklist and Display show at normal brightness; with Superdim the widgets hide by themselves after 30 s without a touch.
- **Resting:** the clock (S 64, M 96, L 132 or XL 168 (default) / 200, −0.045em, no AM/PM); the clock, the alarm line (20 below it) and the action line (56 below that) are centred together vertically. Below the clock a bell and "Alarm 06:30 · in 7 h 12 min" (16, `muted`), "Snoozed until 06:39", or "Skipped · next alarm Sat 08:15". Under that, when the next alarm has actions, a 400 wide line with its actions as 6 dots and the alarm as an 11 accent dot (same range as the mini timeline), labelled with the first action, the alarm (600, accent) and the last action; labels closer than 15 % to the alarm are left out. The line is at 30 % while the alarm is skipped. Top right: "Tap or swipe up for widgets" (12, 75 %). The resting content moves by up to 10 × 6 every 3 minutes against burn-in.
- **Screen off until tapped** (Display setting): the resting screen is black but for a tiny bell and the next ring time (11, `#6E4A2A`, bottom right, 22 / 18 in). A tap shows the clock for 5 s (fade in 400 ms, out 1.4 s) with a 200 × 2 `line` bar under it running out; a tap while it shows, or a swipe up, opens the widgets.
- **Widgets** (tap or swipe up; tap or swipe down hides them): the clock shrinks to 77.5 high and moves to the top left (600 ms, ease-soft). Under it the alarm line (14, `ink`) and "Charging · 82%". Bottom left the "Do not disturb" pill (bell, y 264; "Do not disturb on" with a bell-off icon and `soft` fill) above the brightness pill (eye; "Normal", "Dim" or "Superdim" with `soft` fill; a tap steps Normal → Dim → Superdim → Normal); top right "Checklist" (when one is chosen), "Edit widgets" and "Hide" (moon icon). Do not disturb uses the phone's own priority rules, so alarms still ring; the first tap opens the system screen that grants the app access. The cards (204 × 288, radius 20, 1px `line`, title 11 / 600 uppercase) scroll sideways from x = 280 and rise 16 → 0 after 150 ms:
  - *Bedroom* (always first, 330 wide): a bar (radius 26, 1px `line`, `accent` while sliding) that fills from the bottom: **Night lamp** (lamp icon, fill `#6E3F17` at 45–100 % with the brightness, 5 % steps, "Off" at 0; a switch is Off / On) ; beside it **Heating** shows three presets (below). The value (28 / 200, `#FFE9D2`) sits at the top of the bar, the icon at the bottom, the name below it (13, `ink`); the fill ends in a 2px `accent` edge. Touch anywhere and slide up or down; the fill and number follow the finger with a soft tick per step, letting go sets it (no Save), a tap sets it where touched. The direction is decided after the touch slop: a sideways swipe scrolls the widgets and never changes a light. Without a thermostat, a chosen room temperature shows as a bar that does not slide. At the bottom, side by side (52 high, radius 26, border `#6A4A2C`, icon and label): **All lights off** turns off every light that is on (and the night lamp if it is a switch), then reads "Lights are off · Tap to undo" (`#1A1209`, `muted`) for 6 s; with a thermostat, **Heating off** sets it to off and reads "Heating is off · Tap to undo" for as long as it is off (a tap turns it on again in the mode it was in). **Heating**, beside the lamp bar when a thermostat is chosen, shows its title with what it does now ("Heating to 19.5°", "Now 19.5° · cooling to 17°", "Now 19.5° · holding" or "Off · now 19.5°", from the thermostat's target and the room temperature) and three presets stacked (radius 18, 1px `line`; the one at the thermostat's target `#2A1C10` with an `accent` border): Night (moon), Sleep (bed) and Comfort (house), each with its target in 26 / 200. They start at 17°, 18.5° and 19.5° and are changed in Settings → Night display → Heating presets. A tap sets the target, turning the thermostat on first when it is off. The card is 392 wide. Status entities show as one line beside the title ("Front door Locked · Window Closed").
  - *Weather* (title Today / Tomorrow / weekday): icon, "7° → 12°" (30 / 300), "Rain around 08:00" or the condition, then advice and sunrise / sunset (13, `muted`).
  - *Next alarm*: the actions around the alarm as "time name" rows with the alarm row in accent, how many run after dismiss, and a "Skip tomorrow" switch (repeating alarms). A skipped alarm stays in the card at 35 % with the switch on, so the skip can be undone.
  - *First up*: the first timed event of the day across the chosen calendars (up to six; time 30 / 300, title 15 / 500), its place and "N more after".
  - *Battery*: percentage, an 8 high bar and when it is full.
  - An HA widget without its entity says where to choose it, with a Settings pill.
- **Checklist** (mockup "Bedtime checklist"): one Home Assistant to-do list per phone, chosen in Settings → Daily checklist. Inside the night window (from 22:00) the screen opens on it instead of the resting clock while something is left to tick. The clock is small at the top left as with the widgets, under it the alarm line (14, `ink`) and "Your daily list from Home Assistant shows up here every night from 22:00." (13, `muted`). The panel (x 280, y 20, 540 × 350, radius 22, 1px `line`) has "Before bed" (18 / 600, `#FFE9D2`), "3 of 6 done · Bedtime in Home Assistant" (13, `muted`), a 3 high progress bar (`#1D140B`, `accent`) and the items in two columns (min 60 high, radius 16, border `line`, `#2A1C10` when ticked): a 26 box (radius 8, border `#6A4A2C`, `accent` with a tick when ticked), the item (15, `#FFE9D2`; `muted` and struck through when ticked) and "Runs Lock front door" or "Tap to check off" (12, `muted`). Ticking an item ticks it in Home Assistant (or only on the phone, a setting) and runs the automation or script chosen for it in Settings. All ticked: a moon, "All done. Sleep well." (20 / 500) and **Dim the screen** (accent) or **Show list again**. Dim the screen or a swipe down puts it away until 04:00; the "Checklist" pill with the widgets brings it back. It only opens by itself between the start of the night window and 04:00, never after the reset. With "Start fresh every day" the list is unticked once a day after 04:00.
- **Quick alarm** (widgets open, under the alarm line; 212 × 72, radius 36, 1px `accent`, a plus in a 50 circle, "Rings once"; mockup "Quick alarm"): a one-time alarm in two steps, saved like any alarm without repeat days, so it turns itself off after it rings. The top bar has the breadcrumb "‹ Night display / Quick alarm", the tabs **1 Time & sound** and **2 Actions · n**, and **Set for 07:00** (accent). *1 Time & sound:* the time (96 / 200), "in 8 h 12 min · tomorrow", "Near the end of sleep cycle 4, so easier to wake" (or "Between sleep cycles") and the plan ("05:30 Heating · 06:20 Lamp · 06:30 Alarm"); when an enabled repeating alarm rings within 3 hours of it, a switch "Skip Workday 06:30" skips that alarm's next ring too. Four tiles: Sunrise screen (on = 20 min before), Vibrate, Sound (the system picker) and Actions. Below, the next 13 hours in 5-minute steps from the next 5-minute mark, starting at 07:00: slide the handle; dots are ends of sleep cycles (15 minutes to fall asleep, then cycles of 90), a band shows the actions, and with a calendar chosen a marker and a dashed bracket show how long after waking up the next appointment starts (yellow within an hour, red when it starts before). *2 Actions:* a line from an hour before to half an hour after the alarm; tap a script below to put it on the line (15 minutes before), slide it along the line to move it, or use −5 / +5 or Take away. Set saves an alarm called "Quick alarm" and the screen reads "Quick alarm set" with Undo (deletes it and undoes the skip) and Back to the clock.
- **Display** (Edit widgets → Display): "Done" (accent) top right goes back. A 380 wide column: "Night display" (18 / 600) and three choices, each an uppercase title (12 / 600, `muted`), a row of options in a 1px `line` box (radius 16, padding 4; options 44 high, radius 12, 14 / 600, selected `#2A1C10` and `#FFE9D2`, else `muted`) and a help line (12, `muted`): Clock size S / M / L / XL, Screen Always on / Off until tapped, Brightness Normal / Dim / Superdim. Right of it "Preview · tap it", the night display at 410 × 190 (radius 22, 6px `#1C140D` frame) with the chosen size and colours (Dim `#8A5A2E` / `#6E4A2A`), tapping it peeks for 3 s when the screen is off, and a summary line ("Screen stays dark. Clock size L, dim.").
- **Edit widgets:** "Display" and "Done" (accent) top right. A "Start by itself" tile ("When the phone charges on its side between 22:00 and 07:00…"), the intro line, and two columns of widget tiles (icon, name, description, switch; off tiles at 55 % without a border). A last line points to Settings for the entities.
- **Sunrise** (an alarm with a sunrise, night display open): over the chosen minutes before the alarm the background and text follow the keyframes 0 % `#000000` / `#E6B183`, 35 % `#2E1607` / `#F2C79C`, 55 % `#C77632` / `#FFF3E6`, 62 % text `#2A1A0E`, 78 % `#FFDDB5`, 100 % `#FFFDF8` / `#2A1A0E`, and the window brightness rises from 2 % to 100 % (quadratic). A sunrise overrides Superdim, Off until tapped and the checklist.

### Settings (app)
From the "App settings" mockups (cards variant). Only settings with a backing in the data model are shown; there is no theme toggle.
- **Home (portrait):** "Settings" (32 / 600), a search field (48 high, radius 14, `soft`, "Search, for example “sound”"), then category cards two to a row (min 124, radius 18, 1px `line`, `surface`): a 36 `accent-soft` tile with the `accent` icon, the name (15 / 600) and a summary of the current values (12, `muted`). An odd last card spans both columns.
- **Categories:** *Alarms* (Sound: Default sound, Gradually louder), *Night display* (Starting: Start by itself, From, Until; Screen: Open night display, Daily checklist; Widgets: the Home Assistant entities, up to three morning buttons (scripts) and the heating presets, with a note), *Home Assistant* (Connection: user, addresses, time zone, last sync; Account: Log in again, Log out) and *About & help* (Reliability: Health check; About: Version, Check for updates, Phone id).
- **Category page:** a breadcrumb "‹ Settings / Night display" (back returns to the cards), an intro line (14, `muted`), then groups: an uppercase title (12 / 600, `muted`) above a `surface` card (radius 16, 1px `line`) with rows of at least 52, divided by `line`. Rows are a switch, a value with a chevron (opens its picker or screen), an action (`accent`, Log out in `danger`) or plain info.
- **Search:** while there is a query, "3 settings found" (or "Nothing found. Try another word.") and the matching rows (label, "Category › Group", value) replace the cards; it matches label, value, category and group. A tap opens the category with that row on `accent-soft`. Back clears the query first.
- **Landscape:** the rail, then a 270 wide column with "Settings" (20 / 600), the search and the categories (52 high: icon, name, one-line summary; the selected one on `soft` with an `accent` icon), then the selected category's page (title, intro, groups) filling the rest. The first category is selected at first; no cards and no breadcrumb. Search results take the place of the category list.

### Daily checklist settings (app)
Settings → Night display → **Daily checklist**, from the mockup "Checklist settings in the app": a breadcrumb "‹ Settings / Daily checklist", then a `surface` card with the home icon (`accent`), "Home Assistant" and "● Connected · homeassistant.local" (or "Log in again", `warn`) and **Change** (login). "Your daily list": the to-do lists (`todo.*`) as radio rows (min 54, radius 14, border `accent` and `surface` fill when chosen) with "3 to do · todo.bedtime". "In this list" with "Edit in Home Assistant ↗" (opens the list in HA): the items (48 high, a 16 box, line between) with what ticking them runs, or "+ Action" (`accent`); a tap picks an automation or script (or Clear). Then three switch cards: Show on the night display, Start fresh every day (untick at 04:00), Tick off in Home Assistant too (off: ticks stay on the phone).

### Wake-up screen (app)
- **Ringing:** the time (120 or 26 % of the width, 200) and the label or "Good morning" in the middle; a chevron and "Swipe up to snooze 9 min" that nudges up 6 every 2.4 s (tapping it also snoozes); bottom left the status ("Brightening like a sunrise"), bottom right **Stop** (accent pill). A swipe up of 60 snoozes. With a sunrise the screen runs the keyframes above over 30 s, or starts bright when the night display already showed them; without one it is black with `ink` text.
- **Snoozed here:** background `#120904`, brightness 5 %, "Snoozing until 06:39", "Swipe up to snooze again" (adds one more snooze length) and "Dimmed while you snooze". Stop dismisses the snoozed alarm.
- **After Stop** (mockup "Morning screen", portrait and landscape): the date and "alarm off at 06:31", the weather now (icon, temperature 92 / 200, the condition, "Rain 08:00–10:00." in rain blue with advice, "Up to 12° · sunrise 08:02"; only "Good morning" without a weather entity), then today on one timeline from the hour of the alarm to 22:00: the weather by the hour on the left (icon, temperature, rain chance; shaded before sunrise and after sunset, a rain band, a line and dot for now) and the appointments of the chosen calendars beside it (time, title, place, the weather at that hour; the next one in `accent` with "in 54 min"). Tap an appointment and its hours get an `accent` frame in the weather column. In landscape the timeline runs sideways under the header (76 per hour, weather above, appointments below). Under it up to three buttons that run the scripts chosen in Settings → Night display → Morning buttons ("Tap to run", then "Started"), and **Done** (outline): "Have a good day" and "Next alarm Fri 06:30" for 4 seconds, then the screen closes (Back stays). Light (`#FFFDF8`, ink `#2A1A0E`, muted `#6B5644`) after a sunrise, the night palette otherwise. It also closes by itself after 10 minutes.

## Icon

A rising sun with twin bells and clock hands. The source is `docs/brand/icon.svg` (100×100 viewBox, rounded square, radius 22.5%).

| Palette | bg | ink | accent |
|---|---|---|---|
| Light (launcher, add-on store) | `#F5F5F2` | `#151515` | `#E0701C` |
| Dark | `#111114` | `#F1F0EC` | `#FFAD5C` |

- **Android:** an adaptive icon. The background is the light `bg`. The foreground is the 100-unit artwork scaled 0.72 into the 72dp visible area (`translate 18, scale 0.72`). The monochrome layer cuts the hands out of the sun.
- **Notification icon:** the white silhouette of sun, horizon and bells.
- **Add-ons:** `icon.png` (128×128) and `logo.png` (250×100, icon and "Alarm Actions") for both add-ons.
- **Panel:** the SVG favicon.
