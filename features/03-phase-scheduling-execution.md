# Phase 3: Scheduling and Execution

**Goal:** Actions run in Home Assistant at the right time, independent of any phone.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 3.1 | Scheduler: planned runs for `before`, `at`, `after`, tracked per occurrence (several active occurrences per alarm) | FR-EXE-01, TR-SCH-02, 03, 06 | Scripts start at planned time; overlapping occurrences tested |
| 3.2 | Reschedule on every relevant change; edits during an active occurrence keep its remaining runs | TR-SCH-04, FR-EXE-14 | Edits take effect without restart |
| 3.3 | Executor with label re-check and error isolation | TR-EXE-01..03, FR-ACT-07 | Failed action does not block others |
| 3.4 | Skip next occurrence handling and flag reset (system change bumps `change_seq`) | FR-ALM-05, FR-EXE-04 | Skipped occurrence runs nothing |
| 3.5 | One-time alarm auto-disable without cancelling the fired occurrence | FR-ALM-06 | Alarm disabled after it fires; its `after` runs still happen |
| 3.6 | Restart recovery with grace period | FR-EXE-05, TR-SCH-05 | Late runs executed or marked `missed` |
| 3.7 | Execution records (storage, per-user cap, retention; no records for disabled actions) | FR-EXE-06, TR-DAT-03 | Each run produces a record |
| 3.8 | Exactly-once guarantee with write-ahead `running` record | TR-SCH-06 | Restart (also a crash right after the script call) does not re-run a finished action |
| 3.9 | Scheduler test suite | TR-QLT-02 | DST, midnight, restart, skip covered |
| 3.10 | "Run now" test endpoint | FR-EXE-08 | Action can be tested manually |
| 3.11 | Executor passes no parameters; only `script.turn_on` for label-gated scripts | SEC-04, 02, SEC-11 | Label-gate tests pass |
| 3.12 | Script-start rate limit (excess dropped, logged `skipped`) and duplicate suppression | FR-EXE-10, SEC-05, SEC-11, D21 | Flood test stays within limit; dropped starts recorded |
| 3.13 | Owner validity check at execution; alarm disabled if owner inactive and stays disabled after reactivation | FR-ACC-04, SEC-03, D24 | Test with deleted and reactivated user |
| 3.14 | "Before" action already past on save: skip, record `missed` | FR-EXE-09 | Verified, warning flag returned by API |
| 3.15 | Bounded next-occurrence calculation | SEC-05 | No unbounded loops, DST tests |
| 3.16 | Core unavailable at run time: retry until the grace period ends, then `missed`; recalculate on reconnect | TR-HA-01, 05, TR-SCH-04 | Test with fake Core going down around a planned run |
| 3.17 | Add-on restart (e.g. after saving options) within the grace period runs due actions once | TR-SCH-05, D30 | Restart test passes |

**Exit criteria:** An alarm with a *before*, *at*, and *after* action runs each at the right moment, including across an add-on restart and a HA Core restart.
