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
- **Alarm card:** a 44/300 time, the label in `muted`, then "days · summary". The summary is "2 before · 1 with · 1 after · 1 after dismiss", or "Simple alarm" when there are no actions. Below it is a status line: next ring, snoozed until, off, skipped, or not synced/not saved. Next comes the mini timeline (180 wide, only when there are around-the-alarm actions), then the switch on the right. An off alarm shows its content at 50% opacity. Small pill buttons under the content hold "Skip next"/"Undo skip" (repeating and on) and "Dismiss now" (within the hour before the ring, or while ringing).
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
- **Chips:** Repeat (the days), Snooze ("Snooze 9 min", or "Snooze default"), and in the app Sound (the ringtone name). A chip opens its inline editor right below the chips (rise); only one is open at a time.
  - Name: a text field.
  - Repeat: 7 day toggles, "Select no days for a one-time alarm".
  - Snooze: − value + and "Use default (N min)".
  - Sound: opens the system ringtone picker directly.
- Banners for conflict (Reload), saved with warnings, and errors sit under the header.
- **Simple view** (no actions, view mode): rows Name, Repeat, Snooze and Sound (app), each of which opens its editor inline. Below them is a dashed card: "+ Add actions". "Turn on lights, start coffee or play the news before, with or after this alarm. It stays a simple alarm until you do."
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
