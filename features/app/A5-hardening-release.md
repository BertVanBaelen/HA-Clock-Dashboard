# App Phase A5: Hardening and Release

**Goal:** The alarm can be trusted to wake you.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| A5.1 | Device test run on several Android versions and manufacturers with aggressive battery management | ATR-QLT-02 | Results recorded, workarounds documented |
| A5.2 | Unit tests: schedule calculation (DST, weekdays, skip), sync merge | ATR-QLT-01 | Tests green in CI |
| A5.3 | Update check against `GET /app/info` | AFR-SET-03 | App offers the download when newer |
| A5.4 | Security check: tokens, HTTPS only, backup exclusion, no secrets in APK | ASEC-01..07 | Checklist signed off |
| A5.5 | Two-week real-life trial on your own phone | all RNG | No missed alarms |
| A5.6 | User guide: install, permissions, troubleshooting | README | Added |

**Exit criteria:** you used it as your only alarm for two weeks without a miss.
