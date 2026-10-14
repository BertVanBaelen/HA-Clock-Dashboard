# Phase 7: Hardening and Release

**Goal:** A stable, documented v1.0.0.

**Objects and components:** [Dashboard add-on](../analysis/components/dashboard-addon.md), [App add-on](../analysis/components/app-addon.md), [Android app](../analysis/components/android-app.md), [Settings](../analysis/objects/settings.md)

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 7.1 | Final access check: matrix tests green on both listeners, no route reachable without ingress identity or a valid HA token, no host port mapped by default, no webhooks; external API off by default | SEC-01, 02, 03, 11, 12, 13 | Verified against analysis sections 13 and 14 |
| 7.2 | Manual test with two users: user B cannot see or change user A's alarms, using the panel and the app through the public URL | SEC-03 | Test notes recorded |
| 7.2a | Away-from-home test through the Cloudflare tunnel on mobile data: create an alarm with a script action (e.g. one that activates a scene), check it runs; check that responses are not cached and that requests without a token, with a revoked token, or to other paths are rejected | FR-APP-07, AFR-LOG-03, SEC-13 | Test notes recorded |
| 7.3 | Safety scenarios: restart during a heating window, crash right after a script call, duplicate dismissals, time zone change, overlapping occurrences | SEC-05, TR-QLT-02, TR-SCH-06 | Exactly-once verified |
| 7.4 | Test coverage review | TR-QLT-01, 02 | Target met (suggested 80%) |
| 7.5 | Compatibility on current and previous HA Core release, on HA OS (aarch64, e.g. Raspberry Pi) and amd64 | TR-PLT-03, 06 | Manual test notes for both architectures |
| 7.5a | HA backup and restore during the night: alarms keep running during a hot backup; restore brings back all data | TR-DAT-07 | Verified |
| 7.6 | Performance check: 50 alarms with 20 actions each | SEC-05 | No event-loop blocking |
| 7.7 | User docs: install (Add-on Store repository), script labeling, add-on options, internet-use guidance, examples; `DOCS.md` for both add-ons | README, SEC-10, TR-PLT-08 | Fresh user completes quick start |
| 7.8 | Screenshots and example scripts (heating with auto-off) | README | Added |
| 7.9 | Changelog and release notes | TR-QLT-05 | Present |
| 7.10 | End-to-end test on a real phone: install the APK from the Install tab, set an alarm with a before and an after-dismissal action, verify both run | FR-APP-01, AFR-RNG-01, FR-EXE-02 | Both scripts ran at the right time |
| 7.10a | Away test on a real phone: switch the phone to another time zone, sync; alarm rings at local time and HA runs no actions | FR-EXE-12, AFR-RNG-08 | Records show "user away"; normal again after switching back |
| 7.11 | Release: both images on GHCR before the version bump; the app add-on contains the signed APK and checksum; a fresh HA OS install works from the single repository URL | TR-APP-01, 02, TR-PLT-10 | Fresh HA install works |
| 7.12 | Release `1.0.0` | TR-PLT-02 | Add-on Store offers the update for both add-ons |

## Backlog (post v1)
- Conditions per action (only if home, only on weekdays), FR-ACT-08, 09
- Shared household alarms, FR-ACC-03
- Custom action label name, FR-SET-03
- Additional languages
