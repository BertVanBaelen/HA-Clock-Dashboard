# App Phase A3: Alarm Engine

**Goal:** A reliable full-screen alarm that rings with no network and through Do Not Disturb. This is the highest-risk part, so it starts as soon as A1 is done, in parallel with A2, using locally created test alarms.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| A3.1 | Exact scheduling with `setAlarmClock` from the local database | AFR-RNG-01, ATR-RNG-01 | Alarm fires on time, also in Doze |
| A3.2 | Foreground service playing on the alarm stream | AFR-RNG-03, ATR-RNG-02 | Rings through Do Not Disturb |
| A3.3 | Full-screen ringing screen over the lock screen | AFR-RNG-02, ATR-RNG-03 | Screen turns on and shows over the lock screen |
| A3.4 | Snooze (per-alarm duration or default) and stop | AFR-RNG-04 | Snooze re-rings, stop ends the service |
| A3.5 | Reschedule after reboot, time change, time zone change, app update | AFR-RNG-06, ATR-RNG-04 | Verified for each trigger |
| A3.5a | Away mode: ring at local wall-clock time in a foreign time zone, show "actions paused", send no reports | AFR-RNG-08 | Verified by changing the phone's time zone |
| A3.6 | First-run permission guide (exact alarm, full-screen intent, notifications, battery, install unknown apps) | AFR-SET-01, 05, ATR-RNG-07 | App guides through each permission |
| A3.7 | Maximum ringing duration guard, reported as `timed_out` | ATR-RNG-06 | Alarm stops after the limit |
| A3.8 | Alarm sound picker and optional volume ramp | AFR-RNG-05 | Sound selectable |
| A3.9 | Missed-alarm message on next start | AFR-RNG-07 | *Should have* |
| A3.10 | Health check screen (permissions, battery restrictions, DND) | AFR-SET-04 | *Should have* |

**Exit criteria:** with airplane mode on, the phone locked and Do Not Disturb enabled, the alarm rings full screen at the right time.
