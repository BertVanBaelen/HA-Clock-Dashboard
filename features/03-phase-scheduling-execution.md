# Phase 3: Scheduling and Execution

**Goal:** Actions run in Home Assistant at the right time, independent of any phone.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 3.1 | Scheduler: planned runs for `before`, `at`, `after` | FR-EXE-01, TR-SCH-02, 03, 06 | Scripts start at planned time |
| 3.2 | Reschedule on every relevant change | TR-SCH-04 | Edits take effect without restart |
| 3.3 | Executor with label re-check and error isolation | TR-EXE-01..03, FR-ACT-07 | Failed action does not block others |
| 3.4 | Skip next occurrence handling and flag reset | FR-ALM-05, FR-EXE-04 | Skipped occurrence runs nothing |
| 3.5 | One-time alarm auto-disable | FR-ALM-06 | Alarm disabled after it fires |
| 3.6 | Restart recovery with grace period | FR-EXE-05, TR-SCH-05 | Late runs executed or marked `missed` |
| 3.7 | Execution records (storage, cap, retention) | FR-EXE-06, TR-DAT-03 | Each run produces a record |
| 3.8 | Exactly-once guarantee | TR-SCH-06 | Restart does not re-run a finished action |
| 3.9 | Scheduler test suite | TR-QLT-02 | DST, midnight, restart, skip covered |
| 3.10 | "Run now" test endpoint | FR-EXE-08 | Action can be tested manually |
| 3.11 | Executor passes no parameters; only `script.turn_on` for label-gated scripts | SEC-04, 02, SEC-11 | Label-gate tests pass |
| 3.12 | Script-start rate limit and duplicate suppression | FR-EXE-10, SEC-05, SEC-11 | Flood test stays within limit |
| 3.13 | Owner validity check at execution; alarm disabled if owner inactive | FR-ACC-04, SEC-03 | Test with deleted user |
| 3.14 | "Before" action already past on save: skip, record `missed` | FR-EXE-09 | Verified, warning flag returned by API |
| 3.15 | Bounded next-occurrence calculation | SEC-05 | No unbounded loops, DST tests |

**Exit criteria:** An alarm with a *before*, *at*, and *after* action runs each at the right moment, including across a HA restart.
