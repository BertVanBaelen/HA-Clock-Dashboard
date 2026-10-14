# App Phase A2: Login, API Client and Sync

**Goal:** The user can log in and see their alarms, stored locally.

**Objects and components:** [Android app](../../analysis/components/android-app.md), [Client](../../analysis/objects/client.md), [Alarm](../../analysis/objects/alarm.md)

Depends on dashboard phases 2 and 6 (6.6, 6.6a for the external API); the spike A2.0 only needs dashboard phase 1 plus a test route.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| A2.0 | **Spike:** with a **non-admin** HA user's OAuth token, call a test route on the add-on's external listener through a Cloudflare tunnel (`https://api.vanbaelen.dev/clock/...`) from mobile data; the add-on validates the token with `auth/current_user` on `homeassistant:8123` | ATR-SYN-05, TR-APP-06, TR-API-12 | Non-admin user identified correctly; tunnel does not block, challenge or cache the call |
| A2.1 | Setup by scanning the Install tab QR code (or typing HA URL and API URL); login via HA OAuth flow, using the GitHub Pages `client_id` and custom-scheme redirect | AFR-LOG-01, ASEC-01, ATR-SYN-04, 06 | Login works through the public URL |
| A2.1a | Bearer token handling: proactive refresh, refresh and retry once on 401, re-login prompt when refresh fails | ATR-SYN-05, AFR-LOG-03 | App keeps working after the access token expires (30 min) and from mobile data |
| A2.2 | Token storage in Keystore-backed encrypted storage; logout wipes data | AFR-LOG-02, ASEC-01, 06 | Verified, no tokens in logs |
| A2.3 | API client with HTTPS-only and API version check | ATR-SYN-03, AFR-SET-02 | Incompatible dashboard shows a clear message |
| A2.4 | Local database (Room) for alarms and sync state | ATR-RNG-05 | Alarms stored locally |
| A2.5 | Sync on open, on time zone change and "sync now" with `change_seq`; registers client with time zone; shows sync state | AFR-SYN-01, 04, ATR-SYN-02 | Alarms match the server |
| A2.6 | Background sync with WorkManager | AFR-SYN-01 | Syncs while the app is closed |

**Exit criteria:** an alarm created in the panel appears in the app after sync, and the app never needs a second login.
