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
- **Header:** the 56/300 time (tap: time picker). On the right are the label and the days (tap: edit the name).
- **Chips:** Repeat (the days), Snooze ("Snooze 9 min", or "Snooze default"), and in the app Sound (the ringtone name), Vibrate and Sunrise. The Vibrate chip toggles directly between "Vibrate" (`soft` fill) and "No vibration" (no fill, `muted`). The Sunrise chip ("Sunrise 20 min" or "No sunrise") opens Off / 10 / 20 / 30 min. Sound, vibration and sunrise are stored on the phone only. A chip opens its inline editor right below the chips (rise); only one is open at a time.
  - Name: a text field.
  - Repeat: 7 day toggles, "Select no days for a one-time alarm".
  - Snooze: − value + and "Use default (N min)".
  - Sound: opens the system ringtone picker directly.
- Banners for conflict (Reload), saved with warnings, and errors sit under the header.
- **Simple view** (no actions, view mode): rows Name, Repeat, Snooze and Sound (app), each of which opens its editor inline, and in the app a Vibrate row with a switch and a Sunrise row. Below them is a dashed card: "+ Add actions". "Turn on lights, start coffee or play the news before, with or after this alarm. It stays a simple alarm until you do."
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
- **Bottom area** (separated by a `line` border):
  - View mode: a primary "＋ Add action" button and the hint "Tap an action to move or change it". While scripts load, or when none exist, show the loading text or the no-scripts text instead.
  - Pick mode: "What should happen?" with Cancel, and a horizontal row of script tiles (92 wide, ≥84 high, icon + name). Picking a script places it at −15 (before 15).
  - Place mode:
    - A segmented control: "Around the alarm" | "After dismiss".
    - Around the alarm: ▲ earlier (−1 min), a readout ("15 min before the alarm", "Together with the alarm", "5 min after the alarm"), and ▼ later (+1 min). The subline reads "<Script> runs at 06:15 · drag it on the timeline". Dragging on the timeline snaps to 5 min. The range is ±720.
    - After dismiss: ▲▼ change the delay (0-120). The readout is "Right when you dismiss" or "10 min after you dismiss".
    - A row with the Enabled switch, plus "Run now" for a saved action of a saved alarm. A "before already past" warning shows when it applies.
    - Buttons: Remove (existing action), Cancel, and an accent **Place here** (new) or **Done** (existing). These change the draft; Save stores it.
    - After Place here or Done, the card flashes.

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

Content is drawn at 90 % opacity, 45 % with the Dimmer on (remembered).
- **Resting:** the clock (176 / 200, −0.045em, no AM/PM) centred at the top. Below it a bell and "Alarm 06:30 · in 7 h 12 min" (16, `muted`), "Snoozed until 06:39", or "Skipped · next alarm Sat 08:15". Under that, when the next alarm has actions, a 400 wide line with its actions as 6 dots and the alarm as an 11 accent dot (same range as the mini timeline), labelled with the first action, the alarm (600, accent) and the last action; labels closer than 15 % to the alarm are left out. The line is at 30 % while the alarm is skipped. Top right: "Tap or swipe up for widgets" (12, 75 %). The resting content moves by up to 10 × 6 every 3 minutes against burn-in.
- **Widgets** (tap or swipe up; tap or swipe down hides them): the clock shrinks to 44 % and moves to the top left (600 ms, ease-soft). Under it the alarm line (14, `ink`) and "Charging · 82%". Bottom left the "Do not disturb" pill (bell, y 264; "Do not disturb on" with a bell-off icon and `soft` fill) above the Dimmer pill; top right "Edit widgets" and "Hide" (moon icon). Do not disturb uses the phone's own priority rules, so alarms still ring; the first tap opens the system screen that grants the app access. The cards (204 × 288, radius 20, 1px `line`, title 11 / 600 uppercase) scroll sideways from x = 280 and rise 16 → 0 after 150 ms:
  - *Bedroom* (always first, 420 wide): two rows to slide (76 high, radius 22, 1px `line`): **Night lamp** (lamp icon, fill `#6E3F17` at 45–100 % with the brightness, 5 % steps, "Off" at 0; a switch is Off / On) and **Heating** (thermometer, fill `#6E2A12`, the thermostat's target in its own steps, 0.5° by default, over 15–24 °C / 59–76 °F; the small line "Now 19.5° · slide to set" is the room temperature entity or the thermostat's reading). Name 15 / 500 and value 32 / 200 in `#FFE9D2`, hint 12 `ink`; the fill ends in a 2px `accent` edge. Touch anywhere and slide; the fill and number follow the finger with a soft tick per step, letting go sets it (no Save), a tap sets it where touched. Without a thermostat, a chosen room temperature shows as a row that does not slide. At the bottom **All lights off** (50 high, radius 25, border `#6A4A2C`, lamp-off icon): turns off every light that is on (and the night lamp if it is a switch), then reads "All lights off · tap to undo" (`#1A1209`, `muted`) for 6 s. Status entities show as one line beside the title ("Front door Locked · Window Closed").
  - *Weather* (title Today / Tomorrow / weekday): icon, "7° → 12°" (30 / 300), "Rain around 08:00" or the condition, then advice and sunrise / sunset (13, `muted`).
  - *Next alarm*: the actions around the alarm as "time name" rows with the alarm row in accent, how many run after dismiss, and a "Skip tomorrow" switch (repeating alarms). A skipped alarm stays in the card at 35 % with the switch on, so the skip can be undone.
  - *First up*: the first timed event of the day across the chosen calendars (up to six; time 30 / 300, title 15 / 500), its place and "N more after".
  - *Battery*: percentage, an 8 high bar and when it is full.
  - An HA widget without its entity says where to choose it, with a Settings pill.
- **Edit widgets:** "Done" (accent) top right. A "Start by itself" tile ("When the phone charges on its side between 22:00 and 07:00…"), the intro line, and two columns of widget tiles (icon, name, description, switch; off tiles at 55 % without a border). A last line points to Settings for the entities.
- **Sunrise** (an alarm with a sunrise, night display open): over the chosen minutes before the alarm the background and text follow the keyframes 0 % `#000000` / `#E6B183`, 35 % `#2E1607` / `#F2C79C`, 55 % `#C77632` / `#FFF3E6`, 62 % text `#2A1A0E`, 78 % `#FFDDB5`, 100 % `#FFFDF8` / `#2A1A0E`, and the window brightness rises from 2 % to 100 % (quadratic).

### Wake-up screen (app)
- **Ringing:** the time (120 or 26 % of the width, 200) and the label or "Good morning" in the middle; a chevron and "Swipe up to snooze 9 min" that nudges up 6 every 2.4 s (tapping it also snoozes); bottom left the status ("Brightening like a sunrise"), bottom right **Stop** (accent pill). A swipe up of 60 snoozes. With a sunrise the screen runs the keyframes above over 30 s, or starts bright when the night display already showed them; without one it is black with `ink` text.
- **Snoozed here:** background `#120904`, brightness 5 %, "Snoozing until 06:39", "Swipe up to snooze again" (adds one more snooze length) and "Dimmed while you snooze". Stop dismisses the snoozed alarm.
- **After Stop:** "Good morning" (24 / 600), the date and "alarm off at 06:31", and a Done pill. With weather: the icon, the temperature (76 / 200), "Cloudy, rain from 08:00", high / low / feels like, advice in a `soft` box, and now plus six hours (time, icon, temperature, a rain bar up to 40 high, the chance in rain blue `#3F6A94`). Chips for sunrise, sunset, wind and the after-dismiss actions ("Coffee · now"). Light (`#FFFDF8`, ink `#2A1A0E`, muted `#6B5644`) after a sunrise, the night palette otherwise. It closes by itself after 10 minutes.

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
