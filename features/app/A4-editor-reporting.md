# App Phase A4: Editor and Event Reporting

**Goal:** The app can do everything the panel can, and reports dismiss/snooze so "after dismissal" actions work.

Depends on A2 and A3.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| A4.1 | Alarm list and create/edit screen (time, label, days, one-time) | AFR-ALM-01 | Alarms saved to the server and local copy |
| A4.2 | Action editor: picker from server list, timing, offset | AFR-ALM-02 | Same result as the panel |
| A4.3 | Skip next occurrence | AFR-ALM-01 | State matches the panel |
| A4.4 | Warning when a *before* action will be skipped | AFR-ALM-03 | Warning shown before save |
| A4.5 | Report dismissed/snoozed/timed-out with occurrence ids; offline queue with retry | AFR-SYN-03, ATR-SYN-01 | Server runs the "after dismissal" action after a real dismissal |
| A4.6 | Offline edit queue and conflict screen | AFR-SYN-02 | *Should have* |
| A4.7 | Night display (bedside clock, next alarm, dimmed, optional auto-start while charging) | AFR-NGT-01, ATR-NGT-01 | *Should have*; a ringing alarm takes over the screen |

**Exit criteria:** dismissing the alarm on the phone starts the "after dismissal" script in Home Assistant, also when the phone had no network at the moment of dismissal.
