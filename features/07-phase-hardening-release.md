# Phase 7: Hardening and Release

**Goal:** A stable, documented v1.0.0.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 7.1 | Final access check: matrix tests green, no route reachable without login, no webhooks or public URLs | SEC-01, 03, 11 | Verified against analysis sections 13 and 14 |
| 7.2 | Manual test with two users: user B cannot see or change user A's alarms, using the panel and the app through the public URL | SEC-03 | Test notes recorded |
| 7.3 | Safety scenarios: restart during a heating window, crash right after a script call, duplicate dismissals, time zone change, overlapping occurrences | SEC-05, TR-QLT-02, TR-SCH-06 | Exactly-once verified |
| 7.4 | Test coverage review | TR-QLT-01, 02 | Target met (suggested 80%) |
| 7.5 | Compatibility on current and previous HA release | TR-PLT-03 | CI matrix green |
| 7.6 | Performance check: 50 alarms with 20 actions each | SEC-05 | No event-loop blocking |
| 7.7 | User docs: install, script labeling, internet-use guidance, examples | README, SEC-10 | Fresh user completes quick start |
| 7.8 | Screenshots and example scripts (heating with auto-off) | README | Added |
| 7.9 | Changelog and release notes | TR-QLT-05 | Present |
| 7.10 | End-to-end test on a real phone: install the APK from the Install tab, set an alarm with a before and an after-dismissal action, verify both run | FR-APP-01, AFR-RNG-01, FR-EXE-02 | Both scripts ran at the right time |
| 7.10a | Away test on a real phone: switch the phone to another time zone, sync; alarm rings at local time and HA runs no actions | FR-EXE-12, AFR-RNG-08 | Records show "user away"; normal again after switching back |
| 7.11 | Release includes a signed APK and checksum; HACS installs it from the single repository link | TR-APP-01, 02 | Fresh HA install works |
| 7.12 | Tag `v1.0.0` | TR-PLT-02 | HACS installs it |

## Backlog (post v1)
- Conditions per action (only if home, only on weekdays), FR-ACT-08, 09
- Shared household alarms, FR-ACC-03
- Custom action label name, FR-SET-03
- Additional languages
