# App Phase A2: Login, API Client and Sync

**Goal:** The user can log in and see their alarms, stored locally.

Depends on dashboard phases 2 and 6; the spike A2.0 only needs dashboard phase 1.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| A2.0 | **Spike:** with a **non-admin** HA user's OAuth token, call `supervisor/api` `GET /addons/<slug>/info` and `POST /ingress/session` over the websocket, then reach the add-on through `/api/hassio_ingress/<entry>/` with the session cookie, from outside the home network | ATR-SYN-05, TR-APP-06 | Works for a non-admin user, or a documented alternative is decided before A2.1 |
| A2.1 | HA URL entry and login via HA OAuth flow, using the GitHub Pages `client_id` and custom-scheme redirect | AFR-LOG-01, ASEC-01, ATR-SYN-04 | Login works through the public URL |
| A2.1a | Ingress session handling: slug (default or from the Install tab QR code), session creation, renewal, retry on 401 | ATR-SYN-05, 06 | App keeps working after the ingress session expires |
| A2.2 | Token storage in Keystore-backed encrypted storage; logout wipes data | AFR-LOG-02, ASEC-01, 06 | Verified, no tokens in logs |
| A2.3 | API client with HTTPS-only and API version check | ATR-SYN-03, AFR-SET-02 | Incompatible dashboard shows a clear message |
| A2.4 | Local database (Room) for alarms and sync state | ATR-RNG-05 | Alarms stored locally |
| A2.5 | Sync on open, on time zone change and "sync now" with `change_seq`; registers client with time zone; shows sync state | AFR-SYN-01, 04, ATR-SYN-02 | Alarms match the server |
| A2.6 | Background sync with WorkManager | AFR-SYN-01 | Syncs while the app is closed |

**Exit criteria:** an alarm created in the panel appears in the app after sync, and the app never needs a second login.
