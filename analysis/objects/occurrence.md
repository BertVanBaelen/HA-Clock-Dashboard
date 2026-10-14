# Occurrence

**Purpose:** One concrete instance of an [Alarm](alarm.md) at a specific date and time. The unit to which skip, snooze, dismiss and [planned runs](planned-run.md) attach.

## Attributes
| Attribute | Type | Notes |
|---|---|---|
| `id` | string | `<alarm_id>_<YYYYMMDDTHHMMSSZ>` (scheduled time in UTC). Example: `6f0c1a52-…-2b1d6d6a9b10_20261015T044500Z` |
| `alarm_id` | UUID | |
| `scheduled_at` | datetime UTC | |
| `status` | enum | `pending`, `skipped`, `fired`, `snoozed`, `dismissed`, `expired` |

## State machine
```
pending ──skip──────────────────► skipped
pending ──dismissed event (early)► dismissed   (before/at/after runs cancelled)
pending ──time reached──────────► fired
fired ──snoozed event──────────► snoozed ──dismissed event──► dismissed
fired ──dismissed event─────────► dismissed
fired/snoozed ──timeout or timed_out event──► expired
```

## Rules
- Computed in the HA time zone. A local time that does not exist (spring forward) fires at the next valid moment; an ambiguous time (fall back) fires at its first occurrence.
- At most one occurrence per alarm is `pending` (the next one). Later ones are computed, not stored.
- An alarm can have **several active occurrences at once**: an earlier one can still be `fired`/`snoozed` (dismiss timeout up to 60 min) or have `after` runs pending (offset up to 720 min) while the next one's `before` runs are due. Planned runs are tracked per occurrence.
- Occurrences in a final state (`skipped`, `dismissed`, `expired`) are kept only for history.
- Early dismissal is allowed up to 60 minutes before `scheduled_at`; earlier than that, use skip.
- The deterministic id lets an offline app report events for an occurrence the server has not yet stored, as long as alarm and time match the server's calculation.

## Decisions
| Decision | Why |
|---|---|
| Occurrence id `<alarm_id>_<YYYYMMDDTHHMMSSZ>` | URL-safe and identical in Python, TypeScript and Kotlin; the app can compute it offline |
| Dismissal timeout is **60 minutes from the alarm time**, configurable | Covers long snooze chains; heating after that is pointless |
| Server and app use the same published test vectors (ids, DST, weekdays, skip) | Both sides must compute the same occurrences |

## Requirements and features
- Requirements: FR-ALM-05, 06, 07, FR-EXE-04; TR-SCH-01, 02, TR-API-04; ATR-SYN-01
- [Phase 2](../../features/02-phase-data-model-api.md): 2.8
- [Phase 3](../../features/03-phase-scheduling-execution.md): 3.1, 3.4, 3.5, 3.9
- [Phase 5](../../features/05-phase-dismiss-history.md): 5.1
- [Phase 6](../../features/06-phase-app-addon-sync.md): 6.9
- [App A3](../../features/app/A3-alarm-engine.md): A3.1, A3.5, A3.9