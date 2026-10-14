# App Phase A2: Login, API Client and Sync

**Goal:** The user can log in and see their alarms, stored locally.

Depends on dashboard phases 2 and 6.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| A2.1 | HA URL entry and login via HA OAuth flow | AFR-LOG-01, ASEC-01 | Login works through the public URL |
| A2.2 | Token storage in Keystore-backed encrypted storage; logout wipes data | AFR-LOG-02, ASEC-01, 06 | Verified, no tokens in logs |
| A2.3 | API client with HTTPS-only and API version check | ATR-SYN-03, AFR-SET-02 | Incompatible dashboard shows a clear message |
| A2.4 | Local database (Room) for alarms and sync state | ATR-RNG-05 | Alarms stored locally |
| A2.5 | Sync on open and "sync now"; shows sync state | AFR-SYN-01, 04, ATR-SYN-02 | Alarms match the server |
| A2.6 | Background sync with WorkManager | AFR-SYN-01 | Syncs while the app is closed |

**Exit criteria:** an alarm created in the panel appears in the app after sync, and the app never needs a second login.
