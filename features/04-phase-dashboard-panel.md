# Phase 4: Dashboard Panel

**Goal:** A complete, mobile-friendly UI in the HA sidebar.

| # | Feature | Requirements | Done when |
|---|---|---|---|
| 4.1 | Panel registration, static files, build pipeline | TR-UI-01, 03, 04 | "Alarms" appears in the sidebar |
| 4.2 | Alarm list with toggle, time, days, next trigger | FR-UI-01, FR-ALM-07 | List renders live data |
| 4.3 | Create/edit dialog (time, label, days, one-time) | FR-UI-02, FR-ALM-01, 02 | Alarm saved via API |
| 4.4 | Action editor: picker, timing, offset | FR-UI-02, 03, FR-ACT-02..04 | Actions saved with correct timing |
| 4.5 | Per-action enable/disable and duplicates | FR-ACT-05, 06 | Works in UI |
| 4.6 | Skip next / undo skip | FR-ALM-05 | Button with visual state |
| 4.7 | Unavailable-script warning | FR-ACT-07 | Warning shown for broken actions |
| 4.8 | Conflict handling (409) | TR-API-02 | User is prompted to refresh |
| 4.9 | Theming and responsive layout | FR-UI-05, 06 | Verified in desktop and Companion App |
| 4.10 | Settings view (admin) | FR-SET-01, 02 | Values editable |
| 4.11 | Alarm duplication | FR-ALM-08 | *Could have* |
| 4.12 | Warning in the editor when a *before* action will be skipped | FR-UI-09, FR-EXE-09 | Warning shown before save |
| 4.13 | User text rendered safely (no unsafe HTML) | SEC-07 | Reviewed |
| 4.14 | No external requests; panel bundle contains no data, only UI code | SEC-07, SEC-08 | Verified in network tab and bundle |
| 4.16 | "Install app" tab placeholder wired to `GET /app/info` (full feature in phase 6) | FR-UI-07, TR-UI-06 | Tab hidden when no APK |

**Exit criteria:** A non-technical household member can create an alarm with actions on a phone, without touching YAML.
